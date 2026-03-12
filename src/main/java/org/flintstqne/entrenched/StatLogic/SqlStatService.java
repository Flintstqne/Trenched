package org.flintstqne.entrenched.StatLogic;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * SQL-backed implementation of StatService with async batched writes.
 */
public class SqlStatService implements StatService {

    private static final long FLUSH_INTERVAL_TICKS = 200L; // 10 seconds
    private static final long ASSIST_WINDOW_MS = 10_000L; // 10 seconds for assists
    private static final long REVENGE_WINDOW_MS = 60_000L; // 1 minute for revenge
    private static final long LOGIN_STREAK_RESET_MS = 36 * 60 * 60 * 1000L; // 36 hours
    private static final long DAMAGE_CLEANUP_MS = 60_000L; // Clean damage tracking every minute

    // MVP Formula weights
    private static final double MVP_KILLS_WEIGHT = 10.0;
    private static final double MVP_OBJECTIVES_WEIGHT = 25.0;
    private static final double MVP_CAPTURES_WEIGHT = 50.0;
    private static final double MVP_IP_WEIGHT = 0.1;

    private final JavaPlugin plugin;
    private final StatDb db;
    private final RoundService roundService;
    private final ConfigManager config;
    private final Logger logger;

    private final ConcurrentLinkedQueue<StatEvent> writeQueue = new ConcurrentLinkedQueue<>();
    private BukkitTask flushTask;
    private BukkitTask cleanupTask;

    // Track current kill streaks in memory (reset on death)
    private final Map<UUID, Integer> currentKillStreaks = new HashMap<>();

    public SqlStatService(JavaPlugin plugin, StatDb db, RoundService roundService, ConfigManager config) {
        this.plugin = plugin;
        this.db = db;
        this.roundService = roundService;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    @Override
    public void start() {
        // Start async flush task
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 
                FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);

        // Start cleanup task for damage tracking
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, 
                () -> db.cleanupDamageTracking(DAMAGE_CLEANUP_MS), 
                1200L, 1200L); // Every minute

        logger.info("[Stats] Service started with 10-second flush interval");
    }

    @Override
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        flush(); // Final flush
        logger.info("[Stats] Service stopped");
    }

    @Override
    public void flush() {
        if (writeQueue.isEmpty()) return;

        List<StatEvent> batch = new ArrayList<>();
        StatEvent event;
        while ((event = writeQueue.poll()) != null) {
            batch.add(event);
        }

        if (batch.isEmpty()) return;

        // Process batch
        for (StatEvent e : batch) {
            try {
                // Ensure player exists
                db.ensurePlayerExists(e.playerUuid(), e.playerName());

                // Update lifetime stats
                if (e.isSetOperation()) {
                    db.setLifetimeStat(e.playerUuid(), e.category(), e.delta());
                } else {
                    db.incrementLifetimeStat(e.playerUuid(), e.category(), e.delta());
                }

                // Update round stats if applicable
                if (e.hasRound()) {
                    db.ensureRoundEntryExists(e.playerUuid(), e.roundId(), null);
                    if (e.isSetOperation()) {
                        db.setRoundStat(e.playerUuid(), e.roundId(), e.category(), e.delta());
                    } else {
                        db.incrementRoundStat(e.playerUuid(), e.roundId(), e.category(), e.delta());
                    }
                }

                // Log event for detailed tracking
                db.logEvent(e.playerUuid(), e.roundId(), e.category(), e.delta(), e.metadata());

            } catch (Exception ex) {
                logger.warning("[Stats] Failed to process event: " + ex.getMessage());
            }
        }

        logger.fine("[Stats] Flushed " + batch.size() + " stat events");
    }

    @Override
    public void recordEvent(StatEvent event) {
        writeQueue.offer(event);
    }

    @Override
    public void incrementStat(UUID playerUuid, String playerName, StatCategory category, double delta, int roundId) {
        recordEvent(StatEvent.increment(playerUuid, playerName, category, delta, roundId));
    }

    @Override
    public void decrementStat(UUID playerUuid, StatCategory category, double delta, int roundId) {
        // Create a decrement event (negative delta)
        recordEvent(StatEvent.increment(playerUuid, null, category, -delta, roundId));
    }

    @Override
    public void setStat(UUID playerUuid, String playerName, StatCategory category, double value, int roundId) {
        recordEvent(StatEvent.set(playerUuid, playerName, category, value, roundId));
    }

    @Override
    public void recordDamage(UUID attacker, UUID victim, double damage) {
        // Run async to not block main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            db.recordDamage(attacker, victim, damage);
        });
    }

    @Override
    public void recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                           int roundId, boolean isCommanderKill) {
        // Increment kills
        incrementStat(killerUuid, killerName, StatCategory.KILLS, 1, roundId);

        // Check for commander kill
        if (isCommanderKill) {
            incrementStat(killerUuid, killerName, StatCategory.COMMANDER_KILLS, 1, roundId);
        }

        // Check for revenge kill
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (db.isRevengeKill(killerUuid, victimUuid, REVENGE_WINDOW_MS)) {
                incrementStat(killerUuid, killerName, StatCategory.REVENGE_KILLS, 1, roundId);
            }
            
            // Record this kill for future revenge tracking
            db.recordKill(killerUuid, victimUuid);
        });

        // Update kill streak
        int newStreak = currentKillStreaks.getOrDefault(killerUuid, 0) + 1;
        currentKillStreaks.put(killerUuid, newStreak);
        
        setStat(killerUuid, killerName, StatCategory.KILL_STREAK_CURRENT, newStreak, roundId);
        
        // Check if best streak
        double currentBest = db.getLifetimeStat(killerUuid, StatCategory.KILL_STREAK_BEST);
        if (newStreak > currentBest) {
            setStat(killerUuid, killerName, StatCategory.KILL_STREAK_BEST, newStreak, roundId);
        }

        // Check for first blood
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!db.isFirstBloodClaimed(roundId)) {
                incrementStat(killerUuid, killerName, StatCategory.FIRST_BLOOD, 1, roundId);
            }
        });

        // Award assists to recent attackers
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<UUID> assisters = db.getRecentAttackers(victimUuid, ASSIST_WINDOW_MS);
            for (UUID assisterId : assisters) {
                if (!assisterId.equals(killerUuid)) {
                    // Get assister name from DB
                    Optional<PlayerStats> assisterStats = db.getPlayerStats(assisterId);
                    String assisterName = assisterStats.map(PlayerStats::getLastKnownName).orElse("Unknown");
                    incrementStat(assisterId, assisterName, StatCategory.ASSISTS, 1, roundId);
                }
            }
        });
    }

    @Override
    public void recordDeath(UUID victimUuid, String victimName, UUID killerUuid, int roundId) {
        // Increment deaths
        incrementStat(victimUuid, victimName, StatCategory.DEATHS, 1, roundId);

        // Reset kill streak
        currentKillStreaks.put(victimUuid, 0);
        setStat(victimUuid, victimName, StatCategory.KILL_STREAK_CURRENT, 0, roundId);
    }

    @Override
    public Optional<PlayerStats> getPlayerStats(UUID playerUuid) {
        return db.getPlayerStats(playerUuid);
    }

    @Override
    public Optional<PlayerStats> getPlayerRoundStats(UUID playerUuid, int roundId) {
        return db.getRoundStats(playerUuid, roundId);
    }

    @Override
    public List<LeaderboardEntry> getLeaderboard(StatCategory category, int limit) {
        return db.getLeaderboard(category, limit);
    }

    @Override
    public List<LeaderboardEntry> getRoundLeaderboard(int roundId, StatCategory category, int limit) {
        return db.getRoundLeaderboard(roundId, category, limit);
    }

    @Override
    public TeamStats getTeamStats(String team, int roundId) {
        List<PlayerStats> playerStats = db.getTeamRoundStats(roundId, team);
        
        int playerCount = playerStats.size();
        Map<StatCategory, Double> totals = new EnumMap<>(StatCategory.class);
        Map<StatCategory, Double> averages = new EnumMap<>(StatCategory.class);
        
        // Calculate totals
        for (StatCategory cat : StatCategory.values()) {
            double total = 0;
            for (PlayerStats ps : playerStats) {
                total += ps.getStat(cat);
            }
            totals.put(cat, total);
            averages.put(cat, playerCount > 0 ? total / playerCount : 0);
        }

        // Find MVP
        UUID mvpUuid = null;
        String mvpName = null;
        double mvpScore = 0;
        
        for (PlayerStats ps : playerStats) {
            double score = ps.getMVPScore();
            if (score > mvpScore) {
                mvpScore = score;
                mvpUuid = ps.getUuid();
                mvpName = ps.getLastKnownName();
            }
        }

        return new TeamStats(team, roundId, playerCount, totals, averages, mvpUuid, mvpName, mvpScore);
    }

    @Override
    public Optional<RoundSummary> getRoundSummary(int roundId) {
        TeamStats redStats = getTeamStats("red", roundId);
        TeamStats blueStats = getTeamStats("blue", roundId);

        // If no players on either team, the round has no stats (possibly purged)
        if (redStats.playerCount() == 0 && blueStats.playerCount() == 0) {
            return Optional.empty();
        }

        // Find overall MVP
        UUID mvpUuid = null;
        String mvpName = null;
        double mvpScore = 0;

        if (redStats.mvpScore() > mvpScore) {
            mvpUuid = redStats.mvpUuid();
            mvpName = redStats.mvpName();
            mvpScore = redStats.mvpScore();
        }
        if (blueStats.mvpScore() > mvpScore) {
            mvpUuid = blueStats.mvpUuid();
            mvpName = blueStats.mvpName();
            mvpScore = blueStats.mvpScore();
        }

        // Get round metadata for winner and times
        // For now, construct summary from available data
        return Optional.of(new RoundSummary(
                roundId,
                null, // winner would come from round metadata
                0, // start time
                System.currentTimeMillis(), // end time
                redStats.playerCount(),
                blueStats.playerCount(),
                redStats.getTotal(StatCategory.KILLS),
                blueStats.getTotal(StatCategory.KILLS),
                redStats.getTotal(StatCategory.OBJECTIVES_COMPLETED),
                blueStats.getTotal(StatCategory.OBJECTIVES_COMPLETED),
                redStats.getTotal(StatCategory.REGIONS_CAPTURED),
                blueStats.getTotal(StatCategory.REGIONS_CAPTURED),
                mvpUuid,
                mvpName,
                mvpScore
        ));
    }

    @Override
    public void handlePlayerLogin(UUID playerUuid, String playerName, int roundId, String team) {
        // Ensure player exists and update name/login time
        db.ensurePlayerExists(playerUuid, playerName);
        db.updatePlayerLogin(playerUuid, playerName);

        // Ensure round entry exists
        if (roundId > 0) {
            db.ensureRoundEntryExists(playerUuid, roundId, team);
        }

        // Check login streak
        checkLoginStreak(playerUuid);

        // Increment rounds played if this is a new round for the player
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<PlayerStats> roundStats = db.getRoundStats(playerUuid, roundId);
            if (roundStats.isEmpty() || roundStats.get().getStat(StatCategory.ROUNDS_PLAYED) == 0) {
                incrementStat(playerUuid, playerName, StatCategory.ROUNDS_PLAYED, 1, roundId);
            }
        });

        logger.fine("[Stats] Player login handled: " + playerName);
    }

    @Override
    public void checkLoginStreak(UUID playerUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<PlayerStats> statsOpt = db.getPlayerStats(playerUuid);
            if (statsOpt.isEmpty()) return;

            PlayerStats stats = statsOpt.get();
            long lastLogin = stats.getLastLogin();
            long now = System.currentTimeMillis();
            long timeSinceLogin = now - lastLogin;

            double currentStreak = stats.getStat(StatCategory.LOGIN_STREAK);
            double bestStreak = stats.getStat(StatCategory.LOGIN_STREAK_BEST);

            if (timeSinceLogin > LOGIN_STREAK_RESET_MS) {
                // Reset streak - been offline more than 36 hours
                db.setLifetimeStat(playerUuid, StatCategory.LOGIN_STREAK, 1);
                logger.fine("[Stats] Login streak reset for " + stats.getLastKnownName());
            } else if (timeSinceLogin > 12 * 60 * 60 * 1000L) {
                // Increment streak - been offline more than 12 hours (new day)
                double newStreak = currentStreak + 1;
                db.setLifetimeStat(playerUuid, StatCategory.LOGIN_STREAK, newStreak);
                
                if (newStreak > bestStreak) {
                    db.setLifetimeStat(playerUuid, StatCategory.LOGIN_STREAK_BEST, newStreak);
                }
                logger.fine("[Stats] Login streak incremented to " + newStreak + " for " + stats.getLastKnownName());
            }
            // Else: logged in within 12 hours, don't change streak
        });
    }

    @Override
    public UUID calculateMVP(int roundId) {
        List<LeaderboardEntry> killLeaders = db.getRoundLeaderboard(roundId, StatCategory.KILLS, 100);
        
        UUID mvpUuid = null;
        double mvpScore = 0;

        for (LeaderboardEntry entry : killLeaders) {
            Optional<PlayerStats> statsOpt = db.getRoundStats(entry.uuid(), roundId);
            if (statsOpt.isEmpty()) continue;

            PlayerStats stats = statsOpt.get();
            double score = stats.getMVPScore();
            
            if (score > mvpScore) {
                mvpScore = score;
                mvpUuid = entry.uuid();
            }
        }

        return mvpUuid;
    }

    @Override
    public void saveRoundEnd(int roundId, String winner, long startTime, long endTime) {
        UUID mvpUuid = calculateMVP(roundId);
        String mvpName = null;
        double mvpScore = 0;

        if (mvpUuid != null) {
            Optional<PlayerStats> mvpStats = db.getPlayerStats(mvpUuid);
            if (mvpStats.isPresent()) {
                mvpName = mvpStats.get().getLastKnownName();
                Optional<PlayerStats> roundStats = db.getRoundStats(mvpUuid, roundId);
                if (roundStats.isPresent()) {
                    mvpScore = roundStats.get().getMVPScore();
                    // Award MVP stat
                    incrementStat(mvpUuid, mvpName, StatCategory.ROUNDS_MVP, 1, roundId);
                }
            }
        }

        // Award rounds won to winning team
        if (winner != null && !winner.equalsIgnoreCase("draw")) {
            List<PlayerStats> winningTeam = db.getTeamRoundStats(roundId, winner);
            for (PlayerStats ps : winningTeam) {
                incrementStat(ps.getUuid(), ps.getLastKnownName(), StatCategory.ROUNDS_WON, 1, roundId);
            }
        }

        db.saveRoundMetadata(roundId, winner, startTime, endTime, mvpUuid, mvpName, mvpScore);
        logger.info("[Stats] Round " + roundId + " end saved. Winner: " + winner + ", MVP: " + mvpName);
    }

    @Override
    public List<Integer> getAllRoundIds() {
        return db.getAllRoundIds();
    }

    @Override
    public void purgeRound(int roundId) {
        db.purgeRound(roundId);
    }

    /**
     * Gets the current round ID from RoundService.
     */
    public int getCurrentRoundId() {
        return roundService.getCurrentRound()
                .map(Round::roundId)
                .orElse(-1);
    }
}

