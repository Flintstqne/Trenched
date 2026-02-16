package org.flintstqne.entrenched.RoundLogic;

public record Round(
        int roundId,
        long startTime,
        long endTime,
        int currentPhase,
        long worldSeed,
        String worldName,
        RoundStatus status,
        String winningTeam
) {
    public enum RoundStatus {
        PENDING,
        ACTIVE,
        COMPLETED
    }

    public long phaseEndTime() {
        long phaseDuration = 7 * 24 * 60 * 60 * 1000L; // 1 week in ms
        return startTime + (currentPhase * phaseDuration);
    }

    public long timeUntilPhaseEnd() {
        return phaseEndTime() - System.currentTimeMillis();
    }

    public boolean isPhaseExpired() {
        return System.currentTimeMillis() >= phaseEndTime();
    }
}
