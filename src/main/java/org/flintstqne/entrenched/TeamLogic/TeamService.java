package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.Location;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TeamService {
    Optional<Team> getTeam(String teamId);
    Collection<Team> listTeams();

    Optional<String> getPlayerTeam(UUID playerId);

    JoinResult joinTeam(UUID playerId, String teamId, JoinReason reason);
    LeaveResult leaveTeam(UUID playerId);

    SwapResult swapTeam(UUID playerId, String toTeamId);

    Optional<Location> getTeamSpawn(String teamId);
    void setTeamSpawn(String teamId, Location spawn);

    boolean createTeam(Team team);
    boolean deleteTeam(String teamId);

    long countTeamMembers(String teamId);

    void resetAllTeams();
}

enum JoinResult { OK, TEAM_NOT_FOUND, TEAM_FULL, ALREADY_IN_TEAM, ERROR }
enum LeaveResult { OK, NOT_IN_TEAM, ERROR }
 enum SwapResult { OK, NOT_IN_TEAM, TEAM_NOT_FOUND, TEAM_FULL, ERROR }

