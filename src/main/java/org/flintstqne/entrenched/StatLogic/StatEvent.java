package org.flintstqne.entrenched.StatLogic;

import java.util.UUID;

/**
 * Represents a queued stat event for async batched writing.
 */
public record StatEvent(
    UUID playerUuid,
    String playerName,
    StatCategory category,
    double delta,
    int roundId,
    long timestamp,
    String metadata
) {
    public static StatEvent increment(UUID playerUuid, String playerName, StatCategory category,
                                       double delta, int roundId) {
        return new StatEvent(playerUuid, playerName, category, delta, roundId,
                             System.currentTimeMillis(), null);
    }

    public static StatEvent increment(UUID playerUuid, String playerName, StatCategory category,
                                       double delta, int roundId, String metadata) {
        return new StatEvent(playerUuid, playerName, category, delta, roundId,
                             System.currentTimeMillis(), metadata);
    }

    public static StatEvent set(UUID playerUuid, String playerName, StatCategory category,
                                 double value, int roundId) {
        return new StatEvent(playerUuid, playerName, category, value, roundId,
                             System.currentTimeMillis(), null);
    }

    public static StatEvent lifetimeIncrement(UUID playerUuid, String playerName,
                                               StatCategory category, double delta) {
        return new StatEvent(playerUuid, playerName, category, delta, -1,
                             System.currentTimeMillis(), null);
    }

    public boolean hasRound() {
        return roundId > 0;
    }

    public boolean isSetOperation() {
        return !category.isCounter();
    }
}

