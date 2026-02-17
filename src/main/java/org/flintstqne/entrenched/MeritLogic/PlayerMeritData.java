package org.flintstqne.entrenched.MeritLogic;

import java.util.UUID;

/**
 * Represents a player's merit data.
 */
public record PlayerMeritData(
        UUID uuid,
        int tokenBalance,           // Spendable tokens earned
        int tokensEarnedToday,
        int receivedMerits,         // Merits received from others (determines rank)
        int receivedToday,
        int meritsGivenToday,
        int lifetimeTokensEarned,
        int lifetimeMeritsGiven,
        int lifetimeMeritsReceived,
        int lifetimeKills,
        int lifetimeCaptures,
        int lifetimeRoadBlocks,
        int roundsCompleted,
        int playtimeMinutes,
        int loginStreak,
        String lastLoginDate,
        long createdAt
) {
    /**
     * Gets the player's current rank based on received merits.
     */
    public MeritRank getRank() {
        return MeritRank.getRankForMerits(receivedMerits);
    }

    /**
     * Gets merits needed to reach the next rank.
     */
    public int getMeritsToNextRank() {
        return getRank().getMeritsToNextRank(receivedMerits);
    }

    /**
     * Gets the next rank, or null if at max.
     */
    public MeritRank getNextRank() {
        return getRank().getNextRank();
    }

    /**
     * Creates a new PlayerMeritData with updated token balance.
     */
    public PlayerMeritData withTokenBalance(int newBalance) {
        return new PlayerMeritData(uuid, newBalance, tokensEarnedToday, receivedMerits, receivedToday,
                meritsGivenToday, lifetimeTokensEarned, lifetimeMeritsGiven, lifetimeMeritsReceived,
                lifetimeKills, lifetimeCaptures, lifetimeRoadBlocks, roundsCompleted, playtimeMinutes,
                loginStreak, lastLoginDate, createdAt);
    }

    /**
     * Creates a new PlayerMeritData with updated received merits.
     */
    public PlayerMeritData withReceivedMerits(int newReceived) {
        return new PlayerMeritData(uuid, tokenBalance, tokensEarnedToday, newReceived, receivedToday,
                meritsGivenToday, lifetimeTokensEarned, lifetimeMeritsGiven, lifetimeMeritsReceived,
                lifetimeKills, lifetimeCaptures, lifetimeRoadBlocks, roundsCompleted, playtimeMinutes,
                loginStreak, lastLoginDate, createdAt);
    }

    /**
     * Creates default data for a new player.
     */
    public static PlayerMeritData createNew(UUID uuid) {
        return new PlayerMeritData(
                uuid,
                0,  // tokenBalance
                0,  // tokensEarnedToday
                0,  // receivedMerits
                0,  // receivedToday
                0,  // meritsGivenToday
                0,  // lifetimeTokensEarned
                0,  // lifetimeMeritsGiven
                0,  // lifetimeMeritsReceived
                0,  // lifetimeKills
                0,  // lifetimeCaptures
                0,  // lifetimeRoadBlocks
                0,  // roundsCompleted
                0,  // playtimeMinutes
                0,  // loginStreak
                null,  // lastLoginDate
                System.currentTimeMillis()
        );
    }
}

