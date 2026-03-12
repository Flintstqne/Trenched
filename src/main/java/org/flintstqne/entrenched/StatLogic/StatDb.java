package org.flintstqne.entrenched.StatLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Database layer for the statistics system.
 * Uses SQLite for persistent storage.
 */
public class StatDb {
    private final JavaPlugin plugin;
    private final Logger logger;
    private Connection connection;
    private boolean initialized = false;
    private volatile boolean shuttingDown = false;

    public StatDb(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        initializeDatabase();
    }

    /**
     * Checks if the database was successfully initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Marks the database as shutting down to suppress further warnings.
     */
    public void markShuttingDown() {
        shuttingDown = true;
    }

    private void initializeDatabase() {
        try {
            // Ensure data folder exists
            if (!plugin.getDataFolder().exists()) {
                boolean created = plugin.getDataFolder().mkdirs();
                logger.info("[Stats] Created data folder: " + created);
            }

            String dbPath = plugin.getDataFolder().getAbsolutePath() + "/stats.db";
            logger.info("[Stats] Connecting to database at: " + dbPath);

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            if (connection == null || connection.isClosed()) {
                logger.severe("[Stats] Failed to establish database connection!");
                return;
            }

            logger.info("[Stats] Database connection established");
            createTables();
            initialized = true;
            logger.info("[Stats] Database initialized successfully");
        } catch (Exception e) {
            logger.severe("[Stats] Failed to initialize database: " + e.getMessage());
            logger.severe("[Stats] Try deleting plugins/Trenched/stats.db and restarting the server");
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Lifetime player stats
            StringBuilder playerStatsCreate = new StringBuilder();
            playerStatsCreate.append("CREATE TABLE IF NOT EXISTS player_stats (");
            playerStatsCreate.append("uuid TEXT PRIMARY KEY,");
            playerStatsCreate.append("last_known_name TEXT NOT NULL");

            for (StatCategory cat : StatCategory.values()) {
                playerStatsCreate.append(",").append(cat.getKey()).append(" REAL DEFAULT 0");
            }
            playerStatsCreate.append(")");

            logger.info("[Stats] Creating player_stats table...");
            stmt.execute(playerStatsCreate.toString());
            logger.info("[Stats] player_stats table created");

            // Per-round player stats
            StringBuilder roundStatsCreate = new StringBuilder();
            roundStatsCreate.append("CREATE TABLE IF NOT EXISTS round_player_stats (");
            roundStatsCreate.append("uuid TEXT NOT NULL,");
            roundStatsCreate.append("round_id INTEGER NOT NULL,");
            roundStatsCreate.append("team TEXT");

            for (StatCategory cat : StatCategory.values()) {
                roundStatsCreate.append(",").append(cat.getKey()).append(" REAL DEFAULT 0");
            }
            roundStatsCreate.append(",PRIMARY KEY (uuid, round_id))");

            logger.info("[Stats] Creating round_player_stats table...");
            stmt.execute(roundStatsCreate.toString());
            logger.info("[Stats] round_player_stats table created");

            // Detailed event log
            logger.info("[Stats] Creating stat_events table...");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS stat_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    round_id INTEGER,
                    category TEXT NOT NULL,
                    delta REAL NOT NULL,
                    timestamp INTEGER NOT NULL,
                    metadata TEXT
                )
            """);
            logger.info("[Stats] stat_events table created");

            // Damage tracking for assists (10 second window)
            logger.info("[Stats] Creating damage_tracking table...");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS damage_tracking (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    victim_uuid TEXT NOT NULL,
                    attacker_uuid TEXT NOT NULL,
                    damage REAL NOT NULL,
                    timestamp INTEGER NOT NULL
                )
            """);
            logger.info("[Stats] damage_tracking table created");

            // Recent kills tracking for revenge kills
            logger.info("[Stats] Creating recent_kills table...");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS recent_kills (
                    killer_uuid TEXT NOT NULL,
                    victim_uuid TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    PRIMARY KEY (killer_uuid, victim_uuid)
                )
            """);
            logger.info("[Stats] recent_kills table created");

            // Round metadata
            logger.info("[Stats] Creating round_metadata table...");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS round_metadata (
                    round_id INTEGER PRIMARY KEY,
                    winner TEXT,
                    start_time INTEGER,
                    end_time INTEGER,
                    mvp_uuid TEXT,
                    mvp_name TEXT,
                    mvp_score REAL
                )
            """);
            logger.info("[Stats] round_metadata table created");

            // Create indexes for common queries
            logger.info("[Stats] Creating indexes...");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stat_events_uuid ON stat_events(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stat_events_round ON stat_events(round_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_damage_tracking_victim ON damage_tracking(victim_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_damage_tracking_time ON damage_tracking(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_round_stats_round ON round_player_stats(round_id)");
            logger.info("[Stats] All indexes created");

            // Verify tables exist
            logger.info("[Stats] Verifying tables...");
            verifyTablesExist(stmt);
        }
    }

    private void verifyTablesExist(Statement stmt) {
        String[] tables = {"player_stats", "round_player_stats", "stat_events", "damage_tracking", "recent_kills", "round_metadata"};
        for (String table : tables) {
            try {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table);
                rs.close();
                logger.info("[Stats] Table verified: " + table);
            } catch (SQLException e) {
                logger.severe("[Stats] Table missing or invalid: " + table + " - " + e.getMessage());
            }
        }
    }

    /**
     * Checks if the database connection is valid and initialized.
     * Returns false if shutting down to prevent further writes.
     */
    private boolean isConnectionValid() {
        if (!initialized || shuttingDown) return false;
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Logs a warning message only if not shutting down.
     */
    private void warnIfNotShuttingDown(String message) {
        if (!shuttingDown) {
            logger.warning(message);
        }
    }

    // === PLAYER STATS OPERATIONS ===

    /**
     * Ensures a player exists in the database.
     * Sets login_streak to 1 for new players.
     */
    public void ensurePlayerExists(UUID uuid, String name) {
        if (!isConnectionValid()) {
            warnIfNotShuttingDown("[Stats] Cannot ensure player exists - no database connection");
            return;
        }
        String sql = """
            INSERT OR IGNORE INTO player_stats (uuid, last_known_name, last_login, login_streak)
            VALUES (?, ?, ?, 1)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            warnIfNotShuttingDown("[Stats] Failed to ensure player exists: " + e.getMessage());
        }
    }

    /**
     * Updates player name and login time.
     */
    public void updatePlayerLogin(UUID uuid, String name) {
        String sql = """
            UPDATE player_stats SET last_known_name = ?, last_login = ? WHERE uuid = ?
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to update player login: " + e.getMessage());
        }
    }

    /**
     * Increments a stat for a player (lifetime).
     */
    public void incrementLifetimeStat(UUID uuid, StatCategory category, double delta) {
        String sql = "UPDATE player_stats SET " + category.getKey() + " = " + category.getKey() + " + ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, delta);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to increment lifetime stat: " + e.getMessage());
        }
    }

    /**
     * Sets a stat for a player (lifetime).
     */
    public void setLifetimeStat(UUID uuid, StatCategory category, double value) {
        String sql = "UPDATE player_stats SET " + category.getKey() + " = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, value);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to set lifetime stat: " + e.getMessage());
        }
    }

    /**
     * Gets a specific lifetime stat for a player.
     */
    public double getLifetimeStat(UUID uuid, StatCategory category) {
        String sql = "SELECT " + category.getKey() + " FROM player_stats WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getDouble(1);
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get lifetime stat: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets all lifetime stats for a player.
     */
    public Optional<PlayerStats> getPlayerStats(UUID uuid) {
        String sql = "SELECT * FROM player_stats WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(parsePlayerStats(rs));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get player stats: " + e.getMessage());
        }
        return Optional.empty();
    }

    private PlayerStats parsePlayerStats(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        String name = rs.getString("last_known_name");
        long lastLogin = rs.getLong("last_login");

        Map<StatCategory, Double> stats = new EnumMap<>(StatCategory.class);
        for (StatCategory cat : StatCategory.values()) {
            stats.put(cat, rs.getDouble(cat.getKey()));
        }

        return new PlayerStats(uuid, name, lastLogin, stats);
    }

    // === ROUND STATS OPERATIONS ===

    /**
     * Ensures a round entry exists for a player.
     */
    public void ensureRoundEntryExists(UUID uuid, int roundId, String team) {
        String sql = """
            INSERT OR IGNORE INTO round_player_stats (uuid, round_id, team)
            VALUES (?, ?, ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, roundId);
            ps.setString(3, team);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to ensure round entry exists: " + e.getMessage());
        }
    }

    /**
     * Increments a stat for a player (round-specific).
     */
    public void incrementRoundStat(UUID uuid, int roundId, StatCategory category, double delta) {
        String sql = "UPDATE round_player_stats SET " + category.getKey() + " = " + category.getKey() + " + ? WHERE uuid = ? AND round_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, delta);
            ps.setString(2, uuid.toString());
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to increment round stat: " + e.getMessage());
        }
    }

    /**
     * Sets a stat for a player (round-specific).
     */
    public void setRoundStat(UUID uuid, int roundId, StatCategory category, double value) {
        String sql = "UPDATE round_player_stats SET " + category.getKey() + " = ? WHERE uuid = ? AND round_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, value);
            ps.setString(2, uuid.toString());
            ps.setInt(3, roundId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to set round stat: " + e.getMessage());
        }
    }

    /**
     * Gets round stats for a player.
     */
    public Optional<PlayerStats> getRoundStats(UUID uuid, int roundId) {
        String sql = "SELECT rps.*, ps.last_known_name FROM round_player_stats rps " +
                     "JOIN player_stats ps ON rps.uuid = ps.uuid " +
                     "WHERE rps.uuid = ? AND rps.round_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, roundId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(parseRoundStats(rs));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get round stats: " + e.getMessage());
        }
        return Optional.empty();
    }

    private PlayerStats parseRoundStats(ResultSet rs) throws SQLException {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        String name = rs.getString("last_known_name");

        Map<StatCategory, Double> stats = new EnumMap<>(StatCategory.class);
        for (StatCategory cat : StatCategory.values()) {
            try {
                stats.put(cat, rs.getDouble(cat.getKey()));
            } catch (SQLException e) {
                stats.put(cat, 0.0);
            }
        }

        return new PlayerStats(uuid, name, 0, stats);
    }

    // === LEADERBOARD OPERATIONS ===

    /**
     * Gets the leaderboard for a specific stat category.
     */
    public List<LeaderboardEntry> getLeaderboard(StatCategory category, int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT uuid, last_known_name, " + category.getKey() +
                     " FROM player_stats ORDER BY " + category.getKey() + " DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                entries.add(new LeaderboardEntry(
                    rank++,
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("last_known_name"),
                    rs.getDouble(category.getKey())
                ));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get leaderboard: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Gets the round leaderboard for a specific stat category.
     */
    public List<LeaderboardEntry> getRoundLeaderboard(int roundId, StatCategory category, int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT rps.uuid, ps.last_known_name, rps." + category.getKey() +
                     " FROM round_player_stats rps " +
                     "JOIN player_stats ps ON rps.uuid = ps.uuid " +
                     "WHERE rps.round_id = ? " +
                     "ORDER BY rps." + category.getKey() + " DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                entries.add(new LeaderboardEntry(
                    rank++,
                    UUID.fromString(rs.getString("uuid")),
                    rs.getString("last_known_name"),
                    rs.getDouble(category.getKey())
                ));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get round leaderboard: " + e.getMessage());
        }
        return entries;
    }

    // === DAMAGE TRACKING (FOR ASSISTS) ===

    /**
     * Records damage dealt by an attacker to a victim.
     */
    public void recordDamage(UUID attacker, UUID victim, double damage) {
        String sql = "INSERT INTO damage_tracking (victim_uuid, attacker_uuid, damage, timestamp) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, victim.toString());
            ps.setString(2, attacker.toString());
            ps.setDouble(3, damage);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to record damage: " + e.getMessage());
        }
    }

    /**
     * Gets attackers who dealt damage to a victim within the time window.
     */
    public List<UUID> getRecentAttackers(UUID victim, long windowMs) {
        List<UUID> attackers = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - windowMs;
        String sql = "SELECT DISTINCT attacker_uuid FROM damage_tracking WHERE victim_uuid = ? AND timestamp > ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, victim.toString());
            ps.setLong(2, cutoff);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                attackers.add(UUID.fromString(rs.getString("attacker_uuid")));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get recent attackers: " + e.getMessage());
        }
        return attackers;
    }

    /**
     * Cleans up old damage tracking entries.
     */
    public void cleanupDamageTracking(long maxAgeMs) {
        if (!initialized) return; // Skip if database not properly initialized

        long cutoff = System.currentTimeMillis() - maxAgeMs;
        String sql = "DELETE FROM damage_tracking WHERE timestamp < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to cleanup damage tracking: " + e.getMessage());
        }
    }

    // === RECENT KILLS (FOR REVENGE) ===

    /**
     * Records a kill for revenge tracking.
     */
    public void recordKill(UUID killer, UUID victim) {
        String sql = "INSERT OR REPLACE INTO recent_kills (killer_uuid, victim_uuid, timestamp) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to record kill: " + e.getMessage());
        }
    }

    /**
     * Checks if this kill is a revenge kill (victim killed the killer recently).
     */
    public boolean isRevengeKill(UUID killer, UUID victim, long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        String sql = "SELECT 1 FROM recent_kills WHERE killer_uuid = ? AND victim_uuid = ? AND timestamp > ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, victim.toString()); // victim was the previous killer
            ps.setString(2, killer.toString()); // killer was previously killed
            ps.setLong(3, cutoff);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to check revenge kill: " + e.getMessage());
        }
        return false;
    }

    // === EVENT LOG ===

    /**
     * Logs a stat event for detailed tracking.
     */
    public void logEvent(UUID uuid, int roundId, StatCategory category, double delta, String metadata) {
        String sql = "INSERT INTO stat_events (uuid, round_id, category, delta, timestamp, metadata) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, roundId);
            ps.setString(3, category.getKey());
            ps.setDouble(4, delta);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, metadata);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to log event: " + e.getMessage());
        }
    }

    // === ROUND METADATA ===

    /**
     * Saves round metadata.
     */
    public void saveRoundMetadata(int roundId, String winner, long startTime, long endTime,
                                   UUID mvpUuid, String mvpName, double mvpScore) {
        String sql = """
            INSERT OR REPLACE INTO round_metadata (round_id, winner, start_time, end_time, mvp_uuid, mvp_name, mvp_score)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, winner);
            ps.setLong(3, startTime);
            ps.setLong(4, endTime);
            ps.setString(5, mvpUuid != null ? mvpUuid.toString() : null);
            ps.setString(6, mvpName);
            ps.setDouble(7, mvpScore);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to save round metadata: " + e.getMessage());
        }
    }

    // === TEAM STATS ===

    /**
     * Gets all players and their stats for a team in a round.
     */
    public List<PlayerStats> getTeamRoundStats(int roundId, String team) {
        List<PlayerStats> stats = new ArrayList<>();
        String sql = "SELECT rps.*, ps.last_known_name FROM round_player_stats rps " +
                     "JOIN player_stats ps ON rps.uuid = ps.uuid " +
                     "WHERE rps.round_id = ? AND rps.team = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ps.setString(2, team.toLowerCase());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                stats.add(parseRoundStats(rs));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get team round stats: " + e.getMessage());
        }
        return stats;
    }

    // === ADMIN OPERATIONS ===

    /**
     * Gets all round IDs that have stats.
     */
    public List<Integer> getAllRoundIds() {
        List<Integer> roundIds = new ArrayList<>();
        String sql = "SELECT DISTINCT round_id FROM round_player_stats ORDER BY round_id DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                roundIds.add(rs.getInt("round_id"));
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to get all round IDs: " + e.getMessage());
        }
        return roundIds;
    }

    /**
     * Purges all stats for a specific round.
     */
    public void purgeRound(int roundId) {
        try {
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM round_player_stats WHERE round_id = ?")) {
                ps.setInt(1, roundId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM stat_events WHERE round_id = ?")) {
                ps.setInt(1, roundId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM round_metadata WHERE round_id = ?")) {
                ps.setInt(1, roundId);
                ps.executeUpdate();
            }

            connection.commit();
            logger.info("[Stats] Purged all stats for round " + roundId);
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                logger.severe("[Stats] Failed to rollback: " + ex.getMessage());
            }
            logger.warning("[Stats] Failed to purge round: " + e.getMessage());
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                logger.warning("[Stats] Failed to reset auto-commit: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if first blood has been claimed this round.
     */
    public boolean isFirstBloodClaimed(int roundId) {
        String sql = "SELECT 1 FROM round_player_stats WHERE round_id = ? AND first_blood > 0 LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roundId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to check first blood: " + e.getMessage());
        }
        return false;
    }

    /**
     * Closes the database connection.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("[Stats] Database connection closed");
            }
        } catch (SQLException e) {
            logger.warning("[Stats] Failed to close database: " + e.getMessage());
        }
    }
}

