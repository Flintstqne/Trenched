package org.flintstqne.entrenched.RoundLogic;

/**
 * Persisted state for endgame evaluation.
 * Stored in round_endgame_state table.
 */
public record RoundEndgameState(
        int roundId,
        EndgameStage stage,

        // Early win hold tracking
        String earlyWinTeam,           // Team holding for early win, or null
        Long earlyWinStartedAt,        // Timestamp when early win hold started, or null

        // Overtime tracking
        String overtimeRegionId,       // Target region for overtime, or null
        Long overtimeStartedAt,        // Timestamp when overtime started, or null
        Long overtimeEndsAt,           // Timestamp when overtime expires, or null
        String overtimeHoldTeam,       // Team currently holding overtime region, or null
        Long overtimeHoldStartedAt     // Timestamp when current hold started, or null
) {
    /**
     * Creates a default NORMAL state for a round.
     */
    public static RoundEndgameState createDefault(int roundId) {
        return new RoundEndgameState(
                roundId,
                EndgameStage.NORMAL,
                null, null,
                null, null, null, null, null
        );
    }

    /**
     * Creates a state for early win hold.
     */
    public RoundEndgameState withEarlyWinHold(String team, long startedAt) {
        return new RoundEndgameState(
                roundId,
                EndgameStage.EARLY_WIN_HOLD,
                team, startedAt,
                null, null, null, null, null
        );
    }

    /**
     * Resets to normal state (early win broken).
     */
    public RoundEndgameState resetToNormal() {
        return new RoundEndgameState(
                roundId,
                EndgameStage.NORMAL,
                null, null,
                null, null, null, null, null
        );
    }

    /**
     * Creates a state for overtime.
     */
    public RoundEndgameState withOvertime(String regionId, long startedAt, long endsAt) {
        return new RoundEndgameState(
                roundId,
                EndgameStage.OVERTIME,
                null, null,
                regionId, startedAt, endsAt, null, null
        );
    }

    /**
     * Updates overtime hold tracking.
     */
    public RoundEndgameState withOvertimeHold(String holdTeam, Long holdStartedAt) {
        return new RoundEndgameState(
                roundId,
                stage,
                earlyWinTeam, earlyWinStartedAt,
                overtimeRegionId, overtimeStartedAt, overtimeEndsAt,
                holdTeam, holdStartedAt
        );
    }

    /**
     * Gets remaining early win hold time in seconds, or -1 if not in early win hold.
     */
    public long getEarlyWinHoldRemainingSeconds(long earlyWinDurationMs) {
        if (stage != EndgameStage.EARLY_WIN_HOLD || earlyWinStartedAt == null) {
            return -1;
        }
        long elapsed = System.currentTimeMillis() - earlyWinStartedAt;
        long remaining = earlyWinDurationMs - elapsed;
        return Math.max(0, remaining / 1000);
    }

    /**
     * Gets remaining overtime time in seconds, or -1 if not in overtime.
     */
    public long getOvertimeRemainingSeconds() {
        if (stage != EndgameStage.OVERTIME || overtimeEndsAt == null) {
            return -1;
        }
        long remaining = overtimeEndsAt - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    /**
     * Gets overtime hold progress in seconds, or -1 if no hold active.
     */
    public long getOvertimeHoldSeconds() {
        if (stage != EndgameStage.OVERTIME || overtimeHoldStartedAt == null) {
            return -1;
        }
        return (System.currentTimeMillis() - overtimeHoldStartedAt) / 1000;
    }
}

