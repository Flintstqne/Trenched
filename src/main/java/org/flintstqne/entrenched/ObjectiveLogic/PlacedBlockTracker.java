package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * In-memory cache and async write manager for player-placed block tracking.
 * <p>
 * Only tracks blocks placed near active building objectives or registered buildings.
 * Uses packed long coordinates in HashSets for O(1) lookup during structure scans.
 * Writes are batched and flushed to SQLite every N seconds on an async thread.
 */
public class PlacedBlockTracker {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PlacedBlockDb db;

    // regionId -> Set of packed coordinates
    private final Map<String, Set<Long>> regionCache = new ConcurrentHashMap<>();

    // Regions whose blocks have been loaded from DB
    private final Set<String> loadedRegions = ConcurrentHashMap.newKeySet();

    // Async write queues
    private final ConcurrentLinkedQueue<PlacedBlockDb.PlacedBlockRecord> pendingWrites = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DeleteRecord> pendingDeletes = new ConcurrentLinkedQueue<>();

    // Flush task
    private BukkitTask flushTask;
    private BukkitTask cleanupTask;

    // Config
    private final long flushIntervalTicks;
    private final long cleanupIntervalTicks;

    // Callback to check if a region still needs tracking
    private RegionActiveChecker regionActiveChecker;

    @FunctionalInterface
    public interface RegionActiveChecker {
        boolean isRegionActive(String regionId);
    }

    public PlacedBlockTracker(JavaPlugin plugin, PlacedBlockDb db, long flushIntervalSeconds, long cleanupIntervalMinutes) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.db = db;
        this.flushIntervalTicks = flushIntervalSeconds * 20L;
        this.cleanupIntervalTicks = cleanupIntervalMinutes * 60L * 20L;
    }

    /**
     * Sets the callback used during cleanup to determine if a region still needs tracking.
     */
    public void setRegionActiveChecker(RegionActiveChecker checker) {
        this.regionActiveChecker = checker;
    }

    // ==================== LIFECYCLE ====================

    public void start() {
        // Async flush task
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::flush, flushIntervalTicks, flushIntervalTicks);

        // Async cleanup task
        cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::cleanup, cleanupIntervalTicks, cleanupIntervalTicks);

        logger.info("[PlacedBlocks] Tracker started (flush every " + (flushIntervalTicks / 20) + "s, cleanup every " + (cleanupIntervalTicks / 1200) + "min)");
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();

        // Synchronous final flush
        flush();

        logger.info("[PlacedBlocks] Tracker stopped");
    }

    // ==================== COORDINATE PACKING ====================

    /**
     * Packs (x, y, z) into a single long.
     * x: bits 0-25 (26 bits, range ±33M — plenty for MC coords)
     * y: bits 26-37 (12 bits, range -2048 to 2047 — covers MC build height)
     * z: bits 38-63 (26 bits, range ±33M)
     */
    static long packCoord(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF))
                | (((long) (y & 0xFFF)) << 26)
                | (((long) (z & 0x3FFFFFF)) << 38);
    }

    static int unpackX(long packed) {
        int raw = (int) (packed & 0x3FFFFFF);
        return (raw & 0x2000000) != 0 ? raw | ~0x3FFFFFF : raw; // sign-extend
    }

    static int unpackY(long packed) {
        int raw = (int) ((packed >> 26) & 0xFFF);
        return (raw & 0x800) != 0 ? raw | ~0xFFF : raw; // sign-extend
    }

    static int unpackZ(long packed) {
        int raw = (int) ((packed >> 38) & 0x3FFFFFF);
        return (raw & 0x2000000) != 0 ? raw | ~0x3FFFFFF : raw; // sign-extend
    }

    // ==================== CACHE OPERATIONS ====================

    /**
     * Loads a region's placed blocks from DB into cache.
     * Should be called when a building objective spawns or on server start for active objectives.
     */
    public void loadRegion(String regionId) {
        if (loadedRegions.contains(regionId)) return;

        List<int[]> blocks = db.loadRegion(regionId);
        Set<Long> packedSet = ConcurrentHashMap.newKeySet();
        for (int[] coord : blocks) {
            packedSet.add(packCoord(coord[0], coord[1], coord[2]));
        }
        regionCache.put(regionId, packedSet);
        loadedRegions.add(regionId);

        if (!blocks.isEmpty()) {
            logger.info("[PlacedBlocks] Loaded " + blocks.size() + " tracked blocks for region " + regionId);
        }
    }

    /**
     * Checks if a block at (x, y, z) is player-placed for the given region.
     * Returns false if the region's cache hasn't been loaded.
     */
    public boolean isPlayerPlaced(int x, int y, int z, String regionId) {
        Set<Long> regionSet = regionCache.get(regionId);
        if (regionSet == null) return false;
        return regionSet.contains(packCoord(x, y, z));
    }

    /**
     * Returns whether the region's cache has been loaded.
     * Used by BuildingDetector to decide whether to use player-placed filtering or fallback.
     */
    public boolean isRegionLoaded(String regionId) {
        return loadedRegions.contains(regionId);
    }

    /**
     * Returns the set of packed coordinates for a region (for direct use in scan loops).
     * Returns null if not loaded.
     */
    public Set<Long> getRegionSet(String regionId) {
        return regionCache.get(regionId);
    }

    // ==================== TRACKING ====================

    /**
     * Track a player-placed block. Adds to cache immediately and queues a DB write.
     */
    public void trackBlock(int x, int y, int z, UUID playerUuid, String team, String regionId) {
        long packed = packCoord(x, y, z);

        // Add to cache
        regionCache.computeIfAbsent(regionId, k -> ConcurrentHashMap.newKeySet()).add(packed);

        // Queue async DB write
        pendingWrites.offer(new PlacedBlockDb.PlacedBlockRecord(x, y, z, playerUuid, team, regionId, System.currentTimeMillis()));
    }

    /**
     * Untrack a block (broken/exploded/moved). Removes from cache and queues a DB delete.
     */
    public void untrackBlock(int x, int y, int z) {
        long packed = packCoord(x, y, z);

        // Remove from all region caches (block can only be in one, but check all for safety)
        for (Set<Long> regionSet : regionCache.values()) {
            regionSet.remove(packed);
        }

        // Queue async DB delete
        pendingDeletes.offer(new DeleteRecord(x, y, z));
    }

    // ==================== FLUSH ====================

    /**
     * Drains pending writes and deletes into a single SQLite transaction.
     * Runs on an async thread.
     */
    private void flush() {
        // Drain writes
        List<PlacedBlockDb.PlacedBlockRecord> writes = new ArrayList<>();
        PlacedBlockDb.PlacedBlockRecord record;
        while ((record = pendingWrites.poll()) != null) {
            writes.add(record);
        }

        // Drain deletes
        List<long[]> deletes = new ArrayList<>();
        DeleteRecord deleteRecord;
        while ((deleteRecord = pendingDeletes.poll()) != null) {
            deletes.add(new long[]{ deleteRecord.x, deleteRecord.y, deleteRecord.z });
        }

        if (!writes.isEmpty()) {
            db.batchInsert(writes);
        }
        if (!deletes.isEmpty()) {
            db.batchDelete(deletes);
        }
    }

    // ==================== CLEANUP ====================

    /**
     * Removes tracked data for regions that no longer have active building objectives.
     * Runs on an async thread periodically.
     */
    private void cleanup() {
        if (regionActiveChecker == null) return;

        List<String> trackedRegions = db.getTrackedRegions();
        for (String regionId : trackedRegions) {
            if (!regionActiveChecker.isRegionActive(regionId)) {
                db.deleteRegion(regionId);
                regionCache.remove(regionId);
                loadedRegions.remove(regionId);
            }
        }
    }

    /**
     * Clear all tracking data (round reset).
     */
    public void clearAll() {
        pendingWrites.clear();
        pendingDeletes.clear();
        regionCache.clear();
        loadedRegions.clear();
        db.deleteAll();
    }

    /**
     * Clear tracking for a specific region (e.g., region captured).
     */
    public void clearRegion(String regionId) {
        regionCache.remove(regionId);
        loadedRegions.remove(regionId);
        // Queue the DB cleanup on next flush — but for region deletes, do it directly
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> db.deleteRegion(regionId));
    }

    // ==================== INTERNAL ====================

    private record DeleteRecord(int x, int y, int z) {}
}

