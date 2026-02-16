// src/main/java/org/flintstqne/terrainGen/TeamLogic/TeamListener.java
package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

public final class TeamListener implements Listener {

    private final TeamService teamService;
    private final ScoreboardUtil scoreboardUtil;
    private final JavaPlugin plugin;

    public TeamListener(TeamService teamService, ScoreboardUtil scoreboardUtil, JavaPlugin plugin) {
        this.teamService = teamService;
        this.scoreboardUtil = scoreboardUtil;
        this.plugin = plugin;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        teamService.getPlayerTeam(player.getUniqueId())
                .flatMap(teamService::getTeamSpawn)
                .ifPresent(event::setRespawnLocation);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Schedule an update shortly after join so scoreboard is set once player is fully initialized
        if (scoreboardUtil != null && plugin != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                try {
                    scoreboardUtil.updatePlayerScoreboard(player);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to update scoreboard for " + player.getName() + ": " + t.getMessage());
                }
            }, 40L);
        }
    }
}
