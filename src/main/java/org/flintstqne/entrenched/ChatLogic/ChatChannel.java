package org.flintstqne.entrenched.ChatLogic;

/**
 * Represents the available chat channels.
 */
public enum ChatChannel {
    GENERAL("General", "g", "gen", "general"),
    TEAM("Team", "tc", "teamchat"),
    DIVISION("Division", "dc", "divisionchat"),
    PARTY("Party", "pc", "partychat"),
    REGION("Region", "rc", "regionchat", "local");

    private final String displayName;
    private final String[] aliases;

    ChatChannel(String displayName, String... aliases) {
        this.displayName = displayName;
        this.aliases = aliases;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String[] getAliases() {
        return aliases;
    }

    /**
     * Gets a ChatChannel from a command alias.
     */
    public static ChatChannel fromAlias(String alias) {
        String lower = alias.toLowerCase();
        for (ChatChannel channel : values()) {
            for (String a : channel.aliases) {
                if (a.equalsIgnoreCase(lower)) {
                    return channel;
                }
            }
        }
        return null;
    }
}

