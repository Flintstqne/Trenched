package org.flintstqne.entrenched.ObjectiveLogic;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
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

    // Scheduled tasks
    private BukkitTask uiUpdateTask;
    private BukkitTask particleTask;

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

        // Show action bar warning if Hold Ground is in progress
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
            case RAID_PLANT_EXPLOSIVE -> "⚠ Explosive Being Planted!";
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

            // Special handling for hold ground - use region center from hold zone info
            if (objective.type() == ObjectiveType.RAID_HOLD_GROUND) {
                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(objective.regionId());
                if (holdZoneOpt.isPresent()) {
                    int[] holdZone = holdZoneOpt.get();
                    int y = player.getWorld().getHighestBlockYAt(holdZone[0], holdZone[1]) + 1;
                    objLoc = new Location(player.getWorld(), holdZone[0] + 0.5, y, holdZone[1] + 0.5);
                }
            } else if (objective.hasLocation()) {
                objLoc = objective.getLocation(player.getWorld());
            }

            if (objLoc == null) continue;

            double distance = player.getLocation().distance(objLoc);
            if (distance > bossBarDistance) continue;

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

        // Find nearest objective with a location
        RegionObjective nearest = null;
        double nearestDist = Double.MAX_VALUE;
        Location nearestLoc = null;

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
            } else if (obj.hasLocation()) {
                objLoc = obj.getLocation(player.getWorld());
            }

            if (objLoc == null) continue;

            double dist = player.getLocation().distance(objLoc);
            if (dist < nearestDist && dist <= hintDistance) {
                nearest = obj;
                nearestDist = dist;
                nearestLoc = objLoc;
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
        String direction = getDirectionIndicator(player, nearestLoc);
        int dist = (int) nearestDist;

        Component message = Component.text(symbol + " ")
                .color(nearest.type().isRaid() ? NamedTextColor.RED : NamedTextColor.GREEN)
                .append(Component.text(nearest.type().getDisplayName())
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(" [" + nearest.getInfluenceReward() + " IP] ")
                        .color(NamedTextColor.GOLD))
                .append(Component.text("- " + dist + "m " + direction)
                        .color(NamedTextColor.GRAY));

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
    }
}

