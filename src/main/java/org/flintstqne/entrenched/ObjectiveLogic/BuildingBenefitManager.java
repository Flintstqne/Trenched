package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages gameplay benefits for registered buildings.
 *
 * Benefits include:
 * - Outpost variant buffs when leaving
 * - Watchtower enemy detection and alerts
 * - Garrison quick-travel spawn system (future)
 * - Building particle effects
 */
public class BuildingBenefitManager {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final RoundService roundService;
    private final ConfigManager config;
    private RegionRenderer regionRenderer;

    // Tasks
    private BukkitTask benefitTickTask;
    private BukkitTask particleTask;

    // Track players inside buildings - playerId -> buildingId
    private final Map<UUID, Integer> playersInBuildings = new ConcurrentHashMap<>();

    // Track watchtower occupants - buildingId -> set of player UUIDs on platform
    private final Map<Integer, Set<UUID>> watchtowerOccupants = new ConcurrentHashMap<>();

    // Track last enemy alert time per region to prevent spam - regionId -> timestamp
    private final Map<String, Long> lastEnemyAlert = new ConcurrentHashMap<>();
    private static final long ENEMY_ALERT_COOLDOWN_MS = 30000; // 30 seconds

    // Track players with active outpost buffs - playerId -> expiry timestamp
    private final Map<UUID, Long> outpostBuffExpiry = new ConcurrentHashMap<>();

    // Track enemies detected by watchtowers per friendly team.
    private final Map<String, Map<UUID, Long>> watchtowerDetectedEnemies = new ConcurrentHashMap<>();
    private static final long WATCHTOWER_MARKER_DURATION_MS = 5000; // 5 seconds of recon markers
    private static final double WATCHTOWER_MARKER_VIEW_DISTANCE_SQUARED = 192 * 192;
    private static final Particle.DustOptions WATCHTOWER_MARKER_DUST =
            new Particle.DustOptions(Color.fromRGB(255, 80, 80), 1.2f);

    // Counter for ambient sound (plays every 6th particle tick = ~3 seconds)
    private int ambientSoundCounter = 0;
    private static final int AMBIENT_SOUND_INTERVAL = 6;

    public BuildingBenefitManager(JavaPlugin plugin, ObjectiveService objectiveService,
                                  RegionService regionService, TeamService teamService,
                                  RoundService roundService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.roundService = roundService;
        this.config = config;
    }

    /**
     * Sets the region renderer for region name lookups.
     */
    public void setRegionRenderer(RegionRenderer regionRenderer) {
        this.regionRenderer = regionRenderer;
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
     * Starts the benefit management tasks.
     */
    public void start() {
        // Main benefit tick - runs every second (20 ticks)
        benefitTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBenefits, 20L, 20L);

        // Particle effects - runs every 10 ticks (0.5 seconds)
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, 10L, 10L);

        plugin.getLogger().info("[Buildings] Benefit manager started");
    }

    /**
     * Stops the benefit management tasks.
     */
    public void stop() {
        if (benefitTickTask != null) {
            benefitTickTask.cancel();
        }
        if (particleTask != null) {
            particleTask.cancel();
        }

        watchtowerDetectedEnemies.clear();

        plugin.getLogger().info("[Buildings] Benefit manager stopped");
    }

    /**
     * Clears all tracked data (called on new round).
     */
    public void clearTrackedData() {
        playersInBuildings.clear();
        watchtowerOccupants.clear();
        lastEnemyAlert.clear();
        outpostBuffExpiry.clear();
        watchtowerDetectedEnemies.clear();
    }

    // ==================== MAIN TICK ====================

    /**
     * Main benefit tick - runs every second.
     */
    private void tickBenefits() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return;
        }

        // Get all active buildings
        List<RegisteredBuilding> buildings = getActiveBuildings();
        if (buildings.isEmpty()) {
            return;
        }

        pruneTrackedPlayers(buildings, gameWorld);

        // Process each online player
        for (Player player : gameWorld.getPlayers()) {
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (teamOpt.isEmpty()) {
                continue;
            }

            String playerTeam = teamOpt.get();
            int playerX = player.getLocation().getBlockX();
            int playerY = player.getLocation().getBlockY();
            int playerZ = player.getLocation().getBlockZ();

            // Check if player is inside any building
            RegisteredBuilding insideBuilding = null;
            for (RegisteredBuilding building : buildings) {
                if (isInsideBuilding(playerX, playerY, playerZ, building)) {
                    // Check if this is a friendly building
                    if (building.team().equalsIgnoreCase(playerTeam)) {
                        insideBuilding = building;
                        break;
                    }
                }
            }

            // Handle building entry/exit
            Integer previousBuildingId = playersInBuildings.get(player.getUniqueId());
            if (insideBuilding != null) {
                if (previousBuildingId == null || previousBuildingId != insideBuilding.objectiveId()) {
                    // Entered a new building
                    onPlayerEnterBuilding(player, insideBuilding);
                }
                playersInBuildings.put(player.getUniqueId(), insideBuilding.objectiveId());
            } else if (previousBuildingId != null) {
                // Left a building
                RegisteredBuilding leftBuilding = findBuildingById(buildings, previousBuildingId);
                if (leftBuilding != null) {
                    onPlayerExitBuilding(player, leftBuilding);
                }
                playersInBuildings.remove(player.getUniqueId());
            }

            // Process watchtower detection for this player
            for (RegisteredBuilding building : buildings) {
                if (building.type() == BuildingType.WATCHTOWER && building.team().equalsIgnoreCase(playerTeam)) {
                    tickWatchtowerDetection(player, playerTeam, building, gameWorld);
                }
            }
        }

        // Refresh team-only recon markers from active watchtower detections.
        tickWatchtowerDetections();

        // Clean up expired outpost buffs
        tickOutpostBuffExpiry();
    }

    // ==================== BUILDING ENTRY/EXIT ====================

    /**
     * Called when a player enters a building.
     */
    private void onPlayerEnterBuilding(Player player, RegisteredBuilding building) {
        switch (building.type()) {
            case OUTPOST -> {
                // No immediate effect - buff applied on exit
            }
            case WATCHTOWER -> {
                // Track as occupant if on platform
                if (isOnWatchtowerPlatform(player, building)) {
                    watchtowerOccupants.computeIfAbsent(building.objectiveId(), k -> ConcurrentHashMap.newKeySet())
                            .add(player.getUniqueId());
                }
            }
            case GARRISON -> {
                // Future: Apply garrison variant buffs
            }
        }
    }

    /**
     * Called when a player exits a building.
     */
    private void onPlayerExitBuilding(Player player, RegisteredBuilding building) {
        switch (building.type()) {
            case OUTPOST -> applyOutpostVariantBuff(player, building);
            case WATCHTOWER -> {
                Set<UUID> occupants = watchtowerOccupants.get(building.objectiveId());
                if (occupants != null) {
                    occupants.remove(player.getUniqueId());
                }
            }
            case GARRISON -> {
                // Future: Apply garrison exit effects
            }
        }
    }

    // ==================== OUTPOST BUFFS ====================

    /**
     * Applies the outpost variant buff when a player leaves.
     */
    private void applyOutpostVariantBuff(Player player, RegisteredBuilding building) {
        String variant = building.variant();
        if (variant == null || variant.equals("Standard")) {
            return; // No buff for standard outposts
        }

        PotionEffectType effectType = null;
        int durationTicks = 5 * 60 * 20; // 5 minutes

        switch (variant) {
            case "Mining Outpost" -> effectType = PotionEffectType.LUCK; // Fortune equivalent
            case "Fishing Outpost" -> effectType = PotionEffectType.LUCK; // Luck of the Sea equivalent
            case "Farm Outpost" -> effectType = PotionEffectType.SATURATION;
            case "Forest Outpost" -> effectType = PotionEffectType.HASTE;
            case "Mountain Outpost" -> effectType = PotionEffectType.SLOW_FALLING;
            case "Desert Outpost" -> effectType = PotionEffectType.FIRE_RESISTANCE;
        }

        if (effectType != null) {
            player.addPotionEffect(new PotionEffect(effectType, durationTicks, 0, true, true, true));
            player.sendMessage(ChatColor.GREEN + "* " + variant + " buff applied! " +
                    ChatColor.GRAY + "(5 minutes)");
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);

            outpostBuffExpiry.put(player.getUniqueId(), System.currentTimeMillis() + (5 * 60 * 1000));
        }
    }

    /**
     * Cleans up expired outpost buff tracking.
     */
    private void tickOutpostBuffExpiry() {
        long now = System.currentTimeMillis();
        outpostBuffExpiry.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    // ==================== WATCHTOWER DETECTION ====================

    /**
     * Processes watchtower enemy detection for a player.
     */
    private void tickWatchtowerDetection(Player occupant, String occupantTeam, RegisteredBuilding watchtower, World world) {
        // Check if player is on the watchtower platform
        if (!isOnWatchtowerPlatform(occupant, watchtower)) {
            // Remove from occupants if they left the platform
            Set<UUID> occupants = watchtowerOccupants.get(watchtower.objectiveId());
            if (occupants != null) {
                occupants.remove(occupant.getUniqueId());
            }
            return;
        }

        // Add to occupants
        watchtowerOccupants.computeIfAbsent(watchtower.objectiveId(), k -> ConcurrentHashMap.newKeySet())
                .add(occupant.getUniqueId());

        // Calculate detection range based on tower height
        int towerHeight = watchtower.maxY() - watchtower.minY();
        int detectionRange = calculateDetectionRange(towerHeight);
        double detectionRangeSquared = detectionRange * detectionRange;

        // Find enemies within range
        Location towerTop = new Location(world, watchtower.anchorX(), watchtower.maxY(), watchtower.anchorZ());
        String regionId = watchtower.regionId();

        boolean enemyDetected = false;
        for (Player target : world.getPlayers()) {
            if (target.getUniqueId().equals(occupant.getUniqueId())) {
                continue;
            }

            Optional<String> targetTeamOpt = teamService.getPlayerTeam(target.getUniqueId());
            if (targetTeamOpt.isEmpty()) {
                continue;
            }

            String targetTeam = targetTeamOpt.get();
            if (targetTeam.equalsIgnoreCase(occupantTeam)) {
                continue; // Same team
            }

            if (target.getLocation().distanceSquared(towerTop) <= detectionRangeSquared) {
                markWatchtowerDetection(occupantTeam, target);
                enemyDetected = true;
            }
        }

        // Send enemy alert if enemies detected and not on cooldown
        if (enemyDetected) {
            sendEnemyAlert(occupantTeam, regionId, watchtower);
        }
    }

    /**
     * Calculates watchtower detection range based on height.
     */
    private int calculateDetectionRange(int height) {
        if (height >= 30) {
            return 160;
        }
        if (height >= 25) {
            return 128;
        }
        if (height >= 20) {
            return 96;
        }
        return 64; // Base range for 15-19 blocks
    }

    /**
     * Marks an enemy as detected for a specific friendly team.
     */
    private void markWatchtowerDetection(String team, Player enemy) {
        String normalizedTeam = team.toLowerCase(Locale.ROOT);
        watchtowerDetectedEnemies
                .computeIfAbsent(normalizedTeam, ignored -> new ConcurrentHashMap<>())
                .put(enemy.getUniqueId(), System.currentTimeMillis() + WATCHTOWER_MARKER_DURATION_MS);
    }

    /**
     * Sends enemy detection alert to team.
     */
    private void sendEnemyAlert(String team, String regionId, RegisteredBuilding watchtower) {
        String alertKey = team + ":" + regionId;
        long now = System.currentTimeMillis();
        Long lastAlert = lastEnemyAlert.get(alertKey);

        if (lastAlert != null && (now - lastAlert) < ENEMY_ALERT_COOLDOWN_MS) {
            return; // On cooldown
        }

        lastEnemyAlert.put(alertKey, now);

        String regionName = getRegionDisplayName(regionId);

        // Alert all team members
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeam.isPresent() && playerTeam.get().equalsIgnoreCase(team)) {
                player.sendMessage(ChatColor.YELLOW + "[Watchtower] " + ChatColor.WHITE + "Enemy spotted in " +
                        ChatColor.GOLD + regionName + ChatColor.WHITE + "!");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
            }
        }
    }

    private void pruneTrackedPlayers(List<RegisteredBuilding> buildings, World gameWorld) {
        List<UUID> staleTrackedPlayers = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : playersInBuildings.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(gameWorld)) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            RegisteredBuilding building = findBuildingById(buildings, entry.getValue());
            if (building == null) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            Optional<String> playerTeam = teamService.getPlayerTeam(entry.getKey());
            if (playerTeam.isEmpty() || !playerTeam.get().equalsIgnoreCase(building.team())) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            if (!isInsideBuilding(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ(),
                    building)) {
                staleTrackedPlayers.add(entry.getKey());
            }
        }
        for (UUID playerId : staleTrackedPlayers) {
            playersInBuildings.remove(playerId);
        }

        List<Integer> emptyWatchtowers = new ArrayList<>();
        for (Map.Entry<Integer, Set<UUID>> entry : watchtowerOccupants.entrySet()) {
            RegisteredBuilding building = findBuildingById(buildings, entry.getKey());
            if (building == null || building.type() != BuildingType.WATCHTOWER) {
                emptyWatchtowers.add(entry.getKey());
                continue;
            }

            pruneWatchtowerOccupants(entry.getValue(), building, gameWorld);
            if (entry.getValue().isEmpty()) {
                emptyWatchtowers.add(entry.getKey());
            }
        }
        for (Integer buildingId : emptyWatchtowers) {
            watchtowerOccupants.remove(buildingId);
        }
    }

    private void pruneWatchtowerOccupants(Set<UUID> occupants, RegisteredBuilding watchtower, World gameWorld) {
        List<UUID> staleOccupants = new ArrayList<>();
        for (UUID occupantId : occupants) {
            Player occupant = Bukkit.getPlayer(occupantId);
            if (occupant == null || !occupant.isOnline() || !occupant.getWorld().equals(gameWorld)) {
                staleOccupants.add(occupantId);
                continue;
            }

            Optional<String> occupantTeam = teamService.getPlayerTeam(occupantId);
            if (occupantTeam.isEmpty() || !occupantTeam.get().equalsIgnoreCase(watchtower.team())) {
                staleOccupants.add(occupantId);
                continue;
            }

            if (!isOnWatchtowerPlatform(occupant, watchtower)) {
                staleOccupants.add(occupantId);
            }
        }

        for (UUID occupantId : staleOccupants) {
            occupants.remove(occupantId);
        }
    }

    /**
     * Refreshes team-only recon markers for detected enemies.
     */
    private void tickWatchtowerDetections() {
        long now = System.currentTimeMillis();
        List<String> emptyTeams = new ArrayList<>();

        for (Map.Entry<String, Map<UUID, Long>> teamEntry : watchtowerDetectedEnemies.entrySet()) {
            String team = teamEntry.getKey();
            Map<UUID, Long> detectedEnemies = teamEntry.getValue();
            List<UUID> expiredEnemies = new ArrayList<>();

            for (Map.Entry<UUID, Long> detectionEntry : detectedEnemies.entrySet()) {
                if (detectionEntry.getValue() < now) {
                    expiredEnemies.add(detectionEntry.getKey());
                    continue;
                }

                Player enemy = Bukkit.getPlayer(detectionEntry.getKey());
                if (enemy == null || !enemy.isOnline()) {
                    expiredEnemies.add(detectionEntry.getKey());
                    continue;
                }

                showWatchtowerMarker(team, enemy);
            }

            for (UUID enemyId : expiredEnemies) {
                detectedEnemies.remove(enemyId);
            }

            if (detectedEnemies.isEmpty()) {
                emptyTeams.add(team);
            }
        }

        for (String team : emptyTeams) {
            watchtowerDetectedEnemies.remove(team);
        }
    }

    /**
     * Shows a team-only recon marker over a detected enemy.
     */
    private void showWatchtowerMarker(String team, Player enemy) {
        Location enemyLoc = enemy.getLocation();
        Location markerLoc = enemyLoc.clone().add(0, enemy.getHeight() + 0.4, 0);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(enemy.getWorld())) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(enemyLoc) > WATCHTOWER_MARKER_VIEW_DISTANCE_SQUARED) {
                continue;
            }

            Optional<String> viewerTeamOpt = teamService.getPlayerTeam(viewer.getUniqueId());
            if (viewerTeamOpt.isEmpty() || !viewerTeamOpt.get().equalsIgnoreCase(team)) {
                continue;
            }

            viewer.spawnParticle(Particle.DUST, markerLoc, 6, 0.25, 0.5, 0.25, 0.0, WATCHTOWER_MARKER_DUST);
            viewer.spawnParticle(Particle.END_ROD, markerLoc.clone().add(0, 0.3, 0), 2, 0.15, 0.25, 0.15, 0.0);
        }
    }

    /**
     * Checks if a player is on the watchtower platform.
     */
    private boolean isOnWatchtowerPlatform(Player player, RegisteredBuilding watchtower) {
        int playerY = player.getLocation().getBlockY();
        int platformY = watchtower.maxY();

        // Player is on platform if they're at or near the top of the tower
        if (playerY < platformY - 2 || playerY > platformY + 3) {
            return false;
        }

        int playerX = player.getLocation().getBlockX();
        int playerZ = player.getLocation().getBlockZ();

        // Check if within horizontal bounds (with some tolerance)
        return playerX >= watchtower.minX() - 1 && playerX <= watchtower.maxX() + 1 &&
                playerZ >= watchtower.minZ() - 1 && playerZ <= watchtower.maxZ() + 1;
    }

    // ==================== PARTICLE EFFECTS ====================

    /**
     * Spawns particle effects for buildings.
     */
    private void tickParticles() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return;
        }

        List<RegisteredBuilding> buildings = getActiveBuildings();
        if (buildings.isEmpty()) {
            return;
        }

        boolean playSound = (++ambientSoundCounter >= AMBIENT_SOUND_INTERVAL);
        if (playSound) ambientSoundCounter = 0;

        for (RegisteredBuilding building : buildings) {
            // Only show particles to team members within 32 blocks
            Location buildingCenter = new Location(gameWorld,
                    building.anchorX(), building.anchorY(), building.anchorZ());

            Collection<Player> nearbyPlayers = gameWorld.getNearbyPlayers(buildingCenter, 32);

            for (Player player : nearbyPlayers) {
                Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
                if (playerTeam.isEmpty() || !playerTeam.get().equalsIgnoreCase(building.team())) {
                    continue; // Only show to team members
                }

                spawnBuildingParticles(player, building, gameWorld);

                // Play subtle ambient sound every ~3 seconds for nearby players (within 16 blocks)
                if (playSound && player.getLocation().distanceSquared(buildingCenter) <= 16 * 16) {
                    Sound ambientSound = switch (building.type()) {
                        case OUTPOST -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
                        case WATCHTOWER -> Sound.BLOCK_AMETHYST_CLUSTER_STEP;
                        case GARRISON -> Sound.BLOCK_CAMPFIRE_CRACKLE;
                    };
                    player.playSound(buildingCenter, ambientSound, 0.15f, 1.2f);
                }
            }
        }
    }

    /**
     * Spawns particles for a specific building visible to a player.
     */
    private void spawnBuildingParticles(Player viewer, RegisteredBuilding building, World world) {
        Location particleLoc;
        Particle particleType;
        int count;

        switch (building.type()) {
            case OUTPOST -> {
                // White sparkles rising from center
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.anchorY() + 1.5,
                        building.anchorZ() + 0.5);
                particleType = Particle.END_ROD;
                count = 3;
            }
            case WATCHTOWER -> {
                // Blue beacon-like particles at platform level
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.maxY() + 0.5,
                        building.anchorZ() + 0.5);
                particleType = Particle.SOUL_FIRE_FLAME;
                count = 5;
            }
            case GARRISON -> {
                // Team-colored flames
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.anchorY() + 0.5,
                        building.anchorZ() + 0.5);
                particleType = building.team().equalsIgnoreCase("RED") ?
                        Particle.FLAME : Particle.SOUL_FIRE_FLAME;
                count = 4;
            }
            default -> {
                return;
            }
        }

        // Add some random offset
        double offsetX = (Math.random() - 0.5) * 0.5;
        double offsetZ = (Math.random() - 0.5) * 0.5;
        particleLoc.add(offsetX, Math.random() * 0.5, offsetZ);

        viewer.spawnParticle(particleType, particleLoc, count, 0.1, 0.2, 0.1, 0.01);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Checks if coordinates are inside a building's bounds.
     */
    private boolean isInsideBuilding(int x, int y, int z, RegisteredBuilding building) {
        return x >= building.minX() && x <= building.maxX() &&
                y >= building.minY() && y <= building.maxY() &&
                z >= building.minZ() && z <= building.maxZ();
    }

    /**
     * Gets all active registered buildings.
     */
    private List<RegisteredBuilding> getActiveBuildings() {
        return objectiveService.getAllActiveBuildings();
    }

    /**
     * Finds a building by its objective ID.
     */
    private RegisteredBuilding findBuildingById(List<RegisteredBuilding> buildings, int objectiveId) {
        for (RegisteredBuilding building : buildings) {
            if (building.objectiveId() == objectiveId) {
                return building;
            }
        }
        return null;
    }

    // ==================== PUBLIC API ====================

    /**
     * Gets the set of players currently occupying a watchtower.
     */
    public Set<UUID> getWatchtowerOccupants(int buildingId) {
        return watchtowerOccupants.getOrDefault(buildingId, Collections.emptySet());
    }

    /**
     * Checks if any friendly watchtower has this region under surveillance.
     */
    public boolean isRegionUnderSurveillance(String regionId, String team) {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return false;
        }

        for (RegisteredBuilding building : getActiveBuildings()) {
            if (building.type() == BuildingType.WATCHTOWER &&
                    building.team().equalsIgnoreCase(team) &&
                    building.regionId().equalsIgnoreCase(regionId)) {

                Set<UUID> occupants = watchtowerOccupants.get(building.objectiveId());
                if (occupants == null) {
                    continue;
                }

                pruneWatchtowerOccupants(occupants, building, gameWorld);
                if (!occupants.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
