package org.flintstqne.entrenched.RegionLogic;

import java.util.*;

/**
 * Service interface for region capture operations.
 */
public interface RegionService {

    // ==================== RESULT ENUMS ====================

    enum CaptureResult {
        SUCCESS,
        REGION_NOT_FOUND,
        REGION_FORTIFIED,
        REGION_PROTECTED,
        NOT_ADJACENT,
        ALREADY_OWNED,
        INSUFFICIENT_INFLUENCE
    }

    enum InfluenceResult {
        SUCCESS,
        REGION_NOT_FOUND,
        REGION_FORTIFIED,
        REGION_PROTECTED,
        RATE_LIMITED,
        INVALID_ACTION,
        NO_ACTIVE_ROUND,
        NOT_ADJACENT  // Region is not adjacent to any friendly territory
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initializes all regions for a new round.
     * Sets home regions for each team.
     */
    void initializeRegionsForRound(int roundId, String redHome, String blueHome);

    // ==================== REGION QUERIES ====================

    /**
     * Gets the current status of a region.
     */
    Optional<RegionStatus> getRegionStatus(String regionId);

    /**
     * Gets all region statuses for the current round.
     */
    List<RegionStatus> getAllRegionStatuses();

    /**
     * Gets all regions owned by a team.
     */
    List<RegionStatus> getRegionsByOwner(String team);

    /**
     * Gets the region ID for given block coordinates.
     */
    String getRegionIdForLocation(int blockX, int blockZ);

    /**
     * Counts regions owned by a team.
     */
    int countRegionsOwned(String team);

    // ==================== INFLUENCE OPERATIONS ====================

    /**
     * Adds influence points for a team in a region.
     * Handles anti-spam, rate limiting, and triggers capture if threshold reached.
     *
     * @param playerUuid The player performing the action
     * @param regionId   The region ID
     * @param team       The team gaining influence
     * @param action     The action type
     * @param multiplier Optional multiplier (e.g., for reduced repeat kill points)
     * @return Result of the operation
     */
    InfluenceResult addInfluence(UUID playerUuid, String regionId, String team, InfluenceAction action, double multiplier);

    /**
     * Adds influence with default multiplier (1.0).
     */
    default InfluenceResult addInfluence(UUID playerUuid, String regionId, String team, InfluenceAction action) {
        return addInfluence(playerUuid, regionId, team, action, 1.0);
    }

    /**
     * Gets the influence points for a team in a region.
     */
    double getInfluence(String regionId, String team);

    /**
     * Gets the influence required to capture a region.
     */
    double getInfluenceRequired(String regionId, String capturingTeam);

    /**
     * Calculates the kill point multiplier based on repeat kills.
     */
    double getKillMultiplier(UUID killerUuid, UUID victimUuid, String regionId);

    // ==================== CAPTURE OPERATIONS ====================

    /**
     * Attempts to capture a region for a team.
     * Should be called automatically when influence threshold is reached.
     */
    CaptureResult captureRegion(String regionId, String team);

    /**
     * Checks if a team can attack a region (adjacency rules).
     */
    boolean canAttackRegion(String regionId, String team);

    /**
     * Gets adjacent regions to a given region.
     */
    List<String> getAdjacentRegions(String regionId);

    /**
     * Checks if a region is adjacent to any region owned by a team.
     */
    boolean isAdjacentToTeam(String regionId, String team);

    // ==================== ADMIN OPERATIONS ====================

    /**
     * Force captures a region for a team (admin command).
     */
    void captureRegion(String regionId, String team, long fortifyUntil);

    /**
     * Resets a region to neutral state (admin command).
     */
    void resetRegion(String regionId);

    /**
     * Sets a region's state directly (admin command).
     */
    void setRegionState(String regionId, RegionState state);

    /**
     * Adds influence directly without player context (admin command).
     */
    void addInfluence(String regionId, String team, double amount, UUID playerUuid);

    /**
     * Initializes/resets all regions with team homes.
     */
    void initializeRegions(String redHome, String blueHome);

    // ==================== SUPPLY LINE OPERATIONS ====================

    /**
     * Calculates supply efficiency for a region (0.0 to 1.0).
     * Based on shortest path to home region.
     */
    double getSupplyEfficiency(String regionId, String team);

    /**
     * Checks if a region is connected to its team's home.
     */
    boolean isConnectedToHome(String regionId, String team);

    /**
     * Gets all regions that would be cut off if a region is captured.
     */
    List<String> getRegionsThatWouldBeCutOff(String regionId, String team);

    // ==================== DEFENSE OPERATIONS ====================

    /**
     * Gets the total defense bonus for a region.
     * Includes time-held bonus and structure bonus.
     */
    double getDefenseBonus(String regionId);

    /**
     * Updates defense structure count for a region.
     */
    void updateDefenseStructures(String regionId, int count);

    // ==================== DECAY ====================

    /**
     * Applies influence decay to all contested regions.
     * Should be called periodically.
     */
    void applyInfluenceDecay();

    /**
     * Updates fortification status for all regions.
     * Removes fortification when time expires.
     */
    void updateFortificationStatus();

    // ==================== EVENTS ====================

    /**
     * Called when a player kills another player.
     */
    void onPlayerKill(UUID killerUuid, UUID victimUuid, String killerTeam, String victimTeam, int blockX, int blockZ);

    /**
     * Called when a player places a block.
     */
    void onBlockPlace(UUID playerUuid, String team, int blockX, int blockY, int blockZ, String blockType);

    /**
     * Called when a player breaks a block.
     */
    void onBlockBreak(UUID playerUuid, String team, int blockX, int blockY, int blockZ, boolean wasPlayerPlaced, String placedByTeam);

    /**
     * Called when a player places a banner.
     */
    void onBannerPlace(UUID playerUuid, String team, int blockX, int blockZ);

    /**
     * Called when a player removes an enemy banner.
     */
    void onBannerRemove(UUID playerUuid, String team, int blockX, int blockZ, String bannerTeam);

    /**
     * Called when a player removes their own team's banner that earned IP.
     * Deducts IP as anti-cheese protection.
     */
    void onOwnBannerRemove(UUID playerUuid, String team, int blockX, int blockZ);

    /**
     * Called when a player kills a mob.
     */
    void onMobKill(UUID playerUuid, String team, int blockX, int blockZ);
}

