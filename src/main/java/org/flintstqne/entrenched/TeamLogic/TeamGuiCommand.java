package org.flintstqne.entrenched.TeamLogic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

public class TeamGuiCommand implements CommandExecutor {

    private final TeamSelectionView view;

    public TeamGuiCommand(TeamService teamService, JavaPlugin plugin, ScoreboardUtil scoreboardUtil) {
        this.view = new TeamSelectionView(teamService, plugin, scoreboardUtil);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        view.createGui().show(player);
        return true;
    }
}
