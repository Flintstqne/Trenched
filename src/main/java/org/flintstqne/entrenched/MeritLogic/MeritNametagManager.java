package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player name tags to display merit rank above their head.
 * Uses scoreboard teams to add prefixes to player names.
 */
public class MeritNametagManager {

    private final JavaPlugin plugin;
    private final MeritService meritService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    // Track last known rank for each player to avoid unnecessary updates
    private final ConcurrentHashMap<UUID, MeritRank> lastKnownRanks = new ConcurrentHashMap<>();

    private BukkitTask updateTask;

    public MeritNametagManager(JavaPlugin plugin, MeritService meritService,
                                TeamService teamService, ConfigManager configManager) {
        this.plugin = plugin;
        this.meritService = meritService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    /**
     * Starts the periodic nametag update task.
     */
    public void start() {
        if (!configManager.showRankNametag()) {
            plugin.getLogger().info("[MeritNametagManager] Rank nametags disabled in config");
            return;
        }

        // Update every 5 seconds (100 ticks)
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAllNametags, 20L, 100L);
        plugin.getLogger().info("[MeritNametagManager] Started nametag update task");
    }

    /**
     * Stops the periodic update task.
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /**
     * Updates nametags for all online players.
     */
    public void updateAllNametags() {
        if (!configManager.showRankNametag()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerNametag(player);
        }
    }

    /**
     * Updates a single player's nametag.
     */
    public void updatePlayerNametag(Player player) {
        if (!configManager.showRankNametag()) return;

        UUID uuid = player.getUniqueId();
        MeritRank currentRank = meritService.getPlayerRank(uuid);

        // Check if rank changed
        MeritRank lastRank = lastKnownRanks.get(uuid);
        if (lastRank == currentRank) {
            return; // No change needed
        }

        lastKnownRanks.put(uuid, currentRank);

        // Get player's team color
        Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
        ChatColor teamColor = ChatColor.GRAY;
        if (teamOpt.isPresent()) {
            String team = teamOpt.get();
            if (team.equalsIgnoreCase("red")) {
                teamColor = ChatColor.RED;
            } else if (team.equalsIgnoreCase("blue")) {
                teamColor = ChatColor.BLUE;
            }
        }

        // Build the prefix: [RANK] with team color for name
        String prefix = currentRank.getFormattedTag() + " " + teamColor;

        // Apply to all online players' scoreboards
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyNametagToViewer(player, viewer, prefix, teamColor);
        }
    }

    /**
     * Applies a player's nametag prefix as seen by a specific viewer.
     */
    private void applyNametagToViewer(Player target, Player viewer, String prefix, ChatColor teamColor) {
        Scoreboard scoreboard = viewer.getScoreboard();

        // Get or create team for this player's rank display
        String teamName = "mr_" + target.getName().substring(0, Math.min(target.getName().length(), 12));
        Team team = scoreboard.getTeam(teamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        // Set prefix (max 64 chars in modern versions)
        team.setPrefix(prefix);

        // Set team color for glowing effect and tab list
        team.setColor(teamColor);

        // Add the player to this team
        if (!team.hasEntry(target.getName())) {
            // Remove from other teams first
            for (Team t : scoreboard.getTeams()) {
                if (t.hasEntry(target.getName()) && !t.getName().equals(teamName)) {
                    t.removeEntry(target.getName());
                }
            }
            team.addEntry(target.getName());
        }
    }

    /**
     * Called when a player joins - sets up their nametag.
     */
    public void onPlayerJoin(Player player) {
        if (!configManager.showRankNametag()) return;

        // Delay slightly to ensure scoreboard is ready
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Update this player's nametag for all viewers
            updatePlayerNametag(player);

            // Update all other players' nametags for this new viewer
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) {
                    MeritRank rank = meritService.getPlayerRank(other.getUniqueId());
                    Optional<String> teamOpt = teamService.getPlayerTeam(other.getUniqueId());
                    ChatColor teamColor = ChatColor.GRAY;
                    if (teamOpt.isPresent()) {
                        String team = teamOpt.get();
                        if (team.equalsIgnoreCase("red")) {
                            teamColor = ChatColor.RED;
                        } else if (team.equalsIgnoreCase("blue")) {
                            teamColor = ChatColor.BLUE;
                        }
                    }
                    String prefix = rank.getFormattedTag() + " " + teamColor;
                    applyNametagToViewer(other, player, prefix, teamColor);
                }
            }
        }, 10L);
    }

    /**
     * Called when a player quits - cleans up tracking.
     */
    public void onPlayerQuit(Player player) {
        lastKnownRanks.remove(player.getUniqueId());
    }

    /**
     * Forces an immediate update for a player (e.g., after promotion).
     */
    public void forceUpdate(Player player) {
        lastKnownRanks.remove(player.getUniqueId());
        updatePlayerNametag(player);
    }

    /**
     * Forces update for all players (e.g., after config reload).
     */
    public void forceUpdateAll() {
        lastKnownRanks.clear();
        updateAllNametags();
    }
}

