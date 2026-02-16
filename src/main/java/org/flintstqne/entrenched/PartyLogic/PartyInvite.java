package org.flintstqne.entrenched.PartyLogic;

/**
 * Represents a pending party invite.
 */
public record PartyInvite(
        int inviteId,
        String inviterUuid,
        String inviteeUuid,
        int partyId,
        long invitedAt,
        long expiresAt
) {
    /**
     * Checks if this invite has expired.
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}

