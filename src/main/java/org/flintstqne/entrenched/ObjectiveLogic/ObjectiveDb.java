package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

/**
 * Database access layer for Objective system.
 */
public final class ObjectiveDb implements AutoCloseable {

    private final Connection connection;

    public ObjectiveDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "objectives.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open objectives database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");

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
                  created_at INTEGER NOT NULL,
                  completed_at INTEGER
                )
                """);

            // Index for faster queries
            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_objectives_region_round 
                ON region_objectives(region_id, round_id, status)
                """);

            // Player objective cooldowns
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS objective_cooldowns (
                  player_uuid TEXT NOT NULL,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  objective_type TEXT NOT NULL,
                  cooldown_until INTEGER NOT NULL,
                  PRIMARY KEY(player_uuid, region_id, round_id, objective_type)
                )
                """);

            // Player objective completions (for tracking)
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS objective_completions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  objective_id INTEGER NOT NULL,
                  player_uuid TEXT NOT NULL,
                  team TEXT NOT NULL,
                  completed_at INTEGER NOT NULL
                )
                """);

            // Hold ground tracking (players currently holding ground)
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS hold_ground_progress (
                  objective_id INTEGER NOT NULL,
                  player_uuid TEXT NOT NULL,
                  team TEXT NOT NULL,
                  started_at INTEGER NOT NULL,
                  last_tick INTEGER NOT NULL,
                  total_seconds INTEGER DEFAULT 0,
                  PRIMARY KEY(objective_id, player_uuid)
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS registered_buildings (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  objective_id INTEGER NOT NULL UNIQUE,
                  region_id TEXT NOT NULL,
                  round_id INTEGER NOT NULL,
                  building_type TEXT NOT NULL,
                  team TEXT NOT NULL,
                  status TEXT NOT NULL,
                  anchor_x INTEGER NOT NULL,
                  anchor_y INTEGER NOT NULL,
                  anchor_z INTEGER NOT NULL,
                  min_x INTEGER NOT NULL,
                  min_y INTEGER NOT NULL,
                  min_z INTEGER NOT NULL,
                  max_x INTEGER NOT NULL,
                  max_y INTEGER NOT NULL,
                  max_z INTEGER NOT NULL,
                  total_score REAL NOT NULL,
                  structure_score REAL NOT NULL,
                  interior_score REAL NOT NULL,
                  access_score REAL NOT NULL,
                  signature_score REAL NOT NULL,
                  context_score REAL NOT NULL,
                  variant TEXT,
                  registered_at INTEGER NOT NULL,
                  last_validated_at INTEGER NOT NULL,
                  invalidated_at INTEGER,
                  FOREIGN KEY(objective_id) REFERENCES region_objectives(id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_registered_buildings_round_status
                ON registered_buildings(round_id, status)
                """);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // Ignore close errors
        }
    }

    // ==================== OBJECTIVE CRUD ====================

    /**
     * Creates a new objective and returns its ID.
     */
    public int createObjective(String regionId, int roundId, ObjectiveType type,
                                Integer locationX, Integer locationY, Integer locationZ) {
        String sql = """
            INSERT INTO region_objectives (region_id, round_id, objective_type, status, 
                                           location_x, location_y, location_z, progress, created_at)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 0, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);
            ps.setString(3, type.name());
            if (locationX != null) ps.setInt(4, locationX); else ps.setNull(4, Types.INTEGER);
            if (locationY != null) ps.setInt(5, locationY); else ps.setNull(5, Types.INTEGER);
            if (locationZ != null) ps.setInt(6, locationZ); else ps.setNull(6, Types.INTEGER);
            ps.setLong(7, System.currentTimeMillis());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create objective", e);
        }

        return -1;
    }

    /**
     * Gets an objective by ID.
     */
    public Optional<RegionObjective> getObjective(int id) {
        String sql = "SELECT * FROM region_objectives WHERE id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapObjective(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get objective", e);
        }

        return Optional.empty();
    }

    /**
     * Gets all active objectives in a region for a round.
     */
    public List<RegionObjective> getActiveObjectives(String regionId, int roundId) {
        String sql = """
            SELECT * FROM region_objectives 
            WHERE region_id = ? AND round_id = ? AND status = 'ACTIVE'
            ORDER BY created_at ASC
            """;

        List<RegionObjective> objectives = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objectives.add(mapObjective(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get active objectives", e);
        }

        return objectives;
    }

    /**
     * Gets all active objectives of a specific category in a region.
     */
    public List<RegionObjective> getActiveObjectivesByCategory(String regionId, int roundId,
                                                                ObjectiveCategory category) {
        return getActiveObjectives(regionId, roundId).stream()
                .filter(o -> o.type().getCategory() == category)
                .toList();
    }

    /**
     * Counts active objectives in a region.
     */
    public int countActiveObjectives(String regionId, int roundId) {
        String sql = """
            SELECT COUNT(*) FROM region_objectives 
            WHERE region_id = ? AND round_id = ? AND status = 'ACTIVE'
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, regionId);
            ps.setInt(2, roundId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count objectives", e);
        }

        return 0;
    }

    /**
     * Updates an objective's progress.
     */
    public void updateProgress(int objectiveId, double progress) {
        String sql = "UPDATE region_objectives SET progress = ? WHERE id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, Math.min(1.0, Math.max(0.0, progress)));
            ps.setInt(2, objectiveId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update objective progress", e);
        }
    }

    /**
     * Marks an objective as completed.
     */
    public void completeObjective(int objectiveId, String completedByTeam) {
        String sql = """
            UPDATE region_objectives 
            SET status = 'COMPLETED', progress = 1.0, completed_by = ?, completed_at = ?
            WHERE id = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, completedByTeam);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, objectiveId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to complete objective", e);
        }
    }

    /**
     * Marks an objective as expired.
     */
    public void expireObjective(int objectiveId) {
        String sql = """
            UPDATE region_objectives 
            SET status = 'EXPIRED', completed_at = ?
            WHERE id = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, objectiveId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire objective", e);
        }
    }

    /**
     * Expires all active objectives in a region.
     */
    public void expireAllInRegion(String regionId, int roundId) {
        String sql = """
            UPDATE region_objectives 
            SET status = 'EXPIRED', completed_at = ?
            WHERE region_id = ? AND round_id = ? AND status = 'ACTIVE'
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire objectives in region", e);
        }
    }

    // ==================== COOLDOWNS ====================

    /**
     * Sets a cooldown for a player on an objective type in a region.
     */
    public void setCooldown(UUID playerUuid, String regionId, int roundId,
                            ObjectiveType type, long cooldownUntil) {
        String sql = """
            INSERT OR REPLACE INTO objective_cooldowns 
            (player_uuid, region_id, round_id, objective_type, cooldown_until)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.setString(4, type.name());
            ps.setLong(5, cooldownUntil);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set cooldown", e);
        }
    }

    /**
     * Gets the cooldown end time for a player/type/region combination.
     * Returns 0 if no cooldown exists.
     */
    public long getCooldownUntil(UUID playerUuid, String regionId, int roundId, ObjectiveType type) {
        String sql = """
            SELECT cooldown_until FROM objective_cooldowns 
            WHERE player_uuid = ? AND region_id = ? AND round_id = ? AND objective_type = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, regionId);
            ps.setInt(3, roundId);
            ps.setString(4, type.name());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get cooldown", e);
        }

        return 0;
    }

    // ==================== COMPLETION TRACKING ====================

    /**
     * Records an objective completion for a player.
     */
    public void recordCompletion(int objectiveId, UUID playerUuid, String team) {
        String sql = """
            INSERT INTO objective_completions (objective_id, player_uuid, team, completed_at)
            VALUES (?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            ps.setString(2, playerUuid.toString());
            ps.setString(3, team);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record completion", e);
        }
    }

    /**
     * Gets all objectives completed by a player in a round.
     */
    public List<RegionObjective> getCompletedByPlayer(UUID playerUuid, int roundId) {
        String sql = """
            SELECT o.* FROM region_objectives o
            INNER JOIN objective_completions c ON o.id = c.objective_id
            WHERE c.player_uuid = ? AND o.round_id = ?
            ORDER BY c.completed_at DESC
            """;

        List<RegionObjective> objectives = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, roundId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    objectives.add(mapObjective(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get completed objectives", e);
        }

        return objectives;
    }

    // ==================== HOLD GROUND TRACKING ====================

    /**
     * Updates hold ground progress for a player.
     */
    public void updateHoldGroundProgress(int objectiveId, UUID playerUuid, String team, int totalSeconds) {
        String sql = """
            INSERT OR REPLACE INTO hold_ground_progress 
            (objective_id, player_uuid, team, started_at, last_tick, total_seconds)
            VALUES (?, ?, ?, COALESCE(
                (SELECT started_at FROM hold_ground_progress WHERE objective_id = ? AND player_uuid = ?), 
                ?
            ), ?, ?)
            """;

        long now = System.currentTimeMillis();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            ps.setString(2, playerUuid.toString());
            ps.setString(3, team);
            ps.setInt(4, objectiveId);
            ps.setString(5, playerUuid.toString());
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setInt(8, totalSeconds);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update hold ground progress", e);
        }
    }

    /**
     * Gets the hold ground progress for a player.
     */
    public int getHoldGroundProgress(int objectiveId, UUID playerUuid) {
        String sql = """
            SELECT total_seconds FROM hold_ground_progress 
            WHERE objective_id = ? AND player_uuid = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            ps.setString(2, playerUuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get hold ground progress", e);
        }

        return 0;
    }

    /**
     * Clears hold ground progress for an objective.
     */
    public void clearHoldGroundProgress(int objectiveId) {
        String sql = "DELETE FROM hold_ground_progress WHERE objective_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear hold ground progress", e);
        }
    }

    /**
     * Clears hold ground progress for a specific player in an objective.
     */
    public void clearHoldGroundProgressForPlayer(int objectiveId, UUID playerUuid) {
        String sql = "DELETE FROM hold_ground_progress WHERE objective_id = ? AND player_uuid = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear hold ground progress for player", e);
        }
    }

    // ==================== BUILDING REGISTRY ====================

    public void upsertRegisteredBuilding(RegionObjective objective, BuildingDetectionResult detection,
                                         String team, RegisteredBuildingStatus status, long now) {
        String sql = """
            INSERT INTO registered_buildings (
              objective_id, region_id, round_id, building_type, team, status,
              anchor_x, anchor_y, anchor_z,
              min_x, min_y, min_z, max_x, max_y, max_z,
              total_score, structure_score, interior_score, access_score, signature_score, context_score,
              variant, registered_at, last_validated_at, invalidated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(objective_id) DO UPDATE SET
              region_id = excluded.region_id,
              round_id = excluded.round_id,
              building_type = excluded.building_type,
              team = excluded.team,
              status = excluded.status,
              anchor_x = excluded.anchor_x,
              anchor_y = excluded.anchor_y,
              anchor_z = excluded.anchor_z,
              min_x = excluded.min_x,
              min_y = excluded.min_y,
              min_z = excluded.min_z,
              max_x = excluded.max_x,
              max_y = excluded.max_y,
              max_z = excluded.max_z,
              total_score = excluded.total_score,
              structure_score = excluded.structure_score,
              interior_score = excluded.interior_score,
              access_score = excluded.access_score,
              signature_score = excluded.signature_score,
              context_score = excluded.context_score,
              variant = excluded.variant,
              last_validated_at = excluded.last_validated_at,
              invalidated_at = excluded.invalidated_at
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objective.id());
            ps.setString(2, objective.regionId());
            ps.setInt(3, objective.roundId());
            ps.setString(4, detection.type().name());
            ps.setString(5, team);
            ps.setString(6, status.name());
            ps.setInt(7, objective.locationX());
            ps.setInt(8, objective.locationY());
            ps.setInt(9, objective.locationZ());
            ps.setInt(10, detection.minX());
            ps.setInt(11, detection.minY());
            ps.setInt(12, detection.minZ());
            ps.setInt(13, detection.maxX());
            ps.setInt(14, detection.maxY());
            ps.setInt(15, detection.maxZ());
            ps.setDouble(16, detection.totalScore());
            ps.setDouble(17, detection.structureScore());
            ps.setDouble(18, detection.interiorScore());
            ps.setDouble(19, detection.accessScore());
            ps.setDouble(20, detection.signatureScore());
            ps.setDouble(21, detection.contextScore());
            ps.setString(22, detection.variant());
            ps.setLong(23, now);
            ps.setLong(24, now);
            ps.setNull(25, Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert registered building", e);
        }
    }

    public Optional<RegisteredBuilding> getRegisteredBuilding(int objectiveId) {
        String sql = "SELECT * FROM registered_buildings WHERE objective_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, objectiveId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRegisteredBuilding(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get registered building", e);
        }

        return Optional.empty();
    }

    public List<RegisteredBuilding> getRegisteredBuildingsByStatus(int roundId, RegisteredBuildingStatus status) {
        String sql = """
            SELECT * FROM registered_buildings
            WHERE round_id = ? AND status = ?
            ORDER BY last_validated_at ASC
            """;

        List<RegisteredBuilding> buildings = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    buildings.add(mapRegisteredBuilding(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get registered buildings by status", e);
        }
        return buildings;
    }

    public void invalidateRegisteredBuilding(int objectiveId, long now) {
        String sql = """
            UPDATE registered_buildings
            SET status = ?, invalidated_at = ?
            WHERE objective_id = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, RegisteredBuildingStatus.INVALID.name());
            ps.setLong(2, now);
            ps.setInt(3, objectiveId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invalidate registered building", e);
        }
    }

    public void invalidateRegisteredBuildingsInRegion(String regionId, int roundId, long now) {
        String sql = """
            UPDATE registered_buildings
            SET status = ?, invalidated_at = ?
            WHERE region_id = ? AND round_id = ? AND status <> ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, RegisteredBuildingStatus.INVALID.name());
            ps.setLong(2, now);
            ps.setString(3, regionId);
            ps.setInt(4, roundId);
            ps.setString(5, RegisteredBuildingStatus.INVALID.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invalidate buildings in region", e);
        }
    }

    // ==================== HELPERS ====================

    private RegionObjective mapObjective(ResultSet rs) throws SQLException {
        Integer locX = rs.getObject("location_x") != null ? rs.getInt("location_x") : null;
        Integer locY = rs.getObject("location_y") != null ? rs.getInt("location_y") : null;
        Integer locZ = rs.getObject("location_z") != null ? rs.getInt("location_z") : null;
        Long completedAt = rs.getObject("completed_at") != null ? rs.getLong("completed_at") : null;

        return new RegionObjective(
                rs.getInt("id"),
                rs.getString("region_id"),
                rs.getInt("round_id"),
                ObjectiveType.valueOf(rs.getString("objective_type")),
                ObjectiveStatus.valueOf(rs.getString("status")),
                locX, locY, locZ,
                rs.getDouble("progress"),
                rs.getString("completed_by"),
                rs.getLong("created_at"),
                completedAt
        );
    }

    private RegisteredBuilding mapRegisteredBuilding(ResultSet rs) throws SQLException {
        Long invalidatedAt = rs.getObject("invalidated_at") != null ? rs.getLong("invalidated_at") : null;

        return new RegisteredBuilding(
                rs.getInt("id"),
                rs.getInt("objective_id"),
                rs.getString("region_id"),
                rs.getInt("round_id"),
                BuildingType.valueOf(rs.getString("building_type")),
                rs.getString("team"),
                RegisteredBuildingStatus.valueOf(rs.getString("status")),
                rs.getInt("anchor_x"),
                rs.getInt("anchor_y"),
                rs.getInt("anchor_z"),
                rs.getInt("min_x"),
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z"),
                rs.getDouble("total_score"),
                rs.getDouble("structure_score"),
                rs.getDouble("interior_score"),
                rs.getDouble("access_score"),
                rs.getDouble("signature_score"),
                rs.getDouble("context_score"),
                rs.getString("variant"),
                rs.getLong("registered_at"),
                rs.getLong("last_validated_at"),
                invalidatedAt
        );
    }
}

