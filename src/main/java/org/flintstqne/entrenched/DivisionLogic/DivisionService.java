package org.flintstqne.entrenched.DivisionLogic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for division operations.
 */
public interface DivisionService {

    enum CreateResult {
        SUCCESS,
        NAME_TAKEN,
        TAG_TAKEN,
        TEAM_LIMIT_REACHED,
        ON_COOLDOWN,
        NO_ACTIVE_ROUND,
        PLAYER_NOT_ON_TEAM,
        ALREADY_IN_DIVISION
    }

    enum JoinResult {
        REQUEST_SENT,
        ALREADY_REQUESTED,
        ALREADY_IN_DIVISION,
        DIVISION_NOT_FOUND,
        WRONG_TEAM,
        NO_ACTIVE_ROUND
    }

    CreateResult createDivision(UUID founderUuid, String name, String tag);

    /**
     * Creates a division with option to bypass cooldown (for OPs).
     */
    CreateResult createDivision(UUID founderUuid, String name, String tag, boolean bypassCooldown);

    Optional<Division> getDivision(int divisionId);

    Optional<Division> findDivision(UUID playerUuid, String nameOrTag);

    List<Division> getDivisionsForTeam(String team);

    boolean renameDivision(UUID playerUuid, String newName);

    boolean changeTag(UUID playerUuid, String newTag);

    boolean setDescription(UUID playerUuid, String description);

    boolean disbandDivision(UUID playerUuid);

    JoinResult requestJoin(UUID playerUuid, String divisionNameOrTag);

    boolean acceptRequest(UUID officerUuid, UUID requesterUuid);

    boolean denyRequest(UUID officerUuid, UUID requesterUuid);

    boolean invitePlayer(UUID officerUuid, UUID targetUuid);

    boolean leaveDivision(UUID playerUuid);

    boolean kickMember(UUID officerUuid, UUID targetUuid);

    Optional<DivisionMember> getMembership(UUID playerUuid);

    Optional<Division> getPlayerDivision(UUID playerUuid);

    List<DivisionMember> getMembers(int divisionId);

    List<JoinRequest> getPendingRequests(UUID officerUuid);

    boolean promoteToOfficer(UUID commanderUuid, UUID targetUuid);

    boolean demoteToMember(UUID commanderUuid, UUID targetUuid);

    boolean transferCommander(UUID commanderUuid, UUID newCommanderUuid);

    boolean setWaypoint(UUID playerUuid, String name, String world, int x, int y, int z);

    boolean removeWaypoint(UUID playerUuid, String name);

    List<Waypoint> getWaypoints(UUID playerUuid);

    long getCreationCooldownRemaining(UUID playerUuid);

    boolean canCreateDivision(UUID playerUuid);

    int getDivisionCount(String team);

    int getMaxDivisionsPerTeam();
}

