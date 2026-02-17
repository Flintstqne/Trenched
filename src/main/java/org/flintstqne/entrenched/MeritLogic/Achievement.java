package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.ChatColor;

/**
 * One-time achievements that award merit tokens.
 * Each achievement can only be earned once per player.
 */
public enum Achievement {
    // ==================== COMBAT ACHIEVEMENTS ====================
    FIRST_KILL("First Blood", "Get your first kill", 1, "combat", ChatColor.RED),
    KILL_10("Soldier", "Kill 10 enemies", 2, "combat", ChatColor.RED),
    KILL_50("Veteran", "Kill 50 enemies", 5, "combat", ChatColor.RED),
    KILL_100("Centurion", "Kill 100 enemies", 10, "combat", ChatColor.RED),
    KILL_500("War Hero", "Kill 500 enemies", 25, "combat", ChatColor.DARK_RED),
    STREAK_5("On a Roll", "Get a 5 kill streak", 2, "combat", ChatColor.RED),
    STREAK_10("Unstoppable", "Get a 10 kill streak", 5, "combat", ChatColor.RED),
    STREAK_15("Rampage", "Get a 15 kill streak", 10, "combat", ChatColor.DARK_RED),
    SHUTDOWN_STREAK("Party Pooper", "End an enemy's 5+ kill streak", 2, "combat", ChatColor.RED),

    // ==================== TERRITORY ACHIEVEMENTS ====================
    FIRST_CAPTURE("Conqueror", "Capture your first region", 2, "territory", ChatColor.GOLD),
    CAPTURE_5("Expansionist", "Capture 5 regions", 5, "territory", ChatColor.GOLD),
    CAPTURE_10("Empire Builder", "Capture 10 regions", 10, "territory", ChatColor.GOLD),
    CAPTURE_25("Dominator", "Capture 25 regions", 25, "territory", ChatColor.YELLOW),
    DEFEND_REGION("Defender", "Successfully defend a region", 2, "territory", ChatColor.GOLD),
    DEFEND_5("Guardian", "Defend 5 regions", 5, "territory", ChatColor.GOLD),
    TOP_CONTRIBUTOR("MVP", "Be the top contributor in a capture", 3, "territory", ChatColor.YELLOW),

    // ==================== LOGISTICS ACHIEVEMENTS ====================
    FIRST_ROAD("Road Builder", "Place your first road block", 1, "logistics", ChatColor.GRAY),
    ROAD_100("Highway Engineer", "Place 100 road blocks", 3, "logistics", ChatColor.GRAY),
    ROAD_500("Infrastructure Master", "Place 500 road blocks", 10, "logistics", ChatColor.GRAY),
    ROAD_1000("Logistics Legend", "Place 1000 road blocks", 20, "logistics", ChatColor.WHITE),
    SUPPLY_REGION("Supply Sergeant", "Fully supply a region", 2, "logistics", ChatColor.GRAY),
    SUPPLY_ROUTE("Supply Chain", "Complete a supply route", 5, "logistics", ChatColor.GRAY),
    DISRUPT_SUPPLY("Saboteur", "Disrupt enemy supply lines", 3, "logistics", ChatColor.DARK_GRAY),
    MAJOR_SABOTAGE("Master Saboteur", "Disrupt 3+ regions at once", 10, "logistics", ChatColor.DARK_GRAY),

    // ==================== SOCIAL ACHIEVEMENTS ====================
    FIRST_DIVISION("Enlisted", "Join a division", 1, "social", ChatColor.GREEN),
    CREATE_DIVISION("Commander", "Create a division", 5, "social", ChatColor.GREEN),
    FIRST_PARTY("Party Up", "Join a party", 1, "social", ChatColor.GREEN),
    GIVE_MERIT("Generous", "Give merit to another player", 1, "social", ChatColor.GREEN),
    GIVE_10_MERITS("Philanthropist", "Give 10 merits to others", 3, "social", ChatColor.GREEN),
    RECEIVE_MERIT("Recognized", "Receive merit from another player", 1, "social", ChatColor.GREEN),
    RECEIVE_10_MERITS("Celebrated", "Receive 10 merits from others", 3, "social", ChatColor.GREEN),

    // ==================== PROGRESSION ACHIEVEMENTS ====================
    RANK_CORPORAL("Promoted: Corporal", "Reach the rank of Corporal", 2, "progression", ChatColor.WHITE),
    RANK_SERGEANT("Promoted: Sergeant", "Reach the rank of Sergeant", 3, "progression", ChatColor.GRAY),
    RANK_STAFF_SERGEANT("Promoted: Staff Sergeant", "Reach the rank of Staff Sergeant", 5, "progression", ChatColor.DARK_GRAY),
    RANK_LIEUTENANT("Promoted: Lieutenant", "Reach the rank of Lieutenant", 10, "progression", ChatColor.AQUA),
    RANK_CAPTAIN("Promoted: Captain", "Reach the rank of Captain", 15, "progression", ChatColor.DARK_AQUA),
    RANK_MAJOR("Promoted: Major", "Reach the rank of Major", 20, "progression", ChatColor.BLUE),
    RANK_COLONEL("Promoted: Colonel", "Reach the rank of Colonel", 30, "progression", ChatColor.DARK_BLUE),
    RANK_GENERAL("Promoted: General", "Reach the rank of General", 50, "progression", ChatColor.GOLD),

    // ==================== TIME-BASED ACHIEVEMENTS ====================
    FIRST_LOGIN("Welcome", "Log in for the first time", 1, "time", ChatColor.LIGHT_PURPLE),
    PLAY_1_HOUR("Getting Started", "Play for 1 hour", 1, "time", ChatColor.LIGHT_PURPLE),
    PLAY_10_HOURS("Regular", "Play for 10 hours", 5, "time", ChatColor.LIGHT_PURPLE),
    PLAY_50_HOURS("Dedicated", "Play for 50 hours", 15, "time", ChatColor.LIGHT_PURPLE),
    PLAY_100_HOURS("Hardcore", "Play for 100 hours", 30, "time", ChatColor.DARK_PURPLE),
    LOGIN_STREAK_7("Weekly Warrior", "Log in 7 days in a row", 5, "time", ChatColor.LIGHT_PURPLE),
    LOGIN_STREAK_30("Monthly Master", "Log in 30 days in a row", 20, "time", ChatColor.DARK_PURPLE),

    // ==================== ROUND ACHIEVEMENTS ====================
    WIN_ROUND("Victor", "Be on the winning team", 5, "round", ChatColor.YELLOW),
    WIN_5_ROUNDS("Champion", "Win 5 rounds", 15, "round", ChatColor.YELLOW),
    COMPLETE_ROUND("Participant", "Complete a full round", 2, "round", ChatColor.YELLOW),
    TOP_ROUND_IP("Round MVP", "Have the most IP in a round", 10, "round", ChatColor.GOLD);

    private final String displayName;
    private final String description;
    private final int tokenReward;
    private final String category;
    private final ChatColor color;

    Achievement(String displayName, String description, int tokenReward, String category, ChatColor color) {
        this.displayName = displayName;
        this.description = description;
        this.tokenReward = tokenReward;
        this.category = category;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public int getTokenReward() {
        return tokenReward;
    }

    public String getCategory() {
        return category;
    }

    public ChatColor getColor() {
        return color;
    }

    /**
     * Gets the formatted display with color.
     */
    public String getFormatted() {
        return color + displayName;
    }

    /**
     * Gets achievements by category.
     */
    public static Achievement[] getByCategory(String category) {
        return java.util.Arrays.stream(values())
                .filter(a -> a.category.equals(category))
                .toArray(Achievement[]::new);
    }

    /**
     * Gets all unique categories.
     */
    public static String[] getCategories() {
        return java.util.Arrays.stream(values())
                .map(Achievement::getCategory)
                .distinct()
                .toArray(String[]::new);
    }
}

