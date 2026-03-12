package org.flintstqne.entrenched.StatLogic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.Division;
import org.flintstqne.entrenched.DivisionLogic.DivisionMember;
import org.flintstqne.entrenched.DivisionLogic.DivisionRole;
import org.flintstqne.entrenched.DivisionLogic.DivisionService;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener that hooks into game events to track statistics.
 */
public class StatListener implements Listener {

    private final JavaPlugin plugin;
    private final StatService statService;
    private final TeamService teamService;
    private final RegionService regionService;
    private final RoundService roundService;
    private final ConfigManager config;

    private DivisionService divisionService;

    // Track session start times for time_played
    private final Map<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();

    // Track time in enemy territory (per minute tracking)
    private final Map<UUID, Long> enemyTerritoryTime = new ConcurrentHashMap<>();

    private BukkitTask timeTrackingTask;

    public StatListener(JavaPlugin plugin, StatService statService, TeamService teamService,
                        RegionService regionService, RoundService roundService, ConfigManager config) {
        this.plugin = plugin;
        this.statService = statService;
        this.teamService = teamService;
        this.regionService = regionService;
        this.roundService = roundService;
        this.config = config;
    }

    public void setDivisionService(DivisionService divisionService) {
        this.divisionService = divisionService;
    }

    /**
     * Starts the time tracking task for time_played and time_in_enemy_territory.
     */
    public void startTimeTracking() {
        // Track time every minute
        timeTrackingTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int roundId = getCurrentRoundId();
            if (roundId < 0) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                String name = player.getName();

                // Increment time played by 1 minute
                statService.incrementStat(uuid, name, StatCategory.TIME_PLAYED, 1, roundId);

                // Check if in enemy territory
                Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
                if (teamOpt.isPresent()) {
                    String team = teamOpt.get();
                    String regionId = regionService.getRegionIdForLocation(
                            player.getLocation().getBlockX(),
                            player.getLocation().getBlockZ()
                    );

                    if (regionId != null) {
                        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                        if (statusOpt.isPresent()) {
                            RegionStatus status = statusOpt.get();
                            // Check if enemy territory
                            if (status.ownerTeam() != null && !status.ownerTeam().equalsIgnoreCase(team)) {
                                statService.incrementStat(uuid, name, StatCategory.TIME_IN_ENEMY_TERRITORY, 1, roundId);
                            }
                        }
                    }
                }
            }
        }, 1200L, 1200L); // Every minute (1200 ticks)
    }

    /**
     * Stops the time tracking task.
     */
    public void stopTimeTracking() {
        if (timeTrackingTask != null) {
            timeTrackingTask.cancel();
            timeTrackingTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // Track session start
        sessionStartTimes.put(uuid, System.currentTimeMillis());

        // Get team and round
        Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
        String team = teamOpt.orElse(null);
        int roundId = getCurrentRoundId();

        // Handle login in stat service
        statService.handlePlayerLogin(uuid, name, roundId, team);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Calculate session time and add to time_played
        Long sessionStart = sessionStartTimes.remove(uuid);
        if (sessionStart != null) {
            long sessionMinutes = (System.currentTimeMillis() - sessionStart) / (1000 * 60);
            // Time is already tracked per-minute, so this is just cleanup
        }

        enemyTerritoryTime.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Only track player vs player damage
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        double damage = event.getFinalDamage();
        int roundId = getCurrentRoundId();

        // Record damage for assist tracking
        statService.recordDamage(attacker.getUniqueId(), victim.getUniqueId(), damage);

        // Track damage dealt/taken
        statService.incrementStat(attacker.getUniqueId(), attacker.getName(),
                StatCategory.DAMAGE_DEALT, damage, roundId);
        statService.incrementStat(victim.getUniqueId(), victim.getName(),
                StatCategory.DAMAGE_TAKEN, damage, roundId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        int roundId = getCurrentRoundId();

        if (killer != null && !killer.equals(victim)) {
            // Check if killer and victim are on different teams
            Optional<String> killerTeamOpt = teamService.getPlayerTeam(killer.getUniqueId());
            Optional<String> victimTeamOpt = teamService.getPlayerTeam(victim.getUniqueId());

            if (killerTeamOpt.isPresent() && victimTeamOpt.isPresent() &&
                !killerTeamOpt.get().equalsIgnoreCase(victimTeamOpt.get())) {

                // Check if commander kill
                boolean isCommanderKill = isCommanderOrOfficer(victim.getUniqueId());

                // Record kill
                statService.recordKill(
                        killer.getUniqueId(), killer.getName(),
                        victim.getUniqueId(), victim.getName(),
                        roundId, isCommanderKill
                );

                // Record death
                statService.recordDeath(
                        victim.getUniqueId(), victim.getName(),
                        killer.getUniqueId(), roundId
                );
            }
        } else {
            // Non-PvP death (fall damage, void, etc.)
            statService.recordDeath(
                    victim.getUniqueId(), victim.getName(),
                    null, roundId
            );
        }
    }

    /**
     * Checks if a player is a commander or officer in their division.
     */
    private boolean isCommanderOrOfficer(UUID playerUuid) {
        if (divisionService == null) return false;

        Optional<DivisionMember> membershipOpt = divisionService.getMembership(playerUuid);
        if (membershipOpt.isEmpty()) return false;

        DivisionMember member = membershipOpt.get();
        return member.role() == DivisionRole.COMMANDER || member.role() == DivisionRole.OFFICER;
    }

    /**
     * Gets the current round ID.
     */
    private int getCurrentRoundId() {
        return roundService.getCurrentRound()
                .map(Round::roundId)
                .orElse(-1);
    }

    // === OBJECTIVE STAT RECORDING METHODS ===
    // These can be called from ObjectiveListener

    /**
     * Records an objective completion.
     */
    public void recordObjectiveCompleted(UUID playerUuid, String playerName, boolean isSettlement) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.OBJECTIVES_COMPLETED, 1, roundId);

        if (isSettlement) {
            statService.incrementStat(playerUuid, playerName, StatCategory.OBJECTIVES_SETTLEMENT, 1, roundId);
        } else {
            statService.incrementStat(playerUuid, playerName, StatCategory.OBJECTIVES_RAID, 1, roundId);
        }
    }

    /**
     * Records intel captured.
     */
    public void recordIntelCaptured(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.INTEL_CAPTURED, 1, roundId);
        recordObjectiveCompleted(playerUuid, playerName, false);
    }

    /**
     * Records intel recovered (defender).
     */
    public void recordIntelRecovered(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.INTEL_RECOVERED, 1, roundId);
    }

    /**
     * Records TNT planted.
     */
    public void recordTntPlanted(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.TNT_PLANTED, 1, roundId);
        recordObjectiveCompleted(playerUuid, playerName, false);
    }

    /**
     * Records TNT defused.
     */
    public void recordTntDefused(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.TNT_DEFUSED, 1, roundId);
    }

    /**
     * Records supply cache destroyed.
     */
    public void recordSupplyCacheDestroyed(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.SUPPLY_CACHES_DESTROYED, 1, roundId);
        recordObjectiveCompleted(playerUuid, playerName, false);
    }

    /**
     * Records hold ground win.
     */
    public void recordHoldGroundWin(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.HOLD_GROUND_WINS, 1, roundId);
        recordObjectiveCompleted(playerUuid, playerName, false);
    }

    /**
     * Records hold ground defend.
     */
    public void recordHoldGroundDefend(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.HOLD_GROUND_DEFENDS, 1, roundId);
    }

    /**
     * Records resource depot established.
     */
    public void recordResourceDepotEstablished(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.RESOURCE_DEPOTS_ESTABLISHED, 1, roundId);
        recordObjectiveCompleted(playerUuid, playerName, true);
    }

    // === BUILDING STAT RECORDING METHODS ===

    /**
     * Records a building constructed.
     */
    public void recordBuildingConstructed(UUID playerUuid, String playerName, String buildingType) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.BUILDINGS_CONSTRUCTED, 1, roundId);

        // Track specific building types
        switch (buildingType.toLowerCase()) {
            case "outpost" -> statService.incrementStat(playerUuid, playerName, StatCategory.OUTPOSTS_BUILT, 1, roundId);
            case "watchtower" -> statService.incrementStat(playerUuid, playerName, StatCategory.WATCHTOWERS_BUILT, 1, roundId);
            case "garrison" -> statService.incrementStat(playerUuid, playerName, StatCategory.GARRISONS_BUILT, 1, roundId);
        }
    }

    /**
     * Records a building destroyed.
     */
    public void recordBuildingDestroyed(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.BUILDINGS_DESTROYED, 1, roundId);
    }

    /**
     * Records a depot placed.
     */
    public void recordDepotPlaced(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.DEPOTS_PLACED, 1, roundId);
    }

    /**
     * Records a depot raided.
     */
    public void recordDepotRaided(UUID playerUuid, String playerName, double lootValue) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.DEPOTS_RAIDED, 1, roundId);
        statService.incrementStat(playerUuid, playerName, StatCategory.DEPOT_LOOT_VALUE, lootValue, roundId);
    }

    /**
     * Records road segments built.
     */
    public void recordRoadsBuilt(UUID playerUuid, String playerName, int count) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.ROADS_BUILT, count, roundId);
    }

    /**
     * Records roads damaged.
     */
    public void recordRoadsDamaged(UUID playerUuid, String playerName, int count) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.ROADS_DAMAGED, count, roundId);
    }

    /**
     * Records banners placed.
     */
    public void recordBannerPlaced(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.BANNERS_PLACED, 1, roundId);
    }

    /**
     * Records containers stocked.
     */
    public void recordContainerStocked(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.CONTAINERS_STOCKED, 1, roundId);
    }

    /**
     * Revokes a container stocked stat (when container is emptied below threshold).
     */
    public void revokeContainerStocked(UUID playerUuid) {
        int roundId = getCurrentRoundId();
        statService.decrementStat(playerUuid, StatCategory.CONTAINERS_STOCKED, 1, roundId);
    }

    // === TERRITORY STAT RECORDING METHODS ===

    /**
     * Records a region captured.
     */
    public void recordRegionCaptured(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.REGIONS_CAPTURED, 1, roundId);
    }

    /**
     * Records a region contested.
     */
    public void recordRegionContested(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.REGIONS_CONTESTED, 1, roundId);
    }

    /**
     * Records a region defended.
     */
    public void recordRegionDefended(UUID playerUuid, String playerName) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.REGIONS_DEFENDED, 1, roundId);
    }

    /**
     * Records IP earned.
     */
    public void recordIPEarned(UUID playerUuid, String playerName, double amount) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.IP_EARNED, amount, roundId);
    }

    /**
     * Records IP denied (defensive actions).
     */
    public void recordIPDenied(UUID playerUuid, String playerName, double amount) {
        int roundId = getCurrentRoundId();
        statService.incrementStat(playerUuid, playerName, StatCategory.IP_DENIED, amount, roundId);
    }
}

