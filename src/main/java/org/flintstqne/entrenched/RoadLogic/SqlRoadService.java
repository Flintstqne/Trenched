package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * SQL-backed implementation of RoadService.
 */
public final class SqlRoadService implements RoadService {

    private static final int GRID_SIZE = 4;
    private static final int REGION_BLOCKS = 512;
    private static final int HALF_SIZE = (GRID_SIZE * REGION_BLOCKS) / 2;

    private final RoadDb db;
    private final RoundService roundService;
    private final RegionService regionService;
    private final ConfigManager configManager;
    private final Logger logger;

    // In-memory cache for road blocks (for fast lookups)
    private final Map<String, RoadBlock> roadBlockCache = new ConcurrentHashMap<>();

    // Cache for supply status
    private final Map<String, SupplyLevel> supplyCache = new ConcurrentHashMap<>();

    // Debounced recalculation - tracks teams that need recalculation
    private final Set<String> pendingRecalculation = ConcurrentHashMap.newKeySet();
    private volatile long lastRecalculationTime = 0;
    private static final long RECALCULATION_DEBOUNCE_MS = 3000; // 3 seconds

    public SqlRoadService(RoadDb db, RoundService roundService, RegionService regionService,
                          ConfigManager configManager) {
        this.db = db;
        this.roundService = roundService;
        this.regionService = regionService;
        this.configManager = configManager;
        this.logger = Bukkit.getLogger();
    }

    private int getCurrentRoundId() {
        return roundService.getCurrentRound().map(Round::roundId).orElse(-1);
    }

    private void log(String message) {
        if (configManager.isVerbose()) {
            logger.info("[RoadService] " + message);
        }
    }

    // ==================== ROAD BLOCK OPERATIONS ====================

    @Override
    public void onPathBlockPlaced(int x, int y, int z, UUID playerUuid, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        String regionId = getRegionIdForLocation(x, z);
        if (regionId == null) return;

        long now = System.currentTimeMillis();

        // Insert into database
        db.insertRoadBlock(roundId, regionId, x, y, z, playerUuid.toString(), team, now);

        // Update cache
        RoadBlock block = new RoadBlock(x, y, z, regionId, playerUuid.toString(), team, now);
        roadBlockCache.put(block.toKey(), block);

        log("Road block placed at " + x + "," + y + "," + z + " by " + team + " in " + regionId);

        // Schedule debounced recalculation (prevents lag when placing many blocks)
        scheduleRecalculation(team);
    }

    @Override
    public void insertRoadBlockWithoutRecalculation(int x, int y, int z, UUID playerUuid, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) {
            logger.warning("[RoadService] Cannot register road block - no active round!");
            return;
        }

        String regionId = getRegionIdForLocation(x, z);
        if (regionId == null) {
            logger.warning("[RoadService] Cannot register road block - invalid region for coords " + x + "," + z);
            return;
        }

        long now = System.currentTimeMillis();

        // Insert into database
        db.insertRoadBlock(roundId, regionId, x, y, z, playerUuid.toString(), team, now);

        // Update cache
        RoadBlock block = new RoadBlock(x, y, z, regionId, playerUuid.toString(), team, now);
        roadBlockCache.put(block.toKey(), block);

        // NO recalculation - caller must call recalculateSupply() when done
    }

    @Override
    public Optional<String> onPathBlockRemoved(int x, int y, int z) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Optional.empty();

        // Remove from database and get team
        Optional<String> teamOpt = db.deleteRoadBlock(roundId, x, y, z);

        // Remove from cache
        String key = RoadBlock.toKey(x, y, z);
        roadBlockCache.remove(key);

        if (teamOpt.isPresent()) {
            log("Road block removed at " + x + "," + y + "," + z + " owned by " + teamOpt.get());
            // Schedule debounced recalculation for affected team
            scheduleRecalculation(teamOpt.get());
        }

        return teamOpt;
    }

    /**
     * Schedules a debounced supply recalculation for the given team.
     * Multiple calls within the debounce window will result in a single recalculation.
     * The actual recalculation happens asynchronously via the periodic scheduler.
     */
    private void scheduleRecalculation(String team) {
        pendingRecalculation.add(team);

        // IMMEDIATELY clear supply cache for this team to force fresh calculation on next read
        supplyCache.entrySet().removeIf(e -> e.getKey().endsWith(":" + team));

        // Do NOT call flushPendingRecalculations here - it's too expensive for the main thread
        // The async scheduler in Trenched.java will handle it
    }

    /**
     * Processes all pending supply recalculations.
     * Called periodically or when needed.
     */
    public void flushPendingRecalculations() {
        if (pendingRecalculation.isEmpty()) return;

        Set<String> teams = new HashSet<>(pendingRecalculation);
        pendingRecalculation.clear();
        lastRecalculationTime = System.currentTimeMillis();

        for (String team : teams) {
            log("Flushing supply recalculation for " + team);
            recalculateSupply(team);
        }
    }

    /**
     * Checks if there are pending recalculations that should be processed.
     * Call this periodically (e.g., every few seconds) from a scheduler.
     */
    public boolean hasPendingRecalculations() {
        return !pendingRecalculation.isEmpty();
    }

    @Override
    public boolean isRoadBlock(int x, int y, int z) {
        return getRoadBlock(x, y, z).isPresent();
    }

    @Override
    public Optional<RoadBlock> getRoadBlock(int x, int y, int z) {
        String key = RoadBlock.toKey(x, y, z);

        // Check cache first
        RoadBlock cached = roadBlockCache.get(key);
        if (cached != null) return Optional.of(cached);

        // Fall back to database
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Optional.empty();

        Optional<RoadBlock> block = db.getRoadBlock(roundId, x, y, z);
        block.ifPresent(b -> roadBlockCache.put(key, b));
        return block;
    }

    // ==================== SUPPLY STATUS OPERATIONS ====================

    @Override
    public SupplyLevel getSupplyLevel(String regionId, String team) {
        String cacheKey = regionId + ":" + team;
        SupplyLevel cached = supplyCache.get(cacheKey);
        if (cached != null) return cached;

        int roundId = getCurrentRoundId();
        if (roundId < 0) return SupplyLevel.UNSUPPLIED;

        // Check database cache
        Optional<SupplyLevel> dbLevel = db.getSupplyLevel(roundId, regionId, team);
        if (dbLevel.isPresent()) {
            supplyCache.put(cacheKey, dbLevel.get());
            return dbLevel.get();
        }

        // Calculate fresh
        SupplyLevel level = calculateSupplyLevel(regionId, team);
        supplyCache.put(cacheKey, level);
        return level;
    }

    @Override
    public boolean isConnectedToHome(String regionId, String team) {
        SupplyLevel level = getSupplyLevel(regionId, team);
        return level == SupplyLevel.SUPPLIED || level == SupplyLevel.PARTIAL;
    }

    @Override
    public List<String> getAffectedRegions(int x, int y, int z, String team) {
        List<String> affected = new ArrayList<>();

        // Get all connected regions before the change
        Set<String> previouslyConnected = getConnectedRegions(team);

        // Recalculate after the change
        recalculateSupply(team);
        Set<String> nowConnected = getConnectedRegions(team);

        // Find regions that lost connection
        for (String region : previouslyConnected) {
            if (!nowConnected.contains(region)) {
                affected.add(region);
            }
        }

        return affected;
    }

    @Override
    public void recalculateSupply(String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return;

        // Get all regions owned by this team
        List<RegionStatus> ownedRegions = regionService.getRegionsByOwner(team);

        // Clear supply cache for this team
        supplyCache.entrySet().removeIf(e -> e.getKey().endsWith(":" + team));

        // Calculate supply for each region
        for (RegionStatus region : ownedRegions) {
            SupplyLevel level = calculateSupplyLevel(region.regionId(), team);
            boolean connected = level == SupplyLevel.SUPPLIED || level == SupplyLevel.PARTIAL;

            // Update database cache
            db.updateSupplyStatus(roundId, region.regionId(), team, level, connected);

            // Update memory cache
            supplyCache.put(region.regionId() + ":" + team, level);
        }
    }

    private SupplyLevel calculateSupplyLevel(String regionId, String team) {
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return SupplyLevel.ISOLATED;

        // Home is always supplied
        if (regionId.equals(homeRegion)) return SupplyLevel.SUPPLIED;

        // Check if region is owned by the team
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty() || !statusOpt.get().isOwnedBy(team)) {
            return SupplyLevel.ISOLATED;
        }

        // Check if any adjacent regions are owned (not isolated)
        boolean hasAdjacentOwned = false;
        for (String adjacent : regionService.getAdjacentRegions(regionId)) {
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adjacent);
            if (adjStatus.isPresent() && adjStatus.get().isOwnedBy(team)) {
                hasAdjacentOwned = true;
                break;
            }
        }

        if (!hasAdjacentOwned) {
            return SupplyLevel.ISOLATED;
        }

        // Try to find a road path to home
        int pathLength = findRoadPathToHome(regionId, team);

        if (pathLength >= 0) {
            // Road path exists - but check for internal gaps (blown up roads)
            if (hasRoadGapsInRegion(regionId, team)) {
                // Road has gaps (hole blown in it) = partial supply
                return SupplyLevel.PARTIAL;
            }
            // Road path exists with no gaps = full supply
            return SupplyLevel.SUPPLIED;
        }

        // Check if there's an alternative region-based path (without roads)
        if (hasRegionPathToHome(regionId, team)) {
            // Has region path but no road - partial supply
            return SupplyLevel.PARTIAL;
        }

        // No connection at all
        return SupplyLevel.UNSUPPLIED;
    }

    private boolean hasRegionPathToHome(String regionId, String team) {
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return false;
        if (regionId.equals(homeRegion)) return true;

        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(regionId);
        visited.add(regionId);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (String adjacent : regionService.getAdjacentRegions(current)) {
                if (visited.contains(adjacent)) continue;

                Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adjacent);
                if (adjStatus.isEmpty() || !adjStatus.get().isOwnedBy(team)) continue;

                if (adjacent.equals(homeRegion)) {
                    return true;
                }

                visited.add(adjacent);
                queue.add(adjacent);
            }
        }

        return false;
    }

    /**
     * Checks if there are gaps/disconnections in the road within a single region.
     *
     * This detects when a road has been damaged (hole blown in it) by checking if the
     * road blocks entering from the home direction can reach blocks deeper in the region.
     *
     * IMPORTANT: We are very lenient here to avoid false positives from scattered terrain blocks.
     * We only return true (gap detected) if the road clearly can't continue forward.
     *
     * @param regionId The region to check
     * @param team The team owning the roads
     * @return true if the road has critical gaps (broken/blown up), false otherwise
     */
    private boolean hasRoadGapsInRegion(String regionId, String team) {
        // Check if gap detection is enabled in config
        if (!configManager.isSupplyGapDetectionEnabled()) {
            return false; // Gap detection disabled - assume no gaps
        }

        int roundId = getCurrentRoundId();
        if (roundId < 0) return false;

        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return false;
        if (regionId.equals(homeRegion)) return false; // Home never has gaps

        // Get all road blocks in this region
        List<RoadBlock> blocks = db.getRoadBlocksInRegion(roundId, regionId, team);

        // If few blocks, assume no meaningful gap
        if (blocks.size() < 20) return false;

        // Find the entry region (the adjacent supplied region toward home)
        String entryRegion = findEntryRegion(regionId, team);
        if (entryRegion == null) {
            // Can't find entry - don't flag as gap, let other checks handle it
            return false;
        }

        // Get border area with entry region
        int[] entryBorder = getBorderArea(regionId, entryRegion);
        if (entryBorder == null) return false;

        // Find blocks at the entry border
        List<RoadBlock> entryBlocks = blocks.stream()
                .filter(b -> isInBorderArea(b, entryBorder))
                .toList();

        // If no blocks at entry, that's handled by checkBorderRoadConnection
        if (entryBlocks.isEmpty()) return false;

        // Use A* flood-fill to count reachable blocks from entry
        int xzRadius = configManager.getSupplyAdjacencyRadius();
        int yTolerance = configManager.getSupplyYTolerance();

        Map<String, RoadBlock> spatialIndex = buildSpatialIndex(blocks);

        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();

        // Start from entry blocks
        for (RoadBlock entry : entryBlocks) {
            String key = entry.toKey();
            queue.add(key);
            reachable.add(key);
        }

        // Flood fill with sufficient iterations
        // Each iteration processes one block, so we need at least blocks.size() iterations
        // to potentially reach all blocks. Add extra headroom for complex paths.
        int maxIterations = Math.max(blocks.size() * 3, 5000);
        int iterations = 0;

        while (!queue.isEmpty() && iterations < maxIterations) {
            iterations++;
            String currentKey = queue.poll();
            RoadBlock current = spatialIndex.get(currentKey);
            if (current == null) continue;

            // Check neighbors efficiently
            for (int dx = -xzRadius; dx <= xzRadius; dx++) {
                for (int dz = -xzRadius; dz <= xzRadius; dz++) {
                    for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        String neighborKey = RoadBlock.toKey(current.x() + dx, current.y() + dy, current.z() + dz);
                        if (spatialIndex.containsKey(neighborKey) && !reachable.contains(neighborKey)) {
                            reachable.add(neighborKey);
                            queue.add(neighborKey);
                        }
                    }
                }
            }
        }

        // VERY lenient gap detection:
        // Only flag a gap if LESS THAN 15% of blocks are reachable from entry
        // AND there are at least 100 blocks total
        // This threshold is intentionally very low because auto-scanning picks up
        // many scattered terrain blocks that aren't part of the actual road
        double reachablePercent = (double) reachable.size() / blocks.size();

        logger.info("[RoadService] hasRoadGapsInRegion " + regionId + ": " +
                reachable.size() + "/" + blocks.size() + " reachable (" +
                String.format("%.1f%%", reachablePercent * 100) + "), entryBlocks=" + entryBlocks.size() +
                ", entryRegion=" + entryRegion);

        // Only detect gaps if:
        // 1. Less than 15% reachable (very severe disconnection)
        // 2. At least 100 blocks total (significant road network)
        // 3. At least 10 entry blocks (road actually reaches the border)
        if (reachablePercent < 0.15 && blocks.size() >= 100 && entryBlocks.size() >= 10) {
            logger.info("[RoadService] Gap detected in " + regionId + ": only " +
                    String.format("%.1f%%", reachablePercent * 100) + " reachable (" +
                    reachable.size() + "/" + blocks.size() + ")");
            return true;
        }

        logger.info("[RoadService] hasRoadGapsInRegion " + regionId + ": NO GAP");
        return false;
    }

    /**
     * Finds the entry region (the adjacent region that connects toward home).
     * Uses a simple check - any adjacent owned region with a road connection.
     */
    private String findEntryRegion(String regionId, String team) {
        // Simple approach: find any adjacent owned region that has road blocks at the border
        // Don't call getConnectedRegions() here as that's expensive (full BFS)

        for (String adj : regionService.getAdjacentRegions(regionId)) {
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
            if (adjStatus.isEmpty() || !adjStatus.get().isOwnedBy(team)) continue;

            // Check if there are road blocks at the border (quick check, no pathfinding)
            if (hasRoadBlocksAtBorder(adj, regionId, team)) {
                return adj;
            }
        }
        return null;
    }

    /**
     * Quick check if there are road blocks at the border between two regions.
     * This is a fast check that doesn't do full pathfinding.
     */
    private boolean hasRoadBlocksAtBorder(String region1, String region2, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return false;

        int[] border = getBorderArea(region1, region2);
        if (border == null) return false;

        // Just check if there are ANY blocks in the border area for both regions
        List<RoadBlock> borderBlocks = db.getRoadBlocksInArea(roundId, border[0], border[1], border[2], border[3], team);

        boolean hasRegion1Blocks = false;
        boolean hasRegion2Blocks = false;

        for (RoadBlock block : borderBlocks) {
            String blockRegion = getRegionIdForLocation(block.x(), block.z());
            if (region1.equals(blockRegion)) hasRegion1Blocks = true;
            if (region2.equals(blockRegion)) hasRegion2Blocks = true;
            if (hasRegion1Blocks && hasRegion2Blocks) return true;
        }

        return false;
    }

    // ==================== ROAD CONNECTIVITY ====================

    @Override
    public boolean hasRoadConnection(String fromRegion, String toRegion, String team) {
        // Check if there's a road connection between adjacent regions
        return checkBorderRoadConnection(fromRegion, toRegion, team);
    }

    @Override
    public Set<String> getConnectedRegions(String team) {
        Set<String> connected = new HashSet<>();
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return connected;

        // BFS from home, following road connections
        Queue<String> queue = new LinkedList<>();
        queue.add(homeRegion);
        connected.add(homeRegion);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (String adjacent : regionService.getAdjacentRegions(current)) {
                if (connected.contains(adjacent)) continue;

                Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adjacent);
                if (adjStatus.isEmpty() || !adjStatus.get().isOwnedBy(team)) continue;

                // Check if there's a road connection
                if (checkBorderRoadConnection(current, adjacent, team)) {
                    connected.add(adjacent);
                    queue.add(adjacent);
                }
            }
        }

        return connected;
    }

    @Override
    public int findRoadPathToHome(String regionId, String team) {
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return -1;
        if (regionId.equals(homeRegion)) return 0;

        // Use BFS to find path via verified continuous road connections
        Queue<PathNode> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(new PathNode(homeRegion, null, 0));
        visited.add(homeRegion);

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();

            for (String adjacent : regionService.getAdjacentRegions(current.regionId)) {
                if (visited.contains(adjacent)) continue;

                Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adjacent);
                if (adjStatus.isEmpty() || !adjStatus.get().isOwnedBy(team)) continue;

                // Check road connection at the border
                if (!checkBorderRoadConnection(current.regionId, adjacent, team)) continue;

                // For intermediate regions (not the first hop from home),
                // verify that there's a continuous road through the current region
                if (current.previousRegion != null) {
                    if (!verifyContinuousRoadThroughRegion(current.regionId, current.previousRegion, adjacent, team)) {
                        continue; // No continuous road through this region
                    }
                }

                // If this is the target region, verify it has internal road connectivity
                // (not broken/disconnected road segments)
                if (adjacent.equals(regionId)) {
                    // Check if the road in the target region is continuous from the entry point
                    // If the region has internal gaps (multiple disconnected components), it's only partial
                    boolean hasGaps = hasRoadGapsInRegion(adjacent, team);
                    if (hasGaps) {
                        logger.info("[RoadService] findRoadPathToHome: " + adjacent + " has gaps, trying other paths");
                        continue; // Try other paths
                    }
                    logger.info("[RoadService] findRoadPathToHome: SUCCESS - path to " + regionId + " found via " + current.regionId);
                    return current.distance + 1;
                }

                visited.add(adjacent);
                queue.add(new PathNode(adjacent, current.regionId, current.distance + 1));
            }
        }

        logger.info("[RoadService] findRoadPathToHome: FAILED - no path found to " + regionId);
        return -1; // Not connected via roads
    }

    /**
     * Helper class for BFS path finding with previous region tracking.
     */
    private record PathNode(String regionId, String previousRegion, int distance) {}

    /**
     * Verifies that there is a CONTINUOUS road through a region from one border to another.
     * Uses pathfinding to trace actual road blocks, not just checking border presence.
     *
     * @param regionId The region to check
     * @param entryRegion The region the road comes FROM
     * @param exitRegion The region the road goes TO
     * @param team The team owning the roads
     * @return true if a continuous road path exists through the region
     */
    private boolean verifyContinuousRoadThroughRegion(String regionId, String entryRegion, String exitRegion, String team) {
        // If gap detection is disabled, assume roads are continuous
        // This avoids false negatives from scattered terrain blocks
        if (!configManager.isSupplyGapDetectionEnabled()) {
            return true;
        }

        int roundId = getCurrentRoundId();
        if (roundId < 0) return false;

        int xzRadius = configManager.getSupplyAdjacencyRadius();
        int yTolerance = configManager.getSupplyYTolerance();

        // Get all road blocks in this region
        List<RoadBlock> allBlocks = db.getRoadBlocksInRegion(roundId, regionId, team);
        if (allBlocks.isEmpty()) return false;

        // Performance optimization: for large road networks, assume connected
        if (allBlocks.size() > 1000) {
            return true; // Assume connected to avoid freeze
        }

        // Get the border areas
        int[] entryBorder = getBorderArea(regionId, entryRegion);
        int[] exitBorder = getBorderArea(regionId, exitRegion);
        if (entryBorder == null || exitBorder == null) return false;

        // Find blocks at the entry border (within this region)
        List<RoadBlock> entryBlocks = allBlocks.stream()
                .filter(b -> isInBorderArea(b, entryBorder))
                .toList();

        // Find blocks at the exit border (within this region)
        List<RoadBlock> exitBlocks = allBlocks.stream()
                .filter(b -> isInBorderArea(b, exitBorder))
                .toList();

        if (entryBlocks.isEmpty() || exitBlocks.isEmpty()) {
            return false;
        }

        // Build spatial index for O(1) neighbor lookups
        Map<String, RoadBlock> spatialIndex = buildSpatialIndex(allBlocks);

        // BFS from entry blocks to see if we can reach any exit block
        Set<String> exitKeys = exitBlocks.stream().map(RoadBlock::toKey).collect(Collectors.toSet());
        Set<String> visited = new HashSet<>();
        Queue<String> bfsQueue = new LinkedList<>();
        int maxIterations = 2000; // Limit to prevent freezes
        int iterations = 0;

        // Start from all entry blocks
        for (RoadBlock entry : entryBlocks) {
            String key = entry.toKey();
            bfsQueue.add(key);
            visited.add(key);
        }

        while (!bfsQueue.isEmpty() && iterations < maxIterations) {
            iterations++;
            String currentKey = bfsQueue.poll();

            // Check if we've reached an exit block
            if (exitKeys.contains(currentKey)) {
                return true; // Found a continuous path!
            }

            RoadBlock current = spatialIndex.get(currentKey);
            if (current == null) continue;

            // Explore neighbors using spatial index with flexible Y tolerance
            Set<String> neighbors = getNeighbors(current, spatialIndex, xzRadius, yTolerance);
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    bfsQueue.add(neighbor);
                }
            }
        }

        return false; // No continuous path found through this region
    }

    /**
     * Checks if a block is within a border area.
     */
    private boolean isInBorderArea(RoadBlock block, int[] border) {
        return block.x() >= border[0] && block.x() <= border[1] &&
               block.z() >= border[2] && block.z() <= border[3];
    }

    /**
     * Builds a spatial index for O(1) neighbor lookups.
     */
    private Map<String, RoadBlock> buildSpatialIndex(List<RoadBlock> blocks) {
        Map<String, RoadBlock> index = new HashMap<>();
        for (RoadBlock block : blocks) {
            index.put(block.toKey(), block);
        }
        return index;
    }

    /**
     * Builds a chunk-based spatial index for efficient neighbor lookups.
     * Groups blocks into chunks for faster proximity searches.
     */
    private Map<String, List<RoadBlock>> buildChunkIndex(List<RoadBlock> blocks, int chunkSize) {
        Map<String, List<RoadBlock>> index = new HashMap<>();
        for (RoadBlock block : blocks) {
            String chunkKey = (block.x() / chunkSize) + "," + (block.z() / chunkSize);
            index.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(block);
        }
        return index;
    }

    /**
     * Gets neighbors for a block using chunk-based spatial index.
     * Much faster than O(n³) iteration - only checks blocks in nearby chunks.
     */
    private Set<String> getNeighbors(RoadBlock block, Map<String, RoadBlock> spatialIndex,
                                     Map<String, List<RoadBlock>> chunkIndex, int xzRadius, int yTolerance) {
        Set<String> neighbors = new HashSet<>();

        // Calculate which chunks to check
        int chunkSize = Math.max(xzRadius * 2, 16);
        int blockChunkX = block.x() / chunkSize;
        int blockChunkZ = block.z() / chunkSize;

        // Check surrounding chunks (including current chunk)
        for (int cx = blockChunkX - 1; cx <= blockChunkX + 1; cx++) {
            for (int cz = blockChunkZ - 1; cz <= blockChunkZ + 1; cz++) {
                String chunkKey = cx + "," + cz;
                List<RoadBlock> chunkBlocks = chunkIndex.get(chunkKey);
                if (chunkBlocks == null) continue;

                for (RoadBlock other : chunkBlocks) {
                    if (other.toKey().equals(block.toKey())) continue;

                    // Check if within radius
                    int dx = Math.abs(other.x() - block.x());
                    int dy = Math.abs(other.y() - block.y());
                    int dz = Math.abs(other.z() - block.z());

                    if (dx <= xzRadius && dy <= yTolerance && dz <= xzRadius) {
                        neighbors.add(other.toKey());
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * Gets neighbors for a block using spatial index (O(1) per neighbor check).
     * Uses separate X/Z radius and Y tolerance for terrain flexibility.
     *
     * OPTIMIZATION: Instead of iterating all possible coordinates (expensive with large Y tolerance),
     * we only check common neighbor offsets (adjacent blocks, cardinal directions).
     * For large Y tolerances, we sample key Y levels rather than checking every single one.
     */
    private Set<String> getNeighbors(RoadBlock block, Map<String, RoadBlock> spatialIndex, int xzRadius, int yTolerance) {
        Set<String> neighbors = new HashSet<>();

        // Optimization: For performance, we use a smarter neighbor search
        // Instead of checking ALL (2*xzRadius+1)*(2*yTolerance+1)*(2*xzRadius+1) positions,
        // we check a reduced set of positions that are most likely to contain road blocks

        // Sample Y levels: check current Y, +/- small increments, and extremes
        int[] yOffsets;
        if (yTolerance <= 3) {
            // Small tolerance - check all
            yOffsets = new int[yTolerance * 2 + 1];
            for (int i = 0; i <= yTolerance * 2; i++) {
                yOffsets[i] = i - yTolerance;
            }
        } else {
            // Large tolerance - sample key levels (same level, ±1, ±2, ±4, ±8, ±16, ±max)
            Set<Integer> ySet = new java.util.TreeSet<>();
            ySet.add(0);
            for (int step : new int[]{1, 2, 3, 4, 6, 8, 12, 16, 24, 32}) {
                if (step <= yTolerance) {
                    ySet.add(step);
                    ySet.add(-step);
                }
            }
            yOffsets = ySet.stream().mapToInt(Integer::intValue).toArray();
        }

        // Check all XZ positions within radius, but only sampled Y positions
        for (int dx = -xzRadius; dx <= xzRadius; dx++) {
            for (int dz = -xzRadius; dz <= xzRadius; dz++) {
                for (int dy : yOffsets) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    String neighborKey = RoadBlock.toKey(block.x() + dx, block.y() + dy, block.z() + dz);
                    if (spatialIndex.containsKey(neighborKey)) {
                        neighbors.add(neighborKey);
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * Gets neighbors for a block using spatial index with same radius for all axes.
     * @deprecated Use getNeighbors(block, spatialIndex, xzRadius, yTolerance) instead
     */
    private Set<String> getNeighbors(RoadBlock block, Map<String, RoadBlock> spatialIndex, int radius) {
        return getNeighbors(block, spatialIndex, radius, radius);
    }

    @Override
    public boolean checkBorderRoadConnection(String region1, String region2, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) {
            return false;
        }

        // Get the border area between the two regions
        int[] border = getBorderArea(region1, region2);
        if (border == null) {
            return false;
        }

        int minX = border[0], maxX = border[1], minZ = border[2], maxZ = border[3];

        // Get road blocks in the border area
        List<RoadBlock> borderBlocks = db.getRoadBlocksInArea(roundId, minX, maxX, minZ, maxZ, team);

        int xzRadius = configManager.getSupplyAdjacencyRadius();
        int yTolerance = configManager.getSupplyYTolerance();

        // If we have blocks in the border area, do the standard check
        if (!borderBlocks.isEmpty()) {
            // Get blocks that are in region1 (just inside the border)
            List<RoadBlock> region1BorderBlocks = borderBlocks.stream()
                    .filter(b -> isBlockInRegion(b, region1))
                    .toList();

            // Get blocks that are in region2 (just inside the border)
            List<RoadBlock> region2BorderBlocks = borderBlocks.stream()
                    .filter(b -> isBlockInRegion(b, region2))
                    .toList();

            // If we have blocks on both sides of the border, check if they connect
            if (!region1BorderBlocks.isEmpty() && !region2BorderBlocks.isEmpty()) {
                // Build spatial index for O(1) neighbor lookups
                Map<String, RoadBlock> spatialIndex = buildSpatialIndex(borderBlocks);

                // BFS from any region1 block to see if we can reach any region2 block
                Set<String> region2Keys = region2BorderBlocks.stream()
                        .map(RoadBlock::toKey)
                        .collect(Collectors.toSet());

                Set<String> visited = new HashSet<>();
                Queue<String> queue = new LinkedList<>();

                // Start from all region1 blocks
                for (RoadBlock r1Block : region1BorderBlocks) {
                    String key = r1Block.toKey();
                    queue.add(key);
                    visited.add(key);
                }

                int maxIterations = 500; // Limit to prevent freezes
                int iterations = 0;

                while (!queue.isEmpty() && iterations < maxIterations) {
                    iterations++;
                    String currentKey = queue.poll();

                    // Check if we've reached a region2 block
                    if (region2Keys.contains(currentKey)) {
                        return true; // Found a path!
                    }

                    RoadBlock current = spatialIndex.get(currentKey);
                    if (current == null) continue;

                    // Explore neighbors using spatial index with flexible Y tolerance
                    Set<String> neighbors = getNeighbors(current, spatialIndex, xzRadius, yTolerance);
                    for (String neighbor : neighbors) {
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }

                // If we exhausted iterations and visited many blocks, assume connected
                if (iterations >= maxIterations && visited.size() > 100) {
                    return true; // Assume connected for large road networks
                }
            }
        }

        // FALLBACK: If standard border check failed, try extended check
        // Look for road blocks NEAR the border that could connect via pathfinding
        // This handles cases where the road doesn't quite reach the border line

        // Get all road blocks in both regions
        List<RoadBlock> region1Blocks = db.getRoadBlocksInRegion(roundId, region1, team);
        List<RoadBlock> region2Blocks = db.getRoadBlocksInRegion(roundId, region2, team);

        if (region1Blocks.isEmpty() || region2Blocks.isEmpty()) {
            return false;
        }

        // Find the closest blocks to the border in each region
        // Extended border area - look further into each region
        int extendedWidth = configManager.getSupplyBorderWidth() * 3; // Triple the normal width
        int[] extendedBorder = getExtendedBorderArea(region1, region2, extendedWidth);
        if (extendedBorder == null) return false;

        // Get blocks near the border (extended area)
        List<RoadBlock> region1NearBorder = region1Blocks.stream()
                .filter(b -> isInExtendedBorderArea(b, extendedBorder, region1))
                .toList();

        List<RoadBlock> region2NearBorder = region2Blocks.stream()
                .filter(b -> isInExtendedBorderArea(b, extendedBorder, region2))
                .toList();

        if (region1NearBorder.isEmpty() || region2NearBorder.isEmpty()) {
            return false;
        }

        // Check if any block from region1 is within adjacency radius of any block in region2
        // This allows roads that don't perfectly align at the border but are close enough
        for (RoadBlock b1 : region1NearBorder) {
            for (RoadBlock b2 : region2NearBorder) {
                int dx = Math.abs(b1.x() - b2.x());
                int dz = Math.abs(b1.z() - b2.z());
                int dy = Math.abs(b1.y() - b2.y());

                // If blocks are within extended adjacency range, consider them connected
                if (dx <= xzRadius * 2 && dz <= xzRadius * 2 && dy <= yTolerance) {
                    return true;
                }
            }
        }

        return false; // No connection found
    }

    /**
     * Gets an extended border area for fallback connection checking.
     */
    private int[] getExtendedBorderArea(String region1, String region2, int extendedWidth) {
        // Parse region IDs (e.g., "A1", "B2")
        if (region1.length() < 2 || region2.length() < 2) return null;

        char row1 = region1.charAt(0);
        int col1 = Integer.parseInt(region1.substring(1));
        char row2 = region2.charAt(0);
        int col2 = Integer.parseInt(region2.substring(1));

        // Calculate region bounds
        int region1MinX = (col1 - 1) * REGION_BLOCKS - HALF_SIZE;
        int region1MaxX = col1 * REGION_BLOCKS - HALF_SIZE - 1;
        int region1MinZ = (row1 - 'A') * REGION_BLOCKS - HALF_SIZE;
        int region1MaxZ = (row1 - 'A' + 1) * REGION_BLOCKS - HALF_SIZE - 1;

        if (col1 == col2 && Math.abs(row1 - row2) == 1) {
            // Vertical neighbors (N/S)
            int borderZ = (row1 < row2) ? region1MaxZ : region1MinZ;
            return new int[]{
                    region1MinX, region1MaxX,
                    borderZ - extendedWidth, borderZ + extendedWidth
            };
        } else if (row1 == row2 && Math.abs(col1 - col2) == 1) {
            // Horizontal neighbors (E/W)
            int borderX = (col1 < col2) ? region1MaxX : region1MinX;
            return new int[]{
                    borderX - extendedWidth, borderX + extendedWidth,
                    region1MinZ, region1MaxZ
            };
        }

        return null;
    }

    /**
     * Checks if a block is in the extended border area for a specific region.
     */
    private boolean isInExtendedBorderArea(RoadBlock block, int[] extendedBorder, String regionId) {
        // First check if it's in the extended border area
        if (block.x() < extendedBorder[0] || block.x() > extendedBorder[1]) return false;
        if (block.z() < extendedBorder[2] || block.z() > extendedBorder[3]) return false;

        // Then check if it's actually in the specified region
        return isBlockInRegion(block, regionId);
    }

    /**
     * Checks if a road block is within a specific region.
     */
    private boolean isBlockInRegion(RoadBlock block, String regionId) {
        String blockRegion = getRegionIdForLocation(block.x(), block.z());
        return regionId.equals(blockRegion);
    }

    @Override
    public int[] getBorderAreaPublic(String region1, String region2) {
        return getBorderArea(region1, region2);
    }

    @Override
    public int scanBorderArea(String region1, String region2, String team, org.bukkit.World world) {
        int[] border = getBorderArea(region1, region2);
        if (border == null) return 0;

        // Get valid path block types from config
        List<String> pathBlockNames = configManager.getSupplyPathBlocks();
        Set<org.bukkit.Material> pathBlocks = new java.util.HashSet<>();
        if (pathBlockNames == null || pathBlockNames.isEmpty()) {
            pathBlocks.add(org.bukkit.Material.DIRT_PATH);
            pathBlocks.add(org.bukkit.Material.GRAVEL);
            pathBlocks.add(org.bukkit.Material.COBBLESTONE);
            pathBlocks.add(org.bukkit.Material.STONE_BRICKS);
            pathBlocks.add(org.bukkit.Material.POLISHED_ANDESITE);
        } else {
            for (String name : pathBlockNames) {
                try {
                    pathBlocks.add(org.bukkit.Material.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        UUID systemUuid = UUID.fromString("00000000-0000-0000-0000-000000000000");

        logger.info("[RoadService] Scanning border " + region1 + "<->" + region2 +
                ": X[" + border[0] + " to " + border[1] + "] Z[" + border[2] + " to " + border[3] + "]");

        int found = scanAreaForRoads(border[0], border[1], border[2], border[3],
                world, pathBlocks, systemUuid, team);

        logger.info("[RoadService] Found " + found + " road blocks at border " + region1 + "<->" + region2);

        return found;
    }


    /**
     * Gets the border area between two adjacent regions.
     * Returns [minX, maxX, minZ, maxZ] or null if not adjacent.
     */
    private int[] getBorderArea(String region1, String region2) {
        // Parse region IDs (e.g., "A1", "B2")
        if (region1.length() < 2 || region2.length() < 2) return null;

        char row1 = region1.charAt(0);
        int col1 = Integer.parseInt(region1.substring(1));
        char row2 = region2.charAt(0);
        int col2 = Integer.parseInt(region2.substring(1));

        // Calculate region bounds
        int region1MinX = (col1 - 1) * REGION_BLOCKS - HALF_SIZE;
        int region1MaxX = col1 * REGION_BLOCKS - HALF_SIZE - 1;
        int region1MinZ = (row1 - 'A') * REGION_BLOCKS - HALF_SIZE;
        int region1MaxZ = (row1 - 'A' + 1) * REGION_BLOCKS - HALF_SIZE - 1;

        int region2MinX = (col2 - 1) * REGION_BLOCKS - HALF_SIZE;
        int region2MaxX = col2 * REGION_BLOCKS - HALF_SIZE - 1;
        int region2MinZ = (row2 - 'A') * REGION_BLOCKS - HALF_SIZE;
        int region2MaxZ = (row2 - 'A' + 1) * REGION_BLOCKS - HALF_SIZE - 1;

        // Border width - how far from the border line to search for road blocks
        // Use config value or default to 32 blocks (more generous detection)
        int borderWidth = configManager.getSupplyBorderWidth();

        if (col1 == col2 && Math.abs(row1 - row2) == 1) {
            // Vertical neighbors (N/S) - border is a horizontal line (Z changes)
            int borderZ = (row1 < row2) ? region1MaxZ : region1MinZ;
            return new int[]{
                    Math.max(region1MinX, region2MinX),
                    Math.min(region1MaxX, region2MaxX),
                    borderZ - borderWidth,
                    borderZ + borderWidth
            };
        } else if (row1 == row2 && Math.abs(col1 - col2) == 1) {
            // Horizontal neighbors (E/W) - border is a vertical line (X changes)
            int borderX = (col1 < col2) ? region1MaxX : region1MinX;
            return new int[]{
                    borderX - borderWidth,
                    borderX + borderWidth,
                    Math.max(region1MinZ, region2MinZ),
                    Math.min(region1MaxZ, region2MaxZ)
            };
        }

        return null; // Not adjacent
    }


    // ==================== ROAD SEGMENT OPERATIONS ====================

    @Override
    public int getRoadBlockCount(String regionId, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return 0;
        return db.getRoadBlockCount(roundId, regionId, team);
    }

    @Override
    public List<RoadBlock> getRoadBlocksInRegion(String regionId, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Collections.emptyList();
        return db.getRoadBlocksInRegion(roundId, regionId, team);
    }

    @Override
    public List<String> findRoadGaps(String regionId, String team) {
        List<String> gaps = new ArrayList<>();
        int roundId = getCurrentRoundId();
        if (roundId < 0) return gaps;

        List<RoadBlock> blocks = db.getRoadBlocksInRegion(roundId, regionId, team);
        if (blocks.size() < 2) {
            if (blocks.isEmpty()) {
                gaps.add("No road blocks in this region");
            }
            return gaps;
        }

        // Get adjacent owned regions to identify border blocks
        List<String> adjacentOwned = new ArrayList<>();
        for (String adj : regionService.getAdjacentRegions(regionId)) {
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
            if (adjStatus.isPresent() && adjStatus.get().isOwnedBy(team)) {
                adjacentOwned.add(adj);
            }
        }

        // Build spatial index and chunk index
        int xzRadius = configManager.getSupplyAdjacencyRadius();
        int yTolerance = configManager.getSupplyYTolerance();
        int chunkSize = Math.max(xzRadius * 2, 16);

        Map<String, RoadBlock> spatialIndex = new HashMap<>();
        for (RoadBlock block : blocks) {
            spatialIndex.put(block.toKey(), block);
        }
        Map<String, List<RoadBlock>> chunkIndex = buildChunkIndex(blocks, chunkSize);

        // Find border blocks (connected to adjacent regions)
        Set<String> borderBlockKeys = new HashSet<>();
        Map<String, String> blockToBorder = new HashMap<>(); // Which border each block touches
        for (String adj : adjacentOwned) {
            int[] border = getBorderArea(regionId, adj);
            if (border == null) continue;

            for (RoadBlock block : blocks) {
                if (isInBorderArea(block, border)) {
                    borderBlockKeys.add(block.toKey());
                    blockToBorder.put(block.toKey(), adj);
                }
            }
        }

        // Use union-find to find connected components
        Map<String, String> parent = new HashMap<>();
        for (RoadBlock block : blocks) {
            parent.put(block.toKey(), block.toKey());
        }

        final java.util.function.Function<String, String>[] findRef = new java.util.function.Function[1];
        findRef[0] = key -> {
            if (!parent.get(key).equals(key)) {
                parent.put(key, findRef[0].apply(parent.get(key)));
            }
            return parent.get(key);
        };

        // Union neighboring blocks
        for (RoadBlock block : blocks) {
            Set<String> neighbors = getNeighbors(block, spatialIndex, chunkIndex, xzRadius, yTolerance);
            String blockRoot = findRef[0].apply(block.toKey());
            for (String neighborKey : neighbors) {
                String neighborRoot = findRef[0].apply(neighborKey);
                if (!blockRoot.equals(neighborRoot)) {
                    parent.put(neighborRoot, blockRoot);
                }
            }
        }

        // Group blocks by connected component
        Map<String, List<RoadBlock>> components = new HashMap<>();
        for (RoadBlock block : blocks) {
            String root = findRef[0].apply(block.toKey());
            components.computeIfAbsent(root, k -> new ArrayList<>()).add(block);
        }

        // Identify which components are part of the supply line (touch a border)
        Set<String> supplyLineComponents = new HashSet<>();
        for (String borderKey : borderBlockKeys) {
            supplyLineComponents.add(findRef[0].apply(borderKey));
        }

        // Sort components: supply line first (by size), then isolated (by size)
        List<List<RoadBlock>> supplyComponents = new ArrayList<>();
        List<List<RoadBlock>> isolatedComponents = new ArrayList<>();

        for (Map.Entry<String, List<RoadBlock>> entry : components.entrySet()) {
            if (supplyLineComponents.contains(entry.getKey())) {
                supplyComponents.add(entry.getValue());
            } else {
                isolatedComponents.add(entry.getValue());
            }
        }

        supplyComponents.sort((a, b) -> Integer.compare(b.size(), a.size()));
        isolatedComponents.sort((a, b) -> Integer.compare(b.size(), a.size()));

        // Report supply line segments
        if (supplyComponents.size() > 1) {
            gaps.add("⚠ Supply line has " + supplyComponents.size() + " disconnected segments (causes 50% supply):");

            for (int i = 0; i < supplyComponents.size(); i++) {
                List<RoadBlock> component = supplyComponents.get(i);
                int[] bounds = getComponentBounds(component);

                // Find which borders this segment touches
                Set<String> touchedBorders = new HashSet<>();
                for (RoadBlock block : component) {
                    String border = blockToBorder.get(block.toKey());
                    if (border != null) touchedBorders.add(border);
                }

                String borderInfo = touchedBorders.isEmpty() ? "" : " (borders: " + String.join(", ", touchedBorders) + ")";
                gaps.add(String.format("  Segment %d: %d blocks, X[%d to %d] Z[%d to %d]%s",
                        i + 1, component.size(), bounds[0], bounds[1], bounds[4], bounds[5], borderInfo));

                // Show gap to next segment
                if (i < supplyComponents.size() - 1) {
                    List<RoadBlock> nextComponent = supplyComponents.get(i + 1);
                    int[] closest = findClosestBlocks(component, nextComponent);
                    if (closest != null) {
                        gaps.add(String.format("    → Gap: Build from (%d, %d, %d) towards (%d, %d, %d)",
                                closest[0], closest[1], closest[2], closest[3], closest[4], closest[5]));
                    }
                }
            }
        } else if (supplyComponents.size() == 1) {
            gaps.add("✓ Supply line is continuous (" + supplyComponents.get(0).size() + " connected blocks)");
        } else {
            gaps.add("⚠ No road blocks at region borders - supply line doesn't pass through here");
        }

        // Report isolated segments (don't affect supply, just informational)
        if (!isolatedComponents.isEmpty()) {
            int totalIsolated = isolatedComponents.stream().mapToInt(List::size).sum();
            gaps.add("");
            gaps.add("ℹ " + isolatedComponents.size() + " isolated road segment(s) (" + totalIsolated + " blocks) - not part of supply line");
        }

        return gaps;
    }

    @Override
    public List<String> debugGapDetection(String regionId, String team) {
        List<String> debug = new ArrayList<>();
        int roundId = getCurrentRoundId();

        debug.add("=== Gap Detection Debug for " + regionId + " (" + team + ") ===");

        if (roundId < 0) {
            debug.add("ERROR: No active round");
            return debug;
        }

        String homeRegion = getHomeRegion(team);
        debug.add("Home Region: " + homeRegion);

        if (homeRegion == null) {
            debug.add("ERROR: No home region for team");
            return debug;
        }

        if (regionId.equals(homeRegion)) {
            debug.add("This IS the home region - never has gaps");
            return debug;
        }

        // Get all road blocks in this region
        List<RoadBlock> blocks = db.getRoadBlocksInRegion(roundId, regionId, team);
        debug.add("Total road blocks in region: " + blocks.size());

        if (blocks.size() < 5) {
            debug.add("Less than 5 blocks - gap detection skipped (too few blocks)");
            debug.add("hasRoadGapsInRegion would return: FALSE");
            return debug;
        }

        // Find the entry region
        String entryRegion = findEntryRegion(regionId, team);
        debug.add("Entry region (toward home): " + (entryRegion != null ? entryRegion : "NONE FOUND"));

        if (entryRegion == null) {
            debug.add("No entry region found - cannot determine gap");
            debug.add("hasRoadGapsInRegion would return: FALSE");
            return debug;
        }

        // Get border area with entry region
        int[] entryBorder = getBorderArea(regionId, entryRegion);
        if (entryBorder == null) {
            debug.add("ERROR: Could not get border area with entry region");
            debug.add("hasRoadGapsInRegion would return: FALSE");
            return debug;
        }
        debug.add("Entry border area: X[" + entryBorder[0] + " to " + entryBorder[1] + "] Z[" + entryBorder[2] + " to " + entryBorder[3] + "]");

        // Find blocks at the entry border
        List<RoadBlock> entryBlocks = blocks.stream()
                .filter(b -> isInBorderArea(b, entryBorder))
                .toList();
        debug.add("Blocks at entry border: " + entryBlocks.size());

        if (entryBlocks.isEmpty()) {
            debug.add("No blocks at entry border - gap detection skipped");
            debug.add("hasRoadGapsInRegion would return: FALSE");
            return debug;
        }

        // Show first few entry blocks
        int shown = 0;
        for (RoadBlock b : entryBlocks) {
            if (shown++ >= 3) break;
            debug.add("  Entry block: " + b.x() + ", " + b.y() + ", " + b.z());
        }

        // Build spatial index
        int xzRadius = configManager.getSupplyAdjacencyRadius();
        int yTolerance = configManager.getSupplyYTolerance();
        int chunkSize = Math.max(xzRadius * 2, 16);

        debug.add("Adjacency settings: xzRadius=" + xzRadius + ", yTolerance=" + yTolerance);

        Map<String, RoadBlock> spatialIndex = buildSpatialIndex(blocks);
        Map<String, List<RoadBlock>> chunkIndex = buildChunkIndex(blocks, chunkSize);

        // BFS from entry blocks
        Set<String> connectedToEntry = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        int maxIterations = Math.min(blocks.size() * 2, 5000);
        int iterations = 0;

        for (RoadBlock entry : entryBlocks) {
            String key = entry.toKey();
            queue.add(key);
            connectedToEntry.add(key);
        }

        while (!queue.isEmpty() && iterations < maxIterations) {
            iterations++;
            String currentKey = queue.poll();
            RoadBlock current = spatialIndex.get(currentKey);
            if (current == null) continue;

            Set<String> neighbors = getNeighbors(current, spatialIndex, chunkIndex, xzRadius, yTolerance);
            for (String neighborKey : neighbors) {
                if (!connectedToEntry.contains(neighborKey)) {
                    connectedToEntry.add(neighborKey);
                    queue.add(neighborKey);
                }
            }
        }

        debug.add("BFS iterations: " + iterations + " (max: " + maxIterations + ")");
        debug.add("Blocks connected to entry: " + connectedToEntry.size() + " / " + blocks.size());

        int disconnectedCount = blocks.size() - connectedToEntry.size();
        debug.add("Disconnected blocks: " + disconnectedCount);

        double disconnectedPercent = (double) disconnectedCount / blocks.size() * 100;
        debug.add("Disconnected %: " + String.format("%.1f%%", disconnectedPercent));

        // Check gap threshold
        boolean hasGap = disconnectedCount >= 3 && disconnectedCount > blocks.size() * 0.1;
        debug.add("Gap threshold: ≥3 blocks AND >10% disconnected");
        debug.add("  ≥3 blocks: " + (disconnectedCount >= 3 ? "YES" : "NO") + " (" + disconnectedCount + " disconnected)");
        debug.add("  >10%: " + (disconnectedCount > blocks.size() * 0.1 ? "YES" : "NO") + " (" + String.format("%.1f%%", disconnectedPercent) + ")");
        debug.add("");
        debug.add("hasRoadGapsInRegion would return: " + (hasGap ? "TRUE (GAP DETECTED)" : "FALSE (NO GAP)"));

        // If there are disconnected blocks, show some examples
        if (disconnectedCount > 0) {
            debug.add("");
            debug.add("Sample disconnected blocks:");
            int count = 0;
            for (RoadBlock block : blocks) {
                if (!connectedToEntry.contains(block.toKey())) {
                    if (count++ >= 5) {
                        debug.add("  ... and " + (disconnectedCount - 5) + " more");
                        break;
                    }
                    debug.add("  " + block.x() + ", " + block.y() + ", " + block.z());
                }
            }
        }

        return debug;
    }

    /**
     * Gets the bounding box of a component [minX, maxX, minY, maxY, minZ, maxZ].
     */
    private int[] getComponentBounds(List<RoadBlock> component) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (RoadBlock block : component) {
            minX = Math.min(minX, block.x());
            maxX = Math.max(maxX, block.x());
            minY = Math.min(minY, block.y());
            maxY = Math.max(maxY, block.y());
            minZ = Math.min(minZ, block.z());
            maxZ = Math.max(maxZ, block.z());
        }
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    /**
     * Finds the closest blocks between two components.
     * Returns [x1, y1, z1, x2, y2, z2] or null.
     */
    private int[] findClosestBlocks(List<RoadBlock> comp1, List<RoadBlock> comp2) {
        int closestDist = Integer.MAX_VALUE;
        RoadBlock closest1 = null, closest2 = null;

        for (RoadBlock b1 : comp1) {
            for (RoadBlock b2 : comp2) {
                int dist = Math.abs(b1.x() - b2.x()) + Math.abs(b1.z() - b2.z());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest1 = b1;
                    closest2 = b2;
                }
            }
        }

        if (closest1 == null || closest2 == null) return null;
        return new int[]{closest1.x(), closest1.y(), closest1.z(), closest2.x(), closest2.y(), closest2.z()};
    }

    // ==================== RESPAWN & HEALTH OPERATIONS ====================

    @Override
    public int getRespawnDelay(UUID playerUuid, String team) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return 0;

        String regionId = getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        if (regionId == null) return 0;

        SupplyLevel level = getSupplyLevel(regionId, team);

        // Use config values if available, otherwise use enum defaults
        return switch (level) {
            case SUPPLIED -> 0;
            case PARTIAL -> configManager.getSupplyPartialRespawnDelay();
            case UNSUPPLIED -> configManager.getSupplyUnsuppliedRespawnDelay();
            case ISOLATED -> configManager.getSupplyIsolatedRespawnDelay();
        };
    }

    @Override
    public double getHealthRegenMultiplier(UUID playerUuid, String team) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return 1.0;

        String regionId = getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        if (regionId == null) return 1.0;

        SupplyLevel level = getSupplyLevel(regionId, team);

        return switch (level) {
            case SUPPLIED, PARTIAL -> 1.0;
            case UNSUPPLIED -> configManager.getSupplyUnsuppliedHealthRegen();
            case ISOLATED -> configManager.getSupplyIsolatedHealthRegen();
        };
    }

    // ==================== AUTO-SCANNING ====================

    @Override
    public int scanRegionForRoads(String regionId, String team, org.bukkit.World world) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) {
            logger.warning("[RoadService] Cannot scan region - no active round!");
            return 0;
        }

        if (world == null) {
            logger.warning("[RoadService] Cannot scan region - world is null!");
            return 0;
        }

        // Get region bounds
        int[] bounds = getRegionBounds(regionId);
        if (bounds == null) {
            logger.warning("[RoadService] Cannot scan region - invalid region ID: " + regionId);
            return 0;
        }

        int minX = bounds[0], maxX = bounds[1], minZ = bounds[2], maxZ = bounds[3];

        // Get valid path block types from config
        List<String> pathBlockNames = configManager.getSupplyPathBlocks();
        Set<org.bukkit.Material> pathBlocks = new java.util.HashSet<>();
        if (pathBlockNames == null || pathBlockNames.isEmpty()) {
            pathBlocks.add(org.bukkit.Material.DIRT_PATH);
            pathBlocks.add(org.bukkit.Material.GRAVEL);
            pathBlocks.add(org.bukkit.Material.COBBLESTONE);
            pathBlocks.add(org.bukkit.Material.STONE_BRICKS);
            pathBlocks.add(org.bukkit.Material.POLISHED_ANDESITE);
        } else {
            for (String name : pathBlockNames) {
                try {
                    pathBlocks.add(org.bukkit.Material.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // Scan parameters
        final int START_Y = 52;
        final int AIR_THRESHOLD = 10;
        final int SOLID_THRESHOLD = 10;

        int foundCount = 0;
        UUID systemUuid = UUID.fromString("00000000-0000-0000-0000-000000000000"); // System-placed

        // Scan the region for path blocks
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Scan up from START_Y
                int consecutiveAir = 0;
                for (int y = START_Y; y < world.getMaxHeight() && consecutiveAir < AIR_THRESHOLD; y++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    org.bukkit.Material type = block.getType();

                    if (type.isAir()) {
                        consecutiveAir++;
                    } else {
                        consecutiveAir = 0;
                        if (pathBlocks.contains(type)) {
                            // Check if already registered
                            if (!isRoadBlock(x, y, z)) {
                                insertRoadBlockWithoutRecalculation(x, y, z, systemUuid, team);
                                foundCount++;
                            }
                        }
                    }
                }

                // Scan down from START_Y
                int consecutiveSolid = 0;
                for (int y = START_Y - 1; y >= world.getMinHeight() && consecutiveSolid < SOLID_THRESHOLD; y--) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    org.bukkit.Material type = block.getType();

                    if (pathBlocks.contains(type)) {
                        // Check if already registered
                        if (!isRoadBlock(x, y, z)) {
                            insertRoadBlockWithoutRecalculation(x, y, z, systemUuid, team);
                            foundCount++;
                        }
                        consecutiveSolid = 0;
                    } else if (type.isAir()) {
                        consecutiveSolid = 0;
                    } else {
                        consecutiveSolid++;
                    }
                }
            }
        }

        // Recalculate supply once at the end
        if (foundCount > 0) {
            recalculateSupply(team);
            logger.info("[RoadService] Auto-scanned " + regionId + " for " + team + ": found " + foundCount + " road blocks");
        }

        // Also scan the border areas of adjacent owned regions to ensure connections work
        // This fixes the case where home region has roads but they weren't registered
        int borderBlocksFound = scanAdjacentBordersForRoads(regionId, team, world, pathBlocks);
        if (borderBlocksFound > 0) {
            recalculateSupply(team);
            logger.info("[RoadService] Auto-scanned adjacent borders for " + regionId + ": found " + borderBlocksFound + " additional road blocks");
        }

        return foundCount + borderBlocksFound;
    }

    /**
     * Scans the border areas of adjacent owned regions for path blocks.
     * If an adjacent region has NO road blocks, does a full scan of that region too.
     */
    private int scanAdjacentBordersForRoads(String regionId, String team, org.bukkit.World world,
                                             Set<org.bukkit.Material> pathBlocks) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return 0;

        UUID systemUuid = UUID.fromString("00000000-0000-0000-0000-000000000000");
        int totalFound = 0;

        logger.info("[RoadService] Scanning adjacent borders for " + regionId + " (team: " + team + ")");

        // Get adjacent regions that are owned by the same team
        List<String> adjacentRegions = regionService.getAdjacentRegions(regionId);
        logger.info("[RoadService] Adjacent regions: " + adjacentRegions);

        for (String adjRegion : adjacentRegions) {
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adjRegion);

            if (adjStatus.isEmpty()) {
                logger.info("[RoadService] " + adjRegion + " has no status");
                continue;
            }

            if (!adjStatus.get().isOwnedBy(team)) {
                logger.info("[RoadService] " + adjRegion + " not owned by " + team + " (owner: " + adjStatus.get().ownerTeam() + ")");
                continue;
            }

            // Check if adjacent region has ANY road blocks
            int existingBlocks = getRoadBlockCount(adjRegion, team);
            logger.info("[RoadService] " + adjRegion + " has " + existingBlocks + " existing road blocks");

            if (existingBlocks == 0) {
                // Adjacent region has NO road blocks - do a full scan of that region
                // This handles the case where home region was never scanned
                logger.info("[RoadService] Adjacent region " + adjRegion + " has no road blocks - scanning entire region");

                int[] adjBounds = getRegionBounds(adjRegion);
                if (adjBounds != null) {
                    logger.info("[RoadService] Scanning " + adjRegion + " bounds: X[" + adjBounds[0] + " to " + adjBounds[1] + "] Z[" + adjBounds[2] + " to " + adjBounds[3] + "]");
                    int adjFound = scanAreaForRoads(adjBounds[0], adjBounds[1], adjBounds[2], adjBounds[3],
                            world, pathBlocks, systemUuid, team);
                    totalFound += adjFound;
                    logger.info("[RoadService] Found " + adjFound + " road blocks in " + adjRegion);
                }
            } else {
                // Adjacent region has blocks - scan the border area
                int[] border = getBorderArea(regionId, adjRegion);
                if (border != null) {
                    logger.info("[RoadService] Scanning border " + regionId + "<->" + adjRegion +
                            ": X[" + border[0] + " to " + border[1] + "] Z[" + border[2] + " to " + border[3] + "]");

                    // Check how many existing blocks are at the border ON BOTH SIDES
                    List<RoadBlock> blocksInAdj = getRoadBlocksInRegion(adjRegion, team).stream()
                            .filter(b -> b.x() >= border[0] && b.x() <= border[1] &&
                                        b.z() >= border[2] && b.z() <= border[3])
                            .toList();
                    List<RoadBlock> blocksInThis = getRoadBlocksInRegion(regionId, team).stream()
                            .filter(b -> b.x() >= border[0] && b.x() <= border[1] &&
                                        b.z() >= border[2] && b.z() <= border[3])
                            .toList();
                    logger.info("[RoadService] Existing blocks at border - " + adjRegion + ": " + blocksInAdj.size() +
                            ", " + regionId + ": " + blocksInThis.size());

                    // Show sample coordinates
                    if (!blocksInAdj.isEmpty()) {
                        RoadBlock sample = blocksInAdj.get(0);
                        logger.info("[RoadService] Sample block in " + adjRegion + ": " + sample.x() + "," + sample.y() + "," + sample.z());
                    }
                    if (!blocksInThis.isEmpty()) {
                        RoadBlock sample = blocksInThis.get(0);
                        logger.info("[RoadService] Sample block in " + regionId + ": " + sample.x() + "," + sample.y() + "," + sample.z());
                    }

                    int borderFound = scanAreaForRoads(border[0], border[1], border[2], border[3],
                            world, pathBlocks, systemUuid, team);
                    totalFound += borderFound;
                    if (borderFound > 0) {
                        logger.info("[RoadService] Found " + borderFound + " NEW road blocks at border " + regionId + "<->" + adjRegion);
                    } else {
                        logger.info("[RoadService] No new path blocks found at border");

                        // If both sides have blocks but connection fails, explain why
                        if (!blocksInAdj.isEmpty() && !blocksInThis.isEmpty()) {
                            boolean connected = checkBorderRoadConnection(regionId, adjRegion, team);
                            logger.info("[RoadService] Border connection check: " + (connected ? "CONNECTED" : "FAILED"));
                        } else if (blocksInAdj.isEmpty()) {
                            logger.info("[RoadService] " + adjRegion + " has no blocks at border!");
                        } else if (blocksInThis.isEmpty()) {
                            logger.info("[RoadService] " + regionId + " has no blocks at border!");
                        }
                    }
                }
            }
        }

        logger.info("[RoadService] Total found in adjacent scans: " + totalFound);
        return totalFound;
    }

    /**
     * Scans a rectangular area for path blocks and registers them.
     */
    private int scanAreaForRoads(int minX, int maxX, int minZ, int maxZ,
                                  org.bukkit.World world, Set<org.bukkit.Material> pathBlocks,
                                  UUID systemUuid, String team) {
        final int START_Y = 52;
        final int AIR_THRESHOLD = 100; // High threshold to catch elevated roads/bridges
        final int SOLID_THRESHOLD = 10;
        int foundCount = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Scan up from START_Y
                int consecutiveAir = 0;
                for (int y = START_Y; y < world.getMaxHeight() && consecutiveAir < AIR_THRESHOLD; y++) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    org.bukkit.Material type = block.getType();

                    if (type.isAir()) {
                        consecutiveAir++;
                    } else {
                        consecutiveAir = 0;
                        if (pathBlocks.contains(type)) {
                            if (!isRoadBlock(x, y, z)) {
                                insertRoadBlockWithoutRecalculation(x, y, z, systemUuid, team);
                                foundCount++;
                            }
                        }
                    }
                }

                // Scan down from START_Y
                int consecutiveSolid = 0;
                for (int y = START_Y - 1; y >= world.getMinHeight() && consecutiveSolid < SOLID_THRESHOLD; y--) {
                    org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                    org.bukkit.Material type = block.getType();

                    if (pathBlocks.contains(type)) {
                        if (!isRoadBlock(x, y, z)) {
                            insertRoadBlockWithoutRecalculation(x, y, z, systemUuid, team);
                            foundCount++;
                        }
                        consecutiveSolid = 0;
                    } else if (type.isAir()) {
                        consecutiveSolid = 0;
                    } else {
                        consecutiveSolid++;
                    }
                }
            }
        }

        return foundCount;
    }

    /**
     * Gets the bounds for a region.
     * @return [minX, maxX, minZ, maxZ] or null if invalid
     */
    private int[] getRegionBounds(String regionId) {
        if (regionId == null || regionId.length() < 2) return null;

        char row = regionId.toUpperCase().charAt(0);
        int col;
        try {
            col = Integer.parseInt(regionId.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }

        if (row < 'A' || row > 'D' || col < 1 || col > 4) return null;

        int gridX = col - 1;
        int gridZ = row - 'A';

        int minX = gridX * REGION_BLOCKS - HALF_SIZE;
        int maxX = minX + REGION_BLOCKS - 1;
        int minZ = gridZ * REGION_BLOCKS - HALF_SIZE;
        int maxZ = minZ + REGION_BLOCKS - 1;

        return new int[]{minX, maxX, minZ, maxZ};
    }

    // ==================== CLEANUP ====================

    @Override
    public void clearAllData() {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;
        db.clearAllData(roundId);
        roadBlockCache.clear();
        supplyCache.clear();
    }

    @Override
    public void clearRegionData(String regionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;
        db.clearRegionData(roundId, regionId);

        // Clear cache entries for this region
        roadBlockCache.entrySet().removeIf(e -> e.getValue().regionId().equals(regionId));
        supplyCache.entrySet().removeIf(e -> e.getKey().startsWith(regionId + ":"));
    }

    // ==================== HELPER METHODS ====================

    private String getRegionIdForLocation(int blockX, int blockZ) {
        int gridX = (blockX + HALF_SIZE) / REGION_BLOCKS;
        int gridZ = (blockZ + HALF_SIZE) / REGION_BLOCKS;

        if (gridX < 0 || gridX >= GRID_SIZE || gridZ < 0 || gridZ >= GRID_SIZE) {
            return null;
        }

        char rowLabel = (char) ('A' + gridZ);
        return rowLabel + String.valueOf(gridX + 1);
    }

    private String getHomeRegion(String team) {
        if ("red".equalsIgnoreCase(team)) {
            return configManager.getRegionRedHome();
        } else if ("blue".equalsIgnoreCase(team)) {
            return configManager.getRegionBlueHome();
        }
        return null;
    }
}

