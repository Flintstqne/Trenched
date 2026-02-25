package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.List;
import java.util.Optional;

/**
 * Manages particle effects for Division Depots.
 * - Placement particles (one-time burst)
 * - Ambient particles (periodic, team-colored)
 * - Vulnerability particles (warning effect when raidable)
 */
public class DepotParticleManager {

    private final JavaPlugin plugin;
    private final DepotService depotService;
    private final DivisionService divisionService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    private BukkitTask particleTask;
    private boolean running = false;

    public DepotParticleManager(JavaPlugin plugin, DepotService depotService,
                                 DivisionService divisionService, TeamService teamService,
                                 ConfigManager configManager) {
        this.plugin = plugin;
        this.depotService = depotService;
        this.divisionService = divisionService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    /**
     * Starts the periodic particle effect task.
     */
    public void start() {
        if (running) return;
        if (!isEnabled()) {
            plugin.getLogger().info("[Depot] Depot particles disabled in config");
            return;
        }

        running = true;
        int interval = getParticleIntervalTicks();

        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::showAllDepotParticles, 20L, interval);
        plugin.getLogger().info("[Depot] Particle manager started (interval: " + interval + " ticks)");
    }

    /**
     * Stops the periodic particle effect task.
     */
    public void stop() {
        running = false;
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
    }

    /**
     * Shows particles for all active depots.
     */
    private void showAllDepotParticles() {
        if (!isEnabled()) return;

        // Get all depots for both teams
        List<DepotLocation> redDepots = depotService.getDepotsForTeam("red");
        List<DepotLocation> blueDepots = depotService.getDepotsForTeam("blue");

        for (DepotLocation depot : redDepots) {
            showDepotParticles(depot, "red");
        }

        for (DepotLocation depot : blueDepots) {
            showDepotParticles(depot, "blue");
        }
    }

    /**
     * Shows particles for a single depot.
     */
    private void showDepotParticles(DepotLocation depot, String team) {
        World world = Bukkit.getWorld(depot.world());
        if (world == null) return;

        Location loc = new Location(world, depot.x() + 0.5, depot.y() + 1.0, depot.z() + 0.5);

        // Check if depot is vulnerable
        boolean vulnerable = depotService.isDepotVulnerable(depot);

        if (vulnerable) {
            // Show warning particles for vulnerable depots
            showVulnerableParticles(world, loc);
        } else {
            // Show normal ambient particles (team-colored)
            showAmbientParticles(world, loc, team);
        }
    }

    /**
     * Shows ambient particles for a depot (team-colored).
     */
    private void showAmbientParticles(World world, Location loc, String team) {
        Particle particle = getAmbientParticle();

        // Spawn particles in a small area above the depot
        for (int i = 0; i < 3; i++) {
            double offsetX = (Math.random() - 0.5) * 0.5;
            double offsetY = Math.random() * 0.5;
            double offsetZ = (Math.random() - 0.5) * 0.5;

            Location particleLoc = loc.clone().add(offsetX, offsetY, offsetZ);

            // Use dust particles for team colors
            if (particle == Particle.DUST) {
                Particle.DustOptions dustOptions;
                if ("red".equalsIgnoreCase(team)) {
                    dustOptions = new Particle.DustOptions(org.bukkit.Color.RED, 1.0f);
                } else {
                    dustOptions = new Particle.DustOptions(org.bukkit.Color.BLUE, 1.0f);
                }
                world.spawnParticle(Particle.DUST, particleLoc, 1, dustOptions);
            } else {
                world.spawnParticle(particle, particleLoc, 1, 0, 0, 0, 0);
            }
        }
    }

    /**
     * Shows warning particles for a vulnerable depot.
     */
    private void showVulnerableParticles(World world, Location loc) {
        Particle particle = getVulnerableParticle();

        // More intense particle effect for vulnerable depots
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2 * i) / 8;
            double radius = 0.5;
            double offsetX = Math.cos(angle) * radius;
            double offsetZ = Math.sin(angle) * radius;

            Location particleLoc = loc.clone().add(offsetX, 0.2, offsetZ);

            if (particle == Particle.DUST) {
                // Red warning dust
                Particle.DustOptions dustOptions = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 50, 50), 1.5f);
                world.spawnParticle(Particle.DUST, particleLoc, 1, dustOptions);
            } else {
                world.spawnParticle(particle, particleLoc, 1, 0, 0.05, 0, 0.02);
            }
        }

        // Add a rising particle in the center
        world.spawnParticle(Particle.FLAME, loc, 1, 0.1, 0.2, 0.1, 0.01);
    }

    /**
     * Shows a one-time placement burst effect.
     */
    public void showPlacementEffect(Location location, String team) {
        if (!isEnabled()) return;

        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 0.5, 0.5);

        // Burst of team-colored particles
        Particle.DustOptions dustOptions;
        if ("red".equalsIgnoreCase(team)) {
            dustOptions = new Particle.DustOptions(org.bukkit.Color.RED, 1.5f);
        } else {
            dustOptions = new Particle.DustOptions(org.bukkit.Color.BLUE, 1.5f);
        }

        // Spiral burst effect
        for (int i = 0; i < 20; i++) {
            double angle = (Math.PI * 2 * i) / 20;
            double radius = 0.8;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = (double) i / 20;

            Location particleLoc = center.clone().add(x, y, z);
            world.spawnParticle(Particle.DUST, particleLoc, 2, 0.1, 0.1, 0.1, 0, dustOptions);
        }

        // Central burst
        world.spawnParticle(Particle.END_ROD, center, 15, 0.3, 0.3, 0.3, 0.05);

        // Play sound
        world.playSound(center, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
    }

    /**
     * Shows a one-time raid completion effect.
     */
    public void showRaidEffect(Location location) {
        if (!isEnabled()) return;

        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 0.5, 0.5);

        // Explosion of particles
        world.spawnParticle(Particle.EXPLOSION, center, 3, 0.5, 0.5, 0.5, 0);
        world.spawnParticle(Particle.FLAME, center, 30, 0.5, 0.5, 0.5, 0.1);
        world.spawnParticle(Particle.SMOKE, center, 20, 0.5, 0.5, 0.5, 0.05);

        // Play sound
        world.playSound(center, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
        world.playSound(center, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.0f, 0.5f);
    }

    /**
     * Shows vulnerability warning effect when depot becomes vulnerable.
     */
    public void showVulnerabilityWarning(Location location) {
        if (!isEnabled()) return;

        World world = location.getWorld();
        if (world == null) return;

        Location center = location.clone().add(0.5, 1.0, 0.5);

        // Warning particles
        Particle.DustOptions warningDust = new Particle.DustOptions(org.bukkit.Color.ORANGE, 2.0f);

        for (int i = 0; i < 3; i++) {
            final int tick = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (world == null) return;

                // Expanding ring
                double radius = 0.5 + (tick * 0.5);
                for (int j = 0; j < 16; j++) {
                    double angle = (Math.PI * 2 * j) / 16;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;

                    Location particleLoc = center.clone().add(x, 0, z);
                    world.spawnParticle(Particle.DUST, particleLoc, 1, warningDust);
                }
            }, tick * 5L);
        }

        // Play warning sound
        world.playSound(center, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
    }

    // ==================== Configuration ====================

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("division-depots.visuals.show-particles", true);
    }

    private int getParticleIntervalTicks() {
        return plugin.getConfig().getInt("division-depots.visuals.particle-interval-ticks", 40);
    }

    private Particle getAmbientParticle() {
        String particleName = plugin.getConfig().getString("division-depots.visuals.particle-type", "SOUL_FIRE_FLAME");
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Fall back to dust for team colors
            return Particle.DUST;
        }
    }

    private Particle getVulnerableParticle() {
        String particleName = plugin.getConfig().getString("division-depots.visuals.vulnerable-particle-type", "DRIP_LAVA");
        try {
            return Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Particle.DRIPPING_LAVA;
        }
    }
}

