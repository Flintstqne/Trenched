package org.flintstqne.entrenched.StatLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /stats command.
 */
public class StatCommand implements CommandExecutor, TabCompleter {

    private final StatService statService;
    private final TeamService teamService;
    private final RoundService roundService;
    private final ConfigManager config;

    public StatCommand(StatService statService, TeamService teamService,
                       RoundService roundService, ConfigManager config) {
        this.statService = statService;
        this.teamService = teamService;
        this.roundService = roundService;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show personal stats
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player: /stats <player>");
                return true;
            }
            showPlayerStats(sender, player.getUniqueId(), player.getName());
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "leaderboard", "lb", "top" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /stats leaderboard <category>");
                    sender.sendMessage(ChatColor.GRAY + "Categories: kills, deaths, objectives, captures, ip, time");
                    return true;
                }
                showLeaderboard(sender, args[1], args.length > 2 ? parseIntOrDefault(args[2], 10) : 10);
            }
            case "round" -> {
                int roundId;
                if (args.length > 1) {
                    roundId = parseIntOrDefault(args[1], -1);
                    if (roundId < 0) {
                        sender.sendMessage(ChatColor.RED + "Invalid round ID");
                        return true;
                    }
                } else {
                    roundId = roundService.getCurrentRound().map(Round::roundId).orElse(-1);
                    if (roundId < 0) {
                        sender.sendMessage(ChatColor.RED + "No active round");
                        return true;
                    }
                }
                showRoundStats(sender, roundId);
            }
            case "team" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /stats team <red|blue>");
                    return true;
                }
                String team = args[1].toLowerCase();
                if (!team.equals("red") && !team.equals("blue")) {
                    sender.sendMessage(ChatColor.RED + "Team must be 'red' or 'blue'");
                    return true;
                }
                int roundId = roundService.getCurrentRound().map(Round::roundId).orElse(-1);
                if (roundId < 0) {
                    sender.sendMessage(ChatColor.RED + "No active round");
                    return true;
                }
                showTeamStats(sender, team, roundId);
            }
            default -> {
                // Assume it's a player name
                Player target = Bukkit.getPlayer(args[0]);
                if (target != null) {
                    showPlayerStats(sender, target.getUniqueId(), target.getName());
                } else {
                    // Try to find offline player
                    @SuppressWarnings("deprecation")
                    UUID offlineUuid = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
                    Optional<PlayerStats> stats = statService.getPlayerStats(offlineUuid);
                    if (stats.isPresent()) {
                        showPlayerStats(sender, offlineUuid, stats.get().getLastKnownName());
                    } else {
                        sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
                    }
                }
            }
        }

        return true;
    }

    private void showPlayerStats(CommandSender sender, UUID playerUuid, String playerName) {
        Optional<PlayerStats> statsOpt = statService.getPlayerStats(playerUuid);
        if (statsOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No stats found for " + playerName);
            return;
        }

        PlayerStats stats = statsOpt.get();

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.WHITE + playerName + "'s Stats" + ChatColor.GOLD + " ═══════");
        sender.sendMessage("");

        // Combat Stats
        sender.sendMessage(ChatColor.RED + "⚔ Combat");
        sender.sendMessage(ChatColor.GRAY + "  Kills: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.KILLS) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Deaths: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.DEATHS) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "K/D: " + ChatColor.YELLOW + String.format("%.2f", stats.getKDR()));
        sender.sendMessage(ChatColor.GRAY + "  Assists: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.ASSISTS) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Commander Kills: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.COMMANDER_KILLS) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Best Streak: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.KILL_STREAK_BEST));
        sender.sendMessage(ChatColor.GRAY + "  Damage Dealt: " + ChatColor.WHITE + String.format("%,.0f", stats.getStat(StatCategory.DAMAGE_DEALT)) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Damage Taken: " + ChatColor.WHITE + String.format("%,.0f", stats.getStat(StatCategory.DAMAGE_TAKEN)));

        // Territory Stats
        sender.sendMessage(ChatColor.YELLOW + "🏴 Territory");
        sender.sendMessage(ChatColor.GRAY + "  Captured: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.REGIONS_CAPTURED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Defended: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.REGIONS_DEFENDED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Contested: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.REGIONS_CONTESTED));
        sender.sendMessage(ChatColor.GRAY + "  IP Earned: " + ChatColor.WHITE + String.format("%,.0f", stats.getStat(StatCategory.IP_EARNED)) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "IP Denied: " + ChatColor.WHITE + String.format("%,.0f", stats.getStat(StatCategory.IP_DENIED)));
        sender.sendMessage(ChatColor.GRAY + "  Banners Placed: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.BANNERS_PLACED));

        // Objective Stats
        sender.sendMessage(ChatColor.GREEN + "🎯 Objectives");
        sender.sendMessage(ChatColor.GRAY + "  Completed: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.OBJECTIVES_COMPLETED) +
                ChatColor.DARK_GRAY + " (" + ChatColor.GRAY + "Settlement: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.OBJECTIVES_SETTLEMENT) +
                ChatColor.DARK_GRAY + ", " + ChatColor.GRAY + "Raid: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.OBJECTIVES_RAID) + ChatColor.DARK_GRAY + ")");
        sender.sendMessage(ChatColor.GRAY + "  Intel: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.INTEL_CAPTURED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "TNT: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.TNT_PLANTED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Defused: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.TNT_DEFUSED));

        // Building Stats
        sender.sendMessage(ChatColor.AQUA + "🏗 Building");
        sender.sendMessage(ChatColor.GRAY + "  Buildings: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.BUILDINGS_CONSTRUCTED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Destroyed: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.BUILDINGS_DESTROYED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Roads: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.ROADS_BUILT));
        sender.sendMessage(ChatColor.GRAY + "  Depots Placed: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.DEPOTS_PLACED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Depots Raided: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.DEPOTS_RAIDED));

        // Participation Stats
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "📊 Participation");
        int timePlayed = (int) stats.getStat(StatCategory.TIME_PLAYED);
        int hours = timePlayed / 60;
        int minutes = timePlayed % 60;
        int timeInEnemy = (int) stats.getStat(StatCategory.TIME_IN_ENEMY_TERRITORY);
        int enemyHours = timeInEnemy / 60;
        int enemyMinutes = timeInEnemy % 60;
        sender.sendMessage(ChatColor.GRAY + "  Rounds: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.ROUNDS_PLAYED) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Wins: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.ROUNDS_WON) +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "MVPs: " + ChatColor.WHITE + (int) stats.getStat(StatCategory.ROUNDS_MVP));
        sender.sendMessage(ChatColor.GRAY + "  Time Played: " + ChatColor.WHITE + hours + "h " + minutes + "m" +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "In Enemy Territory: " + ChatColor.WHITE + enemyHours + "h " + enemyMinutes + "m");
        int loginStreak = (int) stats.getStat(StatCategory.LOGIN_STREAK);
        int bestStreak = (int) stats.getStat(StatCategory.LOGIN_STREAK_BEST);
        sender.sendMessage(ChatColor.GRAY + "  Login Streak: " + ChatColor.WHITE + loginStreak + " days" +
                ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "Best: " + ChatColor.WHITE + bestStreak + " days");

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "MVP Score: " + ChatColor.YELLOW + String.format("%,.0f", stats.getMVPScore()));
        sender.sendMessage("");
    }

    private void showLeaderboard(CommandSender sender, String categoryArg, int limit) {
        StatCategory category = parseCategory(categoryArg);
        if (category == null) {
            sender.sendMessage(ChatColor.RED + "Unknown category: " + categoryArg);
            sender.sendMessage(ChatColor.GRAY + "Valid categories: kills, deaths, assists, objectives, captures, ip, time, streak, mvps");
            return;
        }

        List<LeaderboardEntry> entries = statService.getLeaderboard(category, limit);
        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No leaderboard data available for " + category.getDisplayName());
            return;
        }

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.WHITE + category.getDisplayName() + " Leaderboard" + ChatColor.GOLD + " ═══════");
        sender.sendMessage("");

        for (LeaderboardEntry entry : entries) {
            ChatColor rankColor = switch (entry.rank()) {
                case 1 -> ChatColor.GOLD;
                case 2 -> ChatColor.GRAY;
                case 3 -> ChatColor.RED;
                default -> ChatColor.WHITE;
            };
            String medal = switch (entry.rank()) {
                case 1 -> "🥇";
                case 2 -> "🥈";
                case 3 -> "🥉";
                default -> "#" + entry.rank();
            };

            sender.sendMessage(rankColor + medal + " " + ChatColor.WHITE + entry.username() +
                    ChatColor.DARK_GRAY + " - " + ChatColor.YELLOW + entry.getFormattedValue(category));
        }
        sender.sendMessage("");
    }

    private void showRoundStats(CommandSender sender, int roundId) {
        Optional<RoundSummary> summaryOpt = statService.getRoundSummary(roundId);
        if (summaryOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No stats found for round " + roundId);
            return;
        }

        RoundSummary summary = summaryOpt.get();

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.WHITE + "Round " + roundId + " Stats" + ChatColor.GOLD + " ═══════");
        sender.sendMessage("");

        // Team comparison
        sender.sendMessage(ChatColor.RED + "Red Team" + ChatColor.DARK_GRAY + " vs " + ChatColor.BLUE + "Blue Team");
        sender.sendMessage(ChatColor.GRAY + "  Players: " + ChatColor.RED + summary.redPlayerCount() +
                ChatColor.DARK_GRAY + " | " + ChatColor.BLUE + summary.bluePlayerCount());
        sender.sendMessage(ChatColor.GRAY + "  Kills: " + ChatColor.RED + (int) summary.redTotalKills() +
                ChatColor.DARK_GRAY + " | " + ChatColor.BLUE + (int) summary.blueTotalKills());
        sender.sendMessage(ChatColor.GRAY + "  Objectives: " + ChatColor.RED + (int) summary.redTotalObjectives() +
                ChatColor.DARK_GRAY + " | " + ChatColor.BLUE + (int) summary.blueTotalObjectives());
        sender.sendMessage(ChatColor.GRAY + "  Captures: " + ChatColor.RED + (int) summary.redTotalCaptures() +
                ChatColor.DARK_GRAY + " | " + ChatColor.BLUE + (int) summary.blueTotalCaptures());

        // MVP
        if (summary.mvpName() != null) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "⭐ Round MVP: " + ChatColor.WHITE + summary.mvpName() +
                    ChatColor.GRAY + " (Score: " + String.format("%,.0f", summary.mvpScore()) + ")");
        }

        sender.sendMessage("");
    }

    private void showTeamStats(CommandSender sender, String team, int roundId) {
        TeamStats stats = statService.getTeamStats(team, roundId);

        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        String teamName = team.substring(0, 1).toUpperCase() + team.substring(1);

        sender.sendMessage("");
        sender.sendMessage(ChatColor.GOLD + "═══════ " + teamColor + teamName + " Team Stats" + ChatColor.GOLD + " ═══════");
        sender.sendMessage(ChatColor.GRAY + "Round " + roundId + " | " + stats.playerCount() + " players");
        sender.sendMessage("");

        sender.sendMessage(ChatColor.GRAY + "Total Kills: " + ChatColor.WHITE + (int) stats.getTotal(StatCategory.KILLS) +
                ChatColor.DARK_GRAY + " (avg: " + String.format("%.1f", stats.getAverage(StatCategory.KILLS)) + ")");
        sender.sendMessage(ChatColor.GRAY + "Total Deaths: " + ChatColor.WHITE + (int) stats.getTotal(StatCategory.DEATHS));
        sender.sendMessage(ChatColor.GRAY + "Total Objectives: " + ChatColor.WHITE + (int) stats.getTotal(StatCategory.OBJECTIVES_COMPLETED));
        sender.sendMessage(ChatColor.GRAY + "Total Captures: " + ChatColor.WHITE + (int) stats.getTotal(StatCategory.REGIONS_CAPTURED));
        sender.sendMessage(ChatColor.GRAY + "Total IP Earned: " + ChatColor.WHITE + String.format("%,.0f", stats.getTotal(StatCategory.IP_EARNED)));

        if (stats.mvpName() != null) {
            sender.sendMessage("");
            sender.sendMessage(ChatColor.GOLD + "⭐ Team MVP: " + ChatColor.WHITE + stats.mvpName() +
                    ChatColor.GRAY + " (Score: " + String.format("%,.0f", stats.mvpScore()) + ")");
        }

        sender.sendMessage("");
    }

    private StatCategory parseCategory(String arg) {
        return switch (arg.toLowerCase()) {
            case "kills", "kill", "k" -> StatCategory.KILLS;
            case "deaths", "death", "d" -> StatCategory.DEATHS;
            case "assists", "assist", "a" -> StatCategory.ASSISTS;
            case "kdr" -> null; // Computed, not stored
            case "objectives", "obj", "o" -> StatCategory.OBJECTIVES_COMPLETED;
            case "captures", "capture", "cap" -> StatCategory.REGIONS_CAPTURED;
            case "ip", "influence" -> StatCategory.IP_EARNED;
            case "time", "played", "timeplayed" -> StatCategory.TIME_PLAYED;
            case "streak", "killstreak" -> StatCategory.KILL_STREAK_BEST;
            case "mvps", "mvp" -> StatCategory.ROUNDS_MVP;
            case "wins", "win" -> StatCategory.ROUNDS_WON;
            case "buildings", "build" -> StatCategory.BUILDINGS_CONSTRUCTED;
            case "roads", "road" -> StatCategory.ROADS_BUILT;
            case "depots", "depot" -> StatCategory.DEPOTS_RAIDED;
            case "intel" -> StatCategory.INTEL_CAPTURED;
            case "tnt" -> StatCategory.TNT_PLANTED;
            default -> StatCategory.fromKey(arg);
        };
    }

    private int parseIntOrDefault(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("leaderboard");
            completions.add("round");
            completions.add("team");

            // Add online player names
            for (Player p : Bukkit.getOnlinePlayers()) {
                completions.add(p.getName());
            }

            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();

            if (sub.equals("leaderboard") || sub.equals("lb") || sub.equals("top")) {
                List<String> categories = Arrays.asList(
                        "kills", "deaths", "assists", "objectives", "captures",
                        "ip", "time", "streak", "mvps", "wins", "buildings", "roads"
                );
                return categories.stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (sub.equals("team")) {
                return Arrays.asList("red", "blue").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if (sub.equals("round")) {
                List<Integer> roundIds = statService.getAllRoundIds();
                return roundIds.stream()
                        .map(String::valueOf)
                        .filter(s -> s.startsWith(args[1]))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}

