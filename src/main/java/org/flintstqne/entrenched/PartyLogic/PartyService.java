package org.flintstqne.entrenched.PartyLogic;

import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;

public final class PartyService {

    private static final long INVITE_EXPIRY_MS = 60 * 1000; // 60 seconds

    private final PartyDb db;
    private final RoundService roundService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    public PartyService(PartyDb db, RoundService roundService, TeamService teamService, ConfigManager configManager) {
        this.db = db;
        this.roundService = roundService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    private Optional<Round> getCurrentRound() {
        return roundService.getCurrentRound();
    }

    private int getCurrentRoundId() {
        return getCurrentRound().map(Round::roundId).orElse(-1);
    }

    /**
     * Creates a new party with the player as leader.
     */
    public CreateResult createParty(UUID playerUuid) {
        Optional<Round> roundOpt = getCurrentRound();
        if (roundOpt.isEmpty()) return CreateResult.NO_ACTIVE_ROUND;

        int roundId = roundOpt.get().roundId();
        String playerUuidStr = playerUuid.toString();

        // Check if player is on a team
        Optional<String> teamOpt = teamService.getPlayerTeam(playerUuid);
        if (teamOpt.isEmpty()) return CreateResult.PLAYER_NOT_ON_TEAM;

        // Check if already in a party
        if (db.getMembership(playerUuidStr).isPresent()) {
            return CreateResult.ALREADY_IN_PARTY;
        }

        // Create the party
        int partyId = db.createParty(roundId, teamOpt.get(), playerUuidStr);
        db.addMember(partyId, playerUuidStr);

        return CreateResult.SUCCESS;
    }

    /**
     * Invites a player to the party.
     */
    public InviteResult invitePlayer(UUID inviterUuid, UUID inviteeUuid) {
        if (inviterUuid.equals(inviteeUuid)) return InviteResult.CANNOT_INVITE_SELF;

        String inviterUuidStr = inviterUuid.toString();
        String inviteeUuidStr = inviteeUuid.toString();

        // Check if inviter is in a party
        Optional<PartyMember> inviterMember = db.getMembership(inviterUuidStr);
        if (inviterMember.isEmpty()) return InviteResult.NOT_IN_PARTY;

        int partyId = inviterMember.get().partyId();
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) return InviteResult.NOT_IN_PARTY;

        Party party = partyOpt.get();

        // Check if inviter is leader
        if (!party.leaderUuid().equals(inviterUuidStr)) {
            return InviteResult.NOT_LEADER;
        }

        // Check party size
        if (db.countMembers(partyId) >= getMaxPartySize()) {
            return InviteResult.PARTY_FULL;
        }

        // Check if target is already in a party
        if (db.getMembership(inviteeUuidStr).isPresent()) {
            return InviteResult.TARGET_ALREADY_IN_PARTY;
        }

        // Check if target is on the same team
        Optional<String> inviteeTeam = teamService.getPlayerTeam(inviteeUuid);
        if (inviteeTeam.isEmpty() || !inviteeTeam.get().equals(party.team())) {
            return InviteResult.WRONG_TEAM;
        }

        // Check if already invited
        if (db.getInvite(inviteeUuidStr, partyId).isPresent()) {
            return InviteResult.TARGET_ALREADY_INVITED;
        }

        // Create invite
        long expiresAt = System.currentTimeMillis() + INVITE_EXPIRY_MS;
        db.createInvite(inviterUuidStr, inviteeUuidStr, partyId, expiresAt);

        return InviteResult.SUCCESS;
    }

    /**
     * Accepts a party invite.
     */
    public JoinResult acceptInvite(UUID playerUuid, int partyId) {
        String playerUuidStr = playerUuid.toString();

        // Check if already in a party
        if (db.getMembership(playerUuidStr).isPresent()) {
            return JoinResult.ALREADY_IN_PARTY;
        }

        // Check if invite exists
        Optional<PartyInvite> inviteOpt = db.getInvite(playerUuidStr, partyId);
        if (inviteOpt.isEmpty()) return JoinResult.NO_INVITE;

        PartyInvite invite = inviteOpt.get();
        if (invite.isExpired()) {
            db.deleteInvite(invite.inviteId());
            return JoinResult.INVITE_EXPIRED;
        }

        // Check if party still exists
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) {
            db.deleteInvite(invite.inviteId());
            return JoinResult.PARTY_NOT_FOUND;
        }

        // Check party size
        if (db.countMembers(partyId) >= getMaxPartySize()) {
            return JoinResult.PARTY_FULL;
        }

        // Join the party
        db.addMember(partyId, playerUuidStr);
        db.deleteInvitesForPlayer(playerUuidStr);

        return JoinResult.SUCCESS;
    }

    /**
     * Accepts the most recent party invite.
     */
    public JoinResult acceptLatestInvite(UUID playerUuid) {
        String playerUuidStr = playerUuid.toString();

        List<PartyInvite> invites = db.getPendingInvites(playerUuidStr);
        if (invites.isEmpty()) return JoinResult.NO_INVITE;

        // Get the most recent invite
        PartyInvite latestInvite = invites.get(0);
        return acceptInvite(playerUuid, latestInvite.partyId());
    }

    /**
     * Declines a party invite.
     */
    public boolean declineInvite(UUID playerUuid, int partyId) {
        String playerUuidStr = playerUuid.toString();

        Optional<PartyInvite> inviteOpt = db.getInvite(playerUuidStr, partyId);
        if (inviteOpt.isEmpty()) return false;

        db.deleteInvite(inviteOpt.get().inviteId());
        return true;
    }

    /**
     * Leaves the current party.
     */
    public LeaveResult leaveParty(UUID playerUuid) {
        String playerUuidStr = playerUuid.toString();

        Optional<PartyMember> memberOpt = db.getMembership(playerUuidStr);
        if (memberOpt.isEmpty()) return LeaveResult.NOT_IN_PARTY;

        int partyId = memberOpt.get().partyId();
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) return LeaveResult.NOT_IN_PARTY;

        Party party = partyOpt.get();

        // Remove member
        db.removeMember(playerUuidStr);

        // If leader left, handle succession or disband
        if (party.leaderUuid().equals(playerUuidStr)) {
            List<PartyMember> remainingMembers = db.getMembers(partyId);
            if (remainingMembers.isEmpty()) {
                // No members left, delete the party
                db.deleteParty(partyId);
                return LeaveResult.PARTY_DISBANDED;
            } else {
                // Transfer to first remaining member
                db.updatePartyLeader(partyId, remainingMembers.get(0).playerUuid());
            }
        }

        return LeaveResult.SUCCESS;
    }

    /**
     * Kicks a player from the party (leader only).
     */
    public boolean kickMember(UUID leaderUuid, UUID targetUuid) {
        if (leaderUuid.equals(targetUuid)) return false; // Can't kick yourself

        String leaderUuidStr = leaderUuid.toString();
        String targetUuidStr = targetUuid.toString();

        Optional<PartyMember> leaderMember = db.getMembership(leaderUuidStr);
        if (leaderMember.isEmpty()) return false;

        int partyId = leaderMember.get().partyId();
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) return false;

        // Verify leader
        if (!partyOpt.get().leaderUuid().equals(leaderUuidStr)) return false;

        // Verify target is in same party
        Optional<PartyMember> targetMember = db.getMembership(targetUuidStr);
        if (targetMember.isEmpty() || targetMember.get().partyId() != partyId) return false;

        db.removeMember(targetUuidStr);
        return true;
    }

    /**
     * Transfers party leadership.
     */
    public boolean transferLeadership(UUID leaderUuid, UUID newLeaderUuid) {
        if (leaderUuid.equals(newLeaderUuid)) return false;

        String leaderUuidStr = leaderUuid.toString();
        String newLeaderUuidStr = newLeaderUuid.toString();

        Optional<PartyMember> leaderMember = db.getMembership(leaderUuidStr);
        if (leaderMember.isEmpty()) return false;

        int partyId = leaderMember.get().partyId();
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) return false;

        // Verify current leader
        if (!partyOpt.get().leaderUuid().equals(leaderUuidStr)) return false;

        // Verify new leader is in party
        Optional<PartyMember> newLeaderMember = db.getMembership(newLeaderUuidStr);
        if (newLeaderMember.isEmpty() || newLeaderMember.get().partyId() != partyId) return false;

        db.updatePartyLeader(partyId, newLeaderUuidStr);
        return true;
    }

    /**
     * Disbands the party (leader only).
     */
    public boolean disbandParty(UUID leaderUuid) {
        String leaderUuidStr = leaderUuid.toString();

        Optional<PartyMember> leaderMember = db.getMembership(leaderUuidStr);
        if (leaderMember.isEmpty()) return false;

        int partyId = leaderMember.get().partyId();
        Optional<Party> partyOpt = db.getParty(partyId);
        if (partyOpt.isEmpty()) return false;

        // Verify leader
        if (!partyOpt.get().leaderUuid().equals(leaderUuidStr)) return false;

        db.deleteParty(partyId);
        return true;
    }

    /**
     * Gets a player's party.
     */
    public Optional<Party> getPlayerParty(UUID playerUuid) {
        Optional<PartyMember> memberOpt = db.getMembership(playerUuid.toString());
        if (memberOpt.isEmpty()) return Optional.empty();
        return db.getParty(memberOpt.get().partyId());
    }

    /**
     * Gets all members of a party.
     */
    public List<PartyMember> getMembers(int partyId) {
        return db.getMembers(partyId);
    }

    /**
     * Gets pending invites for a player.
     */
    public List<PartyInvite> getPendingInvites(UUID playerUuid) {
        return db.getPendingInvites(playerUuid.toString());
    }

    /**
     * Checks if player is party leader.
     */
    public boolean isLeader(UUID playerUuid) {
        Optional<Party> partyOpt = getPlayerParty(playerUuid);
        return partyOpt.isPresent() && partyOpt.get().leaderUuid().equals(playerUuid.toString());
    }

    /**
     * Gets the max party size from config.
     */
    public int getMaxPartySize() {
        return configManager.getPartyMaxSize();
    }

    public enum CreateResult {
        SUCCESS,
        ALREADY_IN_PARTY,
        NO_ACTIVE_ROUND,
        PLAYER_NOT_ON_TEAM
    }

    public enum InviteResult {
        SUCCESS,
        NOT_IN_PARTY,
        NOT_LEADER,
        TARGET_ALREADY_IN_PARTY,
        TARGET_ALREADY_INVITED,
        PARTY_FULL,
        WRONG_TEAM,
        CANNOT_INVITE_SELF
    }

    public enum JoinResult {
        SUCCESS,
        ALREADY_IN_PARTY,
        NO_INVITE,
        INVITE_EXPIRED,
        PARTY_FULL,
        PARTY_NOT_FOUND
    }

    public enum LeaveResult {
        SUCCESS,
        NOT_IN_PARTY,
        PARTY_DISBANDED
    }
}
