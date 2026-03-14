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
    SPECIALIST("Specialist", "SPC", 45, ChatColor.WHITE),
    CORPORAL("Corporal", "CPL", 70, ChatColor.GREEN),
    SERGEANT("Sergeant", "SGT", 100, ChatColor.GREEN),
    STAFF_SERGEANT("Staff Sergeant", "SSG", 150, ChatColor.DARK_GREEN),
    SERGEANT_FIRST_CLASS("Sergeant First Class", "SFC", 210, ChatColor.DARK_GREEN),
    MASTER_SERGEANT("Master Sergeant", "MSG", 280, ChatColor.DARK_GREEN),
    FIRST_SERGEANT("First Sergeant", "1SG", 350, ChatColor.DARK_GREEN),
    SERGEANT_MAJOR("Sergeant Major", "SGM", 430, ChatColor.DARK_GREEN),
    COMMAND_SERGEANT_MAJOR("Command Sergeant Major", "CSM", 520, ChatColor.DARK_GREEN),

    // Warrant Officer Ranks
    WARRANT_OFFICER_1("Warrant Officer 1", "WO1", 625, ChatColor.DARK_AQUA),
    CHIEF_WARRANT_OFFICER_2("Chief Warrant Officer 2", "CW2", 750, ChatColor.DARK_AQUA),
    CHIEF_WARRANT_OFFICER_3("Chief Warrant Officer 3", "CW3", 900, ChatColor.DARK_AQUA),
    CHIEF_WARRANT_OFFICER_4("Chief Warrant Officer 4", "CW4", 1075, ChatColor.DARK_AQUA),
    CHIEF_WARRANT_OFFICER_5("Chief Warrant Officer 5", "CW5", 1275, ChatColor.DARK_AQUA),

    // Officer Ranks
    CADET("Cadet", "CDT", 1500, ChatColor.AQUA),
    SECOND_LIEUTENANT("Second Lieutenant", "2LT", 1750, ChatColor.AQUA),
    FIRST_LIEUTENANT("First Lieutenant", "1LT", 2050, ChatColor.AQUA),
    CAPTAIN("Captain", "CPT", 2400, ChatColor.DARK_AQUA),
    MAJOR("Major", "MAJ", 2850, ChatColor.BLUE),
    LIEUTENANT_COLONEL("Lieutenant Colonel", "LTC", 3400, ChatColor.BLUE),
    COLONEL("Colonel", "COL", 4100, ChatColor.DARK_BLUE),

    // General Ranks
    BRIGADIER_GENERAL("Brigadier General", "BG", 5000, ChatColor.GOLD),
    MAJOR_GENERAL("Major General", "MG", 6500, ChatColor.GOLD),
    LIEUTENANT_GENERAL("Lieutenant General", "LTG", 8500, ChatColor.YELLOW),
    GENERAL("General", "GEN", 11000, ChatColor.YELLOW),
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
     * Checks if this rank is an officer rank (CDT and above).
     */
    public boolean isOfficer() {
        return this.ordinal() >= CADET.ordinal();
    }

    /**
     * Checks if this rank is a general rank (BG and above).
     */
    public boolean isGeneral() {
        return this.ordinal() >= BRIGADIER_GENERAL.ordinal();
    }

    /**
     * Checks if this rank is a warrant officer (WO1 through CW5).
     */
    public boolean isWarrantOfficer() {
        return this.ordinal() >= WARRANT_OFFICER_1.ordinal() && this.ordinal() <= CHIEF_WARRANT_OFFICER_5.ordinal();
    }

    /**
     * Checks if this rank is NCO (SGT through CSM).
     */
    public boolean isNCO() {
        return this.ordinal() >= SERGEANT.ordinal() && this.ordinal() <= COMMAND_SERGEANT_MAJOR.ordinal();
    }
}

