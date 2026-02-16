package org.flintstqne.entrenched.TeamLogic;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.util.Arrays;
import java.util.Optional;

public class TeamSelectionView {

    private final TeamService teamService;
    private final JavaPlugin plugin;
    private final ScoreboardUtil scoreboardUtil;
    private static final int MAX_TEAM_IMBALANCE = 2;

    //The balance check uses MAX_TEAM_IMBALANCE = 2,
    // meaning teams can differ by at most 1 player
    // (e.g., 5 vs 4 is allowed, 6 vs 4 is not)

    public TeamSelectionView(TeamService teamService, JavaPlugin plugin) {
        this(teamService, plugin, null);
    }

    public TeamSelectionView(TeamService teamService, JavaPlugin plugin, ScoreboardUtil scoreboardUtil) {
        this.teamService = teamService;
        this.plugin = plugin;
        this.scoreboardUtil = scoreboardUtil;
    }

    public ChestGui createGui() {
        ChestGui gui = new ChestGui(3, "Select Your Team");

        // Prevent item movement
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        // Prevent closing without team selection
        gui.setOnClose(event -> {
            Player player = (Player) event.getPlayer();
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());

            if (teamOpt.isEmpty()) {
                // Player hasn't selected a team - reopen after 1 tick
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    createGui().show(player);
                    player.sendMessage(ChatColor.RED + "You must select a team before continuing!");
                }, 1L);
            }
        });

        StaticPane pane = new StaticPane(0, 0, 9, 3);

        // Red team button
        ItemStack redWool = new ItemStack(Material.RED_WOOL);
        ItemMeta redMeta = redWool.getItemMeta();
        redMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Join RED Team");
        redMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Click to join the red team",
                ChatColor.GRAY + "Current members: " + teamService.countTeamMembers("red")
        ));
        redWool.setItemMeta(redMeta);

        GuiItem redItem = new GuiItem(redWool, event -> {
            Player player = (Player) event.getWhoClicked();
            JoinResult result = teamService.joinTeam(player.getUniqueId(), "red", JoinReason.PLAYER_CHOICE);

            switch (result) {
                case OK -> {
                    player.sendMessage(ChatColor.GREEN + "You joined the " + ChatColor.RED + "RED" + ChatColor.GREEN + " team!");
                    gui.setOnClose(null); // Allow closing now
                    player.closeInventory();
                    if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                    // Teleport player to team spawn if configured
                    teamService.getTeamSpawn("red").ifPresent(spawn -> player.teleport(spawn));
                }
                case TEAM_FULL -> player.sendMessage(ChatColor.RED + "Red team is full!");
                case ALREADY_IN_TEAM -> {
                    player.sendMessage(ChatColor.YELLOW + "You are already on a team!");
                    gui.setOnClose(null);
                    player.closeInventory();
                    if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                    // If player is already in a team, teleport them to their current team spawn
                    teamService.getPlayerTeam(player.getUniqueId()).ifPresent(tid ->
                            teamService.getTeamSpawn(tid).ifPresent(spawn -> player.teleport(spawn))
                    );
                }
                default -> player.sendMessage(ChatColor.RED + "Failed to join red team.");
            }
        });

        // Blue team button
        ItemStack blueWool = new ItemStack(Material.BLUE_WOOL);
        ItemMeta blueMeta = blueWool.getItemMeta();
        blueMeta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Join BLUE Team");
        blueMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Click to join the blue team",
                ChatColor.GRAY + "Current members: " + teamService.countTeamMembers("blue")
        ));
        blueWool.setItemMeta(blueMeta);

        GuiItem blueItem = new GuiItem(blueWool, event -> {
            Player player = (Player) event.getWhoClicked();
            JoinResult result = teamService.joinTeam(player.getUniqueId(), "blue", JoinReason.PLAYER_CHOICE);

            switch (result) {
                case OK -> {
                    player.sendMessage(ChatColor.GREEN + "You joined the " + ChatColor.BLUE + "BLUE" + ChatColor.GREEN + " team!");
                    gui.setOnClose(null);
                    player.closeInventory();
                    if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                    // Teleport player to team spawn if configured
                    teamService.getTeamSpawn("blue").ifPresent(spawn -> player.teleport(spawn));
                }
                case TEAM_FULL -> player.sendMessage(ChatColor.RED + "Blue team is full!");
                case ALREADY_IN_TEAM -> {
                    player.sendMessage(ChatColor.YELLOW + "You are already on a team!");
                    gui.setOnClose(null);
                    player.closeInventory();
                    if (scoreboardUtil != null) scoreboardUtil.updatePlayerScoreboard(player);
                    // If player is already in a team, teleport them to their current team spawn
                    teamService.getPlayerTeam(player.getUniqueId()).ifPresent(tid ->
                            teamService.getTeamSpawn(tid).ifPresent(spawn -> player.teleport(spawn))
                    );
                }
                default -> player.sendMessage(ChatColor.RED + "Failed to join blue team.");
            }
        });

        pane.addItem(redItem, 2, 1);
        pane.addItem(blueItem, 6, 1);

        gui.addPane(pane);

        return gui;
    }
}
