package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Database access layer for the merit system.
 */
public class MeritDb {

    private final Connection connection;
    private final Logger logger;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public MeritDb(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
        try {
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/merits.db"
            );
            createTables();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to merit database", e);
        }
    }

    private void createTables() {
        try (Statement stmt = connection.createStatement()) {
            // Player merit tracking
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_merits (
                    uuid TEXT PRIMARY KEY,
                    token_balance INTEGER DEFAULT 0,
                    tokens_earned_today INTEGER DEFAULT 0,
                    last_token_date TEXT,
                    received_merits INTEGER DEFAULT 0,
                    received_today INTEGER DEFAULT 0,
                    last_received_date TEXT,
                    merits_given_today INTEGER DEFAULT 0,
                    last_given_date TEXT,
                    lifetime_tokens_earned INTEGER DEFAULT 0,
                    lifetime_merits_given INTEGER DEFAULT 0,
                    lifetime_merits_received INTEGER DEFAULT 0,
                    lifetime_kills INTEGER DEFAULT 0,
                    lifetime_captures INTEGER DEFAULT 0,
                    lifetime_road_blocks INTEGER DEFAULT 0,
                    rounds_completed INTEGER DEFAULT 0,
                    playtime_minutes INTEGER DEFAULT 0,
                    login_streak INTEGER DEFAULT 0,
                    last_login_date TEXT,
                    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
            """);

            // Merit transaction log
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS merit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    transaction_type TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    reason TEXT,
                    other_player TEXT,
                    round_id INTEGER,
                    timestamp INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
            """);

            // Batched progress tracking (for kills per 5, blocks per 100, etc.)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS merit_progress (
                    uuid TEXT NOT NULL,
                    source TEXT NOT NULL,
                    progress INTEGER DEFAULT 0,
                    last_updated INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    PRIMARY KEY (uuid, source)
                )
            """);

            // Kill tracking for same-player cooldown
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS merit_kill_cooldowns (
                    killer_uuid TEXT NOT NULL,
                    victim_uuid TEXT NOT NULL,
                    last_kill INTEGER NOT NULL,
                    PRIMARY KEY (killer_uuid, victim_uuid)
                )
            """);

            // Merit giving cooldowns
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS merit_cooldowns (
                    giver_uuid TEXT NOT NULL,
                    receiver_uuid TEXT NOT NULL,
                    times_given_today INTEGER DEFAULT 0,
                    times_given_this_week INTEGER DEFAULT 0,
                    last_given INTEGER,
                    last_reset_date TEXT,
                    PRIMARY KEY (giver_uuid, receiver_uuid)
                )
            """);

            // Player interaction tracking (for anti-farming)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_interactions (
                    player1_uuid TEXT NOT NULL,
                    player2_uuid TEXT NOT NULL,
                    region_id TEXT,
                    interaction_type TEXT,
                    timestamp INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                )
            """);

            // Player achievements
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_achievements (
                    uuid TEXT NOT NULL,
                    achievement TEXT NOT NULL,
                    unlocked_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
                    PRIMARY KEY (uuid, achievement)
                )
            """);

            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_merit_log_uuid ON merit_log(uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_merit_log_timestamp ON merit_log(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_players ON player_interactions(player1_uuid, player2_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_achievements_uuid ON player_achievements(uuid)");

            logger.info("[MeritDb] Database tables created/verified");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create merit tables", e);
        }
    }

    // ==================== PLAYER DATA ====================

    public Optional<PlayerMeritData> getPlayerData(UUID uuid) {
        String today = LocalDate.now().format(DATE_FORMAT);

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM player_merits WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                // Reset daily counters if date changed
                String lastTokenDate = rs.getString("last_token_date");
                String lastReceivedDate = rs.getString("last_received_date");
                String lastGivenDate = rs.getString("last_given_date");

                int tokensEarnedToday = today.equals(lastTokenDate) ? rs.getInt("tokens_earned_today") : 0;
                int receivedToday = today.equals(lastReceivedDate) ? rs.getInt("received_today") : 0;
                int meritsGivenToday = today.equals(lastGivenDate) ? rs.getInt("merits_given_today") : 0;

                return Optional.of(new PlayerMeritData(
                        uuid,
                        rs.getInt("token_balance"),
                        tokensEarnedToday,
                        rs.getInt("received_merits"),
                        receivedToday,
                        meritsGivenToday,
                        rs.getInt("lifetime_tokens_earned"),
                        rs.getInt("lifetime_merits_given"),
                        rs.getInt("lifetime_merits_received"),
                        rs.getInt("lifetime_kills"),
                        rs.getInt("lifetime_captures"),
                        rs.getInt("lifetime_road_blocks"),
                        rs.getInt("rounds_completed"),
                        rs.getInt("playtime_minutes"),
                        rs.getInt("login_streak"),
                        rs.getString("last_login_date"),
                        rs.getLong("created_at")
                ));
            }
            return Optional.empty();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to get player data: " + e.getMessage());
            return Optional.empty();
        }
    }

    public PlayerMeritData getOrCreatePlayerData(UUID uuid) {
        return getPlayerData(uuid).orElseGet(() -> {
            createPlayerData(uuid);
            return PlayerMeritData.createNew(uuid);
        });
    }

    public void createPlayerData(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR IGNORE INTO player_merits (uuid) VALUES (?)
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to create player data: " + e.getMessage());
        }
    }

    // ==================== TOKEN OPERATIONS ====================

    /**
     * Awards tokens to a player.
     */
    public void addTokens(UUID uuid, int amount, MeritTokenSource source, String reason, Integer roundId) {
        String today = LocalDate.now().format(DATE_FORMAT);

        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET
                token_balance = token_balance + ?,
                tokens_earned_today = CASE WHEN last_token_date = ? THEN tokens_earned_today + ? ELSE ? END,
                last_token_date = ?,
                lifetime_tokens_earned = lifetime_tokens_earned + ?
            WHERE uuid = ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, today);
            ps.setInt(3, amount);
            ps.setInt(4, amount);
            ps.setString(5, today);
            ps.setInt(6, amount);
            ps.setString(7, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to add tokens: " + e.getMessage());
        }

        // Log the transaction
        logTransaction(uuid, "EARN_TOKEN", amount, source.getCategory(), reason, null, roundId);
    }

    /**
     * Removes tokens from a player (for giving to others).
     * Returns true if successful, false if insufficient balance.
     */
    public boolean removeTokens(UUID uuid, int amount) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET token_balance = token_balance - ?
            WHERE uuid = ? AND token_balance >= ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.setInt(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to remove tokens: " + e.getMessage());
            return false;
        }
    }

    // ==================== RECEIVED MERITS ====================

    /**
     * Adds received merits to a player (given by another player).
     */
    public void addReceivedMerits(UUID receiver, UUID giver, int amount, String reason, Integer roundId) {
        String today = LocalDate.now().format(DATE_FORMAT);

        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET
                received_merits = received_merits + ?,
                received_today = CASE WHEN last_received_date = ? THEN received_today + ? ELSE ? END,
                last_received_date = ?,
                lifetime_merits_received = lifetime_merits_received + ?
            WHERE uuid = ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, today);
            ps.setInt(3, amount);
            ps.setInt(4, amount);
            ps.setString(5, today);
            ps.setInt(6, amount);
            ps.setString(7, receiver.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to add received merits: " + e.getMessage());
        }

        // Log receiving
        logTransaction(receiver, "RECEIVE_MERIT", amount, "peer", reason, giver.toString(), roundId);
    }

    /**
     * Records that a player gave merits.
     */
    public void recordMeritGiven(UUID giver, UUID receiver, int amount, String reason, Integer roundId) {
        String today = LocalDate.now().format(DATE_FORMAT);

        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET
                merits_given_today = CASE WHEN last_given_date = ? THEN merits_given_today + ? ELSE ? END,
                last_given_date = ?,
                lifetime_merits_given = lifetime_merits_given + ?
            WHERE uuid = ?
        """)) {
            ps.setString(1, today);
            ps.setInt(2, amount);
            ps.setInt(3, amount);
            ps.setString(4, today);
            ps.setInt(5, amount);
            ps.setString(6, giver.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to record merit given: " + e.getMessage());
        }

        // Log giving
        logTransaction(giver, "GIVE_MERIT", amount, "peer", reason, receiver.toString(), roundId);
    }

    /**
     * Sets a player's received merits to a specific value.
     */
    public void setReceivedMerits(UUID uuid, int amount) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET received_merits = ? WHERE uuid = ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to set received merits: " + e.getMessage());
        }
    }

    /**
     * Sets a player's token balance to a specific value.
     */
    public void setTokenBalance(UUID uuid, int amount) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET token_balance = ? WHERE uuid = ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to set token balance: " + e.getMessage());
        }
    }

    /**
     * Completely resets a player's merit data.
     */
    public void resetPlayerData(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET
                token_balance = 0,
                tokens_earned_today = 0,
                received_merits = 0,
                received_today = 0,
                merits_given_today = 0,
                lifetime_tokens_earned = 0,
                lifetime_merits_given = 0,
                lifetime_merits_received = 0,
                lifetime_kills = 0,
                lifetime_captures = 0,
                lifetime_road_blocks = 0,
                rounds_completed = 0,
                playtime_minutes = 0,
                login_streak = 0
            WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to reset player data: " + e.getMessage());
        }

        // Also clear achievements
        try (PreparedStatement ps = connection.prepareStatement("""
            DELETE FROM player_achievements WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to clear achievements: " + e.getMessage());
        }

        // Clear progress
        try (PreparedStatement ps = connection.prepareStatement("""
            DELETE FROM merit_progress WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to clear progress: " + e.getMessage());
        }

        // Log the reset
        logTransaction(uuid, "ADMIN_RESET", 0, "admin", "Merit data reset", null, null);
    }

    // ==================== PROGRESS TRACKING (BATCHED) ====================

    /**
     * Adds progress toward a batched token (e.g., kills toward "1 per 5").
     * Returns the number of tokens earned (0 if threshold not reached).
     */
    public int addProgress(UUID uuid, MeritTokenSource source, int amount) {
        int threshold = source.getThreshold();
        if (threshold <= 1) {
            return amount; // Not batched, return full amount
        }

        try {
            // Get current progress
            int currentProgress = 0;
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT progress FROM merit_progress WHERE uuid = ? AND source = ?
            """)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, source.name());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    currentProgress = rs.getInt("progress");
                }
            }

            // Add new progress
            int newProgress = currentProgress + amount;
            int tokensEarned = newProgress / threshold;
            int remainingProgress = newProgress % threshold;

            // Update progress
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO merit_progress (uuid, source, progress, last_updated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, source) DO UPDATE SET
                    progress = ?,
                    last_updated = ?
            """)) {
                long now = System.currentTimeMillis();
                ps.setString(1, uuid.toString());
                ps.setString(2, source.name());
                ps.setInt(3, remainingProgress);
                ps.setLong(4, now);
                ps.setInt(5, remainingProgress);
                ps.setLong(6, now);
                ps.executeUpdate();
            }

            return tokensEarned;
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to add progress: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets current progress toward a batched token.
     */
    public int getProgress(UUID uuid, MeritTokenSource source) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT progress FROM merit_progress WHERE uuid = ? AND source = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, source.name());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt("progress");
            }
            return 0;
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to get progress: " + e.getMessage());
            return 0;
        }
    }

    // ==================== STAT UPDATES ====================

    public void incrementKills(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET lifetime_kills = lifetime_kills + 1 WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to increment kills: " + e.getMessage());
        }
    }

    public void incrementCaptures(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET lifetime_captures = lifetime_captures + 1 WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to increment captures: " + e.getMessage());
        }
    }

    public void addRoadBlocks(UUID uuid, int amount) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET lifetime_road_blocks = lifetime_road_blocks + ? WHERE uuid = ?
        """)) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to add road blocks: " + e.getMessage());
        }
    }

    public void incrementRoundsCompleted(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET rounds_completed = rounds_completed + 1 WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to increment rounds: " + e.getMessage());
        }
    }

    public void addPlaytimeMinutes(UUID uuid, int minutes) {
        try (PreparedStatement ps = connection.prepareStatement("""
            UPDATE player_merits SET playtime_minutes = playtime_minutes + ? WHERE uuid = ?
        """)) {
            ps.setInt(1, minutes);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to add playtime: " + e.getMessage());
        }
    }

    // ==================== LOGIN STREAK ====================

    /**
     * Updates login streak. Returns true if this is first login today.
     */
    public boolean updateLoginStreak(UUID uuid) {
        String today = LocalDate.now().format(DATE_FORMAT);
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FORMAT);

        try {
            // Get last login date
            String lastLogin = null;
            int currentStreak = 0;
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT last_login_date, login_streak FROM player_merits WHERE uuid = ?
            """)) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    lastLogin = rs.getString("last_login_date");
                    currentStreak = rs.getInt("login_streak");
                }
            }

            // Already logged in today
            if (today.equals(lastLogin)) {
                return false;
            }

            // Calculate new streak
            int newStreak;
            if (yesterday.equals(lastLogin)) {
                newStreak = currentStreak + 1;  // Consecutive day
            } else {
                newStreak = 1;  // Streak broken or first login
            }

            // Update
            try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE player_merits SET last_login_date = ?, login_streak = ? WHERE uuid = ?
            """)) {
                ps.setString(1, today);
                ps.setInt(2, newStreak);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }

            return true;  // First login today
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to update login streak: " + e.getMessage());
            return false;
        }
    }

    // ==================== KILL COOLDOWNS ====================

    /**
     * Checks if a kill is on cooldown (same victim within 5 minutes).
     */
    public boolean isKillOnCooldown(UUID killer, UUID victim) {
        long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT last_kill FROM merit_kill_cooldowns
            WHERE killer_uuid = ? AND victim_uuid = ? AND last_kill > ?
        """)) {
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            ps.setLong(3, fiveMinutesAgo);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to check kill cooldown: " + e.getMessage());
            return false;
        }
    }

    /**
     * Records a kill for cooldown tracking.
     */
    public void recordKill(UUID killer, UUID victim) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO merit_kill_cooldowns (killer_uuid, victim_uuid, last_kill)
            VALUES (?, ?, ?)
            ON CONFLICT(killer_uuid, victim_uuid) DO UPDATE SET last_kill = ?
        """)) {
            long now = System.currentTimeMillis();
            ps.setString(1, killer.toString());
            ps.setString(2, victim.toString());
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to record kill: " + e.getMessage());
        }
    }

    // ==================== TRANSACTION LOG ====================

    private void logTransaction(UUID uuid, String type, int amount, String source, String reason, String otherPlayer, Integer roundId) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO merit_log (uuid, transaction_type, amount, source, reason, other_player, round_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.setInt(3, amount);
            ps.setString(4, source);
            ps.setString(5, reason);
            ps.setString(6, otherPlayer);
            if (roundId != null) {
                ps.setInt(7, roundId);
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to log transaction: " + e.getMessage());
        }
    }

    // ==================== LEADERBOARD ====================

    /**
     * Gets top players by received merits.
     */
    public List<PlayerMeritData> getTopByReceivedMerits(int limit) {
        List<PlayerMeritData> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT * FROM player_merits ORDER BY received_merits DESC LIMIT ?
        """)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to get leaderboard: " + e.getMessage());
        }
        return results;
    }

    private PlayerMeritData mapResultSet(ResultSet rs) throws SQLException {
        return new PlayerMeritData(
                UUID.fromString(rs.getString("uuid")),
                rs.getInt("token_balance"),
                rs.getInt("tokens_earned_today"),
                rs.getInt("received_merits"),
                rs.getInt("received_today"),
                rs.getInt("merits_given_today"),
                rs.getInt("lifetime_tokens_earned"),
                rs.getInt("lifetime_merits_given"),
                rs.getInt("lifetime_merits_received"),
                rs.getInt("lifetime_kills"),
                rs.getInt("lifetime_captures"),
                rs.getInt("lifetime_road_blocks"),
                rs.getInt("rounds_completed"),
                rs.getInt("playtime_minutes"),
                rs.getInt("login_streak"),
                rs.getString("last_login_date"),
                rs.getLong("created_at")
        );
    }

    // ==================== PLAYER INTERACTIONS ====================

    /**
     * Records that two players were in the same region.
     */
    public void recordInteraction(UUID player1, UUID player2, String regionId, String type) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO player_interactions (player1_uuid, player2_uuid, region_id, interaction_type)
            VALUES (?, ?, ?, ?)
        """)) {
            ps.setString(1, player1.toString());
            ps.setString(2, player2.toString());
            ps.setString(3, regionId);
            ps.setString(4, type);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to record interaction: " + e.getMessage());
        }
    }

    /**
     * Checks if two players interacted within the last N minutes.
     */
    public boolean hasRecentInteraction(UUID player1, UUID player2, int minutes) {
        long since = System.currentTimeMillis() - (minutes * 60 * 1000L);

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT 1 FROM player_interactions
            WHERE ((player1_uuid = ? AND player2_uuid = ?) OR (player1_uuid = ? AND player2_uuid = ?))
            AND timestamp > ?
            LIMIT 1
        """)) {
            ps.setString(1, player1.toString());
            ps.setString(2, player2.toString());
            ps.setString(3, player2.toString());
            ps.setString(4, player1.toString());
            ps.setLong(5, since);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to check interaction: " + e.getMessage());
            return false;
        }
    }

    // ==================== ACHIEVEMENTS ====================

    /**
     * Gets all achievements unlocked by a player.
     */
    public Set<Achievement> getUnlockedAchievements(UUID uuid) {
        Set<Achievement> achievements = new HashSet<>();

        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT achievement FROM player_achievements WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                try {
                    achievements.add(Achievement.valueOf(rs.getString("achievement")));
                } catch (IllegalArgumentException ignored) {
                    // Achievement no longer exists
                }
            }
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to get achievements: " + e.getMessage());
        }

        return achievements;
    }

    /**
     * Checks if a player has a specific achievement.
     */
    public boolean hasAchievement(UUID uuid, Achievement achievement) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT 1 FROM player_achievements WHERE uuid = ? AND achievement = ?
        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, achievement.name());
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to check achievement: " + e.getMessage());
            return false;
        }
    }

    /**
     * Unlocks an achievement for a player.
     */
    public void unlockAchievement(UUID uuid, Achievement achievement) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT OR IGNORE INTO player_achievements (uuid, achievement) VALUES (?, ?)
        """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, achievement.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to unlock achievement: " + e.getMessage());
        }
    }


    /**
     * Gets the count of achievements unlocked by a player.
     */
    public int getAchievementCount(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("""
            SELECT COUNT(*) FROM player_achievements WHERE uuid = ?
        """)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to count achievements: " + e.getMessage());
        }
        return 0;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.warning("[MeritDb] Failed to close connection: " + e.getMessage());
        }
    }
}

