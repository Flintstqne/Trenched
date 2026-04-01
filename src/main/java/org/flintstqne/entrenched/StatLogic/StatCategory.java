package org.flintstqne.entrenched.StatLogic;

/**
 * All tracked stat categories organized by group.
 * 40 stats across 5 groups: Combat, Territory, Objective, Building, Participation
 */
public enum StatCategory {
    // === COMBAT STATS (12) ===
    KILLS("kills", "Kills", StatGroup.COMBAT),
    DEATHS("deaths", "Deaths", StatGroup.COMBAT),
    ASSISTS("assists", "Assists", StatGroup.COMBAT),
    KILL_STREAK_BEST("kill_streak_best", "Best Kill Streak", StatGroup.COMBAT),
    KILL_STREAK_CURRENT("kill_streak_current", "Current Kill Streak", StatGroup.COMBAT),
    COMMANDER_KILLS("commander_kills", "Commander Kills", StatGroup.COMBAT),
    REVENGE_KILLS("revenge_kills", "Revenge Kills", StatGroup.COMBAT),
    DAMAGE_DEALT("damage_dealt", "Damage Dealt", StatGroup.COMBAT),
    DAMAGE_TAKEN("damage_taken", "Damage Taken", StatGroup.COMBAT),
    BULLETS_SHOT("bullets_shot", "Bullets Shot", StatGroup.COMBAT),
    POTIONS_USED("potions_used", "Potions Used", StatGroup.COMBAT),
    HEALING_POTIONS_USED("healing_potions_used", "Healing Potions Used", StatGroup.COMBAT),

    // === TERRITORY STATS (6) ===
    REGIONS_CAPTURED("regions_captured", "Regions Captured", StatGroup.TERRITORY),
    REGIONS_CONTESTED("regions_contested", "Regions Contested", StatGroup.TERRITORY),
    REGIONS_DEFENDED("regions_defended", "Regions Defended", StatGroup.TERRITORY),
    IP_EARNED("ip_earned", "IP Earned", StatGroup.TERRITORY),
    IP_DENIED("ip_denied", "IP Denied", StatGroup.TERRITORY),
    TIME_IN_ENEMY_TERRITORY("time_in_enemy_territory", "Time in Enemy Territory (min)", StatGroup.TERRITORY),

    // === OBJECTIVE STATS (11) ===
    OBJECTIVES_COMPLETED("objectives_completed", "Objectives Completed", StatGroup.OBJECTIVE),
    OBJECTIVES_SETTLEMENT("objectives_settlement", "Settlement Objectives", StatGroup.OBJECTIVE),
    OBJECTIVES_RAID("objectives_raid", "Raid Objectives", StatGroup.OBJECTIVE),
    INTEL_CAPTURED("intel_captured", "Intel Captured", StatGroup.OBJECTIVE),
    INTEL_RECOVERED("intel_recovered", "Intel Recovered", StatGroup.OBJECTIVE),
    TNT_PLANTED("tnt_planted", "TNT Planted", StatGroup.OBJECTIVE),
    TNT_DEFUSED("tnt_defused", "TNT Defused", StatGroup.OBJECTIVE),
    SUPPLY_CACHES_DESTROYED("supply_caches_destroyed", "Supply Caches Destroyed", StatGroup.OBJECTIVE),
    HOLD_GROUND_WINS("hold_ground_wins", "Hold Ground Wins", StatGroup.OBJECTIVE),
    HOLD_GROUND_DEFENDS("hold_ground_defends", "Hold Ground Defends", StatGroup.OBJECTIVE),
    RESOURCE_DEPOTS_ESTABLISHED("resource_depots_established", "Resource Depots Established", StatGroup.OBJECTIVE),

    // === BUILDING & LOGISTICS STATS (14) ===
    BUILDINGS_CONSTRUCTED("buildings_constructed", "Buildings Constructed", StatGroup.BUILDING),
    BUILDINGS_DESTROYED("buildings_destroyed", "Buildings Destroyed", StatGroup.BUILDING),
    OUTPOSTS_BUILT("outposts_built", "Outposts Built", StatGroup.BUILDING),
    WATCHTOWERS_BUILT("watchtowers_built", "Watchtowers Built", StatGroup.BUILDING),
    GARRISONS_BUILT("garrisons_built", "Garrisons Built", StatGroup.BUILDING),
    DEPOTS_PLACED("depots_placed", "Division Depots Placed", StatGroup.BUILDING),
    DEPOTS_RAIDED("depots_raided", "Depots Raided", StatGroup.BUILDING),
    DEPOT_LOOT_VALUE("depot_loot_value", "Depot Loot Value", StatGroup.BUILDING),
    ROADS_BUILT("roads_built", "Road Segments Built", StatGroup.BUILDING),
    ROADS_DAMAGED("roads_damaged", "Roads Damaged", StatGroup.BUILDING),
    BANNERS_PLACED("banners_placed", "Banners Placed", StatGroup.BUILDING),
    CONTAINERS_STOCKED("containers_stocked", "Containers Stocked", StatGroup.BUILDING),
    BLOCKS_BROKEN("blocks_broken", "Blocks Broken", StatGroup.BUILDING),
    BLOCKS_PLACED("blocks_placed", "Blocks Placed", StatGroup.BUILDING),

    // === PARTICIPATION STATS (8) ===
    ROUNDS_PLAYED("rounds_played", "Rounds Played", StatGroup.PARTICIPATION),
    ROUNDS_WON("rounds_won", "Rounds Won", StatGroup.PARTICIPATION),
    ROUNDS_MVP("rounds_mvp", "Round MVPs", StatGroup.PARTICIPATION),
    TIME_PLAYED("time_played", "Time Played (min)", StatGroup.PARTICIPATION),
    LOGIN_STREAK("login_streak", "Login Streak", StatGroup.PARTICIPATION),
    LOGIN_STREAK_BEST("login_streak_best", "Best Login Streak", StatGroup.PARTICIPATION),
    FIRST_BLOOD("first_blood", "First Bloods", StatGroup.PARTICIPATION),
    LAST_LOGIN("last_login", "Last Login", StatGroup.PARTICIPATION);

    private final String key;
    private final String displayName;
    private final StatGroup group;

    StatCategory(String key, String displayName, StatGroup group) {
        this.key = key;
        this.displayName = displayName;
        this.group = group;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public StatGroup getGroup() {
        return group;
    }

    /**
     * Find category by key string.
     */
    public static StatCategory fromKey(String key) {
        for (StatCategory cat : values()) {
            if (cat.key.equalsIgnoreCase(key)) {
                return cat;
            }
        }
        return null;
    }

    /**
     * Check if this stat is a counter (incremented) vs a value (set directly).
     */
    public boolean isCounter() {
        return this != KILL_STREAK_BEST && this != KILL_STREAK_CURRENT &&
               this != LOGIN_STREAK && this != LOGIN_STREAK_BEST && this != LAST_LOGIN;
    }

    /**
     * Check if this stat should be shown on leaderboards.
     */
    public boolean isLeaderboardStat() {
        return this != KILL_STREAK_CURRENT && this != LAST_LOGIN && this != LOGIN_STREAK;
    }

    /**
     * Get all categories in a specific group.
     */
    public static StatCategory[] getByGroup(StatGroup group) {
        return java.util.Arrays.stream(values())
                .filter(c -> c.group == group)
                .toArray(StatCategory[]::new);
    }

    public enum StatGroup {
        COMBAT("Combat", "⚔"),
        TERRITORY("Territory", "🏴"),
        OBJECTIVE("Objectives", "🎯"),
        BUILDING("Building & Logistics", "🏗"),
        PARTICIPATION("Participation", "📊");

        private final String displayName;
        private final String icon;

        StatGroup(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIcon() {
            return icon;
        }
    }
}

