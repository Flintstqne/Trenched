package org.flintstqne.entrenched.ChatLogic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages which chat channel each player is currently in.
 */
public final class ChatChannelManager {

    private final Map<UUID, ChatChannel> playerChannels = new ConcurrentHashMap<>();

    /**
     * Gets the current chat channel for a player.
     * Defaults to GENERAL if not set.
     */
    public ChatChannel getChannel(UUID playerId) {
        return playerChannels.getOrDefault(playerId, ChatChannel.GENERAL);
    }

    /**
     * Sets the chat channel for a player.
     */
    public void setChannel(UUID playerId, ChatChannel channel) {
        playerChannels.put(playerId, channel);
    }

    /**
     * Removes a player from the channel manager (on quit).
     */
    public void removePlayer(UUID playerId) {
        playerChannels.remove(playerId);
    }

    /**
     * Checks if a player is in a specific channel.
     */
    public boolean isInChannel(UUID playerId, ChatChannel channel) {
        return getChannel(playerId) == channel;
    }
}

