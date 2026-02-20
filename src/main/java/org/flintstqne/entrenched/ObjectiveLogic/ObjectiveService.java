package org.flintstqne.entrenched.ObjectiveLogic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for objective operations.
 */
public interface ObjectiveService {

    // ==================== RESULT ENUMS ====================

    enum CompleteResult {
        SUCCESS,
        OBJECTIVE_NOT_FOUND,
        ALREADY_COMPLETED,
        OBJECTIVE_EXPIRED,
        WRONG_REGION,
        ON_COOLDOWN,
        INSUFFICIENT_PROGRESS,
        REGION_ALREADY_OWNED
    }

    enum SpawnResult {
        SUCCESS,
        REGION_NOT_FOUND,
        MAX_OBJECTIVES_REACHED,
        NO_VALID_LOCATION,
        NO_ACTIVE_ROUND
    }

    // ==================== OBJECTIVE QUERIES ====================

    /**
     * Gets all active objectives in a region.
     */
    List<RegionObjective> getActiveObjectives(String regionId);

    /**
     * Gets all active objectives for a specific category in a region.
     */
    List<RegionObjective> getActiveObjectives(String regionId, ObjectiveCategory category);

    /**
     * Gets a specific objective by ID.
     */
    Optional<RegionObjective> getObjective(int objectiveId);

    /**
     * Gets the nearest active objective to a location within a region.
     */
    Optional<RegionObjective> getNearestObjective(String regionId, int x, int z);

    /**
     * Gets all objectives completed by a player in the current round.
     */
    List<RegionObjective> getCompletedByPlayer(UUID playerUuid);

    /**
     * Counts active objectives in a region.
     */
    int countActiveObjectives(String regionId);

    // ==================== OBJECTIVE SPAWNING ====================

    /**
     * Spawns objectives for a region based on its ownership status.
     * - Neutral regions get settlement objectives
     * - Enemy regions get raid objectives
     */
    SpawnResult spawnObjectivesForRegion(String regionId);

    /**
     * Spawns a specific objective type in a region.
     */
    SpawnResult spawnObjective(String regionId, ObjectiveType type);

    /**
     * Spawns a specific objective at a specific location.
     */
    SpawnResult spawnObjective(String regionId, ObjectiveType type, int x, int y, int z);

    /**
     * Refreshes objectives in all regions (removes expired, spawns new).
     * Should be called periodically.
     */
    void refreshAllObjectives();

    /**
     * Expires all objectives in a region (called when region is captured).
     */
    void expireObjectivesInRegion(String regionId);

    // ==================== OBJECTIVE PROGRESS ====================

    /**
     * Updates progress on an objective.
     * @return The updated objective, or empty if not found
     */
    Optional<RegionObjective> updateProgress(int objectiveId, double progress);

    /**
     * Adds progress to an objective (incremental).
     * @return The updated objective, or empty if not found
     */
    Optional<RegionObjective> addProgress(int objectiveId, double progressDelta);

    // ==================== OBJECTIVE COMPLETION ====================

    /**
     * Attempts to complete an objective.
     * Awards influence to the completing team.
     */
    CompleteResult completeObjective(int objectiveId, UUID playerUuid, String team);

    /**
     * Checks if a player is on cooldown for an objective type in a region.
     */
    boolean isOnCooldown(UUID playerUuid, String regionId, ObjectiveType type);

    /**
     * Gets the remaining cooldown time in seconds.
     */
    long getCooldownRemaining(UUID playerUuid, String regionId, ObjectiveType type);

    // ==================== OBJECTIVE DETECTION ====================

    /**
     * Called when a player destroys a block - checks for cache/sabotage objectives.
     */
    void onBlockDestroyed(UUID playerUuid, String team, String regionId, int x, int y, int z, String blockType);

    /**
     * Called when a player places a block - checks for settlement objectives.
     */
    void onBlockPlaced(UUID playerUuid, String team, String regionId, int x, int y, int z, String blockType);

    /**
     * Called when a player kills another player - checks for assassination objectives.
     */
    void onPlayerKill(UUID killerUuid, UUID victimUuid, String regionId);

    /**
     * Called when a player places a container (chest, barrel, etc.) - tracks for resource depot.
     */
    void onContainerPlaced(UUID playerUuid, String team, String regionId, int x, int y, int z, String blockType);

    /**
     * Called when a player breaks a container - recalculates resource depot progress.
     * @param containerCount Current container count after breaking
     * @param totalItems Total items remaining after breaking
     */
    void onContainerBroken(UUID playerUuid, String team, String regionId, int containerCount, int totalItems);

    /**
     * Called when a player closes a container inventory - checks for resource depot completion.
     * @param containerLocations List of [x, y, z] arrays for all nearby containers
     * @param totalItems Total items across all containers
     */
    void onContainerInteract(UUID playerUuid, String team, String regionId, int containerCount, int totalItems);

    /**
     * Called every second to update hold-ground objective progress for players in hold zones.
     * Tracks players who are in the center of regions with active hold ground objectives.
     * @param playerLocations Map of player UUID to their current regionId and coordinates [x, z]
     * @param playerTeams Map of player UUID to their team
     */
    void tickHoldGroundObjectives(Map<UUID, HoldGroundPlayerData> playerData);

    /**
     * Gets the hold zone center and radius for a region's hold ground objective.
     * @return Optional containing [centerX, centerZ, radius] or empty if no hold ground objective
     */
    Optional<int[]> getHoldZoneInfo(String regionId);

    /**
     * Clears all tracked data (block tracking, etc.). Called on new round.
     */
    void clearTrackedData();

    /**
     * Data class for hold ground tracking.
     */
    record HoldGroundPlayerData(String regionId, int x, int z, String team) {}

    // ==================== CALLBACKS ====================

    /**
     * Sets a callback for when an objective is completed.
     */
    void setCompletionCallback(ObjectiveCompletionCallback callback);

    /**
     * Sets a callback for when an objective is spawned.
     */
    void setSpawnCallback(ObjectiveSpawnCallback callback);

    @FunctionalInterface
    interface ObjectiveCompletionCallback {
        void onObjectiveCompleted(RegionObjective objective, UUID playerUuid, String team);
    }

    @FunctionalInterface
    interface ObjectiveSpawnCallback {
        void onObjectiveSpawned(RegionObjective objective);
    }
}

