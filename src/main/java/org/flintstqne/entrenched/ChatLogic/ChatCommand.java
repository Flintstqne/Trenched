package org.flintstqne.entrenched.ChatLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.*;
import org.flintstqne.entrenched.PartyLogic.*;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Optional;
import java.util.UUID;

/**
 * Unified chat command handler for all chat channels.
 * Handles /dc, /g, /gen, /general, /pc, /partychat, /rc, /regionchat
 */
public final class ChatCommand implements CommandExecutor {

    private final ChatChannelManager channelManager;
    private final DivisionService divisionService;
    private final PartyService partyService;
    private final TeamService teamService;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer; // May be null if BlueMap not available

    public ChatCommand(ChatChannelManager channelManager, DivisionService divisionService,
                       PartyService partyService, TeamService teamService, ConfigManager configManager,
                       RegionRenderer regionRenderer) {
        this.channelManager = channelManager;
        this.divisionService = divisionService;
        this.partyService = partyService;
        this.teamService = teamService;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use chat commands.");
            return true;
        }

        String prefix = configManager.getPrefix();
        ChatChannel targetChannel = ChatChannel.fromAlias(label);

        if (targetChannel == null) {
            player.sendMessage(prefix + ChatColor.RED + "Unknown chat channel.");
            return true;
        }

        // If no arguments, switch to this channel
        if (args.length == 0) {
            return switchChannel(player, targetChannel, prefix);
        }

        // If arguments provided, send message to that channel without switching
        String message = String.join(" ", args);
        return sendToChannel(player, targetChannel, message, prefix);
    }

    /**
     * Switches the player's active chat channel.
     */
    private boolean switchChannel(Player player, ChatChannel channel, String prefix) {
        // Validate player can use this channel
        String validationError = validateChannelAccess(player, channel);
        if (validationError != null) {
            player.sendMessage(prefix + ChatColor.RED + validationError);
            return true;
        }

        channelManager.setChannel(player.getUniqueId(), channel);

        String channelColor = getChannelColor(channel);
        player.sendMessage(prefix + ChatColor.GREEN + "Switched to " + channelColor + channel.getDisplayName() + ChatColor.GREEN + " chat.");
        return true;
    }

    /**
     * Sends a message to a specific channel without switching.
     */
    private boolean sendToChannel(Player player, ChatChannel channel, String message, String prefix) {
        // Validate player can use this channel
        String validationError = validateChannelAccess(player, channel);
        if (validationError != null) {
            player.sendMessage(prefix + ChatColor.RED + validationError);
            return true;
        }

        switch (channel) {
            case GENERAL -> sendGeneralMessage(player, message);
            case TEAM -> sendTeamMessage(player, message, prefix);
            case DIVISION -> sendDivisionMessage(player, message, prefix);
            case PARTY -> sendPartyMessage(player, message, prefix);
            case REGION -> sendRegionMessage(player, message, prefix);
        }

        return true;
    }

    /**
     * Validates if a player can access a channel.
     * Returns error message if not, null if OK.
     */
    private String validateChannelAccess(Player player, ChatChannel channel) {
        switch (channel) {
            case TEAM -> {
                if (teamService.getPlayerTeam(player.getUniqueId()).isEmpty()) {
                    return "You are not on a team.";
                }
            }
            case DIVISION -> {
                if (divisionService.getPlayerDivision(player.getUniqueId()).isEmpty()) {
                    return "You are not in a division.";
                }
            }
            case PARTY -> {
                if (partyService.getPlayerParty(player.getUniqueId()).isEmpty()) {
                    return "You are not in a party. Create one with /party create";
                }
            }
            case REGION -> {
                // Region chat is always available if regionRenderer exists
                if (regionRenderer == null) {
                    return "Region chat is not available.";
                }
            }
            case GENERAL -> {
                // General is always available
            }
        }
        return null;
    }

    /**
     * Gets the region ID for a location.
     */
    private String getRegionId(Location location) {
        if (regionRenderer == null) return null;

        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();

        // Use same calculation as RegionRenderer
        final int GRID_SIZE = 4;
        final int REGION_BLOCKS = 512;
        final int halfSize = (GRID_SIZE * REGION_BLOCKS) / 2;

        int gridX = (blockX + halfSize) / REGION_BLOCKS;
        int gridZ = (blockZ + halfSize) / REGION_BLOCKS;

        if (gridX < 0 || gridX >= GRID_SIZE || gridZ < 0 || gridZ >= GRID_SIZE) {
            return null;
        }

        char rowLabel = (char) ('A' + gridZ);
        return rowLabel + String.valueOf(gridX + 1);
    }

    /**
     * Gets the region name for a location.
     */
    private String getRegionName(Location location) {
        if (regionRenderer == null) return "Unknown";
        return regionRenderer.getRegionNameForBlock(location.getBlockX(), location.getBlockZ()).orElse("Unknown");
    }

    /**
     * Sends a message to general chat (all players).
     */
    private void sendGeneralMessage(Player player, String message) {
        UUID playerId = player.getUniqueId();

        ChatColor teamColor = ChatColor.GRAY;
        String divisionTag = "";

        Optional<String> teamOpt = teamService.getPlayerTeam(playerId);
        if (teamOpt.isPresent()) {
            String team = teamOpt.get();
            teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        }

        // Get division tag if in a division
        Optional<Division> divOpt = divisionService.getPlayerDivision(playerId);
        if (divOpt.isPresent()) {
            divisionTag = teamColor + divOpt.get().formattedTag() + " ";
        }

        String formattedMessage = divisionTag + teamColor + player.getName() + ChatColor.WHITE + ": " + message;

        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(formattedMessage);
        }
    }

    /**
     * Sends a message to team chat (all players on the same team).
     */
    private void sendTeamMessage(Player player, String message, String prefix) {
        UUID playerId = player.getUniqueId();

        Optional<String> teamOpt = teamService.getPlayerTeam(playerId);
        if (teamOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not on a team.");
            return;
        }

        String team = teamOpt.get();
        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        String teamName = team.equalsIgnoreCase("red") ? "Red" : "Blue";

        // Get division tag if in a division
        String divisionTag = "";
        Optional<Division> divOpt = divisionService.getPlayerDivision(playerId);
        if (divOpt.isPresent()) {
            divisionTag = teamColor + divOpt.get().formattedTag() + " ";
        }

        String formattedMessage = ChatColor.DARK_GRAY + "[" + teamColor + teamName + ChatColor.DARK_GRAY + "] " +
                divisionTag + teamColor + player.getName() + ChatColor.WHITE + ": " + message;

        // Send to all players on the same team
        for (Player online : Bukkit.getOnlinePlayers()) {
            Optional<String> onlineTeamOpt = teamService.getPlayerTeam(online.getUniqueId());
            if (onlineTeamOpt.isPresent() && onlineTeamOpt.get().equalsIgnoreCase(team)) {
                online.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * Sends a message to division chat.
     */
    private void sendDivisionMessage(Player player, String message, String prefix) {
        Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a division.");
            return;
        }

        Division div = divOpt.get();
        Optional<DivisionMember> memberOpt = divisionService.getMembership(player.getUniqueId());
        DivisionRole role = memberOpt.map(DivisionMember::role).orElse(DivisionRole.MEMBER);

        String symbol = role.getSymbol();
        String roleColor = switch (role) {
            case COMMANDER -> ChatColor.GOLD.toString();
            case OFFICER -> ChatColor.YELLOW.toString();
            case MEMBER -> ChatColor.WHITE.toString();
        };

        String formattedMessage = ChatColor.DARK_GRAY + "[" + ChatColor.BLUE + "Div" + ChatColor.DARK_GRAY + "] " +
                ChatColor.GRAY + div.formattedTag() + " " +
                roleColor + symbol + (symbol.isEmpty() ? "" : " ") + ChatColor.GRAY + player.getName() + ChatColor.GRAY + ": " +
                ChatColor.WHITE + message;

        for (DivisionMember member : divisionService.getMembers(div.divisionId())) {
            Player onlineMember = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
            if (onlineMember != null) {
                onlineMember.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * Sends a message to party chat.
     */
    private void sendPartyMessage(Player player, String message, String prefix) {
        Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a party.");
            return;
        }

        Party party = partyOpt.get();
        boolean isLeader = party.leaderUuid().equals(player.getUniqueId().toString());

        String leaderSymbol = isLeader ? ChatColor.GOLD + "★ " : "";

        String formattedMessage = ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "Party" + ChatColor.DARK_GRAY + "] " +
                leaderSymbol + ChatColor.GREEN + player.getName() + ChatColor.GRAY + ": " +
                ChatColor.WHITE + message;

        for (PartyMember member : partyService.getMembers(party.partyId())) {
            Player onlineMember = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
            if (onlineMember != null) {
                onlineMember.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * Sends a message to region chat (all players in the same region, regardless of team).
     */
    private void sendRegionMessage(Player player, String message, String prefix) {
        String senderRegionId = getRegionId(player.getLocation());
        if (senderRegionId == null) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a valid region.");
            return;
        }

        String regionName = getRegionName(player.getLocation());
        UUID playerId = player.getUniqueId();

        // Get sender's team color
        ChatColor teamColor = ChatColor.GRAY;
        Optional<String> teamOpt = teamService.getPlayerTeam(playerId);
        if (teamOpt.isPresent()) {
            String team = teamOpt.get();
            teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        }

        // Get division tag if in a division
        String divisionTag = "";
        Optional<Division> divOpt = divisionService.getPlayerDivision(playerId);
        if (divOpt.isPresent()) {
            divisionTag = teamColor + divOpt.get().formattedTag() + " ";
        }

        String formattedMessage = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + regionName + ChatColor.DARK_GRAY + "] " +
                divisionTag + teamColor + player.getName() + ChatColor.WHITE + ": " + message;

        // Send to all players in the same region
        int recipients = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String onlineRegionId = getRegionId(online.getLocation());
            if (senderRegionId.equals(onlineRegionId)) {
                online.sendMessage(formattedMessage);
                recipients++;
            }
        }

        // If sender was the only one in the region, let them know
        if (recipients == 1) {
            player.sendMessage(ChatColor.GRAY + "(No other players are in this region)");
        }
    }

    /**
     * Gets the color associated with a channel.
     */
    private String getChannelColor(ChatChannel channel) {
        return switch (channel) {
            case GENERAL -> ChatColor.WHITE.toString();
            case TEAM -> ChatColor.AQUA.toString();
            case DIVISION -> ChatColor.BLUE.toString();
            case PARTY -> ChatColor.GREEN.toString();
            case REGION -> ChatColor.GOLD.toString();
        };
    }
}

