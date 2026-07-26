package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.Location;

import java.util.*;

public final class TeamService {

    private final TeamDb db;

    // Caches
    private Map<String, Team> teamCache;
    private Map<UUID, String> membershipCache;
    private Map<String, Location> spawnCache;

    public TeamService(TeamDb db) {
        this.db = db;
        refreshCache();
    }

    private void refreshCache() {
        teamCache = new HashMap<>(db.loadTeams());
        membershipCache = new HashMap<>(db.loadMemberships());
        spawnCache = new HashMap<>(db.loadSpawns());
    }

    public Optional<Team> getTeam(String teamId) {
        return Optional.ofNullable(teamCache.get(teamId));
    }

    public Collection<Team> listTeams() {
        return List.copyOf(teamCache.values());
    }

    public Optional<String> getPlayerTeam(UUID playerId) {
        return Optional.ofNullable(membershipCache.get(playerId));
    }

    public JoinResult joinTeam(UUID playerId, String teamId, JoinReason reason) {
        Team team = teamCache.get(teamId);
        if (team == null) return JoinResult.TEAM_NOT_FOUND;

        String existing = membershipCache.get(playerId);
        if (existing != null) {
            return existing.equals(teamId) ? JoinResult.ALREADY_IN_TEAM : JoinResult.ALREADY_IN_TEAM;
        }

        int maxSize = team.color(); // Note: assumes color field stores max size (needs fix if color is truly color)
        if (maxSize > 0) {
            long currentCount = membershipCache.values().stream()
                    .filter(teamId::equals)
                    .count();
            if (currentCount >= maxSize) return JoinResult.TEAM_FULL;
        }

        db.setMembership(playerId, teamId);
        membershipCache.put(playerId, teamId);
        return JoinResult.OK;
    }

    public LeaveResult leaveTeam(UUID playerId) {
        if (!membershipCache.containsKey(playerId)) return LeaveResult.NOT_IN_TEAM;

        db.clearMembership(playerId);
        membershipCache.remove(playerId);
        return LeaveResult.OK;
    }

    public SwapResult swapTeam(UUID playerId, String toTeamId) {
        String existing = membershipCache.get(playerId);
        if (existing == null) return SwapResult.NOT_IN_TEAM;
        if (existing.equals(toTeamId)) return SwapResult.OK;

        Team team = teamCache.get(toTeamId);
        if (team == null) return SwapResult.TEAM_NOT_FOUND;

        int maxSize = team.color(); // Same note as above
        if (maxSize > 0) {
            long currentCount = membershipCache.values().stream()
                    .filter(toTeamId::equals)
                    .count();
            if (currentCount >= maxSize) return SwapResult.TEAM_FULL;
        }

        db.setMembership(playerId, toTeamId);
        membershipCache.put(playerId, toTeamId);
        return SwapResult.OK;
    }

    public Optional<Location> getTeamSpawn(String teamId) {
        return Optional.ofNullable(spawnCache.get(teamId));
    }

    public void setTeamSpawn(String teamId, Location spawn) {
        db.upsertSpawn(teamId, spawn);
        spawnCache.put(teamId, spawn);
    }

    public boolean createTeam(Team team) {
        boolean success = db.upsertTeam(team);
        if (success) teamCache.put(team.id(), team);
        return success;
    }

    public boolean deleteTeam(String teamId) {
        boolean success = db.deleteTeam(teamId);
        if (success) {
            teamCache.remove(teamId);
            membershipCache.entrySet().removeIf(e -> e.getValue().equals(teamId));
            spawnCache.remove(teamId);
        }
        return success;
    }

    public void resetAllTeams() {
        db.clearAllMemberships();
        membershipCache.clear();
    }

    public long countTeamMembers(String teamId) {
        return membershipCache.values().stream()
                .filter(teamId::equals)
                .count();
    }
}

enum JoinResult { OK, TEAM_NOT_FOUND, TEAM_FULL, ALREADY_IN_TEAM, ERROR }
enum LeaveResult { OK, NOT_IN_TEAM, ERROR }
enum SwapResult { OK, NOT_IN_TEAM, TEAM_NOT_FOUND, TEAM_FULL, ERROR }
