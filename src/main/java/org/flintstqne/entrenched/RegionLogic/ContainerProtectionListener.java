package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveCategory;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveService;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType;
import org.flintstqne.entrenched.ObjectiveLogic.RegionObjective;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents enemies from opening or breaking team containers (chests, barrels, etc.)
 * unless they have a valid "Destroy Supply Cache" objective targeting that container.
 */
public class ContainerProtectionListener implements Listener {

    private final RegionService regionService;
    private final TeamService teamService;
    private final ObjectiveService objectiveService;
    private final ConfigManager configManager;

    // Track which team placed which container: "x,y,z" -> team
    private final Map<String, String> containerOwnership = new ConcurrentHashMap<>();

    // Debounce for protection messages to prevent spam
    private final Map<UUID, Long> lastProtectionMessage = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 3000; // 3 seconds

    public ContainerProtectionListener(RegionService regionService, TeamService teamService,
                                        ObjectiveService objectiveService, ConfigManager configManager) {
        this.regionService = regionService;
        this.teamService = teamService;
        this.objectiveService = objectiveService;
        this.configManager = configManager;
    }

    /**
     * Tracks a container placement for ownership.
     */
    public void trackContainerPlacement(int x, int y, int z, String team) {
        String key = x + "," + y + "," + z;
        containerOwnership.put(key, team);
    }

    /**
     * Removes container tracking when broken.
     */
    public void removeContainerTracking(int x, int y, int z) {
        String key = x + "," + y + "," + z;
        containerOwnership.remove(key);
    }

    /**
     * Gets the team that owns a container at a location.
     */
    public Optional<String> getContainerOwner(int x, int y, int z) {
        String key = x + "," + y + "," + z;
        return Optional.ofNullable(containerOwnership.get(key));
    }

    /**
     * Clears all container tracking (called on round reset).
     */
    public void clearTracking() {
        containerOwnership.clear();
        lastProtectionMessage.clear();
    }

    // ==================== EVENT HANDLERS ====================

    /**
     * Tracks container placements to associate them with the player's team.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!isContainer(block.getType())) return;

        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        // Track this container as owned by the player's team
        trackContainerPlacement(block.getX(), block.getY(), block.getZ(), teamOpt.get());
    }

    /**
     * Prevents enemies from opening protected containers.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || !isContainer(block.getType())) return;

        Player player = event.getPlayer();

        // Check if player is on a team
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) return; // No team = no protection applies

        String playerTeam = playerTeamOpt.get();

        // Check who owns this container
        Optional<String> containerOwnerOpt = getContainerOwner(
                block.getX(), block.getY(), block.getZ());

        if (containerOwnerOpt.isEmpty()) {
            // Untracked container (natural chest, etc.) - no protection
            return;
        }

        String containerOwner = containerOwnerOpt.get();

        // If same team, allow access
        if (containerOwner.equalsIgnoreCase(playerTeam)) {
            return;
        }

        // Enemy trying to access - check region ownership
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());
        if (regionId == null) return;

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // If the region is owned by the container owner's team, container is protected
        if (status.isOwnedBy(containerOwner)) {
            // Container is protected - block access
            event.setCancelled(true);
            sendProtectionMessage(player, "You cannot access enemy containers in their territory!");
            return;
        }

        // Region is neutral, contested, or owned by player's team - allow access
        // (If player's team captured the region, they can access enemy containers)
    }

    /**
     * Prevents enemies from breaking protected containers unless they have a valid objective.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isContainer(block.getType())) return;

        Player player = event.getPlayer();

        // Check if player is on a team
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) return;

        String playerTeam = playerTeamOpt.get();

        // Check who owns this container
        Optional<String> containerOwnerOpt = getContainerOwner(
                block.getX(), block.getY(), block.getZ());

        if (containerOwnerOpt.isEmpty()) {
            // Untracked container - no protection
            return;
        }

        String containerOwner = containerOwnerOpt.get();

        // If same team, allow breaking
        if (containerOwner.equalsIgnoreCase(playerTeam)) {
            // Remove tracking since it's being broken
            removeContainerTracking(block.getX(), block.getY(), block.getZ());
            return;
        }

        // Enemy trying to break - check region ownership
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());
        if (regionId == null) return;

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // If the region is owned by the container owner's team, check for objective bypass
        if (status.isOwnedBy(containerOwner)) {
            // Check if there's a valid "Destroy Supply Cache" objective
            if (hasDestroyCacheObjective(regionId, playerTeam, block.getX(), block.getY(), block.getZ())) {
                // Allow breaking - objective bypass
                removeContainerTracking(block.getX(), block.getY(), block.getZ());
                return;
            }

            // Container is protected - block breaking
            event.setCancelled(true);
            sendProtectionMessage(player, "You cannot destroy enemy containers in their territory!");
            return;
        }

        // Region is neutral, contested, or owned by player's team - allow breaking
        removeContainerTracking(block.getX(), block.getY(), block.getZ());
    }

    // ==================== HELPER METHODS ====================

    /**
     * Checks if a block type is a container that should be protected.
     */
    private boolean isContainer(Material material) {
        return material == Material.CHEST ||
               material == Material.TRAPPED_CHEST ||
               material == Material.BARREL ||
               material == Material.SHULKER_BOX ||
               material.name().contains("SHULKER_BOX") ||
               material == Material.HOPPER ||
               material == Material.DROPPER ||
               material == Material.DISPENSER;
    }

    /**
     * Checks if there's a valid "Destroy Supply Cache" objective for this container.
     */
    private boolean hasDestroyCacheObjective(String regionId, String attackerTeam, int x, int y, int z) {
        // Get active raid objectives in this region
        for (RegionObjective obj : objectiveService.getActiveObjectives(regionId, ObjectiveCategory.RAID)) {
            if (obj.type() == ObjectiveType.RAID_DESTROY_CACHE) {
                // There's an active destroy cache objective - allow breaking any enemy chest
                // The objective system will handle completion detection
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a protection message to a player with cooldown to prevent spam.
     */
    private void sendProtectionMessage(Player player, String message) {
        long now = System.currentTimeMillis();
        Long lastMessage = lastProtectionMessage.get(player.getUniqueId());

        if (lastMessage == null || (now - lastMessage) > MESSAGE_COOLDOWN_MS) {
            lastProtectionMessage.put(player.getUniqueId(), now);
            player.sendMessage(configManager.getPrefix() + ChatColor.RED + message);
        }
    }
}

