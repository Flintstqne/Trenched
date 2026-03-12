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

    // Track which region each player was last in (for enemy-enter-region detection)
    private final Map<UUID, String> playerLastRegion = new ConcurrentHashMap<>();

    // Cooldown for threat alerts per region (regionId -> last alert timestamp)
    private final Map<String, Long> threatAlertCooldowns = new ConcurrentHashMap<>();
    private static final long THREAT_ALERT_COOLDOWN_MS = 60_000; // 1 minute between alerts per region

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
                playerLastRegion.remove(player.getUniqueId());
                continue;
            }

            // Check if an enemy just entered a defended region — alert defenders
            checkEnemyRegionEntry(player, gameWorld);

            updatePlayerUI(player);
        }
    }

    /**
     * Checks if a player just entered a region they can attack.
     * If so, alerts all defenders in that region about the threat.
     * Only fires when:
     * 1. The enemy's team owns an adjacent region (can actually capture)
     * 2. The region has active raid objectives
     * 3. The alert cooldown for that region has expired
     */
    private void checkEnemyRegionEntry(Player player, World gameWorld) {
        String currentRegion = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        String previousRegion = playerLastRegion.put(player.getUniqueId(), currentRegion);

        // Only trigger on region change
        if (currentRegion == null || currentRegion.equals(previousRegion)) return;

        // Get player's team
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return;
        String enemyTeam = teamOpt.get();

        // Get region status
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(currentRegion);
        if (statusOpt.isEmpty()) return;
        RegionStatus status = statusOpt.get();

        // Region must be owned by the OTHER team (this player is an enemy entering)
        if (status.ownerTeam() == null || status.ownerTeam().equalsIgnoreCase(enemyTeam)) return;
        if (status.state() != RegionState.OWNED && status.state() != RegionState.CONTESTED) return;

        // Enemy's team must own an adjacent region (can actually capture this region)
        if (!regionService.isAdjacentToTeam(currentRegion, enemyTeam)) return;

        // Must have active raid objectives
        List<RegionObjective> raidObjectives = objectiveService.getActiveObjectives(currentRegion, ObjectiveCategory.RAID);
        if (raidObjectives.isEmpty()) return;

        // Check cooldown for this region
        long now = System.currentTimeMillis();
        Long lastAlert = threatAlertCooldowns.get(currentRegion);
        if (lastAlert != null && (now - lastAlert) < THREAT_ALERT_COOLDOWN_MS) return;
        threatAlertCooldowns.put(currentRegion, now);

        // Alert all defenders in this region
        String defenderTeam = status.ownerTeam();
        for (Player defender : gameWorld.getPlayers()) {
            Optional<String> defTeamOpt = teamService.getPlayerTeam(defender.getUniqueId());
            if (defTeamOpt.isEmpty() || !defTeamOpt.get().equalsIgnoreCase(defenderTeam)) continue;

            String defenderRegion = regionService.getRegionIdForLocation(
                    defender.getLocation().getBlockX(),
                    defender.getLocation().getBlockZ()
            );

            if (!currentRegion.equals(defenderRegion)) continue;

            // Send threat alert for the most dangerous objective
            RegionObjective highestThreat = raidObjectives.stream()
                    .max(Comparator.comparingInt(RegionObjective::getInfluenceReward))
                    .orElse(raidObjectives.get(0));

            Component message = Component.text("⚠ ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("THREAT DETECTED: ")
                            .color(NamedTextColor.RED))
                    .append(Component.text(getDefenderThreatDescription(highestThreat.type()))
                            .color(NamedTextColor.YELLOW));

            defender.sendMessage(message);
            defender.playSound(defender.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
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

        // Check if region is valid for capturing (adjacent to team's territory)
        // Only show objectives in regions the team can actually influence
        if (!isRegionValidForCapture(regionId, playerTeam, status)) {
            clearBossBars(player.getUniqueId());
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

        // NOTE: Action bar hints removed - compass is now displayed on scoreboard
        // The ScoreboardUtil handles objective compass display
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
     * Checks if any enemy player is currently inside the hold ground zone for a region,
     * AND their team owns an adjacent region (meaning they can actually capture this region).
     */
    private boolean isEnemyInHoldZone(Player viewer, String regionId, RegionStatus status) {
        Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(regionId);
        if (holdZoneOpt.isEmpty()) return false;

        int[] holdZone = holdZoneOpt.get();
        int holdRadius = config.getRegionSize() / 8;
        String defenderTeam = status.ownerTeam();
        if (defenderTeam == null) return false;

        for (Player otherPlayer : viewer.getWorld().getPlayers()) {
            if (otherPlayer.getUniqueId().equals(viewer.getUniqueId())) continue;
            Optional<String> otherTeamOpt = teamService.getPlayerTeam(otherPlayer.getUniqueId());
            if (otherTeamOpt.isEmpty()) continue;
            String enemyTeam = otherTeamOpt.get();
            if (enemyTeam.equalsIgnoreCase(defenderTeam)) continue;

            // Enemy must own an adjacent region to actually be a threat
            if (!regionService.isAdjacentToTeam(regionId, enemyTeam)) continue;

            double dist = Math.sqrt(
                    Math.pow(otherPlayer.getLocation().getX() - holdZone[0], 2) +
                    Math.pow(otherPlayer.getLocation().getZ() - holdZone[1], 2));
            if (dist <= holdRadius) {
                return true;
            }
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

        // Check if any enemy team actually owns an adjacent region — if not, no real threat
        String defenderTeam = status.ownerTeam();
        if (defenderTeam != null) {
            String enemyTeam = defenderTeam.equalsIgnoreCase("red") ? "blue" : "red";
            if (!regionService.isAdjacentToTeam(regionId, enemyTeam)) {
                // No enemy adjacent — clear any existing alert bars and return
                clearDefenderBars(player, bars);
                return;
            }
        }

        // Get raid objectives in this region (these are what attackers are doing)
        List<RegionObjective> raidObjectives = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.RAID);

        for (RegionObjective objective : raidObjectives) {
            // Only show alerts for objectives with progress (attackers actively working on them)
            // For Hold Ground: only show when enemies are CURRENTLY in the hold zone
            boolean shouldAlert;
            if (objective.type() == ObjectiveType.RAID_HOLD_GROUND) {
                shouldAlert = isEnemyInHoldZone(player, regionId, status);
            } else {
                shouldAlert = objective.progress() > 0;
            }

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
                // Only show if enemies are CURRENTLY in the hold zone
                if (!isEnemyInHoldZone(player, regionId, status)) break;

                Optional<int[]> holdZoneOpt = objectiveService.getHoldZoneInfo(regionId);
                if (holdZoneOpt.isEmpty()) break;
                int[] holdZone = holdZoneOpt.get();

                int seconds = (int) (objective.progress() * 60);

                // Get hold zone location for direction
                String directionText = "";
                int y = player.getWorld().getHighestBlockYAt(holdZone[0], holdZone[1]) + 1;
                Location targetLoc = new Location(player.getWorld(), holdZone[0] + 0.5, y, holdZone[1] + 0.5);
                int distance = (int) Math.sqrt(
                        Math.pow(player.getLocation().getX() - holdZone[0], 2) +
                        Math.pow(player.getLocation().getZ() - holdZone[1], 2));
                directionText = " " + getDirectionIndicator(player, targetLoc) + " " + distance + "m";

                Component warning = Component.text("⚠ ALERT: ")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text("Enemy holding region center!")
                                .color(NamedTextColor.YELLOW)
                                .decoration(TextDecoration.BOLD, false))
                        .append(Component.text(" (" + seconds + "/60s)")
                                .color(NamedTextColor.WHITE))
                        .append(Component.text(directionText)
                                .color(NamedTextColor.AQUA));
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
            if (objective.type() == ObjectiveType.RAID_CAPTURE_INTEL && objective.progress() > 0) {
                // Get intel carrier info
                Optional<ObjectiveService.IntelCarrierInfo> intelInfo =
                        objectiveService.getIntelCarrierInfo(objective.regionId());

                if (intelInfo.isPresent()) {
                    ObjectiveService.IntelCarrierInfo info = intelInfo.get();

                    if (info.isDropped()) {
                        // Intel is dropped - show direction to it and remaining time
                        Location dropLoc = new Location(player.getWorld(), info.droppedX() + 0.5, info.droppedY(), info.droppedZ() + 0.5);
                        String directionText = getDirectionIndicator(player, dropLoc) + " " +
                                (int) player.getLocation().distance(dropLoc) + "m";
                        int secondsRemaining = info.droppedSecondsRemaining();

                        Component warning = Component.text("⚡ INTEL DROPPED! ")
                                .color(NamedTextColor.GOLD)
                                .decorate(TextDecoration.BOLD)
                                .append(Component.text("Recover in " + secondsRemaining + "s! ")
                                        .color(NamedTextColor.YELLOW)
                                        .decoration(TextDecoration.BOLD, false))
                                .append(Component.text(directionText)
                                        .color(NamedTextColor.GRAY));
                        player.sendActionBar(warning);
                    } else if (info.carrierUuid() != null) {
                        // Intel is being carried - show direction to carrier
                        Player carrier = Bukkit.getPlayer(info.carrierUuid());
                        if (carrier != null && carrier.isOnline()) {
                            String carrierName = carrier.getName();
                            String directionText = getDirectionIndicator(player, carrier.getLocation()) + " " +
                                    (int) player.getLocation().distance(carrier.getLocation()) + "m";

                            Component warning = Component.text("⚡ INTEL STOLEN! ")
                                    .color(NamedTextColor.RED)
                                    .decorate(TextDecoration.BOLD)
                                    .append(Component.text("Carrier: " + carrierName + " ")
                                            .color(NamedTextColor.YELLOW)
                                            .decoration(TextDecoration.BOLD, false))
                                    .append(Component.text(directionText)
                                            .color(NamedTextColor.GRAY));
                            player.sendActionBar(warning);
                        }
                    }
                }
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
     * Clears all defender alert boss bars for a player (used when no real threat exists).
     */
    private void clearDefenderBars(Player player, Map<Integer, BossBar> bars) {
        Iterator<Map.Entry<Integer, BossBar>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, BossBar> entry = iterator.next();
            player.hideBossBar(entry.getValue());
            iterator.remove();
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
            case RAID_CAPTURE_INTEL -> {
                if (objective.progress() >= 0.5) {
                    yield "⚡ INTEL STOLEN! Intercept the carrier!";
                } else if (objective.progress() > 0 && objective.progress() < 0.5) {
                    // Intel is dropped - try to get timeout info
                    Optional<ObjectiveService.IntelCarrierInfo> infoOpt = objectiveService.getIntelCarrierInfo(objective.regionId());
                    if (infoOpt.isPresent() && infoOpt.get().isDropped()) {
                        int seconds = infoOpt.get().droppedSecondsRemaining();
                        yield "⚡ Intel dropped! Recover in " + seconds + "s!";
                    }
                    yield "⚡ Intel dropped! Recover it!";
                } else {
                    yield "⚠ Intel Under Threat!";
                }
            }
            default -> "⚠ Region Under Attack!";
        };


        return Component.text(warningText)
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD);
    }

    /**
     * Checks if a region is valid for the team to capture/influence.
     * A region is valid if:
     * - It's adjacent to territory the team owns, OR
     * - It's a contested region where the team is the original owner (defending)
     *
     * This prevents showing objectives in regions that can't actually be influenced.
     */
    private boolean isRegionValidForCapture(String regionId, String playerTeam, RegionStatus status) {
        // If team already owns this region fully, they can't "capture" it (they're defending)
        if (status.isOwnedBy(playerTeam)) {
            return false;
        }

        // Check if region is adjacent to team's territory
        if (regionService.isAdjacentToTeam(regionId, playerTeam)) {
            return true;
        }

        // Special case: contested region where this team is the original owner
        // They can still complete objectives even if not adjacent (they're defending their turf)
        if (status.state() == RegionState.CONTESTED && playerTeam.equalsIgnoreCase(status.ownerTeam())) {
            return true;
        }

        // Not adjacent, not a valid capture target
        return false;
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
        String progressText;

        // For resource depot, use actual tracked counts instead of estimates
        if (objective.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT) {
            Optional<int[]> countsOpt = objectiveService.getResourceDepotCounts(objective.regionId());
            if (countsOpt.isPresent()) {
                int[] counts = countsOpt.get();
                // counts = [qualifyingContainers, totalItems, requiredContainers, minItemsPerContainer]
                progressText = counts[0] + "/" + counts[2] + " containers stocked (" + counts[3] + "+ items each)";
            } else {
                progressText = objective.getProgressDescription();
            }
        } else if (objective.type() == ObjectiveType.SETTLEMENT_ESTABLISH_OUTPOST
                || objective.type() == ObjectiveType.SETTLEMENT_WATCHTOWER
                || objective.type() == ObjectiveType.SETTLEMENT_GARRISON_QUARTERS) {
            // For building objectives, show user-friendly progress with checklist
            Optional<BuildingDetectionResult> detectionOpt = objectiveService.getBuildingDetectionResult(objective.id());
            if (detectionOpt.isPresent()) {
                BuildingDetectionResult detection = detectionOpt.get();
                // getFriendlyProgress handles both valid and in-progress states,
                // including variant names and variant upgrade hints
                progressText = detection.getFriendlyProgress();
            } else {
                progressText = "Build near the objective marker";
            }
        } else {
            progressText = objective.getProgressDescription();
        }

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

        // Update stored objective (for reference only, we now update every tick)
        playerActionBarObjective.put(player.getUniqueId(), nearest.id());

        // Build action bar message with compass
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
            // Location-based objective - show compass and distance
            String compass = getCompassDisplay(player, nearestLoc);
            int dist = (int) nearestDist;

            message = Component.text(symbol + " ")
                    .color(nearest.type().isRaid() ? NamedTextColor.RED : NamedTextColor.GREEN)
                    .append(Component.text(nearest.type().getDisplayName())
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(" [" + nearest.getInfluenceReward() + " IP] ")
                            .color(NamedTextColor.GOLD))
                    .append(Component.text(compass + " ")
                            .color(NamedTextColor.AQUA))
                    .append(Component.text(dist + "m")
                            .color(NamedTextColor.WHITE));
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
     * Gets a visual compass display showing the direction to the target.
     * Shows a compass bar with the direction highlighted: [←·↑·→] or similar.
     */
    private String getCompassDisplay(Player player, Location targetLoc) {
        if (targetLoc == null) return "[ ◇ ]";

        Location playerLoc = player.getLocation();
        double dx = targetLoc.getX() - playerLoc.getX();
        double dz = targetLoc.getZ() - playerLoc.getZ();

        // Get player's facing direction
        float yaw = playerLoc.getYaw();

        // Calculate angle to objective relative to player facing
        double angleToObj = Math.toDegrees(Math.atan2(-dx, dz));
        double relativeAngle = angleToObj - yaw;

        // Normalize to -180 to 180
        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        // Build compass display based on relative angle
        // Using a 5-slot compass: [← ↖ ↑ ↗ →] or [↙ ← ↖ ↑ ↗] etc.

        // Simple version: show the primary direction with emphasis
        String arrow = getDirectionArrow(relativeAngle);

        // Build a visual compass bar
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
            // Straight ahead
            return "[ · · ▲ · · ]";
        } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
            // Front-right
            return "[ · · ↑ ◆ · ]";
        } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
            // Right
            return "[ · · · · ▶ ]";
        } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
            // Back-right
            return "[ · · ↓ ◆ · ]";
        } else if (relativeAngle >= 157.5 || relativeAngle < -157.5) {
            // Behind
            return "[ · · ▼ · · ]";
        } else if (relativeAngle >= -157.5 && relativeAngle < -112.5) {
            // Back-left
            return "[ · ◆ ↓ · · ]";
        } else if (relativeAngle >= -112.5 && relativeAngle < -67.5) {
            // Left
            return "[ ◀ · · · · ]";
        } else if (relativeAngle >= -67.5 && relativeAngle < -22.5) {
            // Front-left
            return "[ · ◆ ↑ · · ]";
        }

        return "[ ◇ ]";
    }

    /**
     * Gets a simple arrow for the given angle.
     */
    private String getDirectionArrow(double relativeAngle) {
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) return "▲";
        if (relativeAngle >= 22.5 && relativeAngle < 67.5) return "↗";
        if (relativeAngle >= 67.5 && relativeAngle < 112.5) return "▶";
        if (relativeAngle >= 112.5 && relativeAngle < 157.5) return "↘";
        if (relativeAngle >= 157.5 || relativeAngle < -157.5) return "▼";
        if (relativeAngle >= -157.5 && relativeAngle < -112.5) return "↙";
        if (relativeAngle >= -112.5 && relativeAngle < -67.5) return "◀";
        if (relativeAngle >= -67.5 && relativeAngle < -22.5) return "↖";
        return "◇";
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

                // Special handling for Capture Intel - show particles at dropped location if dropped
                if (objective.type() == ObjectiveType.RAID_CAPTURE_INTEL) {
                    spawnIntelParticles(gameWorld, status.regionId(), objective);
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
     * Spawns particle markers for Capture Intel objectives.
     * Shows particles at the intel's current location (original, dropped, or carrier).
     */
    private void spawnIntelParticles(World world, String regionId, RegionObjective objective) {
        Optional<ObjectiveService.IntelCarrierInfo> infoOpt = objectiveService.getIntelCarrierInfo(regionId);

        Location loc;
        Color particleColor;
        String particleLabel;

        if (infoOpt.isPresent()) {
            ObjectiveService.IntelCarrierInfo info = infoOpt.get();

            if (info.isDropped()) {
                // Intel is dropped - show particles at dropped location
                loc = new Location(world, info.droppedX() + 0.5, info.droppedY() + 1, info.droppedZ() + 0.5);
                particleColor = Color.ORANGE; // Orange for dropped intel
                particleLabel = "DROPPED";
            } else if (info.carrierUuid() != null) {
                // Intel is being carried - show particles following carrier
                Player carrier = Bukkit.getPlayer(info.carrierUuid());
                if (carrier != null && carrier.isOnline()) {
                    loc = carrier.getLocation().clone().add(0, 2.5, 0);
                    particleColor = Color.PURPLE; // Purple for carried intel
                    particleLabel = "CARRIER";
                } else {
                    return; // Carrier not online, skip particles
                }
            } else {
                // Fallback to objective location
                if (!objective.hasLocation()) return;
                loc = objective.getLocation(world);
                particleColor = Color.YELLOW;
                particleLabel = "INTEL";
            }
        } else {
            // No carrier info - show at original objective location
            if (!objective.hasLocation()) return;
            loc = objective.getLocation(world);
            particleColor = Color.YELLOW;
            particleLabel = "INTEL";
        }

        if (loc == null) return;

        // Only spawn particles if players are nearby
        boolean hasNearbyPlayer = false;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(loc) < 100) {
                hasNearbyPlayer = true;
                break;
            }
        }

        if (!hasNearbyPlayer) return;

        // Spawn beacon-like particles
        Particle.DustOptions dust = new Particle.DustOptions(particleColor, 1.5f);

        // Vertical beam (shorter for carried intel)
        int beamHeight = infoOpt.isPresent() && infoOpt.get().carrierUuid() != null ? 5 : 20;
        for (int y = 0; y < beamHeight; y += 2) {
            Location particleLoc = loc.clone().add(0, y, 0);
            world.spawnParticle(Particle.DUST, particleLoc, 3, 0.2, 0.2, 0.2, 0, dust);
        }

        // Circle at base
        for (int i = 0; i < 8; i++) {
            double angle = (2 * Math.PI / 8) * i;
            double offsetX = Math.cos(angle) * 1.5;
            double offsetZ = Math.sin(angle) * 1.5;
            Location particleLoc = loc.clone().add(offsetX, 0.5, offsetZ);
            world.spawnParticle(Particle.DUST, particleLoc, 2, 0, 0, 0, 0, dust);
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

                RegionStatus status = statusOpt.get();
                ObjectiveCategory relevantCategory = getRelevantCategory(status, team);

                // For RAID objectives, also notify defenders about threats to their region
                boolean isDefender = status.ownerTeam() != null && status.ownerTeam().equalsIgnoreCase(team);
                boolean isAttacker = relevantCategory == objective.type().getCategory();

                if (isAttacker) {
                    // Show attacker notification
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
                // Defenders are NOT alerted on objective spawn — they are alerted
                // when an enemy actually enters the region (see checkEnemyRegionEntry)
            }
        }
    }

    /**
     * Gets a defender-friendly threat description for a raid objective.
     */
    private String getDefenderThreatDescription(ObjectiveType type) {
        return switch (type) {
            case RAID_CAPTURE_INTEL -> "Enemy targeting your intel! Defend the intel item!";
            case RAID_DESTROY_CACHE -> "Enemy targeting your supply cache!";
            case RAID_ASSASSINATE_COMMANDER -> "Enemy targeting your commander!";
            case RAID_SABOTAGE_DEFENSES -> "Enemy saboteurs detected!";
            case RAID_PLANT_EXPLOSIVE -> "Explosive threat detected!";
            case RAID_HOLD_GROUND -> "Enemy attempting to hold your territory!";
            default -> "Enemy activity detected!";
        };
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
        playerLastRegion.remove(player.getUniqueId());

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

