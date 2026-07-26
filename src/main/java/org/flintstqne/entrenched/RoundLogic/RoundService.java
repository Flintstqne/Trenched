package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoundService {

    private final RoundDb db;

    public RoundService(RoundDb db) {
        this.db = db;
    }

    public Optional<Round> getCurrentRound() {
        return db.getCurrentRound();
    }

    public Round startNewRound(long worldSeed) {
        int roundId = db.createRound(worldSeed);
        db.updateRoundStatus(roundId, Round.RoundStatus.ACTIVE);
        return db.getRound(roundId).orElseThrow();
    }

    public void setWorldName(int roundId, String worldName) {
        db.setWorldName(roundId, worldName);
    }

    /**
     * Gets the current game world for the active round.
     * Falls back to default world name from config if no world name is stored.
     */
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

    /**
     * Sets the phase directly for the current round (admin command).
     * @param phase The target phase number
     * @return true if the phase was set successfully
     */
    public boolean setPhase(int phase) {
        Optional<Round> currentOpt = getCurrentRound();
        if (currentOpt.isEmpty()) return false;
        Round current = currentOpt.get();
        if (current.status() == Round.RoundStatus.COMPLETED) return false;
        db.updatePhase(current.roundId(), phase);
        return true;
    }

    public void endRound(String winningTeam) {
        getCurrentRound().ifPresent(round ->
                db.completeRound(round.roundId(), winningTeam)
        );
    }

    public Map<String, String> getRegionNames(int roundId) {
        return db.loadRegionNames(roundId);
    }

    public void setRegionNames(int roundId, Map<String, String> names) {
        db.saveRegionNames(roundId, names);
    }

    public boolean isRoundActive() {
        return getCurrentRound()
                .map(r -> r.status() == Round.RoundStatus.ACTIVE)
                .orElse(false);
    }

    public Optional<Round> getRound(int roundId) {
        return db.getRound(roundId);
    }

    public List<Round> getRoundHistory() {
        return db.getRoundHistory();
    }

    public enum PhaseResult {
        ADVANCED,
        ROUND_ENDED,
        NO_ACTIVE_ROUND,
        ALREADY_ENDED
    }
}
