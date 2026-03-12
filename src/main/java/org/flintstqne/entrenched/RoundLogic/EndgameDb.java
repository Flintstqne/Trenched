package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.Optional;

/**
 * Database layer for endgame state persistence.
 */
public class EndgameDb {

    private final Connection connection;

    public EndgameDb(JavaPlugin plugin) {
        try {
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/entrenched.db"
            );
            initTables();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize endgame database", e);
        }
    }

    private void initTables() {
        String sql = """
            CREATE TABLE IF NOT EXISTS round_endgame_state (
                round_id INTEGER PRIMARY KEY,
                stage TEXT NOT NULL DEFAULT 'NORMAL',
                early_win_team TEXT,
                early_win_started_at INTEGER,
                overtime_region_id TEXT,
                overtime_started_at INTEGER,
                overtime_ends_at INTEGER,
                overtime_hold_team TEXT,
                overtime_hold_started_at INTEGER
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create endgame tables", e);
        }
    }

    /**
     * Gets the endgame state for a round, creating default if not exists.
     */
    public RoundEndgameState getOrCreate(int roundId) {
        String sql = "SELECT * FROM round_endgame_state WHERE round_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapState(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get endgame state", e);
        }

        // Create default state
        RoundEndgameState defaultState = RoundEndgameState.createDefault(roundId);
        save(defaultState);
        return defaultState;
    }

    /**
     * Gets the endgame state for a round if it exists.
     */
    public Optional<RoundEndgameState> get(int roundId) {
        String sql = "SELECT * FROM round_endgame_state WHERE round_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapState(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get endgame state", e);
        }

        return Optional.empty();
    }

    /**
     * Saves or updates the endgame state.
     */
    public void save(RoundEndgameState state) {
        String sql = """
            INSERT OR REPLACE INTO round_endgame_state (
                round_id, stage, early_win_team, early_win_started_at,
                overtime_region_id, overtime_started_at, overtime_ends_at,
                overtime_hold_team, overtime_hold_started_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, state.roundId());
            ps.setString(2, state.stage().name());
            ps.setString(3, state.earlyWinTeam());
            setNullableLong(ps, 4, state.earlyWinStartedAt());
            ps.setString(5, state.overtimeRegionId());
            setNullableLong(ps, 6, state.overtimeStartedAt());
            setNullableLong(ps, 7, state.overtimeEndsAt());
            ps.setString(8, state.overtimeHoldTeam());
            setNullableLong(ps, 9, state.overtimeHoldStartedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save endgame state", e);
        }
    }

    /**
     * Deletes the endgame state for a round.
     */
    public void delete(int roundId) {
        String sql = "DELETE FROM round_endgame_state WHERE round_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete endgame state", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // Ignore close errors
        }
    }

    private RoundEndgameState mapState(ResultSet rs) throws SQLException {
        return new RoundEndgameState(
                rs.getInt("round_id"),
                EndgameStage.valueOf(rs.getString("stage")),
                rs.getString("early_win_team"),
                getNullableLong(rs, "early_win_started_at"),
                rs.getString("overtime_region_id"),
                getNullableLong(rs, "overtime_started_at"),
                getNullableLong(rs, "overtime_ends_at"),
                rs.getString("overtime_hold_team"),
                getNullableLong(rs, "overtime_hold_started_at")
        );
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }
}

