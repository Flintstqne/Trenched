package org.flintstqne.entrenched.ObjectiveLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RoadLogic.DeathListener;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages gameplay benefits for registered buildings.
 *
 * Benefits include:
 * - Outpost variant buffs when leaving
 * - Watchtower enemy detection and alerts
 * - Garrison quick-travel spawn system (future)
 * - Building particle effects
 */
public class BuildingBenefitManager implements Listener {

    private final JavaPlugin plugin;
    private final ObjectiveService objectiveService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final RoundService roundService;
    private final ConfigManager config;
    private RegionRenderer regionRenderer;

    // Optional reference to DeathListener - used to suppress exit buffs on death
    private DeathListener deathListener;
    // Tasks
    private BukkitTask benefitTickTask;
    private BukkitTask particleTask;

    // Track players inside buildings - playerId -> buildingId
    private final Map<UUID, Integer> playersInBuildings = new ConcurrentHashMap<>();

    // Track watchtower occupants - buildingId -> set of player UUIDs on platform
    private final Map<Integer, Set<UUID>> watchtowerOccupants = new ConcurrentHashMap<>();

    // Track last enemy alert time per region to prevent spam - regionId -> timestamp
    private final Map<String, Long> lastEnemyAlert = new ConcurrentHashMap<>();
    private static final long ENEMY_ALERT_COOLDOWN_MS = 30000; // 30 seconds

    // Track players with active outpost buffs - playerId -> (variantName -> expiry timestamp)
    // Keyed per-variant so different outpost types each have their own independent cooldown.
    private final Map<UUID, Map<String, Long>> outpostBuffExpiry = new ConcurrentHashMap<>();

    // Shared building cache — refreshed at most once every 5 s to keep both
    // tickBenefits (1 Hz) and tickParticles (2 Hz) off the DB hot path.
    private volatile List<RegisteredBuilding> cachedBuildings = Collections.emptyList();
    private long lastBuildingCacheRefreshMs = 0L;
    private static final long BUILDING_CACHE_REFRESH_INTERVAL_MS = 5_000L;

    // Track enemies currently glowing (spotted via spyglass) — UUID → expiry timestamp ms
    private final Map<UUID, Long> glowingEnemies = new ConcurrentHashMap<>();
    private static final int GLOWING_DURATION_TICKS = 20 * 20;   // 20 seconds
    private static final long GLOWING_DURATION_MS   = 20_000L;

    // Per-spotter cooldown to prevent spam — UUID → cooldown-expiry timestamp ms
    private final Map<UUID, Long> spyglassCooldowns = new ConcurrentHashMap<>();
    private static final long SPYGLASS_COOLDOWN_MS = 5_000L; // 5 seconds between spots

    // Counter for ambient sound (plays every 6th particle tick = ~3 seconds)
    private int ambientSoundCounter = 0;
    private static final int AMBIENT_SOUND_INTERVAL = 6;

    public BuildingBenefitManager(JavaPlugin plugin, ObjectiveService objectiveService,
                                  RegionService regionService, TeamService teamService,
                                  RoundService roundService, ConfigManager config) {
        this.plugin = plugin;
        this.objectiveService = objectiveService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.roundService = roundService;
        this.config = config;
    }

    /**
     * Sets the region renderer for region name lookups.
     */
    public void setRegionRenderer(RegionRenderer regionRenderer) {
        this.regionRenderer = regionRenderer;
    }

    /**
     * Called when a player dies. Removes them from building tracking immediately
     * so the next benefit tick does not fire an exit callback (and award a buff).
     */
    public void notifyPlayerDied(UUID playerId) {
        playersInBuildings.remove(playerId);
        // Also evict from watchtower occupant sets
        for (Set<UUID> occupants : watchtowerOccupants.values()) {
            occupants.remove(playerId);
        }
    }

    /**
     * Gets the display name for a region.
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId;
    }

    /**
     * Starts the benefit management tasks.
     */
    public void start() {
        // Main benefit tick - runs every second (20 ticks)
        benefitTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBenefits, 20L, 20L);

        // Particle effects - runs every 10 ticks (0.5 seconds)
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, 10L, 10L);

        plugin.getLogger().info("[Buildings] Benefit manager started");
    }

    /**
     * Stops the benefit management tasks.
     */
    public void stop() {
        if (benefitTickTask != null) {
            benefitTickTask.cancel();
        }
        if (particleTask != null) {
            particleTask.cancel();
        }

        // Remove GLOWING from all enemies that were spotted this session
        for (UUID enemyId : glowingEnemies.keySet()) {
            Player enemy = Bukkit.getPlayer(enemyId);
            if (enemy != null && enemy.isOnline()) {
                enemy.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        glowingEnemies.clear();
        spyglassCooldowns.clear();

        plugin.getLogger().info("[Buildings] Benefit manager stopped");
    }

    /**
     * Clears all tracked data (called on new round).
     */
    public void clearTrackedData() {
        playersInBuildings.clear();
        watchtowerOccupants.clear();
        lastEnemyAlert.clear();
        outpostBuffExpiry.clear();
        cachedBuildings = Collections.emptyList();
        lastBuildingCacheRefreshMs = 0L;

        // Remove GLOWING from all spotted enemies when a new round starts
        for (UUID enemyId : glowingEnemies.keySet()) {
            Player enemy = Bukkit.getPlayer(enemyId);
            if (enemy != null && enemy.isOnline()) {
                enemy.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        glowingEnemies.clear();
        spyglassCooldowns.clear();
    }

    // ==================== MAIN TICK ====================

    /**
     * Main benefit tick - runs every second.
     */
    private void tickBenefits() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return;
        }

        // Get all active buildings (cached — see getActiveBuildings())
        List<RegisteredBuilding> buildings = getActiveBuildings();

        // Always prune tracked players even when the buildings list is empty.
        // If every building was destroyed while players were inside, we must still
        // evict them from playersInBuildings — otherwise they stay there forever.
        pruneTrackedPlayers(buildings, gameWorld);

        if (buildings.isEmpty()) {
            return;
        }

        // Process each online player
        for (Player player : gameWorld.getPlayers()) {
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (teamOpt.isEmpty()) {
                continue;
            }

            String playerTeam = teamOpt.get();
            int playerX = player.getLocation().getBlockX();
            int playerY = player.getLocation().getBlockY();
            int playerZ = player.getLocation().getBlockZ();

            // Check if player is inside any building
            RegisteredBuilding insideBuilding = null;
            for (RegisteredBuilding building : buildings) {
                if (isInsideBuilding(playerX, playerY, playerZ, building)) {
                    // Check if this is a friendly building
                    if (building.team().equalsIgnoreCase(playerTeam)) {
                        insideBuilding = building;
                        break;
                    }
                }
            }

            // Handle building entry/exit
            Integer previousBuildingId = playersInBuildings.get(player.getUniqueId());
            if (insideBuilding != null) {
                if (previousBuildingId == null || previousBuildingId != insideBuilding.objectiveId()) {
                    // Entered a new building
                    plugin.getLogger().info("[Buildings] " + player.getName() + " ENTERED " +
                            insideBuilding.type().getDisplayName() + " (obj " + insideBuilding.objectiveId() +
                            ") variant=" + insideBuilding.variant() +
                            " bounds=[" + insideBuilding.minX() + "," + insideBuilding.minY() + "," + insideBuilding.minZ() +
                            " to " + insideBuilding.maxX() + "," + insideBuilding.maxY() + "," + insideBuilding.maxZ() + "]");
                    onPlayerEnterBuilding(player, insideBuilding);
                }
                playersInBuildings.put(player.getUniqueId(), insideBuilding.objectiveId());
            } else if (previousBuildingId != null) {
                // Left a building
                RegisteredBuilding leftBuilding = findBuildingById(buildings, previousBuildingId);
                if (leftBuilding != null) {
                    plugin.getLogger().info("[Buildings] " + player.getName() + " EXITED " +
                            leftBuilding.type().getDisplayName() + " (obj " + leftBuilding.objectiveId() +
                            ") variant=" + leftBuilding.variant());
                    onPlayerExitBuilding(player, leftBuilding);
                }
                playersInBuildings.remove(player.getUniqueId());
            }

            // Process watchtower detection for this player
            for (RegisteredBuilding building : buildings) {
                if (building.type() == BuildingType.WATCHTOWER && building.team().equalsIgnoreCase(playerTeam)) {
                    tickWatchtowerDetection(player, playerTeam, building, gameWorld);
                }
            }
        }

        // Refresh team-only recon markers from active watchtower detections.
        cleanupGlowingEnemies();

        // Clean up expired outpost buffs
        tickOutpostBuffExpiry();
    }

    // ==================== BUILDING ENTRY/EXIT ====================

    /**
     * Called when a player enters a building.
     */
    private void onPlayerEnterBuilding(Player player, RegisteredBuilding building) {
        switch (building.type()) {
            case OUTPOST -> {
                // No immediate effect - buff applied on exit
            }
            case WATCHTOWER -> {
                // Track as occupant if on platform
                if (isOnWatchtowerPlatform(player, building)) {
                    watchtowerOccupants.computeIfAbsent(building.objectiveId(), k -> ConcurrentHashMap.newKeySet())
                            .add(player.getUniqueId());
                }
            }
            case GARRISON -> {
                // Future: Apply garrison variant buffs
            }
        }
    }

    /**
     * Called when a player exits a building.
     */
    private void onPlayerExitBuilding(Player player, RegisteredBuilding building) {
        switch (building.type()) {
            case OUTPOST -> applyOutpostVariantBuff(player, building);
            case WATCHTOWER -> {
                Set<UUID> occupants = watchtowerOccupants.get(building.objectiveId());
                if (occupants != null) {
                    occupants.remove(player.getUniqueId());
                }
            }
            case GARRISON -> {
                // Future: Apply garrison exit effects
            }
        }
    }

    // ==================== OUTPOST BUFFS ====================

    /**
     * Applies the outpost variant buff when a player leaves.
     */
    private void applyOutpostVariantBuff(Player player, RegisteredBuilding building) {
        String variant = building.variant();
        plugin.getLogger().info("[Buildings] applyOutpostVariantBuff for " + player.getName() +
                ": variant='" + variant + "', type=" + building.type());

        if (variant == null || variant.equals("Standard") || variant.isEmpty()) {
            plugin.getLogger().info("[Buildings] No buff - variant is Standard/null/empty");
            return; // No buff for standard outposts
        }

        // Prevent indefinite refresh by rapidly stepping in/out.
        // Only grant a new buff once the previous one for THIS variant has expired.
        // Keyed per-variant so a Forest Outpost buff doesn't block a Mountain Outpost buff.
        Map<String, Long> playerExpiries = outpostBuffExpiry.get(player.getUniqueId());
        if (playerExpiries != null) {
            Long existingExpiry = playerExpiries.get(variant);
            if (existingExpiry != null && System.currentTimeMillis() < existingExpiry) {
                plugin.getLogger().info("[Buildings] Buff skipped for " + player.getName() +
                        " - " + variant + " buff still active for " +
                        ((existingExpiry - System.currentTimeMillis()) / 1000) + "s");
                return;
            }
        }

        PotionEffectType effectType = null;
        int durationTicks = 5 * 60 * 20; // 5 minutes

        // Use startsWith to handle variants that may still have "(needs ...)" suffix
        if (variant.startsWith("Mining Outpost")) {
            effectType = PotionEffectType.LUCK; // Fortune equivalent
        } else if (variant.startsWith("Fishing Outpost")) {
            effectType = PotionEffectType.LUCK; // Luck of the Sea equivalent
        } else if (variant.startsWith("Farm Outpost")) {
            effectType = PotionEffectType.SATURATION;
        } else if (variant.startsWith("Forest Outpost")) {
            effectType = PotionEffectType.HASTE;
        } else if (variant.startsWith("Mountain Outpost")) {
            effectType = PotionEffectType.SLOW_FALLING;
        } else if (variant.startsWith("Desert Outpost")) {
            effectType = PotionEffectType.FIRE_RESISTANCE;
        }

        if (effectType != null) {
            plugin.getLogger().info("[Buildings] Applying buff " + effectType.getKey() + " to " + player.getName());
            player.addPotionEffect(new PotionEffect(effectType, durationTicks, 0, true, true, true));
            player.sendMessage(Component.text("✦ ", NamedTextColor.GREEN)
                    .append(Component.text(variant + " buff applied! ", NamedTextColor.GREEN))
                    .append(Component.text("(5 minutes)", NamedTextColor.GRAY)));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);

            outpostBuffExpiry
                    .computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                    .put(variant, System.currentTimeMillis() + (5 * 60 * 1000));
        } else {
            plugin.getLogger().warning("[Buildings] No effect matched for variant '" + variant + "'");
        }
    }

    /**
     * Cleans up expired outpost buff tracking.
     */
    private void tickOutpostBuffExpiry() {
        long now = System.currentTimeMillis();
        // Remove individual variant entries that have expired, then remove the player
        // entry entirely if all variant entries are gone.
        outpostBuffExpiry.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(v -> v.getValue() < now);
            return entry.getValue().isEmpty();
        });
    }

    // ==================== WATCHTOWER DETECTION ====================

    /**
     * Processes watchtower passive range-awareness for a player on the platform.
     * Enemies entering detection range still trigger a team alert, but actual spotting
     * (Glowing) requires the player to actively aim through a spyglass — see onSpyglassUse().
     */
    private void tickWatchtowerDetection(Player occupant, String occupantTeam, RegisteredBuilding watchtower, World world) {
        // Check if player is on the watchtower platform
        if (!isOnWatchtowerPlatform(occupant, watchtower)) {
            // Remove from occupants if they left the platform
            Set<UUID> occupants = watchtowerOccupants.get(watchtower.objectiveId());
            if (occupants != null) {
                occupants.remove(occupant.getUniqueId());
            }
            return;
        }

        // Add to occupants
        watchtowerOccupants.computeIfAbsent(watchtower.objectiveId(), k -> ConcurrentHashMap.newKeySet())
                .add(occupant.getUniqueId());

        // Calculate detection range based on tower height
        int towerHeight = watchtower.maxY() - watchtower.minY();
        int detectionRange = calculateDetectionRange(towerHeight);
        double detectionRangeSquared = detectionRange * detectionRange;

        // Find enemies within range — only used to trigger the passive alert message
        Location towerTop = new Location(world, watchtower.anchorX(), watchtower.maxY(), watchtower.anchorZ());
        String regionId = watchtower.regionId();

        boolean enemyNearby = false;
        for (Player target : world.getPlayers()) {
            if (target.getUniqueId().equals(occupant.getUniqueId())) continue;

            Optional<String> targetTeamOpt = teamService.getPlayerTeam(target.getUniqueId());
            if (targetTeamOpt.isEmpty()) continue;

            String targetTeam = targetTeamOpt.get();
            if (targetTeam.equalsIgnoreCase(occupantTeam)) continue; // Same team

            if (target.getLocation().distanceSquared(towerTop) <= detectionRangeSquared) {
                enemyNearby = true;
                break; // One is enough to trigger the alert
            }
        }

        // Send passive range alert if enemies are nearby (throttled by cooldown)
        if (enemyNearby) {
            sendEnemyAlert(occupantTeam, regionId, watchtower);
        }
    }

    /**
     * Calculates watchtower detection range based on height.
     */
    private int calculateDetectionRange(int height) {
        if (height >= 30) {
            return 160;
        }
        if (height >= 25) {
            return 128;
        }
        if (height >= 20) {
            return 96;
        }
        return 64; // Base range for 15-19 blocks
    }

    // ==================== SPYGLASS SPOTTING — ACTIVE DETECTION ====================

    /**
     * Fires when a player right-clicks while holding a spyglass.
     *
     * Requirements (no exception):
     *   1. Player must be standing on a friendly watchtower platform.
     *   2. Player must be holding a spyglass.
     *   3. The ray-cast from the player's eye must reach an enemy without being
     *      blocked by a solid block — i.e., genuine line-of-sight is required.
     *
     * On success the enemy receives the GLOWING potion effect for {@value GLOWING_DURATION_TICKS}
     * ticks (20 s), making their silhouette visible through terrain to everyone.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onSpyglassUse(PlayerInteractEvent event) {
        // Only main-hand right-clicks — prevents double-fire from off-hand slot
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.SPYGLASS) return;

        Player spotter = event.getPlayer();

        // ── Requirement 1: must be on a friendly watchtower platform ──────────────
        RegisteredBuilding watchtower = findWatchtowerPlatformForPlayer(spotter);
        if (watchtower == null) return; // silently ignore — spyglass works normally elsewhere

        plugin.getLogger().info("[Watchtower] Spyglass used by " + spotter.getName() +
                " on watchtower (obj " + watchtower.objectiveId() + ")");

        // ── Cooldown guard ────────────────────────────────────────────────────────
        Long cooldownExpiry = spyglassCooldowns.get(spotter.getUniqueId());
        if (cooldownExpiry != null && System.currentTimeMillis() < cooldownExpiry) {
            long remaining = (cooldownExpiry - System.currentTimeMillis() + 999) / 1000L;
            spotter.sendMessage(Component.text("⏳ Spyglass recharging — " + remaining + "s remaining.")
                    .color(NamedTextColor.YELLOW));
            return;
        }

        // ── Team check ────────────────────────────────────────────────────────────
        Optional<String> teamOpt = teamService.getPlayerTeam(spotter.getUniqueId());
        if (teamOpt.isEmpty()) return;
        String spotterTeam = teamOpt.get();

        // ── Requirement 3: ray-cast — solid blocks obstruct line-of-sight ────────
        int towerHeight = watchtower.maxY() - watchtower.minY();
        int detectionRange = calculateDetectionRange(towerHeight);

        // Step 1: Entity-only raytrace to find who the player is aiming at.
        // This ignores blocks entirely so the watchtower's own fences/walls/slabs
        // cannot prevent the spotter from acquiring a target.
        RayTraceResult entityResult = spotter.getWorld().rayTraceEntities(
                spotter.getEyeLocation(),
                spotter.getEyeLocation().getDirection(),
                detectionRange,
                0.3,    // small hitbox expansion for comfortable aiming
                entity -> entity instanceof Player p && !p.getUniqueId().equals(spotter.getUniqueId())
        );

        if (entityResult == null || !(entityResult.getHitEntity() instanceof Player enemy)) {
            spotter.sendMessage(Component.text("No target in sight.")
                    .color(NamedTextColor.GRAY));
            return;
        }

        // Step 2: Verify genuine line-of-sight via block raytrace, but skip any
        // block that falls inside the watchtower's own bounding box.  Without this,
        // the tower's platform fences/walls block the ray before it leaves the
        // structure, making it impossible to spot anyone.
        if (!hasLineOfSightPastWatchtower(spotter, enemy, watchtower)) {
            spotter.sendMessage(Component.text("No target in sight — view obstructed.")
                    .color(NamedTextColor.GRAY));
            return;
        }

        // ── Verify the hit entity is an enemy ────────────────────────────────────
        Optional<String> enemyTeamOpt = teamService.getPlayerTeam(enemy.getUniqueId());
        if (enemyTeamOpt.isEmpty() || enemyTeamOpt.get().equalsIgnoreCase(spotterTeam)) {
            spotter.sendMessage(Component.text("No enemy in crosshair.")
                    .color(NamedTextColor.GRAY));
            return;
        }

        // ── Apply Glowing and notify ──────────────────────────────────────────────
        applyGlowingToEnemy(spotter, enemy, spotterTeam, watchtower);

        // Set per-spotter cooldown
        spyglassCooldowns.put(spotter.getUniqueId(), System.currentTimeMillis() + SPYGLASS_COOLDOWN_MS);
    }

    /**
     * Applies the GLOWING potion effect to the spotted enemy and broadcasts alerts.
     *
     * The GLOWING effect draws the enemy's entity outline through solid terrain for all
     * players — exactly the through-wall visibility the design specifies.
     */
    private void applyGlowingToEnemy(Player spotter, Player enemy, String spotterTeam,
                                     RegisteredBuilding watchtower) {
        // Apply (or refresh) GLOWING — ambient=false, particles=false keeps it clean
        enemy.addPotionEffect(new PotionEffect(
                PotionEffectType.GLOWING, GLOWING_DURATION_TICKS, 0, false, false, false));

        // Track so we can forcibly remove it on round-end / plugin stop
        glowingEnemies.put(enemy.getUniqueId(), System.currentTimeMillis() + GLOWING_DURATION_MS);

        String regionName = getRegionDisplayName(watchtower.regionId());
        int durationSeconds = GLOWING_DURATION_TICKS / 20;

        // ── Notify the spotter ────────────────────────────────────────────────────
        spotter.sendMessage(Component.text("🔍 ", NamedTextColor.GOLD)
                .append(Component.text("Spotted ", NamedTextColor.WHITE))
                .append(Component.text(enemy.getName(), NamedTextColor.RED))
                .append(Component.text("! Glowing for " + durationSeconds + "s.",
                        NamedTextColor.YELLOW)));
        spotter.playSound(spotter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f);

        // ── Notify teammates in the same region only ─────────────────────────────
        String watchtowerRegion = watchtower.regionId();
        World gameWorld = spotter.getWorld();
        for (Player teammate : gameWorld.getPlayers()) {
            if (teammate.getUniqueId().equals(spotter.getUniqueId())) continue;
            Optional<String> tmTeam = teamService.getPlayerTeam(teammate.getUniqueId());
            if (tmTeam.isEmpty() || !tmTeam.get().equalsIgnoreCase(spotterTeam)) continue;
            // Skip players outside the watchtower's region — alert isn't actionable for them
            String tmRegion = regionService.getRegionIdForLocation(
                    teammate.getLocation().getBlockX(), teammate.getLocation().getBlockZ());
            if (!watchtowerRegion.equals(tmRegion)) continue;

            teammate.sendMessage(Component.text("[Watchtower] ", NamedTextColor.YELLOW)
                    .append(Component.text(spotter.getName(), NamedTextColor.GOLD))
                    .append(Component.text(" spotted ", NamedTextColor.WHITE))
                    .append(Component.text(enemy.getName(), NamedTextColor.RED))
                    .append(Component.text(" in ", NamedTextColor.WHITE))
                    .append(Component.text(regionName, NamedTextColor.GOLD))
                    .append(Component.text("! [Glowing " + durationSeconds + "s]",
                            NamedTextColor.YELLOW)));
            teammate.playSound(teammate.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
        }

        // ── Notify the spotted enemy so they can react ────────────────────────────
        enemy.sendMessage(Component.text("👁 ", NamedTextColor.RED)
                .append(Component.text(
                        "You've been spotted by a watchtower! Your position is visible for "
                                + durationSeconds + "s.",
                        NamedTextColor.RED)));
        enemy.playSound(enemy.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 0.5f);
    }

    /**
     * Returns the active friendly watchtower whose platform the player is standing on,
     * or {@code null} if the player is not on any watchtower platform.
     */
    private RegisteredBuilding findWatchtowerPlatformForPlayer(Player player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return null;
        String playerTeam = teamOpt.get();

        for (RegisteredBuilding building : getActiveBuildings()) {
            if (building.type() != BuildingType.WATCHTOWER) continue;
            if (!building.team().equalsIgnoreCase(playerTeam)) continue;
            if (isOnWatchtowerPlatform(player, building)) return building;
        }
        return null;
    }

    /**
     * Checks line-of-sight from spotter to enemy, ignoring solid blocks that are
     * inside the watchtower's own bounding box.  Without this, the tower's fences,
     * walls, and slab edges block the ray before it ever leaves the structure.
     * <p>
     * Algorithm: iteratively ray-trace blocks towards the target.  Each time the
     * first hit block is inside the tower, advance past it and trace again.  If a
     * non-tower block is hit before the enemy, LOS is genuinely blocked.
     */
    private boolean hasLineOfSightPastWatchtower(Player spotter, Player enemy,
                                                  RegisteredBuilding watchtower) {
        Location start = spotter.getEyeLocation();
        // Aim at the enemy's eye rather than their feet for a more natural check
        Location target = enemy.getEyeLocation();
        Vector toTarget = target.toVector().subtract(start.toVector());
        double totalDistance = toTarget.length();
        if (totalDistance < 0.1) return true; // practically on top of each other

        Vector direction = toTarget.clone().normalize();
        Location current = start.clone();
        double remaining = totalDistance;

        // Safety cap — a watchtower platform is at most a few blocks thick
        for (int i = 0; i < 10 && remaining > 0.5; i++) {
            RayTraceResult blockHit = spotter.getWorld().rayTraceBlocks(
                    current, direction, remaining,
                    FluidCollisionMode.NEVER,
                    true  // ignore passable (non-solid) blocks like glass panes, vegetation
            );

            if (blockHit == null) {
                return true; // No solid blocks between current position and target
            }

            Block hitBlock = blockHit.getHitBlock();
            if (hitBlock == null) {
                return true;
            }

            // If the blocking block is inside the watchtower's structure, skip past it
            int bx = hitBlock.getX();
            int by = hitBlock.getY();
            int bz = hitBlock.getZ();
            if (bx >= watchtower.minX() && bx <= watchtower.maxX() &&
                by >= watchtower.minY() && by <= watchtower.maxY() &&
                bz >= watchtower.minZ() && bz <= watchtower.maxZ()) {
                // Advance 0.15 blocks past the hit point so we don't re-hit the same face
                double hitDist = blockHit.getHitPosition().distance(current.toVector());
                double advance = hitDist + 0.15;
                current = current.clone().add(direction.clone().multiply(advance));
                remaining -= advance;
                continue;
            }

            // Hit a block that is NOT part of the watchtower — genuine obstruction
            return false;
        }

        // Exhausted iterations (very thick tower?) — give the spotter the benefit of the doubt
        return true;
    }

    /**
     * Removes expired entries from the glowing-enemy and spyglass-cooldown maps.
     * Called every second from {@link #tickBenefits()}.
     */
    private void cleanupGlowingEnemies() {
        long now = System.currentTimeMillis();
        glowingEnemies.entrySet().removeIf(entry -> entry.getValue() < now);
        spyglassCooldowns.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * Sends enemy detection alert to team.
     */
    private void sendEnemyAlert(String team, String regionId, RegisteredBuilding watchtower) {
        String alertKey = team + ":" + regionId;
        long now = System.currentTimeMillis();
        Long lastAlert = lastEnemyAlert.get(alertKey);

        if (lastAlert != null && (now - lastAlert) < ENEMY_ALERT_COOLDOWN_MS) {
            return; // On cooldown
        }

        lastEnemyAlert.put(alertKey, now);

        String regionName = getRegionDisplayName(regionId);

        // Alert team members in the game world only — not lobby/spectator players
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) return;

        for (Player player : gameWorld.getPlayers()) {
            Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeam.isEmpty() || !playerTeam.get().equalsIgnoreCase(team)) continue;
            // Only alert players who are actually in the same region
            String playerRegion = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ());
            if (!regionId.equals(playerRegion)) continue;
            player.sendMessage(Component.text("[Watchtower] ", NamedTextColor.YELLOW)
                    .append(Component.text("Enemy spotted in ", NamedTextColor.WHITE))
                    .append(Component.text(regionName, NamedTextColor.GOLD))
                    .append(Component.text("!", NamedTextColor.WHITE)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.0f);
        }
    }

    private void pruneTrackedPlayers(List<RegisteredBuilding> buildings, World gameWorld) {
        List<UUID> staleTrackedPlayers = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : playersInBuildings.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(gameWorld)) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            RegisteredBuilding building = findBuildingById(buildings, entry.getValue());
            if (building == null) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            Optional<String> playerTeam = teamService.getPlayerTeam(entry.getKey());
            if (playerTeam.isEmpty() || !playerTeam.get().equalsIgnoreCase(building.team())) {
                staleTrackedPlayers.add(entry.getKey());
                continue;
            }

            if (!isInsideBuilding(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ(),
                    building)) {
                // Fire exit callback BEFORE removing so buffs (like outpost variant effects) get applied
                plugin.getLogger().info("[Buildings] PRUNE EXIT: " + player.getName() + " left " +
                        building.type().getDisplayName() + " (obj " + building.objectiveId() +
                        ") at " + player.getLocation().getBlockX() + "," +
                        player.getLocation().getBlockY() + "," + player.getLocation().getBlockZ());
                onPlayerExitBuilding(player, building);
                staleTrackedPlayers.add(entry.getKey());
            }
        }
        for (UUID playerId : staleTrackedPlayers) {
            playersInBuildings.remove(playerId);
        }

        List<Integer> emptyWatchtowers = new ArrayList<>();
        for (Map.Entry<Integer, Set<UUID>> entry : watchtowerOccupants.entrySet()) {
            RegisteredBuilding building = findBuildingById(buildings, entry.getKey());
            if (building == null || building.type() != BuildingType.WATCHTOWER) {
                emptyWatchtowers.add(entry.getKey());
                continue;
            }

            pruneWatchtowerOccupants(entry.getValue(), building, gameWorld);
            if (entry.getValue().isEmpty()) {
                emptyWatchtowers.add(entry.getKey());
            }
        }
        for (Integer buildingId : emptyWatchtowers) {
            watchtowerOccupants.remove(buildingId);
        }
    }

    private void pruneWatchtowerOccupants(Set<UUID> occupants, RegisteredBuilding watchtower, World gameWorld) {
        List<UUID> staleOccupants = new ArrayList<>();
        for (UUID occupantId : occupants) {
            Player occupant = Bukkit.getPlayer(occupantId);
            if (occupant == null || !occupant.isOnline() || !occupant.getWorld().equals(gameWorld)) {
                staleOccupants.add(occupantId);
                continue;
            }

            Optional<String> occupantTeam = teamService.getPlayerTeam(occupantId);
            if (occupantTeam.isEmpty() || !occupantTeam.get().equalsIgnoreCase(watchtower.team())) {
                staleOccupants.add(occupantId);
                continue;
            }

            if (!isOnWatchtowerPlatform(occupant, watchtower)) {
                staleOccupants.add(occupantId);
            }
        }

        for (UUID occupantId : staleOccupants) {
            occupants.remove(occupantId);
        }
    }


    /**
     * Checks if a player is on the watchtower platform.
     */
    private boolean isOnWatchtowerPlatform(Player player, RegisteredBuilding watchtower) {
        int playerY = player.getLocation().getBlockY();
        int platformY = watchtower.maxY();

        // Player is on platform if they're at or near the top of the tower
        if (playerY < platformY - 2 || playerY > platformY + 3) {
            return false;
        }

        int playerX = player.getLocation().getBlockX();
        int playerZ = player.getLocation().getBlockZ();

        // Check if within horizontal bounds (with some tolerance)
        return playerX >= watchtower.minX() - 1 && playerX <= watchtower.maxX() + 1 &&
                playerZ >= watchtower.minZ() - 1 && playerZ <= watchtower.maxZ() + 1;
    }

    // ==================== PARTICLE EFFECTS ====================

    /**
     * Spawns particle effects for buildings.
     */
    private void tickParticles() {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return;
        }

        List<RegisteredBuilding> buildings = getActiveBuildings();
        if (buildings.isEmpty()) {
            return;
        }

        boolean playSound = (++ambientSoundCounter >= AMBIENT_SOUND_INTERVAL);
        if (playSound) ambientSoundCounter = 0;

        for (RegisteredBuilding building : buildings) {
            // Only show particles to team members within 32 blocks
            Location buildingCenter = new Location(gameWorld,
                    building.anchorX(), building.anchorY(), building.anchorZ());

            Collection<Player> nearbyPlayers = gameWorld.getNearbyPlayers(buildingCenter, 32);

            for (Player player : nearbyPlayers) {
                Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
                if (playerTeam.isEmpty() || !playerTeam.get().equalsIgnoreCase(building.team())) {
                    continue; // Only show to team members
                }

                spawnBuildingParticles(player, building, gameWorld);

                // Play subtle ambient sound every ~3 seconds for nearby players (within 16 blocks)
                if (playSound && player.getLocation().distanceSquared(buildingCenter) <= 16 * 16) {
                    Sound ambientSound = switch (building.type()) {
                        case OUTPOST -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
                        case WATCHTOWER -> Sound.BLOCK_AMETHYST_CLUSTER_STEP;
                        case GARRISON -> Sound.BLOCK_CAMPFIRE_CRACKLE;
                    };
                    player.playSound(buildingCenter, ambientSound, 0.15f, 1.2f);
                }
            }
        }
    }

    /**
     * Spawns particles for a specific building visible to a player.
     */
    private void spawnBuildingParticles(Player viewer, RegisteredBuilding building, World world) {
        Location particleLoc;
        Particle particleType;
        int count;

        switch (building.type()) {
            case OUTPOST -> {
                // White sparkles rising from center
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.anchorY() + 1.5,
                        building.anchorZ() + 0.5);
                particleType = Particle.END_ROD;
                count = 3;
            }
            case WATCHTOWER -> {
                // Blue beacon-like particles at platform level
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.maxY() + 0.5,
                        building.anchorZ() + 0.5);
                particleType = Particle.SOUL_FIRE_FLAME;
                count = 5;
            }
            case GARRISON -> {
                // Team-colored flames
                particleLoc = new Location(world,
                        building.anchorX() + 0.5,
                        building.anchorY() + 0.5,
                        building.anchorZ() + 0.5);
                particleType = building.team().equalsIgnoreCase("RED") ?
                        Particle.FLAME : Particle.SOUL_FIRE_FLAME;
                count = 4;
            }
            default -> {
                return;
            }
        }

        // Add some random offset
        double offsetX = (Math.random() - 0.5) * 0.5;
        double offsetZ = (Math.random() - 0.5) * 0.5;
        particleLoc.add(offsetX, Math.random() * 0.5, offsetZ);

        viewer.spawnParticle(particleType, particleLoc, count, 0.1, 0.2, 0.1, 0.01);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Checks if coordinates are inside a building's bounds.
     */
    private boolean isInsideBuilding(int x, int y, int z, RegisteredBuilding building) {
        // Add 2-block buffer around the structure bounds so players don't flicker
        // in/out when standing at doorways or right outside a wall
        int buffer = 2;
        return x >= (building.minX() - buffer) && x <= (building.maxX() + buffer) &&
                y >= (building.minY() - 1) && y <= (building.maxY() + 3) &&
                z >= (building.minZ() - buffer) && z <= (building.maxZ() + buffer);
    }

    /**
     * Gets all active registered buildings, using a 5-second TTL cache to avoid
     * hammering the database from tickBenefits (1 Hz) and tickParticles (2 Hz).
     */
    private List<RegisteredBuilding> getActiveBuildings() {
        long now = System.currentTimeMillis();
        if (now - lastBuildingCacheRefreshMs >= BUILDING_CACHE_REFRESH_INTERVAL_MS) {
            cachedBuildings = objectiveService.getAllActiveBuildings();
            lastBuildingCacheRefreshMs = now;
        }
        return cachedBuildings;
    }

    /**
     * Finds a building by its objective ID.
     */
    private RegisteredBuilding findBuildingById(List<RegisteredBuilding> buildings, int objectiveId) {
        for (RegisteredBuilding building : buildings) {
            if (building.objectiveId() == objectiveId) {
                return building;
            }
        }
        return null;
    }

    // ==================== PUBLIC API ====================

    /**
     * Gets the set of players currently occupying a watchtower.
     */
    public Set<UUID> getWatchtowerOccupants(int buildingId) {
        return watchtowerOccupants.getOrDefault(buildingId, Collections.emptySet());
    }

    /**
     * Checks if any friendly watchtower has this region under surveillance.
     */
    public boolean isRegionUnderSurveillance(String regionId, String team) {
        World gameWorld = roundService.getGameWorld().orElse(null);
        if (gameWorld == null) {
            return false;
        }

        for (RegisteredBuilding building : getActiveBuildings()) {
            if (building.type() == BuildingType.WATCHTOWER &&
                    building.team().equalsIgnoreCase(team) &&
                    building.regionId().equalsIgnoreCase(regionId)) {

                Set<UUID> occupants = watchtowerOccupants.get(building.objectiveId());
                if (occupants == null) {
                    continue;
                }

                pruneWatchtowerOccupants(occupants, building, gameWorld);
                if (!occupants.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
