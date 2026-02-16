package org.flintstqne.entrenched.DivisionLogic;

/**
 * Represents a Division within a team.
 */
public record Division(
        int divisionId,
        int roundId,
        String team,
        String name,
        String tag,
        String description,
        String founderUuid,
        long createdAt
) {
    /**
     * Gets the formatted tag for display (e.g., "[NW]").
     */
    public String formattedTag() {
        return "[" + tag + "]";
    }
}

