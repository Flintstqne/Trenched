package org.flintstqne.entrenched.MeritLogic;

/**
 * Categories for earning merit tokens.
 */
public enum MeritTokenSource {
    // Combat sources
    KILL_ENEMY_TERRITORY("Kill in Enemy Territory", "combat", 5),  // Per 5 kills
    KILL_GENERAL("Kill Enemy", "combat", 10),                       // Per 10 kills
    KILL_STREAK_5("5 Kill Streak", "combat", 1),
    KILL_STREAK_10("10 Kill Streak", "combat", 1),
    FIRST_BLOOD("First Blood", "combat", 1),
    SHUTDOWN("Shutdown Kill Streak", "combat", 1),

    // Territory sources
    CAPTURE_NEUTRAL("Capture Neutral Region", "territory", 1),
    CAPTURE_ENEMY("Capture Enemy Region", "territory", 1),
    PARTICIPATE_CAPTURE("Participate in Capture", "territory", 1),
    DEFEND_REGION("Defend Region", "territory", 1),
    MAJOR_CONTRIBUTOR("Major Contributor", "territory", 1),

    // Logistics sources
    COMPLETE_SUPPLY_ROUTE("Complete Supply Route", "logistics", 1),
    ESTABLISH_SUPPLY("Establish Supply", "logistics", 1),
    ROAD_MILESTONE("Road Block Milestone", "logistics", 100),  // Per 100 blocks

    // Construction sources
    MAJOR_FORTIFICATION("Major Fortification", "construction", 100),  // Per 100 blocks
    ESTABLISH_OUTPOST("Establish Outpost", "construction", 1),

    // Sabotage sources
    DISRUPT_SUPPLY("Disrupt Enemy Supply", "sabotage", 1),
    MAJOR_SABOTAGE("Major Sabotage", "sabotage", 1),

    // Time-based sources
    DAILY_LOGIN("Daily Login", "time", 1),
    LOGIN_STREAK_7("7-Day Login Streak", "time", 1),
    LOGIN_STREAK_30("30-Day Login Streak", "time", 1),
    ROUND_COMPLETION("Round Completion", "time", 1),
    ACTIVE_PLAYTIME("Active Playtime", "time", 120),  // Per 2 hours (120 minutes)

    // Achievement (one-time unlocks)
    ACHIEVEMENT("Achievement", "achievement", 1);

    private final String displayName;
    private final String category;
    private final int threshold;  // How many actions needed to earn 1 token (or 1 for single events)

    MeritTokenSource(String displayName, String category, int threshold) {
        this.displayName = displayName;
        this.category = category;
        this.threshold = threshold;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    /**
     * For batched sources (kills, blocks), this is how many actions = 1 token.
     * For single events, this is 1.
     */
    public int getThreshold() {
        return threshold;
    }

    /**
     * Whether this source requires multiple actions to earn a token.
     */
    public boolean isBatched() {
        return threshold > 1;
    }
}

