package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for road block placement and destruction events.
 */
public final class RoadListener implements Listener {

    private final JavaPlugin plugin;
    private final RoadService roadService;
    private final TeamService teamService;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer;
    private final Set<Material> pathBlockTypes;

    // Callback for notifying disruptions
    private RoadDisruptionCallback disruptionCallback;

    // Debouncing for road damage notifications - batches multiple blocks broken together
    private final Map<String, PendingDamageNotification> pendingNotifications = new ConcurrentHashMap<>();
    private static final long NOTIFICATION_DEBOUNCE_MS = 5000; // 5 seconds to batch notifications
    private static final int MIN_BLOCKS_FOR_NOTIFICATION = 5; // Minimum blocks to trigger player notification
    private static final int MIN_BLOCKS_FOR_LOG = 50; // Minimum blocks to log (reduce spam)

    /**
     * Tracks pending damage notifications to batch them together.
     */
    private static class PendingDamageNotification {
        final String team;
        final String sourceRegion;
        final Player causer;
        final Set<String> previouslyConnected;
        final List<RoadBlock> destroyedBlocks = new ArrayList<>();
        final long firstDamageTime;
        boolean notificationScheduled = false;

        PendingDamageNotification(String team, String sourceRegion, Player causer, Set<String> previouslyConnected) {
            this.team = team;
            this.sourceRegion = sourceRegion;
            this.causer = causer;
            this.previouslyConnected = previouslyConnected;
            this.firstDamageTime = System.currentTimeMillis();
        }
    }

    public RoadListener(JavaPlugin plugin, RoadService roadService, TeamService teamService,
                        ConfigManager configManager, RegionRenderer regionRenderer) {
        this.plugin = plugin;
        this.roadService = roadService;
        this.teamService = teamService;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
        this.pathBlockTypes = loadPathBlockTypes();
    }

    /**
     * Sets the callback for road disruption notifications.
     */
    public void setDisruptionCallback(RoadDisruptionCallback callback) {
        this.disruptionCallback = callback;
    }

    /**
     * Loads valid path block types from config.
     */
    private Set<Material> loadPathBlockTypes() {
        Set<Material> types = new HashSet<>();
        List<String> configBlocks = configManager.getSupplyPathBlocks();

        if (configBlocks == null || configBlocks.isEmpty()) {
            // Defaults
            types.add(Material.DIRT_PATH);
            types.add(Material.GRAVEL);
            types.add(Material.COBBLESTONE);
            types.add(Material.STONE_BRICKS);
            types.add(Material.POLISHED_ANDESITE);
        } else {
            for (String blockName : configBlocks) {
                try {
                    Material mat = Material.valueOf(blockName.toUpperCase());
                    types.add(mat);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("[RoadListener] Invalid path block type: " + blockName);
                }
            }
        }

        return types;
    }

    /**
     * Checks if a material is a valid path block.
     */
    public boolean isPathBlock(Material material) {
        return pathBlockTypes.contains(material);
    }

    // ==================== BLOCK PLACE ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        // Only track path blocks
        if (!isPathBlock(block.getType())) return;

        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());

        // Only track if player is on a team
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // Record the road block
        roadService.onPathBlockPlaced(x, y, z, player.getUniqueId(), team);

        // Optional: Show feedback to player
        if (configManager.isVerbose()) {
            player.sendMessage(ChatColor.GRAY + "[Road] Path block placed and tracked.");
        }
    }

    // ==================== SHOVEL PATH CREATION ====================

    /**
     * Handles when a player uses a shovel on dirt/grass to create DIRT_PATH.
     * This is a block CHANGE, not a block PLACE, so we need a separate handler.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShovelUse(PlayerInteractEvent event) {
        // Only process right-click on block
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        Material blockType = block.getType();

        // Check if this is a block that can become DIRT_PATH
        if (!canBecomePathBlock(blockType)) return;

        // Check if player is holding a shovel
        ItemStack item = event.getItem();
        if (item == null || !isShovel(item.getType())) return;

        // Check if DIRT_PATH is in our tracked path blocks
        if (!isPathBlock(Material.DIRT_PATH)) return;

        Player player = event.getPlayer();
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();

        // Schedule a delayed check to see if the block actually changed
        // (The block change happens AFTER this event)
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Block checkBlock = block.getWorld().getBlockAt(x, y, z);
            if (checkBlock.getType() == Material.DIRT_PATH) {
                // Block successfully changed to DIRT_PATH - track it
                roadService.onPathBlockPlaced(x, y, z, player.getUniqueId(), team);

                if (configManager.isVerbose()) {
                    player.sendMessage(ChatColor.GRAY + "[Road] Path created with shovel and tracked.");
                }
            }
        }, 1L); // 1 tick delay
    }

    /**
     * Checks if a block type can be converted to DIRT_PATH with a shovel.
     */
    private boolean canBecomePathBlock(Material material) {
        return material == Material.DIRT ||
               material == Material.GRASS_BLOCK ||
               material == Material.COARSE_DIRT ||
               material == Material.MYCELIUM ||
               material == Material.PODZOL ||
               material == Material.ROOTED_DIRT;
    }

    /**
     * Checks if an item is a shovel.
     */
    private boolean isShovel(Material material) {
        return material == Material.WOODEN_SHOVEL ||
               material == Material.STONE_SHOVEL ||
               material == Material.IRON_SHOVEL ||
               material == Material.GOLDEN_SHOVEL ||
               material == Material.DIAMOND_SHOVEL ||
               material == Material.NETHERITE_SHOVEL;
    }

    // ==================== BLOCK BREAK ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Check if this was a tracked road block
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        if (!roadService.isRoadBlock(x, y, z)) return;

        // Get road block info before removal
        Optional<RoadBlock> roadBlock = roadService.getRoadBlock(x, y, z);
        String affectedTeam = roadBlock.map(RoadBlock::team).orElse(null);

        if (affectedTeam == null) return;

        String sourceRegion = roadBlock.map(RoadBlock::regionId).orElse("unknown");
        Player breaker = event.getPlayer();

        // Remove the road block (schedules recalculation)
        Optional<String> removedTeam = roadService.onPathBlockRemoved(x, y, z);
        if (removedTeam.isEmpty()) return;

        // Add to batched notification
        addToPendingNotification(affectedTeam, sourceRegion, breaker, roadBlock.orElse(null));
    }

    /**
     * Adds a destroyed road block to the pending notification batch.
     * Schedules a delayed notification if one isn't already pending.
     */
    private void addToPendingNotification(String team, String region, Player causer, RoadBlock block) {
        String notificationKey = team + ":" + region;

        PendingDamageNotification pending = pendingNotifications.computeIfAbsent(notificationKey, k -> {
            // Capture connected regions BEFORE destruction
            Set<String> previouslyConnected = new HashSet<>(roadService.getConnectedRegions(team));
            return new PendingDamageNotification(team, region, causer, previouslyConnected);
        });

        if (block != null) {
            synchronized (pending.destroyedBlocks) {
                pending.destroyedBlocks.add(block);
            }
        }

        // Schedule notification if not already scheduled
        if (!pending.notificationScheduled) {
            pending.notificationScheduled = true;

            // Wait for debounce period + recalculation time, then send batched notification
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                // Remove from pending
                pendingNotifications.remove(notificationKey);

                // Get current connection state after recalculation
                Set<String> nowConnected = roadService.getConnectedRegions(team);

                // Find regions that lost supply
                List<String> disconnectedRegions = new ArrayList<>();
                for (String r : pending.previouslyConnected) {
                    if (!nowConnected.contains(r)) {
                        disconnectedRegions.add(r);
                    }
                }

                int totalBlocksDestroyed;
                RoadBlock sampleBlock;
                synchronized (pending.destroyedBlocks) {
                    totalBlocksDestroyed = pending.destroyedBlocks.size();
                    sampleBlock = pending.destroyedBlocks.isEmpty() ? null : pending.destroyedBlocks.get(0);
                }

                // Only notify if there were actual disconnections OR significant damage
                if (!disconnectedRegions.isEmpty() || totalBlocksDestroyed >= MIN_BLOCKS_FOR_NOTIFICATION) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        notifyRoadDamage(pending.causer, team, region, totalBlocksDestroyed, disconnectedRegions, sampleBlock);
                    });
                } else if (totalBlocksDestroyed > 0 && configManager.isVerbose()) {
                    // Only log minor damage if verbose mode is on
                    plugin.getLogger().info("[RoadListener] Minor road damage: " + totalBlocksDestroyed +
                            " blocks in " + region + " for " + team + " (below notification threshold)");
                }
            }, 200L); // 10 seconds (longer debounce + recalculation time)
        }
    }

    // ==================== EXPLOSIONS ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList(), null);
    }

    /**
     * Handles explosion damage to roads.
     * Uses the same batching mechanism as regular block breaks.
     */
    private void handleExplosion(List<Block> blocks, Player causer) {
        // Group affected blocks by team and region
        Map<String, Map<String, List<RoadBlock>>> affectedByTeamAndRegion = new HashMap<>();

        for (Block block : blocks) {
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();

            if (!roadService.isRoadBlock(x, y, z)) continue;

            Optional<RoadBlock> roadBlock = roadService.getRoadBlock(x, y, z);
            if (roadBlock.isEmpty()) continue;

            String team = roadBlock.get().team();
            String region = roadBlock.get().regionId();

            affectedByTeamAndRegion
                    .computeIfAbsent(team, k -> new HashMap<>())
                    .computeIfAbsent(region, k -> new ArrayList<>())
                    .add(roadBlock.get());
        }

        if (affectedByTeamAndRegion.isEmpty()) return;

        // Process each team's affected roads using the batching system
        for (Map.Entry<String, Map<String, List<RoadBlock>>> teamEntry : affectedByTeamAndRegion.entrySet()) {
            String team = teamEntry.getKey();

            for (Map.Entry<String, List<RoadBlock>> regionEntry : teamEntry.getValue().entrySet()) {
                String region = regionEntry.getKey();
                List<RoadBlock> teamBlocks = regionEntry.getValue();

                // Add all explosion-destroyed blocks to the pending notification
                for (RoadBlock rb : teamBlocks) {
                    // Remove the road block (schedules recalculation)
                    roadService.onPathBlockRemoved(rb.x(), rb.y(), rb.z());

                    // Add to batched notification
                    addToPendingNotification(team, region, causer, rb);
                }
            }
        }
    }

    /**
     * Notifies players about road damage/disruption.
     * Always called when road blocks are destroyed, even if no regions fully disconnect.
     */
    private void notifyRoadDamage(Player causer, String affectedTeam, String sourceRegion,
                                  int blocksDestroyed, List<String> disconnectedRegions, RoadBlock sampleBlock) {
        // Trigger callback for full disconnections
        if (disruptionCallback != null && !disconnectedRegions.isEmpty()) {
            disruptionCallback.onRoadDisrupted(affectedTeam, disconnectedRegions, sampleBlock);
        }

        // Get coordinates string
        String coordsStr = sampleBlock != null
                ? "(" + sampleBlock.x() + ", " + sampleBlock.y() + ", " + sampleBlock.z() + ")"
                : "(unknown)";

        // Convert region IDs to names
        String sourceRegionName = getRegionDisplayName(sourceRegion);

        // Build list of all affected regions (source + downstream disconnected)
        List<String> allAffectedRegionNames = new ArrayList<>();
        allAffectedRegionNames.add(sourceRegionName);
        for (String regionId : disconnectedRegions) {
            String name = getRegionDisplayName(regionId);
            if (!allAffectedRegionNames.contains(name)) {
                allAffectedRegionNames.add(name);
            }
        }

        // Log the damage (only if significant or causes disconnection)
        if (blocksDestroyed >= MIN_BLOCKS_FOR_LOG || !disconnectedRegions.isEmpty()) {
            plugin.getLogger().info("[RoadListener] " + blocksDestroyed + " road blocks destroyed in " + sourceRegionName +
                    " at " + coordsStr + " for " + affectedTeam + " team." +
                    (disconnectedRegions.isEmpty() ? "" : " Disconnected regions: " + String.join(", ", allAffectedRegionNames)));
        }

        // Notify all players on the affected team
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeam.isPresent() && playerTeam.get().equalsIgnoreCase(affectedTeam)) {
                // This player is on the affected team - warn them
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "⚠ Road damaged in " +
                        ChatColor.WHITE + sourceRegionName + ChatColor.RED + "!");

                // Show coordinates
                player.sendMessage(ChatColor.GRAY + "  Location: " + ChatColor.AQUA + coordsStr);

                if (!disconnectedRegions.isEmpty()) {
                    player.sendMessage(ChatColor.GRAY + "  Supplies disrupted to: " + ChatColor.YELLOW +
                            String.join(", ", allAffectedRegionNames));
                    player.sendMessage(ChatColor.GRAY + "  Respawn delays and health regen penalties now apply.");
                } else {
                    player.sendMessage(ChatColor.GRAY + "  " + blocksDestroyed + " road block(s) destroyed. Check supply status!");
                }

                // Play warning sound
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            }
        }

        // Notify the player who caused it (if enemy)
        if (causer != null) {
            Optional<String> causerTeam = teamService.getPlayerTeam(causer.getUniqueId());
            if (causerTeam.isPresent() && !causerTeam.get().equalsIgnoreCase(affectedTeam)) {
                causer.sendMessage(configManager.getPrefix() + ChatColor.GREEN +
                        "You damaged enemy roads in " + ChatColor.WHITE + sourceRegionName + ChatColor.GREEN + "!");
                causer.sendMessage(ChatColor.GRAY + "  Location: " + ChatColor.AQUA + coordsStr);
                if (!disconnectedRegions.isEmpty()) {
                    causer.sendMessage(ChatColor.GRAY + "  Supplies disrupted to: " + ChatColor.YELLOW +
                            String.join(", ", allAffectedRegionNames));
                }
            }
        }
    }

    /**
     * Gets the display name for a region (name + ID).
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId)
                    .map(name -> name + " [" + regionId + "]")
                    .orElse(regionId);
        }
        return regionId;
    }

    /**
     * Callback interface for road disruption events.
     */
    @FunctionalInterface
    public interface RoadDisruptionCallback {
        void onRoadDisrupted(String team, List<String> affectedRegions, RoadBlock destroyedBlock);
    }
}

