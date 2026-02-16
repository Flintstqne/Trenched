package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class RoundCommand implements CommandExecutor, TabCompleter {

    private final RoundService roundService;
    private final TeamService teamService;
    private final RegionRenderer regionRenderer; // may be null if BlueMap not available
    private final ScoreboardUtil scoreboardUtil;
    private final PhaseScheduler phaseScheduler;
    private final ConfigManager configManager;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private NewRoundInitializer newRoundInitializer;

    public RoundCommand(RoundService roundService, TeamService teamService, RegionRenderer regionRenderer,
                        ScoreboardUtil scoreboardUtil, PhaseScheduler phaseScheduler, ConfigManager configManager) {
        this.roundService = roundService;
        this.teamService = teamService;
        this.regionRenderer = regionRenderer;
        this.scoreboardUtil = scoreboardUtil;
        this.phaseScheduler = phaseScheduler;
        this.configManager = configManager;
        log("RoundCommand initialized");
    }

    /**
     * Sets the NewRoundInitializer. Must be called after construction to enable /round new command.
     */
    public void setNewRoundInitializer(NewRoundInitializer initializer) {
        this.newRoundInitializer = initializer;
        log("NewRoundInitializer set");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        log("Command received from " + sender.getName() + " with args: " + String.join(", ", args));

        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You need OP permission to use round commands.");
            log("Permission denied for " + sender.getName());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /round <info|start|new|phase|end|history>");
            return true;
        }

        String sub = args[0].toLowerCase();
        log("Processing subcommand: " + sub);

        switch (sub) {
            case "info" -> handleInfo(sender);
            case "start" -> handleStart(sender);
            case "new" -> handleNew(sender);
            case "phase" -> handlePhase(sender);
            case "end" -> handleEnd(sender, args);
            case "history" -> handleHistory(sender);
            default -> sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand. Use: info, start, new, phase, end, history");
        }

        return true;
    }

    private void handleInfo(CommandSender sender) {
        log("=== INFO Command Execution ===");

        Optional<Round> currentOpt = roundService.getCurrentRound();
        log("Current round query result: " + (currentOpt.isPresent() ? "Found" : "Not found"));

        if (currentOpt.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No active round.");
            log("No active round to display");
            return;
        }

        Round round = currentOpt.get();
        int maxPhases = configManager.getMaxPhases();
        log("Round details: ID=" + round.roundId() + ", Status=" + round.status() +
                ", Phase=" + round.currentPhase() + ", Seed=" + round.worldSeed());

        sender.sendMessage(ChatColor.GOLD + "=== Current Round ===");
        sender.sendMessage(ChatColor.WHITE + "Round ID: " + ChatColor.YELLOW + round.roundId());
        sender.sendMessage(ChatColor.WHITE + "Status: " + getStatusColor(round.status()) + round.status());
        sender.sendMessage(ChatColor.WHITE + "Phase: " + ChatColor.AQUA + round.currentPhase() + "/" + maxPhases);
        sender.sendMessage(ChatColor.WHITE + "Started: " + ChatColor.GRAY + dateFormat.format(new Date(round.startTime())));

        if (round.status() == Round.RoundStatus.ACTIVE) {
            // Show time until next auto-phase
            if (phaseScheduler != null && configManager.isAutoPhaseEnabled()) {
                String timeStr = phaseScheduler.getFormattedTimeUntilNextPhase();
                sender.sendMessage(ChatColor.WHITE + "Next phase in: " + ChatColor.GREEN + timeStr);
                sender.sendMessage(ChatColor.GRAY + "  (Auto-advance: " + configManager.getPhaseDurationMinutes() + " min/phase)");
            } else {
                sender.sendMessage(ChatColor.WHITE + "Auto-phase: " + ChatColor.GRAY + "Disabled (manual only)");
            }
            log("Phase scheduler status shown");
        }

        if (round.status() == Round.RoundStatus.COMPLETED && round.winningTeam() != null) {
            sender.sendMessage(ChatColor.WHITE + "Winner: " + ChatColor.GOLD + round.winningTeam());
            log("Round winner: " + round.winningTeam());
        }

        log("=== INFO Command Complete ===");
    }

    private void handleStart(CommandSender sender) {
        log("=== START Command Execution ===");

        Optional<Round> existingOpt = roundService.getCurrentRound();
        log("Checking for existing round: " + (existingOpt.isPresent() ? "Found" : "None"));

        if (existingOpt.isPresent()) {
            Round existing = existingOpt.get();
            if (existing.status() != Round.RoundStatus.COMPLETED) {
                sender.sendMessage(ChatColor.RED + "A round is already active (ID: " + existing.roundId() + ")");
                log("Cannot start - round " + existing.roundId() + " is still " + existing.status());
                return;
            }
            log("Previous round " + existing.roundId() + " is completed, proceeding");
        }

        World mainWorld = Bukkit.getWorlds().get(0);
        long worldSeed = mainWorld.getSeed();
        log("World seed obtained: " + worldSeed);

        sender.sendMessage(ChatColor.YELLOW + "Starting new round with seed: " + worldSeed);
        log("Creating new round in database...");

        Round newRound = roundService.startNewRound(worldSeed);
        log("New round created: ID=" + newRound.roundId() + ", Status=" + newRound.status());

        log("Resetting all team memberships...");
        teamService.resetAllTeams();
        log("Team memberships cleared");

        // Immediately generate and persist region names for deterministic behavior
        if (regionRenderer != null) {
            try {
                log("Triggering immediate region name generation for round " + newRound.roundId());
                regionRenderer.generateAndPersistNamesForCurrentRound(mainWorld);
                log("Immediate region name generation complete for round " + newRound.roundId());

                // Immediately refresh BlueMap markers so the map reflects the new names without restart
                try {
                    regionRenderer.refreshMarkers(mainWorld);
                    log("RegionRenderer.refreshMarkers called successfully");
                } catch (Exception e) {
                    log("Error while refreshing BlueMap markers: " + e.getMessage());
                }
            } catch (Exception e) {
                log("Error during immediate region name generation: " + e.getMessage());
            }
        } else {
            log("RegionRenderer not present - skipping immediate name generation");
        }

        sender.sendMessage(ChatColor.GREEN + "Round " + newRound.roundId() + " started!");
        sender.sendMessage(ChatColor.GRAY + "All players have been removed from teams.");
        sender.sendMessage(ChatColor.GRAY + "Players will be prompted to select teams on next join.");

        log("Broadcasting round start to all online players...");
        Bukkit.getOnlinePlayers().forEach(player -> {
            player.sendTitle(
                    ChatColor.GOLD + "Round " + newRound.roundId(),
                    ChatColor.YELLOW + "Phase 1 has begun!",
                    10, 70, 20
            );
            log("Title sent to player: " + player.getName());
        });

        // Update scoreboards for all currently-online players so they immediately see region/team changes
        if (scoreboardUtil != null) {
            Bukkit.getOnlinePlayers().forEach(player -> {
                try {
                    scoreboardUtil.updatePlayerScoreboard(player);
                } catch (Exception e) {
                    log("Failed to update scoreboard for player " + player.getName() + ": " + e.getMessage());
                }
            });
        }

        // Restart phase scheduler for the new round
        if (phaseScheduler != null) {
            phaseScheduler.start();
            log("PhaseScheduler restarted for new round");
        }

        log("=== START Command Complete ===");
    }

    private void handleNew(CommandSender sender) {
        log("=== NEW Command Execution ===");

        if (newRoundInitializer == null) {
            sender.sendMessage(ChatColor.RED + "New round system is not configured.");
            log("NewRoundInitializer not set");
            return;
        }

        if (newRoundInitializer.isInitializationInProgress()) {
            sender.sendMessage(ChatColor.RED + "A new round initialization is already in progress!");
            log("Initialization already in progress");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Initiating new round with fresh world...");
        sender.sendMessage(ChatColor.YELLOW + "This will:");
        sender.sendMessage(ChatColor.GRAY + "  • Kick all players");
        sender.sendMessage(ChatColor.GRAY + "  • Regenerate the world with a new seed");
        sender.sendMessage(ChatColor.GRAY + "  • Pregenerate chunks with Chunky (~10 mins)");
        sender.sendMessage(ChatColor.GRAY + "  • Reset all teams and spawns");
        sender.sendMessage(ChatColor.GRAY + "  • Generate new region names");
        sender.sendMessage(ChatColor.YELLOW + "Players will be able to rejoin in ~20-30 minutes.");

        newRoundInitializer.initiateNewRound(sender);

        log("=== NEW Command Initiated ===");
    }

    private void handlePhase(CommandSender sender) {
        log("=== PHASE Command Execution ===");

        Optional<Round> currentOpt = roundService.getCurrentRound();
        log("Current round check: " + (currentOpt.isPresent() ? "Found" : "Not found"));

        if (currentOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No active round to advance.");
            log("No active round available");
            return;
        }

        Round current = currentOpt.get();
        log("Current round: ID=" + current.roundId() + ", Phase=" + current.currentPhase() +
                ", Status=" + current.status());

        if (current.status() != Round.RoundStatus.ACTIVE) {
            sender.sendMessage(ChatColor.RED + "Round is not active (Status: " + current.status() + ")");
            log("Round status prevents phase advancement: " + current.status());
            return;
        }

        log("Attempting to advance phase...");
        RoundService.PhaseResult result = roundService.advancePhase();
        log("Phase advancement result: " + result);

        switch (result) {
            case ADVANCED -> {
                int newPhase = current.currentPhase() + 1;
                sender.sendMessage(ChatColor.GREEN + "Advanced to Phase " + newPhase);
                log("Successfully advanced to phase " + newPhase);

                log("Broadcasting phase change to all online players...");
                Bukkit.getOnlinePlayers().forEach(player -> {
                    player.sendTitle(
                            ChatColor.AQUA + "Phase " + newPhase,
                            ChatColor.GRAY + "New phase has begun!",
                            10, 70, 20
                    );
                    log("Phase title sent to: " + player.getName());
                });

                // Update scoreboards for all players to reflect the new phase
                log("Updating scoreboards for all online players...");
                if (scoreboardUtil != null) {
                    Bukkit.getOnlinePlayers().forEach(player -> {
                        try {
                            scoreboardUtil.updatePlayerScoreboard(player);
                            log("Scoreboard updated for player: " + player.getName());
                        } catch (Exception e) {
                            log("Failed to update scoreboard for player " + player.getName() + ": " + e.getMessage());
                        }
                    });
                }

                // Restart phase scheduler for the next phase
                if (phaseScheduler != null) {
                    phaseScheduler.start();
                    log("PhaseScheduler restarted for next phase");
                }
            }
            case ROUND_ENDED -> {
                sender.sendMessage(ChatColor.YELLOW + "Phase " + configManager.getMaxPhases() + " completed. Use /round end <team> to declare a winner.");
                log("Maximum phases reached, awaiting round end command");

                // Stop phase scheduler since round is at max phase
                if (phaseScheduler != null) {
                    phaseScheduler.stop();
                }
            }
            case NO_ACTIVE_ROUND -> {
                sender.sendMessage(ChatColor.RED + "No active round found.");
                log("ERROR: No active round (unexpected state)");
            }
            case ALREADY_ENDED -> {
                sender.sendMessage(ChatColor.RED + "Round has already ended.");
                log("Round already completed");
            }
        }

        log("=== PHASE Command Complete ===");
    }

    private void handleEnd(CommandSender sender, String[] args) {
        log("=== END Command Execution ===");

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /round end <red|blue>");
            log("Missing winning team argument");
            return;
        }

        String winningTeam = args[1].toLowerCase();
        log("Winning team specified: " + winningTeam);

        if (!winningTeam.equals("red") && !winningTeam.equals("blue")) {
            sender.sendMessage(ChatColor.RED + "Team must be 'red' or 'blue'.");
            log("Invalid team specified: " + winningTeam);
            return;
        }

        Optional<Round> currentOpt = roundService.getCurrentRound();
        log("Current round check: " + (currentOpt.isPresent() ? "Found" : "Not found"));

        if (currentOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No active round to end.");
            log("No round available to end");
            return;
        }

        Round current = currentOpt.get();
        log("Ending round: ID=" + current.roundId() + ", Status=" + current.status());

        log("Updating round status to COMPLETED...");
        roundService.endRound(winningTeam);
        log("Round marked as completed with winner: " + winningTeam);

        // Clear teams at the end of the round so everyone must reselect on next join
        try {
            log("Clearing all team memberships as the round ended...");
            teamService.resetAllTeams();
            log("All team memberships cleared via TeamService");

            // Update scoreboards for all online players so they see they have no team
            if (scoreboardUtil != null) {
                Bukkit.getOnlinePlayers().forEach(p -> {
                    try {
                        scoreboardUtil.updatePlayerScoreboard(p);
                    } catch (Throwable t) {
                        log("Failed to update scoreboard for player " + p.getName() + ": " + t.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log("Failed to clear team memberships on round end: " + e.getMessage());
        }

        sender.sendMessage(ChatColor.GREEN + "Round " + current.roundId() + " ended!");
        sender.sendMessage(ChatColor.GOLD + "Winner: " + getTeamColor(winningTeam) + winningTeam.toUpperCase());

        log("Broadcasting round end to all online players...");
        Bukkit.getOnlinePlayers().forEach(player -> {
            player.sendTitle(
                    ChatColor.GOLD + "Round Ended!",
                    getTeamColor(winningTeam) + winningTeam.toUpperCase() + ChatColor.YELLOW + " wins!",
                    10, 100, 20
            );
            log("End title sent to: " + player.getName());
        });

        // Stop phase scheduler since round ended
        if (phaseScheduler != null) {
            phaseScheduler.stop();
            log("PhaseScheduler stopped for ended round");
        }

        log("=== END Command Complete ===");
    }

    private void handleHistory(CommandSender sender) {
        log("=== HISTORY Command Execution ===");

        var history = roundService.getRoundHistory();
        log("Retrieved " + history.size() + " rounds from history");

        if (history.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No rounds have been played yet.");
            log("No historical rounds found");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Round History ===");

        for (Round round : history) {
            log("Processing round for display: ID=" + round.roundId());

            sender.sendMessage(ChatColor.YELLOW + "Round " + round.roundId() +
                    " - " + getStatusColor(round.status()) + round.status());
            sender.sendMessage(ChatColor.GRAY + "  Started: " + dateFormat.format(new Date(round.startTime())));

            if (round.endTime() > 0) {
                sender.sendMessage(ChatColor.GRAY + "  Ended: " + dateFormat.format(new Date(round.endTime())));
                long duration = round.endTime() - round.startTime();
                sender.sendMessage(ChatColor.GRAY + "  Duration: " + formatDuration(duration));
            }

            if (round.winningTeam() != null) {
                sender.sendMessage(ChatColor.GRAY + "  Winner: " +
                        getTeamColor(round.winningTeam()) + round.winningTeam());
            }

            sender.sendMessage("");
        }

        log("=== HISTORY Command Complete ===");
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    private ChatColor getStatusColor(Round.RoundStatus status) {
        return switch (status) {
            case ACTIVE -> ChatColor.GREEN;
            case COMPLETED -> ChatColor.GRAY;
            case PENDING -> ChatColor.YELLOW;
        };
    }

    private ChatColor getTeamColor(String team) {
        return team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
    }

    private void log(String message) {
        Bukkit.getLogger().info("[RoundCommand] " + message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return Collections.emptyList();

        if (args.length == 1) {
            List<String> options = Arrays.asList("info", "start", "new", "phase", "end", "history");
            return options.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "end" -> Arrays.asList("red", "blue", "draw").stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
                case "new" -> Collections.singletonList("confirm");
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }
}
