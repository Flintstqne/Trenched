package org.flintstqne.entrenched.RoadLogic;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service interface for road/supply line operations.
 */
public interface RoadService {

    // ==================== ROAD BLOCK OPERATIONS ====================

    /**
     * Records a path block placement.
     *
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @param playerUuid Player who placed the block
     * @param team Team of the player
     */
    void onPathBlockPlaced(int x, int y, int z, UUID playerUuid, String team);

    /**
     * Records a path block placement WITHOUT triggering supply recalculation.
     * Use this for batch operations, then call recalculateSupply() once at the end.
     *
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @param playerUuid Player who placed the block
     * @param team Team of the player
     */
    void insertRoadBlockWithoutRecalculation(int x, int y, int z, UUID playerUuid, String team);

    /**
     * Records a path block removal/destruction.
     *
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return The team that owned the removed block (if any)
     */
    Optional<String> onPathBlockRemoved(int x, int y, int z);

    /**
     * Checks if a block at the given location is a tracked road block.
     */
    boolean isRoadBlock(int x, int y, int z);

    /**
     * Gets the road block at the given location.
     */
    Optional<RoadBlock> getRoadBlock(int x, int y, int z);

    // ==================== SUPPLY STATUS OPERATIONS ====================

    /**
     * Gets the supply level for a region.
     */
    SupplyLevel getSupplyLevel(String regionId, String team);

    /**
     * Checks if a region has a road connection to home.
     */
    boolean isConnectedToHome(String regionId, String team);

    /**
     * Gets all regions affected by a road disruption at the given location.
     *
     * @return List of region IDs that lost supply due to this disruption
     */
    List<String> getAffectedRegions(int x, int y, int z, String team);

    /**
     * Recalculates supply status for all regions of a team.
     * Called after road changes or region captures.
     */
    void recalculateSupply(String team);

    /**
     * Flushes any pending supply recalculations.
     * This is called periodically to batch recalculations for performance.
     */
    void flushPendingRecalculations();

    /**
     * Checks if there are pending recalculations waiting to be processed.
     */
    boolean hasPendingRecalculations();

    // ==================== ROAD CONNECTIVITY ====================

    /**
     * Checks if there is a continuous road path between two regions.
     */
    boolean hasRoadConnection(String fromRegion, String toRegion, String team);

    /**
     * Gets all regions reachable via roads from the home region.
     */
    Set<String> getConnectedRegions(String team);

    /**
     * Finds the shortest road path from a region to home.
     *
     * @return Number of region hops, or -1 if not connected
     */
    int findRoadPathToHome(String regionId, String team);

    // ==================== ROAD SEGMENT OPERATIONS ====================

    /**
     * Checks road connectivity between two adjacent regions.
     * Scans the border area for continuous path blocks.
     */
    boolean checkBorderRoadConnection(String region1, String region2, String team);

    /**
     * Gets the total road block count for a team in a region.
     */
    int getRoadBlockCount(String regionId, String team);

    /**
     * Gets all road blocks in a region for a team.
     */
    List<RoadBlock> getRoadBlocksInRegion(String regionId, String team);

    /**
     * Finds road gaps in a region for debugging/reporting.
     * Returns a list of gap descriptions with coordinate ranges.
     *
     * @return List of gap descriptions, empty if no gaps
     */
    List<String> findRoadGaps(String regionId, String team);

    /**
     * Debug method that tests gap detection and returns detailed info.
     * @return List of debug messages explaining the gap detection process
     */
    List<String> debugGapDetection(String regionId, String team);

    // ==================== RESPAWN & HEALTH OPERATIONS ====================

    /**
     * Gets the additional respawn delay for a player based on their region's supply.
     */
    int getRespawnDelay(UUID playerUuid, String team);

    /**
     * Gets the health regen multiplier for a player based on their region's supply.
     */
    double getHealthRegenMultiplier(UUID playerUuid, String team);

    // ==================== CLEANUP ====================

    /**
     * Clears all road data. Called on new round.
     */
    void clearAllData();

    /**
     * Clears road data for a specific region.
     */
    void clearRegionData(String regionId);
}
