package org.flintstqne.entrenched.Utils;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.DivisionCommand;

/**
 * Handles the /confirm command for confirming pending actions.
 * Currently supports:
 * - Division creation (resource cost confirmation)
 */
public final class ConfirmCommand implements CommandExecutor {

    private final DivisionCommand divisionCommand;
    private final ConfigManager configManager;

    public ConfirmCommand(DivisionCommand divisionCommand, ConfigManager configManager) {
        this.divisionCommand = divisionCommand;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        // Check for pending division creation
        if (divisionCommand.hasPendingCreation(player.getUniqueId())) {
            divisionCommand.confirmCreation(player);
            return true;
        }

        // No pending actions
        player.sendMessage(configManager.getPrefix() + ChatColor.RED + "You have no pending actions to confirm.");
        return true;
    }
}

