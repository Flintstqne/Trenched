package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.util.Optional;

/**
 * Handles automatic phase advancement based on configured time intervals.
 */
public final class PhaseScheduler {

    private final JavaPlugin plugin;
    private final RoundService roundService;
    private final ConfigManager configManager;
    private final ScoreboardUtil scoreboardUtil;

    private BukkitTask scheduledTask;
    private long nextPhaseTime; // Unix timestamp when next phase should occur

    public PhaseScheduler(
            JavaPlugin plugin,
            RoundService roundService,
            ConfigManager configManager,
            ScoreboardUtil scoreboardUtil
    ) {
        this.plugin = plugin;
        this.roundService = roundService;
        this.configManager = configManager;
        this.scoreboardUtil = scoreboardUtil;
    }

    /**
     * Starts or restarts the phase scheduler.
     * Call this on plugin enable and after round changes.
     */
    public void start() {
        stop(); // Cancel any existing task

        if (!configManager.isAutoPhaseEnabled()) {
            log("Auto phase advancement is disabled (phase-duration-minutes = 0)");
            return;
        }

        Optional<Round> currentRoundOpt = roundService.getCurrentRound();
        if (currentRoundOpt.isEmpty()) {
            log("No active round - phase scheduler not started");
            return;
        }

        Round round = currentRoundOpt.get();
        if (round.status() != Round.RoundStatus.ACTIVE) {
            log("Round is not active (status: " + round.status() + ") - scheduler not started");
            return;
        }

        int currentPhase = round.currentPhase();
        int maxPhases = configManager.getMaxPhases();

        // Calculate when the current phase ends
        long phaseDurationMillis = configManager.getPhaseDurationMillis();
        long phaseStartTime = calculateCurrentPhaseStartTime(round);
        nextPhaseTime = phaseStartTime + phaseDurationMillis;

        long currentTime = System.currentTimeMillis();
        long delayMillis = nextPhaseTime - currentTime;

        // Check if we're at max phase
        if (currentPhase >= maxPhases) {
            // We're at the final phase - schedule conclusion instead of advancement
            if (delayMillis <= 0) {
                // Final phase already elapsed
                log("Final phase time has already elapsed - announcing conclusion");
                broadcastMaxPhaseReached();
                return;
            }

            long delayTicks = (delayMillis / 1000) * 20;
            log("Phase scheduler started (FINAL PHASE):");
            log("  Current phase: " + currentPhase + "/" + maxPhases + " (FINAL)");
            log("  Phase duration: " + configManager.getPhaseDurationMinutes() + " minutes");
            log("  Phase concludes in: " + formatDuration(delayMillis));

            scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                log("Final phase duration elapsed - announcing conclusion");
                broadcastMaxPhaseReached();
            }, delayTicks);
            return;
        }

        if (delayMillis <= 0) {
            // Phase should have already advanced - do it now
            log("Phase time has already elapsed - advancing immediately");
            advancePhase();
            return;
        }

        // Convert to ticks (1 tick = 50ms, so 20 ticks = 1 second)
        long delayTicks = (delayMillis / 1000) * 20;

        log("Phase scheduler started:");
        log("  Current phase: " + currentPhase);
        log("  Phase duration: " + configManager.getPhaseDurationMinutes() + " minutes");
        log("  Next phase in: " + formatDuration(delayMillis));

        // Schedule the phase advancement
        scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, this::advancePhase, delayTicks);
    }

    /**
     * Stops the phase scheduler.
     */
    public void stop() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel();
            scheduledTask = null;
            log("Phase scheduler stopped");
        }
    }

    /**
     * Gets the time until the next phase advancement.
     * @return milliseconds until next phase, or -1 if not scheduled
     */
    public long getTimeUntilNextPhase() {
        if (scheduledTask == null || scheduledTask.isCancelled()) {
            return -1;
        }
        return Math.max(0, nextPhaseTime - System.currentTimeMillis());
    }

    /**
     * Gets a formatted string of time until next phase.
     */
    public String getFormattedTimeUntilNextPhase() {
        long millis = getTimeUntilNextPhase();
        if (millis < 0) return "N/A";
        return formatDuration(millis);
    }

    private void advancePhase() {
        log("Auto-advancing phase...");

        Optional<Round> currentRoundOpt = roundService.getCurrentRound();
        if (currentRoundOpt.isEmpty()) {
            log("No active round found - cannot advance phase");
            return;
        }

        Round round = currentRoundOpt.get();
        int currentPhase = round.currentPhase();
        int maxPhases = configManager.getMaxPhases();

        if (currentPhase >= maxPhases) {
            log("Already at max phase (" + currentPhase + "/" + maxPhases + ")");
            broadcastMaxPhaseReached();
            return;
        }

        RoundService.PhaseResult result = roundService.advancePhase();
        log("Phase advancement result: " + result);

        switch (result) {
            case ADVANCED -> {
                int newPhase = currentPhase + 1;
                broadcastPhaseAdvanced(newPhase);
                updateAllScoreboards();

                // Reschedule for next phase OR schedule final phase conclusion
                if (newPhase < maxPhases) {
                    // More phases to go - schedule next advancement
                    start();
                } else {
                    // This is the final phase - schedule the conclusion announcement
                    log("Final phase (" + newPhase + ") started - scheduling conclusion in " +
                            configManager.getPhaseDurationMinutes() + " minutes");
                    scheduleFinalPhaseConclusion();
                }
            }
            case ROUND_ENDED -> {
                log("Round ended during phase advancement");
                broadcastMaxPhaseReached();
            }
            case NO_ACTIVE_ROUND -> log("No active round - phase not advanced");
            case ALREADY_ENDED -> log("Round already ended - phase not advanced");
        }
    }

    /**
     * Schedules the final phase conclusion announcement.
     * This runs after the final phase's full duration has elapsed.
     */
    private void scheduleFinalPhaseConclusion() {
        long phaseDurationTicks = configManager.getPhaseDurationTicks();

        scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            log("Final phase duration elapsed - announcing conclusion");
            broadcastMaxPhaseReached();
            scheduledTask = null;
        }, phaseDurationTicks);

        // Update next phase time for display purposes
        nextPhaseTime = System.currentTimeMillis() + configManager.getPhaseDurationMillis();
    }

    private long calculateCurrentPhaseStartTime(Round round) {
        // Phase start time = round start + (phase-1) * duration
        // Phase 1 starts at round start
        // Phase 2 starts at round start + 1 * duration
        // etc.
        long phaseDuration = configManager.getPhaseDurationMillis();
        return round.startTime() + ((round.currentPhase() - 1) * phaseDuration);
    }

    private void broadcastPhaseAdvanced(int newPhase) {
        String message = configManager.formatMessage(
                configManager.getPhaseAdvancedMessage(),
                "phase", newPhase
        );

        Bukkit.broadcastMessage(configManager.getPrefix() + message);

        // Send title to all players
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(
                    ChatColor.AQUA + "Phase " + newPhase,
                    ChatColor.GRAY + "A new phase has begun!",
                    10, 70, 20
            );
        }
    }

    private void broadcastMaxPhaseReached() {
        Bukkit.broadcastMessage(configManager.getPrefix() +
                ChatColor.YELLOW + "Final phase reached! Awaiting round conclusion...");
    }

    private void updateAllScoreboards() {
        if (scoreboardUtil == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                scoreboardUtil.updatePlayerScoreboard(player);
            } catch (Exception e) {
                log("Failed to update scoreboard for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) return days + "d " + (hours % 24) + "h " + (minutes % 60) + "m";
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }

    private void log(String message) {
        plugin.getLogger().info("[PhaseScheduler] " + message);
    }
}

