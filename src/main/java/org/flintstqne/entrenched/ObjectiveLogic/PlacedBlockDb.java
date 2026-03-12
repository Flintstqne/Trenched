package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite persistence for player-placed block tracking.
 * Stores blocks placed near building objectives so that structure detection
 * can distinguish player builds from natural terrain.
 */
public class PlacedBlockDb {

    private final Logger logger;
    private Connection connection;

    public PlacedBlockDb(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            String dbPath = plugin.getDataFolder().getAbsolutePath() + "/placed_blocks.db";
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // Enable WAL mode for better concurrent read/write performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }

            createTables();
            logger.info("[PlacedBlocks] Database initialized");
        } catch (Exception e) {
            logger.severe("[PlacedBlocks] Failed to initialize database: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS placed_blocks (
                    x          INTEGER NOT NULL,
                    y          INTEGER NOT NULL,
                    z          INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    team       TEXT NOT NULL,
                    region_id  TEXT NOT NULL,
                    placed_at  INTEGER NOT NULL,
                    PRIMARY KEY (x, y, z)
                )
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_placed_blocks_region
                    ON placed_blocks (region_id)
            """);
        }
    }

    // ==================== BATCH OPERATIONS ====================

    /**
     * Batch insert placed blocks in a single transaction.
     */
    public void batchInsert(List<PlacedBlockRecord> records) {
        if (records.isEmpty() || !isOpen()) return;

        String sql = "INSERT OR IGNORE INTO placed_blocks (x, y, z, player_uuid, team, region_id, placed_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (PlacedBlockRecord record : records) {
                    ps.setInt(1, record.x());
                    ps.setInt(2, record.y());
                    ps.setInt(3, record.z());
                    ps.setString(4, record.playerUuid().toString());
                    ps.setString(5, record.team());
                    ps.setString(6, record.regionId());
                    ps.setLong(7, record.placedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to batch insert: " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /**
     * Batch delete placed blocks by coordinates in a single transaction.
     */
    public void batchDelete(List<long[]> coords) {
        if (coords.isEmpty() || !isOpen()) return;

        String sql = "DELETE FROM placed_blocks WHERE x = ? AND y = ? AND z = ?";

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (long[] coord : coords) {
                    ps.setLong(1, coord[0]);
                    ps.setLong(2, coord[1]);
                    ps.setLong(3, coord[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to batch delete: " + e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ==================== QUERY OPERATIONS ====================

    /**
     * Load all placed block coordinates for a region.
     * Returns a list of [x, y, z] arrays.
     */
    public List<int[]> loadRegion(String regionId) {
        List<int[]> blocks = new ArrayList<>();
        if (!isOpen()) return blocks;

        String sql = "SELECT x, y, z FROM placed_blocks WHERE region_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    blocks.add(new int[]{ rs.getInt("x"), rs.getInt("y"), rs.getInt("z") });
                }
            }
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to load region " + regionId + ": " + e.getMessage());
        }

        return blocks;
    }

    /**
     * Get all distinct region IDs that have tracked blocks.
     */
    public List<String> getTrackedRegions() {
        List<String> regions = new ArrayList<>();
        if (!isOpen()) return regions;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT region_id FROM placed_blocks")) {
            while (rs.next()) {
                regions.add(rs.getString("region_id"));
            }
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to get tracked regions: " + e.getMessage());
        }

        return regions;
    }

    // ==================== CLEANUP OPERATIONS ====================

    /**
     * Delete all placed blocks for a specific region.
     */
    public void deleteRegion(String regionId) {
        if (!isOpen()) return;

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM placed_blocks WHERE region_id = ?")) {
            ps.setString(1, regionId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                logger.info("[PlacedBlocks] Cleaned up " + deleted + " blocks in region " + regionId);
            }
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to delete region " + regionId + ": " + e.getMessage());
        }
    }

    /**
     * Delete all placed blocks (round reset).
     */
    public void deleteAll() {
        if (!isOpen()) return;

        try (Statement stmt = connection.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM placed_blocks");
            logger.info("[PlacedBlocks] Cleared all tracked blocks (" + deleted + " rows)");
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to delete all: " + e.getMessage());
        }
    }

    // ==================== LIFECYCLE ====================

    private boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("[PlacedBlocks] Database connection closed");
            }
        } catch (SQLException e) {
            logger.warning("[PlacedBlocks] Failed to close database: " + e.getMessage());
        }
    }

    // ==================== DATA RECORD ====================

    public record PlacedBlockRecord(int x, int y, int z, UUID playerUuid, String team, String regionId, long placedAt) {}
}

