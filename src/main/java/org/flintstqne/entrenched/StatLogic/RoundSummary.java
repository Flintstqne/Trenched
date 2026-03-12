package org.flintstqne.entrenched.StatLogic;

import java.util.UUID;

/**
 * Summary of a completed round.
 */
public record RoundSummary(
    int roundId,
    String winner,
    long startTime,
    long endTime,
    int redPlayerCount,
    int bluePlayerCount,
    double redTotalKills,
    double blueTotalKills,
    double redTotalObjectives,
    double blueTotalObjectives,
    double redTotalCaptures,
    double blueTotalCaptures,
    UUID mvpUuid,
    String mvpName,
    double mvpScore
) {
    /**
     * Gets duration in minutes.
     */
    public long getDurationMinutes() {
        return (endTime - startTime) / (1000 * 60);
    }

    /**
     * Gets total player count.
     */
    public int getTotalPlayers() {
        return redPlayerCount + bluePlayerCount;
    }

    /**
     * Gets total kills across both teams.
     */
    public double getTotalKills() {
        return redTotalKills + blueTotalKills;
    }
}

