package org.flintstqne.entrenched.PartyLogic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for party operations.
 */
public interface PartyService {

    enum CreateResult {
        SUCCESS,
        ALREADY_IN_PARTY,
        NO_ACTIVE_ROUND,
        PLAYER_NOT_ON_TEAM
    }

    enum InviteResult {
        SUCCESS,
        NOT_IN_PARTY,
        NOT_LEADER,
        TARGET_ALREADY_IN_PARTY,
        TARGET_ALREADY_INVITED,
        PARTY_FULL,
        WRONG_TEAM,
        CANNOT_INVITE_SELF
    }

    enum JoinResult {
        SUCCESS,
        ALREADY_IN_PARTY,
        NO_INVITE,
        INVITE_EXPIRED,
        PARTY_FULL,
        PARTY_NOT_FOUND
    }

    enum LeaveResult {
        SUCCESS,
        NOT_IN_PARTY,
        PARTY_DISBANDED
    }

    /**
     * Creates a new party with the player as leader.
     */
    CreateResult createParty(UUID playerUuid);

    /**
     * Invites a player to the party.
     */
    InviteResult invitePlayer(UUID inviterUuid, UUID inviteeUuid);

    /**
     * Accepts a party invite.
     */
    JoinResult acceptInvite(UUID playerUuid, int partyId);

    /**
     * Accepts the most recent party invite.
     */
    JoinResult acceptLatestInvite(UUID playerUuid);

    /**
     * Declines a party invite.
     */
    boolean declineInvite(UUID playerUuid, int partyId);

    /**
     * Leaves the current party.
     */
    LeaveResult leaveParty(UUID playerUuid);

    /**
     * Kicks a player from the party (leader only).
     */
    boolean kickMember(UUID leaderUuid, UUID targetUuid);

    /**
     * Transfers party leadership.
     */
    boolean transferLeadership(UUID leaderUuid, UUID newLeaderUuid);

    /**
     * Disbands the party (leader only).
     */
    boolean disbandParty(UUID leaderUuid);

    /**
     * Gets a player's party.
     */
    Optional<Party> getPlayerParty(UUID playerUuid);

    /**
     * Gets all members of a party.
     */
    List<PartyMember> getMembers(int partyId);

    /**
     * Gets pending invites for a player.
     */
    List<PartyInvite> getPendingInvites(UUID playerUuid);

    /**
     * Checks if player is party leader.
     */
    boolean isLeader(UUID playerUuid);

    /**
     * Gets the max party size from config.
     */
    int getMaxPartySize();
}

