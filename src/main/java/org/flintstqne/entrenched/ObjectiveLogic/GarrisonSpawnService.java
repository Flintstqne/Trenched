package org.flintstqne.entrenched.ObjectiveLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the garrison quick-travel spawn system.
 *
 * After respawning at home base, players receive a map item that shows
 * all friendly garrisons. Clicking a garrison teleports them there.
 * The map disappears after moving X blocks from spawn.
 */
public class GarrisonSpawnService {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final RoundService roundService;
    private final ConfigManager config;
    private RegionRenderer regionRenderer;

    // Key for identifying spawn map items
    private final NamespacedKey SPAWN_MAP_KEY;
    private final NamespacedKey SPAWN_LOCATION_KEY;
    private final NamespacedKey GARRISON_ID_KEY;
    private final NamespacedKey HOME_SPAWN_KEY;

    // Track players with active spawn maps - playerId -> spawn location
    private final Map<UUID, Location> playersWithSpawnMaps = new ConcurrentHashMap<>();

    // Track teleport cooldowns - playerId -> cooldown expiry timestamp
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    private static final long TELEPORT_COOLDOWN_MS = 600000; // 600 seconds (10 minutes)

    // Track garrison teleport capacity - buildingId -> teleports this minute
    private final Map<Integer, Integer> garrisonTeleportsThisMinute = new ConcurrentHashMap<>();
    private long lastCapacityResetTime = System.currentTimeMillis();

    // Distance player can move before spawn map disappears
    private static final int SPAWN_MAP_DISTANCE_LIMIT = 50;

    // Task for checking spawn map validity
    private BukkitTask spawnMapCheckTask;

    // Inventory title for garrison selection GUI
    public static final String GARRISON_GUI_TITLE = "Select Garrison";

    public GarrisonSpawnService(JavaPlugin plugin, ObjectiveService objectiveService,
                                RegionService regionService, TeamService teamService,
                                RoundService roundService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.roundService = roundService;
        this.config = config;

        this.SPAWN_MAP_KEY = new NamespacedKey(plugin, "garrison_spawn_map");
        this.SPAWN_LOCATION_KEY = new NamespacedKey(plugin, "spawn_location");
        this.GARRISON_ID_KEY = new NamespacedKey(plugin, "garrison_id");
        this.HOME_SPAWN_KEY = new NamespacedKey(plugin, "home_spawn");
    }

    /**
     * Sets the region renderer for region name lookups.
     */
    public void setRegionRenderer(RegionRenderer regionRenderer) {
        this.regionRenderer = regionRenderer;
    }

    /**
     * Starts the spawn map check task.
     */
    public void start() {
        // Check every second if players have moved too far from spawn
        spawnMapCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkSpawnMapValidity, 20L, 20L);
        plugin.getLogger().info("[Garrison] Spawn service started");
    }

    /**
     * Stops the spawn map check task.
     */
    public void stop() {
        if (spawnMapCheckTask != null) {
            spawnMapCheckTask.cancel();
        }
    }

    /**
     * Clears all tracked data (called on new round).
     */
    public void clearTrackedData() {
        playersWithSpawnMaps.clear();
        teleportCooldowns.clear();
        garrisonTeleportsThisMinute.clear();
    }

    // ==================== SPAWN MAP HANDLING ====================

    /**
     * Gives a spawn map to a player after they respawn.
     * Called by the death/respawn system.
     */
    public void giveSpawnMap(Player player, Location spawnLocation) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            return;
        }

        String team = teamOpt.get();

        // Check if there are any garrisons to teleport to
        List<RegisteredBuilding> garrisons = getTeamGarrisons(team);

        // Always give the map — it shows the 4x4 region grid even with 0 garrisons
        // Create the spawn map item
        ItemStack spawnMap = createSpawnMapItem(team, garrisons.size());

        // Store spawn location in PDC
        ItemMeta meta = spawnMap.getItemMeta();
        if (meta != null) {
            String locString = spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ();
            meta.getPersistentDataContainer().set(SPAWN_LOCATION_KEY, PersistentDataType.STRING, locString);
            spawnMap.setItemMeta(meta);
        }

        // Give to player
        player.getInventory().setItemInOffHand(spawnMap);
        playersWithSpawnMaps.put(player.getUniqueId(), spawnLocation);

        player.sendMessage(Component.text("[Garrison] ", NamedTextColor.GREEN)
                .append(Component.text("You have a ", NamedTextColor.WHITE))
                .append(Component.text("Garrison Map", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text(". Right-click to view the region grid.", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  The map will disappear if you move " + SPAWN_MAP_DISTANCE_LIMIT + " blocks.",
                NamedTextColor.GRAY));

        if (garrisons.isEmpty()) {
            player.sendMessage(Component.text("  ⚠ No garrisons available! Build a garrison to unlock spawning.", NamedTextColor.YELLOW));
        }

        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
    }

    /**
     * Creates the spawn map item.
     */
    private ItemStack createSpawnMapItem(String team, int garrisonCount) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = map.getItemMeta();

        if (meta != null) {
            meta.displayName(Component.text("Garrison Map", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Right-click to open garrison menu", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(garrisonCount + " garrison(s) available", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Disappears after moving " + SPAWN_MAP_DISTANCE_LIMIT + " blocks", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            meta.getPersistentDataContainer().set(SPAWN_MAP_KEY, PersistentDataType.BYTE, (byte) 1);

            map.setItemMeta(meta);
        }

        return map;
    }

    /**
     * Checks if an item is a spawn map.
     */
    public boolean isSpawnMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        return meta.getPersistentDataContainer().has(SPAWN_MAP_KEY, PersistentDataType.BYTE);
    }

    /**
     * Opens the garrison selection GUI for a player.
     */
    public void openGarrisonMenu(Player player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(Component.text("You must be on a team to use this!", NamedTextColor.RED));
            return;
        }

        String team = teamOpt.get();
        List<RegisteredBuilding> garrisons = getTeamGarrisons(team);

        // Build a lookup: regionId -> garrison
        Map<String, RegisteredBuilding> garrisonByRegion = new java.util.HashMap<>();
        for (RegisteredBuilding g : garrisons) {
            garrisonByRegion.put(g.regionId(), g);
        }

        // Refresh the lore on the held spawn map so the garrison count is current
        ItemStack heldMap = player.getInventory().getItemInOffHand();
        if (isSpawnMap(heldMap)) {
            ItemMeta mapMeta = heldMap.getItemMeta();
            if (mapMeta != null) {
                List<Component> freshLore = new ArrayList<>();
                freshLore.add(Component.text("Right-click to open garrison menu", NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                freshLore.add(Component.empty());
                freshLore.add(Component.text(garrisons.size() + " garrison(s) available", NamedTextColor.YELLOW)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                freshLore.add(Component.empty());
                freshLore.add(Component.text("Disappears after moving " + SPAWN_MAP_DISTANCE_LIMIT + " blocks", NamedTextColor.RED)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                mapMeta.lore(freshLore);
                heldMap.setItemMeta(mapMeta);
            }
        }

        // Check if the player's current region has a garrison
        String playerRegion = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(), player.getLocation().getBlockZ());
        if (playerRegion != null && !garrisonByRegion.containsKey(playerRegion)) {
            String regionName = getRegionDisplayName(playerRegion);
            player.sendMessage(Component.text("⚠ ", NamedTextColor.YELLOW)
                    .append(Component.text("No garrison in your region (", NamedTextColor.RED))
                    .append(Component.text(regionName, NamedTextColor.GOLD))
                    .append(Component.text(")! Build one to enable spawning here.", NamedTextColor.RED)));
        }

        // Create 4x4 grid GUI — 9 columns, we place the 4x4 grid in columns 1-4, rows 0-3
        // Using a 36-slot (4 rows of 9) inventory with the grid placed in the center
        // Row layout in inventory: slots 0-8 = row A, 9-17 = row B, 18-26 = row C, 27-35 = row D
        // Place the 4x4 grid at columns 2-5 (center) of each row, with labels
        Inventory gui = Bukkit.createInventory(null, 45, Component.text(GARRISON_GUI_TITLE));

        // Column header labels (row 0: slots 0-8)
        for (int col = 0; col < 4; col++) {
            ItemStack label = new ItemStack(Material.PAPER);
            ItemMeta labelMeta = label.getItemMeta();
            if (labelMeta != null) {
                labelMeta.displayName(Component.text("Column " + (col + 1), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                label.setItemMeta(labelMeta);
            }
            gui.setItem(col + 2, label); // slots 2,3,4,5
        }

        // Determine team home region
        String homeRegion = team.equalsIgnoreCase("RED")
                ? config.getRegionRedHome()
                : config.getRegionBlueHome();

        // Row labels + grid cells
        for (int row = 0; row < 4; row++) {
            char rowLabel = (char) ('A' + row);
            int inventoryRow = row + 1; // offset by 1 for the header row

            // Row label (column 0)
            ItemStack rowLabelItem = new ItemStack(Material.PAPER);
            ItemMeta rowLabelMeta = rowLabelItem.getItemMeta();
            if (rowLabelMeta != null) {
                rowLabelMeta.displayName(Component.text("Row " + rowLabel, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                rowLabelItem.setItemMeta(rowLabelMeta);
            }
            gui.setItem(inventoryRow * 9, rowLabelItem);

            // Grid cells (columns 2-5)
            for (int col = 0; col < 4; col++) {
                String regionId = rowLabel + String.valueOf(col + 1);
                int slot = inventoryRow * 9 + col + 2;

                RegisteredBuilding garrison = garrisonByRegion.get(regionId);
                boolean isHome = regionId.equalsIgnoreCase(homeRegion);
                ItemStack cell;

                if (garrison != null) {
                    // Team garrison present — use team-colored stained glass
                    Material glassMat = team.equalsIgnoreCase("RED")
                            ? Material.RED_STAINED_GLASS_PANE
                            : Material.BLUE_STAINED_GLASS_PANE;
                    cell = new ItemStack(glassMat);
                    ItemMeta meta = cell.getItemMeta();
                    if (meta != null) {
                        String regionName = getRegionDisplayName(regionId);
                        String variantText = garrison.variant() != null && !garrison.variant().equals("Basic Garrison")
                                ? " (" + garrison.variant() + ")" : "";
                        String homeTag = isHome ? " \u2302" : ""; // ⌂

                        meta.displayName(Component.text("⚑ " + regionName + variantText + homeTag, NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD)
                                .decoration(TextDecoration.ITALIC, false));

                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("Region: " + regionId, NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false));
                        lore.add(Component.text("Location: " + garrison.anchorX() + ", " + garrison.anchorY() + ", " + garrison.anchorZ(),
                                        NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false));

                        // Show capacity
                        int capacity = getGarrisonCapacity(garrison);
                        int used = garrisonTeleportsThisMinute.getOrDefault(garrison.objectiveId(), 0);
                        lore.add(Component.text("Capacity: ", NamedTextColor.GRAY)
                                .append(Component.text(used + "/" + capacity + " this minute",
                                        used >= capacity ? NamedTextColor.RED : NamedTextColor.GREEN))
                                .decoration(TextDecoration.ITALIC, false));

                        // Show variant bonuses
                        List<String> bonuses = getGarrisonVariantBonuses(garrison.variant());
                        if (!bonuses.isEmpty()) {
                            lore.add(Component.empty());
                            lore.add(Component.text("Bonuses:", NamedTextColor.YELLOW)
                                    .decoration(TextDecoration.ITALIC, false));
                            for (String bonus : bonuses) {
                                lore.add(Component.text("  - " + bonus, NamedTextColor.AQUA)
                                        .decoration(TextDecoration.ITALIC, false));
                            }
                        }

                        lore.add(Component.empty());
                        lore.add(Component.text("Click to teleport!", NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false));

                        meta.lore(lore);

                        // Store garrison ID in PDC for click handling
                        meta.getPersistentDataContainer().set(
                                GARRISON_ID_KEY,
                                PersistentDataType.INTEGER,
                                garrison.objectiveId()
                        );

                        cell.setItemMeta(meta);
                    }
                } else if (isHome) {
                    // Home region with no garrison — always spawnable (team-colored stained glass)
                    Material homeGlass = team.equalsIgnoreCase("RED")
                            ? Material.RED_STAINED_GLASS_PANE
                            : Material.BLUE_STAINED_GLASS_PANE;
                    cell = new ItemStack(homeGlass);
                    ItemMeta meta = cell.getItemMeta();
                    if (meta != null) {
                        String regionName = getRegionDisplayName(regionId);
                        meta.displayName(Component.text("\u2302 " + regionName + " (Home Base)", NamedTextColor.GREEN)
                                .decorate(TextDecoration.BOLD)
                                .decoration(TextDecoration.ITALIC, false));

                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("Region: " + regionId, NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false));
                        lore.add(Component.text("Your team's home region", NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false));
                        lore.add(Component.empty());
                        lore.add(Component.text("Click to spawn at home base!", NamedTextColor.GREEN)
                                .decoration(TextDecoration.ITALIC, false));
                        meta.lore(lore);

                        // Mark as home spawn in PDC
                        meta.getPersistentDataContainer().set(
                                HOME_SPAWN_KEY, PersistentDataType.BYTE, (byte) 1);

                        cell.setItemMeta(meta);
                    }
                } else {
                    // No garrison — white stained glass (empty region)
                    cell = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
                    ItemMeta meta = cell.getItemMeta();
                    if (meta != null) {
                        String regionName = getRegionDisplayName(regionId);
                        meta.displayName(Component.text(regionName + " (" + regionId + ")", NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false));

                        List<Component> lore = new ArrayList<>();
                        lore.add(Component.text("No garrison in this region", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false));
                        meta.lore(lore);

                        cell.setItemMeta(meta);
                    }
                }

                gui.setItem(slot, cell);
            }
        }

        player.openInventory(gui);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.2f);
    }

    /**
     * Handles a garrison menu click.
     */
    public void handleGarrisonMenuClick(Player player, ItemStack clickedItem) {
        if (clickedItem == null || !clickedItem.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clickedItem.getItemMeta();

        // Check if this is a home spawn click
        if (meta.getPersistentDataContainer().has(HOME_SPAWN_KEY, PersistentDataType.BYTE)) {
            player.closeInventory();
            teleportToHomeSpawn(player);
            return;
        }

        if (!meta.getPersistentDataContainer().has(GARRISON_ID_KEY,
                PersistentDataType.INTEGER)) {
            return;
        }

        int garrisonId = meta.getPersistentDataContainer().get(
                GARRISON_ID_KEY,
                PersistentDataType.INTEGER
        );

        player.closeInventory();
        teleportToGarrison(player, garrisonId);
    }

    // ==================== TELEPORTATION ====================

    /**
     * Teleports a player to a garrison.
     */
    public void teleportToGarrison(Player player, int garrisonId) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(Component.text("You must be on a team!", NamedTextColor.RED));
            return;
        }

        String team = teamOpt.get();

        // Find the garrison
        Optional<RegisteredBuilding> garrisonOpt = objectiveService.getRegisteredBuilding(garrisonId);
        if (garrisonOpt.isEmpty()) {
            player.sendMessage(Component.text("This garrison no longer exists!", NamedTextColor.RED));
            return;
        }

        RegisteredBuilding garrison = garrisonOpt.get();
        if (garrison.type() != BuildingType.GARRISON || garrison.status() != RegisteredBuildingStatus.ACTIVE) {
            player.sendMessage(Component.text("This garrison is no longer active!", NamedTextColor.RED));
            return;
        }

        // Verify it's a friendly garrison
        if (!garrison.team().equalsIgnoreCase(team)) {
            player.sendMessage(Component.text("This is not your team's garrison!", NamedTextColor.RED));
            return;
        }

        // Check cooldown
        Long cooldownUntil = teleportCooldowns.get(player.getUniqueId());
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
            long remaining = (cooldownUntil - System.currentTimeMillis()) / 1000;
            player.sendMessage(Component.text("Teleport on cooldown! " + remaining + "s remaining", NamedTextColor.RED));
            return;
        }

        // Check capacity
        resetCapacityIfNeeded();
        int capacity = getGarrisonCapacity(garrison);
        int used = garrisonTeleportsThisMinute.getOrDefault(garrison.objectiveId(), 0);
        if (used >= capacity) {
            player.sendMessage(Component.text("This garrison is at capacity! Try again in a minute.", NamedTextColor.RED));
            return;
        }

        // Perform teleport
        World world = roundService.getGameWorld().orElse(null);
        if (world == null) {
            player.sendMessage(Component.text("Could not find game world!", NamedTextColor.RED));
            return;
        }

        // Find safe teleport location near garrison
        Location teleportLoc = findSafeTeleportLocation(world, garrison);
        if (teleportLoc == null) {
            player.sendMessage(Component.text("Could not find safe teleport location!", NamedTextColor.RED));
            return;
        }

        // Teleport!
        player.teleport(teleportLoc);

        // Apply cooldown
        teleportCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + TELEPORT_COOLDOWN_MS);

        // Increment capacity usage
        garrisonTeleportsThisMinute.merge(garrison.objectiveId(), 1, Integer::sum);

        // Remove spawn map
        removeSpawnMap(player);

        // Apply variant buffs
        applyGarrisonVariantBuffs(player, garrison);

        // Notify
        String regionName = getRegionDisplayName(garrison.regionId());
        player.sendMessage(Component.text("[Garrison] ", NamedTextColor.GREEN)
                .append(Component.text("Teleported to garrison in ", NamedTextColor.WHITE))
                .append(Component.text(regionName, NamedTextColor.GOLD)));

        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    /**
     * Teleports a player to their team's home spawn point.
     */
    private void teleportToHomeSpawn(Player player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(Component.text("You must be on a team!", NamedTextColor.RED));
            return;
        }

        String team = teamOpt.get();
        Optional<Location> spawnOpt = teamService.getTeamSpawn(team);
        if (spawnOpt.isEmpty()) {
            player.sendMessage(Component.text("Could not find home spawn!", NamedTextColor.RED));
            return;
        }

        Location spawn = spawnOpt.get();
        player.teleport(spawn);

        // Remove spawn map
        removeSpawnMap(player);

        // Notify
        String homeRegion = team.equalsIgnoreCase("RED")
                ? config.getRegionRedHome()
                : config.getRegionBlueHome();
        String regionName = getRegionDisplayName(homeRegion);
        player.sendMessage(Component.text("[Garrison] ", NamedTextColor.GREEN)
                .append(Component.text("Teleported to home base in ", NamedTextColor.WHITE))
                .append(Component.text(regionName, NamedTextColor.GOLD)));

        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    }

    /**
     * Finds a safe teleport location just outside the garrison door.
     * Priority: door → fence gate → building exterior edge → anchor fallback.
     */
    private Location findSafeTeleportLocation(World world, RegisteredBuilding garrison) {
        // 1. Try to find a door and spawn just outside it
        Location doorSpawn = findSpawnOutsideDoor(world, garrison);
        if (doorSpawn != null) return doorSpawn;

        // 2. Fallback: find a safe spot just outside the building bounds
        Location edgeSpawn = findSpawnAtBuildingEdge(world, garrison);
        if (edgeSpawn != null) return edgeSpawn;

        // 3. Last resort: search near anchor
        int cx = garrison.anchorX(), cy = garrison.anchorY(), cz = garrison.anchorZ();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int y = cy + 5; y >= cy - 5; y--) {
                    if (isSafeLocation(world, cx + dx, y, cz + dz)) {
                        return new Location(world, cx + dx + 0.5, y, cz + dz + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Scans the garrison bounds for a door (or fence gate) and returns a safe
     * location 1-2 blocks outside the door on its facing side.
     */
    private Location findSpawnOutsideDoor(World world, RegisteredBuilding garrison) {
        // Scan for door blocks first, then fence gates
        int[][] doorBlock = findDoorBlock(world, garrison);
        if (doorBlock == null) return null;

        int dx = doorBlock[0][0], dy = doorBlock[0][1], dz = doorBlock[0][2];
        BlockFace facing = doorBlock[1] != null ? toBlockFace(doorBlock[1]) : null;

        // If we got a facing direction from door data, try that first
        if (facing != null) {
            Location loc = trySpawnInDirection(world, dx, dy, dz, facing);
            if (loc != null) return loc;
        }

        // Try all four cardinal directions, preferring ones that point outside the building
        int midX = (garrison.minX() + garrison.maxX()) / 2;
        int midZ = (garrison.minZ() + garrison.maxZ()) / 2;

        // Sort directions so we try the ones pointing away from building center first
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        java.util.Arrays.sort(faces, (a, b) -> {
            int awayA = (dx + a.getModX() - midX) * (dx + a.getModX() - midX)
                    + (dz + a.getModZ() - midZ) * (dz + a.getModZ() - midZ);
            int awayB = (dx + b.getModX() - midX) * (dx + b.getModX() - midX)
                    + (dz + b.getModZ() - midZ) * (dz + b.getModZ() - midZ);
            return Integer.compare(awayB, awayA); // farther from center first
        });

        for (BlockFace f : faces) {
            if (f == facing) continue; // already tried
            Location loc = trySpawnInDirection(world, dx, dy, dz, f);
            if (loc != null) return loc;
        }

        return null;
    }

    /**
     * Finds a door or fence gate block in the garrison bounds.
     * Returns int[2][] where [0]={x,y,z} and [1]={modX,0,modZ} facing direction (nullable).
     */
    private int[][] findDoorBlock(World world, RegisteredBuilding garrison) {
        // Pass 1: actual door blocks (not trapdoors)
        for (int y = garrison.minY(); y <= garrison.maxY(); y++) {
            for (int x = garrison.minX(); x <= garrison.maxX(); x++) {
                for (int z = garrison.minZ(); z <= garrison.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    String name = block.getType().name();
                    if (name.contains("DOOR") && !name.contains("TRAPDOOR")) {
                        int[] facing = null;
                        if (block.getBlockData() instanceof Door door) {
                            BlockFace f = door.getFacing();
                            facing = new int[]{f.getModX(), 0, f.getModZ()};
                        }
                        return new int[][]{new int[]{x, y, z}, facing};
                    }
                }
            }
        }
        // Pass 2: fence gates
        for (int y = garrison.minY(); y <= garrison.maxY(); y++) {
            for (int x = garrison.minX(); x <= garrison.maxX(); x++) {
                for (int z = garrison.minZ(); z <= garrison.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().name().contains("FENCE_GATE")) {
                        return new int[][]{new int[]{x, y, z}, null};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Tries to find a safe spawn location 1-2 blocks from the door in the given direction.
     */
    private Location trySpawnInDirection(World world, int doorX, int doorY, int doorZ, BlockFace dir) {
        for (int dist = 1; dist <= 2; dist++) {
            int tx = doorX + dir.getModX() * dist;
            int tz = doorZ + dir.getModZ() * dist;
            // Check a few Y levels around the door
            for (int dy = 0; dy >= -2; dy--) {
                int ty = doorY + dy;
                if (isSafeLocation(world, tx, ty, tz)) {
                    return new Location(world, tx + 0.5, ty, tz + 0.5);
                }
            }
            for (int dy = 1; dy <= 2; dy++) {
                int ty = doorY + dy;
                if (isSafeLocation(world, tx, ty, tz)) {
                    return new Location(world, tx + 0.5, ty, tz + 0.5);
                }
            }
        }
        return null;
    }

    /**
     * Finds a safe spawn location along the exterior edges of the building.
     */
    private Location findSpawnAtBuildingEdge(World world, RegisteredBuilding garrison) {
        int minX = garrison.minX() - 2, maxX = garrison.maxX() + 2;
        int minZ = garrison.minZ() - 2, maxZ = garrison.maxZ() + 2;
        int baseY = garrison.minY();

        // Walk the perimeter 1-2 blocks outside the building
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Only check the outer ring, not interior
                boolean isEdge = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!isEdge) continue;

                for (int y = baseY + 5; y >= baseY - 3; y--) {
                    if (isSafeLocation(world, x, y, z)) {
                        return new Location(world, x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private BlockFace toBlockFace(int[] dir) {
        if (dir == null) return null;
        if (dir[0] == 0 && dir[2] == -1) return BlockFace.NORTH;
        if (dir[0] == 0 && dir[2] == 1) return BlockFace.SOUTH;
        if (dir[0] == 1 && dir[2] == 0) return BlockFace.EAST;
        if (dir[0] == -1 && dir[2] == 0) return BlockFace.WEST;
        return null;
    }

    /**
     * Checks if a location is safe for teleporting.
     */
    private boolean isSafeLocation(World world, int x, int y, int z) {
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        Material ground = world.getBlockAt(x, y - 1, z).getType();

        return feet.isAir() && head.isAir() && ground.isSolid();
    }

    // ==================== GARRISON VARIANTS ====================

    /**
     * Gets the teleport capacity for a garrison based on bed count.
     */
    private int getGarrisonCapacity(RegisteredBuilding garrison) {
        // Capacity tiers driven by actual bed count, not signatureScore
        // (signatureScore bundles beds + storage and caps bed contribution at 12,
        //  making tier thresholds unreliable for distinguishing 3-bed from 9-bed garrisons)
        int beds = garrison.bedCount();
        int base;
        if (beds >= 9) {
            base = 6;       // 9+ beds → 6/min
        } else if (beds >= 6) {
            base = 5;       // 6-8 beds → 5/min
        } else if (beds >= 4) {
            base = 4;       // 4-5 beds → 4/min
        } else {
            base = 3;       // 3 beds (minimum) → 3/min
        }

        // Supply Garrison grants +1 spawn capacity
        if ("Supply Garrison".equals(garrison.variant())) {
            base += 1;
        }

        return base;
    }

    /**
     * Applies garrison variant buffs when a player teleports.
     */
    private void applyGarrisonVariantBuffs(Player player, RegisteredBuilding garrison) {
        String variant = garrison.variant();
        if (variant == null || variant.equals("Basic Garrison")) {
            return; // No buffs for basic garrison
        }

        int duration = 30 * 20; // 30 seconds

        switch (variant) {
            case "Medical Garrison" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, duration, 0, true, true, true));
                player.sendMessage(Component.text("  Regeneration I applied (30s)", NamedTextColor.LIGHT_PURPLE));
            }
            case "Armory Garrison" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, duration, 0, true, true, true));
                player.sendMessage(Component.text("  Resistance I applied (30s)", NamedTextColor.AQUA));
            }
            case "Command Garrison" -> {
                // Division members get 60s, others get 30s
                // For now, give 30s to all - division check can be added later
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration, 0, true, true, true));
                player.sendMessage(Component.text("  Strength I applied (30s)", NamedTextColor.RED));
            }
            case "Supply Garrison" -> {
                int supplyDuration = 60 * 20; // 60 seconds
                player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, supplyDuration, 0, true, true, true));
                player.sendMessage(Component.text("  Saturation applied (60s)", NamedTextColor.GOLD));
            }
            case "Fortified Garrison" -> {
                int fortDuration = 15 * 20; // 15 seconds
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, fortDuration, 1, true, true, true));
                player.sendMessage(Component.text("  Resistance II applied (15s)", NamedTextColor.BLUE));
            }
        }
    }

    /**
     * Gets bonus descriptions for a garrison variant.
     */
    private List<String> getGarrisonVariantBonuses(String variant) {
        if (variant == null) {
            return Collections.emptyList();
        }

        return switch (variant) {
            case "Medical Garrison" -> List.of("Regeneration I for 30s");
            case "Armory Garrison" -> List.of("Resistance I for 30s");
            case "Command Garrison" -> List.of("Strength I for 30s");
            case "Supply Garrison" -> List.of("Saturation for 60s", "+1 spawn capacity");
            case "Fortified Garrison" -> List.of("Resistance II for 15s");
            default -> Collections.emptyList();
        };
    }

    // ==================== HELPER METHODS ====================

    /**
     * Gets all active garrisons for a team.
     */
    private List<RegisteredBuilding> getTeamGarrisons(String team) {
        List<RegisteredBuilding> garrisons = new ArrayList<>();

        for (RegisteredBuilding building : objectiveService.getAllActiveBuildings()) {
            if (building.type() == BuildingType.GARRISON && building.team().equalsIgnoreCase(team)) {
                garrisons.add(building);
            }
        }

        return garrisons;
    }

    /**
     * Removes the spawn map from a player.
     */
    private void removeSpawnMap(Player player) {
        playersWithSpawnMaps.remove(player.getUniqueId());

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isSpawnMap(offHand)) {
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }

        // Also check main inventory
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isSpawnMap(item)) {
                player.getInventory().setItem(i, new ItemStack(Material.AIR));
            }
        }
    }

    /**
     * Checks spawn map validity for all players and removes if they've moved too far.
     */
    private void checkSpawnMapValidity() {
        List<UUID> stalePlayers = new ArrayList<>();

        for (Map.Entry<UUID, Location> entry : playersWithSpawnMaps.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline()) {
                stalePlayers.add(entry.getKey());
                continue;
            }

            Location spawnLoc = entry.getValue();
            Location currentLoc = player.getLocation();

            // Check if same world
            if (spawnLoc.getWorld() != null && !spawnLoc.getWorld().equals(currentLoc.getWorld())) {
                removeSpawnMap(player);
                continue;
            }

            // Check distance — 2D only (ignore Y) so a player moving vertically
            // (e.g. dropping into a hole near spawn) doesn't lose the map
            double dx = spawnLoc.getX() - currentLoc.getX();
            double dz = spawnLoc.getZ() - currentLoc.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > SPAWN_MAP_DISTANCE_LIMIT) {
                removeSpawnMap(player);
                player.sendMessage(Component.text("Your Garrison Map has disappeared - you moved too far from spawn.",
                        NamedTextColor.GRAY));
            }
        }

        for (UUID playerId : stalePlayers) {
            playersWithSpawnMaps.remove(playerId);
        }
    }

    /**
     * Resets garrison capacity counters if a minute has passed.
     */
    private void resetCapacityIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCapacityResetTime >= 60000) {
            garrisonTeleportsThisMinute.clear();
            lastCapacityResetTime = now;
        }
    }

    /**
     * Gets the display name for a region.
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId;
    }

    /**
     * Gets the cooldown remaining for a player (in seconds).
     */
    public long getCooldownRemaining(UUID playerId) {
        Long cooldownUntil = teleportCooldowns.get(playerId);
        if (cooldownUntil == null || System.currentTimeMillis() >= cooldownUntil) {
            return 0;
        }
        return (cooldownUntil - System.currentTimeMillis()) / 1000;
    }

    /**
     * Checks if a player has a spawn map.
     */
    public boolean hasSpawnMap(UUID playerId) {
        return playersWithSpawnMaps.containsKey(playerId);
    }
}
