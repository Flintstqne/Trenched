package org.flintstqne.entrenched.PartyLogic;

/**
 * Represents a player's membership in a party.
 */
public record PartyMember(
        String playerUuid,
        int partyId,
        long joinedAt
) {
}

