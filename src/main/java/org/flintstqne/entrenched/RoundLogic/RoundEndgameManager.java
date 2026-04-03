package org.flintstqne.entrenched.RoundLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages endgame evaluation including:
 * - Early win detection (10/14 regions, +4 lead, 30 min hold)
 * - Regulation end evaluation
 * - Overtime management
 */
public class RoundEndgameManager {

    private final JavaPlugin plugin;
    private final EndgameDb db;
    private final RoundService roundService;
    private final RegionService regionService;
    private final ConfigManager config;
    private RegionRenderer regionRenderer;

    // Configuration constants
    private static final int EARLY_WIN_MIN_REGIONS = 10;        // Need 10 of 14 non-home regions
    private static final int EARLY_WIN_MIN_LEAD = 4;            // Need +4 region lead
    private static final long EARLY_WIN_HOLD_MS = 30 * 60 * 1000L; // 30 minutes
    private static final int EARLY_WIN_MIN_PHASE = 2;           // Only from Phase 2 onward

    private static final long OVERTIME_DURATION_MS = 15 * 60 * 1000L; // 15 minutes
    private static final long OVERTIME_HOLD_WIN_MS = 5 * 60 * 1000L;  // 5 minutes to win

    private static final long HEAT_WINDOW_MS = 30 * 60 * 1000L; // 30 minute rolling window
    private static final double CAPTURE_HEAT_BONUS = 500.0;     // Bonus heat on capture

    // Tick interval (5 seconds)
    private static final long TICK_INTERVAL_TICKS = 100L;

    // Heat tracking for overtime target selection
    // regionId -> list of (timestamp, heatDelta) pairs
    private final Map<String, List<HeatEntry>> regionHeatHistory = new ConcurrentHashMap<>();

    // Cached state
    private RoundEndgameState currentState;
    private BukkitTask tickTask;

    // Callback for round end
    private RoundEndCallback roundEndCallback;

    public RoundEndgameManager(JavaPlugin plugin, EndgameDb db, RoundService roundService,
                                RegionService regionService, ConfigManager config) {
        this.plugin = plugin;
        this.db = db;
        this.roundService = roundService;
        this.regionService = regionService;
        this.config = config;
    }

    public void setRegionRenderer(RegionRenderer regionRenderer) {
        this.regionRenderer = regionRenderer;
    }

    public void setRoundEndCallback(RoundEndCallback callback) {
        this.roundEndCallback = callback;
    }

    /**
     * Starts the endgame manager for the current round.
     */
    public void start() {
        Optional<Round> roundOpt = roundService.getCurrentRound();
        if (roundOpt.isEmpty()) {
            plugin.getLogger().warning("[Endgame] No active round to manage");
            return;
        }

        int roundId = roundOpt.get().roundId();
        currentState = db.getOrCreate(roundId);

        // Start tick task
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);

        plugin.getLogger().info("[Endgame] Manager started for round " + roundId + " (stage: " + currentState.stage() + ")");
    }

    /**
     * Stops the endgame manager.
     */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        regionHeatHistory.clear();
        currentState = null;
        plugin.getLogger().info("[Endgame] Manager stopped");
    }

    /**
     * Forces a round end with a specific winner (admin command).
     */
    public void forceEnd(String winner) {
        if (currentState != null) {
            db.delete(currentState.roundId());
        }
        stop();

        if (roundEndCallback != null) {
            roundEndCallback.onRoundEnd(winner);
        }
    }

    /**
     * Gets the current endgame state.
     */
    public Optional<RoundEndgameState> getState() {
        return Optional.ofNullable(currentState);
    }

    /**
     * Gets territory counts for display.
     * Returns [redCount, blueCount] of non-home controlled regions.
     */
    public int[] getTerritoryCount() {
        int red = 0;
        int blue = 0;

        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            if (isHomeRegion(status.regionId())) continue;
            if (!status.state().isControlled()) continue;

            String owner = status.ownerTeam();
            if ("RED".equalsIgnoreCase(owner)) red++;
            else if ("BLUE".equalsIgnoreCase(owner)) blue++;
        }

        return new int[]{red, blue};
    }

    /**
     * Gets the display name for a region.
     */
    public String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId;
    }

    // ==================== MAIN TICK ====================

    private void tick() {
        Optional<Round> roundOpt = roundService.getCurrentRound();
        if (roundOpt.isEmpty() || currentState == null) {
            return;
        }

        Round round = roundOpt.get();
        if (round.status() != Round.RoundStatus.ACTIVE) {
            return;
        }

        // Update heat tracking
        updateHeatTracking();

        // Process based on current stage
        switch (currentState.stage()) {
            case NORMAL -> tickNormal(round);
            case EARLY_WIN_HOLD -> tickEarlyWinHold(round);
            case OVERTIME -> tickOvertime();
        }
    }

    // ==================== NORMAL STAGE ====================

    private void tickNormal(Round round) {
        int phase = round.currentPhase();

        // Check for early win conditions (Phase 2+)
        if (phase >= EARLY_WIN_MIN_PHASE) {
            checkEarlyWinConditions();
        }

        // Check if regulation has ended (max phase reached and phase time expired)
        if (phase >= config.getMaxPhases()) {
            // PhaseScheduler handles phase expiry - we check if it's time to evaluate
            // This is triggered by PhaseScheduler calling onRegulationEnd()
        }
    }

    private void checkEarlyWinConditions() {
        int[] counts = getTerritoryCount();
        int red = counts[0];
        int blue = counts[1];

        String candidateTeam = null;
        if (red >= EARLY_WIN_MIN_REGIONS && (red - blue) >= EARLY_WIN_MIN_LEAD) {
            candidateTeam = "RED";
        } else if (blue >= EARLY_WIN_MIN_REGIONS && (blue - red) >= EARLY_WIN_MIN_LEAD) {
            candidateTeam = "BLUE";
        }

        if (candidateTeam != null) {
            // Start early win hold
            long now = System.currentTimeMillis();
            currentState = currentState.withEarlyWinHold(candidateTeam, now);
            db.save(currentState);

            broadcastEarlyWinStart(candidateTeam);
            plugin.getLogger().info("[Endgame] Early win hold started for " + candidateTeam +
                    " (regions: RED=" + red + ", BLUE=" + blue + ")");
        }
    }

    // ==================== EARLY WIN HOLD STAGE ====================

    private void tickEarlyWinHold(Round round) {
        int[] counts = getTerritoryCount();
        int red = counts[0];
        int blue = counts[1];

        String holdTeam = currentState.earlyWinTeam();
        boolean stillValid = false;

        if ("RED".equalsIgnoreCase(holdTeam)) {
            stillValid = red >= EARLY_WIN_MIN_REGIONS && (red - blue) >= EARLY_WIN_MIN_LEAD;
        } else if ("BLUE".equalsIgnoreCase(holdTeam)) {
            stillValid = blue >= EARLY_WIN_MIN_REGIONS && (blue - red) >= EARLY_WIN_MIN_LEAD;
        }

        if (!stillValid) {
            // Hold broken
            currentState = currentState.resetToNormal();
            db.save(currentState);

            broadcastEarlyWinBroken(holdTeam);
            plugin.getLogger().info("[Endgame] Early win hold broken for " + holdTeam);

            // Check if other team now qualifies
            checkEarlyWinConditions();
            return;
        }

        // Check for milestone broadcasts
        long remainingMs = currentState.getEarlyWinHoldRemainingSeconds(EARLY_WIN_HOLD_MS) * 1000;
        long elapsedMs = System.currentTimeMillis() - currentState.earlyWinStartedAt();

        // 15 minutes remaining (halfway point)
        if (elapsedMs >= 15 * 60 * 1000 && elapsedMs < 15 * 60 * 1000 + 5000) {
            broadcastEarlyWinMilestone(holdTeam, 15);
        }
        // 5 minutes remaining
        if (elapsedMs >= 25 * 60 * 1000 && elapsedMs < 25 * 60 * 1000 + 5000) {
            broadcastEarlyWinMilestone(holdTeam, 5);
        }

        // Check for completion
        if (elapsedMs >= EARLY_WIN_HOLD_MS) {
            // Early win achieved!
            broadcastEarlyWinVictory(holdTeam);
            plugin.getLogger().info("[Endgame] Early win achieved by " + holdTeam);

            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd(holdTeam);
            }
        }
    }

    // ==================== REGULATION END ====================

    /**
     * Called when regulation time expires (Phase 3 ends).
     * This should be called by PhaseScheduler.
     */
    public void onRegulationEnd() {
        if (currentState == null) return;

        // If already in overtime, ignore
        if (currentState.stage() == EndgameStage.OVERTIME) return;

        int[] counts = getTerritoryCount();
        int red = counts[0];
        int blue = counts[1];

        plugin.getLogger().info("[Endgame] Regulation ended - RED: " + red + ", BLUE: " + blue);

        if (red > blue) {
            broadcastRegulationWinner("RED", red, blue);
            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd("RED");
            }
        } else if (blue > red) {
            broadcastRegulationWinner("BLUE", blue, red);
            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd("BLUE");
            }
        } else {
            // Tied - start overtime
            startOvertime();
        }
    }

    // ==================== OVERTIME ====================

    private void startOvertime() {
        String targetRegion = selectOvertimeTarget();
        if (targetRegion == null) {
            plugin.getLogger().warning("[Endgame] Could not select overtime target - ending as draw");
            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd("DRAW");
            }
            return;
        }

        long now = System.currentTimeMillis();
        long endsAt = now + OVERTIME_DURATION_MS;

        currentState = currentState.withOvertime(targetRegion, now, endsAt);
        db.save(currentState);

        broadcastOvertimeStart(targetRegion);
        plugin.getLogger().info("[Endgame] Overtime started - target region: " + targetRegion);
    }

    private void tickOvertime() {
        if (currentState.overtimeRegionId() == null) return;

        String targetRegion = currentState.overtimeRegionId();
        long now = System.currentTimeMillis();

        // Check if overtime has expired
        if (currentState.overtimeEndsAt() != null && now >= currentState.overtimeEndsAt()) {
            resolveOvertimeExpired();
            return;
        }

        // Check current ownership of target region
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(targetRegion);
        if (statusOpt.isEmpty()) {
            plugin.getLogger().warning("[Endgame] Overtime target region not found: " + targetRegion);
            return;
        }

        RegionStatus status = statusOpt.get();
        String currentOwner = status.state().isControlled() ? status.ownerTeam() : null;

        // Update hold tracking
        String previousHoldTeam = currentState.overtimeHoldTeam();

        if (currentOwner != null && !currentOwner.equalsIgnoreCase(previousHoldTeam)) {
            // New owner - reset hold timer
            currentState = currentState.withOvertimeHold(currentOwner, now);
            db.save(currentState);

            broadcastOvertimeHoldChange(currentOwner);
            plugin.getLogger().info("[Endgame] Overtime hold started by " + currentOwner);
        } else if (currentOwner == null && previousHoldTeam != null) {
            // Region became neutral - clear hold
            currentState = currentState.withOvertimeHold(null, null);
            db.save(currentState);

            broadcastOvertimeNeutral();
            plugin.getLogger().info("[Endgame] Overtime region became neutral");
        }

        // Check for 5-minute hold win
        if (currentOwner != null && currentState.overtimeHoldStartedAt() != null) {
            long holdDuration = now - currentState.overtimeHoldStartedAt();
            if (holdDuration >= OVERTIME_HOLD_WIN_MS) {
                // Hold win achieved!
                broadcastOvertimeHoldWin(currentOwner);
                plugin.getLogger().info("[Endgame] Overtime hold win by " + currentOwner);

                if (roundEndCallback != null) {
                    roundEndCallback.onRoundEnd(currentOwner);
                }
            }
        }
    }

    private void resolveOvertimeExpired() {
        String targetRegion = currentState.overtimeRegionId();

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(targetRegion);
        String owner = null;

        if (statusOpt.isPresent()) {
            RegionStatus status = statusOpt.get();
            if (status.state().isControlled()) {
                owner = status.ownerTeam();
            }
        }

        if (owner != null) {
            broadcastOvertimeTimeWin(owner);
            plugin.getLogger().info("[Endgame] Overtime expired - winner: " + owner);

            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd(owner);
            }
        } else {
            broadcastOvertimeDraw();
            plugin.getLogger().info("[Endgame] Overtime expired - target neutral - DRAW");

            if (roundEndCallback != null) {
                roundEndCallback.onRoundEnd("DRAW");
            }
        }
    }

    // ==================== OVERTIME TARGET SELECTION ====================

    private String selectOvertimeTarget() {
        List<String> candidates = new ArrayList<>();

        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            String regionId = status.regionId();
            // Exclude home regions
            if (isHomeRegion(regionId)) continue;
            candidates.add(regionId);
        }

        if (candidates.isEmpty()) return null;

        // Score each candidate by heat
        Map<String, Double> heatScores = new HashMap<>();
        for (String regionId : candidates) {
            heatScores.put(regionId, calculateRegionHeat(regionId));
        }

        // Sort by heat descending, with tiebreakers
        candidates.sort((a, b) -> {
            double heatA = heatScores.get(a);
            double heatB = heatScores.get(b);

            if (Math.abs(heatA - heatB) > 0.01) {
                return Double.compare(heatB, heatA); // Higher heat first
            }

            // Tiebreaker 1: Prefer regions adjacent to both teams
            boolean aAdjBoth = isAdjacentToBothTeams(a);
            boolean bAdjBoth = isAdjacentToBothTeams(b);
            if (aAdjBoth != bAdjBoth) {
                return aAdjBoth ? -1 : 1;
            }

            // Tiebreaker 2: Higher capture count
            int capturesA = getCaptureCount(a);
            int capturesB = getCaptureCount(b);
            if (capturesA != capturesB) {
                return Integer.compare(capturesB, capturesA);
            }

            // Tiebreaker 3: More recently owned
            long ownedA = getOwnedSince(a);
            long ownedB = getOwnedSince(b);
            if (ownedA != ownedB) {
                return Long.compare(ownedB, ownedA); // Newer first
            }

            // Tiebreaker 4: Closer to map center
            double distA = distanceToCenter(a);
            double distB = distanceToCenter(b);
            if (Math.abs(distA - distB) > 0.01) {
                return Double.compare(distA, distB); // Closer first
            }

            // Tiebreaker 5: Lexicographic
            return a.compareTo(b);
        });

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private double calculateRegionHeat(String regionId) {
        List<HeatEntry> history = regionHeatHistory.get(regionId);
        if (history == null || history.isEmpty()) return 0.0;

        long cutoff = System.currentTimeMillis() - HEAT_WINDOW_MS;
        double total = 0.0;

        for (HeatEntry entry : history) {
            if (entry.timestamp >= cutoff) {
                total += entry.heat;
            }
        }

        return total;
    }

    /**
     * Records heat for a region. Called when influence changes or captures occur.
     */
    public void recordHeat(String regionId, double heat) {
        regionHeatHistory.computeIfAbsent(regionId, k -> new ArrayList<>())
                .add(new HeatEntry(System.currentTimeMillis(), heat));
    }

    /**
     * Records a capture event for heat tracking.
     */
    public void recordCapture(String regionId) {
        recordHeat(regionId, CAPTURE_HEAT_BONUS);
    }

    private void updateHeatTracking() {
        // Prune old heat entries
        long cutoff = System.currentTimeMillis() - HEAT_WINDOW_MS;
        regionHeatHistory.values().forEach(list ->
                list.removeIf(entry -> entry.timestamp < cutoff)
        );
    }

    // ==================== HELPER METHODS ====================

    private boolean isHomeRegion(String regionId) {
        String redHome = config.getRegionRedHome();
        String blueHome = config.getRegionBlueHome();
        return regionId.equalsIgnoreCase(redHome) || regionId.equalsIgnoreCase(blueHome);
    }

    private boolean isAdjacentToBothTeams(String regionId) {
        Set<String> adjacentTeams = new HashSet<>();

        for (String adj : regionService.getAdjacentRegions(regionId)) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(adj);
            if (statusOpt.isPresent() && statusOpt.get().state().isControlled()) {
                adjacentTeams.add(statusOpt.get().ownerTeam().toUpperCase());
            }
        }

        return adjacentTeams.contains("RED") && adjacentTeams.contains("BLUE");
    }

    private int getCaptureCount(String regionId) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        return statusOpt.map(RegionStatus::timesCaptured).orElse(0);
    }

    private long getOwnedSince(String regionId) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isPresent()) {
            return statusOpt.get().ownedSince();
        }
        return 0;
    }

    private double distanceToCenter(String regionId) {
        // Parse region ID to get grid position (A1 = 0,0, D4 = 3,3)
        if (regionId == null || regionId.length() < 2) return Double.MAX_VALUE;

        char col = regionId.charAt(0);
        int row;
        try {
            row = Integer.parseInt(regionId.substring(1)) - 1;
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }

        int colNum = col - 'A';

        // Center is at 1.5, 1.5 for a 4x4 grid
        double dx = colNum - 1.5;
        double dy = row - 1.5;

        return Math.sqrt(dx * dx + dy * dy);
    }

    // ==================== BROADCASTS ====================

    private void broadcastEarlyWinStart(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component message = Component.text("⚔ ", NamedTextColor.GOLD)
                .append(Component.text(team.toUpperCase(), color).decorate(TextDecoration.BOLD))
                .append(Component.text(" has achieved ", NamedTextColor.WHITE))
                .append(Component.text("BREAKTHROUGH", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.WHITE));

        Component subtitle = Component.text("Hold for 30 minutes to win early!", NamedTextColor.YELLOW);

        broadcastAll(message, subtitle, Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    private void broadcastEarlyWinMilestone(String team, int minutesRemaining) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component message = Component.text("⏰ ", NamedTextColor.YELLOW)
                .append(Component.text(team.toUpperCase(), color).decorate(TextDecoration.BOLD))
                .append(Component.text(" breakthrough: ", NamedTextColor.WHITE))
                .append(Component.text(minutesRemaining + " minutes", NamedTextColor.GOLD))
                .append(Component.text(" remaining!", NamedTextColor.WHITE));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        }
    }

    private void broadcastEarlyWinBroken(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component message = Component.text("⚔ ", NamedTextColor.YELLOW)
                .append(Component.text(team.toUpperCase(), color).decorate(TextDecoration.BOLD))
                .append(Component.text(" breakthrough ", NamedTextColor.WHITE))
                .append(Component.text("BROKEN", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.WHITE));

        broadcastAll(message, null, Sound.ENTITY_WITHER_DEATH);
    }

    private void broadcastEarlyWinVictory(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component title = Component.text(team.toUpperCase() + " WINS!", color).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Early Victory - Map Dominance!", NamedTextColor.GOLD);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private void broadcastRegulationWinner(String team, int winnerCount, int loserCount) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component title = Component.text(team.toUpperCase() + " WINS!", color).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Regulation Victory (" + winnerCount + "-" + loserCount + ")", NamedTextColor.GOLD);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private void broadcastOvertimeStart(String targetRegion) {
        String regionName = getRegionDisplayName(targetRegion);

        Component title = Component.text("⚔ OVERTIME ⚔", NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Target: " + regionName, NamedTextColor.WHITE);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);

            player.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.GOLD));
            player.sendMessage(Component.text("      ⚔ OVERTIME ACTIVATED ⚔", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("  Target Region: ", NamedTextColor.WHITE)
                    .append(Component.text(regionName, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)));
            player.sendMessage(Component.text("  Duration: ", NamedTextColor.WHITE)
                    .append(Component.text("15 minutes", NamedTextColor.AQUA)));
            player.sendMessage(Component.text("  Hold to Win: ", NamedTextColor.WHITE)
                    .append(Component.text("5 minutes continuous", NamedTextColor.GREEN)));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("  ⚠ No adjacency or fortification rules!", NamedTextColor.RED));
            player.sendMessage(Component.text("═══════════════════════════════════", NamedTextColor.GOLD));
        }
    }

    private void broadcastOvertimeHoldChange(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component message = Component.text("⏰ ", NamedTextColor.YELLOW)
                .append(Component.text(team.toUpperCase(), color).decorate(TextDecoration.BOLD))
                .append(Component.text(" now holds the overtime region!", NamedTextColor.WHITE));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 1.0f);
        }
    }

    private void broadcastOvertimeNeutral() {
        Component message = Component.text("⚠ ", NamedTextColor.RED)
                .append(Component.text("Overtime region is now ", NamedTextColor.WHITE))
                .append(Component.text("NEUTRAL", NamedTextColor.GRAY).decorate(TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.WHITE));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1.0f);
        }
    }

    private void broadcastOvertimeHoldWin(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component title = Component.text(team.toUpperCase() + " WINS!", color).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Overtime Hold Victory!", NamedTextColor.GOLD);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private void broadcastOvertimeTimeWin(String team) {
        NamedTextColor color = "RED".equalsIgnoreCase(team) ? NamedTextColor.RED : NamedTextColor.BLUE;

        Component title = Component.text(team.toUpperCase() + " WINS!", color).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Overtime Region Control!", NamedTextColor.GOLD);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    private void broadcastOvertimeDraw() {
        Component title = Component.text("DRAW", NamedTextColor.GRAY).decorate(TextDecoration.BOLD);
        Component subtitle = Component.text("Overtime expired with neutral target", NamedTextColor.WHITE);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_DEATH, 1.0f, 0.5f);
        }
    }

    private void broadcastAll(Component message, Component subtitle, Sound sound) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
            if (subtitle != null) {
                player.showTitle(Title.title(Component.empty(), subtitle,
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(1))));
            }
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
    }

    // ==================== INNER CLASSES ====================

    private record HeatEntry(long timestamp, double heat) {}

    @FunctionalInterface
    public interface RoundEndCallback {
        void onRoundEnd(String winner);
    }
}

