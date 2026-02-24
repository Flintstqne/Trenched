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

    // Banner placement cooldown: "x,z" -> timestamp when IP was last awarded
    // Prevents place/break/place IP farming at the same location
    private final Map<String, Long> bannerPlacementCooldowns = new ConcurrentHashMap<>();
    private static final long BANNER_COOLDOWN_MS = 300000; // 5 minutes

    // Banner region tracking: "regionId" -> Set of "chunkAreaKey" where banners earned IP
    // chunkAreaKey is based on 2x2 chunk areas (32x32 blocks)
    // Key format: "regionId:team" -> Set of "areaX,areaZ"
    private final Map<String, Set<String>> bannerAreaTracking = new ConcurrentHashMap<>();
    private static final int BANNER_AREA_SIZE = 32; // 2x2 chunks = 32 blocks

    // Track banners that have earned IP - "x,z" -> regionId
    // Used to deduct IP when player breaks their own banner
    private final Map<String, String> bannersEarnedIP = new ConcurrentHashMap<>();

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
     * Silently returns false if not adjacent - use for arbitrary actions like block placement.
     *
     * @return true if the player can earn influence, false otherwise
     */
    private boolean canEarnInfluenceInRegion(Player player, String regionId, String team) {
        return canEarnInfluenceInRegion(player, regionId, team, false);
    }

    /**
     * Checks if a player can earn influence in a region (must be adjacent to friendly territory).
     * Optionally sends a warning message if not adjacent (with cooldown to prevent spam).
     *
     * @param sendWarning If true, sends a warning message when not adjacent (for important actions like kills/banners)
     * @return true if the player can earn influence, false otherwise
     */
    private boolean canEarnInfluenceInRegion(Player player, String regionId, String team, boolean sendWarning) {
        // Check if region is already owned by the team (no need to check adjacency)
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isPresent() && statusOpt.get().isOwnedBy(team)) {
            return false; // Can't earn influence in own region
        }

        // Check adjacency
        if (!regionService.isAdjacentToTeam(regionId, team)) {
            // Only send warning if requested (for important actions like kills/banners)
            if (sendWarning) {
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
                // Player kills are important - send warning if not adjacent
                if (!canEarnInfluenceInRegion(killer, regionId, killerTeam, true)) {
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

        // Get region info for this block
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());

        // Check if it's a banner - banners are intentional capture actions, handle separately
        if (isBanner(block.getType())) {
            String bannerKey = block.getX() + "," + block.getZ();
            String bannerTeam = getBannerTeam(block.getType());

            // For banners, check adjacency with warning since it's an intentional capture action
            boolean canEarnInfluence = true;
            if (regionId != null) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                    // Banners are important - send warning if not adjacent
                    canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team, true);
                }
            }

            // Check if placing over enemy banner
            String existingBanner = placedBanners.get(bannerKey);
            if (existingBanner != null && !existingBanner.equalsIgnoreCase(team) && canEarnInfluence) {
                regionService.onBannerRemove(player.getUniqueId(), team, block.getX(), block.getZ(), existingBanner);
            }

            if (bannerTeam != null && bannerTeam.equalsIgnoreCase(team) && canEarnInfluence) {
                placedBanners.put(bannerKey, team);

                // Check cooldown to prevent place/break/place IP farming
                long now = System.currentTimeMillis();
                Long lastPlacement = bannerPlacementCooldowns.get(bannerKey);
                boolean onCooldown = lastPlacement != null && (now - lastPlacement) < BANNER_COOLDOWN_MS;

                if (!onCooldown) {
                    // Check 2x2 chunk area restriction - only one banner per 32x32 area per region
                    String areaKey = getBannerAreaKey(block.getX(), block.getZ());
                    String trackingKey = regionId + ":" + team;
                    Set<String> usedAreas = bannerAreaTracking.computeIfAbsent(trackingKey, k -> ConcurrentHashMap.newKeySet());

                    if (usedAreas.contains(areaKey)) {
                        // Already placed a banner in this 2x2 chunk area for this region
                        player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW +
                                "Banner placed, but you've already earned IP in this area. " +
                                "Banners must be " + BANNER_AREA_SIZE + " blocks apart to earn IP.");
                    } else {
                        // Award IP, set cooldown, mark area as used, and track this banner earned IP
                        bannerPlacementCooldowns.put(bannerKey, now);
                        usedAreas.add(areaKey);
                        bannersEarnedIP.put(bannerKey, regionId); // Track that this banner earned IP
                        regionService.onBannerPlace(player.getUniqueId(), team, block.getX(), block.getZ());

                        if (regionId != null) {
                            String regionName = getRegionDisplayName(regionId);
                            player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "+" +
                                    configManager.getRegionBannerPlacePoints() + " IP " +
                                    ChatColor.GRAY + "- Banner placed in " + regionName);
                        }
                    }
                } else {
                    // On cooldown - no IP awarded
                    long remainingSeconds = (BANNER_COOLDOWN_MS - (now - lastPlacement)) / 1000;
                    player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW +
                            "Banner placed, but this location is on cooldown (" + remainingSeconds + "s remaining).");
                }
            }
        } else {
            // Regular block placement - check adjacency silently (no warning for arbitrary blocks)
            boolean canEarnInfluence = true;
            if (regionId != null) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                    // Regular blocks - no warning, just silently skip if not adjacent
                    canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team, false);
                }
            }

            if (canEarnInfluence) {
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

        // Get region info for this block
        String regionId = regionService.getRegionIdForLocation(block.getX(), block.getZ());

        // Check if it's a banner - banners are intentional capture actions, handle separately
        if (isBanner(block.getType())) {
            String bannerKey = block.getX() + "," + block.getZ();
            String bannerTeam = placedBanners.remove(bannerKey);

            // For breaking enemy banners, check adjacency with warning since it's intentional
            boolean canEarnInfluence = true;
            if (regionId != null) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                    // Breaking enemy banners is important - send warning if not adjacent
                    canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team, true);
                }
            }

            if (bannerTeam != null && !bannerTeam.equalsIgnoreCase(team) && canEarnInfluence) {
                // Breaking enemy banner - award IP
                regionService.onBannerRemove(player.getUniqueId(), team, block.getX(), block.getZ(), bannerTeam);
                player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "+" +
                        configManager.getRegionBannerRemovePoints() + " IP " +
                        ChatColor.GRAY + "- Enemy banner destroyed");

                // If this enemy banner had earned IP, remove it from tracking
                bannersEarnedIP.remove(bannerKey);
            } else if (bannerTeam != null && bannerTeam.equalsIgnoreCase(team)) {
                // Breaking own team's banner - check if it earned IP and deduct
                String earnedInRegion = bannersEarnedIP.remove(bannerKey);
                if (earnedInRegion != null) {
                    // This banner earned IP - deduct it
                    regionService.onOwnBannerRemove(player.getUniqueId(), team, block.getX(), block.getZ());
                    player.sendMessage(configManager.getPrefix() + ChatColor.RED + "-" +
                            configManager.getRegionBannerPlacePoints() + " IP " +
                            ChatColor.GRAY + "- Own banner destroyed (IP returned)");

                    // Also remove from area tracking so they can place again in this area
                    String areaKey = getBannerAreaKey(block.getX(), block.getZ());
                    String trackingKey = earnedInRegion + ":" + team;
                    Set<String> usedAreas = bannerAreaTracking.get(trackingKey);
                    if (usedAreas != null) {
                        usedAreas.remove(areaKey);
                    }
                }
            }
        } else {
            // Regular block break - check adjacency silently (no warning for arbitrary blocks)
            boolean canEarnInfluence = true;
            if (regionId != null) {
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isPresent() && !statusOpt.get().isOwnedBy(team)) {
                    // Regular blocks - no warning, just silently skip if not adjacent
                    canEarnInfluence = canEarnInfluenceInRegion(player, regionId, team, false);
                }
            }

            if (canEarnInfluence) {
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
     * Gets the area key for a 2x2 chunk area (32x32 blocks) containing the given coordinates.
     * Used to enforce one banner per area per region.
     */
    private String getBannerAreaKey(int x, int z) {
        int areaX = Math.floorDiv(x, BANNER_AREA_SIZE);
        int areaZ = Math.floorDiv(z, BANNER_AREA_SIZE);
        return areaX + "," + areaZ;
    }

    /**
     * Clears all tracked data. Called on new round.
     */
    public void clearTrackedData() {
        playerPlacedBlocks.clear();
        placedBanners.clear();
        bannerAreaTracking.clear();
        bannersEarnedIP.clear();
        bannerPlacementCooldowns.clear();
    }
}

