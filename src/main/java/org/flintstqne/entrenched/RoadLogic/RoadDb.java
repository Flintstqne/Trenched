package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Database access layer for road/supply line system.
 */
public final class RoadDb implements AutoCloseable {

    private final Connection connection;

    public RoadDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "roads.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open roads database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            // Enable WAL mode for better concurrent read/write performance
            // WAL allows readers and writers to operate simultaneously
            st.executeUpdate("PRAGMA journal_mode=WAL");
            // NORMAL synchronous is safe with WAL and much faster than FULL
            st.executeUpdate("PRAGMA synchronous=NORMAL");
            st.executeUpdate("PRAGMA foreign_keys = ON");

            // Road blocks table - tracks individual path blocks
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS road_blocks (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  round_id INTEGER NOT NULL,
                  region_id TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  placed_by TEXT NOT NULL,
                  team TEXT NOT NULL,
                  placed_at INTEGER,
                  player_placed INTEGER NOT NULL DEFAULT 0,
                  UNIQUE(round_id, x, y, z)
                )
                """);

            // Create index for faster lookups
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_road_blocks_coords 
                ON road_blocks(round_id, x, y, z)
                """);

            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_road_blocks_region 
                ON road_blocks(round_id, region_id, team)
                """);

            // Index for player-placed filtering (region-based queries)
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_road_blocks_player_placed 
                ON road_blocks(round_id, region_id, team, player_placed)
                """);

            // Index for area-based queries with player-placed filter
            // Covers getRoadBlocksInArea with playerPlacedOnly=true
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_road_blocks_area_player_placed
                ON road_blocks(round_id, team, player_placed, x, z)
                """);

            // Supply status cache table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS supply_status (
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  team TEXT NOT NULL,
                  supply_level TEXT DEFAULT 'UNSUPPLIED',
                  connected_to_home INTEGER DEFAULT 0,
                  last_updated INTEGER,
                  PRIMARY KEY(region_id, round_id, team)
                )
                """);

            // Migration: add player_placed column if it doesn't exist (for existing databases)
            try {
                st.executeUpdate("ALTER TABLE road_blocks ADD COLUMN player_placed INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // Column already exists - this is fine
            }
        }
    }

    // ==================== ROAD BLOCK METHODS ====================

    public void insertRoadBlock(int roundId, String regionId, int x, int y, int z,
                                String placedBy, String team, long placedAt, boolean playerPlaced) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO road_blocks(round_id, region_id, x, y, z, placed_by, team, placed_at, player_placed)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, placedBy);
            ps.setString(7, team);
            ps.setLong(8, placedAt);
            ps.setInt(9, playerPlaced ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert road block", e);
        }
    }

    public Optional<RoadBlock> getRoadBlock(int roundId, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM road_blocks WHERE round_id = ? AND x = ? AND y = ? AND z = ?
            """)) {
            ps.setInt(1, roundId);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRoadBlock(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get road block", e);
        }
    }

    public Optional<String> deleteRoadBlock(int roundId, int x, int y, int z) {
        // First get the team that owned this block
        Optional<RoadBlock> existing = getRoadBlock(roundId, x, y, z);
        if (existing.isEmpty()) return Optional.empty();

        String team = existing.get().team();

        try (PreparedStatement ps = connection.prepareStatement("""
            DELETE FROM road_blocks WHERE round_id = ? AND x = ? AND y = ? AND z = ?
            """)) {
            ps.setInt(1, roundId);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete road block", e);
        }

        return Optional.of(team);
    }

    public List<RoadBlock> getRoadBlocksInRegion(int roundId, String regionId, String team) {
        return getRoadBlocksInRegion(roundId, regionId, team, false);
    }

    public List<RoadBlock> getRoadBlocksInRegion(int roundId, String regionId, String team, boolean playerPlacedOnly) {
        String sql = playerPlacedOnly
                ? "SELECT * FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ? AND player_placed = 1"
                : "SELECT * FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setString(3, team);
            try (ResultSet rs = ps.executeQuery()) {
                List<RoadBlock> blocks = new ArrayList<>();
                while (rs.next()) {
                    blocks.add(mapRoadBlock(rs));
                }
                return blocks;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get road blocks in region", e);
        }
    }

    public int getRoadBlockCount(int roundId, String regionId, String team) {
        return getRoadBlockCount(roundId, regionId, team, false);
    }

    public int getRoadBlockCount(int roundId, String regionId, String team, boolean playerPlacedOnly) {
        String sql = playerPlacedOnly
                ? "SELECT COUNT(*) FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ? AND player_placed = 1"
                : "SELECT COUNT(*) FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setString(3, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count road blocks", e);
        }
    }

    public List<RoadBlock> getRoadBlocksInArea(int roundId, int minX, int maxX, int minZ, int maxZ, String team) {
        return getRoadBlocksInArea(roundId, minX, maxX, minZ, maxZ, team, false);
    }

    public List<RoadBlock> getRoadBlocksInArea(int roundId, int minX, int maxX, int minZ, int maxZ, String team, boolean playerPlacedOnly) {
        String sql = playerPlacedOnly
                ? "SELECT * FROM road_blocks WHERE round_id = ? AND team = ? AND x >= ? AND x <= ? AND z >= ? AND z <= ? AND player_placed = 1"
                : "SELECT * FROM road_blocks WHERE round_id = ? AND team = ? AND x >= ? AND x <= ? AND z >= ? AND z <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setInt(3, minX);
            ps.setInt(4, maxX);
            ps.setInt(5, minZ);
            ps.setInt(6, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                List<RoadBlock> blocks = new ArrayList<>();
                while (rs.next()) {
                    blocks.add(mapRoadBlock(rs));
                }
                return blocks;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get road blocks in area", e);
        }
    }

    // ==================== SUPPLY STATUS METHODS ====================

    public void updateSupplyStatus(int roundId, String regionId, String team,
                                   SupplyLevel level, boolean connectedToHome) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO supply_status(region_id, round_id, team, supply_level, connected_to_home, last_updated)
            VALUES(?, ?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);
            ps.setString(3, team);
            ps.setString(4, level.name());
            ps.setInt(5, connectedToHome ? 1 : 0);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update supply status", e);
        }
    }

    public Optional<SupplyLevel> getSupplyLevel(int roundId, String regionId, String team) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT supply_level FROM supply_status 
            WHERE round_id = ? AND region_id = ? AND team = ?
            """)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setString(3, team);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(SupplyLevel.valueOf(rs.getString("supply_level")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get supply level", e);
        }
    }

    public boolean isConnectedToHome(int roundId, String regionId, String team) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT connected_to_home FROM supply_status 
            WHERE round_id = ? AND region_id = ? AND team = ?
            """)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setString(3, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("connected_to_home") == 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check home connection", e);
        }
    }

    // ==================== CLEANUP METHODS ====================

    /**
     * Batch-inserts multiple road blocks in a single transaction.
     * Much faster than individual inserts for auto-scanning.
     */
    public int batchInsertRoadBlocks(int roundId, List<int[]> coords, String regionIdPrefix,
                                      String placedBy, String team, long placedAt, boolean playerPlaced,
                                      java.util.function.Function<int[], String> regionIdMapper) {
        String sql = """
            INSERT OR IGNORE INTO road_blocks(round_id, region_id, x, y, z, placed_by, team, placed_at, player_placed)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        int inserted = 0;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int[] c : coords) {
                    String regionId = regionIdMapper.apply(c);
                    if (regionId == null) continue;
                    ps.setInt(1, roundId);
                    ps.setString(2, regionId);
                    ps.setInt(3, c[0]); // x
                    ps.setInt(4, c[1]); // y
                    ps.setInt(5, c[2]); // z
                    ps.setString(6, placedBy);
                    ps.setString(7, team);
                    ps.setLong(8, placedAt);
                    ps.setInt(9, playerPlaced ? 1 : 0);
                    ps.addBatch();
                    inserted++;

                    // Execute in batches of 500 to avoid memory issues
                    if (inserted % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("Failed to batch insert road blocks", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        return inserted;
    }

    /**
     * Counts road blocks in an area efficiently without loading all data.
     * Used for quick border presence checks.
     */
    public int countRoadBlocksInArea(int roundId, int minX, int maxX, int minZ, int maxZ,
                                      String team, boolean playerPlacedOnly) {
        String sql = playerPlacedOnly
                ? "SELECT COUNT(*) FROM road_blocks WHERE round_id = ? AND team = ? AND x >= ? AND x <= ? AND z >= ? AND z <= ? AND player_placed = 1"
                : "SELECT COUNT(*) FROM road_blocks WHERE round_id = ? AND team = ? AND x >= ? AND x <= ? AND z >= ? AND z <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setInt(3, minX);
            ps.setInt(4, maxX);
            ps.setInt(5, minZ);
            ps.setInt(6, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count road blocks in area", e);
        }
    }

    public void clearAllData(int roundId) {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM road_blocks WHERE round_id = " + roundId);
            st.executeUpdate("DELETE FROM supply_status WHERE round_id = " + roundId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear road data", e);
        }
    }

    public void clearRegionData(int roundId, String regionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM road_blocks WHERE round_id = ? AND region_id = ?")) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear region road data", e);
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM supply_status WHERE round_id = ? AND region_id = ?")) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear region supply status", e);
        }
    }

    // ==================== HELPER METHODS ====================

    private RoadBlock mapRoadBlock(ResultSet rs) throws SQLException {
        return new RoadBlock(
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("region_id"),
                rs.getString("placed_by"),
                rs.getString("team"),
                rs.getLong("placed_at"),
                rs.getInt("player_placed") == 1
        );
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}

