package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.ChatColor;

/**
 * Military-themed ranks based on received merits.
 * Ranks are purely cosmetic - no gameplay advantages.
 */
public enum MeritRank {
    // Enlisted Ranks
    RECRUIT("Recruit", "RCT", 0, ChatColor.GRAY),
    PRIVATE("Private", "PVT", 10, ChatColor.WHITE),
    PRIVATE_FIRST_CLASS("Private First Class", "PFC", 25, ChatColor.WHITE),
    CORPORAL("Corporal", "CPL", 50, ChatColor.GREEN),
    SERGEANT("Sergeant", "SGT", 100, ChatColor.GREEN),
    STAFF_SERGEANT("Staff Sergeant", "SSG", 175, ChatColor.DARK_GREEN),
    MASTER_SERGEANT("Master Sergeant", "MSG", 275, ChatColor.DARK_GREEN),

    // Officer Ranks
    SECOND_LIEUTENANT("Second Lieutenant", "2LT", 400, ChatColor.AQUA),
    FIRST_LIEUTENANT("First Lieutenant", "1LT", 600, ChatColor.AQUA),
    CAPTAIN("Captain", "CPT", 850, ChatColor.DARK_AQUA),
    MAJOR("Major", "MAJ", 1200, ChatColor.BLUE),
    LIEUTENANT_COLONEL("Lieutenant Colonel", "LTC", 1750, ChatColor.BLUE),
    COLONEL("Colonel", "COL", 2500, ChatColor.DARK_BLUE),

    // General Ranks
    BRIGADIER_GENERAL("Brigadier General", "BG", 3500, ChatColor.GOLD),
    MAJOR_GENERAL("Major General", "MG", 5000, ChatColor.GOLD),
    LIEUTENANT_GENERAL("Lieutenant General", "LTG", 7500, ChatColor.YELLOW),
    GENERAL("General", "GEN", 10000, ChatColor.YELLOW),
    GENERAL_OF_THE_ARMY("General of the Army", "GOA", 15000, ChatColor.LIGHT_PURPLE);

    private final String displayName;
    private final String tag;
    private final int meritsRequired;
    private final ChatColor color;

    MeritRank(String displayName, String tag, int meritsRequired, ChatColor color) {
        this.displayName = displayName;
        this.tag = tag;
        this.meritsRequired = meritsRequired;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTag() {
        return tag;
    }

    public String getFormattedTag() {
        return color + "[" + tag + "]" + ChatColor.RESET;
    }

    public int getMeritsRequired() {
        return meritsRequired;
    }

    public ChatColor getColor() {
        return color;
    }

    /**
     * Gets the rank for a given merit count.
     */
    public static MeritRank getRankForMerits(int merits) {
        MeritRank result = RECRUIT;
        for (MeritRank rank : values()) {
            if (merits >= rank.meritsRequired) {
                result = rank;
            } else {
                break;
            }
        }
        return result;
    }

    /**
     * Gets the next rank after this one, or null if this is the highest.
     */
    public MeritRank getNextRank() {
        MeritRank[] ranks = values();
        int nextOrdinal = this.ordinal() + 1;
        if (nextOrdinal < ranks.length) {
            return ranks[nextOrdinal];
        }
        return null;
    }

    /**
     * Gets merits needed to reach the next rank.
     */
    public int getMeritsToNextRank(int currentMerits) {
        MeritRank next = getNextRank();
        if (next == null) {
            return 0; // Already at max rank
        }
        return Math.max(0, next.meritsRequired - currentMerits);
    }

    /**
     * Checks if this rank is an officer rank (2LT and above).
     */
    public boolean isOfficer() {
        return this.ordinal() >= SECOND_LIEUTENANT.ordinal();
    }

    /**
     * Checks if this rank is a general rank (BG and above).
     */
    public boolean isGeneral() {
        return this.ordinal() >= BRIGADIER_GENERAL.ordinal();
    }

    /**
     * Checks if this rank is NCO (SGT through MSG).
     */
    public boolean isNCO() {
        return this.ordinal() >= SERGEANT.ordinal() && this.ordinal() <= MASTER_SERGEANT.ordinal();
    }
}

