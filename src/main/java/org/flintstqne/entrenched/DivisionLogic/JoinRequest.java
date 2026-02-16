package org.flintstqne.entrenched.DivisionLogic;

/**
 * Represents a pending join request to a division.
 */
public record JoinRequest(
        int requestId,
        String playerUuid,
        int divisionId,
        long requestedAt,
        String status
) {
}

