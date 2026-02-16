// src/main/java/org/flintstqne/terrainGen/TeamLogic/TeamDb.java
package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class TeamDb implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(TeamDb.class.getName());

    // Region constants - reference these from TeamWorldSeeder
    public static final int REGION_SIZE_BLOCKS = 512;

    private final Connection connection;

    public TeamDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder: " + dir.getAbsolutePath());
            }

            File dbFile = new File(dir, "teams.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open SQLite database", e);
        }
    }

    private void migrate() throws SQLException {
        // Enable FK constraints for this connection (SQLite needs this per-connection).
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");
        }

        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS teams (
                  id TEXT PRIMARY KEY,
                  display_name TEXT NOT NULL,
                  color INTEGER NOT NULL
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS memberships (
                  player_uuid TEXT PRIMARY KEY,
                  team_id TEXT NOT NULL,
                  FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS team_spawns (
                  team_id TEXT PRIMARY KEY,
                  world TEXT NOT NULL,
                  x REAL NOT NULL,
                  y REAL NOT NULL,
                  z REAL NOT NULL,
                  yaw REAL NOT NULL,
                  pitch REAL NOT NULL,
                  FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS region_claims (
                  rx INTEGER NOT NULL,
                  rz INTEGER NOT NULL,
                  team_id TEXT NOT NULL,
                  PRIMARY KEY(rx, rz),
                  FOREIGN KEY(team_id) REFERENCES teams(id) ON DELETE CASCADE
                )
                """);
        }
    }

    // --- Teams ---

    public Map<String, Team> loadTeams() {
        Map<String, Team> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, display_name, color FROM teams"
        );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                String display = rs.getString(2);
                int color = rs.getInt(3);
                out.put(id, new Team(id, display, color));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load teams", e);
        }
    }

    public boolean upsertTeam(Team team) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO teams(id, display_name, color) VALUES(?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              display_name = excluded.display_name,
              color = excluded.color
            """)) {
            ps.setString(1, team.id());
            ps.setString(2, team.displayName());
            ps.setInt(3, team.color());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert team", e);
        }
    }

    public boolean deleteTeam(String teamId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM teams WHERE id = ?"
        )) {
            ps.setString(1, teamId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete team", e);
        }
    }

    // --- Memberships ---

    public Map<UUID, String> loadMemberships() {
        Map<UUID, String> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, team_id FROM memberships"
        );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID player = UUID.fromString(rs.getString(1));
                String teamId = rs.getString(2);
                out.put(player, teamId);
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load memberships", e);
        }
    }

    public void setMembership(UUID playerId, String teamId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO memberships(player_uuid, team_id) VALUES(?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
              team_id = excluded.team_id
            """)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, teamId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set membership", e);
        }
    }

    public void clearMembership(UUID playerId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM memberships WHERE player_uuid = ?"
        )) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear membership", e);
        }
    }

    public int countMembers(String teamId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM memberships WHERE team_id = ?"
        )) {
            ps.setString(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count members", e);
        }
    }

    // --- Spawns ---

    public Map<String, Location> loadSpawns() {
        Map<String, Location> out = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT team_id, world, x, y, z, yaw, pitch
            FROM team_spawns
            """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String teamId = rs.getString(1);
                String worldName = rs.getString(2);
                double x = rs.getDouble(3);
                double y = rs.getDouble(4);
                double z = rs.getDouble(5);
                float yaw = (float) rs.getDouble(6);
                float pitch = (float) rs.getDouble(7);

                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    LOGGER.warning("Team spawn for '" + teamId +
                            "' references unknown world: '" + worldName +
                            "' (spawn will be unavailable until world loads)");
                    continue;
                }

                out.put(teamId, new Location(world, x, y, z, yaw, pitch));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load spawns", e);
        }
    }

    public void upsertSpawn(String teamId, Location loc) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO team_spawns(team_id, world, x, y, z, yaw, pitch)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(team_id) DO UPDATE SET
              world = excluded.world,
              x = excluded.x,
              y = excluded.y,
              z = excluded.z,
              yaw = excluded.yaw,
              pitch = excluded.pitch
            """)) {
            ps.setString(1, teamId);
            ps.setString(2, Objects.requireNonNull(loc.getWorld(), "world").getName());
            ps.setDouble(3, loc.getX());
            ps.setDouble(4, loc.getY());
            ps.setDouble(5, loc.getZ());
            ps.setDouble(6, loc.getYaw());
            ps.setDouble(7, loc.getPitch());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert spawn", e);
        }
    }

    public void deleteSpawn(String teamId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM team_spawns WHERE team_id = ?"
        )) {
            ps.setString(1, teamId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete spawn", e);
        }
    }

    // --- Region claims ---

    public void claimRegion(int regionX, int regionZ, String teamId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO region_claims(rx, rz, team_id) VALUES(?, ?, ?)
            ON CONFLICT(rx, rz) DO UPDATE SET
              team_id = excluded.team_id
            """)) {
            ps.setInt(1, regionX);
            ps.setInt(2, regionZ);
            ps.setString(3, teamId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to claim region", e);
        }
    }

    public Optional<String> getRegionOwner(int regionX, int regionZ) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT team_id FROM region_claims WHERE rx = ? AND rz = ?"
        )) {
            ps.setInt(1, regionX);
            ps.setInt(2, regionZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.ofNullable(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get region owner", e);
        }
    }

    public void clearAllMemberships() {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM memberships"
        )) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear all memberships", e);
        }
    }


    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
