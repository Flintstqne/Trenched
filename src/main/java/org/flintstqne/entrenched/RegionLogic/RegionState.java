package org.flintstqne.entrenched.RegionLogic;

/**
 * Represents the current state of a region.
 */
public enum RegionState {
    /**
     * Unclaimed region, can be claimed by either team.
     */
    NEUTRAL,

    /**
     * Belongs to a team, generates supply.
     */
    OWNED,

    /**
     * Being actively attacked by enemy team.
     */
    CONTESTED,

    /**
     * Recently captured, immune to attack for a period.
     */
    FORTIFIED,

    /**
     * Home region, cannot be captured (unless team has <= min regions).
     */
    PROTECTED;

    /**
     * Check if the region can be attacked.
     */
    public boolean canBeAttacked() {
        return this == NEUTRAL || this == OWNED || this == CONTESTED;
    }

    /**
     * Check if the region is controlled by a team.
     */
    public boolean isControlled() {
        return this == OWNED || this == FORTIFIED || this == PROTECTED || this == CONTESTED;
    }
}

