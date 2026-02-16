package org.flintstqne.entrenched.PartyLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all /party commands.
 */
public final class PartyCommand implements CommandExecutor, TabCompleter {

    private final PartyService partyService;
    private final ConfigManager configManager;

    public PartyCommand(PartyService partyService, ConfigManager configManager) {
        this.partyService = partyService;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use party commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "create" -> handleCreate(player);
            case "invite", "inv" -> handleInvite(player, args);
            case "accept", "join" -> handleAccept(player, args);
            case "deny", "decline" -> handleDeny(player, args);
            case "leave" -> handleLeave(player);
            case "kick" -> handleKick(player, args);
            case "list", "members" -> handleList(player);
            case "transfer", "promote" -> handleTransfer(player, args);
            case "disband" -> handleDisband(player);
            default -> {
                sendHelp(player);
                yield true;
            }
        };
    }

    private void sendHelp(Player player) {
        String prefix = configManager.getPrefix();
        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Party Commands:");
        player.sendMessage(ChatColor.GRAY + "  /party create" + ChatColor.WHITE + " - Create a new party");
        player.sendMessage(ChatColor.GRAY + "  /party invite <player>" + ChatColor.WHITE + " - Invite a player");
        player.sendMessage(ChatColor.GRAY + "  /party accept" + ChatColor.WHITE + " - Accept party invite");
        player.sendMessage(ChatColor.GRAY + "  /party deny" + ChatColor.WHITE + " - Decline party invite");
        player.sendMessage(ChatColor.GRAY + "  /party leave" + ChatColor.WHITE + " - Leave your party");
        player.sendMessage(ChatColor.GRAY + "  /party list" + ChatColor.WHITE + " - View party members");

        if (partyService.isLeader(player.getUniqueId())) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "Leader Commands:");
            player.sendMessage(ChatColor.GRAY + "  /party kick <player>" + ChatColor.WHITE + " - Kick a member");
            player.sendMessage(ChatColor.GRAY + "  /party transfer <player>" + ChatColor.WHITE + " - Transfer leadership");
            player.sendMessage(ChatColor.GRAY + "  /party disband" + ChatColor.WHITE + " - Disband the party");
        }
        player.sendMessage("");
    }

    private boolean handleCreate(Player player) {
        String prefix = configManager.getPrefix();

        PartyService.CreateResult result = partyService.createParty(player.getUniqueId());

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.GREEN + "Party created! Invite players with /party invite <player>");
            }
            case ALREADY_IN_PARTY -> player.sendMessage(prefix + ChatColor.RED + "You are already in a party. Leave first with /party leave");
            case NO_ACTIVE_ROUND -> player.sendMessage(prefix + ChatColor.RED + "No active round.");
            case PLAYER_NOT_ON_TEAM -> player.sendMessage(prefix + ChatColor.RED + "You must be on a team first.");
        }

        return true;
    }

    private boolean handleInvite(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /party invite <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(prefix + ChatColor.RED + "Player not found or not online.");
            return true;
        }

        PartyService.InviteResult result = partyService.invitePlayer(player.getUniqueId(), target.getUniqueId());

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.GREEN + "Invited " + target.getName() + " to your party.");
                target.sendMessage(prefix + ChatColor.YELLOW + player.getName() + " has invited you to their party!");
                target.sendMessage(prefix + ChatColor.GRAY + "Type /party accept to join or /party deny to decline.");
            }
            case NOT_IN_PARTY -> player.sendMessage(prefix + ChatColor.RED + "You are not in a party. Create one with /party create");
            case NOT_LEADER -> player.sendMessage(prefix + ChatColor.RED + "Only the party leader can invite players.");
            case TARGET_ALREADY_IN_PARTY -> player.sendMessage(prefix + ChatColor.RED + target.getName() + " is already in a party.");
            case TARGET_ALREADY_INVITED -> player.sendMessage(prefix + ChatColor.RED + target.getName() + " already has a pending invite.");
            case PARTY_FULL -> player.sendMessage(prefix + ChatColor.RED + "Your party is full (" + partyService.getMaxPartySize() + " max).");
            case WRONG_TEAM -> player.sendMessage(prefix + ChatColor.RED + target.getName() + " is not on your team.");
            case CANNOT_INVITE_SELF -> player.sendMessage(prefix + ChatColor.RED + "You can't invite yourself.");
        }

        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        PartyService.JoinResult result = partyService.acceptLatestInvite(player.getUniqueId());

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.GREEN + "You joined the party!");
                // Notify other party members
                Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
                partyOpt.ifPresent(party -> notifyParty(party.partyId(),
                        prefix + ChatColor.YELLOW + player.getName() + " has joined the party.", player));
            }
            case ALREADY_IN_PARTY -> player.sendMessage(prefix + ChatColor.RED + "You are already in a party. Leave first with /party leave");
            case NO_INVITE -> player.sendMessage(prefix + ChatColor.RED + "You don't have any pending party invites.");
            case INVITE_EXPIRED -> player.sendMessage(prefix + ChatColor.RED + "The party invite has expired.");
            case PARTY_FULL -> player.sendMessage(prefix + ChatColor.RED + "The party is full.");
            case PARTY_NOT_FOUND -> player.sendMessage(prefix + ChatColor.RED + "The party no longer exists.");
        }

        return true;
    }

    private boolean handleDeny(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        List<PartyInvite> invites = partyService.getPendingInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You don't have any pending party invites.");
            return true;
        }

        // Decline the most recent invite
        PartyInvite invite = invites.get(0);
        partyService.declineInvite(player.getUniqueId(), invite.partyId());

        String inviterName = Bukkit.getOfflinePlayer(UUID.fromString(invite.inviterUuid())).getName();
        player.sendMessage(prefix + ChatColor.YELLOW + "Declined party invite from " + inviterName + ".");

        return true;
    }

    private boolean handleLeave(Player player) {
        String prefix = configManager.getPrefix();

        Optional<Party> partyBefore = partyService.getPlayerParty(player.getUniqueId());
        int partyIdBefore = partyBefore.map(Party::partyId).orElse(-1);

        PartyService.LeaveResult result = partyService.leaveParty(player.getUniqueId());

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.YELLOW + "You left the party.");
                if (partyIdBefore > 0) {
                    notifyParty(partyIdBefore, prefix + ChatColor.YELLOW + player.getName() + " has left the party.", player);
                }
            }
            case NOT_IN_PARTY -> player.sendMessage(prefix + ChatColor.RED + "You are not in a party.");
            case PARTY_DISBANDED -> player.sendMessage(prefix + ChatColor.YELLOW + "You left and the party was disbanded.");
        }

        return true;
    }

    private boolean handleKick(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /party kick <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore()) {
            player.sendMessage(prefix + ChatColor.RED + "Player not found.");
            return true;
        }

        boolean success = partyService.kickMember(player.getUniqueId(), target.getUniqueId());

        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + target.getName() + " has been kicked from the party.");
            Player onlineTarget = target.getPlayer();
            if (onlineTarget != null) {
                onlineTarget.sendMessage(prefix + ChatColor.RED + "You have been kicked from the party.");
            }
            Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
            partyOpt.ifPresent(party -> notifyParty(party.partyId(),
                    prefix + ChatColor.YELLOW + target.getName() + " was kicked from the party.", player));
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to kick player. You may not be the leader or they're not in your party.");
        }

        return true;
    }

    private boolean handleList(Player player) {
        String prefix = configManager.getPrefix();

        Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a party.");
            return true;
        }

        Party party = partyOpt.get();
        List<PartyMember> members = partyService.getMembers(party.partyId());

        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Party Members (" + members.size() + "/" + partyService.getMaxPartySize() + "):");

        for (PartyMember member : members) {
            String playerName = Bukkit.getOfflinePlayer(UUID.fromString(member.playerUuid())).getName();
            boolean isLeader = member.playerUuid().equals(party.leaderUuid());
            boolean online = Bukkit.getPlayer(UUID.fromString(member.playerUuid())) != null;

            String onlineStatus = online ? ChatColor.GREEN + "●" : ChatColor.RED + "●";
            String leaderTag = isLeader ? ChatColor.GOLD + "★ " : "  ";

            player.sendMessage(onlineStatus + " " + leaderTag + ChatColor.WHITE + playerName);
        }
        player.sendMessage("");

        return true;
    }

    private boolean handleTransfer(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /party transfer <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore()) {
            player.sendMessage(prefix + ChatColor.RED + "Player not found.");
            return true;
        }

        boolean success = partyService.transferLeadership(player.getUniqueId(), target.getUniqueId());

        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + "Leadership transferred to " + target.getName() + ".");
            Player onlineTarget = target.getPlayer();
            if (onlineTarget != null) {
                onlineTarget.sendMessage(prefix + ChatColor.GREEN + "You are now the party leader!");
            }
            Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
            partyOpt.ifPresent(party -> notifyParty(party.partyId(),
                    prefix + ChatColor.YELLOW + target.getName() + " is now the party leader.", player, onlineTarget));
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to transfer leadership. You may not be the leader or they're not in your party.");
        }

        return true;
    }

    private boolean handleDisband(Player player) {
        String prefix = configManager.getPrefix();

        Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
        if (partyOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a party.");
            return true;
        }

        int partyId = partyOpt.get().partyId();
        List<PartyMember> members = partyService.getMembers(partyId);

        boolean success = partyService.disbandParty(player.getUniqueId());

        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + "Party disbanded.");
            // Notify all members
            for (PartyMember member : members) {
                if (!member.playerUuid().equals(player.getUniqueId().toString())) {
                    Player onlineMember = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
                    if (onlineMember != null) {
                        onlineMember.sendMessage(prefix + ChatColor.YELLOW + "The party has been disbanded.");
                    }
                }
            }
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to disband. You may not be the party leader.");
        }

        return true;
    }

    /**
     * Notifies all party members except excluded players.
     */
    private void notifyParty(int partyId, String message, Player... exclude) {
        Set<UUID> excludeSet = new HashSet<>();
        for (Player p : exclude) {
            if (p != null) excludeSet.add(p.getUniqueId());
        }

        for (PartyMember member : partyService.getMembers(partyId)) {
            UUID memberUuid = UUID.fromString(member.playerUuid());
            if (!excludeSet.contains(memberUuid)) {
                Player onlineMember = Bukkit.getPlayer(memberUuid);
                if (onlineMember != null) {
                    onlineMember.sendMessage(message);
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("create", "invite", "accept", "deny", "leave", "list"));
            if (partyService.isLeader(player.getUniqueId())) {
                options.addAll(Arrays.asList("kick", "transfer", "disband"));
            }
            return options.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "invite", "inv" -> getOnlinePlayerNames(args[1]);
                case "kick", "transfer", "promote" -> getPartyMemberNames(player, args[1]);
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }

    private List<String> getOnlinePlayerNames(String prefix) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<String> getPartyMemberNames(Player player, String prefix) {
        Optional<Party> partyOpt = partyService.getPlayerParty(player.getUniqueId());
        if (partyOpt.isEmpty()) return Collections.emptyList();

        return partyService.getMembers(partyOpt.get().partyId()).stream()
                .filter(m -> !m.playerUuid().equals(player.getUniqueId().toString()))
                .map(m -> Bukkit.getOfflinePlayer(UUID.fromString(m.playerUuid())).getName())
                .filter(Objects::nonNull)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}

