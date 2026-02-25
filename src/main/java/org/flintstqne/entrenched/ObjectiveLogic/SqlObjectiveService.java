package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.DivisionMember;
import org.flintstqne.entrenched.DivisionLogic.DivisionRole;
import org.flintstqne.entrenched.DivisionLogic.DivisionService;
import org.flintstqne.entrenched.DivisionLogic.Division;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SQL-backed implementation of ObjectiveService.
 */
public class SqlObjectiveService implements ObjectiveService {

    private final JavaPlugin plugin;
    private final ObjectiveDb db;
    private final RoundService roundService;
    private final RegionService regionService;
    private final ConfigManager config;
    private DivisionService divisionService;
    private TeamService teamService;

    private ObjectiveCompletionCallback completionCallback;
    private ObjectiveSpawnCallback spawnCallback;

    // Cache for region centers (calculated once)
    private final Map<String, int[]> regionCenters = new HashMap<>();

    // Debounce for "region owned" message to prevent spam
    private final Map<UUID, Long> lastRegionOwnedWarning = new ConcurrentHashMap<>();
    private static final long REGION_OWNED_WARNING_COOLDOWN_MS = 10000; // 10 seconds

    // Track blocks that have earned objective progress - "regionId:objectiveType" -> Set of "x,y,z"
    // Prevents place/break/place cheese for wall and road objectives
    private final Map<String, Set<String>> objectiveBlocksTracking = new ConcurrentHashMap<>();

    // Track enemy chests by region and team - "regionId:team" -> Set of "x,y,z"
    // Used for Destroy Supply Cache objective to target enemy-placed chests
    private final Map<String, Set<String>> enemyChestTracking = new ConcurrentHashMap<>();

    // Track Resource Depot last update time per region to prevent rapid open/close cheese
    // regionId -> last update timestamp
    private final Map<String, Long> resourceDepotLastUpdate = new ConcurrentHashMap<>();
    private static final long RESOURCE_DEPOT_UPDATE_COOLDOWN_MS = 5000; // 5 seconds between updates

    // Track Resource Depot last item count per region to detect actual changes
    // regionId -> [containerCount, itemCount]
    private final Map<String, int[]> resourceDepotLastCounts = new ConcurrentHashMap<>();

    // Track container placement cooldowns per player - playerUuid -> last placement timestamp
    private final Map<UUID, Long> containerPlacementCooldowns = new ConcurrentHashMap<>();
    private static final long CONTAINER_PLACEMENT_COOLDOWN_MS = 3000; // 3 seconds between placements

    // Track planted explosives - regionId -> PlantedExplosiveData
    // Used for Plant Explosive objective defend timer
    private final Map<String, PlantedExplosiveData> plantedExplosives = new ConcurrentHashMap<>();

    // Track completed objective types per region to prevent repeats until all types exhausted
    // "regionId:category" -> Set of completed ObjectiveTypes
    private final Map<String, Set<ObjectiveType>> completedObjectiveTypes = new ConcurrentHashMap<>();

    // Internal data class for tracking planted explosives
    private record PlantedExplosiveData(
            int objectiveId,
            UUID planterUuid,
            String planterTeam,
            int x, int y, int z,
            long plantedAtMillis,
            int defendSeconds
    ) {
        int getSecondsRemaining() {
            long elapsed = (System.currentTimeMillis() - plantedAtMillis) / 1000;
            return Math.max(0, defendSeconds - (int) elapsed);
        }

        double getProgress() {
            long elapsed = (System.currentTimeMillis() - plantedAtMillis) / 1000;
            return Math.min(1.0, (double) elapsed / defendSeconds);
        }
    }

    // Track intel carriers - sourceRegionId -> IntelCarrierData
    // Used for Capture Intel objective
    private final Map<String, IntelCarrierData> intelCarriers = new ConcurrentHashMap<>();

    // Track spawned intel items - regionId -> Item entity UUID
    private final Map<String, UUID> spawnedIntelItems = new ConcurrentHashMap<>();

    // Track intel objective spawn times - regionId -> spawn timestamp (for 10-minute overall limit)
    private final Map<String, Long> intelObjectiveSpawnTimes = new ConcurrentHashMap<>();

    // Intel dropped timeout in seconds (60 seconds to recover dropped intel before it respawns)
    private static final int INTEL_DROPPED_TIMEOUT_SECONDS = 60;

    // Intel objective total lifetime in seconds (10 minutes)
    private static final int INTEL_OBJECTIVE_LIFETIME_SECONDS = 10 * 60;

    // Internal data class for tracking intel carriers
    private record IntelCarrierData(
            int objectiveId,
            String sourceRegionId,
            UUID carrierUuid,
            String carrierTeam,
            long pickedUpAtMillis,
            boolean isDropped,
            int droppedX, int droppedY, int droppedZ,
            long droppedAtMillis  // When intel was dropped (for timeout)
    ) {
        IntelCarrierData withDropped(int x, int y, int z) {
            return new IntelCarrierData(objectiveId, sourceRegionId, null, carrierTeam,
                    pickedUpAtMillis, true, x, y, z, System.currentTimeMillis());
        }

        IntelCarrierData withCarrier(UUID newCarrier, String team) {
            return new IntelCarrierData(objectiveId, sourceRegionId, newCarrier, team,
                    System.currentTimeMillis(), false, 0, 0, 0, 0);
        }

        int getDroppedSecondsRemaining() {
            if (!isDropped) return 0;
            long elapsed = (System.currentTimeMillis() - droppedAtMillis) / 1000;
            return Math.max(0, INTEL_DROPPED_TIMEOUT_SECONDS - (int) elapsed);
        }

        boolean isDroppedTimedOut() {
            return isDropped && getDroppedSecondsRemaining() <= 0;
        }
    }

    public SqlObjectiveService(JavaPlugin plugin, ObjectiveDb db, RoundService roundService,
                                RegionService regionService, ConfigManager config) {
        this.plugin = plugin;
        this.db = db;
        this.roundService = roundService;
        this.regionService = regionService;
        this.config = config;

        // Pre-calculate region centers
        calculateRegionCenters();
    }

    /**
     * Sets the DivisionService (called after construction to avoid circular dependency).
     */
    public void setDivisionService(DivisionService divisionService) {
        this.divisionService = divisionService;
    }

    /**
     * Sets the TeamService (called after construction to avoid circular dependency).
     */
    public void setTeamService(TeamService teamService) {
        this.teamService = teamService;
    }

    private void calculateRegionCenters() {
        int regionSize = config.getRegionSize();
        int gridSize = 4; // 4x4 grid
        int totalSize = regionSize * gridSize;
        int startX = -(totalSize / 2);
        int startZ = -(totalSize / 2);

        for (int z = 0; z < gridSize; z++) {
            for (int x = 0; x < gridSize; x++) {
                char row = (char) ('A' + z);
                String regionId = row + String.valueOf(x + 1);

                int centerX = startX + (x * regionSize) + (regionSize / 2);
                int centerZ = startZ + (z * regionSize) + (regionSize / 2);

                regionCenters.put(regionId, new int[]{centerX, centerZ});
            }
        }
    }

    private Optional<Integer> getCurrentRoundId() {
        return roundService.getCurrentRound().map(Round::roundId);
    }

    // ==================== OBJECTIVE QUERIES ====================

    @Override
    public List<RegionObjective> getActiveObjectives(String regionId) {
        return getCurrentRoundId()
                .map(roundId -> db.getActiveObjectives(regionId, roundId))
                .orElse(Collections.emptyList());
    }

    @Override
    public List<RegionObjective> getActiveObjectives(String regionId, ObjectiveCategory category) {
        return getCurrentRoundId()
                .map(roundId -> db.getActiveObjectivesByCategory(regionId, roundId, category))
                .orElse(Collections.emptyList());
    }

    @Override
    public Optional<RegionObjective> getObjective(int objectiveId) {
        return db.getObjective(objectiveId);
    }

    @Override
    public Optional<RegionObjective> getNearestObjective(String regionId, int x, int z) {
        List<RegionObjective> objectives = getActiveObjectives(regionId);

        return objectives.stream()
                .filter(RegionObjective::hasLocation)
                .min(Comparator.comparingDouble(o -> {
                    int dx = o.locationX() - x;
                    int dz = o.locationZ() - z;
                    return Math.sqrt(dx * dx + dz * dz);
                }));
    }

    @Override
    public List<RegionObjective> getCompletedByPlayer(UUID playerUuid) {
        return getCurrentRoundId()
                .map(roundId -> db.getCompletedByPlayer(playerUuid, roundId))
                .orElse(Collections.emptyList());
    }

    @Override
    public int countActiveObjectives(String regionId) {
        return getCurrentRoundId()
                .map(roundId -> db.countActiveObjectives(regionId, roundId))
                .orElse(0);
    }

    // ==================== OBJECTIVE SPAWNING ====================

    @Override
    public SpawnResult spawnObjectivesForRegion(String regionId) {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            plugin.getLogger().warning("[Objectives] spawnObjectivesForRegion: No active round");
            return SpawnResult.NO_ACTIVE_ROUND;
        }

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            plugin.getLogger().warning("[Objectives] spawnObjectivesForRegion: Region " + regionId + " not found");
            return SpawnResult.REGION_NOT_FOUND;
        }

        RegionStatus status = statusOpt.get();
        int maxObjectives = config.getObjectivesPerRegion();
        int currentCount = countActiveObjectives(regionId);

        if (currentCount >= maxObjectives) {
            return SpawnResult.MAX_OBJECTIVES_REACHED;
        }

        // Determine which type of objectives to spawn based on region state
        ObjectiveCategory category;
        if (status.state() == RegionState.NEUTRAL) {
            category = ObjectiveCategory.SETTLEMENT;
        } else if (status.state() == RegionState.OWNED || status.state() == RegionState.CONTESTED) {
            category = ObjectiveCategory.RAID;
        } else {
            // Fortified or Protected - no objectives
            plugin.getLogger().info("[Objectives] Region " + regionId + " is " + status.state() + " - skipping");
            return SpawnResult.SUCCESS;
        }

        // Get available objective types for this category
        ObjectiveType[] availableTypes = category == ObjectiveCategory.RAID
                ? ObjectiveType.getRaidObjectives()
                : ObjectiveType.getSettlementObjectives();

        // Get already active objective types to avoid duplicates
        Set<ObjectiveType> activeTypes = new HashSet<>();
        for (RegionObjective obj : getActiveObjectives(regionId, category)) {
            activeTypes.add(obj.type());
        }

        // Get previously completed objective types to avoid repeats
        String completedKey = regionId + ":" + category.name();
        Set<ObjectiveType> completedTypes = completedObjectiveTypes.getOrDefault(completedKey, new HashSet<>());

        // If all objective types have been completed, reset the tracking for fresh objectives
        Set<ObjectiveType> allTypesInCategory = new HashSet<>(Arrays.asList(availableTypes));
        if (completedTypes.containsAll(allTypesInCategory)) {
            plugin.getLogger().info("[Objectives] Region " + regionId +
                    ": All " + category + " objective types completed, resetting for fresh objectives");
            completedTypes.clear();
            completedObjectiveTypes.put(completedKey, completedTypes);
        }

        // Spawn new objectives up to the max
        int toSpawn = maxObjectives - currentCount;
        List<ObjectiveType> candidates = new ArrayList<>();
        for (ObjectiveType type : availableTypes) {
            // Skip if already active or previously completed (until reset)
            if (activeTypes.contains(type) || completedTypes.contains(type)) {
                continue;
            }

            // Special check for assassinate commander - only spawn if valid targets exist
            if (type == ObjectiveType.RAID_ASSASSINATE_COMMANDER) {
                if (!canSpawnAssassinateObjective(regionId)) {
                    continue; // Skip - no valid targets online
                }
            }
            // Special check for destroy supply cache - only spawn if enemy chests exist
            if (type == ObjectiveType.RAID_DESTROY_CACHE) {
                if (!canSpawnDestroyCacheObjective(regionId)) {
                    continue; // Skip - no enemy chests in region
                }
            }
            candidates.add(type);
        }

        if (candidates.isEmpty()) {
            plugin.getLogger().warning("[Objectives] Region " + regionId +
                    ": No candidates available (active: " + activeTypes + ", completed: " + completedTypes + ", category: " + category + ")");
            return SpawnResult.SUCCESS;
        }

        plugin.getLogger().info("[Objectives] Region " + regionId + ": spawning " + toSpawn +
                " objectives from " + candidates.size() + " candidates (excluding " + completedTypes.size() + " previously completed)");

        // Shuffle and spawn
        Collections.shuffle(candidates);
        for (int i = 0; i < Math.min(toSpawn, candidates.size()); i++) {
            spawnObjective(regionId, candidates.get(i));
        }

        return SpawnResult.SUCCESS;
    }

    @Override
    public SpawnResult spawnObjective(String regionId, ObjectiveType type) {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            return SpawnResult.NO_ACTIVE_ROUND;
        }

        // Get region center for location-based objectives
        int[] center = regionCenters.get(regionId);
        if (center == null) {
            return SpawnResult.REGION_NOT_FOUND;
        }

        // Generate a random location near the region center for objectives that need it
        Integer locX = null, locY = null, locZ = null;

        if (needsLocation(type)) {
            int offsetRange = config.getRegionSize() / 4; // Within 1/4 of region from center
            locX = center[0] + ThreadLocalRandom.current().nextInt(-offsetRange, offsetRange);
            locZ = center[1] + ThreadLocalRandom.current().nextInt(-offsetRange, offsetRange);

            // Get Y level from world (best effort, defaults to 64)
            World world = roundService.getGameWorld().orElse(null);
            if (world != null) {
                locY = world.getHighestBlockYAt(locX, locZ) + 1;
            } else {
                locY = 64;
            }
        }

        return spawnObjective(regionId, type, locX, locY, locZ);
    }

    @Override
    public SpawnResult spawnObjective(String regionId, ObjectiveType type, int x, int y, int z) {
        return spawnObjective(regionId, type, (Integer) x, (Integer) y, (Integer) z);
    }

    private SpawnResult spawnObjective(String regionId, ObjectiveType type, Integer x, Integer y, Integer z) {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            return SpawnResult.NO_ACTIVE_ROUND;
        }

        int roundId = roundIdOpt.get();
        int objectiveId = db.createObjective(regionId, roundId, type, x, y, z);

        if (objectiveId > 0) {
            plugin.getLogger().info("[Objectives] Spawned " + type.getDisplayName() + " in " + regionId);

            // Special handling for Capture Intel - spawn the intel item
            if (type == ObjectiveType.RAID_CAPTURE_INTEL && x != null && y != null && z != null) {
                spawnIntelItem(regionId, x, y, z);
            }

            // Notify callback
            if (spawnCallback != null) {
                db.getObjective(objectiveId).ifPresent(spawnCallback::onObjectiveSpawned);
            }

            return SpawnResult.SUCCESS;
        }

        return SpawnResult.NO_VALID_LOCATION;
    }

    private boolean needsLocation(ObjectiveType type) {
        return switch (type) {
            // Raid objectives
            case RAID_DESTROY_CACHE, RAID_PLANT_EXPLOSIVE, RAID_CAPTURE_INTEL, RAID_HOLD_GROUND,
            // Settlement objectives - need location for particles and distance display
                 SETTLEMENT_ESTABLISH_OUTPOST, SETTLEMENT_RESOURCE_DEPOT, SETTLEMENT_SECURE_PERIMETER -> true;
            default -> false;
        };
    }

    @Override
    public void refreshAllObjectives() {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            plugin.getLogger().warning("[Objectives] refreshAllObjectives: No active round, skipping");
            return;
        }

        plugin.getLogger().info("[Objectives] Refreshing objectives for all regions...");

        // Get all regions and spawn objectives as needed
        List<RegionStatus> allStatuses = regionService.getAllRegionStatuses();
        plugin.getLogger().info("[Objectives] Found " + allStatuses.size() + " regions to check");

        for (RegionStatus status : allStatuses) {
            // Skip protected regions (home regions)
            if (status.state() == RegionState.PROTECTED) {
                plugin.getLogger().fine("[Objectives] Skipping " + status.regionId() + " - PROTECTED");
                continue;
            }

            // Skip fortified regions
            if (status.state() == RegionState.FORTIFIED) {
                plugin.getLogger().fine("[Objectives] Skipping " + status.regionId() + " - FORTIFIED");
                continue;
            }

            int activeCount = countActiveObjectives(status.regionId());
            int maxObjectives = config.getObjectivesPerRegion();

            plugin.getLogger().info("[Objectives] Region " + status.regionId() +
                    " (state=" + status.state() + "): " + activeCount + "/" + maxObjectives + " active objectives");

            // Spawn objectives if below max
            if (activeCount < maxObjectives) {
                plugin.getLogger().info("[Objectives] Region " + status.regionId() +
                        " has " + activeCount + "/" + maxObjectives + " objectives, spawning more...");
                SpawnResult result = spawnObjectivesForRegion(status.regionId());
                plugin.getLogger().info("[Objectives] Spawn result for " + status.regionId() + ": " + result);
            }
        }
    }

    @Override
    public void expireObjectivesInRegion(String regionId) {
        getCurrentRoundId().ifPresent(roundId -> {
            db.expireAllInRegion(regionId, roundId);

            // Clear block tracking for this region's objectives
            objectiveBlocksTracking.remove(regionId + ":SECURE_PERIMETER");
            objectiveBlocksTracking.remove(regionId + ":SUPPLY_ROUTE");
            objectiveBlocksTracking.remove(regionId + ":SABOTAGE_DEFENSES");

            // Clear completed objective types tracking for this region (fresh slate for new owner)
            completedObjectiveTypes.remove(regionId + ":" + ObjectiveCategory.SETTLEMENT.name());
            completedObjectiveTypes.remove(regionId + ":" + ObjectiveCategory.RAID.name());

            plugin.getLogger().info("[Objectives] Expired all objectives in " + regionId + " (cleared completed types tracking)");
        });
    }

    // ==================== OBJECTIVE PROGRESS ====================

    @Override
    public Optional<RegionObjective> updateProgress(int objectiveId, double progress) {
        db.updateProgress(objectiveId, progress);
        return db.getObjective(objectiveId);
    }

    @Override
    public Optional<RegionObjective> addProgress(int objectiveId, double progressDelta) {
        Optional<RegionObjective> objOpt = db.getObjective(objectiveId);
        if (objOpt.isEmpty()) {
            return Optional.empty();
        }

        double newProgress = Math.min(1.0, objOpt.get().progress() + progressDelta);
        db.updateProgress(objectiveId, newProgress);
        return db.getObjective(objectiveId);
    }

    // ==================== OBJECTIVE COMPLETION ====================

    @Override
    public CompleteResult completeObjective(int objectiveId, UUID playerUuid, String team) {
        Optional<RegionObjective> objOpt = db.getObjective(objectiveId);
        if (objOpt.isEmpty()) {
            return CompleteResult.OBJECTIVE_NOT_FOUND;
        }

        RegionObjective objective = objOpt.get();

        if (objective.status() == ObjectiveStatus.COMPLETED) {
            return CompleteResult.ALREADY_COMPLETED;
        }

        if (objective.status() == ObjectiveStatus.EXPIRED) {
            return CompleteResult.OBJECTIVE_EXPIRED;
        }

        // Check if the region can be contested
        Optional<RegionStatus> regionStatusOpt = regionService.getRegionStatus(objective.regionId());
        if (regionStatusOpt.isPresent()) {
            RegionStatus regionStatus = regionStatusOpt.get();

            // FORTIFIED and PROTECTED regions cannot have objectives completed
            if (regionStatus.state() == RegionState.FORTIFIED ||
                regionStatus.state() == RegionState.PROTECTED) {
                // Notify the player that objective completion is blocked (with debounce)
                long now = System.currentTimeMillis();
                Long lastWarning = lastRegionOwnedWarning.get(playerUuid);
                if (lastWarning == null || (now - lastWarning) > REGION_OWNED_WARNING_COOLDOWN_MS) {
                    lastRegionOwnedWarning.put(playerUuid, now);
                    org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                    if (player != null) {
                        String reason = regionStatus.state() == RegionState.PROTECTED ?
                            "This is a protected home region." :
                            "This region is fortified and cannot be attacked yet.";
                        player.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                                .append(net.kyori.adventure.text.Component.text(reason)
                                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                    }
                }
                return CompleteResult.REGION_ALREADY_OWNED;
            }

            // For OWNED regions: RAID objectives are allowed (they contest the region)
            // SETTLEMENT objectives are NOT allowed in owned regions
            if (regionStatus.state() == RegionState.OWNED && !objective.type().isRaid()) {
                long now = System.currentTimeMillis();
                Long lastWarning = lastRegionOwnedWarning.get(playerUuid);
                if (lastWarning == null || (now - lastWarning) > REGION_OWNED_WARNING_COOLDOWN_MS) {
                    lastRegionOwnedWarning.put(playerUuid, now);
                    org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                    if (player != null) {
                        player.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                                .append(net.kyori.adventure.text.Component.text("This region is already captured - settlement objectives disabled.")
                                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                    }
                }
                return CompleteResult.REGION_ALREADY_OWNED;
            }
        }

        // Check cooldown
        if (isOnCooldown(playerUuid, objective.regionId(), objective.type())) {
            return CompleteResult.ON_COOLDOWN;
        }

        // Mark completed
        db.completeObjective(objectiveId, team);
        db.recordCompletion(objectiveId, playerUuid, team);

        // Set cooldown
        long cooldownMs = config.getObjectiveCooldownMinutes() * 60 * 1000L;
        db.setCooldown(playerUuid, objective.regionId(), objective.roundId(),
                objective.type(), System.currentTimeMillis() + cooldownMs);

        // Award influence
        regionService.addInfluence(objective.regionId(), team, objective.getInfluenceReward(), playerUuid);

        plugin.getLogger().info("[Objectives] " + playerUuid + " completed " +
                objective.type().getDisplayName() + " in " + objective.regionId() +
                " for " + objective.getInfluenceReward() + " IP");

        // Track completed objective type to prevent repeat spawns
        String completedKey = objective.regionId() + ":" + objective.type().getCategory().name();
        completedObjectiveTypes.computeIfAbsent(completedKey, k -> ConcurrentHashMap.newKeySet())
                .add(objective.type());
        plugin.getLogger().fine("[Objectives] Tracked " + objective.type() + " as completed in " + completedKey);

        // Notify callback
        if (completionCallback != null) {
            db.getObjective(objectiveId).ifPresent(completed ->
                    completionCallback.onObjectiveCompleted(completed, playerUuid, team));
        }

        return CompleteResult.SUCCESS;
    }

    @Override
    public boolean isOnCooldown(UUID playerUuid, String regionId, ObjectiveType type) {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            return false;
        }

        long cooldownUntil = db.getCooldownUntil(playerUuid, regionId, roundIdOpt.get(), type);
        return System.currentTimeMillis() < cooldownUntil;
    }

    @Override
    public long getCooldownRemaining(UUID playerUuid, String regionId, ObjectiveType type) {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            return 0;
        }

        long cooldownUntil = db.getCooldownUntil(playerUuid, regionId, roundIdOpt.get(), type);
        long remaining = cooldownUntil - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    // ==================== OBJECTIVE DETECTION ====================

    @Override
    public void onBlockDestroyed(UUID playerUuid, String team, String regionId,
                                  int x, int y, int z, String blockType) {
        String blockKey = x + "," + y + "," + z;

        // Check for raid objectives (sabotage)
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_SABOTAGE_DEFENSES) {
                // Check if this block position already earned progress (anti-cheese)
                String trackingKey = regionId + ":SABOTAGE_DEFENSES";
                Set<String> trackedBlocks = objectiveBlocksTracking.computeIfAbsent(trackingKey, k -> ConcurrentHashMap.newKeySet());

                if (trackedBlocks.contains(blockKey)) {
                    // Already earned progress at this location - skip
                    continue;
                }

                // Track this block and add progress (1/50 blocks = 0.02 progress)
                trackedBlocks.add(blockKey);
                double newProgress = obj.progress() + 0.02;
                updateProgress(obj.id(), newProgress);

                if (newProgress >= 1.0) {
                    completeObjective(obj.id(), playerUuid, team);
                }
                break;
            }

            // Check for destroy cache - if block is an enemy-placed chest
            if (obj.type() == ObjectiveType.RAID_DESTROY_CACHE) {
                if (blockType.contains("CHEST")) {
                    // Get the defending team for this region
                    Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                    if (statusOpt.isEmpty()) continue;

                    String defenderTeam = statusOpt.get().ownerTeam();
                    if (defenderTeam == null) continue;

                    // Player must be attacking (not the defender)
                    if (defenderTeam.equalsIgnoreCase(team)) continue;

                    // Remove from tracking if it was tracked
                    String trackingKey = regionId + ":" + defenderTeam;
                    Set<String> enemyChests = enemyChestTracking.get(trackingKey);
                    if (enemyChests != null) {
                        enemyChests.remove(blockKey);
                    }

                    // Complete the objective - any chest broken by attacker counts
                    completeObjective(obj.id(), playerUuid, team);
                    plugin.getLogger().info("[Objectives] Supply Cache destroyed by " + playerUuid +
                            " at " + x + "," + y + "," + z + " in " + regionId);
                    break;
                }
            }
        }

        // Check for settlement objectives - deduct progress if wall/road blocks broken
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)) {
            switch (obj.type()) {
                case SETTLEMENT_SECURE_PERIMETER -> {
                    if (isWallBlock(blockType)) {
                        String trackingKey = regionId + ":SECURE_PERIMETER";
                        Set<String> trackedBlocks = objectiveBlocksTracking.get(trackingKey);

                        if (trackedBlocks != null && trackedBlocks.remove(blockKey)) {
                            // This block was tracked - deduct progress
                            double newProgress = Math.max(0, obj.progress() - 0.01);
                            updateProgress(obj.id(), newProgress);
                        }
                    }
                }
                case SETTLEMENT_SUPPLY_ROUTE -> {
                    if (isRoadBlock(blockType)) {
                        String trackingKey = regionId + ":SUPPLY_ROUTE";
                        Set<String> trackedBlocks = objectiveBlocksTracking.get(trackingKey);

                        if (trackedBlocks != null && trackedBlocks.remove(blockKey)) {
                            // This block was tracked - deduct progress
                            double newProgress = Math.max(0, obj.progress() - (1.0 / 64.0));
                            updateProgress(obj.id(), newProgress);
                        }
                    }
                }
                default -> {}
            }
        }
    }

    @Override
    public void onBlockPlaced(UUID playerUuid, String team, String regionId,
                               int x, int y, int z, String blockType) {
        String blockKey = x + "," + y + "," + z;

        // Check for settlement objectives
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)) {
            switch (obj.type()) {
                case SETTLEMENT_SECURE_PERIMETER -> {
                    // Check if it's a wall-like block
                    if (isWallBlock(blockType)) {
                        // Check if this block position already earned progress
                        String trackingKey = regionId + ":SECURE_PERIMETER";
                        Set<String> trackedBlocks = objectiveBlocksTracking.computeIfAbsent(trackingKey, k -> ConcurrentHashMap.newKeySet());

                        if (trackedBlocks.contains(blockKey)) {
                            // Already earned progress at this location - skip
                            continue;
                        }

                        // Track this block and add progress
                        trackedBlocks.add(blockKey);
                        double newProgress = obj.progress() + 0.01; // 1/100 blocks
                        updateProgress(obj.id(), newProgress);

                        if (newProgress >= 1.0) {
                            completeObjective(obj.id(), playerUuid, team);
                        }
                    }
                }
                case SETTLEMENT_SUPPLY_ROUTE -> {
                    // Check if it's a road block
                    if (isRoadBlock(blockType)) {
                        // Supply routes must be built near the border of friendly territory
                        // This ensures roads actually connect to adjacent friendly regions
                        int borderDistance = config.getRegionSize() / 4; // Within 1/4 of region from border

                        if (!isNearFriendlyBorder(x, z, regionId, team, borderDistance)) {
                            // Not near a friendly border - don't count this block
                            // Silently skip (don't spam messages for every block)
                            continue;
                        }

                        // Check if this block position already earned progress
                        String trackingKey = regionId + ":SUPPLY_ROUTE";
                        Set<String> trackedBlocks = objectiveBlocksTracking.computeIfAbsent(trackingKey, k -> ConcurrentHashMap.newKeySet());

                        if (trackedBlocks.contains(blockKey)) {
                            // Already earned progress at this location - skip
                            continue;
                        }

                        // Track this block and add progress
                        trackedBlocks.add(blockKey);
                        double newProgress = obj.progress() + (1.0 / 64.0); // 1/64 blocks
                        updateProgress(obj.id(), newProgress);

                        if (newProgress >= 1.0) {
                            completeObjective(obj.id(), playerUuid, team);
                        }
                    }
                }
                default -> {}
            }
        }
    }

    private boolean isWallBlock(String blockType) {
        return blockType.contains("WALL") || blockType.contains("FENCE") ||
                blockType.contains("BRICK") || blockType.contains("STONE") ||
                blockType.contains("COBBLESTONE") || blockType.contains("DEEPSLATE");
    }

    private boolean isRoadBlock(String blockType) {
        return blockType.contains("PATH") || blockType.equals("GRAVEL") ||
                blockType.equals("COBBLESTONE") || blockType.contains("STONE_BRICK");
    }

    /**
     * Checks if a block position is near the border of friendly territory.
     * For Supply Route objective - roads should connect to adjacent friendly regions.
     *
     * @param x Block X coordinate
     * @param z Block Z coordinate
     * @param regionId The region the block is in
     * @param team The team building the road
     * @param borderDistance How close to the border to check (in blocks)
     * @return true if near a border with a friendly-owned region
     */
    private boolean isNearFriendlyBorder(int x, int z, String regionId, String team, int borderDistance) {
        int[] center = regionCenters.get(regionId);
        if (center == null) return false;

        int regionSize = config.getRegionSize();
        int halfRegion = regionSize / 2;

        // Calculate region boundaries
        int minX = center[0] - halfRegion;
        int maxX = center[0] + halfRegion;
        int minZ = center[1] - halfRegion;
        int maxZ = center[1] + halfRegion;

        // Check each direction for friendly border
        // North border (negative Z)
        if (z <= minZ + borderDistance) {
            String northRegion = getAdjacentRegionInDirection(regionId, "NORTH");
            if (northRegion != null && isRegionOwnedByTeam(northRegion, team)) {
                return true;
            }
        }

        // South border (positive Z)
        if (z >= maxZ - borderDistance) {
            String southRegion = getAdjacentRegionInDirection(regionId, "SOUTH");
            if (southRegion != null && isRegionOwnedByTeam(southRegion, team)) {
                return true;
            }
        }

        // West border (negative X)
        if (x <= minX + borderDistance) {
            String westRegion = getAdjacentRegionInDirection(regionId, "WEST");
            if (westRegion != null && isRegionOwnedByTeam(westRegion, team)) {
                return true;
            }
        }

        // East border (positive X)
        if (x >= maxX - borderDistance) {
            String eastRegion = getAdjacentRegionInDirection(regionId, "EAST");
            if (eastRegion != null && isRegionOwnedByTeam(eastRegion, team)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the adjacent region in a specific direction.
     */
    private String getAdjacentRegionInDirection(String regionId, String direction) {
        if (regionId == null || regionId.length() < 2) return null;

        char row = regionId.charAt(0);
        int col;
        try {
            col = Integer.parseInt(regionId.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }

        return switch (direction) {
            case "NORTH" -> row > 'A' ? String.valueOf((char)(row - 1)) + col : null;
            case "SOUTH" -> row < 'D' ? String.valueOf((char)(row + 1)) + col : null;
            case "WEST" -> col > 1 ? String.valueOf(row) + (col - 1) : null;
            case "EAST" -> col < 4 ? String.valueOf(row) + (col + 1) : null;
            default -> null;
        };
    }

    /**
     * Checks if a region is owned by a specific team (including OWNED, FORTIFIED, PROTECTED states).
     */
    private boolean isRegionOwnedByTeam(String regionId, String team) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return false;

        RegionStatus status = statusOpt.get();
        // Check if the region is owned by this team (any owned state)
        return status.isOwnedBy(team);
    }

    @Override
    public void onPlayerKill(UUID killerUuid, UUID victimUuid, String regionId) {
        // Check for assassination objective
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_ASSASSINATE_COMMANDER) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) continue;

                String defenderTeam = statusOpt.get().ownerTeam();
                if (defenderTeam == null) continue;

                // Check if the victim is a valid assassination target (commander or officer)
                if (isAssassinationTarget(victimUuid, defenderTeam)) {
                    // Get killer's team to verify they're an attacker
                    if (teamService != null) {
                        Optional<String> killerTeamOpt = teamService.getPlayerTeam(killerUuid);
                        if (killerTeamOpt.isPresent() && !killerTeamOpt.get().equalsIgnoreCase(defenderTeam)) {
                            // Valid assassination - complete objective
                            completeObjective(obj.id(), killerUuid, killerTeamOpt.get());

                            // Remove glowing effect from the victim immediately
                            org.bukkit.entity.Player victim = org.bukkit.Bukkit.getPlayer(victimUuid);
                            if (victim != null) {
                                victim.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
                            }

                            plugin.getLogger().info("[Objectives] Assassination completed! " + killerUuid +
                                    " killed commander/officer " + victimUuid + " in " + regionId);
                        }
                    }
                }
                break;
            }

            // Check for Hold Ground defense - if defender kills attacker who had progress
            if (obj.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) continue;

                String defenderTeam = statusOpt.get().ownerTeam();
                if (defenderTeam == null) continue;

                if (teamService != null) {
                    Optional<String> killerTeamOpt = teamService.getPlayerTeam(killerUuid);
                    Optional<String> victimTeamOpt = teamService.getPlayerTeam(victimUuid);

                    // Check if defender killed attacker
                    if (killerTeamOpt.isPresent() && victimTeamOpt.isPresent() &&
                        killerTeamOpt.get().equalsIgnoreCase(defenderTeam) &&
                        !victimTeamOpt.get().equalsIgnoreCase(defenderTeam)) {

                        // Check if victim had hold ground progress
                        int victimProgress = db.getHoldGroundProgress(obj.id(), victimUuid);
                        if (victimProgress > 0) {
                            // Clear their progress
                            db.clearHoldGroundProgressForPlayer(obj.id(), victimUuid);

                            // Reduce enemy influence for successful defense
                            double defenseReward = config.getDefenseObjectiveReward() * (victimProgress / 60.0);
                            if (defenseReward > 0) {
                                regionService.reduceInfluence(regionId, victimTeamOpt.get(), defenseReward);

                                // Notify the defender
                                org.bukkit.entity.Player killer = org.bukkit.Bukkit.getPlayer(killerUuid);
                                if (killer != null) {
                                    killer.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                                            .append(net.kyori.adventure.text.Component.text("🛡 Territory defended! ")
                                                    .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                                            .append(net.kyori.adventure.text.Component.text("-" + (int)defenseReward + " enemy IP")
                                                    .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
                                }
                            }

                            plugin.getLogger().info("[Objectives] Hold Ground defense - " + killerUuid +
                                    " killed attacker " + victimUuid + " with " + victimProgress + "s progress in " + regionId);
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a player is a valid assassination target (commander or officer of the defending team).
     */
    private boolean isAssassinationTarget(UUID playerUuid, String team) {
        if (divisionService == null) return false;

        Optional<DivisionMember> memberOpt = divisionService.getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;

        DivisionMember member = memberOpt.get();

        // Check if they're a commander or officer
        if (member.role() != DivisionRole.COMMANDER && member.role() != DivisionRole.OFFICER) {
            return false;
        }

        // Get their division to verify team
        return divisionService.getDivision(member.divisionId())
                .map(division -> division.team().equalsIgnoreCase(team))
                .orElse(false);
    }

    @Override
    public List<UUID> getAssassinationTargets(String regionId) {
        List<UUID> targets = new ArrayList<>();

        if (divisionService == null || teamService == null) return targets;

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return targets;

        String defenderTeam = statusOpt.get().ownerTeam();
        if (defenderTeam == null) return targets;

        // Get all divisions for the defending team
        List<Division> divisions = divisionService.getDivisionsForTeam(defenderTeam);

        for (Division division : divisions) {
            List<DivisionMember> members = divisionService.getMembers(division.divisionId());
            for (DivisionMember member : members) {
                // Only commanders and officers are targets
                if (member.role() == DivisionRole.COMMANDER || member.role() == DivisionRole.OFFICER) {
                    UUID playerUuid = UUID.fromString(member.playerUuid());

                    // Check if player is online and in the region
                    org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        String playerRegion = regionService.getRegionIdForLocation(
                                player.getLocation().getBlockX(),
                                player.getLocation().getBlockZ()
                        );
                        if (regionId.equals(playerRegion)) {
                            targets.add(playerUuid);
                        }
                    }
                }
            }
        }

        return targets;
    }

    @Override
    public boolean canSpawnAssassinateObjective(String regionId) {
        if (divisionService == null) return false;

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return false;

        String defenderTeam = statusOpt.get().ownerTeam();
        if (defenderTeam == null) return false;

        // Check if there are any divisions for the defending team
        List<Division> divisions = divisionService.getDivisionsForTeam(defenderTeam);
        if (divisions.isEmpty()) return false;

        // Check if any commanders or officers from the defending team are online
        for (Division division : divisions) {
            List<DivisionMember> members = divisionService.getMembers(division.divisionId());
            for (DivisionMember member : members) {
                if (member.role() == DivisionRole.COMMANDER || member.role() == DivisionRole.OFFICER) {
                    UUID playerUuid = UUID.fromString(member.playerUuid());
                    org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        return true; // At least one target is online
                    }
                }
            }
        }

        return false;
    }

    @Override
    public List<int[]> getEnemyChestLocations(String regionId, String attackerTeam) {
        List<int[]> locations = new ArrayList<>();

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return locations;

        String defenderTeam = statusOpt.get().ownerTeam();
        if (defenderTeam == null || defenderTeam.equalsIgnoreCase(attackerTeam)) return locations;

        String trackingKey = regionId + ":" + defenderTeam;
        Set<String> enemyChests = enemyChestTracking.get(trackingKey);

        if (enemyChests != null) {
            for (String blockKey : enemyChests) {
                String[] parts = blockKey.split(",");
                if (parts.length == 3) {
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        int z = Integer.parseInt(parts[2]);
                        locations.add(new int[]{x, y, z});
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return locations;
    }

    @Override
    public boolean canSpawnDestroyCacheObjective(String regionId) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return false;

        String defenderTeam = statusOpt.get().ownerTeam();
        if (defenderTeam == null) return false;

        // First check tracked chests from this session
        String trackingKey = regionId + ":" + defenderTeam;
        Set<String> enemyChests = enemyChestTracking.get(trackingKey);
        if (enemyChests != null && !enemyChests.isEmpty()) {
            return true;
        }

        // If no tracked chests, scan the region for existing chests
        // This handles chests that existed before the plugin started tracking
        World world = roundService.getGameWorld().orElse(null);
        if (world == null) return false;

        int[] center = regionCenters.get(regionId);
        if (center == null) return false;

        int regionSize = config.getRegionSize();
        int halfSize = regionSize / 2;
        int scanRadius = Math.min(halfSize, 64); // Scan up to 64 blocks from center

        // Scan for chests in the region (limited scan for performance)
        int foundChests = 0;
        for (int x = center[0] - scanRadius; x <= center[0] + scanRadius && foundChests == 0; x += 8) {
            for (int z = center[1] - scanRadius; z <= center[1] + scanRadius && foundChests == 0; z += 8) {
                // Check if this coordinate is in the region
                String checkRegion = regionService.getRegionIdForLocation(x, z);
                if (!regionId.equals(checkRegion)) continue;

                for (int y = world.getMinHeight(); y < world.getMaxHeight() && foundChests == 0; y += 16) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    if (block.getType().name().contains("CHEST")) {
                        foundChests++;
                    }
                }
            }
        }

        return foundChests > 0;
    }

    @Override
    public void onContainerPlaced(UUID playerUuid, String team, String regionId, int x, int y, int z, String blockType) {
        // Track chests for Destroy Supply Cache objective
        if (blockType.contains("CHEST")) {
            String trackingKey = regionId + ":" + team;
            Set<String> teamChests = enemyChestTracking.computeIfAbsent(trackingKey, k -> ConcurrentHashMap.newKeySet());
            String blockKey = x + "," + y + "," + z;
            teamChests.add(blockKey);
            plugin.getLogger().fine("[Objectives] Chest placed by " + team + " at " + x + "," + y + "," + z + " in " + regionId);
        }

        // Anti-cheese: Check container placement cooldown
        long now = System.currentTimeMillis();
        Long lastPlacement = containerPlacementCooldowns.get(playerUuid);
        if (lastPlacement != null && (now - lastPlacement) < CONTAINER_PLACEMENT_COOLDOWN_MS) {
            // On cooldown - don't trigger progress update for this placement
            return;
        }
        containerPlacementCooldowns.put(playerUuid, now);

        // Update Resource Depot progress when a container is placed
        // The listener will call updateResourceDepotProgress with container counts after a delay
    }

    /**
     * Checks if a player is on container placement cooldown.
     * @return true if player should not earn progress for container placement
     */
    public boolean isOnContainerPlacementCooldown(UUID playerUuid) {
        Long lastPlacement = containerPlacementCooldowns.get(playerUuid);
        if (lastPlacement == null) return false;
        return (System.currentTimeMillis() - lastPlacement) < CONTAINER_PLACEMENT_COOLDOWN_MS;
    }

    /**
     * Updates Resource Depot progress based on qualifying container count.
     * A "qualifying container" has at least the minimum items per container (default 500).
     * Called by the listener after counting containers around the objective location.
     *
     * @param qualifyingContainers Number of containers with 500+ items each
     * @param totalItems Total items across all containers (for display only)
     */
    @Override
    public void updateResourceDepotProgress(UUID playerUuid, String team, String regionId, int qualifyingContainers, int totalItems) {
        // Verify this is a neutral region (settlement objectives only apply to neutral regions)
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            plugin.getLogger().warning("[ResourceDepot] Region " + regionId + " not found");
            return;
        }

        RegionStatus status = statusOpt.get();
        if (status.state() != RegionState.NEUTRAL) {
            plugin.getLogger().fine("[ResourceDepot] Region " + regionId + " is " + status.state() + ", not NEUTRAL - skipping");
            return;
        }

        // Find the Resource Depot objective
        RegionObjective depotObj = null;
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)) {
            if (obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT) {
                depotObj = obj;
                break;
            }
        }

        if (depotObj == null) {
            plugin.getLogger().warning("[ResourceDepot] No active Resource Depot objective in " + regionId);
            return;
        }

        // Get configurable requirements
        int requiredContainers = config.getResourceDepotMinContainers();
        int minItemsPerContainer = config.getResourceDepotMinItemsPerContainer();

        // Store actual counts for UI display: [qualifyingContainers, totalItems, requiredContainers, minItemsPerContainer]
        resourceDepotLastCounts.put(regionId, new int[]{qualifyingContainers, totalItems, requiredContainers, minItemsPerContainer});

        // Log the current state
        plugin.getLogger().info("[ResourceDepot] Progress update in " + regionId +
                ": " + qualifyingContainers + "/" + requiredContainers + " containers with " + minItemsPerContainer + "+ items each");

        // Check completion - need required number of qualifying containers
        if (qualifyingContainers >= requiredContainers) {
            completeObjective(depotObj.id(), playerUuid, team);
            resourceDepotLastCounts.remove(regionId); // Clean up
            plugin.getLogger().info("[ResourceDepot] COMPLETED in " + regionId +
                    " by " + playerUuid + " (" + qualifyingContainers + " containers with " + minItemsPerContainer + "+ items each, " + totalItems + " total items)");
        } else {
            // Progress is simply the ratio of qualifying containers
            double newProgress = (double) qualifyingContainers / requiredContainers;

            updateProgress(depotObj.id(), newProgress);
            plugin.getLogger().info("[ResourceDepot] Progress: " + String.format("%.1f%%", newProgress * 100) +
                    " (" + qualifyingContainers + "/" + requiredContainers + " containers stocked)");
        }
    }

    @Override
    public Optional<int[]> getResourceDepotCounts(String regionId) {
        return Optional.ofNullable(resourceDepotLastCounts.get(regionId));
    }

    @Override
    public void onContainerBroken(UUID playerUuid, String team, String regionId, int containerCount, int totalItems) {
        // Delegate to updateResourceDepotProgress - the listener now handles location-based scanning
        plugin.getLogger().fine("[ResourceDepot] Container broken, recounting...");
        updateResourceDepotProgress(playerUuid, team, regionId, containerCount, totalItems);
    }

    @Override
    public void onContainerInteract(UUID playerUuid, String team, String regionId, int containerCount, int totalItems) {
        // Delegate to updateResourceDepotProgress - the listener now handles location-based scanning
        plugin.getLogger().fine("[ResourceDepot] Container closed, recounting...");
        updateResourceDepotProgress(playerUuid, team, regionId, containerCount, totalItems);
    }

    @Override
    public void tickHoldGroundObjectives(Map<UUID, HoldGroundPlayerData> playerData) {
        if (playerData.isEmpty()) return;

        // Get all regions with active hold ground objectives
        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            // Skip non-owned regions (hold ground is a raid objective)
            if (status.state() != RegionState.OWNED && status.state() != RegionState.CONTESTED) {
                continue;
            }

            String regionId = status.regionId();
            List<RegionObjective> objectives = getActiveObjectives(regionId, ObjectiveCategory.RAID);

            // Find hold ground objective
            RegionObjective holdGroundObj = null;
            for (RegionObjective obj : objectives) {
                if (obj.type() == ObjectiveType.RAID_HOLD_GROUND) {
                    holdGroundObj = obj;
                    break;
                }
            }

            if (holdGroundObj == null) continue;

            // Get hold zone info
            int[] center = regionCenters.get(regionId);
            if (center == null) continue;

            int holdRadius = config.getRegionSize() / 8; // Hold zone is 1/8 of region size
            String defenderTeam = status.ownerTeam();

            // Check each player
            for (Map.Entry<UUID, HoldGroundPlayerData> entry : playerData.entrySet()) {
                UUID playerId = entry.getKey();
                HoldGroundPlayerData data = entry.getValue();

                // Player must be in this region
                if (!regionId.equals(data.regionId())) continue;

                // Player must be an attacker (not the defender)
                if (defenderTeam != null && defenderTeam.equalsIgnoreCase(data.team())) continue;

                // Check if player is in the hold zone
                double distance = Math.sqrt(
                        Math.pow(data.x() - center[0], 2) +
                        Math.pow(data.z() - center[1], 2)
                );

                if (distance <= holdRadius) {
                    // Player is in hold zone - add progress
                    int currentSeconds = db.getHoldGroundProgress(holdGroundObj.id(), playerId);
                    int newSeconds = currentSeconds + 1;
                    db.updateHoldGroundProgress(holdGroundObj.id(), playerId, data.team(), newSeconds);

                    // Check for completion (60 seconds)
                    if (newSeconds >= 60) {
                        completeObjective(holdGroundObj.id(), playerId, data.team());
                        db.clearHoldGroundProgress(holdGroundObj.id());
                        plugin.getLogger().info("[Objectives] Hold Ground completed by " + playerId +
                                " in " + regionId + " after " + newSeconds + " seconds");
                    } else {
                        // Update objective progress for UI
                        double progress = newSeconds / 60.0;
                        updateProgress(holdGroundObj.id(), progress);
                    }
                }
            }
        }
    }

    @Override
    public Optional<int[]> getHoldZoneInfo(String regionId) {
        // Check if there's an active hold ground objective in this region
        List<RegionObjective> objectives = getActiveObjectives(regionId, ObjectiveCategory.RAID);
        boolean hasHoldGround = objectives.stream()
                .anyMatch(obj -> obj.type() == ObjectiveType.RAID_HOLD_GROUND);

        if (!hasHoldGround) return Optional.empty();

        int[] center = regionCenters.get(regionId);
        if (center == null) return Optional.empty();

        int holdRadius = config.getRegionSize() / 8;
        return Optional.of(new int[]{center[0], center[1], holdRadius});
    }

    @Override
    public void clearTrackedData() {
        objectiveBlocksTracking.clear();
        enemyChestTracking.clear();
        plantedExplosives.clear();

        // Remove glowing from any active intel carriers before clearing
        for (IntelCarrierData data : intelCarriers.values()) {
            if (data.carrierUuid() != null && !data.isDropped()) {
                org.bukkit.entity.Player carrier = org.bukkit.Bukkit.getPlayer(data.carrierUuid());
                if (carrier != null) {
                    carrier.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
                    removeIntelFromInventory(carrier);
                }
            }
        }
        intelCarriers.clear();
        intelObjectiveSpawnTimes.clear();

        // Clear completed objective types tracking (for fresh spawns)
        completedObjectiveTypes.clear();

        // Clear Resource Depot anti-cheese tracking
        resourceDepotLastUpdate.clear();
        resourceDepotLastCounts.clear();
        containerPlacementCooldowns.clear();

        // Remove any spawned intel items
        for (UUID itemId : spawnedIntelItems.values()) {
            org.bukkit.entity.Entity item = org.bukkit.Bukkit.getEntity(itemId);
            if (item != null) {
                item.remove();
            }
        }
        spawnedIntelItems.clear();

        lastRegionOwnedWarning.clear();
        plugin.getLogger().info("[Objectives] Cleared all tracked data for new round");
    }

    // ==================== PLANT EXPLOSIVE OBJECTIVE ====================

    @Override
    public boolean onTntPlaced(UUID playerUuid, String team, String regionId, int x, int y, int z) {
        // Check for active plant explosive objective in this region
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_PLANT_EXPLOSIVE) {
                // Verify player is attacking team
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) return false;

                String defenderTeam = statusOpt.get().ownerTeam();
                if (defenderTeam != null && defenderTeam.equalsIgnoreCase(team)) {
                    return false; // Defender can't complete attacker objective
                }

                // Check if TNT is placed near the objective location
                if (obj.hasLocation()) {
                    int dist = Math.abs(obj.locationX() - x) + Math.abs(obj.locationZ() - z);
                    if (dist > 5) {
                        // Not close enough to objective marker
                        return false;
                    }
                }

                // Check if there's already a planted explosive in this region
                if (plantedExplosives.containsKey(regionId)) {
                    return false; // Only one at a time
                }

                // Start the defend timer (30 seconds)
                PlantedExplosiveData data = new PlantedExplosiveData(
                        obj.id(), playerUuid, team, x, y, z,
                        System.currentTimeMillis(), 30
                );
                plantedExplosives.put(regionId, data);

                plugin.getLogger().info("[Objectives] TNT planted for objective by " + playerUuid +
                        " at " + x + "," + y + "," + z + " in " + regionId + " - 30s defend timer started");

                return true;
            }
        }
        return false;
    }

    @Override
    public void onTntBroken(UUID playerUuid, String team, String regionId, int x, int y, int z) {
        PlantedExplosiveData data = plantedExplosives.get(regionId);
        if (data == null) return;

        // Check if this is the planted TNT location
        if (data.x() == x && data.y() == y && data.z() == z) {
            // TNT defused!
            plantedExplosives.remove(regionId);

            // Reset objective progress
            updateProgress(data.objectiveId(), 0.0);

            // Reduce attacker influence as reward for successful defense
            double defenseReward = config.getDefenseObjectiveReward();
            regionService.reduceInfluence(regionId, data.planterTeam(), defenseReward);

            // Notify the defender
            org.bukkit.entity.Player defender = org.bukkit.Bukkit.getPlayer(playerUuid);
            if (defender != null) {
                defender.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                        .append(net.kyori.adventure.text.Component.text("💣 Explosive defused! ")
                                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                        .append(net.kyori.adventure.text.Component.text("-" + (int)defenseReward + " enemy IP")
                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
                defender.playSound(defender.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            }

            plugin.getLogger().info("[Objectives] TNT defused by " + playerUuid +
                    " at " + x + "," + y + "," + z + " in " + regionId + " - reduced " + defenseReward + " enemy IP");
        }
    }

    @Override
    public void tickPlantedExplosives() {
        Iterator<Map.Entry<String, PlantedExplosiveData>> iterator = plantedExplosives.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, PlantedExplosiveData> entry = iterator.next();
            String regionId = entry.getKey();
            PlantedExplosiveData data = entry.getValue();

            int remaining = data.getSecondsRemaining();

            if (remaining <= 0) {
                // Timer complete - explosive detonates, objective complete!
                completeObjective(data.objectiveId(), data.planterUuid(), data.planterTeam());
                iterator.remove();

                plugin.getLogger().info("[Objectives] Plant Explosive completed by " + data.planterUuid() +
                        " in " + regionId + " - explosive detonated!");

                // Create real explosion at location
                World world = roundService.getGameWorld().orElse(null);
                if (world != null) {
                    Location loc = new Location(world, data.x() + 0.5, data.y(), data.z() + 0.5);

                    // Remove the TNT block first (it's about to explode)
                    world.getBlockAt(data.x(), data.y(), data.z()).setType(org.bukkit.Material.AIR);

                    // Create real explosion - power 4 (same as regular TNT), fire=false, breaks blocks=true
                    world.createExplosion(loc, 4.0f, false, true);
                }
            } else {
                // Update objective progress for UI
                double progress = data.getProgress();
                updateProgress(data.objectiveId(), progress);
            }
        }
    }

    @Override
    public Optional<PlantedExplosiveInfo> getPlantedExplosiveInfo(String regionId) {
        PlantedExplosiveData data = plantedExplosives.get(regionId);
        if (data == null) return Optional.empty();

        return Optional.of(new PlantedExplosiveInfo(
                regionId,
                data.objectiveId(),
                data.planterUuid(),
                data.planterTeam(),
                data.x(), data.y(), data.z(),
                data.getSecondsRemaining(),
                data.defendSeconds()
        ));
    }

    // ==================== CAPTURE INTEL OBJECTIVE ====================

    @Override
    public void spawnIntelItem(String regionId, int x, int y, int z) {
        World world = roundService.getGameWorld().orElse(null);
        if (world == null) return;

        // Remove existing intel item if any
        UUID existingItemId = spawnedIntelItems.get(regionId);
        if (existingItemId != null) {
            org.bukkit.entity.Entity existing = org.bukkit.Bukkit.getEntity(existingItemId);
            if (existing != null) {
                existing.remove();
            }
        }

        // Track spawn time only on FIRST spawn (not respawns after defender recovery)
        // This ensures the 10-minute lifetime starts from objective creation
        intelObjectiveSpawnTimes.putIfAbsent(regionId, System.currentTimeMillis());

        // Create the intel item (enchanted map with custom name)
        org.bukkit.inventory.ItemStack intelItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
        org.bukkit.inventory.meta.ItemMeta meta = intelItem.getItemMeta();

        // Calculate remaining time for lore display
        int secondsRemaining = getIntelObjectiveSecondsRemaining(regionId);
        int minutesRemaining = secondsRemaining / 60;

        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text("⚡ SECRET INTEL ⚡")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
            meta.lore(java.util.List.of(
                    net.kyori.adventure.text.Component.text("Capture this and return to friendly territory!")
                            .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW),
                    net.kyori.adventure.text.Component.text("Region: " + regionId)
                            .color(net.kyori.adventure.text.format.NamedTextColor.GRAY),
                    net.kyori.adventure.text.Component.text("Time remaining: ~" + minutesRemaining + " min")
                            .color(net.kyori.adventure.text.format.NamedTextColor.RED)
            ));
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            intelItem.setItemMeta(meta);
        }

        // Drop the item at the location
        Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
        org.bukkit.entity.Item droppedItem = world.dropItem(loc, intelItem);
        droppedItem.setGlowing(true);
        droppedItem.setCustomNameVisible(true);
        droppedItem.customName(net.kyori.adventure.text.Component.text("⚡ SECRET INTEL")
                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD));
        droppedItem.setUnlimitedLifetime(true); // Don't despawn

        spawnedIntelItems.put(regionId, droppedItem.getUniqueId());

        plugin.getLogger().info("[Objectives] Spawned intel item for Capture Intel objective in " + regionId +
                " at " + x + "," + y + "," + z + " (" + minutesRemaining + " min remaining)");
    }

    /**
     * Gets remaining seconds for an intel objective's overall lifetime.
     */
    private int getIntelObjectiveSecondsRemaining(String regionId) {
        Long spawnTime = intelObjectiveSpawnTimes.get(regionId);
        if (spawnTime == null) return INTEL_OBJECTIVE_LIFETIME_SECONDS;
        long elapsed = (System.currentTimeMillis() - spawnTime) / 1000;
        return Math.max(0, INTEL_OBJECTIVE_LIFETIME_SECONDS - (int) elapsed);
    }

    /**
     * Checks if an intel objective has exceeded its 10-minute lifetime.
     */
    private boolean isIntelObjectiveExpired(String regionId) {
        return getIntelObjectiveSecondsRemaining(regionId) <= 0;
    }

    @Override
    public boolean onIntelPickup(UUID playerUuid, String team, String regionId) {
        // Check if there's an active Capture Intel objective in this region
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_CAPTURE_INTEL) {
                // Verify player is attacker
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) return false;

                String defenderTeam = statusOpt.get().ownerTeam();
                if (defenderTeam != null && defenderTeam.equalsIgnoreCase(team)) {
                    return false; // Defender can't capture their own intel
                }

                // Check if intel is already being carried
                IntelCarrierData existing = intelCarriers.get(regionId);
                if (existing != null && !existing.isDropped()) {
                    return false; // Someone already has it
                }

                // Start carrying the intel
                IntelCarrierData data = new IntelCarrierData(
                        obj.id(), regionId, playerUuid, team,
                        System.currentTimeMillis(), false, 0, 0, 0, 0
                );
                intelCarriers.put(regionId, data);

                // Update objective progress to show it's being carried
                updateProgress(obj.id(), 0.5); // 50% = picked up, needs to return

                // Remove the spawned item reference
                spawnedIntelItems.remove(regionId);

                plugin.getLogger().info("[Objectives] Intel picked up by " + playerUuid +
                        " in " + regionId + " - must return to friendly territory");

                return true;
            }
        }

        // Check if this is dropped intel being picked up
        for (Map.Entry<String, IntelCarrierData> entry : intelCarriers.entrySet()) {
            IntelCarrierData data = entry.getValue();
            if (data.isDropped()) {
                // Check if player is near the dropped location
                // This would need position checking in the listener
                // For now, we allow pickup anywhere in the source region
                if (entry.getKey().equals(regionId) || isPlayerNearDroppedIntel(playerUuid, data)) {
                    // Check if it's a defender returning or attacker continuing
                    Optional<RegionStatus> statusOpt = regionService.getRegionStatus(data.sourceRegionId());
                    if (statusOpt.isPresent()) {
                        String defenderTeam = statusOpt.get().ownerTeam();
                        if (defenderTeam != null && defenderTeam.equalsIgnoreCase(team)) {
                            // Defender recovered the intel - reset objective
                            return false; // Let onIntelReturned handle this
                        }
                    }

                    // Attacker picking up dropped intel
                    IntelCarrierData newData = data.withCarrier(playerUuid, team);
                    intelCarriers.put(data.sourceRegionId(), newData);

                    plugin.getLogger().info("[Objectives] Dropped intel recovered by attacker " + playerUuid);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isPlayerNearDroppedIntel(UUID playerUuid, IntelCarrierData data) {
        if (!data.isDropped()) return false;

        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
        if (player == null) return false;

        double dist = Math.sqrt(
                Math.pow(player.getLocation().getX() - data.droppedX(), 2) +
                Math.pow(player.getLocation().getZ() - data.droppedZ(), 2)
        );
        return dist < 5; // Within 5 blocks
    }

    @Override
    public void onIntelCarrierRegionChange(UUID playerUuid, String newRegionId) {
        // Find if this player is carrying intel
        for (Map.Entry<String, IntelCarrierData> entry : intelCarriers.entrySet()) {
            IntelCarrierData data = entry.getValue();
            if (data.carrierUuid() != null && data.carrierUuid().equals(playerUuid) && !data.isDropped()) {
                String sourceRegion = data.sourceRegionId();

                // Check if player entered friendly territory
                Optional<RegionStatus> sourceStatus = regionService.getRegionStatus(sourceRegion);
                Optional<RegionStatus> newStatus = regionService.getRegionStatus(newRegionId);

                if (sourceStatus.isPresent() && newStatus.isPresent()) {
                    // Check if new region is owned by attacker's team (carrier's team)
                    RegionStatus newRegion = newStatus.get();
                    if (newRegion.isOwnedBy(data.carrierTeam())) {
                        // Intel delivered to friendly territory!
                        completeObjective(data.objectiveId(), playerUuid, data.carrierTeam());
                        intelCarriers.remove(entry.getKey());

                        // Remove glowing effect from the carrier
                        org.bukkit.entity.Player carrier = org.bukkit.Bukkit.getPlayer(playerUuid);
                        if (carrier != null) {
                            carrier.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);

                            // Remove intel item from inventory
                            removeIntelFromInventory(carrier);

                            // Notify player
                            carrier.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                                    .append(net.kyori.adventure.text.Component.text("⚡ Intel delivered! Objective complete!")
                                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN)));
                            carrier.playSound(carrier.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        }

                        plugin.getLogger().info("[Objectives] Capture Intel completed! " + playerUuid +
                                " delivered intel from " + sourceRegion + " to " + newRegionId);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void onIntelCarrierDeath(UUID playerUuid, String regionId) {
        // Find if this player was carrying intel
        for (Map.Entry<String, IntelCarrierData> entry : intelCarriers.entrySet()) {
            IntelCarrierData data = entry.getValue();
            if (data.carrierUuid() != null && data.carrierUuid().equals(playerUuid) && !data.isDropped()) {
                // Player died while carrying intel - drop it
                org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerUuid);
                int dropX = 0, dropY = 64, dropZ = 0;
                if (player != null) {
                    dropX = player.getLocation().getBlockX();
                    dropY = player.getLocation().getBlockY();
                    dropZ = player.getLocation().getBlockZ();

                    // Remove glowing effect from the dead carrier
                    player.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);

                    // Remove intel item from their inventory
                    removeIntelFromInventory(player);
                }

                IntelCarrierData droppedData = data.withDropped(dropX, dropY, dropZ);
                intelCarriers.put(entry.getKey(), droppedData);

                // Spawn the intel item at death location
                World world = roundService.getGameWorld().orElse(null);
                if (world != null) {
                    spawnIntelItem(data.sourceRegionId(), dropX, dropY, dropZ);
                }

                // Update objective progress to show it's dropped
                updateProgress(data.objectiveId(), 0.25); // 25% = dropped, vulnerable

                plugin.getLogger().info("[Objectives] Intel carrier " + playerUuid +
                        " died - intel dropped at " + dropX + "," + dropY + "," + dropZ);
                return;
            }
        }
    }

    @Override
    public boolean onIntelReturned(UUID defenderUuid, String team, String regionId) {
        // Check all intel carriers for dropped intel in this region
        for (Map.Entry<String, IntelCarrierData> entry : intelCarriers.entrySet()) {
            IntelCarrierData data = entry.getValue();
            if (data.isDropped()) {
                // Check if defender is near the dropped intel
                if (isPlayerNearDroppedIntel(defenderUuid, data)) {
                    // Check if this player is on the defending team
                    Optional<RegionStatus> statusOpt = regionService.getRegionStatus(data.sourceRegionId());
                    if (statusOpt.isPresent()) {
                        String defenderTeamExpected = statusOpt.get().ownerTeam();
                        if (defenderTeamExpected != null && defenderTeamExpected.equalsIgnoreCase(team)) {
                            // Defender recovered the intel - reset the objective
                            intelCarriers.remove(entry.getKey());

                            // Remove the dropped item
                            UUID itemId = spawnedIntelItems.remove(data.sourceRegionId());
                            if (itemId != null) {
                                org.bukkit.entity.Entity item = org.bukkit.Bukkit.getEntity(itemId);
                                if (item != null) {
                                    item.remove();
                                }
                            }

                            // Reset objective progress
                            updateProgress(data.objectiveId(), 0.0);

                            // Reduce attacker influence as reward for intel recovery
                            double defenseReward = config.getDefenseObjectiveReward();
                            regionService.reduceInfluence(data.sourceRegionId(), data.carrierTeam(), defenseReward);

                            // Notify the defender
                            org.bukkit.entity.Player defender = org.bukkit.Bukkit.getPlayer(defenderUuid);
                            if (defender != null) {
                                defender.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                                        .append(net.kyori.adventure.text.Component.text("⚡ Intel recovered! ")
                                                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN))
                                        .append(net.kyori.adventure.text.Component.text("-" + (int)defenseReward + " enemy IP")
                                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
                                defender.playSound(defender.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                            }

                            // Respawn intel at original location
                            for (RegionObjective obj : getActiveObjectives(data.sourceRegionId(), ObjectiveCategory.RAID)) {
                                if (obj.id() == data.objectiveId() && obj.hasLocation()) {
                                    spawnIntelItem(data.sourceRegionId(), obj.locationX(), obj.locationY(), obj.locationZ());
                                    break;
                                }
                            }

                            plugin.getLogger().info("[Objectives] Intel returned by defender " + defenderUuid +
                                    " in " + data.sourceRegionId() + " - objective reset");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public Optional<IntelCarrierInfo> getIntelCarrierInfo(String regionId) {
        IntelCarrierData data = intelCarriers.get(regionId);
        if (data == null) return Optional.empty();

        return Optional.of(new IntelCarrierInfo(
                data.sourceRegionId(),
                data.objectiveId(),
                data.carrierUuid(),
                data.carrierTeam(),
                data.pickedUpAtMillis(),
                data.isDropped(),
                data.droppedX(), data.droppedY(), data.droppedZ(),
                data.getDroppedSecondsRemaining(),
                getIntelObjectiveSecondsRemaining(regionId)
        ));
    }

    @Override
    public void tickIntelObjectives() {
        // First, check all intel objectives for 10-minute expiration (regardless of phase)
        Set<String> expiredRegions = new HashSet<>();
        for (String regionId : intelObjectiveSpawnTimes.keySet()) {
            if (isIntelObjectiveExpired(regionId)) {
                expiredRegions.add(regionId);
            }
        }

        // Expire intel objectives that have exceeded 10 minutes
        for (String regionId : expiredRegions) {
            expireIntelObjective(regionId);
        }

        // Then check for dropped intel timeout (60 seconds to recover before respawn)
        Iterator<Map.Entry<String, IntelCarrierData>> iterator = intelCarriers.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, IntelCarrierData> entry = iterator.next();
            String regionId = entry.getKey();
            IntelCarrierData data = entry.getValue();

            // Skip if already expired by the 10-minute check above
            if (expiredRegions.contains(regionId)) continue;

            // Check if dropped intel has timed out (60 seconds)
            if (data.isDroppedTimedOut()) {
                // Remove the dropped item
                UUID itemId = spawnedIntelItems.remove(regionId);
                if (itemId != null) {
                    org.bukkit.entity.Entity item = org.bukkit.Bukkit.getEntity(itemId);
                    if (item != null) {
                        item.remove();
                    }
                }

                // Reset the intel carrier tracking
                iterator.remove();

                // Reset objective progress
                updateProgress(data.objectiveId(), 0.0);

                // Respawn intel at original location (if objective still has time remaining)
                if (!isIntelObjectiveExpired(data.sourceRegionId())) {
                    for (RegionObjective obj : getActiveObjectives(data.sourceRegionId(), ObjectiveCategory.RAID)) {
                        if (obj.id() == data.objectiveId() && obj.hasLocation()) {
                            spawnIntelItem(data.sourceRegionId(), obj.locationX(), obj.locationY(), obj.locationZ());
                            plugin.getLogger().info("[Objectives] Dropped intel timed out in " + regionId +
                                    " - respawned at original location");
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Expires an intel objective completely - removes item, carrier tracking, and the objective itself.
     * Called when the 10-minute lifetime is exceeded.
     */
    private void expireIntelObjective(String regionId) {
        // Remove spawned item (if dropped on ground)
        UUID itemId = spawnedIntelItems.remove(regionId);
        if (itemId != null) {
            org.bukkit.entity.Entity item = org.bukkit.Bukkit.getEntity(itemId);
            if (item != null) {
                item.remove();
            }
        }

        // Remove carrier tracking and get objective ID
        IntelCarrierData carrierData = intelCarriers.remove(regionId);
        int objectiveId = -1;
        UUID carrierUuid = null;

        if (carrierData != null) {
            objectiveId = carrierData.objectiveId();
            carrierUuid = carrierData.carrierUuid();
        }

        // Remove spawn time tracking
        intelObjectiveSpawnTimes.remove(regionId);

        // If someone was carrying, remove their glowing effect AND the intel item from inventory
        if (carrierUuid != null) {
            org.bukkit.entity.Player carrier = org.bukkit.Bukkit.getPlayer(carrierUuid);
            if (carrier != null) {
                carrier.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);

                // Remove intel item from player's inventory
                removeIntelFromInventory(carrier);

                carrier.sendMessage(net.kyori.adventure.text.Component.text(config.getPrefix())
                        .append(net.kyori.adventure.text.Component.text("⚡ The intel has expired and disappeared!")
                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)));
                carrier.playSound(carrier.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 0.5f);
            }
        }

        // Find and expire the objective in the database
        if (objectiveId < 0) {
            // Find objective ID from active objectives
            for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
                if (obj.type() == ObjectiveType.RAID_CAPTURE_INTEL) {
                    objectiveId = obj.id();
                    break;
                }
            }
        }

        if (objectiveId > 0) {
            db.expireObjective(objectiveId);
            plugin.getLogger().info("[Objectives] Intel objective expired in " + regionId + ": 10-minute lifetime exceeded");
        }
    }

    /**
     * Removes intel items from a player's inventory.
     */
    private void removeIntelFromInventory(org.bukkit.entity.Player player) {
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getSize(); i++) {
            org.bukkit.inventory.ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() == org.bukkit.Material.FILLED_MAP) {
                org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    net.kyori.adventure.text.Component displayName = meta.displayName();
                    if (displayName != null) {
                        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(displayName);
                        if (name.contains("SECRET INTEL")) {
                            inventory.setItem(i, null);
                            plugin.getLogger().info("[Objectives] Removed expired intel from " + player.getName() + "'s inventory");
                        }
                    }
                }
            }
        }
    }

    // ==================== CALLBACKS ====================

    @Override
    public void setCompletionCallback(ObjectiveCompletionCallback callback) {
        this.completionCallback = callback;
    }

    @Override
    public void setSpawnCallback(ObjectiveSpawnCallback callback) {
        this.spawnCallback = callback;
    }
}

