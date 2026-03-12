package org.flintstqne.entrenched.StatLogic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for stat tracking operations.
 */
public interface StatService {

    // === RECORDING EVENTS ===

    /**
     * Records a stat event (queued for async batch writing).
     */
    void recordEvent(StatEvent event);

    /**
     * Increments a stat for a player.
     */
    void incrementStat(UUID playerUuid, String playerName, StatCategory category, double delta, int roundId);

    /**
     * Decrements a stat for a player (minimum 0).
     */
    void decrementStat(UUID playerUuid, StatCategory category, double delta, int roundId);

    /**
     * Sets a stat for a player (for non-counter stats).
     */
    void setStat(UUID playerUuid, String playerName, StatCategory category, double value, int roundId);

    /**
     * Records damage dealt (for assist tracking).
     */
    void recordDamage(UUID attacker, UUID victim, double damage);

    /**
     * Records a kill with all associated stat updates.
     */
    void recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                    int roundId, boolean isCommanderKill);

    /**
     * Records a death with all associated stat updates.
     */
    void recordDeath(UUID victimUuid, String victimName, UUID killerUuid, int roundId);

    // === QUERYING STATS ===

    /**
     * Gets lifetime stats for a player.
     */
    Optional<PlayerStats> getPlayerStats(UUID playerUuid);

    /**
     * Gets round-specific stats for a player.
     */
    Optional<PlayerStats> getPlayerRoundStats(UUID playerUuid, int roundId);

    /**
     * Gets the leaderboard for a stat category.
     */
    List<LeaderboardEntry> getLeaderboard(StatCategory category, int limit);

    /**
     * Gets the round leaderboard for a stat category.
     */
    List<LeaderboardEntry> getRoundLeaderboard(int roundId, StatCategory category, int limit);

    /**
     * Gets aggregate team stats for a round.
     */
    TeamStats getTeamStats(String team, int roundId);

    /**
     * Gets a summary of a completed round.
     */
    Optional<RoundSummary> getRoundSummary(int roundId);

    // === LOGIN & STREAKS ===

    /**
     * Handles player login - updates name, checks login streak.
     */
    void handlePlayerLogin(UUID playerUuid, String playerName, int roundId, String team);

    /**
     * Checks and updates login streak (resets after 36 hours offline).
     */
    void checkLoginStreak(UUID playerUuid);

    // === ROUND MANAGEMENT ===

    /**
     * Calculates and returns the MVP for a round.
     */
    UUID calculateMVP(int roundId);

    /**
     * Saves round metadata when a round ends.
     */
    void saveRoundEnd(int roundId, String winner, long startTime, long endTime);

    /**
     * Gets all round IDs that have stats.
     */
    List<Integer> getAllRoundIds();

    /**
     * Purges all stats for a specific round.
     */
    void purgeRound(int roundId);

    // === LIFECYCLE ===

    /**
     * Forces a flush of pending stat writes.
     */
    void flush();

    /**
     * Starts the async write task.
     */
    void start();

    /**
     * Stops the service and flushes pending writes.
     */
    void stop();
}

