package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RoundService {

    Optional<Round> getCurrentRound();

    Round startNewRound(long worldSeed);

    void setWorldName(int roundId, String worldName);

    /**
     * Gets the current game world for the active round.
     * Falls back to default world name from config if no world name is stored.
     */
    Optional<World> getGameWorld();

    PhaseResult advancePhase();

    void endRound(String winningTeam);

    Map<String, String> getRegionNames(int roundId);

    void setRegionNames(int roundId, Map<String, String> names);

    boolean isRoundActive();

    Optional<Round> getRound(int roundId);

    List<Round> getRoundHistory();

    enum PhaseResult {
        ADVANCED,
        ROUND_ENDED,
        NO_ACTIVE_ROUND,
        ALREADY_ENDED
    }
}
