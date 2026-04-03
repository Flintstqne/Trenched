package org.flintstqne.entrenched.Utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /settings command for per-player toggles.
 *
 * Currently supports:
 *   /settings particles   — toggle building particle effects on/off
 *   /settings              — show current settings
 */
public final class SettingsCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    /** Players who have disabled particles (default: enabled). */
    private final Set<UUID> particlesDisabled = ConcurrentHashMap.newKeySet();

    private final NamespacedKey PARTICLES_KEY;

    public SettingsCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        this.PARTICLES_KEY = new NamespacedKey(plugin, "particles_disabled");
    }

    /**
     * Checks whether a player has particles enabled.
     * Used by BuildingBenefitManager and ObjectiveUIManager to skip particle rendering.
     */
    public boolean areParticlesEnabled(UUID playerId) {
        return !particlesDisabled.contains(playerId);
    }

    /**
     * Loads a player's particle preference from their PDC on join.
     */
    public void loadPlayerPreference(Player player) {
        if (player.getPersistentDataContainer().has(PARTICLES_KEY, PersistentDataType.BYTE)) {
            Byte val = player.getPersistentDataContainer().get(PARTICLES_KEY, PersistentDataType.BYTE);
            if (val != null && val == 1) {
                particlesDisabled.add(player.getUniqueId());
            }
        }
    }

    /**
     * Removes a player from the in-memory set on quit (PDC persists automatically).
     */
    public void unloadPlayer(UUID playerId) {
        particlesDisabled.remove(playerId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            // Show current settings
            showSettings(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "particles" -> toggleParticles(player);
            default -> {
                player.sendMessage(Component.text("Unknown setting: ", NamedTextColor.RED)
                        .append(Component.text(args[0], NamedTextColor.WHITE)));
                player.sendMessage(Component.text("Usage: /settings [particles]", NamedTextColor.GRAY));
            }
        }

        return true;
    }

    private void showSettings(Player player) {
        boolean particles = areParticlesEnabled(player.getUniqueId());

        player.sendMessage(Component.text("━━━ Player Settings ━━━", NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD));
        player.sendMessage(Component.text("  Particles: ", NamedTextColor.GRAY)
                .append(Component.text(particles ? "ON ✓" : "OFF ✗",
                        particles ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(Component.text("  Use /settings <option> to toggle", NamedTextColor.DARK_GRAY));
    }

    private void toggleParticles(Player player) {
        UUID id = player.getUniqueId();
        if (particlesDisabled.contains(id)) {
            particlesDisabled.remove(id);
            player.getPersistentDataContainer().set(PARTICLES_KEY, PersistentDataType.BYTE, (byte) 0);
            player.sendMessage(Component.text("[Settings] ", NamedTextColor.GREEN)
                    .append(Component.text("Building particles ", NamedTextColor.WHITE))
                    .append(Component.text("enabled", NamedTextColor.GREEN)));
        } else {
            particlesDisabled.add(id);
            player.getPersistentDataContainer().set(PARTICLES_KEY, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(Component.text("[Settings] ", NamedTextColor.GREEN)
                    .append(Component.text("Building particles ", NamedTextColor.WHITE))
                    .append(Component.text("disabled", NamedTextColor.RED)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("particles").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}


