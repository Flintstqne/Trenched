package org.flintstqne.entrenched.RegionLogic;

/**
 * Actions that generate influence points.
 */
public enum InfluenceAction {
    // ==================== ENEMY REGION ACTIONS ====================

    /**
     * Kill an enemy player in the region.
     */
    KILL_ENEMY(50, "kill-points", true),

    /**
     * Kill the same player again (reduced points).
     */
    KILL_ENEMY_REPEAT(25, "kill-same-player-reduction", true),

    /**
     * Place a team banner.
     */
    PLACE_BANNER(25, "banner-place", false),

    /**
     * Remove an enemy banner.
     */
    REMOVE_ENEMY_BANNER(15, "banner-remove-enemy", true),

    /**
     * Destroy enemy player-placed blocks.
     */
    DESTROY_ENEMY_BLOCK(2, "structure-destroy-per-block", true),

    /**
     * Mine enemy player-placed blocks.
     */
    MINE_ENEMY_BLOCK(1, "mine-enemy-blocks", true),

    // ==================== NEUTRAL REGION ACTIONS ====================

    /**
     * Place defensive blocks (walls, fences).
     */
    PLACE_DEFENSIVE_BLOCK(2, "defensive-block", false),

    /**
     * Place a workstation (crafting table, furnace, etc).
     */
    PLACE_WORKSTATION(15, "workstation", false),

    /**
     * Place a torch.
     */
    PLACE_TORCH(1, "torch", false),

    /**
     * Kill a hostile mob.
     */
    KILL_MOB(5, "mob-kill", false),

    /**
     * Build a structure (evaluated periodically).
     */
    BUILD_STRUCTURE(5, "structure-base-points", false),

    // ==================== OBJECTIVE COMPLETIONS ====================

    /**
     * Complete a raid objective (enemy region).
     */
    COMPLETE_RAID_OBJECTIVE(150, "raid-objective", true),

    /**
     * Complete a settlement objective (neutral region).
     */
    COMPLETE_SETTLEMENT_OBJECTIVE(100, "settlement-objective", false);

    private final int defaultPoints;
    private final String configKey;
    private final boolean enemyRegionOnly;

    InfluenceAction(int defaultPoints, String configKey, boolean enemyRegionOnly) {
        this.defaultPoints = defaultPoints;
        this.configKey = configKey;
        this.enemyRegionOnly = enemyRegionOnly;
    }

    public int getDefaultPoints() {
        return defaultPoints;
    }

    public String getConfigKey() {
        return configKey;
    }

    /**
     * Whether this action only applies in enemy regions.
     */
    public boolean isEnemyRegionOnly() {
        return enemyRegionOnly;
    }

    /**
     * Whether this action applies in neutral regions.
     */
    public boolean isNeutralRegionAction() {
        return !enemyRegionOnly;
    }
}

