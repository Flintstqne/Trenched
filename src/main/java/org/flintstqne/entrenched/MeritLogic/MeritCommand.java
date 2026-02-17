package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles /merit and /merits commands.
 */
public class MeritCommand implements CommandExecutor, TabCompleter {

    private final MeritService meritService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    // Valid reason categories
    private static final List<String> REASON_CATEGORIES = Arrays.asList(
            "combat", "leadership", "teamwork", "building", "supply", "bravery"
    );

    public MeritCommand(MeritService meritService, TeamService teamService, ConfigManager configManager) {
        this.meritService = meritService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();

        // /merits - View merit stats
        if (cmdName.equals("merits")) {
            return handleMeritsCommand(sender, args);
        }

        // /merit <player> [reason] - Give merit
        if (cmdName.equals("merit")) {
            return handleMeritCommand(sender, args);
        }

        // /ranks - View all ranks
        if (cmdName.equals("ranks")) {
            return handleRanksCommand(sender);
        }

        return false;
    }

    private boolean handleMeritCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can give merits.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /merit <player> [reason]");
            player.sendMessage(ChatColor.GRAY + "Reasons: combat, leadership, teamwork, building, supply, bravery");
            return true;
        }

        // Find target player
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
            return true;
        }

        // Get reason if provided
        String reason = null;
        if (args.length > 1) {
            String reasonArg = args[1].toLowerCase();
            if (REASON_CATEGORIES.contains(reasonArg)) {
                reason = reasonArg;
            } else {
                // Treat as custom reason
                reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        }

        // Check if same team
        Optional<String> giverTeam = teamService.getPlayerTeam(player.getUniqueId());
        Optional<String> receiverTeam = teamService.getPlayerTeam(target.getUniqueId());
        boolean sameTeam = giverTeam.isPresent() && receiverTeam.isPresent() &&
                giverTeam.get().equalsIgnoreCase(receiverTeam.get());

        // Get current round ID
        Integer roundId = null; // Would need RoundService

        // Attempt to give merit
        MeritService.GiveResult result = meritService.giveMerit(
                player.getUniqueId(),
                target.getUniqueId(),
                1,
                reason,
                sameTeam,
                roundId
        );

        switch (result) {
            case SUCCESS:
                // Notifications handled by MeritService
                break;
            case INSUFFICIENT_TOKENS:
                player.sendMessage(ChatColor.RED + "You don't have any merit tokens to give!");
                player.sendMessage(ChatColor.GRAY + "Earn tokens by completing tasks (kills, captures, building, etc.)");
                break;
            case SELF_MERIT:
                player.sendMessage(ChatColor.RED + "You cannot give merits to yourself!");
                break;
            case DAILY_LIMIT_GIVER:
                player.sendMessage(ChatColor.RED + "You've reached your daily limit for giving merits (5/day).");
                break;
            case DAILY_LIMIT_RECEIVER:
                player.sendMessage(ChatColor.RED + target.getName() + " has reached their daily limit for receiving merits.");
                break;
            case SAME_PLAYER_COOLDOWN:
                player.sendMessage(ChatColor.RED + "You've already given merits to " + target.getName() + " too many times today (max 3).");
                break;
            case NO_INTERACTION:
                player.sendMessage(ChatColor.RED + "You haven't been in the same region as " + target.getName() + " recently.");
                player.sendMessage(ChatColor.GRAY + "You must be near someone to recognize their efforts!");
                break;
            case NEW_PLAYER_LOCKOUT:
                player.sendMessage(ChatColor.RED + "New players must play for a while before giving/receiving merits.");
                break;
            case CROSS_TEAM_LIMIT:
                player.sendMessage(ChatColor.RED + "You've reached your daily limit for cross-team merits (2/day).");
                break;
        }

        return true;
    }

    private boolean handleMeritsCommand(CommandSender sender, String[] args) {
        UUID targetUuid;
        String targetName;

        if (args.length > 0) {
            // View another player's stats
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null) {
                targetUuid = target.getUniqueId();
                targetName = target.getName();
            } else {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
                return true;
            }
        } else if (sender instanceof Player player) {
            targetUuid = player.getUniqueId();
            targetName = player.getName();
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /merits <player>");
            return true;
        }

        // Get merit data
        Optional<PlayerMeritData> dataOpt = meritService.getPlayerData(targetUuid);
        if (dataOpt.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + targetName + " has no merit data yet.");
            return true;
        }

        PlayerMeritData data = dataOpt.get();
        MeritRank rank = data.getRank();
        MeritRank nextRank = rank.getNextRank();

        sender.sendMessage(ChatColor.GOLD + "═══════ Merit Stats: " + ChatColor.WHITE + targetName + ChatColor.GOLD + " ═══════");
        sender.sendMessage(ChatColor.YELLOW + "Rank: " + rank.getFormattedTag() + " " + rank.getColor() + rank.getDisplayName());
        sender.sendMessage(ChatColor.YELLOW + "Received Merits: " + ChatColor.GREEN + data.receivedMerits() +
                ChatColor.GRAY + " (+" + data.receivedToday() + " today)");
        sender.sendMessage(ChatColor.YELLOW + "Token Balance: " + ChatColor.AQUA + data.tokenBalance());

        if (nextRank != null) {
            int toNext = data.getMeritsToNextRank();
            sender.sendMessage(ChatColor.YELLOW + "Next Rank: " + ChatColor.GRAY + nextRank.getDisplayName() +
                    " (" + toNext + " merits needed)");
        } else {
            sender.sendMessage(ChatColor.GOLD + "★ Maximum rank achieved! ★");
        }

        sender.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────");
        sender.sendMessage(ChatColor.GRAY + "Lifetime Stats:");
        sender.sendMessage(ChatColor.GRAY + "  Tokens Earned: " + ChatColor.WHITE + data.lifetimeTokensEarned());
        sender.sendMessage(ChatColor.GRAY + "  Merits Given: " + ChatColor.WHITE + data.lifetimeMeritsGiven());
        sender.sendMessage(ChatColor.GRAY + "  Kills: " + ChatColor.WHITE + data.lifetimeKills());
        sender.sendMessage(ChatColor.GRAY + "  Captures: " + ChatColor.WHITE + data.lifetimeCaptures());
        sender.sendMessage(ChatColor.GRAY + "  Road Blocks: " + ChatColor.WHITE + data.lifetimeRoadBlocks());
        sender.sendMessage(ChatColor.GRAY + "  Playtime: " + ChatColor.WHITE + formatPlaytime(data.playtimeMinutes()));
        sender.sendMessage(ChatColor.GRAY + "  Login Streak: " + ChatColor.WHITE + data.loginStreak() + " days");

        return true;
    }

    private boolean handleRanksCommand(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "═══════════ Military Ranks ═══════════");

        sender.sendMessage(ChatColor.YELLOW + "Enlisted:");
        for (MeritRank rank : MeritRank.values()) {
            if (!rank.isOfficer() && !rank.isGeneral()) {
                sender.sendMessage(ChatColor.GRAY + "  " + rank.getFormattedTag() + " " +
                        rank.getColor() + rank.getDisplayName() +
                        ChatColor.DARK_GRAY + " - " + rank.getMeritsRequired() + " merits");
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Officers:");
        for (MeritRank rank : MeritRank.values()) {
            if (rank.isOfficer() && !rank.isGeneral()) {
                sender.sendMessage(ChatColor.GRAY + "  " + rank.getFormattedTag() + " " +
                        rank.getColor() + rank.getDisplayName() +
                        ChatColor.DARK_GRAY + " - " + rank.getMeritsRequired() + " merits");
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Generals:");
        for (MeritRank rank : MeritRank.values()) {
            if (rank.isGeneral()) {
                sender.sendMessage(ChatColor.GRAY + "  " + rank.getFormattedTag() + " " +
                        rank.getColor() + rank.getDisplayName() +
                        ChatColor.DARK_GRAY + " - " + rank.getMeritsRequired() + " merits");
            }
        }

        sender.sendMessage(ChatColor.GRAY + "Earn merit tokens by playing, then give them to others with /merit <player>");

        return true;
    }

    private String formatPlaytime(int minutes) {
        int hours = minutes / 60;
        int mins = minutes % 60;
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        return mins + "m";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("merit")) {
            if (args.length == 1) {
                // Player names
                String partial = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
            } else if (args.length == 2) {
                // Reason categories
                String partial = args[1].toLowerCase();
                return REASON_CATEGORIES.stream()
                        .filter(r -> r.startsWith(partial))
                        .collect(Collectors.toList());
            }
        }

        if (cmdName.equals("merits")) {
            if (args.length == 1) {
                // Player names
                String partial = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(partial))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}

