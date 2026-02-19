package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;

import java.util.*;
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

    private ObjectiveCompletionCallback completionCallback;
    private ObjectiveSpawnCallback spawnCallback;

    // Cache for region centers (calculated once)
    private final Map<String, int[]> regionCenters = new HashMap<>();

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
            return SpawnResult.NO_ACTIVE_ROUND;
        }

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
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

        // Spawn new objectives up to the max
        int toSpawn = maxObjectives - currentCount;
        List<ObjectiveType> candidates = new ArrayList<>();
        for (ObjectiveType type : availableTypes) {
            if (!activeTypes.contains(type)) {
                candidates.add(type);
            }
        }

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
            case RAID_DESTROY_CACHE, RAID_PLANT_EXPLOSIVE, RAID_CAPTURE_INTEL, RAID_HOLD_GROUND -> true;
            default -> false;
        };
    }

    @Override
    public void refreshAllObjectives() {
        Optional<Integer> roundIdOpt = getCurrentRoundId();
        if (roundIdOpt.isEmpty()) {
            return;
        }

        // Get all regions and spawn objectives as needed
        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            // Skip protected regions (home regions)
            if (status.state() == RegionState.PROTECTED) {
                continue;
            }

            // Skip fortified regions
            if (status.state() == RegionState.FORTIFIED) {
                continue;
            }

            // Spawn objectives if below max
            if (countActiveObjectives(status.regionId()) < config.getObjectivesPerRegion()) {
                spawnObjectivesForRegion(status.regionId());
            }
        }
    }

    @Override
    public void expireObjectivesInRegion(String regionId) {
        getCurrentRoundId().ifPresent(roundId -> {
            db.expireAllInRegion(regionId, roundId);
            plugin.getLogger().info("[Objectives] Expired all objectives in " + regionId);
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
        // Check for sabotage defenses objective
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_SABOTAGE_DEFENSES) {
                // Add progress (1/50 blocks = 0.02 progress)
                double newProgress = obj.progress() + 0.02;
                updateProgress(obj.id(), newProgress);

                if (newProgress >= 1.0) {
                    completeObjective(obj.id(), playerUuid, team);
                }
                break;
            }

            // Check for destroy cache - if block is a chest at objective location
            if (obj.type() == ObjectiveType.RAID_DESTROY_CACHE && obj.hasLocation()) {
                if (blockType.contains("CHEST") &&
                        Math.abs(obj.locationX() - x) <= 2 &&
                        Math.abs(obj.locationZ() - z) <= 2) {
                    completeObjective(obj.id(), playerUuid, team);
                    break;
                }
            }
        }
    }

    @Override
    public void onBlockPlaced(UUID playerUuid, String team, String regionId,
                               int x, int y, int z, String blockType) {
        // Check for settlement objectives
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT)) {
            switch (obj.type()) {
                case SETTLEMENT_SECURE_PERIMETER -> {
                    // Check if it's a wall-like block
                    if (isWallBlock(blockType)) {
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

    @Override
    public void onPlayerKill(UUID killerUuid, UUID victimUuid, String regionId) {
        // Check for assassination objective
        // For now, any kill counts - could add logic to check if victim is "commander"
        for (RegionObjective obj : getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_ASSASSINATE_COMMANDER) {
                // In a full implementation, we'd check if victimUuid is the commander
                // For now, complete on any kill within the objective
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) continue;

                // Killer must be attacking team
                String defenderTeam = statusOpt.get().ownerTeam();
                // This would need killer's team from context - simplified for now
                break;
            }
        }
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

