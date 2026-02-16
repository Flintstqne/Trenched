package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class DivisionDb implements AutoCloseable {

    private final Connection connection;

    public DivisionDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "divisions.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open divisions database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS divisions (
                  division_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  round_id INTEGER NOT NULL,
                  team TEXT NOT NULL,
                  division_name TEXT NOT NULL,
                  division_tag TEXT NOT NULL,
                  description TEXT,
                  founder_uuid TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  UNIQUE(round_id, team, division_name),
                  UNIQUE(round_id, team, division_tag)
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_members (
                  player_uuid TEXT NOT NULL,
                  division_id INTEGER NOT NULL,
                  round_id INTEGER NOT NULL,
                  role TEXT DEFAULT 'MEMBER',
                  joined_at INTEGER NOT NULL,
                  PRIMARY KEY(player_uuid, round_id),
                  FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_requests (
                  request_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  player_uuid TEXT NOT NULL,
                  division_id INTEGER NOT NULL,
                  requested_at INTEGER NOT NULL,
                  status TEXT DEFAULT 'PENDING',
                  FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_waypoints (
                  waypoint_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  division_id INTEGER NOT NULL,
                  name TEXT NOT NULL,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  created_by TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_founder_cooldowns (
                  player_uuid TEXT PRIMARY KEY,
                  last_created_at INTEGER NOT NULL
                )
                """);
        }
    }

    public int createDivision(int roundId, String team, String name, String tag, String founderUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO divisions(round_id, team, division_name, division_tag, founder_uuid, created_at) VALUES(?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setString(3, name);
            ps.setString(4, tag.toUpperCase());
            ps.setString(5, founderUuid);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get division_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create division", e);
        }
    }

    public Optional<Division> getDivision(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM divisions WHERE division_id = ?"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapDivision(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get division", e);
        }
    }

    public Optional<Division> getDivisionByName(int roundId, String team, String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM divisions WHERE round_id = ? AND team = ? AND division_name = ?"
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapDivision(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get division by name", e);
        }
    }

    public Optional<Division> getDivisionByTag(int roundId, String team, String tag) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM divisions WHERE round_id = ? AND team = ? AND division_tag = ?"
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            ps.setString(3, tag.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapDivision(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get division by tag", e);
        }
    }

    public List<Division> getDivisionsForTeam(int roundId, String team) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM divisions WHERE round_id = ? AND team = ? ORDER BY created_at ASC"
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            try (ResultSet rs = ps.executeQuery()) {
                List<Division> divisions = new ArrayList<>();
                while (rs.next()) {
                    divisions.add(mapDivision(rs));
                }
                return divisions;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get divisions for team", e);
        }
    }

    public void updateDivisionName(int divisionId, String newName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE divisions SET division_name = ? WHERE division_id = ?"
        )) {
            ps.setString(1, newName);
            ps.setInt(2, divisionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update division name", e);
        }
    }

    public void updateDivisionTag(int divisionId, String newTag) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE divisions SET division_tag = ? WHERE division_id = ?"
        )) {
            ps.setString(1, newTag.toUpperCase());
            ps.setInt(2, divisionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update division tag", e);
        }
    }

    public void updateDivisionDescription(int divisionId, String description) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE divisions SET description = ? WHERE division_id = ?"
        )) {
            ps.setString(1, description);
            ps.setInt(2, divisionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update division description", e);
        }
    }

    public void deleteDivision(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM divisions WHERE division_id = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete division", e);
        }
    }

    public int countDivisionsForTeam(int roundId, String team) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM divisions WHERE round_id = ? AND team = ?"
        )) {
            ps.setInt(1, roundId);
            ps.setString(2, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count divisions", e);
        }
    }

    public void addMember(int divisionId, int roundId, String playerUuid, DivisionRole role) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO division_members(player_uuid, division_id, round_id, role, joined_at) VALUES(?, ?, ?, ?, ?)"
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, divisionId);
            ps.setInt(3, roundId);
            ps.setString(4, role.name());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add member", e);
        }
    }

    public void removeMember(String playerUuid, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_members WHERE player_uuid = ? AND round_id = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove member", e);
        }
    }

    public Optional<DivisionMember> getMembership(String playerUuid, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_members WHERE player_uuid = ? AND round_id = ?"
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapMember(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get membership", e);
        }
    }

    public List<DivisionMember> getMembers(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_members WHERE division_id = ? ORDER BY role DESC, joined_at ASC"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DivisionMember> members = new ArrayList<>();
                while (rs.next()) {
                    members.add(mapMember(rs));
                }
                return members;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get members", e);
        }
    }

    public void updateMemberRole(String playerUuid, int roundId, DivisionRole newRole) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE division_members SET role = ? WHERE player_uuid = ? AND round_id = ?"
        )) {
            ps.setString(1, newRole.name());
            ps.setString(2, playerUuid);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update member role", e);
        }
    }

    public int createJoinRequest(String playerUuid, int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO division_requests(player_uuid, division_id, requested_at, status) VALUES(?, ?, ?, 'PENDING')",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, divisionId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get request_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create join request", e);
        }
    }

    public List<JoinRequest> getPendingRequests(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_requests WHERE division_id = ? AND status = 'PENDING' ORDER BY requested_at ASC"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<JoinRequest> requests = new ArrayList<>();
                while (rs.next()) {
                    requests.add(mapRequest(rs));
                }
                return requests;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get pending requests", e);
        }
    }

    public Optional<JoinRequest> getPendingRequest(String playerUuid, int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_requests WHERE player_uuid = ? AND division_id = ? AND status = 'PENDING'"
        )) {
            ps.setString(1, playerUuid);
            ps.setInt(2, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRequest(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get pending request", e);
        }
    }

    public void updateRequestStatus(int requestId, String status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE division_requests SET status = ? WHERE request_id = ?"
        )) {
            ps.setString(1, status);
            ps.setInt(2, requestId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update request status", e);
        }
    }

    public Optional<Long> getFounderCooldown(String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_created_at FROM division_founder_cooldowns WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getLong("last_created_at"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get founder cooldown", e);
        }
    }

    public void setFounderCooldown(String playerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO division_founder_cooldowns(player_uuid, last_created_at) VALUES(?, ?)"
        )) {
            ps.setString(1, playerUuid);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set founder cooldown", e);
        }
    }

    public int createWaypoint(int divisionId, String name, String world, int x, int y, int z, String createdBy) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO division_waypoints(division_id, name, world, x, y, z, created_by, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setInt(1, divisionId);
            ps.setString(2, name);
            ps.setString(3, world);
            ps.setInt(4, x);
            ps.setInt(5, y);
            ps.setInt(6, z);
            ps.setString(7, createdBy);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get waypoint_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create waypoint", e);
        }
    }

    public List<Waypoint> getWaypoints(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_waypoints WHERE division_id = ? ORDER BY created_at ASC"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Waypoint> waypoints = new ArrayList<>();
                while (rs.next()) {
                    waypoints.add(mapWaypoint(rs));
                }
                return waypoints;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get waypoints", e);
        }
    }

    public Optional<Waypoint> getWaypoint(int divisionId, String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_waypoints WHERE division_id = ? AND name = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapWaypoint(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get waypoint", e);
        }
    }

    public void deleteWaypoint(int waypointId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_waypoints WHERE waypoint_id = ?"
        )) {
            ps.setInt(1, waypointId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete waypoint", e);
        }
    }

    public void deleteWaypointByName(int divisionId, String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_waypoints WHERE division_id = ? AND name = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete waypoint", e);
        }
    }

    private Division mapDivision(ResultSet rs) throws SQLException {
        return new Division(
                rs.getInt("division_id"),
                rs.getInt("round_id"),
                rs.getString("team"),
                rs.getString("division_name"),
                rs.getString("division_tag"),
                rs.getString("description"),
                rs.getString("founder_uuid"),
                rs.getLong("created_at")
        );
    }

    private DivisionMember mapMember(ResultSet rs) throws SQLException {
        return new DivisionMember(
                rs.getString("player_uuid"),
                rs.getInt("division_id"),
                rs.getInt("round_id"),
                DivisionRole.valueOf(rs.getString("role")),
                rs.getLong("joined_at")
        );
    }

    private JoinRequest mapRequest(ResultSet rs) throws SQLException {
        return new JoinRequest(
                rs.getInt("request_id"),
                rs.getString("player_uuid"),
                rs.getInt("division_id"),
                rs.getLong("requested_at"),
                rs.getString("status")
        );
    }

    private Waypoint mapWaypoint(ResultSet rs) throws SQLException {
        return new Waypoint(
                rs.getInt("waypoint_id"),
                rs.getInt("division_id"),
                rs.getString("name"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("created_by"),
                rs.getLong("created_at")
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

