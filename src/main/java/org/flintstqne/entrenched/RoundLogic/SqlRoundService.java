package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SqlRoundService implements RoundService {

    private final RoundDb db;

    public SqlRoundService(RoundDb db) {
        this.db = db;
    }

    @Override
    public Optional<Round> getCurrentRound() {
        return db.getCurrentRound();
    }

    @Override
    public Round startNewRound(long worldSeed) {
        int roundId = db.createRound(worldSeed);
        db.updateRoundStatus(roundId, Round.RoundStatus.ACTIVE);
        return db.getRound(roundId).orElseThrow();
    }

    @Override
    public void setWorldName(int roundId, String worldName) {
        db.setWorldName(roundId, worldName);
    }

    @Override
    public Optional<World> getGameWorld() {
        return getCurrentRound()
                .map(round -> {
                    String worldName = round.worldName();
                    if (worldName != null && !worldName.isEmpty()) {
                        return Bukkit.getWorld(worldName);
                    }
                    // Fallback to default "world" if no world name stored
                    return Bukkit.getWorld("world");
                });
    }

    @Override
    public PhaseResult advancePhase() {
        Optional<Round> currentOpt = getCurrentRound();
        if (currentOpt.isEmpty()) return PhaseResult.NO_ACTIVE_ROUND;

        Round current = currentOpt.get();
        if (current.status() == Round.RoundStatus.COMPLETED) {
            return PhaseResult.ALREADY_ENDED;
        }

        if (current.currentPhase() >= 3) {
            return PhaseResult.ROUND_ENDED;
        }

        db.updatePhase(current.roundId(), current.currentPhase() + 1);
        return PhaseResult.ADVANCED;
    }

    @Override
    public void endRound(String winningTeam) {
        getCurrentRound().ifPresent(round ->
                db.completeRound(round.roundId(), winningTeam)
        );
    }

    @Override
    public Map<String, String> getRegionNames(int roundId) {
        return db.loadRegionNames(roundId);
    }

    @Override
    public void setRegionNames(int roundId, Map<String, String> names) {
        db.saveRegionNames(roundId, names);
    }

    @Override
    public boolean isRoundActive() {
        return getCurrentRound()
                .map(r -> r.status() == Round.RoundStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    public Optional<Round> getRound(int roundId) {
        return db.getRound(roundId);
    }

    @Override
    public List<Round> getRoundHistory() {
        return db.getRoundHistory();
    }
}
