package org.flintstqne.entrenched.ObjectiveLogic;

/**
 * Status of an objective.
 */
public enum ObjectiveStatus {
    /**
     * Objective is active and can be completed.
     */
    ACTIVE,

    /**
     * Objective has been completed by a team.
     */
    COMPLETED,

    /**
     * Objective has expired (region captured or time limit reached).
     */
    EXPIRED
}

