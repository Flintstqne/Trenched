package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Database access layer for Region capture system.
 */
public final class RegionDb implements AutoCloseable {

    private final Connection connection;

    public RegionDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "regions.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open regions database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");

            // Region status table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS region_status (
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  owner_team TEXT,
                  state TEXT DEFAULT 'NEUTRAL',
                  red_influence REAL DEFAULT 0,
                  blue_influence REAL DEFAULT 0,
                  fortified_until INTEGER,
                  owned_since INTEGER,
                  times_captured INTEGER DEFAULT 0,
                  PRIMARY KEY(region_id, round_id)
                )
                """);

            // Region objectives table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS region_objectives (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  objective_type TEXT NOT NULL,
                  status TEXT DEFAULT 'ACTIVE',
                  location_x INTEGER,
                  location_y INTEGER,
                  location_z INTEGER,
                  progress REAL DEFAULT 0,
                  completed_by TEXT,
                  created_at INTEGER,
                  completed_at INTEGER
                )
                """);

            // Player region stats table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_region_stats (
                  player_uuid TEXT NOT NULL,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  influence_earned REAL DEFAULT 0,
                  kills INTEGER DEFAULT 0,
                  deaths INTEGER DEFAULT 0,
                  blocks_placed INTEGER DEFAULT 0,
                  blocks_mined INTEGER DEFAULT 0,
                  objectives_completed INTEGER DEFAULT 0,
                  banners_placed INTEGER DEFAULT 0,
                  PRIMARY KEY(player_uuid, region_id, round_id)
                )
                """);

            // Anti-spam tracking table (for kill farming, etc.)
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS kill_tracking (
                  killer_uuid TEXT NOT NULL,
                  victim_uuid TEXT NOT NULL,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  kill_count INTEGER DEFAULT 1,
                  last_kill_at INTEGER,
                  PRIMARY KEY(killer_uuid, victim_uuid, region_id, round_id)
                )
                """);

            // Player action rate limiting
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS action_rate_limit (
                  player_uuid TEXT NOT NULL,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  action_type TEXT NOT NULL,
                  action_count INTEGER DEFAULT 0,
                  window_start INTEGER,
                  PRIMARY KEY(player_uuid, region_id, round_id, action_type)
                )
                """);
        }
    }

    // ==================== REGION STATUS METHODS ====================

    public void initializeRegion(String regionId, int roundId, String ownerTeam, RegionState state) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR IGNORE INTO region_status(region_id, round_id, owner_team, state, owned_since)
            VALUES(?, ?, ?, ?, ?)
            """)) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);
            ps.setString(3, ownerTeam);
            ps.setString(4, state.name());
            ps.setLong(5, ownerTeam != null ? System.currentTimeMillis() : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize region", e);
        }
    }

    public Optional<RegionStatus> getRegionStatus(String regionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM region_status WHERE region_id = ? AND round_id = ?"
        )) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRegionStatus(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get region status", e);
        }
    }

    public List<RegionStatus> getAllRegionStatuses(int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM region_status WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                List<RegionStatus> statuses = new ArrayList<>();
                while (rs.next()) {
                    statuses.add(mapRegionStatus(rs));
                }
                return statuses;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all region statuses", e);
        }
    }

    public List<RegionStatus> getRegionsByOwner(int roundId, String team) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM region_status WHERE round_id = ? AND owner_team = ?"
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            try (ResultSet rs = ps.executeQuery()) {
                List<RegionStatus> statuses = new ArrayList<>();
                while (rs.next()) {
                    statuses.add(mapRegionStatus(rs));
                }
                return statuses;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get regions by owner", e);
        }
    }

    public void updateInfluence(String regionId, int roundId, double redInfluence, double blueInfluence) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE region_status SET red_influence = ?, blue_influence = ?
            WHERE region_id = ? AND round_id = ?
            """)) {
            ps.setDouble(1, redInfluence);
            ps.setDouble(2, blueInfluence);
            ps.setString(3, regionId);
            ps.setInt(4, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update influence", e);
        }
    }

    public void updateRegionState(String regionId, int roundId, RegionState state) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE region_status SET state = ? WHERE region_id = ? AND round_id = ?
            """)) {
            ps.setString(1, state.name());
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update region state", e);
        }
    }

    public void updateRegionOwner(String regionId, int roundId, String owner) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE region_status SET owner_team = ? WHERE region_id = ? AND round_id = ?
            """)) {
            ps.setString(1, owner);
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update region owner", e);
        }
    }

    public void captureRegion(String regionId, int roundId, String newOwner, RegionState newState, Long fortifiedUntil) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE region_status
            SET owner_team = ?, state = ?, red_influence = 0, blue_influence = 0,
                fortified_until = ?, owned_since = ?,
                times_captured = times_captured + 1
            WHERE region_id = ? AND round_id = ?
            """)) {
            ps.setString(1, newOwner);
            ps.setString(2, newState.name());
            ps.setObject(3, fortifiedUntil);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, regionId);
            ps.setInt(6, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to capture region", e);
        }
    }


    // ==================== PLAYER STATS METHODS ====================

    public void addInfluenceEarned(String playerUuid, String regionId, int roundId, double influence) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO player_region_stats(player_uuid, region_id, round_id, influence_earned)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(player_uuid, region_id, round_id) 
            DO UPDATE SET influence_earned = influence_earned + ?
            """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.setDouble(4, influence);
            ps.setDouble(5, influence);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add influence earned", e);
        }
    }

    public void incrementPlayerStat(String playerUuid, String regionId, int roundId, String statColumn) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_region_stats(player_uuid, region_id, round_id, " + statColumn + ") " +
                "VALUES(?, ?, ?, 1) " +
                "ON CONFLICT(player_uuid, region_id, round_id) " +
                "DO UPDATE SET " + statColumn + " = " + statColumn + " + 1"
        )) {
            ps.setString(1, playerUuid);
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to increment player stat", e);
        }
    }

    // ==================== KILL TRACKING METHODS ====================

    public int getKillCount(String killerUuid, String victimUuid, String regionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT kill_count FROM kill_tracking 
            WHERE killer_uuid = ? AND victim_uuid = ? AND region_id = ? AND round_id = ?
            """)) {
            ps.setString(1, killerUuid);
            ps.setString(2, victimUuid);
            ps.setString(3, regionId);
            ps.setInt(4, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("kill_count") : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get kill count", e);
        }
    }

    public void recordKill(String killerUuid, String victimUuid, String regionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO kill_tracking(killer_uuid, victim_uuid, region_id, round_id, kill_count, last_kill_at)
            VALUES(?, ?, ?, ?, 1, ?)
            ON CONFLICT(killer_uuid, victim_uuid, region_id, round_id)
            DO UPDATE SET kill_count = kill_count + 1, last_kill_at = ?
            """)) {
            long now = System.currentTimeMillis();
            ps.setString(1, killerUuid);
            ps.setString(2, victimUuid);
            ps.setString(3, regionId);
            ps.setInt(4, roundId);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record kill", e);
        }
    }

    // ==================== RATE LIMITING METHODS ====================

    public int getActionCount(String playerUuid, String regionId, int roundId, String actionType, long windowMs) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT action_count, window_start FROM action_rate_limit
            WHERE player_uuid = ? AND region_id = ? AND round_id = ? AND action_type = ?
            """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.setString(4, actionType);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0;
                long windowStart = rs.getLong("window_start");
                // Reset if window has passed
                if (System.currentTimeMillis() - windowStart > windowMs) {
                    return 0;
                }
                return rs.getInt("action_count");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get action count", e);
        }
    }

    public void incrementActionCount(String playerUuid, String regionId, int roundId, String actionType, long windowMs) {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO action_rate_limit(player_uuid, region_id, round_id, action_type, action_count, window_start)
            VALUES(?, ?, ?, ?, 1, ?)
            ON CONFLICT(player_uuid, region_id, round_id, action_type)
            DO UPDATE SET 
                action_count = CASE 
                    WHEN ? - window_start > ? THEN 1 
                    ELSE action_count + 1 
                END,
                window_start = CASE 
                    WHEN ? - window_start > ? THEN ? 
                    ELSE window_start 
                END
            """)) {
            ps.setString(1, playerUuid);
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.setString(4, actionType);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setLong(7, windowMs);
            ps.setLong(8, now);
            ps.setLong(9, windowMs);
            ps.setLong(10, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to increment action count", e);
        }
    }

    // ==================== HELPER METHODS ====================

    private RegionStatus mapRegionStatus(ResultSet rs) throws SQLException {
        return new RegionStatus(
                rs.getString("region_id"),
                rs.getInt("round_id"),
                rs.getString("owner_team"),
                RegionState.valueOf(rs.getString("state")),
                rs.getDouble("red_influence"),
                rs.getDouble("blue_influence"),
                rs.getObject("fortified_until") != null ? rs.getLong("fortified_until") : null,
                rs.getObject("owned_since") != null ? rs.getLong("owned_since") : null,
                rs.getInt("times_captured")
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

