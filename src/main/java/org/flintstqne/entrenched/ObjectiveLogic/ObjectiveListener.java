package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Listener for objective-related events.
 * Tracks block placement, destruction, and kills for objective progress.
 */
public class ObjectiveListener implements Listener {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final ObjectiveUIManager uiManager;
    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager config;

    private BukkitTask refreshTask;
    private BukkitTask holdGroundTask;
    private RoundService roundService;

    public ObjectiveListener(JavaPlugin plugin, ObjectiveService objectiveService,
                              ObjectiveUIManager uiManager, RegionService regionService,
                              TeamService teamService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.uiManager = uiManager;
        this.regionService = regionService;
        this.teamService = teamService;
        this.config = config;

        // Set up callbacks
        objectiveService.setCompletionCallback((objective, playerUuid, team) -> {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                uiManager.showCompletionNotification(player, objective);
            }
        });

        objectiveService.setSpawnCallback(uiManager::showSpawnNotification);
    }

    /**
     * Sets the round service for getting the game world.
     */
    public void setRoundService(RoundService roundService) {
        this.roundService = roundService;
    }

    /**
     * Starts the objective refresh task.
     */
    public void start() {
        // Refresh objectives periodically
        int refreshMinutes = config.getObjectiveRefreshMinutes();
        long refreshTicks = refreshMinutes * 60L * 20L;

        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin,
                objectiveService::refreshAllObjectives,
                refreshTicks, refreshTicks);

        // Initial spawn of objectives
        Bukkit.getScheduler().runTaskLater(plugin,
                objectiveService::refreshAllObjectives,
                100L); // 5 seconds after start

        // Start hold ground tick task (every second = 20 ticks)
        holdGroundTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::tickHoldGround,
                20L, 20L);

        plugin.getLogger().info("[Objectives] Listener started, refresh every " + refreshMinutes + " minutes");
    }

    /**
     * Stops the objective tasks.
     */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (holdGroundTask != null) {
            holdGroundTask.cancel();
        }
    }

    /**
     * Ticks the hold ground objectives by checking all player positions.
     */
    private void tickHoldGround() {
        // Get game world
        World gameWorld = null;
        if (roundService != null) {
            gameWorld = roundService.getGameWorld().orElse(null);
        }
        if (gameWorld == null) return;

        // Collect player data
        Map<UUID, ObjectiveService.HoldGroundPlayerData> playerData = new HashMap<>();

        for (Player player : gameWorld.getPlayers()) {
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (teamOpt.isEmpty()) continue;

            String regionId = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );
            if (regionId == null) continue;

            playerData.put(player.getUniqueId(), new ObjectiveService.HoldGroundPlayerData(
                    regionId,
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ(),
                    teamOpt.get()
            ));
        }

        // Tick the objectives
        if (!playerData.isEmpty()) {
            objectiveService.tickHoldGroundObjectives(playerData);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = event.getBlock().getX();
        int y = event.getBlock().getY();
        int z = event.getBlock().getZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        String blockType = event.getBlock().getType().name();

        // Notify objective service
        objectiveService.onBlockDestroyed(player.getUniqueId(), team, regionId, x, y, z, blockType);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = event.getBlock().getX();
        int y = event.getBlock().getY();
        int z = event.getBlock().getZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        String blockType = event.getBlock().getType().name();

        // Notify objective service
        objectiveService.onBlockPlaced(player.getUniqueId(), team, regionId, x, y, z, blockType);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        int x = victim.getLocation().getBlockX();
        int z = victim.getLocation().getBlockZ();

        String regionId = regionService.getRegionIdForLocation(x, z);
        if (regionId == null) return;

        // Notify objective service
        objectiveService.onPlayerKill(killer.getUniqueId(), victim.getUniqueId(), regionId);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        uiManager.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        uiManager.onPlayerQuit(event.getPlayer());
    }
}

