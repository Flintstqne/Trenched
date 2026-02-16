package org.flintstqne.entrenched.PartyLogic;

/**
 * Represents a party (small group of players).
 */
public record Party(
        int partyId,
        int roundId,
        String team,
        String leaderUuid,
        long createdAt
) {
}

