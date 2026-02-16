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
        }
    }

    // ==================== ROAD BLOCK METHODS ====================

    public void insertRoadBlock(int roundId, String regionId, int x, int y, int z,
                                String placedBy, String team, long placedAt) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR REPLACE INTO road_blocks(round_id, region_id, x, y, z, placed_by, team, placed_at)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            ps.setInt(1, roundId);
            ps.setString(2, regionId);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, placedBy);
            ps.setString(7, team);
            ps.setLong(8, placedAt);
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
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ?
            """)) {
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
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT COUNT(*) FROM road_blocks WHERE round_id = ? AND region_id = ? AND team = ?
            """)) {
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
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM road_blocks 
            WHERE round_id = ? AND team = ? 
            AND x >= ? AND x <= ? AND z >= ? AND z <= ?
            """)) {
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
                rs.getLong("placed_at")
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

