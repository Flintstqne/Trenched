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

            // Division Depot tables
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_depot_locations (
                  location_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  division_id INTEGER NOT NULL,
                  round_id INTEGER NOT NULL,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  placed_by TEXT NOT NULL,
                  placed_at INTEGER NOT NULL,
                  region_id TEXT NOT NULL,
                  UNIQUE(world, x, y, z),
                  FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_depot_storage (
                  storage_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  division_id INTEGER NOT NULL,
                  round_id INTEGER NOT NULL,
                  slot INTEGER NOT NULL,
                  item_data BLOB,
                  UNIQUE(division_id, round_id, slot),
                  FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS division_depot_raids (
                  raid_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  depot_location_id INTEGER,
                  victim_division_id INTEGER NOT NULL,
                  raider_uuid TEXT NOT NULL,
                  raider_division_id INTEGER,
                  items_dropped INTEGER NOT NULL,
                  raided_at INTEGER NOT NULL
                )
                """);

            // Index for faster depot lookups by region
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_depot_locations_region 
                ON division_depot_locations(region_id)
                """);

            // Index for faster depot lookups by division
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_depot_locations_division 
                ON division_depot_locations(division_id)
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

    // ==================== DEPOT METHODS ====================

    /**
     * Creates a depot location record.
     */
    public int createDepotLocation(int divisionId, int roundId, String world, int x, int y, int z,
                                    String placedBy, String regionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO division_depot_locations(division_id, round_id, world, x, y, z, placed_by, placed_at, region_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setInt(1, divisionId);
            ps.setInt(2, roundId);
            ps.setString(3, world);
            ps.setInt(4, x);
            ps.setInt(5, y);
            ps.setInt(6, z);
            ps.setString(7, placedBy);
            ps.setLong(8, System.currentTimeMillis());
            ps.setString(9, regionId);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get location_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create depot location", e);
        }
    }

    /**
     * Gets a depot at specific coordinates.
     */
    public Optional<DepotLocation> getDepotAt(String world, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_depot_locations WHERE world = ? AND x = ? AND y = ? AND z = ?"
        )) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapDepotLocation(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get depot at location", e);
        }
    }

    /**
     * Gets all depots for a division.
     */
    public List<DepotLocation> getDepotsForDivision(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_depot_locations WHERE division_id = ? ORDER BY placed_at ASC"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DepotLocation> depots = new ArrayList<>();
                while (rs.next()) {
                    depots.add(mapDepotLocation(rs));
                }
                return depots;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get depots for division", e);
        }
    }

    /**
     * Gets all depots in a region.
     */
    public List<DepotLocation> getDepotsInRegion(String regionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM division_depot_locations WHERE region_id = ? AND round_id = ? ORDER BY placed_at ASC"
        )) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DepotLocation> depots = new ArrayList<>();
                while (rs.next()) {
                    depots.add(mapDepotLocation(rs));
                }
                return depots;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get depots in region", e);
        }
    }

    /**
     * Gets all depots for a team in the current round.
     */
    public List<DepotLocation> getDepotsForTeam(String team, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT dl.* FROM division_depot_locations dl
                JOIN divisions d ON dl.division_id = d.division_id
                WHERE d.team = ? AND dl.round_id = ?
                ORDER BY dl.placed_at ASC
                """
        )) {
            ps.setString(1, team);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DepotLocation> depots = new ArrayList<>();
                while (rs.next()) {
                    depots.add(mapDepotLocation(rs));
                }
                return depots;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get depots for team", e);
        }
    }

    /**
     * Counts depots for a division.
     */
    public int countDepotsForDivision(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM division_depot_locations WHERE division_id = ?"
        )) {
            ps.setInt(1, divisionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count depots", e);
        }
    }

    /**
     * Deletes a depot location.
     */
    public void deleteDepotLocation(int locationId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_locations WHERE location_id = ?"
        )) {
            ps.setInt(1, locationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete depot location", e);
        }
    }

    /**
     * Deletes a depot by coordinates.
     */
    public void deleteDepotAt(String world, int x, int y, int z) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_locations WHERE world = ? AND x = ? AND y = ? AND z = ?"
        )) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete depot at location", e);
        }
    }

    /**
     * Deletes all depots for a division.
     */
    public void deleteDepotsForDivision(int divisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_locations WHERE division_id = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete depots for division", e);
        }
    }

    /**
     * Deletes all depot locations for a round.
     */
    public void deleteDepotLocationsForRound(int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_locations WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete depot locations for round", e);
        }
    }

    // ==================== DEPOT STORAGE METHODS ====================

    /**
     * Saves an item to depot storage.
     */
    public void saveDepotStorageSlot(int divisionId, int roundId, int slot, byte[] itemData) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO division_depot_storage(division_id, round_id, slot, item_data) VALUES(?, ?, ?, ?)"
        )) {
            ps.setInt(1, divisionId);
            ps.setInt(2, roundId);
            ps.setInt(3, slot);
            ps.setBytes(4, itemData);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save depot storage slot", e);
        }
    }

    /**
     * Gets all storage slots for a division.
     */
    public Map<Integer, byte[]> getDepotStorage(int divisionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot, item_data FROM division_depot_storage WHERE division_id = ? AND round_id = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.setInt(2, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Integer, byte[]> storage = new HashMap<>();
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    byte[] data = rs.getBytes("item_data");
                    if (data != null) {
                        storage.put(slot, data);
                    }
                }
                return storage;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get depot storage", e);
        }
    }

    /**
     * Clears all storage for a division.
     */
    public void clearDepotStorage(int divisionId, int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_storage WHERE division_id = ? AND round_id = ?"
        )) {
            ps.setInt(1, divisionId);
            ps.setInt(2, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear depot storage", e);
        }
    }

    /**
     * Deletes all depot storage for a round.
     */
    public void deleteDepotStorageForRound(int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM division_depot_storage WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete depot storage for round", e);
        }
    }

    // ==================== DEPOT RAID METHODS ====================

    /**
     * Records a depot raid.
     */
    public int recordDepotRaid(Integer depotLocationId, int victimDivisionId, String raiderUuid,
                                Integer raiderDivisionId, int itemsDropped) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO division_depot_raids(depot_location_id, victim_division_id, raider_uuid, raider_division_id, items_dropped, raided_at) VALUES(?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
        )) {
            if (depotLocationId != null) {
                ps.setInt(1, depotLocationId);
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setInt(2, victimDivisionId);
            ps.setString(3, raiderUuid);
            if (raiderDivisionId != null) {
                ps.setInt(4, raiderDivisionId);
            } else {
                ps.setNull(4, java.sql.Types.INTEGER);
            }
            ps.setInt(5, itemsDropped);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get raid_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record depot raid", e);
        }
    }

    /**
     * Gets the last raid time on a division's depots.
     */
    public Optional<Long> getLastRaidOnDivision(int victimDivisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(raided_at) as last_raid FROM division_depot_raids WHERE victim_division_id = ?"
        )) {
            ps.setInt(1, victimDivisionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastRaid = rs.getLong("last_raid");
                    if (!rs.wasNull()) {
                        return Optional.of(lastRaid);
                    }
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get last raid time", e);
        }
    }

    /**
     * Gets total items lost by a division to raids.
     */
    public int getTotalItemsLostToRaids(int victimDivisionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT SUM(items_dropped) as total FROM division_depot_raids WHERE victim_division_id = ?"
        )) {
            ps.setInt(1, victimDivisionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("total") : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get total items lost", e);
        }
    }

    /**
     * Gets total raids by a player.
     */
    public int getRaidCountByPlayer(String raiderUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM division_depot_raids WHERE raider_uuid = ?"
        )) {
            ps.setString(1, raiderUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get raid count", e);
        }
    }

    private DepotLocation mapDepotLocation(ResultSet rs) throws SQLException {
        return new DepotLocation(
                rs.getInt("location_id"),
                rs.getInt("division_id"),
                rs.getInt("round_id"),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                java.util.UUID.fromString(rs.getString("placed_by")),
                rs.getLong("placed_at"),
                rs.getString("region_id")
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

