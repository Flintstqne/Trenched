package org.flintstqne.entrenched.MeritLogic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for the merit system.
 */
public interface MeritService {

    // ==================== PLAYER DATA ====================

    /**
     * Gets a player's merit data.
     */
    Optional<PlayerMeritData> getPlayerData(UUID uuid);

    /**
     * Gets or creates a player's merit data.
     */
    PlayerMeritData getOrCreatePlayerData(UUID uuid);

    /**
     * Gets a player's current rank.
     */
    MeritRank getPlayerRank(UUID uuid);

    /**
     * Gets a player's token balance.
     */
    int getTokenBalance(UUID uuid);

    /**
     * Gets a player's received merits (rank points).
     */
    int getReceivedMerits(UUID uuid);

    // ==================== TOKEN EARNING ====================

    /**
     * Called when a player kills an enemy.
     * Handles batching (1 token per 5 kills in enemy territory, 1 per 10 elsewhere).
     *
     * @param killer The killer's UUID
     * @param victim The victim's UUID
     * @param inEnemyTerritory Whether the kill was in enemy territory
     * @param roundId Current round ID
     * @return Number of tokens earned (0 if batched threshold not met)
     */
    int onPlayerKill(UUID killer, UUID victim, boolean inEnemyTerritory, Integer roundId);

    /**
     * Called when a player participates in capturing a region.
     *
     * @param uuid Player UUID
     * @param isNeutral Whether the region was neutral (vs enemy)
     * @param ipContributed How much IP the player contributed
     * @param isTopContributor Whether player was top 3 contributor
     * @param roundId Current round ID
     * @return Number of tokens earned
     */
    int onRegionCapture(UUID uuid, boolean isNeutral, double ipContributed, boolean isTopContributor, Integer roundId);

    /**
     * Called when a player successfully defends a region.
     */
    int onRegionDefend(UUID uuid, Integer roundId);

    /**
     * Called when a player places road blocks.
     * Handles batching (1 token per 100 blocks).
     */
    int onRoadBlocksPlaced(UUID uuid, int blockCount, Integer roundId);

    /**
     * Called when a player completes a supply route.
     */
    int onSupplyRouteComplete(UUID uuid, Integer roundId);

    /**
     * Called when a player first supplies a region to 100%.
     */
    int onRegionSupplied(UUID uuid, Integer roundId);

    /**
     * Called when a player disrupts enemy supply.
     */
    int onSupplyDisrupted(UUID uuid, int regionsAffected, Integer roundId);

    /**
     * Called when a player places fortification blocks.
     * Handles batching (1 token per 100 blocks).
     */
    int onFortificationBuilt(UUID uuid, int blockCount, Integer roundId);

    /**
     * Called when a player gets a kill streak.
     */
    int onKillStreak(UUID uuid, int streakCount, Integer roundId);

    /**
     * Called when a player ends an enemy's kill streak.
     */
    int onShutdown(UUID uuid, Integer roundId);

    /**
     * Called when a player gets first blood in a round.
     */
    int onFirstBlood(UUID uuid, Integer roundId);

    /**
     * Called when a player logs in for the day.
     * Handles daily login bonus and streak bonuses.
     */
    int onDailyLogin(UUID uuid, Integer roundId);

    /**
     * Called when a round completes and player participated.
     */
    int onRoundComplete(UUID uuid, double ipEarned, Integer roundId);

    /**
     * Called periodically to award playtime tokens.
     * Awards 1 token per 2 hours of active play.
     */
    int onPlaytimeUpdate(UUID uuid, int minutesPlayed, Integer roundId);

    // ==================== MERIT GIVING ====================

    /**
     * Result of attempting to give a merit.
     */
    enum GiveResult {
        SUCCESS,
        INSUFFICIENT_TOKENS,
        SELF_MERIT,
        DAILY_LIMIT_GIVER,
        DAILY_LIMIT_RECEIVER,
        SAME_PLAYER_COOLDOWN,
        NO_INTERACTION,
        NEW_PLAYER_LOCKOUT,
        CROSS_TEAM_LIMIT
    }

    /**
     * Attempts to give merit tokens from one player to another.
     *
     * @param giver Player giving the merit
     * @param receiver Player receiving the merit
     * @param amount Number of merits to give
     * @param reason Optional reason category
     * @param sameTeam Whether players are on the same team
     * @param roundId Current round ID
     * @return Result of the operation
     */
    GiveResult giveMerit(UUID giver, UUID receiver, int amount, String reason, boolean sameTeam, Integer roundId);

    // ==================== INTERACTION TRACKING ====================

    /**
     * Records that two players were in the same region.
     */
    void recordInteraction(UUID player1, UUID player2, String regionId);

    // ==================== LEADERBOARD ====================

    /**
     * Gets top players by received merits.
     */
    List<PlayerMeritData> getLeaderboard(int limit);

    // ==================== ADMIN ====================

    /**
     * Admin: Give tokens directly to a player.
     */
    void adminGiveTokens(UUID uuid, int amount);

    /**
     * Admin: Give received merits directly to a player.
     */
    void adminGiveMerits(UUID uuid, int amount);

    /**
     * Admin: Set a player's received merits.
     */
    void adminSetMerits(UUID uuid, int amount);

    /**
     * Admin: Reset a player's merit data.
     */
    void adminReset(UUID uuid);
}

