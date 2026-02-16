package org.flintstqne.entrenched.Utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ChatLogic.ChatChannel;
import org.flintstqne.entrenched.ChatLogic.ChatChannelManager;
import org.flintstqne.entrenched.DivisionLogic.*;
import org.flintstqne.entrenched.PartyLogic.*;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Optional;
import java.util.UUID;

public final class ChatUtil implements Listener {

    private final TeamService teamService;
    private final DivisionService divisionService;
    private final PartyService partyService;
    private final ChatChannelManager channelManager;
    private final RegionRenderer regionRenderer; // May be null if BlueMap not available

    public ChatUtil(TeamService teamService, DivisionService divisionService,
                    PartyService partyService, ChatChannelManager channelManager,
                    RegionRenderer regionRenderer) {
        this.teamService = teamService;
        this.divisionService = divisionService;
        this.partyService = partyService;
        this.channelManager = channelManager;
        this.regionRenderer = regionRenderer;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        ChatChannel currentChannel = channelManager.getChannel(playerId);

        switch (currentChannel) {
            case GENERAL -> handleGeneralChat(event, player, playerId);
            case TEAM -> handleTeamChat(event, player, playerId);
            case DIVISION -> handleDivisionChat(event, player, playerId);
            case PARTY -> handlePartyChat(event, player, playerId);
            case REGION -> handleRegionChat(event, player, playerId);
        }
    }

    private void handleGeneralChat(AsyncPlayerChatEvent event, Player player, UUID playerId) {
        ChatColor teamColor = ChatColor.GRAY;
        String divisionTag = "";

        Optional<String> teamId = teamService.getPlayerTeam(playerId);
        if (teamId.isPresent()) {
            String team = teamId.get();
            if (team.equalsIgnoreCase("red")) {
                teamColor = ChatColor.RED;
            } else if (team.equalsIgnoreCase("blue")) {
                teamColor = ChatColor.BLUE;
            }
        }

        // Get division tag if player is in a division
        if (divisionService != null) {
            Optional<Division> divOpt = divisionService.getPlayerDivision(playerId);
            if (divOpt.isPresent()) {
                divisionTag = teamColor + divOpt.get().formattedTag() + " ";
            }
        }

        // Format: [TAG] Username: message (tag in team color)
        event.setFormat(divisionTag + teamColor + "%s" + ChatColor.WHITE + ": %s");
    }

    private void handleTeamChat(AsyncPlayerChatEvent event, Player player, UUID playerId) {
        // Cancel the normal broadcast
        event.setCancelled(true);

        Optional<String> teamOpt = teamService.getPlayerTeam(playerId);
        if (teamOpt.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You are not on a team. Switching to general chat.");
            channelManager.setChannel(playerId, ChatChannel.GENERAL);
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
                player.chat(event.getMessage());
            });
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
                divisionTag + teamColor + player.getName() + ChatColor.WHITE + ": " + event.getMessage();

        // Send to all players on the same team (from main thread for safety)
        final String finalMessage = formattedMessage;
        final String finalTeam = team;

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                Optional<String> onlineTeamOpt = teamService.getPlayerTeam(online.getUniqueId());
                if (onlineTeamOpt.isPresent() && onlineTeamOpt.get().equalsIgnoreCase(finalTeam)) {
                    online.sendMessage(finalMessage);
                }
            }
        });
    }

    private void handleDivisionChat(AsyncPlayerChatEvent event, Player player, UUID playerId) {
        // Cancel the normal broadcast
        event.setCancelled(true);

        Optional<Division> divOpt = divisionService.getPlayerDivision(playerId);
        if (divOpt.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You are not in a division. Switching to general chat.");
            channelManager.setChannel(playerId, ChatChannel.GENERAL);
            // Resend message in general chat
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
                player.chat(event.getMessage());
            });
            return;
        }

        Division div = divOpt.get();
        Optional<DivisionMember> memberOpt = divisionService.getMembership(playerId);
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
                ChatColor.WHITE + event.getMessage();

        // Send to all division members
        for (DivisionMember member : divisionService.getMembers(div.divisionId())) {
            Player onlineMember = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
            if (onlineMember != null) {
                onlineMember.sendMessage(formattedMessage);
            }
        }
    }

    private void handlePartyChat(AsyncPlayerChatEvent event, Player player, UUID playerId) {
        // Cancel the normal broadcast
        event.setCancelled(true);

        Optional<Party> partyOpt = partyService.getPlayerParty(playerId);
        if (partyOpt.isEmpty()) {
            player.sendMessage(ChatColor.RED + "You are not in a party. Switching to general chat.");
            channelManager.setChannel(playerId, ChatChannel.GENERAL);
            // Resend message in general chat
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
                player.chat(event.getMessage());
            });
            return;
        }

        Party party = partyOpt.get();
        boolean isLeader = party.leaderUuid().equals(playerId.toString());

        String leaderSymbol = isLeader ? ChatColor.GOLD + "★ " : "";

        String formattedMessage = ChatColor.DARK_GRAY + "[" + ChatColor.GREEN + "Party" + ChatColor.DARK_GRAY + "] " +
                leaderSymbol + ChatColor.GREEN + player.getName() + ChatColor.GRAY + ": " +
                ChatColor.WHITE + event.getMessage();

        // Send to all party members
        for (PartyMember member : partyService.getMembers(party.partyId())) {
            Player onlineMember = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
            if (onlineMember != null) {
                onlineMember.sendMessage(formattedMessage);
            }
        }
    }

    private void handleRegionChat(AsyncPlayerChatEvent event, Player player, UUID playerId) {
        // Cancel the normal broadcast
        event.setCancelled(true);

        if (regionRenderer == null) {
            player.sendMessage(ChatColor.RED + "Region chat is not available. Switching to general chat.");
            channelManager.setChannel(playerId, ChatChannel.GENERAL);
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
                player.chat(event.getMessage());
            });
            return;
        }

        String senderRegionId = getRegionId(player.getLocation());
        if (senderRegionId == null) {
            player.sendMessage(ChatColor.RED + "You are not in a valid region. Switching to general chat.");
            channelManager.setChannel(playerId, ChatChannel.GENERAL);
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
                player.chat(event.getMessage());
            });
            return;
        }

        String regionName = regionRenderer.getRegionNameForBlock(
                player.getLocation().getBlockX(), player.getLocation().getBlockZ()).orElse("Unknown");

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
                divisionTag + teamColor + player.getName() + ChatColor.WHITE + ": " + event.getMessage();

        // Send to all players in the same region (from main thread for safety)
        final String finalSenderRegionId = senderRegionId;
        final String finalMessage = formattedMessage;

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugins()[0], () -> {
            int recipients = 0;
            for (Player online : Bukkit.getOnlinePlayers()) {
                String onlineRegionId = getRegionId(online.getLocation());
                if (finalSenderRegionId.equals(onlineRegionId)) {
                    online.sendMessage(finalMessage);
                    recipients++;
                }
            }

            // If sender was the only one in the region, let them know
            if (recipients == 1) {
                player.sendMessage(ChatColor.GRAY + "(No other players are in this region)");
            }
        });
    }

    /**
     * Gets the region ID for a location.
     */
    private String getRegionId(Location location) {
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

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        channelManager.removePlayer(event.getPlayer().getUniqueId());
    }
}
