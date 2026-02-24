package org.flintstqne.entrenched.ObjectiveLogic;

/**
 * Types of objectives that can spawn in regions.
 * Split into RAID (enemy regions) and SETTLEMENT (neutral regions) categories.
 */
public enum ObjectiveType {

    // ==================== RAID OBJECTIVES (Enemy Regions) ====================

    /**
     * Find and destroy a hidden supply cache (chest with supplies).
     * 150 IP reward.
     */
    RAID_DESTROY_CACHE("Destroy Supply Cache", 150, ObjectiveCategory.RAID,
            "Find and destroy the enemy supply cache"),

    /**
     * Kill the designated "commander" player (highest kills in region).
     * 200 IP reward.
     */
    RAID_ASSASSINATE_COMMANDER("Assassinate Commander", 200, ObjectiveCategory.RAID,
            "Eliminate the enemy commander"),

    /**
     * Destroy 50+ blocks of walls/fortifications.
     * 100 IP reward.
     */
    RAID_SABOTAGE_DEFENSES("Sabotage Defenses", 100, ObjectiveCategory.RAID,
            "Destroy enemy fortifications (0/50 blocks)"),

    /**
     * Place TNT at a marked location and defend for 30 seconds.
     * 175 IP reward.
     */
    RAID_PLANT_EXPLOSIVE("Plant Explosive", 175, ObjectiveCategory.RAID,
            "Plant explosives at the target location"),

    /**
     * Retrieve an intel item from enemy base and return to your territory.
     * 125 IP reward.
     */
    RAID_CAPTURE_INTEL("Capture Intel", 125, ObjectiveCategory.RAID,
            "Steal intel and return to friendly territory"),

    /**
     * Stay in region center for 60 seconds while contested.
     * 100 IP reward.
     */
    RAID_HOLD_GROUND("Hold Ground", 100, ObjectiveCategory.RAID,
            "Hold the region center for 60 seconds"),

    // ==================== SETTLEMENT OBJECTIVES (Neutral Regions) ====================

    /**
     * Build a structure with bed, chest, and crafting table.
     * 200 IP reward.
     */
    SETTLEMENT_ESTABLISH_OUTPOST("Establish Outpost", 200, ObjectiveCategory.SETTLEMENT,
            "Build a structure with bed, chest, and crafting table"),

    /**
     * Build 100+ blocks of walls.
     * 150 IP reward.
     */
    SETTLEMENT_SECURE_PERIMETER("Secure Perimeter", 150, ObjectiveCategory.SETTLEMENT,
            "Build defensive walls (0/100 blocks)"),

    /**
     * Place 64+ path/road blocks near a border with friendly-owned territory.
     * 125 IP reward.
     */
    SETTLEMENT_SUPPLY_ROUTE("Build Supply Route", 125, ObjectiveCategory.SETTLEMENT,
            "Build road near friendly territory border (0/64 blocks)"),

    /**
     * Build a structure 15+ blocks tall with line of sight.
     * 100 IP reward.
     */
    SETTLEMENT_WATCHTOWER("Build Watchtower", 100, ObjectiveCategory.SETTLEMENT,
            "Build a watchtower (15+ blocks tall)"),

    /**
     * Place 4+ storage containers with 100+ items total.
     * 150 IP reward.
     */
    SETTLEMENT_RESOURCE_DEPOT("Establish Resource Depot", 150, ObjectiveCategory.SETTLEMENT,
            "Create a storage depot (4+ containers, 100+ items)"),

    /**
     * Build an enclosed room with 3+ beds.
     * 175 IP reward.
     */
    SETTLEMENT_GARRISON_QUARTERS("Build Garrison Quarters", 175, ObjectiveCategory.SETTLEMENT,
            "Build barracks with 3+ beds");

    private final String displayName;
    private final int influenceReward;
    private final ObjectiveCategory category;
    private final String description;

    ObjectiveType(String displayName, int influenceReward, ObjectiveCategory category, String description) {
        this.displayName = displayName;
        this.influenceReward = influenceReward;
        this.category = category;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getInfluenceReward() {
        return influenceReward;
    }

    public ObjectiveCategory getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public boolean isRaid() {
        return category == ObjectiveCategory.RAID;
    }

    public boolean isSettlement() {
        return category == ObjectiveCategory.SETTLEMENT;
    }

    /**
     * Gets all raid objective types.
     */
    public static ObjectiveType[] getRaidObjectives() {
        return new ObjectiveType[] {
            RAID_DESTROY_CACHE,
            RAID_ASSASSINATE_COMMANDER,
            RAID_SABOTAGE_DEFENSES,
            RAID_PLANT_EXPLOSIVE,
            RAID_CAPTURE_INTEL,
            RAID_HOLD_GROUND
        };
    }

    /**
     * Gets all settlement objective types.
     */
    public static ObjectiveType[] getSettlementObjectives() {
        return new ObjectiveType[] {
            SETTLEMENT_ESTABLISH_OUTPOST,
            SETTLEMENT_SECURE_PERIMETER,
            SETTLEMENT_SUPPLY_ROUTE,
            SETTLEMENT_WATCHTOWER,
            SETTLEMENT_RESOURCE_DEPOT,
            SETTLEMENT_GARRISON_QUARTERS
        };
    }
}

