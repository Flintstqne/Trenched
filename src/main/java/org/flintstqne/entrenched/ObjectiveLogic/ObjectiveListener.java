package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.StatLogic.StatListener;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener for objective-related events.
 * Tracks block placement, destruction, and kills for objective progress.
 */
public class ObjectiveListener implements Listener {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final ObjectiveUIManager uiManager;
    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager config;

    private BukkitTask refreshTask;
    private BukkitTask holdGroundTask;
    private BukkitTask plantedExplosivesTask;
    private BukkitTask intelTask;
    private BukkitTask structureTask;
    private BukkitTask cleanupTask;
    private RoundService roundService;
    private StatListener statListener;

    // Building damage tracking - tracks who damaged a building (block break or TNT)
    // objectiveId -> Set of damagers (all get credit)
    public record BuildingDamageRecord(UUID damagerUuid, String damagerName, String damagerTeam, long timestamp) {}
    private final Map<Integer, Set<BuildingDamageRecord>> buildingDamageTracking = new ConcurrentHashMap<>();
    private static final long BUILDING_DAMAGE_ATTRIBUTION_WINDOW_MS = 60000; // 60 seconds

    // TNT placer tracking - attribute explosions to the player who placed TNT
    // "x,y,z" -> {placerUuid, placerName, placerTeam, timestamp}
    private final Map<String, BuildingDamageRecord> tntPlacerTracking = new ConcurrentHashMap<>();
    private static final long TNT_CHAIN_ATTRIBUTION_WINDOW_MS = 10000; // 10 seconds for chain reactions

    // Container item count tracking for stocking stat - "x,y,z" -> {playerUuid, itemCount}
    private record ContainerStockRecord(UUID playerUuid, String playerName, int itemCount) {}
    private final Map<String, ContainerStockRecord> containerStockTracking = new ConcurrentHashMap<>();
    private static final int CONTAINER_STOCKING_THRESHOLD = 500; // Items needed to qualify

    // Track players who have been credited for stocking a container (to handle unstocking)
    // "x,y,z" -> Set of player UUIDs who got credit
    private final Map<String, Set<UUID>> containerStockCredits = new ConcurrentHashMap<>();

    public ObjectiveListener(JavaPlugin plugin, ObjectiveService objectiveService,
                              ObjectiveUIManager uiManager, RegionService regionService,
                              TeamService teamService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.uiManager = uiManager;
        this.regionService = regionService;
        this.teamService = teamService;
        this.config = config;

        // Set up callbacks
        objectiveService.setCompletionCallback((objective, playerUuid, team) -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                uiManager.showCompletionNotification(player, objective);
            }
        });

        objectiveService.setSpawnCallback(uiManager::showSpawnNotification);
    }

    /**
     * Sets the round service for getting the game world.
     */
    public void setRoundService(RoundService roundService) {
        this.roundService = roundService;
    }

    /**
     * Sets the stat listener for tracking objective-related stats.
     */
    public void setStatListener(org.flintstqne.entrenched.StatLogic.StatListener statListener) {
        this.statListener = statListener;
    }

    /**
     * Starts the objective refresh task.
     */
    public void start() {
        // Refresh objectives periodically
        int refreshMinutes = config.getObjectiveRefreshMinutes();
        long refreshTicks = refreshMinutes * 60L * 20L;

        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin,
                objectiveService::refreshAllObjectives,
                refreshTicks, refreshTicks);

        // Initial spawn of objectives
        Bukkit.getScheduler().runTaskLater(plugin,
                objectiveService::refreshAllObjectives,
                100L); // 5 seconds after start

        // Start hold ground tick task (every second = 20 ticks)
        holdGroundTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::tickHoldGround,
                20L, 20L);

        // Start planted explosives tick task (every second = 20 ticks)
        plantedExplosivesTask = Bukkit.getScheduler().runTaskTimer(plugin,
                objectiveService::tickPlantedExplosives,
                20L, 20L);

        // Start intel tick task (every second = 20 ticks) - handles dropped intel timeout
        intelTask = Bukkit.getScheduler().runTaskTimer(plugin,
                objectiveService::tickIntelObjectives,
                20L, 20L);

        structureTask = Bukkit.getScheduler().runTaskTimer(plugin,
                objectiveService::tickStructureObjectives,
                20L, 20L);

        // Start cleanup task for memory management (every 30 seconds = 600 ticks)
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::cleanupOldTracking,
                600L, 600L);

        plugin.getLogger().info("[Objectives] Listener started, refresh every " + refreshMinutes + " minutes");
    }

    /**
     * Stops the objective tasks.
     */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (holdGroundTask != null) {
            holdGroundTask.cancel();
        }
        if (plantedExplosivesTask != null) {
            plantedExplosivesTask.cancel();
        }
        if (intelTask != null) {
            intelTask.cancel();
        }
        if (structureTask != null) {
            structureTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
    }

    /**
     * Ticks the hold ground objectives by checking all player positions.
     */
    private void tickHoldGround() {
        // Get game world
        World gameWorld = null;
        if (roundService != null) {
            gameWorld = roundService.getGameWorld().orElse(null);
        }
        if (gameWorld == null) return;

        // Collect player data
        Map<UUID, ObjectiveService.HoldGroundPlayerData> playerData = new HashMap<>();

        for (Player player : gameWorld.getPlayers()) {
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (teamOpt.isEmpty()) continue;

            String regionId = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );
            if (regionId == null) continue;

            playerData.put(player.getUniqueId(), new ObjectiveService.HoldGroundPlayerData(
                    regionId,
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ(),
                    teamOpt.get()
            ));
        }

        // Tick the objectives
        if (!playerData.isEmpty()) {
            objectiveService.tickHoldGroundObjectives(playerData);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = event.getBlock().getX();
        int y = event.getBlock().getY();
        int z = event.getBlock().getZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        String blockType = event.getBlock().getType().name();

        // Notify objective service
        objectiveService.onBlockDestroyed(player.getUniqueId(), team, regionId, x, y, z, blockType);

        // Check for TNT being defused (Plant Explosive objective)
        if (event.getBlock().getType() == Material.TNT) {
            Optional<ObjectiveService.PlantedExplosiveInfo> explosiveInfo =
                    objectiveService.getPlantedExplosiveInfo(regionId);
            if (explosiveInfo.isPresent()) {
                ObjectiveService.PlantedExplosiveInfo info = explosiveInfo.get();
                if (info.x() == x && info.y() == y && info.z() == z) {
                    // This is the planted TNT - check if defuser is defender
                    if (!info.planterTeam().equalsIgnoreCase(team)) {
                        // Defender defused the explosive!
                        objectiveService.onTntBroken(player.getUniqueId(), team, regionId, x, y, z);
                        player.sendMessage(config.getPrefix() + org.bukkit.ChatColor.GREEN +
                                "💣 Explosive defused! Attack thwarted!");
                        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);

                        // Record stat for TNT defusal
                        if (statListener != null) {
                            statListener.recordTntDefused(player.getUniqueId(), player.getName());
                        }
                    } else {
                        // Attacker broke their own TNT
                        objectiveService.onTntBroken(player.getUniqueId(), team, regionId, x, y, z);
                        player.sendMessage(config.getPrefix() + org.bukkit.ChatColor.YELLOW +
                                "💣 You removed your planted explosive.");
                    }
                }
            }
        }

        // Check if a container was broken - recalculate resource depot progress
        if (isStorageContainer(event.getBlock().getType())) {
            // Check if there's an active resource depot objective in this region
            boolean hasResourceDepotObjective = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)
                    .stream()
                    .anyMatch(obj -> obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT);

            if (hasResourceDepotObjective) {
                // Schedule a delayed task to recount (after this block is removed)
                World world = event.getBlock().getWorld();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    updateResourceDepotProgressAtLocation(player, team, regionId, world);
                }, 1L); // 1 tick delay to ensure block is removed
            }
        }

        // Track building damage for destruction attribution
        Optional<RegisteredBuilding> buildingOpt = objectiveService.getRegisteredBuildingAtLocation(x, y, z);
        if (buildingOpt.isPresent()) {
            RegisteredBuilding building = buildingOpt.get();
            // Only track if enemy is breaking the building
            if (!building.team().equalsIgnoreCase(team)) {
                recordBuildingDamage(building.objectiveId(), player.getUniqueId(), player.getName(), team);
                plugin.getLogger().fine("[Stats] Tracked block break damage by " + player.getName() +
                        " on " + building.type().getDisplayName() + " (obj " + building.objectiveId() + ")");
            }
        }
    }

    /**
     * Counts containers and items within a radius of a center point.
     * Used for Resource Depot objective tracking.
     *
     * Returns: [qualifyingContainers, totalContainers, totalItems, minItemsPerContainer]
     * A qualifying container is one that has at least minItemsPerContainer items.
     *
     * @param center The center location to search around
     * @param radius Horizontal search radius in blocks
     * @param verticalRange Vertical search range (up and down from center)
     * @param minItemsPerContainer Minimum items required for a container to count as "qualifying"
     * @return int array [qualifyingContainers, totalContainers, totalItems]
     */
    private int[] countContainersAndItemsInRadius(Location center, int radius, int verticalRange, int minItemsPerContainer) {
        if (center == null || center.getWorld() == null) return new int[]{0, 0, 0};

        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        int qualifyingContainers = 0; // Containers with >= minItemsPerContainer items
        int totalContainers = 0;
        int totalItems = 0;

        // Track which double chest inventories we've already counted
        // Use the left side location as the canonical key for double chests
        Set<String> countedDoubleChests = new HashSet<>();

        // Track single container locations to avoid duplicates
        Set<String> countedSingleContainers = new HashSet<>();

        // Search in the configured vertical range
        int minY = Math.max(world.getMinHeight(), centerY - verticalRange);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + verticalRange);

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);

                    if (!isStorageContainer(block.getType())) continue;

                    BlockState state = block.getState();
                    if (!(state instanceof Container container)) continue;

                    org.bukkit.inventory.Inventory inventory = container.getInventory();

                    // Handle double chests specially
                    if (inventory instanceof org.bukkit.inventory.DoubleChestInventory doubleChestInv) {
                        org.bukkit.block.DoubleChest doubleChest = (org.bukkit.block.DoubleChest) doubleChestInv.getHolder();
                        if (doubleChest != null) {
                            // Use the left side as canonical key for this double chest
                            Location leftLoc = ((org.bukkit.block.Chest) doubleChest.getLeftSide()).getLocation();
                            String doubleChestKey = leftLoc.getBlockX() + "," + leftLoc.getBlockY() + "," + leftLoc.getBlockZ();

                            // Only count if we haven't seen this double chest before
                            if (!countedDoubleChests.contains(doubleChestKey)) {
                                countedDoubleChests.add(doubleChestKey);
                                totalContainers++;

                                // Count items in the entire double chest inventory
                                int containerItems = 0;
                                for (ItemStack item : doubleChestInv.getContents()) {
                                    if (item != null && item.getType() != Material.AIR) {
                                        containerItems += item.getAmount();
                                    }
                                }
                                totalItems += containerItems;

                                // Check if this container qualifies (has enough items)
                                if (containerItems >= minItemsPerContainer) {
                                    qualifyingContainers++;
                                }
                            }
                        }
                    } else {
                        // Regular single container (barrel, shulker, single chest)
                        String locationKey = x + "," + y + "," + z;

                        if (!countedSingleContainers.contains(locationKey)) {
                            countedSingleContainers.add(locationKey);
                            totalContainers++;

                            // Count items
                            int containerItems = 0;
                            for (ItemStack item : inventory.getContents()) {
                                if (item != null && item.getType() != Material.AIR) {
                                    containerItems += item.getAmount();
                                }
                            }
                            totalItems += containerItems;

                            // Check if this container qualifies (has enough items)
                            if (containerItems >= minItemsPerContainer) {
                                qualifyingContainers++;
                            }
                        }
                    }
                }
            }
        }

        return new int[]{qualifyingContainers, totalContainers, totalItems};
    }

    /**
     * Gets the Resource Depot objective location for a region, if one exists.
     */
    private Optional<Location> getResourceDepotLocation(String regionId, World world) {
        for (RegionObjective obj : objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)) {
            if (obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT && obj.hasLocation()) {
                return Optional.ofNullable(obj.getLocation(world));
            }
        }
        return Optional.empty();
    }

    /**
     * Updates Resource Depot progress by scanning containers around the objective location.
     * Scans the full vertical range (all Y levels) within the horizontal radius.
     */
    private void updateResourceDepotProgressAtLocation(Player player, String team, String regionId, World world) {
        Optional<Location> depotLocOpt = getResourceDepotLocation(regionId, world);
        if (depotLocOpt.isEmpty()) {
            plugin.getLogger().fine("[ResourceDepot] No active depot objective with location in " + regionId);
            return;
        }

        Location depotLoc = depotLocOpt.get();

        // Get configurable search parameters
        int radius = config.getResourceDepotRadius();
        int minItemsPerContainer = config.getResourceDepotMinItemsPerContainer();

        // Use full world height for vertical scanning - resource depots can be built at any Y level
        // This ensures chests are found regardless of whether they're underground, on surface, or in the sky
        int fullVerticalRange = (world.getMaxHeight() - world.getMinHeight()) / 2;

        // Scan containers within the configured radius of the depot location (X/Z only, full Y range)
        // Returns: [qualifyingContainers, totalContainers, totalItems]
        int[] counts = countContainersAndItemsInRadius(depotLoc, radius, fullVerticalRange, minItemsPerContainer);

        int qualifyingContainers = counts[0];
        int totalContainers = counts[1];
        int totalItems = counts[2];

        plugin.getLogger().info("[ResourceDepot] Scanned " + regionId + " at " +
                depotLoc.getBlockX() + "," + depotLoc.getBlockZ() +
                " (radius=" + radius + ", fullHeight)" +
                " - Found " + totalContainers + " containers (" + qualifyingContainers + " with " + minItemsPerContainer + "+ items), " + totalItems + " total items");

        // Pass qualifying containers count to the service
        objectiveService.updateResourceDepotProgress(player.getUniqueId(), team, regionId, qualifyingContainers, totalItems);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = event.getBlock().getX();
        int y = event.getBlock().getY();
        int z = event.getBlock().getZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        String blockType = event.getBlock().getType().name();

        // Notify objective service
        objectiveService.onBlockPlaced(player.getUniqueId(), team, regionId, x, y, z, blockType);

        // Check for TNT placement - track for building damage attribution
        if (event.getBlock().getType() == Material.TNT) {
            // Track TNT placement for building damage attribution
            String locKey = getLocationKey(x, y, z);
            tntPlacerTracking.put(locKey, new BuildingDamageRecord(
                    player.getUniqueId(), player.getName(), team, System.currentTimeMillis()));
            plugin.getLogger().fine("[Stats] Tracked TNT placement by " + player.getName() + " at " + locKey);

            boolean startedObjective = objectiveService.onTntPlaced(player.getUniqueId(), team, regionId, x, y, z);
            if (startedObjective) {
                player.sendMessage(config.getPrefix() + org.bukkit.ChatColor.GREEN +
                        "💣 Explosive planted! Defend for 30 seconds!");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            }
        }

        // Also notify if it's a storage container for resource depot objective
        if (isStorageContainer(event.getBlock().getType())) {
            objectiveService.onContainerPlaced(player.getUniqueId(), team, regionId, x, y, z, blockType);

            // Check if there's an active resource depot objective and update progress
            boolean hasResourceDepotObjective = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)
                    .stream()
                    .anyMatch(obj -> obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT);

            if (hasResourceDepotObjective) {
                // Schedule a delayed task to update progress (after this block is placed)
                World world = event.getBlock().getWorld();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    updateResourceDepotProgressAtLocation(player, team, regionId, world);
                }, 1L); // 1 tick delay to ensure block is placed
            }
        }
    }

    // ==================== EXPLOSION EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Location explosionLoc = event.getLocation();
        String locKey = getLocationKey(explosionLoc.getBlockX(), explosionLoc.getBlockY(), explosionLoc.getBlockZ());

        // Find who placed the TNT (or caused the chain reaction)
        BuildingDamageRecord placer = tntPlacerTracking.remove(locKey);

        // If no direct placer, check for chain reaction (another TNT that exploded recently)
        if (placer == null) {
            long now = System.currentTimeMillis();
            // Look for any TNT placer within chain reaction window
            for (Map.Entry<String, BuildingDamageRecord> entry : tntPlacerTracking.entrySet()) {
                if ((now - entry.getValue().timestamp()) <= TNT_CHAIN_ATTRIBUTION_WINDOW_MS) {
                    placer = entry.getValue();
                    break;
                }
            }
        }

        if (placer == null) return;

        // Check each destroyed block for building membership
        for (Block block : event.blockList()) {
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();

            Optional<RegisteredBuilding> buildingOpt = objectiveService.getRegisteredBuildingAtLocation(x, y, z);
            if (buildingOpt.isPresent()) {
                RegisteredBuilding building = buildingOpt.get();
                // Only track if enemy TNT damaged the building
                if (!building.team().equalsIgnoreCase(placer.damagerTeam())) {
                    recordBuildingDamage(building.objectiveId(), placer.damagerUuid(),
                            placer.damagerName(), placer.damagerTeam());
                    plugin.getLogger().info("[Stats] Tracked TNT damage by " + placer.damagerName() +
                            " on " + building.type().getDisplayName() + " (obj " + building.objectiveId() + ")");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        int x = victim.getLocation().getBlockX();
        int z = victim.getLocation().getBlockZ();
        String regionId = regionService.getRegionIdForLocation(x, z);

        // Check if victim was carrying intel
        if (regionId != null) {
            objectiveService.onIntelCarrierDeath(victim.getUniqueId(), regionId);

            // Remove intel item from death drops if they were carrying it
            event.getDrops().removeIf(item -> {
                if (item.getType() == Material.FILLED_MAP) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.hasDisplayName()) {
                        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        return name.contains("SECRET INTEL");
                    }
                }
                return false;
            });
        }

        // Handle kills for assassination objective
        Player killer = victim.getKiller();
        if (killer == null || regionId == null) return;

        // Notify objective service for kill
        objectiveService.onPlayerKill(killer.getUniqueId(), victim.getUniqueId(), regionId);
    }

    // ==================== INTEL CAPTURE EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (item.getType() != Material.FILLED_MAP) return;

        // Check if this is an intel item
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        if (!name.contains("SECRET INTEL")) return;

        // This is intel - check for objective
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = event.getItem().getLocation().getBlockX();
        int z = event.getItem().getLocation().getBlockZ();
        String regionId = regionService.getRegionIdForLocation(x, z);

        if (regionId == null) return;

        // Try to pick up as attacker
        boolean pickedUp = objectiveService.onIntelPickup(player.getUniqueId(), team, regionId);

        if (pickedUp) {
            // Remove the item entity
            event.getItem().remove();
            event.setCancelled(true);

            // Give player glowing effect
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false, true));

            // Notify player
            player.sendMessage(config.getPrefix() + org.bukkit.ChatColor.GREEN +
                    "⚡ You picked up SECRET INTEL! Return to friendly territory!");
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
        } else {
            // Try to return as defender
            boolean returned = objectiveService.onIntelReturned(player.getUniqueId(), team, regionId);
            if (returned) {
                event.getItem().remove();
                event.setCancelled(true);

                player.sendMessage(config.getPrefix() + org.bukkit.ChatColor.GREEN +
                        "⚡ Intel recovered! The enemy's objective has been reset.");
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f);

                // Record stat for intel recovery
                if (statListener != null) {
                    statListener.recordIntelRecovered(player.getUniqueId(), player.getName());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check region changes (not every movement)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();

        String fromRegion = regionService.getRegionIdForLocation(
                event.getFrom().getBlockX(), event.getFrom().getBlockZ());
        String toRegion = regionService.getRegionIdForLocation(
                event.getTo().getBlockX(), event.getTo().getBlockZ());

        // Check if player changed regions
        if (fromRegion != null && toRegion != null && !fromRegion.equals(toRegion)) {
            // Check if this player is carrying intel
            objectiveService.onIntelCarrierRegionChange(player.getUniqueId(), toRegion);

            // If objective completed, remove glowing effect
            // The completion callback will handle notifications
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        uiManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        uiManager.onPlayerQuit(event.getPlayer());
    }

    // ==================== INVENTORY EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();

        // Check if it's a container block
        if (!(holder instanceof Container container)) return;

        Location loc = container.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        String regionId = regionService.getRegionIdForLocation(loc.getBlockX(), loc.getBlockZ());
        if (regionId == null) return;

        // Track container stocking for stat
        trackContainerStocking(player, container, loc);

        // Check if there's an active resource depot objective in this region
        boolean hasResourceDepotObjective = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)
                .stream()
                .anyMatch(obj -> obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT);

        if (!hasResourceDepotObjective) return;

        // Update progress using the objective's location
        updateResourceDepotProgressAtLocation(player, team, regionId, loc.getWorld());
    }

    /**
     * Tracks container stocking for the recordContainerStocked stat.
     * Awards credit when a player stocks a container to 500+ items,
     * revokes credit if items are removed below threshold.
     */
    private void trackContainerStocking(Player player, Container container, Location loc) {
        if (statListener == null) return;

        String locKey = getLocationKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // Count items in the container
        int itemCount = 0;
        org.bukkit.inventory.Inventory inv = container.getInventory();

        // Handle double chests
        if (inv instanceof org.bukkit.inventory.DoubleChestInventory doubleChestInv) {
            for (ItemStack item : doubleChestInv.getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    itemCount += item.getAmount();
                }
            }
        } else {
            for (ItemStack item : inv.getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    itemCount += item.getAmount();
                }
            }
        }

        // Get previous state for this container
        ContainerStockRecord prevRecord = containerStockTracking.get(locKey);
        Set<UUID> creditedPlayers = containerStockCredits.computeIfAbsent(locKey, k -> ConcurrentHashMap.newKeySet());

        boolean wasStocked = prevRecord != null && prevRecord.itemCount() >= CONTAINER_STOCKING_THRESHOLD;
        boolean isStocked = itemCount >= CONTAINER_STOCKING_THRESHOLD;

        // Update tracking
        containerStockTracking.put(locKey, new ContainerStockRecord(player.getUniqueId(), player.getName(), itemCount));

        // Award stat if newly stocked (and player hasn't been credited for this container yet)
        if (!wasStocked && isStocked && !creditedPlayers.contains(player.getUniqueId())) {
            creditedPlayers.add(player.getUniqueId());
            statListener.recordContainerStocked(player.getUniqueId(), player.getName());
            plugin.getLogger().fine("[Stats] Credited " + player.getName() + " for stocking container at " + locKey + " to " + itemCount + " items");
        }

        // Revoke stat if container becomes unstocked
        if (wasStocked && !isStocked) {
            // Revoke credit from all players who were credited for this container
            for (UUID creditedPlayer : creditedPlayers) {
                statListener.revokeContainerStocked(creditedPlayer);
                plugin.getLogger().fine("[Stats] Revoked container stocked stat from " + creditedPlayer + " at " + locKey);
            }
            creditedPlayers.clear();
        }
    }

    /**
     * Checks if a material is a storage container.
     */
    private boolean isStorageContainer(Material material) {
        return switch (material) {
            case CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX,
                 WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                 LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX,
                 PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                 CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX,
                 BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                 BLACK_SHULKER_BOX -> true;
            default -> false;
        };
    }

    // ==================== BUILDING DAMAGE TRACKING ====================

    /**
     * Records that a player damaged a building block.
     * Called from onBlockBreak and onEntityExplode.
     */
    public void recordBuildingDamage(int objectiveId, UUID damagerUuid, String damagerName, String damagerTeam) {
        long now = System.currentTimeMillis();
        BuildingDamageRecord record = new BuildingDamageRecord(damagerUuid, damagerName, damagerTeam, now);

        buildingDamageTracking.computeIfAbsent(objectiveId, k -> ConcurrentHashMap.newKeySet())
                .add(record);

        plugin.getLogger().fine("[Stats] Tracked building damage by " + damagerName + " on objective " + objectiveId);
    }

    /**
     * Gets all recent damagers for a building (within attribution window).
     * Called when a building is destroyed to credit all attackers.
     */
    public Set<BuildingDamageRecord> getBuildingDamagers(int objectiveId) {
        long now = System.currentTimeMillis();
        Set<BuildingDamageRecord> records = buildingDamageTracking.get(objectiveId);
        if (records == null) return Set.of();

        // Filter to only recent damagers and return a copy
        Set<BuildingDamageRecord> recent = new HashSet<>();
        for (BuildingDamageRecord record : records) {
            if ((now - record.timestamp()) <= BUILDING_DAMAGE_ATTRIBUTION_WINDOW_MS) {
                recent.add(record);
            }
        }
        return recent;
    }

    /**
     * Clears building damage tracking for an objective.
     * Called when building is destroyed or rebuilt.
     */
    public void clearBuildingDamageTracking(int objectiveId) {
        buildingDamageTracking.remove(objectiveId);
    }

    /**
     * Gets a location key string for tracking.
     */
    private String getLocationKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /**
     * Cleans up old tracking entries to prevent memory leaks.
     * Called periodically by the cleanup task.
     */
    private void cleanupOldTracking() {
        long now = System.currentTimeMillis();

        // Clean up old building damage records
        for (Map.Entry<Integer, Set<BuildingDamageRecord>> entry : buildingDamageTracking.entrySet()) {
            entry.getValue().removeIf(r -> (now - r.timestamp()) > BUILDING_DAMAGE_ATTRIBUTION_WINDOW_MS);
        }
        // Remove empty sets
        buildingDamageTracking.entrySet().removeIf(e -> e.getValue().isEmpty());

        // Clean up old TNT placer tracking
        tntPlacerTracking.entrySet().removeIf(e -> (now - e.getValue().timestamp()) > TNT_CHAIN_ATTRIBUTION_WINDOW_MS);

        plugin.getLogger().fine("[Stats] Cleaned up old tracking entries - building: " +
                buildingDamageTracking.size() + ", tnt: " + tntPlacerTracking.size());
    }
}
