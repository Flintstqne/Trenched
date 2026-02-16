// src/main/java/org/flintstqne/terrainGen/TeamLogic/TeamCommand.java
package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.util.*;
import java.util.stream.Collectors;

public final class TeamCommand implements CommandExecutor, TabCompleter {

    private final TeamService teamService;
    private final ScoreboardUtil scoreboardUtil;

    public TeamCommand(TeamService teamService, ScoreboardUtil scoreboardUtil) {
        this.teamService = teamService;
        this.scoreboardUtil = scoreboardUtil;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /team list|join <id>|leave|spawn");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "list" -> {
                String list = teamService.listTeams().stream()
                        .sorted(Comparator.comparing(Team::id))
                        .map(t -> t.id() + " (" + t.displayName() + ")")
                        .collect(Collectors.joining(", "));
                sender.sendMessage(ChatColor.GREEN + "Teams: " + (list.isBlank() ? "(none)" : list));
                return true;
            }
            case "join" -> {
                if (args.length < 2 || args[1].isBlank()) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /team join <id>");
                    return true;
                }

                var result = teamService.joinTeam(player.getUniqueId(), args[1], JoinReason.PLAYER_CHOICE);
                switch (result) {
                    case OK -> {
                        sender.sendMessage(ChatColor.GREEN + "Joined team " + args[1] + ".");
                        if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                        // Teleport player to the team's spawn if configured
                        try {
                            teamService.getTeamSpawn(args[1]).ifPresent(spawn -> player.teleport(spawn));
                        } catch (Throwable t) {
                            sender.sendMessage(ChatColor.RED + "Joined team but failed to teleport to spawn: " + t.getMessage());
                        }
                    }
                    case TEAM_NOT_FOUND -> sender.sendMessage(ChatColor.RED + "Team not found.");
                    case TEAM_FULL -> sender.sendMessage(ChatColor.RED + "Team is full.");
                    case ALREADY_IN_TEAM -> {
                        sender.sendMessage(ChatColor.RED + "You are already in a team. Use /team leave or /team swap.");
                        // Teleport to spawn of the requested team if configured
                        try {
                            teamService.getTeamSpawn(args[1]).ifPresent(spawn -> player.teleport(spawn));
                            if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                        } catch (Throwable t) {
                            sender.sendMessage(ChatColor.RED + "Failed to teleport to team spawn: " + t.getMessage());
                        }
                    }
                    default -> sender.sendMessage(ChatColor.RED + "Failed to join team.");
                }
                return true;
            }
            case "leave" -> {
                // Only allow OPs to leave their team
                if (!player.isOp()) {
                    sender.sendMessage(ChatColor.RED + "You cannot leave your team. Teams are permanent unless changed by an admin.");
                    return true;
                }

                String before = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
                var result = teamService.leaveTeam(player.getUniqueId());

                switch (result) {
                    case OK -> {
                        sender.sendMessage(ChatColor.GREEN + "Left your team." + (before != null ? " (" + before + ")" : ""));
                        sender.sendMessage(ChatColor.YELLOW + "Use /teamgui to join a new team.");
                        if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                    }
                    case NOT_IN_TEAM -> sender.sendMessage(ChatColor.RED + "You are not in a team.");
                    default -> sender.sendMessage(ChatColor.RED + "Failed to leave team.");
                }
                return true;
            }

            case "spawn" -> {
                var teamIdOpt = teamService.getPlayerTeam(player.getUniqueId());
                if (teamIdOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "You are not in a team.");
                    return true;
                }

                var spawnOpt = teamService.getTeamSpawn(teamIdOpt.get());
                if (spawnOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No team spawn set." + "(" + teamIdOpt.get() + ")");
                    return true;
                }

                player.teleport(spawnOpt.get());
                sender.sendMessage(ChatColor.GREEN + "Teleported to team spawn.");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /team list|join <id>|leave|spawn");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();

        if (args.length == 1) {
            List<String> options = Arrays.asList("list", "join", "leave", "spawn");
            return options.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            return teamService.listTeams().stream()
                    .map(Team::id)
                    .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
