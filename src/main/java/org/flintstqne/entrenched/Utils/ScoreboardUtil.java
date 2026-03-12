package org.flintstqne.entrenched.Utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.MeritLogic.MeritRank;
import org.flintstqne.entrenched.MeritLogic.MeritService;
import org.flintstqne.entrenched.MeritLogic.PlayerMeritData;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveCategory;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveService;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType;
import org.flintstqne.entrenched.ObjectiveLogic.RegionObjective;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoadLogic.RoadService;
import org.flintstqne.entrenched.RoadLogic.SupplyLevel;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundEndgameManager;
import org.flintstqne.entrenched.RoundLogic.RoundEndgameState;
import org.flintstqne.entrenched.RoundLogic.EndgameStage;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ScoreboardUtil {

    private static final long UPDATE_INTERVAL_TICKS = 5L; // 0.25 seconds for real-time compass updates
    private static final long OBJECTIVE_CACHE_MS = 2000L; // Cache objectives for 2 seconds

    private final TeamService teamService;
    private final RegionRenderer regionRenderer;
    private final RoundService roundService;
    private final RegionService regionService;
    private final ConfigManager configManager;
    private RoadService roadService; // May be set after construction
    private MeritService meritService; // May be set after construction
    private ObjectiveService objectiveService; // May be set after construction
    private RoundEndgameManager endgameManager; // May be set after construction

    private BukkitTask updateTask;
    private int updateFrame = 0; // Frame counter for animation

    // Cache for objectives to reduce DB calls
    private final Map<String, CachedObjectives> objectiveCache = new ConcurrentHashMap<>();

    private record CachedObjectives(List<RegionObjective> objectives, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > OBJECTIVE_CACHE_MS;
        }
    }

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
     * Sets the MeritService for rank/token display.
     * Called after MeritService is initialized.
     */
    public void setMeritService(MeritService meritService) {
        this.meritService = meritService;
    }

    /**
     * Sets the ObjectiveService for objective compass display.
     * Called after ObjectiveService is initialized.
     */
    public void setObjectiveService(ObjectiveService objectiveService) {
        this.objectiveService = objectiveService;
    }

    /**
     * Sets the RoundEndgameManager for endgame state display.
     * Called after endgame system is initialized.
     */
    public void setEndgameManager(RoundEndgameManager endgameManager) {
        this.endgameManager = endgameManager;
    }

    /**
     * Invalidates the objective cache for a region.
     * Call this when objectives are spawned, completed, or changed.
     */
    public void invalidateObjectiveCache(String regionId) {
        objectiveCache.entrySet().removeIf(e -> e.getKey().startsWith(regionId + ":"));
    }

    /**
     * Clears the entire objective cache.
     */
    public void clearObjectiveCache() {
        objectiveCache.clear();
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
            updateFrame++; // Increment frame  for compass animation

            // Clean expired cache entries periodically (every ~5 seconds)
            if (updateFrame % 100 == 0) {
                objectiveCache.entrySet().removeIf(e -> e.getValue().isExpired());
            }

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

        // === Endgame Status (if active) ===
        String endgameLine = getEndgameStatusLine();
        if (endgameLine != null) {
            Score endgameScore = objective.getScore(endgameLine);
            endgameScore.setScore(scoreIndex--);
        }

        // Separator
        Score separator1 = objective.getScore(ChatColor.YELLOW + "" + ChatColor.BOLD + "-----------------");
        separator1.setScore(scoreIndex--);

        // Line: Team
        Score teamLine = objective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "Team: " + teamColor + teamDisplay);
        teamLine.setScore(scoreIndex--);

        // Line: Merit Rank (if MeritService available)
        if (meritService != null) {
            Optional<PlayerMeritData> meritData = meritService.getPlayerData(player.getUniqueId());
            if (meritData.isPresent()) {
                PlayerMeritData data = meritData.get();
                MeritRank rank = data.getRank();

                // Rank line
                Score rankLine = objective.getScore(ChatColor.GRAY + "Rank: " + rank.getFormattedTag() + " " + ChatColor.GRAY + rank.getDisplayName());
                rankLine.setScore(scoreIndex--);

                // Tokens available
                Score tokenLine = objective.getScore(ChatColor.GRAY + "Tokens: " + ChatColor.AQUA + data.tokenBalance() + ChatColor.DARK_GRAY + " available");
                tokenLine.setScore(scoreIndex--);

                // Progress to next rank
                MeritRank nextRank = rank.getNextRank();
                if (nextRank != null) {
                    int toNext = data.getMeritsToNextRank();
                    Score progressLine = objective.getScore(ChatColor.DARK_GRAY + "Next: " + toNext + " merits → " + nextRank.getTag());
                    progressLine.setScore(scoreIndex--);
                }
            }
        }

        Score separator2 = objective.getScore(ChatColor.DARK_GRAY + "- - - - - - - - -");
        separator2.setScore(scoreIndex--);

        // Line: Region Name
        Score regionLine = objective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "Region: " + ChatColor.GRAY + regionName);
        regionLine.setScore(scoreIndex--);

        // === Region Status Section ===
        if (regionStatus != null) {
            // Separator


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

        // === Objective Compass Section ===
        if (objectiveService != null && regionId != null && playerTeam != null) {
            String compassLine = getObjectiveCompassLine(player, regionId, playerTeam, regionStatus);
            if (compassLine != null) {
                Score separator3 = objective.getScore(ChatColor.DARK_GRAY + "· · · · · · · · ·");
                separator3.setScore(scoreIndex--);

                // Display single-line compass
                Score compassScore = objective.getScore(compassLine);
                compassScore.setScore(scoreIndex--);
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

    /**
     * Gets a single-line objective compass for display on the scoreboard.
     * Format: "⚒ ◀ · ✦ · · ▶  45m" showing direction and distance
     *
     * Shows compass for:
     * - Attackers: pointing to objectives they need to complete
     * - Defenders: pointing to objectives enemies are actively working on (with progress > 0)
     */
    private String getObjectiveCompassLine(Player player, String regionId, String playerTeam, RegionStatus regionStatus) {
        if (objectiveService == null || regionStatus == null) return null;

        List<RegionObjective> objectives = null;
        boolean isDefender = false;

        // Check if player is a defender (owns this region)
        if (regionStatus.ownerTeam() != null && regionStatus.ownerTeam().equalsIgnoreCase(playerTeam)) {
            isDefender = true;
            // For defenders: show RAID objectives that have progress (enemies actively working on them)
            // For Hold Ground: only show when enemies are CURRENTLY in the hold zone
            List<RegionObjective> raidObjectives = getCachedObjectives(regionId, ObjectiveCategory.RAID);
            objectives = raidObjectives.stream()
                    .filter(obj -> {
                        if (obj.type() == ObjectiveType.RAID_HOLD_GROUND) {
                            return isEnemyInHoldZone(player, regionId, regionStatus);
                        }
                        return obj.progress() > 0;
                    })
                    .toList();
        } else {
            // For attackers: check if region is valid for capturing
            if (!isRegionValidForCapture(regionId, playerTeam, regionStatus)) {
                return null;
            }

            // Determine which category of objectives to show
            ObjectiveCategory category = getRelevantCategory(regionStatus, playerTeam);
            if (category == null) return null;

            objectives = getCachedObjectives(regionId, category);
        }

        if (objectives == null || objectives.isEmpty()) return null;

        // Find nearest objective with a location
        RegionObjective nearest = null;
        Location nearestLoc = null;
        double nearestDist = Double.MAX_VALUE;
        World world = player.getWorld();

        for (RegionObjective obj : objectives) {
            Location objLoc = null;

            if (obj.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(obj.regionId());
                if (holdZoneOpt.isPresent()) {
                    int[] holdZone = holdZoneOpt.get();
                    int y = world.getHighestBlockYAt(holdZone[0], holdZone[1]) + 1;
                    objLoc = new Location(world, holdZone[0] + 0.5, y, holdZone[1] + 0.5);
                }
            } else if (obj.hasLocation()) {
                objLoc = obj.getLocation(world);
            }

            if (objLoc == null) continue;

            double dist = player.getLocation().distance(objLoc);
            if (dist < nearestDist) {
                nearest = obj;
                nearestLoc = objLoc;
                nearestDist = dist;
            }
        }

        // Build the single-line compass
        // For defenders: show shield icon with warning color
        // For attackers: show attack/build icon
        String symbol;
        ChatColor symbolColor;
        if (isDefender) {
            symbol = "🛡";  // Shield icon for defense
            symbolColor = ChatColor.YELLOW;  // Warning color
        } else if (nearest != null && nearest.type().isRaid()) {
            symbol = "⚔";
            symbolColor = ChatColor.RED;
        } else {
            symbol = "⚒";
            symbolColor = ChatColor.GREEN;
        }

        if (nearest == null || nearestLoc == null) {
            // No location - just show objective exists
            return symbolColor + symbol + ChatColor.GRAY + " · · " + ChatColor.YELLOW + "✦" + ChatColor.GRAY + " · ·";
        }

        // Calculate direction and build compass
        double relativeAngle = getRelativeAngle(player, nearestLoc);
        int dist = (int) nearestDist;
        String compass = buildSingleLineCompass(relativeAngle);

        // Add invisible color codes that change each frame to force scoreboard update
        // This ensures Minecraft re-renders the line even if text appears the same
        String frameMarker = getInvisibleFrameMarker();

        return symbolColor + symbol + " " + compass + " " + ChatColor.WHITE + dist + "m" + frameMarker;
    }

    /**
     * Gets cached objectives for a region+category, refreshing from DB if cache is expired.
     */
    private List<RegionObjective> getCachedObjectives(String regionId, ObjectiveCategory category) {
        String cacheKey = regionId + ":" + category.name();
        CachedObjectives cached = objectiveCache.get(cacheKey);

        if (cached == null || cached.isExpired()) {
            List<RegionObjective> fresh = objectiveService.getActiveObjectives(regionId, category);
            objectiveCache.put(cacheKey, new CachedObjectives(fresh, System.currentTimeMillis()));
            return fresh;
        }

        return cached.objectives();
    }

    /**
     * Creates an invisible marker that changes each frame to force scoreboard line updates.
     * Uses invisible color code combinations that differ per frame.
     */
    private String getInvisibleFrameMarker() {
        // Use color codes that are invisible but unique per frame
        // This forces Minecraft to treat each update as a new line
        int frameIndex = updateFrame % 16;
        return ChatColor.values()[frameIndex].toString() + ChatColor.RESET;
    }

    /**
     * Builds a single-line compass visual: "◀ · ✦ · ▶" with direction highlighted
     */
    private String buildSingleLineCompass(double relativeAngle) {
        String dim = ChatColor.DARK_GRAY + "·";
        String you = ChatColor.YELLOW + "✦";

        String left2, left1, center, right1, right2;

        // Determine which direction to highlight
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
            // Straight ahead
            left2 = dim; left1 = dim;
            center = ChatColor.GREEN + "▲";
            right1 = dim; right2 = dim;
        } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
            // Front-right
            left2 = dim; left1 = dim;
            center = you;
            right1 = ChatColor.YELLOW + "↗"; right2 = dim;
        } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
            // Right
            left2 = dim; left1 = dim;
            center = you;
            right1 = dim; right2 = ChatColor.YELLOW + "▶";
        } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
            // Back-right
            left2 = dim; left1 = dim;
            center = you;
            right1 = ChatColor.RED + "↘"; right2 = dim;
        } else if (relativeAngle >= 157.5 || relativeAngle < -157.5) {
            // Behind
            left2 = dim; left1 = dim;
            center = ChatColor.RED + "▼";
            right1 = dim; right2 = dim;
        } else if (relativeAngle >= -157.5 && relativeAngle < -112.5) {
            // Back-left
            left2 = dim; left1 = ChatColor.RED + "↙";
            center = you;
            right1 = dim; right2 = dim;
        } else if (relativeAngle >= -112.5 && relativeAngle < -67.5) {
            // Left
            left2 = ChatColor.YELLOW + "◀"; left1 = dim;
            center = you;
            right1 = dim; right2 = dim;
        } else {
            // Front-left
            left2 = dim; left1 = ChatColor.YELLOW + "↖";
            center = you;
            right1 = dim; right2 = dim;
        }

        return left2 + " " + left1 + " " + center + " " + right1 + " " + right2;
    }

    /**
     * Gets the relative angle from the player to the target location.
     */
    private double getRelativeAngle(Player player, Location targetLoc) {
        Location playerLoc = player.getLocation();
        double dx = targetLoc.getX() - playerLoc.getX();
        double dz = targetLoc.getZ() - playerLoc.getZ();

        float yaw = playerLoc.getYaw();
        double angleToObj = Math.toDegrees(Math.atan2(-dx, dz));
        double relativeAngle = angleToObj - yaw;

        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        return relativeAngle;
    }

    /**
     * Determines which objective category is relevant for the player in this region.
     */
    private ObjectiveCategory getRelevantCategory(RegionStatus status, String playerTeam) {
        if (status.state() == RegionState.NEUTRAL) {
            return ObjectiveCategory.SETTLEMENT;
        }
        if (status.state() == RegionState.OWNED || status.state() == RegionState.CONTESTED) {
            return ObjectiveCategory.RAID;
        }
        return null;
    }

    /**
     * Checks if a region is valid for the team to capture/influence.
     * A region is valid if:
     * - It's adjacent to territory the team owns, OR
     * - It's a contested region where the team is the original owner (defending)
     */
    private boolean isRegionValidForCapture(String regionId, String playerTeam, RegionStatus status) {
        // If team already owns this region fully, they can't "capture" it
        if (status.isOwnedBy(playerTeam)) {
            return false;
        }

        // Check if region is adjacent to team's territory
        if (regionService.isAdjacentToTeam(regionId, playerTeam)) {
            return true;
        }

        // Special case: contested region where this team is the original owner
        if (status.state() == RegionState.CONTESTED && playerTeam.equalsIgnoreCase(status.ownerTeam())) {
            return true;
        }

        return false;
    }

    /**
     * Checks if any enemy player is currently inside the hold ground zone for a region,
     * AND their team owns an adjacent region (meaning they can actually capture this region).
     */
    private boolean isEnemyInHoldZone(Player viewer, String regionId, RegionStatus status) {
        if (objectiveService == null || teamService == null) return false;

        Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(regionId);
        if (holdZoneOpt.isEmpty()) return false;

        int[] holdZone = holdZoneOpt.get();
        int holdRadius = configManager.getRegionSize() / 8;
        String defenderTeam = status.ownerTeam();
        if (defenderTeam == null) return false;

        for (Player otherPlayer : viewer.getWorld().getPlayers()) {
            if (otherPlayer.getUniqueId().equals(viewer.getUniqueId())) continue;
            Optional<String> otherTeamOpt = teamService.getPlayerTeam(otherPlayer.getUniqueId());
            if (otherTeamOpt.isEmpty()) continue;
            String enemyTeam = otherTeamOpt.get();
            if (enemyTeam.equalsIgnoreCase(defenderTeam)) continue;

            // Enemy must own an adjacent region to actually be a threat
            if (!regionService.isAdjacentToTeam(regionId, enemyTeam)) continue;

            double dist = Math.sqrt(
                    Math.pow(otherPlayer.getLocation().getX() - holdZone[0], 2) +
                    Math.pow(otherPlayer.getLocation().getZ() - holdZone[1], 2));
            if (dist <= holdRadius) {
                return true;
            }
        }
        return false;
    }


    /**
     * Truncates a name to fit on the scoreboard.
     */
    private String truncateName(String name, int maxLength) {
        if (name.length() <= maxLength) return name;
        return name.substring(0, maxLength - 2) + "..";
    }

    /**
     * Gets the endgame status line for display on scoreboard.
     * Returns null if no special endgame state is active.
     */
    private String getEndgameStatusLine() {
        if (endgameManager == null) return null;

        Optional<RoundEndgameState> stateOpt = endgameManager.getState();
        if (stateOpt.isEmpty()) return null;

        RoundEndgameState state = stateOpt.get();
        if (state.stage() == EndgameStage.NORMAL) {
            return null; // No special display during normal play
        }

        switch (state.stage()) {
            case EARLY_WIN_HOLD -> {
                String team = state.earlyWinTeam();
                ChatColor teamColor = "RED".equalsIgnoreCase(team) ? ChatColor.RED : ChatColor.BLUE;
                long remaining = state.getEarlyWinHoldRemainingSeconds(30 * 60 * 1000L);
                String timeStr = formatSeconds(remaining);
                return ChatColor.GOLD + "⚔ BREAKTHROUGH: " + teamColor + team + " " + ChatColor.GRAY + timeStr;
            }
            case OVERTIME -> {
                String targetRegion = state.overtimeRegionId();
                String regionName = endgameManager.getRegionDisplayName(targetRegion);
                if (regionName.length() > 10) {
                    regionName = regionName.substring(0, 8) + "..";
                }
                long remaining = state.getOvertimeRemainingSeconds();
                String timeStr = formatSeconds(remaining);

                if (state.overtimeHoldTeam() != null) {
                    ChatColor holdColor = "RED".equalsIgnoreCase(state.overtimeHoldTeam()) ? ChatColor.RED : ChatColor.BLUE;
                    long holdTime = state.getOvertimeHoldSeconds();
                    return ChatColor.GOLD + "⏱ OT: " + ChatColor.YELLOW + regionName +
                            " " + holdColor + "●" + ChatColor.GRAY + " " + holdTime + "s/" + timeStr;
                }
                return ChatColor.GOLD + "⏱ OT: " + ChatColor.YELLOW + regionName + ChatColor.GRAY + " " + timeStr;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Formats seconds into MM:SS format.
     */
    private String formatSeconds(long seconds) {
        if (seconds < 0) return "0:00";
        long mins = seconds / 60;
        long secs = seconds % 60;
        return String.format("%d:%02d", mins, secs);
    }
}
