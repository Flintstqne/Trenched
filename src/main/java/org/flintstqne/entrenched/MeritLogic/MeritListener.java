package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for game events and awards merit tokens accordingly.
 */
public class MeritListener implements Listener {

    private final JavaPlugin plugin;
    private final MeritService meritService;
    private final TeamService teamService;
    private final RegionService regionService;
    private final RoundService roundService;
    private final ConfigManager configManager;
    private MeritNametagManager nametagManager;

    // Kill streak tracking
    private final Map<UUID, Integer> killStreaks = new ConcurrentHashMap<>();

    // Track session start times for playtime calculation
    private final Map<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();

    // Track first blood
    private final Set<Integer> firstBloodAwarded = ConcurrentHashMap.newKeySet();

    // Interaction tracking: players in same region
    private final Map<String, Set<UUID>> playersInRegion = new ConcurrentHashMap<>();

    public MeritListener(JavaPlugin plugin, MeritService meritService, TeamService teamService,
                         RegionService regionService, RoundService roundService, ConfigManager configManager) {
        this.plugin = plugin;
        this.meritService = meritService;
        this.teamService = teamService;
        this.regionService = regionService;
        this.roundService = roundService;
        this.configManager = configManager;

        // Start periodic tasks
        startInteractionTracker();
        startPlaytimeTracker();
    }

    /**
     * Sets the nametag manager for rank display above heads.
     */
    public void setNametagManager(MeritNametagManager nametagManager) {
        this.nametagManager = nametagManager;
    }

    // ==================== PLAYER JOIN/QUIT ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Ensure player data exists
        meritService.getOrCreatePlayerData(uuid);

        // Track session start
        sessionStartTimes.put(uuid, System.currentTimeMillis());

        // Award daily login token (must play 15+ min, handled by playtime tracker)
        // For now, just record the login
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
        meritService.onDailyLogin(uuid, roundId);

        // Update nametag
        if (nametagManager != null) {
            nametagManager.onPlayerJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Calculate session playtime
        Long startTime = sessionStartTimes.remove(uuid);
        if (startTime != null) {
            long sessionMinutes = (System.currentTimeMillis() - startTime) / 60000;
            if (sessionMinutes > 0) {
                Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
                meritService.onPlaytimeUpdate(uuid, (int) sessionMinutes, roundId);
            }
        }

        // Clear kill streak
        killStreaks.remove(uuid);

        // Cleanup nametag tracking
        if (nametagManager != null) {
            nametagManager.onPlayerQuit(player);
        }
    }

    // ==================== COMBAT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) return;

        UUID killerUuid = killer.getUniqueId();
        UUID victimUuid = victim.getUniqueId();

        // Check teams
        Optional<String> killerTeamOpt = teamService.getPlayerTeam(killerUuid);
        Optional<String> victimTeamOpt = teamService.getPlayerTeam(victimUuid);

        if (killerTeamOpt.isEmpty() || victimTeamOpt.isEmpty()) return;

        String killerTeam = killerTeamOpt.get();
        String victimTeam = victimTeamOpt.get();

        // No merit for team kills
        if (killerTeam.equalsIgnoreCase(victimTeam)) return;

        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);

        // Determine if in enemy territory
        String regionId = regionService.getRegionIdForLocation(
                victim.getLocation().getBlockX(),
                victim.getLocation().getBlockZ()
        );

        boolean inEnemyTerritory = false;
        if (regionId != null) {
            var status = regionService.getRegionStatus(regionId);
            if (status.isPresent()) {
                String owner = status.get().ownerTeam();
                inEnemyTerritory = owner != null && owner.equalsIgnoreCase(victimTeam);
            }
        }

        // Award kill tokens
        meritService.onPlayerKill(killerUuid, victimUuid, inEnemyTerritory, roundId);

        // Check for first blood
        if (roundId != null && firstBloodAwarded.add(roundId)) {
            meritService.onFirstBlood(killerUuid, roundId);
        }

        // Update kill streak
        int streak = killStreaks.merge(killerUuid, 1, Integer::sum);
        if (streak == 5 || streak == 10) {
            meritService.onKillStreak(killerUuid, streak, roundId);
        }

        // Check for shutdown (victim had 5+ streak)
        Integer victimStreak = killStreaks.get(victimUuid);
        if (victimStreak != null && victimStreak >= 5) {
            meritService.onShutdown(killerUuid, roundId);
        }

        // Reset victim's streak
        killStreaks.remove(victimUuid);
    }

    // ==================== INTERACTION TRACKING ====================

    /**
     * Periodically tracks which players are in the same region.
     * This enables the "interaction requirement" for giving merits.
     */
    private void startInteractionTracker() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Clear old data
            playersInRegion.clear();

            // Group players by region
            for (Player player : Bukkit.getOnlinePlayers()) {
                String regionId = regionService.getRegionIdForLocation(
                        player.getLocation().getBlockX(),
                        player.getLocation().getBlockZ()
                );

                if (regionId != null) {
                    playersInRegion
                            .computeIfAbsent(regionId, k -> ConcurrentHashMap.newKeySet())
                            .add(player.getUniqueId());
                }
            }

            // Record interactions for players in same region
            for (Set<UUID> players : playersInRegion.values()) {
                if (players.size() < 2) continue;

                List<UUID> playerList = new ArrayList<>(players);
                for (int i = 0; i < playerList.size(); i++) {
                    for (int j = i + 1; j < playerList.size(); j++) {
                        UUID p1 = playerList.get(i);
                        UUID p2 = playerList.get(j);

                        // Find region ID for this pair
                        Player player1 = Bukkit.getPlayer(p1);
                        if (player1 != null) {
                            String regionId = regionService.getRegionIdForLocation(
                                    player1.getLocation().getBlockX(),
                                    player1.getLocation().getBlockZ()
                            );
                            meritService.recordInteraction(p1, p2, regionId);
                        }
                    }
                }
            }
        }, 600L, 600L); // Every 30 seconds
    }

    /**
     * Periodically updates playtime for online players.
     */
    private void startPlaytimeTracker() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);

            for (Player player : Bukkit.getOnlinePlayers()) {
                // Add 5 minutes of playtime (runs every 5 minutes)
                // Only if player is not AFK (basic check: has moved recently)
                // For simplicity, we'll count all online time
                meritService.onPlaytimeUpdate(player.getUniqueId(), 5, roundId);
            }
        }, 6000L, 6000L); // Every 5 minutes (6000 ticks)
    }

    // ==================== PUBLIC METHODS FOR OTHER SYSTEMS ====================

    /**
     * Called by RegionCaptureListener when a region is captured.
     */
    public void onRegionCaptured(String regionId, String newOwner, List<UUID> contributors,
                                  Map<UUID, Double> ipContributions) {
        if (contributors.isEmpty()) return;

        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);

        // Determine if region was neutral or enemy
        boolean wasNeutral = ipContributions.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum() < 1000; // Rough check based on IP threshold

        // Sort by contribution to find top 3
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(ipContributions.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Set<UUID> topContributors = new HashSet<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            topContributors.add(sorted.get(i).getKey());
        }

        // Award tokens to all contributors
        for (UUID contributor : contributors) {
            double ip = ipContributions.getOrDefault(contributor, 0.0);
            boolean isTop = topContributors.contains(contributor);
            meritService.onRegionCapture(contributor, wasNeutral, ip, isTop, roundId);
        }
    }

    /**
     * Called by RegionCaptureListener when a region is successfully defended.
     */
    public void onRegionDefended(String regionId, List<UUID> defenders) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);

        for (UUID defender : defenders) {
            meritService.onRegionDefend(defender, roundId);
        }
    }

    /**
     * Called by RoadListener when road blocks are placed.
     */
    public void onRoadBlocksPlaced(UUID player, int blockCount) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
        meritService.onRoadBlocksPlaced(player, blockCount, roundId);
    }

    /**
     * Called when a supply route is completed.
     */
    public void onSupplyRouteCompleted(UUID player) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
        meritService.onSupplyRouteComplete(player, roundId);
    }

    /**
     * Called when a region reaches 100% supply for the first time.
     */
    public void onRegionFullySupplied(UUID player) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
        meritService.onRegionSupplied(player, roundId);
    }

    /**
     * Called when enemy supply is disrupted.
     */
    public void onSupplyDisrupted(UUID player, int regionsAffected) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);
        meritService.onSupplyDisrupted(player, regionsAffected, roundId);
    }

    /**
     * Called when a round completes.
     */
    public void onRoundComplete(Map<UUID, Double> playerIpTotals) {
        Integer roundId = roundService.getCurrentRound().map(r -> r.roundId()).orElse(null);

        for (Map.Entry<UUID, Double> entry : playerIpTotals.entrySet()) {
            meritService.onRoundComplete(entry.getKey(), entry.getValue(), roundId);
        }

        // Reset first blood tracking for next round
        if (roundId != null) {
            firstBloodAwarded.remove(roundId);
        }
    }

    /**
     * Resets kill streaks (e.g., at round start).
     */
    public void resetKillStreaks() {
        killStreaks.clear();
    }
}

