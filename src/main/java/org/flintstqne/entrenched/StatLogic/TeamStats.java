package org.flintstqne.entrenched.StatLogic;

import java.util.Map;
import java.util.UUID;

/**
 * Team aggregate statistics for a round.
 */
public record TeamStats(
    String teamId,
    int roundId,
    int playerCount,
    Map<StatCategory, Double> totals,
    Map<StatCategory, Double> averages,
    UUID mvpUuid,
    String mvpName,
    double mvpScore
) {
    public double getTotal(StatCategory category) {
        return totals.getOrDefault(category, 0.0);
    }

    public double getAverage(StatCategory category) {
        return averages.getOrDefault(category, 0.0);
    }

    /**
     * Gets a formatted summary string.
     */
    public String getSummary() {
        return String.format("Team %s - %d players, %.0f kills, %.0f deaths, %.0f objectives",
                teamId.toUpperCase(),
                playerCount,
                getTotal(StatCategory.KILLS),
                getTotal(StatCategory.DEATHS),
                getTotal(StatCategory.OBJECTIVES_COMPLETED));
    }
}

