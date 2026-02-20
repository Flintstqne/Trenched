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
     * Gets online assassination targets (commanders/officers) for a region.
     * @param regionId The region to check
     * @return List of player UUIDs who are valid assassination targets
     */
    List<UUID> getAssassinationTargets(String regionId);

    /**
     * Checks if assassinate commander objective can spawn in a region.
     * @param regionId The region to check
     * @return true if there are valid targets (online enemy commanders/officers)
     */
    boolean canSpawnAssassinateObjective(String regionId);

    /**
     * Gets enemy chest locations in a region for the Destroy Supply Cache objective.
     * @param regionId The region to check
     * @param attackerTeam The attacking team (will return chests placed by the opposing team)
     * @return List of [x, y, z] arrays for enemy chest locations
     */
    List<int[]> getEnemyChestLocations(String regionId, String attackerTeam);

    /**
     * Checks if destroy supply cache objective can spawn in a region.
     * @param regionId The region to check
     * @return true if there are enemy chests in the region
     */
    boolean canSpawnDestroyCacheObjective(String regionId);

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
     * Called when a player places TNT - checks for Plant Explosive objective.
     * @return true if TNT was placed for an objective (starts defend timer)
     */
    boolean onTntPlaced(UUID playerUuid, String team, String regionId, int x, int y, int z);

    /**
     * Called when TNT is broken/defused - cancels Plant Explosive if active.
     */
    void onTntBroken(UUID playerUuid, String team, String regionId, int x, int y, int z);

    /**
     * Called every second to update planted explosive defend timers.
     * Also checks for nearby defenders who might defuse.
     */
    void tickPlantedExplosives();

    /**
     * Gets info about an active planted explosive in a region.
     * @return Optional containing [x, y, z, secondsRemaining] or empty if none active
     */
    Optional<PlantedExplosiveInfo> getPlantedExplosiveInfo(String regionId);

    /**
     * Data class for planted explosive tracking.
     */
    record PlantedExplosiveInfo(
            String regionId,
            int objectiveId,
            UUID planterUuid,
            String planterTeam,
            int x, int y, int z,
            int secondsRemaining,
            int totalSeconds
    ) {
        public double getProgress() {
            return 1.0 - ((double) secondsRemaining / totalSeconds);
        }
    }

    /**
     * Gets the hold zone center and radius for a region's hold ground objective.
     * @return Optional containing [centerX, centerZ, radius] or empty if no hold ground objective
     */
    Optional<int[]> getHoldZoneInfo(String regionId);

    /**
     * Clears all tracked data (block tracking, etc.). Called on new round.
     */
    void clearTrackedData();

    // ==================== CAPTURE INTEL OBJECTIVE ====================

    /**
     * Called when a player picks up an intel item.
     * @return true if the item was intel for an active objective
     */
    boolean onIntelPickup(UUID playerUuid, String team, String regionId);

    /**
     * Called when an intel carrier enters a new region.
     * Checks if they've reached friendly territory to complete the objective.
     */
    void onIntelCarrierRegionChange(UUID playerUuid, String newRegionId);

    /**
     * Called when an intel carrier dies - drops the intel.
     */
    void onIntelCarrierDeath(UUID playerUuid, String regionId);

    /**
     * Called when a defender picks up dropped intel - returns it.
     * @return true if the item was dropped intel that was returned
     */
    boolean onIntelReturned(UUID defenderUuid, String team, String regionId);

    /**
     * Gets info about the current intel carrier in a region.
     */
    Optional<IntelCarrierInfo> getIntelCarrierInfo(String regionId);

    /**
     * Spawns the intel item at the objective location.
     * Called when objective is created.
     */
    void spawnIntelItem(String regionId, int x, int y, int z);

    /**
     * Called every second to check for timed-out dropped intel.
     * If dropped intel times out, it respawns at the original location.
     */
    void tickIntelObjectives();

    /**
     * Data class for intel carrier tracking.
     */
    record IntelCarrierInfo(
            String sourceRegionId,
            int objectiveId,
            UUID carrierUuid,
            String carrierTeam,
            long pickedUpAtMillis,
            boolean isDropped,
            int droppedX, int droppedY, int droppedZ,
            int droppedSecondsRemaining,  // Time left before dropped intel respawns (60s)
            int overallSecondsRemaining   // Time left before entire objective expires (10 min)
    ) {}

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

