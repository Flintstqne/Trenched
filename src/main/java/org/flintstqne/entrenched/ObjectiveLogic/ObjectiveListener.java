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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private RoundService roundService;

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
                // Schedule a delayed task to count remaining containers (after this block is removed)
                Location loc = event.getBlock().getLocation();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    int[] counts = countContainersAndItems(loc, regionId);
                    objectiveService.onContainerBroken(player.getUniqueId(), team, regionId, counts[0], counts[1]);
                }, 1L); // 1 tick delay to ensure block is removed
            }
        }
    }

    /**
     * Counts containers and items in a 64-block horizontal, 16-block vertical area around a location.
     * @return int array [containerCount, totalItems]
     */
    private int[] countContainersAndItems(Location loc, String regionId) {
        if (loc == null || loc.getWorld() == null) return new int[]{0, 0};

        int searchRadiusHorizontal = 64;
        int searchRadiusVertical = 16;
        int containerCount = 0;
        int totalItems = 0;

        for (int dx = -searchRadiusHorizontal; dx <= searchRadiusHorizontal; dx++) {
            for (int dy = -searchRadiusVertical; dy <= searchRadiusVertical; dy++) {
                for (int dz = -searchRadiusHorizontal; dz <= searchRadiusHorizontal; dz++) {
                    Block block = loc.getWorld().getBlockAt(
                            loc.getBlockX() + dx,
                            loc.getBlockY() + dy,
                            loc.getBlockZ() + dz
                    );

                    if (isStorageContainer(block.getType())) {
                        BlockState state = block.getState();
                        if (state instanceof Container nearbyContainer) {
                            containerCount++;
                            for (ItemStack item : nearbyContainer.getInventory().getContents()) {
                                if (item != null && item.getType() != Material.AIR) {
                                    totalItems += item.getAmount();
                                }
                            }
                        }
                    }
                }
            }
        }

        return new int[]{containerCount, totalItems};
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

        // Check for TNT placement (Plant Explosive objective)
        if (event.getBlock().getType() == Material.TNT) {
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
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        int x = victim.getLocation().getBlockX();
        int z = victim.getLocation().getBlockZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        // Notify objective service
        objectiveService.onPlayerKill(killer.getUniqueId(), victim.getUniqueId(), regionId);
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

        // Check if there's an active resource depot objective in this region
        boolean hasResourceDepotObjective = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)
                .stream()
                .anyMatch(obj -> obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT);

        if (!hasResourceDepotObjective) return;

        // Count containers and items using shared helper
        int[] counts = countContainersAndItems(loc, regionId);

        // Notify objective service
        objectiveService.onContainerInteract(player.getUniqueId(), team, regionId, counts[0], counts[1]);
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
}

