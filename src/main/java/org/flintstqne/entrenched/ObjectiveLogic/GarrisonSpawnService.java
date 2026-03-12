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

    // Track players with active spawn maps - playerId -> spawn location
    private final Map<UUID, Location> playersWithSpawnMaps = new ConcurrentHashMap<>();

    // Track teleport cooldowns - playerId -> cooldown expiry timestamp
    private final Map<UUID, Long> teleportCooldowns = new ConcurrentHashMap<>();
    private static final long TELEPORT_COOLDOWN_MS = 600000; // 600 seconds

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
        if (garrisons.isEmpty()) {
            return; // No garrisons available
        }

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
                .append(Component.text(". Right-click to teleport to a friendly garrison.", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  The map will disappear if you move " + SPAWN_MAP_DISTANCE_LIMIT + " blocks.",
                NamedTextColor.GRAY));

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

        if (garrisons.isEmpty()) {
            player.sendMessage(Component.text("No garrisons available for your team!", NamedTextColor.RED));
            removeSpawnMap(player);
            return;
        }

        // Create GUI - size based on number of garrisons (minimum 9, max 54)
        int size = Math.min(54, Math.max(9, ((garrisons.size() / 9) + 1) * 9));
        Inventory gui = Bukkit.createInventory(null, size, Component.text(GARRISON_GUI_TITLE));

        // Add garrison items
        for (int i = 0; i < garrisons.size() && i < size; i++) {
            RegisteredBuilding garrison = garrisons.get(i);
            ItemStack garrisonItem = createGarrisonMenuItem(garrison, team);
            gui.setItem(i, garrisonItem);
        }

        player.openInventory(gui);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.2f);
    }

    /**
     * Creates a menu item for a garrison.
     */
    private ItemStack createGarrisonMenuItem(RegisteredBuilding garrison, String team) {
        // Use team-colored bed as icon
        Material bedMaterial = team.equalsIgnoreCase("RED") ? Material.RED_BED : Material.BLUE_BED;
        ItemStack item = new ItemStack(bedMaterial);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String regionName = getRegionDisplayName(garrison.regionId());
            String variantText = garrison.variant() != null && !garrison.variant().equals("Barracks")
                    ? " (" + garrison.variant() + ")" : "";

            meta.displayName(Component.text(regionName + variantText, NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Location: ", NamedTextColor.GRAY)
                    .append(Component.text(garrison.anchorX() + ", " + garrison.anchorY() + ", " + garrison.anchorZ(),
                            NamedTextColor.WHITE))
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
                lore.add(Component.text("Bonuses:", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                for (String bonus : bonuses) {
                    lore.add(Component.text("  - " + bonus, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                }
            }

            lore.add(Component.empty());
            lore.add(Component.text("Click to teleport!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);

            // Store garrison ID in PDC for click handling
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "garrison_id"),
                    PersistentDataType.INTEGER,
                    garrison.objectiveId()
            );

            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Handles a garrison menu click.
     */
    public void handleGarrisonMenuClick(Player player, ItemStack clickedItem) {
        if (clickedItem == null || !clickedItem.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clickedItem.getItemMeta();
        if (!meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "garrison_id"),
                PersistentDataType.INTEGER)) {
            return;
        }

        int garrisonId = meta.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "garrison_id"),
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
     * Finds a safe teleport location near a garrison.
     */
    private Location findSafeTeleportLocation(World world, RegisteredBuilding garrison) {
        int centerX = garrison.anchorX();
        int centerY = garrison.anchorY();
        int centerZ = garrison.anchorZ();

        // Try to find a safe spot near the garrison
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;

                // Find ground level
                for (int y = centerY + 5; y >= centerY - 5; y--) {
                    Location loc = new Location(world, x + 0.5, y, z + 0.5);
                    if (isSafeLocation(world, x, y, z)) {
                        return loc;
                    }
                }
            }
        }

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
        // Base capacity from docs
        // 3 beds = 3/min, 4-5 = 4/min, 6-8 = 5/min, 9+ = 6/min
        // We approximate based on score - higher score = more beds
        double score = garrison.signatureScore();
        if (score >= 20) {
            return 6; // 9+ beds
        }
        if (score >= 16) {
            return 5; // 6-8 beds
        }
        if (score >= 12) {
            return 4; // 4-5 beds
        }
        return 3; // 3 beds
    }

    /**
     * Applies garrison variant buffs when a player teleports.
     */
    private void applyGarrisonVariantBuffs(Player player, RegisteredBuilding garrison) {
        String variant = garrison.variant();
        if (variant == null || variant.equals("Barracks")) {
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

            // Check distance
            double distance = spawnLoc.distance(currentLoc);
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
