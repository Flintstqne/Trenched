package org.flintstqne.entrenched.DivisionLogic;

/**
 * Represents a player's membership in a division.
 */
public record DivisionMember(
        String playerUuid,
        int divisionId,
        int roundId,
        DivisionRole role,
        long joinedAt
) {
}

