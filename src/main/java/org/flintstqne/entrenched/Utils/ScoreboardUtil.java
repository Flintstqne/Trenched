package org.flintstqne.entrenched.Utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoadLogic.RoadService;
import org.flintstqne.entrenched.RoadLogic.SupplyLevel;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Optional;

public class ScoreboardUtil {

    private static final long UPDATE_INTERVAL_TICKS = 60L; // 3 seconds (20 ticks per second)

    private final TeamService teamService;
    private final RegionRenderer regionRenderer;
    private final RoundService roundService;
    private final RegionService regionService;
    private final ConfigManager configManager;
    private RoadService roadService; // May be set after construction

    private BukkitTask updateTask;

    public ScoreboardUtil(TeamService teamService, RegionRenderer regionRenderer,
                          RoundService roundService, RegionService regionService,
                          ConfigManager configManager) {
        this.teamService = teamService;
        this.regionRenderer = regionRenderer;
        this.roundService = roundService;
        this.regionService = regionService;
        this.configManager = configManager;
    }

    /**
     * Sets the RoadService for supply level display.
     * Called after RoadService is initialized.
     */
    public void setRoadService(RoadService roadService) {
        this.roadService = roadService;
    }

    /**
     * Starts the automatic scoreboard update task.
     * Call this once during plugin enable.
     */
    public void startUpdateTask(JavaPlugin plugin) {
        if (updateTask != null) {
            updateTask.cancel();
        }

        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerScoreboard(player);
            }
        }, UPDATE_INTERVAL_TICKS, UPDATE_INTERVAL_TICKS);
    }

    /**
     * Stops the automatic scoreboard update task.
     * Call this during plugin disable.
     */
    public void stopUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /**
     * Updates scoreboard for a player based on their current location.
     */
    public void updatePlayerScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                "blockhole",
                Criteria.DUMMY,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Blockhole"
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Get player's team
        Optional<String> teamIdOpt = teamService.getPlayerTeam(player.getUniqueId());
        ChatColor teamColor = ChatColor.GRAY;
        String teamDisplay = "None";
        String playerTeam = null;

        if (teamIdOpt.isPresent()) {
            String teamId = teamIdOpt.get();
            playerTeam = teamId;
            if (teamId.equalsIgnoreCase("red")) {
                teamColor = ChatColor.RED;
                teamDisplay = "Red";
            } else if (teamId.equalsIgnoreCase("blue")) {
                teamColor = ChatColor.BLUE;
                teamDisplay = "Blue";
            }
        }

        // Get current round info
        String warInfo = getWarPhaseInfo();

        // Get region info based on player's CURRENT location
        Location loc = player.getLocation();
        String regionId = null;
        String regionName = "Unknown";
        RegionStatus regionStatus = null;

        if (regionService != null) {
            regionId = regionService.getRegionIdForLocation(loc.getBlockX(), loc.getBlockZ());
            if (regionId != null) {
                regionStatus = regionService.getRegionStatus(regionId).orElse(null);
                // Get region name from renderer using the same regionId
                if (regionRenderer != null) {
                    regionName = regionRenderer.getRegionName(regionId).orElse(regionId);
                } else {
                    regionName = regionId;
                }
            }
        }

        int scoreIndex = 10;

        // Line: War and Phase info
        Score warLine = objective.getScore(warInfo);
        warLine.setScore(scoreIndex--);

        // Separator
        Score separator1 = objective.getScore(ChatColor.YELLOW + "" + ChatColor.BOLD + "-----------------");
        separator1.setScore(scoreIndex--);

        // Line: Team
        Score teamLine = objective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "Team: " + teamColor + teamDisplay);
        teamLine.setScore(scoreIndex--);

        // Line: Region Name
        Score regionLine = objective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "Region: " + ChatColor.GRAY + regionName);
        regionLine.setScore(scoreIndex--);

        // === Region Status Section ===
        if (regionStatus != null) {
            // Separator
            Score separator2 = objective.getScore(ChatColor.DARK_GRAY + "- - - - - - - - -");
            separator2.setScore(scoreIndex--);

            // Region Owner
            String ownerDisplay;
            String actualOwner = regionStatus.ownerTeam();
            if (actualOwner != null) {
                ChatColor ownerColor = actualOwner.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
                ownerDisplay = ownerColor + actualOwner.substring(0, 1).toUpperCase() +
                               actualOwner.substring(1);
            } else {
                ownerDisplay = ChatColor.WHITE + "Neutral";
            }
            Score ownerLine = objective.getScore(ChatColor.GRAY + "Owner: " + ownerDisplay);
            ownerLine.setScore(scoreIndex--);

            // Region State
            String stateDisplay = getStateDisplay(regionStatus.state());
            Score stateLine = objective.getScore(ChatColor.GRAY + "Status: " + stateDisplay);
            stateLine.setScore(scoreIndex--);

            // Show IP Progress if region is NOT owned by player's team
            if (playerTeam != null && !regionStatus.isOwnedBy(playerTeam)) {
                double myTeamIP = regionStatus.getInfluence(playerTeam);
                double required = getInfluenceRequired(regionStatus, playerTeam);
                int percentage = (int) ((myTeamIP / required) * 100);

                ChatColor ipColor = playerTeam.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
                Score ipLine = objective.getScore(ChatColor.GRAY + "Your IP: " + ipColor + (int)myTeamIP +
                        ChatColor.DARK_GRAY + "/" + (int)required + " (" + percentage + "%)");
                ipLine.setScore(scoreIndex--);
            }

            // Show supply efficiency ONLY if owned by player's team
            if (playerTeam != null && actualOwner != null &&
                actualOwner.equalsIgnoreCase(playerTeam)) {

                // Use RoadService for accurate supply levels
                if (roadService != null) {
                    SupplyLevel supplyLevel = roadService.getSupplyLevel(regionId, playerTeam);
                    String supplyDisplay = getSupplyDisplay(supplyLevel);
                    Score supplyLine = objective.getScore(ChatColor.GRAY + "Supply: " + supplyDisplay);
                    supplyLine.setScore(scoreIndex--);
                } else if (regionService != null) {
                    // Fallback to regionService if roadService not available
                    double supply = regionService.getSupplyEfficiency(regionId, playerTeam);
                    ChatColor supplyColor = supply >= 0.8 ? ChatColor.GREEN :
                            supply >= 0.5 ? ChatColor.YELLOW : ChatColor.RED;
                    Score supplyLine = objective.getScore(ChatColor.GRAY + "Supply: " + supplyColor + (int)(supply * 100) + "%");
                    supplyLine.setScore(scoreIndex--);
                }
            }
        }

        player.setScoreboard(scoreboard);
    }

    /**
     * @deprecated Use updatePlayerScoreboard(Player) instead. This method ignores the hint.
     */
    @Deprecated
    public void updatePlayerScoreboard(Player player, String regionNameHint) {
        updatePlayerScoreboard(player);
    }

    private String getStateDisplay(RegionState state) {
        return switch (state) {
            case NEUTRAL -> ChatColor.WHITE + "Neutral";
            case OWNED -> ChatColor.GREEN + "Owned";
            case CONTESTED -> ChatColor.YELLOW + "⚔ Contested";
            case FORTIFIED -> ChatColor.AQUA + "🛡 Fortified";
            case PROTECTED -> ChatColor.GOLD + "★ Home";
        };
    }

    private String getSupplyDisplay(SupplyLevel level) {
        return switch (level) {
            case SUPPLIED -> ChatColor.GREEN + "100% " + ChatColor.DARK_GREEN + "(Roads)";
            case PARTIAL -> ChatColor.YELLOW + "50% " + ChatColor.GOLD + "(No Roads)";
            case UNSUPPLIED -> ChatColor.RED + "25% " + ChatColor.DARK_RED + "(Cut Off)";
            case ISOLATED -> ChatColor.DARK_RED + "0% " + ChatColor.DARK_GRAY + "(Isolated)";
        };
    }

    private double getInfluenceRequired(RegionStatus status, String capturingTeam) {
        if (configManager == null) {
            return status.ownerTeam() == null ? 500 : 1000;
        }
        if (status.ownerTeam() == null) {
            return configManager.getRegionNeutralCaptureThreshold();
        }
        return configManager.getRegionEnemyCaptureThreshold();
    }

    private String getWarPhaseInfo() {
        Optional<Round> currentRound = roundService.getCurrentRound();

        if (currentRound.isEmpty()) {
            return ChatColor.WHITE + "" + ChatColor.BOLD + "War - " + ChatColor.GRAY + "None " +
                    ChatColor.WHITE + "" + ChatColor.BOLD + "| Phase - " + ChatColor.GRAY + "N/A";
        }

        Round round = currentRound.get();
        String warId = String.valueOf(round.roundId());
        String phase = String.valueOf(round.currentPhase());

        return ChatColor.WHITE + "" + ChatColor.BOLD + "War - " + ChatColor.GRAY + warId + " " +
                ChatColor.WHITE + "" + ChatColor.BOLD + "| Phase - " + ChatColor.GRAY + phase;
    }
}
