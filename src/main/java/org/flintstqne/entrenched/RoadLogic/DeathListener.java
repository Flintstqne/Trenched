package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player death and respawn mechanics including the spectator-like respawn experience.
 */
public final class DeathListener implements Listener {

    private static final int BASE_RESPAWN_DELAY = 5; // Base 5 seconds before supply penalties

    private final JavaPlugin plugin;
    private final RoadService roadService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    // Track players who are pending respawn with delay
    private final Map<UUID, RespawnData> pendingRespawn = new ConcurrentHashMap<>();

    // Track who killed each player for death message
    private final Map<UUID, String> lastKiller = new ConcurrentHashMap<>();

    public DeathListener(JavaPlugin plugin, RoadService roadService,
                         TeamService teamService, ConfigManager configManager) {
        this.plugin = plugin;
        this.roadService = roadService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    // ==================== DEATH TRACKING ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Player killer = player.getKiller();

        if (killer != null) {
            lastKiller.put(player.getUniqueId(), killer.getName());
        } else {
            // Check for indirect kill (projectile, etc.)
            if (player.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
                if (damageEvent.getDamager() instanceof Player damager) {
                    lastKiller.put(player.getUniqueId(), damager.getName());
                } else {
                    lastKiller.put(player.getUniqueId(), formatEntityName(damageEvent.getDamager().getType().name()));
                }
            } else if (player.getLastDamageCause() != null) {
                // Environmental death
                lastKiller.put(player.getUniqueId(), formatDeathCause(player.getLastDamageCause().getCause()));
            } else {
                lastKiller.remove(player.getUniqueId());
            }
        }
    }

    /**
     * Formats entity type name for display (e.g., "ZOMBIE" -> "Zombie")
     */
    private String formatEntityName(String name) {
        if (name == null || name.isEmpty()) return "Unknown";
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase().replace("_", " ");
    }

    /**
     * Formats death cause for display
     */
    private String formatDeathCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case FALL -> "Fall Damage";
            case FIRE, FIRE_TICK -> "Fire";
            case LAVA -> "Lava";
            case DROWNING -> "Drowning";
            case SUFFOCATION -> "Suffocation";
            case STARVATION -> "Starvation";
            case VOID -> "The Void";
            case LIGHTNING -> "Lightning";
            case POISON -> "Poison";
            case WITHER -> "Wither";
            case FALLING_BLOCK -> "Falling Block";
            case THORNS -> "Thorns";
            case CRAMMING -> "Cramming";
            case FLY_INTO_WALL -> "Kinetic Energy";
            case FREEZE -> "Freezing";
            default -> formatEntityName(cause.name());
        };
    }

    // ==================== RESPAWN HANDLING ====================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
        if (teamOpt.isEmpty()) return;

        String team = teamOpt.get();

        // Calculate total delay: base + supply penalty
        int supplyDelay = roadService.getRespawnDelay(uuid, team);
        int totalDelay = BASE_RESPAWN_DELAY + supplyDelay;

        // Get killer name for display
        String killerName = lastKiller.remove(uuid);

        // Start the spectator-like respawn experience
        startSpectatorRespawn(player, totalDelay, killerName, supplyDelay);
    }

    /**
     * Starts the spectator-like respawn experience.
     * Player can fly around but can't interact or go through blocks.
     */
    private void startSpectatorRespawn(Player player, int totalDelaySeconds, String killerName, int supplyPenalty) {
        UUID uuid = player.getUniqueId();

        // Store respawn data
        RespawnData data = new RespawnData(
                System.currentTimeMillis() + (totalDelaySeconds * 1000L),
                player.getGameMode(),
                totalDelaySeconds,
                supplyPenalty
        );
        pendingRespawn.put(uuid, data);

        // Set to adventure mode (can't break blocks)
        player.setGameMode(GameMode.ADVENTURE);

        // Make invisible
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                totalDelaySeconds * 20 + 20, 0, false, false, true
        ));

        // Allow flying
        player.setAllowFlight(true);
        player.setFlying(true);

        // Prevent interactions
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.WEAKNESS,
                totalDelaySeconds * 20 + 20, 255, false, false, false
        ));

        // Build killed by message
        String killedByText;
        if (killerName != null) {
            killedByText = ChatColor.RED + "Killed by " + ChatColor.WHITE + killerName;
        } else {
            killedByText = ChatColor.RED + "You died";
        }

        // Show initial title
        String supplyNote = supplyPenalty > 0 ?
                ChatColor.GRAY + " (+" + supplyPenalty + "s supply penalty)" : "";
        player.sendTitle(
                killedByText,
                ChatColor.GRAY + "Respawning in " + ChatColor.YELLOW + totalDelaySeconds +
                        ChatColor.GRAY + " seconds" + supplyNote,
                10, 40, 10
        );

        // Start countdown task
        final int[] remaining = {totalDelaySeconds};
        final String finalKilledByText = killedByText;
        BukkitTask countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                pendingRespawn.remove(uuid);
                return;
            }

            remaining[0]--;

            if (remaining[0] > 0) {
                // Update title with countdown
                ChatColor countColor = remaining[0] <= 3 ? ChatColor.YELLOW : ChatColor.WHITE;
                player.sendTitle(
                        finalKilledByText,
                        ChatColor.GRAY + "Respawning in " + countColor + remaining[0] +
                                ChatColor.GRAY + " seconds",
                        0, 25, 0
                );
            } else if (remaining[0] == 0) {
                // Respawn complete
                completeRespawn(player);
            }
        }, 20L, 20L);

        // Store the task for cleanup
        data.countdownTask = countdownTask;

        // Safety: ensure respawn completes even if task fails
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingRespawn.containsKey(uuid)) {
                completeRespawn(player);
            }
        }, (totalDelaySeconds + 2) * 20L);
    }

    /**
     * Completes the respawn, restoring player to normal state.
     */
    private void completeRespawn(Player player) {
        UUID uuid = player.getUniqueId();
        RespawnData data = pendingRespawn.remove(uuid);

        if (data == null) return;

        // Cancel countdown task if running
        if (data.countdownTask != null && !data.countdownTask.isCancelled()) {
            data.countdownTask.cancel();
        }

        // Restore game mode first (before teleport)
        player.setGameMode(GameMode.SURVIVAL);

        // Remove flight
        player.setAllowFlight(false);
        player.setFlying(false);

        // Remove effects
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.WEAKNESS);

        // Teleport to team spawn
        Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
        if (teamOpt.isPresent()) {
            Optional<org.bukkit.Location> spawnOpt = teamService.getTeamSpawn(teamOpt.get());
            if (spawnOpt.isPresent()) {
                player.teleport(spawnOpt.get());
            }
        }

        // Show respawn complete title
        player.sendTitle(
                ChatColor.GREEN + "Respawned!",
                "",
                5, 20, 10
        );

        // Play sound
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f);
    }

    // ==================== RESPAWN STATE INTERACTION PREVENTION ====================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isInRespawnDelay(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isInRespawnDelay(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isInRespawnDelay(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isInRespawnDelay(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isInRespawnDelay(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isInRespawnDelay(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Prevent players in respawn state from dealing damage
        if (event.getDamager() instanceof Player damager) {
            if (isInRespawnDelay(damager.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Checks if a player is currently in respawn delay.
     */
    public boolean isInRespawnDelay(UUID uuid) {
        RespawnData data = pendingRespawn.get(uuid);
        if (data == null) return false;

        if (System.currentTimeMillis() >= data.endTime) {
            pendingRespawn.remove(uuid);
            return false;
        }

        return true;
    }

    /**
     * Gets the remaining respawn time for a player in seconds.
     */
    public int getRemainingRespawnTime(UUID uuid) {
        RespawnData data = pendingRespawn.get(uuid);
        if (data == null) return 0;

        long remaining = data.endTime - System.currentTimeMillis();
        return remaining > 0 ? (int) (remaining / 1000) : 0;
    }

    /**
     * Force completes respawn for a player (e.g., admin command).
     */
    public void forceCompleteRespawn(Player player) {
        if (pendingRespawn.containsKey(player.getUniqueId())) {
            completeRespawn(player);
        }
    }

    /**
     * Cleanup when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        RespawnData data = pendingRespawn.remove(uuid);
        if (data != null && data.countdownTask != null) {
            data.countdownTask.cancel();
        }
        lastKiller.remove(uuid);
    }

    /**
     * Data class for tracking respawn state.
     */
    private static class RespawnData {
        final long endTime;
        final GameMode previousGameMode;
        final int totalDelay;
        final int supplyPenalty;
        BukkitTask countdownTask;

        RespawnData(long endTime, GameMode previousGameMode, int totalDelay, int supplyPenalty) {
            this.endTime = endTime;
            this.previousGameMode = previousGameMode;
            this.totalDelay = totalDelay;
            this.supplyPenalty = supplyPenalty;
        }
    }
}

