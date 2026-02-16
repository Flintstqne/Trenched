package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for game events and forwards them to the RegionService for influence tracking.
 */
public final class RegionCaptureListener implements Listener {

    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer;

    // Track player-placed blocks for determining if a broken block was player-placed
    // Key: "x,y,z" -> Value: team that placed it
    private final Map<String, String> playerPlacedBlocks = new ConcurrentHashMap<>();

    // Banner tracking: "x,z" -> team color
    private final Map<String, String> placedBanners = new ConcurrentHashMap<>();

    public RegionCaptureListener(RegionService regionService, TeamService teamService,
                                  ConfigManager configManager, RegionRenderer regionRenderer) {
        this.regionService = regionService;
        this.teamService = teamService;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
    }

    /**
     * Gets the display name for a region (e.g., "Shadowfen Valley" instead of "A1").
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null && regionId != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId != null ? regionId : "Unknown";
    }

    // Debounce for adjacency warning messages - don't spam players
    private final Map<UUID, Long> lastAdjacencyWarning = new ConcurrentHashMap<>();
    private static final long ADJACENCY_WARNING_COOLDOWN_MS = 10000; // 10 seconds

    /**
     * Checks if a player can earn influence in a region (must be adjacent to friendly territory).
     * Sends a warning message if not adjacent (with cooldown to prevent spam).
     *
     * @return true if the player can earn influence, false otherwise
     */
    private boolean canEarnInfluenceInRegion(Player player, String regionId, String team) {
        // Check if region is already owned by the team (no need to check adjacency)
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isPresent() && statusOpt.get().isOwnedBy(team)) {
            return false; // Can't earn influence in own region
        }

        // Check adjacency
        if (!regionService.isAdjacentToTeam(regionId, team)) {
            // Send warning with cooldown
            long now = System.currentTimeMillis();
            Long lastWarning = lastAdjacencyWarning.get(player.getUniqueId());

            if (lastWarning == null || (now - lastWarning) > ADJACENCY_WARNING_COOLDOWN_MS) {
                lastAdjacencyWarning.put(player.getUniqueId(), now);
                String regionName = getRegionDisplayName(regionId);
                player.sendMessage(configManager.getPrefix() + ChatColor.RED +
                        "You cannot earn influence in " + ChatColor.WHITE + regionName + ChatColor.RED + "!");
                player.sendMessage(ChatColor.GRAY + "  This region is not adjacent to any territory your team controls.");
                player.sendMessage(ChatColor.GRAY + "  Capture adjacent regions first to expand your frontline.");
            }
            return false;
        }

        return true;
    }

    // ==================== PLAYER DEATH ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) return;

        Optional<String> killerTeamOpt = teamService.getPlayerTeam(killer.getUniqueId());
        Optional<String> victimTeamOpt = teamService.getPlayerTeam(victim.getUniqueId());

        if (killerTeamOpt.isEmpty() || victimTeamOpt.isEmpty()) return;

        String killerTeam = killerTeamOpt.get();
        String victimTeam = victimTeamOpt.get();

        // Don't award points for team kills
        if (killerTeam.equalsIgnoreCase(victimTeam)) return;

        int blockX = victim.getLocation().getBlockX();
        int blockZ = victim.getLocation().getBlockZ();

        // Check adjacency before processing kill
        String regionId = regionService.getRegionIdForLocation(blockX, blockZ);
        if (regionId != null) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
            // Only check adjacency if region is not owned by killer's team
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(killerTeam)) {
                if (!canEarnInfluenceInRegion(killer, regionId, killerTeam)) {
                    return; // Can't earn IP in this region - message already sent
                }
            }
        }

        regionService.onPlayerKill(
                killer.getUniqueId(),
                victim.getUniqueId(),
                killerTeam,
                victimTeam,
                blockX,
                blockZ
        );

        // Notify players
        if (regionId != null) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(killerTeam)) {
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(killerTeam)) {
                double multiplier = regionService.getKillMultiplier(killer.getUniqueId(), victim.getUniqueId(), regionId);
                int points = (int) (configManager.getRegionKillPoints() * multiplier);
                String regionName = getRegionDisplayName(regionId);
                killer.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "+" + points + " IP " +
                        ChatColor.GRAY + "- Enemy killed in " + regionName);
            }
        }
    }

    // ==================== MOB DEATH ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        // Only track hostile mob kills
        if (!(entity instanceof Monster)) return;

        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        Optional<String> teamOpt = teamService.getPlayerTeam(killer.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        int blockX = entity.getLocation().getBlockX();
        int blockZ = entity.getLocation().getBlockZ();

        // Check adjacency before processing kill
        String regionId = regionService.getRegionIdForLocation(blockX, blockZ);
        if (regionId != null) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                if (!canEarnInfluenceInRegion(killer, regionId, team)) {
                    return; // Can't earn IP in this region
                }
            }
        }

        regionService.onMobKill(killer.getUniqueId(), team, blockX, blockZ);
    }

    // ==================== BLOCK PLACE ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        String blockKey = getBlockKey(block);
        String blockType = block.getType().name();

        // Track player-placed block
        playerPlacedBlocks.put(blockKey, team);

        // Check adjacency for influence gain
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());
        boolean canEarnInfluence = true;
        if (regionId != null) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team);
            }
        }

        // Check if it's a banner
        if (isBanner(block.getType())) {
            String bannerKey = block.getX() + "," + block.getZ();
            String bannerTeam = getBannerTeam(block.getType());

            // Check if placing over enemy banner
            String existingBanner = placedBanners.get(bannerKey);
            if (existingBanner != null && !existingBanner.equalsIgnoreCase(team) && canEarnInfluence) {
                regionService.onBannerRemove(player.getUniqueId(), team, block.getX(), block.getZ(), existingBanner);
            }

            if (bannerTeam != null && bannerTeam.equalsIgnoreCase(team) && canEarnInfluence) {
                placedBanners.put(bannerKey, team);
                regionService.onBannerPlace(player.getUniqueId(), team, block.getX(), block.getZ());

                if (regionId != null) {
                    String regionName = getRegionDisplayName(regionId);
                    player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "+" +
                            configManager.getRegionBannerPlacePoints() + " IP " +
                            ChatColor.GRAY + "- Banner placed in " + regionName);
                }
            }
        } else if (canEarnInfluence) {
            // Regular block placement - only if can earn influence
            regionService.onBlockPlace(
                    player.getUniqueId(),
                    team,
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    blockType
            );
        }
    }

    // ==================== BLOCK BREAK ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();
        String blockKey = getBlockKey(block);

        // Check if it was a player-placed block
        String placedByTeam = playerPlacedBlocks.remove(blockKey);
        boolean wasPlayerPlaced = placedByTeam != null;

        // Check adjacency for influence gain
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());
        boolean canEarnInfluence = true;
        if (regionId != null) {
            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
            if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team);
            }
        }

        // Check if it's a banner
        if (isBanner(block.getType())) {
            String bannerKey = block.getX() + "," + block.getZ();
            String bannerTeam = placedBanners.remove(bannerKey);

            if (bannerTeam != null && !bannerTeam.equalsIgnoreCase(team) && canEarnInfluence) {
                regionService.onBannerRemove(player.getUniqueId(), team, block.getX(), block.getZ(), bannerTeam);
                player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "+" +
                        configManager.getRegionBannerRemovePoints() + " IP " +
                        ChatColor.GRAY + "- Enemy banner destroyed");
            }
        } else if (canEarnInfluence) {
            // Regular block break - only if can earn influence
            regionService.onBlockBreak(
                    player.getUniqueId(),
                    team,
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    wasPlayerPlaced,
                    placedByTeam
            );
        }
    }

    // ==================== HELPER METHODS ====================

    private String getBlockKey(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private boolean isBanner(Material material) {
        String name = material.name();
        return name.contains("BANNER") && !name.contains("PATTERN");
    }

    private String getBannerTeam(Material material) {
        String name = material.name();
        if (name.contains("RED")) return "red";
        if (name.contains("BLUE")) return "blue";
        return null;
    }

    /**
     * Clears all tracked data. Called on new round.
     */
    public void clearTrackedData() {
        playerPlacedBlocks.clear();
        placedBanners.clear();
    }
}

