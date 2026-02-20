package org.flintstqne.entrenched.ObjectiveLogic;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the UI elements for objectives:
 * - Boss bars for nearby objectives
 * - Action bar hints pointing to objectives
 * - Particle markers at objective locations
 */
public class ObjectiveUIManager {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final RegionService regionService;
    private final RoundService roundService;
    private final TeamService teamService;
    private final ConfigManager config;

    // Player -> Map of ObjectiveId -> BossBar
    private final Map<UUID, Map<Integer, BossBar>> playerBossBars = new ConcurrentHashMap<>();

    // Currently shown action bar objective per player
    private final Map<UUID, Integer> playerActionBarObjective = new ConcurrentHashMap<>();

    // Track players who currently have glowing effect for assassination objective
    private final Set<UUID> glowingTargets = ConcurrentHashMap.newKeySet();

    // Scheduled tasks
    private BukkitTask uiUpdateTask;
    private BukkitTask particleTask;
    private BukkitTask assassinationUpdateTask;

    public ObjectiveUIManager(JavaPlugin plugin, ObjectiveService objectiveService,
                               RegionService regionService, RoundService roundService,
                               TeamService teamService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.regionService = regionService;
        this.roundService = roundService;
        this.teamService = teamService;
        this.config = config;
    }

    /**
     * Starts the UI update tasks.
     */
    public void start() {
        // Update UI every second
        uiUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAllPlayerUI, 20L, 20L);

        // Spawn particles every half second if markers are enabled
        if (config.isObjectiveMarkersEnabled()) {
            particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnObjectiveParticles, 10L, 10L);
        }

        // Update assassination targets every second (glowing effect)
        assassinationUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAssassinationTargets, 20L, 20L);

        plugin.getLogger().info("[Objectives] UI Manager started");
    }

    /**
     * Stops the UI update tasks.
     */
    public void stop() {
        if (uiUpdateTask != null) {
            uiUpdateTask.cancel();
        }
        if (particleTask != null) {
            particleTask.cancel();
        }
        if (assassinationUpdateTask != null) {
            assassinationUpdateTask.cancel();
        }

        // Remove glowing effect from all targets
        for (UUID targetId : glowingTargets) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                target.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        glowingTargets.clear();

        // Clear all boss bars
        for (UUID playerId : playerBossBars.keySet()) {
            clearBossBars(playerId);
        }

        plugin.getLogger().info("[Objectives] UI Manager stopped");
    }

    /**
     * Updates UI for all online players.
     */
    private void updateAllPlayerUI() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(gameWorld)) {
                clearBossBars(player.getUniqueId());
                continue;
            }

            updatePlayerUI(player);
        }
    }

    /**
     * Updates UI for a specific player.
     */
    private void updatePlayerUI(Player player) {
        String regionId = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        if (regionId == null) {
            clearBossBars(player.getUniqueId());
            return;
        }

        // Get player's team
        String playerTeam = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
        if (playerTeam == null) {
            clearBossBars(player.getUniqueId());
            return;
        }

        // Get region status
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            clearBossBars(player.getUniqueId());
            return;
        }

        RegionStatus status = statusOpt.get();

        // Check if player is a defender - show alerts for active Hold Ground objectives
        if (isDefender(status, playerTeam)) {
            updateDefenderAlerts(player, regionId, status);
            return;
        }

        // Determine which objectives are relevant for this player (attackers/settlers)
        ObjectiveCategory relevantCategory = getRelevantCategory(status, playerTeam);
        if (relevantCategory == null) {
            clearBossBars(player.getUniqueId());
            return;
        }

        // Get active objectives
        List<RegionObjective> objectives = objectiveService.getActiveObjectives(regionId, relevantCategory);

        // Update boss bars for nearby objectives
        if (config.isObjectiveBossBarsEnabled()) {
            updateBossBars(player, objectives);
        }

        // Update action bar with nearest objective hint
        if (config.isObjectiveHintsEnabled()) {
            updateActionBar(player, objectives);
        }
    }

    /**
     * Checks if a player is defending a region (owns it).
     */
    private boolean isDefender(RegionStatus status, String playerTeam) {
        if (status.state() == RegionState.OWNED || status.state() == RegionState.CONTESTED) {
            return playerTeam.equalsIgnoreCase(status.ownerTeam());
        }
        return false;
    }

    /**
     * Updates defender alerts - shows warnings when attackers are completing objectives.
     */
    private void updateDefenderAlerts(Player player, String regionId, RegionStatus status) {
        UUID playerId = player.getUniqueId();
        Map<Integer, BossBar> bars = playerBossBars.computeIfAbsent(playerId, k -> new HashMap<>());
        Set<Integer> activeObjectiveIds = new HashSet<>();

        // Get raid objectives in this region (these are what attackers are doing)
        List<RegionObjective> raidObjectives = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.RAID);

        for (RegionObjective objective : raidObjectives) {
            // Only show alerts for objectives with progress (attackers actively working on them)
            // or Hold Ground objectives (always show so defenders know the zone)
            boolean shouldAlert = objective.progress() > 0 || objective.type() == ObjectiveType.RAID_HOLD_GROUND;

            if (!shouldAlert) continue;

            // For Hold Ground, check if player is near the zone
            if (objective.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(regionId);
                if (holdZoneOpt.isPresent()) {
                    int[] holdZone = holdZoneOpt.get();
                    double distance = Math.sqrt(
                            Math.pow(player.getLocation().getBlockX() - holdZone[0], 2) +
                            Math.pow(player.getLocation().getBlockZ() - holdZone[1], 2)
                    );
                    // Only show if within reasonable distance (2x boss bar distance)
                    if (distance > config.getObjectiveBossBarDistance() * 2) continue;
                }
            }

            activeObjectiveIds.add(objective.id());

            // Create or update warning boss bar
            BossBar bar = bars.computeIfAbsent(objective.id(), id -> {
                BossBar newBar = BossBar.bossBar(
                        getDefenderWarningTitle(objective),
                        (float) objective.progress(),
                        BossBar.Color.YELLOW,
                        BossBar.Overlay.PROGRESS
                );
                player.showBossBar(newBar);
                return newBar;
            });

            // Update progress and name
            bar.progress((float) Math.min(1.0, Math.max(0.0, objective.progress())));
            bar.name(getDefenderWarningTitle(objective));

            // Change color based on urgency
            if (objective.progress() > 0.75) {
                bar.color(BossBar.Color.RED);
            } else if (objective.progress() > 0.5) {
                bar.color(BossBar.Color.YELLOW);
            } else {
                bar.color(BossBar.Color.WHITE);
            }
        }

        // Show action bar warning if Hold Ground or Plant Explosive is in progress
        for (RegionObjective objective : raidObjectives) {
            if (objective.type() == ObjectiveType.RAID_HOLD_GROUND && objective.progress() > 0) {
                int seconds = (int) (objective.progress() * 60);
                Component warning = Component.text("⚠ ALERT: ")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text("Enemy holding region center! ")
                                .color(NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text("(" + seconds + "/60s)")
                                .color(NamedTextColor.WHITE));
                player.sendActionBar(warning);
                break;
            }
            if (objective.type() == ObjectiveType.RAID_PLANT_EXPLOSIVE && objective.progress() > 0) {
                int seconds = (int) (objective.progress() * 30);
                int remaining = 30 - seconds;

                // Get explosive location for direction
                Optional<ObjectiveService.PlantedExplosiveInfo> explosiveInfo =
                        objectiveService.getPlantedExplosiveInfo(objective.regionId());

                String directionText = "";
                if (explosiveInfo.isPresent()) {
                    ObjectiveService.PlantedExplosiveInfo info = explosiveInfo.get();
                    Location tntLoc = new Location(player.getWorld(), info.x() + 0.5, info.y(), info.z() + 0.5);
                    directionText = " " + getDirectionIndicator(player, tntLoc) + " " +
                            (int) player.getLocation().distance(tntLoc) + "m";
                }

                Component warning = Component.text("💣 EXPLOSIVE PLANTED! ")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text("Defuse in " + remaining + "s!")
                                .color(NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text(directionText)
                                .color(NamedTextColor.GRAY));
                player.sendActionBar(warning);
                break;
            }
        }

        // Remove boss bars for objectives no longer active/alerting
        Iterator<Map.Entry<Integer, BossBar>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BossBar> entry = iterator.next();
            if (!activeObjectiveIds.contains(entry.getKey())) {
                player.hideBossBar(entry.getValue());
                iterator.remove();
            }
        }
    }

    /**
     * Gets the defender warning title for an objective.
     */
    private Component getDefenderWarningTitle(RegionObjective objective) {
        String warningText = switch (objective.type()) {
            case RAID_HOLD_GROUND -> {
                int seconds = (int) (objective.progress() * 60);
                yield "⚠ ENEMIES HOLDING CENTER (" + seconds + "/60s)";
            }
            case RAID_DESTROY_CACHE -> "⚠ Supply Cache Under Attack!";
            case RAID_SABOTAGE_DEFENSES -> {
                int blocks = (int) (objective.progress() * 50);
                yield "⚠ Defenses Being Sabotaged (" + blocks + "/50)";
            }
            case RAID_PLANT_EXPLOSIVE -> {
                if (objective.progress() > 0) {
                    int seconds = (int) (objective.progress() * 30);
                    yield "💣 EXPLOSIVE PLANTED! (" + seconds + "/30s) - DEFUSE IT!";
                } else {
                    yield "⚠ Explosive Being Planted!";
                }
            }
            case RAID_ASSASSINATE_COMMANDER -> "⚠ Commander Targeted!";
            case RAID_CAPTURE_INTEL -> "⚠ Intel Being Stolen!";
            default -> "⚠ Region Under Attack!";
        };


        return Component.text(warningText)
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD);
    }

    /**
     * Determines which objective category is relevant for a player in a region.
     */
    private ObjectiveCategory getRelevantCategory(RegionStatus status, String playerTeam) {
        if (status.state() == RegionState.NEUTRAL) {
            // Both teams can do settlement objectives in neutral regions
            return ObjectiveCategory.SETTLEMENT;
        } else if (status.state() == RegionState.OWNED || status.state() == RegionState.CONTESTED) {
            // Only attackers can do raid objectives
            if (!playerTeam.equalsIgnoreCase(status.ownerTeam())) {
                return ObjectiveCategory.RAID;
            }
        }
        // Defenders or protected/fortified regions - no relevant objectives
        return null;
    }

    /**
     * Updates boss bars for a player based on nearby objectives.
     */
    private void updateBossBars(Player player, List<RegionObjective> objectives) {
        UUID playerId = player.getUniqueId();
        Map<Integer, BossBar> bars = playerBossBars.computeIfAbsent(playerId, k -> new HashMap<>());

        int bossBarDistance = config.getObjectiveBossBarDistance();
        Set<Integer> activeObjectiveIds = new HashSet<>();

        String playerRegion = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        for (RegionObjective objective : objectives) {
            Location objLoc = null;
            boolean isRegionWideObjective = false;

            // Special handling for hold ground - use region center from hold zone info
            if (objective.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(objective.regionId());
                if (holdZoneOpt.isPresent()) {
                    int[] holdZone = holdZoneOpt.get();
                    int y = player.getWorld().getHighestBlockYAt(holdZone[0], holdZone[1]) + 1;
                    objLoc = new Location(player.getWorld(), holdZone[0] + 0.5, y, holdZone[1] + 0.5);
                }
            } else if (objective.type() == ObjectiveType.RAID_DESTROY_CACHE) {
                // Special handling for destroy cache - find nearest enemy chest
                String playerTeam = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
                if (playerTeam != null) {
                    List<int[]> enemyChests = objectiveService.getEnemyChestLocations(objective.regionId(), playerTeam);
                    if (!enemyChests.isEmpty()) {
                        // Find nearest enemy chest
                        double nearestChestDist = Double.MAX_VALUE;
                        for (int[] chestPos : enemyChests) {
                            double dist = Math.sqrt(
                                    Math.pow(player.getLocation().getX() - chestPos[0], 2) +
                                    Math.pow(player.getLocation().getZ() - chestPos[2], 2)
                            );
                            if (dist < nearestChestDist) {
                                nearestChestDist = dist;
                                objLoc = new Location(player.getWorld(), chestPos[0] + 0.5, chestPos[1], chestPos[2] + 0.5);
                            }
                        }
                    }
                }
            } else if (isRegionWideObjective(objective.type())) {
                // Region-wide objectives (like Resource Depot) show for all players in the region
                isRegionWideObjective = true;
            } else if (objective.hasLocation()) {
                objLoc = objective.getLocation(player.getWorld());
            }

            // Skip if no location and not a region-wide objective
            if (objLoc == null && !isRegionWideObjective) continue;

            // For region-wide objectives, check if player is in the same region
            if (isRegionWideObjective) {
                if (!objective.regionId().equals(playerRegion)) continue;
            } else {
                // For location-based objectives, check distance
                double distance = player.getLocation().distance(objLoc);
                if (distance > bossBarDistance) continue;
            }

            activeObjectiveIds.add(objective.id());

            // Create or update boss bar
            BossBar bar = bars.computeIfAbsent(objective.id(), id -> {
                BossBar newBar = BossBar.bossBar(
                        getObjectiveTitle(objective),
                        (float) objective.progress(),
                        getBossBarColor(objective),
                        BossBar.Overlay.PROGRESS
                );
                player.showBossBar(newBar);
                return newBar;
            });

            // Update progress and name
            bar.progress((float) Math.min(1.0, Math.max(0.0, objective.progress())));
            bar.name(getObjectiveTitle(objective));
        }

        // Remove boss bars for objectives no longer in range
        Iterator<Map.Entry<Integer, BossBar>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BossBar> entry = iterator.next();
            if (!activeObjectiveIds.contains(entry.getKey())) {
                player.hideBossBar(entry.getValue());
                iterator.remove();
            }
        }
    }

    /**
     * Checks if an objective type is region-wide (no specific location).
     */
    private boolean isRegionWideObjective(ObjectiveType type) {
        return switch (type) {
            case SETTLEMENT_RESOURCE_DEPOT,
                 SETTLEMENT_SECURE_PERIMETER,
                 SETTLEMENT_SUPPLY_ROUTE,
                 SETTLEMENT_WATCHTOWER,
                 SETTLEMENT_GARRISON_QUARTERS -> true;
            default -> false;
        };
    }

    /**
     * Clears all boss bars for a player.
     */
    private void clearBossBars(UUID playerId) {
        Map<Integer, BossBar> bars = playerBossBars.remove(playerId);
        if (bars == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        for (BossBar bar : bars.values()) {
            player.hideBossBar(bar);
        }
    }

    /**
     * Gets the boss bar title component for an objective.
     */
    private Component getObjectiveTitle(RegionObjective objective) {
        String symbol = objective.type().isRaid() ? "⚔" : "⚒";
        String progressText = objective.getProgressDescription();

        return Component.text(symbol + " ")
                .color(objective.type().isRaid() ? NamedTextColor.RED : NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .append(Component.text(objective.type().getDisplayName() + ": ")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(progressText)
                        .color(NamedTextColor.WHITE));
    }

    /**
     * Gets the boss bar color for an objective.
     */
    private BossBar.Color getBossBarColor(RegionObjective objective) {
        return objective.type().isRaid() ? BossBar.Color.RED : BossBar.Color.GREEN;
    }

    /**
     * Updates action bar with hint for nearest objective.
     */
    private void updateActionBar(Player player, List<RegionObjective> objectives) {
        if (objectives.isEmpty()) {
            playerActionBarObjective.remove(player.getUniqueId());
            return;
        }

        int hintDistance = config.getObjectiveHintDistance();
        String playerRegion = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        // Find nearest objective with a location OR a region-wide objective
        RegionObjective nearest = null;
        double nearestDist = Double.MAX_VALUE;
        Location nearestLoc = null;
        boolean isRegionWide = false;

        for (RegionObjective obj : objectives) {
            Location objLoc = null;

            // Special handling for hold ground - use region center
            if (obj.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(obj.regionId());
                if (holdZoneOpt.isPresent()) {
                    int[] holdZone = holdZoneOpt.get();
                    int y = player.getWorld().getHighestBlockYAt(holdZone[0], holdZone[1]) + 1;
                    objLoc = new Location(player.getWorld(), holdZone[0] + 0.5, y, holdZone[1] + 0.5);
                }
            } else if (obj.type() == ObjectiveType.RAID_DESTROY_CACHE) {
                // Special handling for destroy cache - find nearest enemy chest
                String playerTeam = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
                if (playerTeam != null && obj.regionId().equals(playerRegion)) {
                    List<int[]> enemyChests = objectiveService.getEnemyChestLocations(obj.regionId(), playerTeam);
                    if (!enemyChests.isEmpty()) {
                        // Find nearest enemy chest
                        double nearestChestDist = Double.MAX_VALUE;
                        for (int[] chestPos : enemyChests) {
                            double dist = Math.sqrt(
                                    Math.pow(player.getLocation().getX() - chestPos[0], 2) +
                                    Math.pow(player.getLocation().getZ() - chestPos[2], 2)
                            );
                            if (dist < nearestChestDist) {
                                nearestChestDist = dist;
                                objLoc = new Location(player.getWorld(), chestPos[0] + 0.5, chestPos[1], chestPos[2] + 0.5);
                            }
                        }
                    }
                }
            } else if (isRegionWideObjective(obj.type())) {
                // Region-wide objectives - show if player is in the region
                if (obj.regionId().equals(playerRegion)) {
                    // Prioritize showing region-wide objectives with progress
                    if (obj.progress() > 0) {
                        nearest = obj;
                        nearestDist = 0;
                        nearestLoc = null;
                        isRegionWide = true;
                        break; // Region-wide with progress takes priority
                    } else if (nearest == null || nearestDist == Double.MAX_VALUE) {
                        nearest = obj;
                        nearestDist = 0;
                        nearestLoc = null;
                        isRegionWide = true;
                    }
                }
                continue;
            } else if (obj.hasLocation()) {
                objLoc = obj.getLocation(player.getWorld());
            }

            if (objLoc == null) continue;

            double dist = player.getLocation().distance(objLoc);
            if (dist < nearestDist && dist <= hintDistance) {
                nearest = obj;
                nearestDist = dist;
                nearestLoc = objLoc;
                isRegionWide = false;
            }
        }

        if (nearest == null) {
            playerActionBarObjective.remove(player.getUniqueId());
            return;
        }

        // Only update if different objective or significant progress change
        Integer lastShown = playerActionBarObjective.get(player.getUniqueId());
        if (lastShown != null && lastShown == nearest.id()) {
            // Still same objective, update less frequently
            return;
        }

        playerActionBarObjective.put(player.getUniqueId(), nearest.id());

        // Build action bar message
        String symbol = nearest.type().isRaid() ? "⚔" : "⚒";

        Component message;
        if (isRegionWide) {
            // Region-wide objective - show progress instead of distance
            int progressPct = nearest.getProgressPercent();
            message = Component.text(symbol + " ")
                    .color(nearest.type().isRaid() ? NamedTextColor.RED : NamedTextColor.GREEN)
                    .append(Component.text(nearest.type().getDisplayName())
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(" [" + nearest.getInfluenceReward() + " IP] ")
                            .color(NamedTextColor.GOLD))
                    .append(Component.text("- " + progressPct + "% complete")
                            .color(NamedTextColor.GRAY));
        } else {
            String direction = getDirectionIndicator(player, nearestLoc);
            int dist = (int) nearestDist;

            message = Component.text(symbol + " ")
                    .color(nearest.type().isRaid() ? NamedTextColor.RED : NamedTextColor.GREEN)
                    .append(Component.text(nearest.type().getDisplayName())
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(" [" + nearest.getInfluenceReward() + " IP] ")
                            .color(NamedTextColor.GOLD))
                    .append(Component.text("- " + dist + "m " + direction)
                            .color(NamedTextColor.GRAY));
        }

        player.sendActionBar(message);
    }

    /**
     * Gets a direction indicator (arrow) for a target location relative to player.
     */
    private String getDirectionIndicator(Player player, Location targetLoc) {
        if (targetLoc == null) return "";

        Location playerLoc = player.getLocation();
        double dx = targetLoc.getX() - playerLoc.getX();
        double dz = targetLoc.getZ() - playerLoc.getZ();

        // Get player's facing direction
        float yaw = playerLoc.getYaw();

        // Calculate angle to objective
        double angleToObj = Math.toDegrees(Math.atan2(-dx, dz));
        double relativeAngle = angleToObj - yaw;

        // Normalize to -180 to 180
        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        // Determine arrow direction
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) return "↑";
        if (relativeAngle >= 22.5 && relativeAngle < 67.5) return "↗";
        if (relativeAngle >= 67.5 && relativeAngle < 112.5) return "→";
        if (relativeAngle >= 112.5 && relativeAngle < 157.5) return "↘";
        if (relativeAngle >= 157.5 || relativeAngle < -157.5) return "↓";
        if (relativeAngle >= -157.5 && relativeAngle < -112.5) return "↙";
        if (relativeAngle >= -112.5 && relativeAngle < -67.5) return "←";
        if (relativeAngle >= -67.5 && relativeAngle < -22.5) return "↖";

        return "";
    }

    /**
     * Spawns particle markers at objective locations.
     */
    private void spawnObjectiveParticles() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) return;

        // Get all regions with active objectives
        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            List<RegionObjective> objectives = objectiveService.getActiveObjectives(status.regionId());

            for (RegionObjective objective : objectives) {
                // Special handling for Hold Ground objectives - draw a circle
                if (objective.type() == ObjectiveType.RAID_HOLD_GROUND) {
                    spawnHoldZoneParticles(gameWorld, status.regionId(), objective);
                    continue;
                }

                if (!objective.hasLocation()) continue;

                Location loc = objective.getLocation(gameWorld);
                if (loc == null) continue;

                // Only spawn particles if players are nearby
                boolean hasNearbyPlayer = false;
                for (Player player : gameWorld.getPlayers()) {
                    if (player.getLocation().distance(loc) < 100) {
                        hasNearbyPlayer = true;
                        break;
                    }
                }

                if (!hasNearbyPlayer) continue;

                // Spawn beacon-like particles
                Particle.DustOptions dust = new Particle.DustOptions(
                        objective.type().isRaid() ? Color.RED : Color.GREEN,
                        1.5f
                );

                // Vertical beam
                for (int y = 0; y < 20; y += 2) {
                    Location particleLoc = loc.clone().add(0, y, 0);
                    gameWorld.spawnParticle(Particle.DUST, particleLoc, 3, 0.2, 0.2, 0.2, 0, dust);
                }

                // Circle at base
                for (int i = 0; i < 8; i++) {
                    double angle = (2 * Math.PI / 8) * i;
                    double offsetX = Math.cos(angle) * 1.5;
                    double offsetZ = Math.sin(angle) * 1.5;
                    Location particleLoc = loc.clone().add(offsetX, 0.5, offsetZ);
                    gameWorld.spawnParticle(Particle.DUST, particleLoc, 2, 0, 0, 0, 0, dust);
                }
            }
        }
    }

    /**
     * Spawns the hold zone circle particles for hold ground objectives.
     */
    private void spawnHoldZoneParticles(World world, String regionId, RegionObjective objective) {
        Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(regionId);
        if (holdZoneOpt.isEmpty()) return;

        int[] holdZone = holdZoneOpt.get();
        int centerX = holdZone[0];
        int centerZ = holdZone[1];
        int radius = holdZone[2];

        // Check if any players are nearby
        Location center = new Location(world, centerX + 0.5, 64, centerZ + 0.5);
        center.setY(world.getHighestBlockYAt(centerX, centerZ) + 1);

        boolean hasNearbyPlayer = false;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(center) < 150) {
                hasNearbyPlayer = true;
                break;
            }
        }

        if (!hasNearbyPlayer) return;

        // Draw circle around hold zone
        Particle.DustOptions orangeDust = new Particle.DustOptions(Color.ORANGE, 1.2f);
        int particleCount = Math.max(16, radius); // More particles for larger circles

        for (int i = 0; i < particleCount; i++) {
            double angle = (2 * Math.PI / particleCount) * i;
            double offsetX = Math.cos(angle) * radius;
            double offsetZ = Math.sin(angle) * radius;

            int x = centerX + (int) offsetX;
            int z = centerZ + (int) offsetZ;
            int y = world.getHighestBlockYAt(x, z) + 1;

            Location particleLoc = new Location(world, x + 0.5, y + 0.5, z + 0.5);
            world.spawnParticle(Particle.DUST, particleLoc, 2, 0.1, 0.3, 0.1, 0, orangeDust);
        }

        // Draw center marker
        Particle.DustOptions yellowDust = new Particle.DustOptions(Color.YELLOW, 2.0f);
        for (int y = 0; y < 10; y += 2) {
            Location particleLoc = center.clone().add(0, y, 0);
            world.spawnParticle(Particle.DUST, particleLoc, 3, 0.3, 0.3, 0.3, 0, yellowDust);
        }

        // Show progress text if any progress
        if (objective.progress() > 0) {
            int seconds = (int) (objective.progress() * 60);
            // Progress is shown via boss bar, particles are enough for location
        }
    }

    /**
     * Shows an objective completion notification.
     */
    public void showCompletionNotification(Player player, RegionObjective objective) {
        // Title notification
        Component title = Component.text("✓ OBJECTIVE COMPLETE")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD);

        Component subtitle = Component.text(objective.type().getDisplayName())
                .color(NamedTextColor.YELLOW)
                .append(Component.text(" +" + objective.getInfluenceReward() + " IP")
                        .color(NamedTextColor.GOLD));

        player.showTitle(net.kyori.adventure.title.Title.title(
                title, subtitle,
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250),
                        java.time.Duration.ofMillis(2000),
                        java.time.Duration.ofMillis(500)
                )
        ));

        // Sound effect
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    /**
     * Shows an objective spawn notification to nearby players.
     */
    public void showSpawnNotification(RegionObjective objective) {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) return;

        // Get region center for notification
        int regionSize = config.getRegionSize();

        for (Player player : gameWorld.getPlayers()) {
            String playerRegion = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );

            // Notify players in the same region
            if (objective.regionId().equals(playerRegion)) {
                String team = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
                if (team == null) continue;

                // Check if this objective is relevant for the player
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(objective.regionId());
                if (statusOpt.isEmpty()) continue;

                ObjectiveCategory relevantCategory = getRelevantCategory(statusOpt.get(), team);
                if (relevantCategory != objective.type().getCategory()) continue;

                // Show notification
                Component message = Component.text("⚡ ")
                        .color(NamedTextColor.GOLD)
                        .append(Component.text("New Objective: ")
                                .color(NamedTextColor.YELLOW))
                        .append(Component.text(objective.type().getDisplayName())
                                .color(NamedTextColor.WHITE))
                        .append(Component.text(" [" + objective.getInfluenceReward() + " IP]")
                                .color(NamedTextColor.GOLD));

                player.sendMessage(message);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.5f);
            }
        }
    }

    /**
     * Called when a player joins to set up their UI.
     */
    public void onPlayerJoin(Player player) {
        // Nothing special needed, updateAllPlayerUI will handle them
    }

    /**
     * Called when a player leaves to clean up their UI.
     */
    public void onPlayerQuit(Player player) {
        clearBossBars(player.getUniqueId());
        playerActionBarObjective.remove(player.getUniqueId());

        // Remove glowing if they were a target
        if (glowingTargets.remove(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    // ==================== ASSASSINATION TARGET TRACKING ====================

    /**
     * Updates assassination target glowing and action bar for attackers.
     */
    private void updateAssassinationTargets() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) return;

        // Collect all current targets across all regions with assassination objectives
        Set<UUID> currentTargets = new HashSet<>();
        Map<String, List<UUID>> targetsByRegion = new HashMap<>();

        for (RegionStatus status : regionService.getAllRegionStatuses()) {
            if (status.state() != RegionState.OWNED && status.state() != RegionState.CONTESTED) {
                continue;
            }

            String regionId = status.regionId();

            // Check for active assassination objective
            boolean hasAssassinateObj = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.RAID)
                    .stream()
                    .anyMatch(obj -> obj.type() == ObjectiveType.RAID_ASSASSINATE_COMMANDER);

            if (hasAssassinateObj) {
                List<UUID> targets = objectiveService.getAssassinationTargets(regionId);
                if (!targets.isEmpty()) {
                    currentTargets.addAll(targets);
                    targetsByRegion.put(regionId, targets);
                }
            }
        }

        // Update glowing effects
        // Remove glowing from players who are no longer targets
        Iterator<UUID> iterator = glowingTargets.iterator();
        while (iterator.hasNext()) {
            UUID targetId = iterator.next();
            if (!currentTargets.contains(targetId)) {
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) {
                    target.removePotionEffect(PotionEffectType.GLOWING);
                }
                iterator.remove();
            }
        }

        // Add glowing to new targets
        for (UUID targetId : currentTargets) {
            if (!glowingTargets.contains(targetId)) {
                Player target = Bukkit.getPlayer(targetId);
                if (target != null && target.isOnline()) {
                    // Apply glowing effect (30 seconds, will be refreshed)
                    target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, false));
                    glowingTargets.add(targetId);
                }
            } else {
                // Refresh glowing for existing targets
                Player target = Bukkit.getPlayer(targetId);
                if (target != null && target.isOnline()) {
                    target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, false));
                }
            }
        }

        // Update action bar for attackers showing direction to nearest target
        for (Player player : gameWorld.getPlayers()) {
            String playerTeam = teamService.getPlayerTeam(player.getUniqueId()).orElse(null);
            if (playerTeam == null) continue;

            String playerRegion = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );
            if (playerRegion == null) continue;

            // Check if there are targets in this region and player is attacker
            List<UUID> regionTargets = targetsByRegion.get(playerRegion);
            if (regionTargets == null || regionTargets.isEmpty()) continue;

            Optional<RegionStatus> statusOpt = regionService.getRegionStatus(playerRegion);
            if (statusOpt.isEmpty()) continue;

            String ownerTeam = statusOpt.get().ownerTeam();
            if (ownerTeam == null || ownerTeam.equalsIgnoreCase(playerTeam)) continue;

            // Player is an attacker - find nearest target
            Player nearestTarget = null;
            double nearestDist = Double.MAX_VALUE;

            for (UUID targetId : regionTargets) {
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) continue;

                double dist = player.getLocation().distance(target.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestTarget = target;
                }
            }

            if (nearestTarget != null) {
                // Show action bar with direction to target
                String direction = getDirectionIndicator(player, nearestTarget.getLocation());
                String targetName = nearestTarget.getName();
                int dist = (int) nearestDist;

                Component message = Component.text("⚔ ")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text("TARGET: ")
                                .color(NamedTextColor.GOLD)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text(targetName)
                                .color(NamedTextColor.YELLOW))
                        .append(Component.text(" [" + dist + "m " + direction + "]")
                                .color(NamedTextColor.GRAY));

                player.sendActionBar(message);
            }
        }
    }
}

