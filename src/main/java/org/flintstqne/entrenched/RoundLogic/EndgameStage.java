package org.flintstqne.entrenched.RoundLogic;

/**
 * Represents the current stage of the endgame evaluation.
 */
public enum EndgameStage {
    /**
     * Normal gameplay - no special endgame conditions active.
     */
    NORMAL,

    /**
     * A team has reached the early-win threshold (10/14 regions, +4 lead)
     * and is holding for 30 minutes to win early.
     */
    EARLY_WIN_HOLD,

    /**
     * Regulation ended in a tie - overtime is active with a single target region.
     */
    OVERTIME
}

