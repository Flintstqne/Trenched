package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class RoundDb implements AutoCloseable {

    private final Connection connection;

    public RoundDb(JavaPlugin plugin) {
        try {
            File dir = plugin.getDataFolder();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Failed to create plugin data folder");
            }

            File dbFile = new File(dir, "rounds.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open rounds database", e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA foreign_keys = ON");
        }

        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS rounds (
                  round_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  start_time INTEGER NOT NULL,
                  end_time INTEGER,
                  current_phase INTEGER NOT NULL DEFAULT 1,
                  world_seed INTEGER NOT NULL,
                  world_name TEXT,
                  status TEXT NOT NULL,
                  winning_team TEXT
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS region_names (
                  round_id INTEGER NOT NULL,
                  region_id TEXT NOT NULL,
                  region_name TEXT NOT NULL,
                  PRIMARY KEY(round_id, region_id),
                  FOREIGN KEY(round_id) REFERENCES rounds(round_id) ON DELETE CASCADE
                )
                """);

            // Add world_name column if it doesn't exist (migration for existing databases)
            try {
                st.executeUpdate("ALTER TABLE rounds ADD COLUMN world_name TEXT");
            } catch (SQLException ignored) {
                // Column already exists
            }
        }
    }

    public int createRound(long worldSeed) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO rounds(start_time, current_phase, world_seed, status) VALUES(?, 1, ?, 'PENDING')",
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, worldSeed);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Failed to get round_id");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create round", e);
        }
    }

    public void updateRoundStatus(int roundId, Round.RoundStatus status) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE rounds SET status = ? WHERE round_id = ?"
        )) {
            ps.setString(1, status.name());
            ps.setInt(2, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update round status", e);
        }
    }

    public void updatePhase(int roundId, int newPhase) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE rounds SET current_phase = ? WHERE round_id = ?"
        )) {
            ps.setInt(1, newPhase);
            ps.setInt(2, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update phase", e);
        }
    }

    public void setWorldName(int roundId, String worldName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE rounds SET world_name = ? WHERE round_id = ?"
        )) {
            ps.setString(1, worldName);
            ps.setInt(2, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set world name", e);
        }
    }

    public Optional<String> getWorldName(int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world_name FROM rounds WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("world_name");
                    return name != null ? Optional.of(name) : Optional.empty();
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get world name", e);
        }
    }

    public void completeRound(int roundId, String winningTeam) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE rounds SET status = 'COMPLETED', end_time = ?, winning_team = ? WHERE round_id = ?"
        )) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, winningTeam);
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to complete round", e);
        }
    }

    public Optional<Round> getCurrentRound() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM rounds WHERE status IN ('PENDING', 'ACTIVE') ORDER BY round_id DESC LIMIT 1"
        )) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRound(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get current round", e);
        }
    }

    public Optional<Round> getRound(int roundId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM rounds WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRound(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get round", e);
        }
    }

    public List<Round> getRoundHistory() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM rounds ORDER BY round_id DESC"
        )) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Round> rounds = new ArrayList<>();
                while (rs.next()) {
                    rounds.add(mapRound(rs));
                }
                return rounds;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get round history", e);
        }
    }

    public void saveRegionNames(int roundId, Map<String, String> regionNames) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM region_names WHERE round_id = ?"
            )) {
                delete.setInt(1, roundId);
                delete.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO region_names(round_id, region_id, region_name) VALUES(?, ?, ?)"
            )) {
                for (Map.Entry<String, String> entry : regionNames.entrySet()) {
                    ps.setInt(1, roundId);
                    ps.setString(2, entry.getKey());
                    ps.setString(3, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new RuntimeException("Failed to save region names", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public Map<String, String> loadRegionNames(int roundId) {
        Map<String, String> names = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT region_id, region_name FROM region_names WHERE round_id = ?"
        )) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.put(rs.getString(1), rs.getString(2));
                }
            }
            return names;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load region names", e);
        }
    }

    private Round mapRound(ResultSet rs) throws SQLException {
        return new Round(
                rs.getInt("round_id"),
                rs.getLong("start_time"),
                rs.getLong("end_time"),
                rs.getInt("current_phase"),
                rs.getLong("world_seed"),
                rs.getString("world_name"),
                Round.RoundStatus.valueOf(rs.getString("status")),
                rs.getString("winning_team")
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
