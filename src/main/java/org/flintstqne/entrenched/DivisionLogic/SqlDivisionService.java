package org.flintstqne.entrenched.DivisionLogic;

import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;

public final class SqlDivisionService implements DivisionService {

    private final DivisionDb db;
    private final RoundService roundService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    public SqlDivisionService(DivisionDb db, RoundService roundService, TeamService teamService, ConfigManager configManager) {
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

    @Override
    public CreateResult createDivision(UUID founderUuid, String name, String tag) {
        return createDivision(founderUuid, name, tag, false);
    }

    @Override
    public CreateResult createDivision(UUID founderUuid, String name, String tag, boolean bypassCooldown) {
        Optional<Round> roundOpt = getCurrentRound();
        if (roundOpt.isEmpty()) return CreateResult.NO_ACTIVE_ROUND;

        int roundId = roundOpt.get().roundId();
        String founderUuidStr = founderUuid.toString();

        Optional<String> teamOpt = teamService.getPlayerTeam(founderUuid);
        if (teamOpt.isEmpty()) return CreateResult.PLAYER_NOT_ON_TEAM;
        String team = teamOpt.get();

        if (db.getMembership(founderUuidStr, roundId).isPresent()) {
            return CreateResult.ALREADY_IN_DIVISION;
        }

        // Skip cooldown check if bypassed (for OPs)
        if (!bypassCooldown && !canCreateDivision(founderUuid)) return CreateResult.ON_COOLDOWN;

        int currentCount = db.countDivisionsForTeam(roundId, team);
        if (currentCount >= getMaxDivisionsPerTeam()) return CreateResult.TEAM_LIMIT_REACHED;

        if (db.getDivisionByName(roundId, team, name).isPresent()) return CreateResult.NAME_TAKEN;

        String normalizedTag = tag.toUpperCase();
        if (db.getDivisionByTag(roundId, team, normalizedTag).isPresent()) return CreateResult.TAG_TAKEN;

        int divisionId = db.createDivision(roundId, team, name, normalizedTag, founderUuidStr);
        db.addMember(divisionId, roundId, founderUuidStr, DivisionRole.COMMANDER);

        // Only set cooldown if not bypassed
        if (!bypassCooldown) {
            db.setFounderCooldown(founderUuidStr);
        }

        return CreateResult.SUCCESS;
    }

    @Override
    public Optional<Division> getDivision(int divisionId) {
        return db.getDivision(divisionId);
    }

    @Override
    public Optional<Division> findDivision(UUID playerUuid, String nameOrTag) {
        Optional<Round> roundOpt = getCurrentRound();
        if (roundOpt.isEmpty()) return Optional.empty();

        int roundId = roundOpt.get().roundId();

        Optional<String> teamOpt = teamService.getPlayerTeam(playerUuid);
        if (teamOpt.isEmpty()) return Optional.empty();
        String team = teamOpt.get();

        Optional<Division> byName = db.getDivisionByName(roundId, team, nameOrTag);
        if (byName.isPresent()) return byName;

        return db.getDivisionByTag(roundId, team, nameOrTag.toUpperCase());
    }

    @Override
    public List<Division> getDivisionsForTeam(String team) {
        int roundId = getCurrentRoundId();
        if (roundId == -1) return Collections.emptyList();
        return db.getDivisionsForTeam(roundId, team);
    }

    @Override
    public boolean renameDivision(UUID playerUuid, String newName) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canEditDivision()) return false;

        Optional<Division> divOpt = db.getDivision(memberOpt.get().divisionId());
        if (divOpt.isEmpty()) return false;

        Division div = divOpt.get();
        Optional<Division> existing = db.getDivisionByName(div.roundId(), div.team(), newName);
        if (existing.isPresent() && existing.get().divisionId() != div.divisionId()) return false;

        db.updateDivisionName(div.divisionId(), newName);
        return true;
    }

    @Override
    public boolean changeTag(UUID playerUuid, String newTag) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canEditDivision()) return false;

        Optional<Division> divOpt = db.getDivision(memberOpt.get().divisionId());
        if (divOpt.isEmpty()) return false;

        Division div = divOpt.get();
        String normalizedTag = newTag.toUpperCase();
        Optional<Division> existing = db.getDivisionByTag(div.roundId(), div.team(), normalizedTag);
        if (existing.isPresent() && existing.get().divisionId() != div.divisionId()) return false;

        db.updateDivisionTag(div.divisionId(), normalizedTag);
        return true;
    }

    @Override
    public boolean setDescription(UUID playerUuid, String description) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canEditDivision()) return false;

        db.updateDivisionDescription(memberOpt.get().divisionId(), description);
        return true;
    }

    @Override
    public boolean disbandDivision(UUID playerUuid) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canDisband()) return false;

        db.deleteDivision(memberOpt.get().divisionId());
        return true;
    }

    @Override
    public JoinResult requestJoin(UUID playerUuid, String divisionNameOrTag) {
        Optional<Round> roundOpt = getCurrentRound();
        if (roundOpt.isEmpty()) return JoinResult.NO_ACTIVE_ROUND;

        int roundId = roundOpt.get().roundId();
        String playerUuidStr = playerUuid.toString();

        if (db.getMembership(playerUuidStr, roundId).isPresent()) return JoinResult.ALREADY_IN_DIVISION;

        Optional<Division> divOpt = findDivision(playerUuid, divisionNameOrTag);
        if (divOpt.isEmpty()) return JoinResult.DIVISION_NOT_FOUND;

        Division div = divOpt.get();

        Optional<String> teamOpt = teamService.getPlayerTeam(playerUuid);
        if (teamOpt.isEmpty() || !teamOpt.get().equals(div.team())) return JoinResult.WRONG_TEAM;

        if (db.getPendingRequest(playerUuidStr, div.divisionId()).isPresent()) return JoinResult.ALREADY_REQUESTED;

        db.createJoinRequest(playerUuidStr, div.divisionId());
        return JoinResult.REQUEST_SENT;
    }

    @Override
    public boolean acceptRequest(UUID officerUuid, UUID requesterUuid) {
        Optional<DivisionMember> officerMember = getMembership(officerUuid);
        if (officerMember.isEmpty()) return false;
        if (!officerMember.get().role().canManageMembers()) return false;

        int divisionId = officerMember.get().divisionId();
        int roundId = officerMember.get().roundId();
        String requesterUuidStr = requesterUuid.toString();

        Optional<JoinRequest> requestOpt = db.getPendingRequest(requesterUuidStr, divisionId);
        if (requestOpt.isEmpty()) return false;

        if (db.getMembership(requesterUuidStr, roundId).isPresent()) {
            db.updateRequestStatus(requestOpt.get().requestId(), "DENIED");
            return false;
        }

        db.updateRequestStatus(requestOpt.get().requestId(), "ACCEPTED");
        db.addMember(divisionId, roundId, requesterUuidStr, DivisionRole.MEMBER);
        return true;
    }

    @Override
    public boolean denyRequest(UUID officerUuid, UUID requesterUuid) {
        Optional<DivisionMember> officerMember = getMembership(officerUuid);
        if (officerMember.isEmpty()) return false;
        if (!officerMember.get().role().canManageMembers()) return false;

        String requesterUuidStr = requesterUuid.toString();
        Optional<JoinRequest> requestOpt = db.getPendingRequest(requesterUuidStr, officerMember.get().divisionId());
        if (requestOpt.isEmpty()) return false;

        db.updateRequestStatus(requestOpt.get().requestId(), "DENIED");
        return true;
    }

    @Override
    public boolean invitePlayer(UUID officerUuid, UUID targetUuid) {
        Optional<DivisionMember> officerMember = getMembership(officerUuid);
        if (officerMember.isEmpty()) return false;
        if (!officerMember.get().role().canManageMembers()) return false;

        int divisionId = officerMember.get().divisionId();
        int roundId = officerMember.get().roundId();
        String targetUuidStr = targetUuid.toString();

        if (db.getMembership(targetUuidStr, roundId).isPresent()) return false;

        Optional<Division> divOpt = db.getDivision(divisionId);
        if (divOpt.isEmpty()) return false;

        Optional<String> targetTeam = teamService.getPlayerTeam(targetUuid);
        if (targetTeam.isEmpty() || !targetTeam.get().equals(divOpt.get().team())) return false;

        db.addMember(divisionId, roundId, targetUuidStr, DivisionRole.MEMBER);
        return true;
    }

    @Override
    public boolean leaveDivision(UUID playerUuid) {
        int roundId = getCurrentRoundId();
        if (roundId == -1) return false;

        String playerUuidStr = playerUuid.toString();
        Optional<DivisionMember> memberOpt = db.getMembership(playerUuidStr, roundId);
        if (memberOpt.isEmpty()) return false;

        DivisionMember member = memberOpt.get();

        if (member.role() == DivisionRole.COMMANDER) {
            List<DivisionMember> members = db.getMembers(member.divisionId());
            if (members.size() > 1) {
                Optional<DivisionMember> newCommander = members.stream()
                        .filter(m -> !m.playerUuid().equals(playerUuidStr))
                        .max(Comparator.comparing(DivisionMember::role).thenComparing(DivisionMember::joinedAt));

                if (newCommander.isPresent()) {
                    db.updateMemberRole(newCommander.get().playerUuid(), roundId, DivisionRole.COMMANDER);
                }
            } else {
                db.deleteDivision(member.divisionId());
                return true;
            }
        }

        db.removeMember(playerUuidStr, roundId);
        return true;
    }

    @Override
    public boolean kickMember(UUID officerUuid, UUID targetUuid) {
        Optional<DivisionMember> officerMember = getMembership(officerUuid);
        if (officerMember.isEmpty()) return false;
        if (!officerMember.get().role().canManageMembers()) return false;

        int roundId = officerMember.get().roundId();
        String targetUuidStr = targetUuid.toString();

        Optional<DivisionMember> targetMember = db.getMembership(targetUuidStr, roundId);
        if (targetMember.isEmpty()) return false;

        if (targetMember.get().divisionId() != officerMember.get().divisionId()) return false;

        if (officerMember.get().role() == DivisionRole.OFFICER && targetMember.get().role() != DivisionRole.MEMBER) {
            return false;
        }

        if (targetMember.get().role() == DivisionRole.COMMANDER) return false;

        db.removeMember(targetUuidStr, roundId);
        return true;
    }

    @Override
    public Optional<DivisionMember> getMembership(UUID playerUuid) {
        int roundId = getCurrentRoundId();
        if (roundId == -1) return Optional.empty();
        return db.getMembership(playerUuid.toString(), roundId);
    }

    @Override
    public Optional<Division> getPlayerDivision(UUID playerUuid) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return Optional.empty();
        return db.getDivision(memberOpt.get().divisionId());
    }

    @Override
    public List<DivisionMember> getMembers(int divisionId) {
        return db.getMembers(divisionId);
    }

    @Override
    public List<JoinRequest> getPendingRequests(UUID officerUuid) {
        Optional<DivisionMember> memberOpt = getMembership(officerUuid);
        if (memberOpt.isEmpty()) return Collections.emptyList();
        if (!memberOpt.get().role().canManageMembers()) return Collections.emptyList();
        return db.getPendingRequests(memberOpt.get().divisionId());
    }

    @Override
    public boolean promoteToOfficer(UUID commanderUuid, UUID targetUuid) {
        Optional<DivisionMember> commanderMember = getMembership(commanderUuid);
        if (commanderMember.isEmpty()) return false;
        if (!commanderMember.get().role().canPromote()) return false;

        int roundId = commanderMember.get().roundId();
        String targetUuidStr = targetUuid.toString();

        Optional<DivisionMember> targetMember = db.getMembership(targetUuidStr, roundId);
        if (targetMember.isEmpty()) return false;
        if (targetMember.get().divisionId() != commanderMember.get().divisionId()) return false;
        if (targetMember.get().role() != DivisionRole.MEMBER) return false;

        db.updateMemberRole(targetUuidStr, roundId, DivisionRole.OFFICER);
        return true;
    }

    @Override
    public boolean demoteToMember(UUID commanderUuid, UUID targetUuid) {
        Optional<DivisionMember> commanderMember = getMembership(commanderUuid);
        if (commanderMember.isEmpty()) return false;
        if (!commanderMember.get().role().canPromote()) return false;

        int roundId = commanderMember.get().roundId();
        String targetUuidStr = targetUuid.toString();

        Optional<DivisionMember> targetMember = db.getMembership(targetUuidStr, roundId);
        if (targetMember.isEmpty()) return false;
        if (targetMember.get().divisionId() != commanderMember.get().divisionId()) return false;
        if (targetMember.get().role() != DivisionRole.OFFICER) return false;

        db.updateMemberRole(targetUuidStr, roundId, DivisionRole.MEMBER);
        return true;
    }

    @Override
    public boolean transferCommander(UUID commanderUuid, UUID newCommanderUuid) {
        Optional<DivisionMember> commanderMember = getMembership(commanderUuid);
        if (commanderMember.isEmpty()) return false;
        if (commanderMember.get().role() != DivisionRole.COMMANDER) return false;

        int roundId = commanderMember.get().roundId();
        String commanderUuidStr = commanderUuid.toString();
        String newCommanderUuidStr = newCommanderUuid.toString();

        Optional<DivisionMember> newCommanderMember = db.getMembership(newCommanderUuidStr, roundId);
        if (newCommanderMember.isEmpty()) return false;
        if (newCommanderMember.get().divisionId() != commanderMember.get().divisionId()) return false;

        db.updateMemberRole(newCommanderUuidStr, roundId, DivisionRole.COMMANDER);
        db.updateMemberRole(commanderUuidStr, roundId, DivisionRole.OFFICER);
        return true;
    }

    @Override
    public boolean setWaypoint(UUID playerUuid, String name, String world, int x, int y, int z) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canSetWaypoints()) return false;

        int divisionId = memberOpt.get().divisionId();
        db.deleteWaypointByName(divisionId, name);
        db.createWaypoint(divisionId, name, world, x, y, z, playerUuid.toString());
        return true;
    }

    @Override
    public boolean removeWaypoint(UUID playerUuid, String name) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return false;
        if (!memberOpt.get().role().canSetWaypoints()) return false;

        int divisionId = memberOpt.get().divisionId();
        Optional<Waypoint> waypointOpt = db.getWaypoint(divisionId, name);
        if (waypointOpt.isEmpty()) return false;

        db.deleteWaypoint(waypointOpt.get().waypointId());
        return true;
    }

    @Override
    public List<Waypoint> getWaypoints(UUID playerUuid) {
        Optional<DivisionMember> memberOpt = getMembership(playerUuid);
        if (memberOpt.isEmpty()) return Collections.emptyList();
        return db.getWaypoints(memberOpt.get().divisionId());
    }

    @Override
    public long getCreationCooldownRemaining(UUID playerUuid) {
        Optional<Long> lastCreated = db.getFounderCooldown(playerUuid.toString());
        if (lastCreated.isEmpty()) return 0;

        long cooldownMs = configManager.getDivisionFounderCooldownHours() * 60 * 60 * 1000L;
        long elapsed = System.currentTimeMillis() - lastCreated.get();
        long remaining = cooldownMs - elapsed;

        return Math.max(0, remaining);
    }

    @Override
    public boolean canCreateDivision(UUID playerUuid) {
        return getCreationCooldownRemaining(playerUuid) == 0;
    }

    @Override
    public int getDivisionCount(String team) {
        int roundId = getCurrentRoundId();
        if (roundId == -1) return 0;
        return db.countDivisionsForTeam(roundId, team);
    }

    @Override
    public int getMaxDivisionsPerTeam() {
        return configManager.getMaxDivisionsPerTeam();
    }
}

