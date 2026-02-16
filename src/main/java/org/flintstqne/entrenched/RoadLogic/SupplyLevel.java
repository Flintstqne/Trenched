package org.flintstqne.entrenched.RoadLogic;

/**
 * Represents the supply status of a region based on road connectivity.
 */
public enum SupplyLevel {
    /**
     * Road is fully connected to home base.
     */
    SUPPLIED(0, 1.0),

    /**
     * Road is damaged but alternative route exists.
     */
    PARTIAL(5, 1.0),

    /**
     * No road connection to home base.
     */
    UNSUPPLIED(15, 0.5),

    /**
     * No owned adjacent regions at all (completely cut off).
     */
    ISOLATED(30, 0.25);

    private final int respawnDelay;
    private final double healthRegenMultiplier;

    SupplyLevel(int respawnDelay, double healthRegenMultiplier) {
        this.respawnDelay = respawnDelay;
        this.healthRegenMultiplier = healthRegenMultiplier;
    }

    /**
     * Gets the additional respawn delay in seconds.
     */
    public int getRespawnDelay() {
        return respawnDelay;
    }

    /**
     * Gets the health regeneration multiplier (1.0 = normal).
     */
    public double getHealthRegenMultiplier() {
        return healthRegenMultiplier;
    }
}

