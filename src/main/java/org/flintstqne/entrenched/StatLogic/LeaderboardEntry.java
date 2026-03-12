package org.flintstqne.entrenched.StatLogic;

import java.util.UUID;

/**
 * Represents an entry in a leaderboard.
 */
public record LeaderboardEntry(
    int rank,
    UUID uuid,
    String username,
    double value
) {
    /**
     * Formats the value for display based on the category.
     */
    public String getFormattedValue(StatCategory category) {
        if (category == StatCategory.TIME_PLAYED || category == StatCategory.TIME_IN_ENEMY_TERRITORY) {
            // Format as hours:minutes
            int totalMinutes = (int) value;
            int hours = totalMinutes / 60;
            int minutes = totalMinutes % 60;
            return String.format("%dh %dm", hours, minutes);
        } else if (category == StatCategory.DAMAGE_DEALT || category == StatCategory.DAMAGE_TAKEN ||
                   category == StatCategory.DEPOT_LOOT_VALUE || category == StatCategory.IP_EARNED ||
                   category == StatCategory.IP_DENIED) {
            // Format with commas for large numbers
            return String.format("%,.0f", value);
        } else if (value == (int) value) {
            // Whole numbers
            return String.valueOf((int) value);
        } else {
            // Decimal numbers (e.g., KDR)
            return String.format("%.2f", value);
        }
    }
}

