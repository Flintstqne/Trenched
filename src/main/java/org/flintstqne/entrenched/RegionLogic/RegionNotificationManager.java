package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages notifications and UI for the region capture system.
 */
public final class RegionNotificationManager {

    private final JavaPlugin plugin;
    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer; // May be null if BlueMap not available

    // Boss bars for contested regions
    private final Map<String, BossBar> regionBossBars = new ConcurrentHashMap<>();

    // Track which regions players are in
    private final Map<UUID, String> playerRegions = new ConcurrentHashMap<>();

    // Update task
    private BukkitTask updateTask;

    public RegionNotificationManager(JavaPlugin plugin, RegionService regionService,
                                     TeamService teamService, ConfigManager configManager,
                                     RegionRenderer regionRenderer) {
        this.plugin = plugin;
        this.regionService = regionService;
        this.teamService = teamService;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
    }

    /**
     * Gets the display name for a region (e.g., "Shadowfen Valley" instead of "A1").
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId;
    }

    /**
     * Starts the notification update task.
     */
    public void start() {
        if (updateTask != null) {
            updateTask.cancel();
        }

        // Update every second
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 20L);
    }

    /**
     * Stops the notification manager.
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }

        // Remove all boss bars
        for (BossBar bar : regionBossBars.values()) {
            bar.removeAll();
        }
        regionBossBars.clear();
    }

    /**
     * Updates all notifications.
     */
    private void update() {
        updatePlayerRegions();
        updateBossBars();

        // Check and update fortification status (every update cycle)
        regionService.updateFortificationStatus();
    }

    /**
     * Tracks which region each player is in.
     */
    private void updatePlayerRegions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String regionId = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );

            String previousRegion = playerRegions.put(player.getUniqueId(), regionId);

            // Check if player entered a new region
            if (regionId != null && !regionId.equals(previousRegion)) {
                onPlayerEnterRegion(player, regionId);
            }
        }
    }

    /**
     * Called when a player enters a region.
     * Shows region info via action bar for 5 seconds.
     */
    private void onPlayerEnterRegion(Player player, String regionId) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());

        // Build action bar message
        String actionBarText = buildRegionActionBar(regionId, status, playerTeamOpt.orElse(null));

        // Show action bar message for 5 seconds (refresh every second)
        showActionBarForDuration(player, actionBarText, 5);
    }

    /**
     * Builds the action bar text for a region.
     */
    private String buildRegionActionBar(String regionId, RegionStatus status, String playerTeam) {
        StringBuilder message = new StringBuilder();

        // Get the display name for the region
        String regionName = getRegionDisplayName(regionId);

        // Region name with color based on owner
        ChatColor regionColor = ChatColor.GOLD;
        if (status.ownerTeam() != null) {
            regionColor = status.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        }

        message.append(ChatColor.GRAY).append("Entering ");
        message.append(regionColor).append(regionName);

        // Add owner info
        if (status.ownerTeam() != null) {
            String teamName = status.ownerTeam().equalsIgnoreCase("red") ? "Red" : "Blue";
            message.append(ChatColor.DARK_GRAY).append(" (").append(regionColor).append(teamName).append(ChatColor.DARK_GRAY).append(")");
        } else {
            message.append(ChatColor.DARK_GRAY).append(" (").append(ChatColor.WHITE).append("Neutral").append(ChatColor.DARK_GRAY).append(")");
        }

        // Add state indicator if not normal
        if (status.state() == RegionState.CONTESTED) {
            message.append(ChatColor.YELLOW).append(" ⚔ CONTESTED");
        } else if (status.state() == RegionState.FORTIFIED) {
            message.append(ChatColor.AQUA).append(" 🛡 FORTIFIED");
        } else if (status.state() == RegionState.PROTECTED) {
            message.append(ChatColor.GOLD).append(" ★ HOME");
        }

        // Add supply info if owned by player's team
        if (playerTeam != null && status.isOwnedBy(playerTeam)) {
            double supply = regionService.getSupplyEfficiency(status.regionId(), playerTeam);
            ChatColor supplyColor = supply >= 0.8 ? ChatColor.GREEN :
                    supply >= 0.5 ? ChatColor.YELLOW : ChatColor.RED;
            message.append(ChatColor.DARK_GRAY).append(" | ").append(ChatColor.GRAY).append("Supply: ")
                    .append(supplyColor).append((int)(supply * 100)).append("%");
        }

        return message.toString();
    }

    /**
     * Shows an action bar message for a specified duration.
     */
    private void showActionBarForDuration(Player player, String message, int seconds) {
        for (int i = 0; i < seconds; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.spigot().sendMessage(
                            net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message)
                    );
                }
            }, i * 20L);
        }
    }

    /**
     * Updates boss bars for contested regions.
     */
    private void updateBossBars() {
        Set<String> activeContestedRegions = new HashSet<>();

        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            if (status.state() == RegionState.CONTESTED) {
                activeContestedRegions.add(status.regionId());
                updateContestedBossBar(status);
            }
        }

        // Remove boss bars for regions no longer contested
        Iterator<Map.Entry<String, BossBar>> iterator = regionBossBars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BossBar> entry = iterator.next();
            if (!activeContestedRegions.contains(entry.getKey())) {
                entry.getValue().removeAll();
                iterator.remove();
            }
        }
    }

    /**
     * Updates or creates a boss bar for a contested region.
     */
    private void updateContestedBossBar(RegionStatus status) {
        BossBar bar = regionBossBars.computeIfAbsent(status.regionId(), id -> {
            BossBar newBar = Bukkit.createBossBar(
                    "",
                    BarColor.PURPLE,
                    BarStyle.SEGMENTED_10
            );
            return newBar;
        });

        // Get the display name for the region
        String regionName = getRegionDisplayName(status.regionId());

        // Update title
        double required = regionService.getInfluenceRequired(status.regionId(),
                status.ownerTeam() != null ?
                        (status.ownerTeam().equals("red") ? "blue" : "red") : "red");

        String title = ChatColor.RED + "Red: " + (int) status.redInfluence() +
                ChatColor.GRAY + " ⚔ " + regionName + " ⚔ " +
                ChatColor.BLUE + "Blue: " + (int) status.blueInfluence() +
                ChatColor.GRAY + " / " + (int) required;
        bar.setTitle(title);

        // Update progress (higher team's progress)
        double maxInfluence = Math.max(status.redInfluence(), status.blueInfluence());
        double progress = Math.min(maxInfluence / required, 1.0);
        bar.setProgress(progress);

        // Update color based on leading team
        if (status.redInfluence() > status.blueInfluence()) {
            bar.setColor(BarColor.RED);
        } else if (status.blueInfluence() > status.redInfluence()) {
            bar.setColor(BarColor.BLUE);
        } else {
            bar.setColor(BarColor.PURPLE);
        }

        // Add players in the region to the boss bar
        bar.removeAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerRegion = playerRegions.get(player.getUniqueId());
            if (status.regionId().equals(playerRegion)) {
                bar.addPlayer(player);
            }
        }
    }

    /**
     * Broadcasts a region capture notification.
     */
    public void broadcastCapture(String regionId, String team, String previousOwner) {
        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
        String teamName = team.equalsIgnoreCase("red") ? "Red Team" : "Blue Team";
        String regionName = getRegionDisplayName(regionId);

        String message;
        if (previousOwner == null) {
            message = configManager.getPrefix() + teamColor + teamName + ChatColor.YELLOW +
                    " has claimed " + ChatColor.WHITE + regionName + ChatColor.YELLOW + "!";
        } else {
            message = configManager.getPrefix() + teamColor + teamName + ChatColor.YELLOW +
                    " has captured " + ChatColor.WHITE + regionName + ChatColor.YELLOW + "!";
        }

        Bukkit.broadcastMessage(message);

        // Notify specific teams
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeamOpt.isEmpty()) continue;

            if (playerTeamOpt.get().equalsIgnoreCase(team)) {
                player.sendMessage(ChatColor.GREEN + "🎉 " + regionName + " is now under your team's control!");
            } else if (previousOwner != null && playerTeamOpt.get().equalsIgnoreCase(previousOwner)) {
                player.sendMessage(ChatColor.RED + "❌ " + regionName + " has fallen to the enemy!");
            }
        }
    }

    /**
     * Sends a warning to defenders when their region is under attack.
     */
    public void sendAttackWarning(String regionId, String attackingTeam) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();
        if (status.ownerTeam() == null) return;

        String defendingTeam = status.ownerTeam();
        String regionName = getRegionDisplayName(regionId);
        ChatColor attackerColor = attackingTeam.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;

        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeamOpt.isPresent() && playerTeamOpt.get().equalsIgnoreCase(defendingTeam)) {
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "⚠ " +
                        ChatColor.WHITE + regionName + ChatColor.RED + " is under attack by " +
                        attackerColor + attackingTeam.toUpperCase() + ChatColor.RED + "!");
            }
        }
    }

    /**
     * Gets the color for a region state.
     */
    private ChatColor getStateColor(RegionState state) {
        return switch (state) {
            case NEUTRAL -> ChatColor.GRAY;
            case OWNED -> ChatColor.GREEN;
            case CONTESTED -> ChatColor.YELLOW;
            case FORTIFIED -> ChatColor.AQUA;
            case PROTECTED -> ChatColor.GOLD;
        };
    }

    // ==================== ROAD/SUPPLY NOTIFICATIONS ====================

    /**
     * Broadcasts a supply line disruption notification to the affected team.
     *
     * @param team The team whose supply was disrupted
     * @param affectedRegions List of region IDs that lost supply
     * @param sourceRegionId The region where the disruption occurred (optional)
     */
    public void broadcastSupplyDisrupted(String team, List<String> affectedRegions, String sourceRegionId) {
        if (affectedRegions.isEmpty()) return;

        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;

        // Build the affected regions string with display names
        List<String> regionNames = affectedRegions.stream()
                .map(this::getRegionDisplayName)
                .toList();
        String regionsStr = String.join(", ", regionNames);

        // Source region message
        String sourceMsg = "";
        if (sourceRegionId != null) {
            sourceMsg = " in " + ChatColor.WHITE + getRegionDisplayName(sourceRegionId);
        }

        // Notify affected team
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeamOpt.isPresent() && playerTeamOpt.get().equalsIgnoreCase(team)) {
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "⚠ Supply line disrupted" +
                        sourceMsg + ChatColor.RED + "!");
                player.sendMessage(ChatColor.GRAY + "  Affected regions: " + ChatColor.YELLOW + regionsStr);
                player.sendMessage(ChatColor.GRAY + "  Respawn delays and health regen penalties now apply.");

                // Play warning sound
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            }
        }

        // Log
        plugin.getLogger().info("[Supply] " + team.toUpperCase() + " team supply disrupted. Affected: " + regionsStr);
    }

    /**
     * Broadcasts a supply line restoration notification to the affected team.
     *
     * @param team The team whose supply was restored
     * @param restoredRegions List of region IDs that regained supply
     */
    public void broadcastSupplyRestored(String team, List<String> restoredRegions) {
        if (restoredRegions.isEmpty()) return;

        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;

        // Build the restored regions string with display names
        List<String> regionNames = restoredRegions.stream()
                .map(this::getRegionDisplayName)
                .toList();
        String regionsStr = String.join(", ", regionNames);

        // Notify affected team
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeamOpt.isPresent() && playerTeamOpt.get().equalsIgnoreCase(team)) {
                player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "✓ Supply line restored!");
                player.sendMessage(ChatColor.GRAY + "  Reconnected regions: " + ChatColor.GREEN + regionsStr);

                // Play success sound
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f);
            }
        }

        // Log
        plugin.getLogger().info("[Supply] " + team.toUpperCase() + " team supply restored. Reconnected: " + regionsStr);
    }

    /**
     * Sends a warning to a player about their current supply status.
     */
    public void sendSupplyWarning(Player player, String regionId, String supplyStatus) {
        String regionName = getRegionDisplayName(regionId);

        String message = switch (supplyStatus.toUpperCase()) {
            case "PARTIAL" -> ChatColor.YELLOW + "⚠ " + regionName + " has damaged supply lines. " +
                    ChatColor.GRAY + "(+5s respawn delay)";
            case "UNSUPPLIED" -> ChatColor.RED + "⚠ " + regionName + " is UNSUPPLIED! " +
                    ChatColor.GRAY + "(+15s respawn, slower health regen)";
            case "ISOLATED" -> ChatColor.DARK_RED + "⚠ " + regionName + " is ISOLATED! " +
                    ChatColor.GRAY + "(+30s respawn, very slow health regen)";
            default -> null;
        };

        if (message != null) {
            player.sendMessage(configManager.getPrefix() + message);
        }
    }

    /**
     * Cleans up when a player quits.
     */
    public void onPlayerQuit(Player player) {
        playerRegions.remove(player.getUniqueId());
    }
}
