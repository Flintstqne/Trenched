package org.flintstqne.entrenched.RegionLogic;

/**
 * Represents a region's current status including ownership and influence.
 */
public record RegionStatus(
        String regionId,           // "A1", "B2", etc.
        int roundId,
        String ownerTeam,          // "red", "blue", or null for neutral
        RegionState state,
        double redInfluence,       // Red team's IP
        double blueInfluence,      // Blue team's IP
        Long fortifiedUntil,       // Timestamp when fortification ends (null if not fortified)
        Long ownedSince,           // Timestamp when captured (null if neutral)
        int timesCaptured          // How many times this region has changed hands
) {
    /**
     * Gets the influence required to capture this region.
     */
    public double getInfluenceRequired(double neutralThreshold, double enemyThreshold) {
        if (state == RegionState.NEUTRAL) {
            return neutralThreshold;
        }
        return enemyThreshold;
    }

    /**
     * Gets the current influence for a team.
     */
    public double getInfluence(String team) {
        if ("red".equalsIgnoreCase(team)) {
            return redInfluence;
        } else if ("blue".equalsIgnoreCase(team)) {
            return blueInfluence;
        }
        return 0;
    }

    /**
     * Gets the opposing team.
     */
    public String getOpposingTeam(String team) {
        if ("red".equalsIgnoreCase(team)) {
            return "blue";
        } else if ("blue".equalsIgnoreCase(team)) {
            return "red";
        }
        return null;
    }

    /**
     * Checks if the region is currently fortified.
     */
    public boolean isFortified() {
        return state == RegionState.FORTIFIED &&
               fortifiedUntil != null &&
               System.currentTimeMillis() < fortifiedUntil;
    }

    /**
     * Checks if a team owns this region.
     */
    public boolean isOwnedBy(String team) {
        return ownerTeam != null && ownerTeam.equalsIgnoreCase(team) && state.isControlled();
    }

    /**
     * Creates a copy with updated influence values.
     */
    public RegionStatus withInfluence(double newRedInfluence, double newBlueInfluence) {
        return new RegionStatus(
                regionId, roundId, ownerTeam, state,
                newRedInfluence, newBlueInfluence,
                fortifiedUntil, ownedSince, timesCaptured
        );
    }

    /**
     * Creates a copy with updated state.
     */
    public RegionStatus withState(RegionState newState) {
        return new RegionStatus(
                regionId, roundId, ownerTeam, newState,
                redInfluence, blueInfluence,
                fortifiedUntil, ownedSince, timesCaptured
        );
    }

    /**
     * Creates a copy with new owner.
     */
    public RegionStatus withOwner(String newOwner, RegionState newState, Long newFortifiedUntil) {
        return new RegionStatus(
                regionId, roundId, newOwner, newState,
                0, 0, // Reset influence on capture
                newFortifiedUntil, System.currentTimeMillis(),
                timesCaptured + 1
        );
    }
}

