package org.flintstqne.entrenched.RoundLogic;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;
import org.flintstqne.entrenched.TeamLogic.TeamDb;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.io.File;
import java.util.Random;

/**
 * Handles the complete new round initialization flow:
 * 1. Kick all players with a message
 * 2. Enable whitelist to prevent rejoining during setup
 * 3. Delete/regenerate the game world
 * 4. Start a new round in the database
 * 5. Generate region names and set up BlueMap
 * 6. Set up team spawns
 * 7. Wipe all team memberships
 * 8. Disable whitelist to allow players back
 */
public class NewRoundInitializer {

    // Chunky pregeneration settings (timing constants stay hardcoded)
    private static final long CHUNKY_CHECK_INTERVAL_TICKS = 20L * 10; // Check every 10 seconds
    private static final int CHUNKY_MAX_CHECKS = 360; // Max 1 hour of checking (360 * 10 seconds)

    private final JavaPlugin plugin;
    private final RoundService roundService;
    private final TeamService teamService;
    private final TeamDb teamDb;
    private final RegionRenderer regionRenderer;
    private final ConfigManager configManager;
    private final PhaseScheduler phaseScheduler;

    private boolean initializationInProgress = false;

    public NewRoundInitializer(
            JavaPlugin plugin,
            RoundService roundService,
            TeamService teamService,
            TeamDb teamDb,
            RegionRenderer regionRenderer,
            ScoreboardUtil scoreboardUtil,
            ConfigManager configManager,
            PhaseScheduler phaseScheduler
    ) {
        this.plugin = plugin;
        this.roundService = roundService;
        this.teamService = teamService;
        this.teamDb = teamDb;
        this.regionRenderer = regionRenderer;
        this.configManager = configManager;
        this.phaseScheduler = phaseScheduler;
    }

    public boolean isInitializationInProgress() {
        return initializationInProgress;
    }

    /**
     * Initiates the new round process. This is an async operation that will:
     * 1. Kick all players
     * 2. Enable whitelist
     * 3. Regenerate world
     * 4. Set up everything
     * 5. Disable whitelist
     *
     * @param initiator The CommandSender who initiated this (for feedback messages)
     */
    public void initiateNewRound(org.bukkit.command.CommandSender initiator) {
        if (initializationInProgress) {
            initiator.sendMessage(ChatColor.RED + "A new round initialization is already in progress!");
            return;
        }

        initializationInProgress = true;
        log("========================================");
        log("NEW ROUND INITIALIZATION STARTED");
        log("========================================");

        // Step 1: Announce to all players
        broadcastMessage(configManager.getPrefix() + ChatColor.YELLOW + "A new war is beginning! Server is resetting...");
        broadcastMessage(ChatColor.GRAY + "You will be kicked momentarily. Please reconnect in ~20-30 minutes (world pregeneration).");

        // Schedule the actual initialization after a short delay for players to read the message
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                executeNewRoundSequence(initiator);
            } catch (Exception e) {
                log("ERROR during new round initialization: " + e.getMessage());
                e.printStackTrace();
                initializationInProgress = false;
                initiator.sendMessage(ChatColor.RED + "Failed to initialize new round: " + e.getMessage());
            }
        }, 60L); // 3 second delay
    }

    private void executeNewRoundSequence(org.bukkit.command.CommandSender initiator) {
        // Step 1: Kick all players
        log("Step 1: Kicking all players...");
        kickAllPlayers(configManager.getNewRoundKickMessage());

        // Step 2: Enable whitelist
        log("Step 2: Enabling whitelist...");
        Bukkit.setWhitelist(true);
        log("Whitelist enabled");

        // Step 3: End any current round
        log("Step 3: Ending current round (if any)...");
        roundService.getCurrentRound().ifPresent(round -> {
            if (round.status() != Round.RoundStatus.COMPLETED) {
                roundService.endRound("none"); // No winner - round was reset
                log("Previous round " + round.roundId() + " marked as completed (no winner - reset)");
            }
        });

        // Step 4: Wipe team memberships
        log("Step 4: Wiping team memberships...");
        teamService.resetAllTeams();
        log("All team memberships cleared");

        // Step 4.5: Delete BlueMap web directory to force fresh render
        log("Step 4.5: Deleting BlueMap web directory...");
        deleteBlueMapDirectory();

        // Step 5: Create fresh world
        log("Step 5: Creating fresh game world...");
        createFreshGameWorld((newWorld) -> {
            if (newWorld == null) {
                log("ERROR: Failed to create new world!");
                initiator.sendMessage(ChatColor.RED + "Failed to create new world!");
                initializationInProgress = false;
                Bukkit.setWhitelist(false);
                return;
            }

            // Step 6: Set world border
            log("Step 6: Setting world border...");
            setWorldBorder(newWorld);

            // Step 7: Start Chunky pregeneration
            log("Step 7: Starting chunk pregeneration with Chunky...");
            startChunkyPregen(newWorld, () -> {
                // This callback runs after pregeneration is complete (or skipped)
                continueAfterPregen(newWorld, initiator);
            });
        });
    }

    private void continueAfterPregen(World world, org.bukkit.command.CommandSender initiator) {
        // Step 8: Start new round
        log("Step 8: Starting new round in database...");
        long worldSeed = world.getSeed();
        Round newRound = roundService.startNewRound(worldSeed);
        log("New round created: ID=" + newRound.roundId() + ", Seed=" + worldSeed);

        // Save the world name for this round
        roundService.setWorldName(newRound.roundId(), world.getName());
        log("World name saved: " + world.getName());

        // Step 9: Reset team spawns to new world heights
        log("Step 9: Resetting team spawns...");
        resetTeamSpawns(world);

        // Step 10: Generate and persist region names
        log("Step 10: Generating region names...");
        if (regionRenderer != null) {
            regionRenderer.generateAndPersistNamesForCurrentRound(world);
            log("Region names generated and persisted");

            // Step 11: Refresh BlueMap markers
            log("Step 11: Refreshing BlueMap markers...");
            try {
                regionRenderer.refreshMarkers(world);
                log("BlueMap markers refreshed");
            } catch (Exception e) {
                log("Warning: Failed to refresh BlueMap markers: " + e.getMessage());
            }
        } else {
            log("RegionRenderer not available - skipping region names and BlueMap");
        }

        // Step 12: Disable whitelist
        log("Step 12: Disabling whitelist...");
        Bukkit.setWhitelist(false);
        log("Whitelist disabled - players can now join");

        // Step 13: Start phase scheduler for auto phase advancement
        log("Step 13: Starting phase scheduler...");
        if (phaseScheduler != null) {
            phaseScheduler.start();
            log("Phase scheduler started");
        }

        // Complete!
        log("========================================");
        log("NEW ROUND INITIALIZATION COMPLETE");
        log("Round ID: " + newRound.roundId());
        log("World Seed: " + worldSeed);
        log("Phase: " + newRound.currentPhase());
        log("========================================");;

        initializationInProgress = false;

        initiator.sendMessage(ChatColor.GREEN + "New round " + newRound.roundId() + " initialized successfully!");
        initiator.sendMessage(ChatColor.GRAY + "World seed: " + worldSeed);
        initiator.sendMessage(ChatColor.GRAY + "Players can now rejoin.");
    }

    private void kickAllPlayers(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kickPlayer(message);
            log("Kicked player: " + player.getName());
        }
        log("All players kicked (" + Bukkit.getOnlinePlayers().size() + " remaining)");
    }

    /**
     * Creates a fresh game world with a unique name and random seed.
     * This approach avoids the issue of not being able to unload the main "world".
     */
    private void createFreshGameWorld(java.util.function.Consumer<World> onComplete) {
        // Generate unique world name based on timestamp
        String baseWorldName = configManager.getWorldName();
        String uniqueWorldName = baseWorldName + "_" + System.currentTimeMillis();

        // Generate random seed
        long newSeed = new Random().nextLong();

        log("Creating fresh game world:");
        log("  World name: " + uniqueWorldName);
        log("  Random seed: " + newSeed);

        // Schedule world creation
        Bukkit.getScheduler().runTask(plugin, () -> {
            WorldCreator creator = new WorldCreator(uniqueWorldName);
            creator.seed(newSeed);
            creator.environment(World.Environment.NORMAL);
            creator.type(WorldType.NORMAL);

            World newWorld = Bukkit.createWorld(creator);

            if (newWorld != null) {
                log("New world created successfully!");
                log("  Actual name: " + newWorld.getName());
                log("  Actual seed: " + newWorld.getSeed());

                // Verify seed
                if (newWorld.getSeed() == newSeed) {
                    log("  Seed verified: CORRECT");
                } else {
                    log("  WARNING: Seed mismatch! Expected " + newSeed + " but got " + newWorld.getSeed());
                }

                // Clean up old game worlds in the background
                scheduleOldWorldCleanup(baseWorldName, uniqueWorldName);

                // Continue with setup
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    onComplete.accept(newWorld);
                }, 20L);
            } else {
                log("ERROR: Failed to create world!");
                onComplete.accept(null);
            }
        });
    }

    /**
     * Schedules cleanup of old game worlds (keeps only the current one).
     */
    private void scheduleOldWorldCleanup(String baseWorldName, String currentWorldName) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            log("Cleaning up old game worlds...");

            File worldContainer = Bukkit.getWorldContainer();
            File[] folders = worldContainer.listFiles();

            if (folders == null) return;

            for (File folder : folders) {
                String folderName = folder.getName();

                // Check if this is an old game world (matches pattern: baseWorldName_timestamp)
                if (folder.isDirectory() &&
                    folderName.startsWith(baseWorldName + "_") &&
                    !folderName.equals(currentWorldName)) {

                    // Check if this world is currently loaded
                    World loadedWorld = Bukkit.getWorld(folderName);
                    if (loadedWorld != null) {
                        // Try to unload it first
                        log("Attempting to unload old world: " + folderName);
                        loadedWorld.setAutoSave(false);
                        boolean unloaded = Bukkit.unloadWorld(loadedWorld, false);
                        if (!unloaded) {
                            log("  Could not unload " + folderName + " - skipping deletion");
                            continue;
                        }
                    }

                    // Delete the folder
                    log("Deleting old world folder: " + folderName);
                    deleteWorldFolderWithRetry(folder, 3);

                    if (!folder.exists()) {
                        log("  Successfully deleted: " + folderName);
                    } else {
                        log("  Failed to fully delete: " + folderName);
                    }
                }
            }

            log("Old world cleanup complete");
        }, 200L); // Wait 10 seconds before cleanup
    }

    /**
     * Deletes the BlueMap plugin directory to force a fresh render for the new world.
     */
    private void deleteBlueMapDirectory() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File blueMapDir = new File(pluginsDir, "BlueMap");

        if (!blueMapDir.exists()) {
            log("BlueMap directory not found - skipping deletion");
            return;
        }

        log("Deleting BlueMap directory: " + blueMapDir.getAbsolutePath());

        // Delete the web directory which contains the rendered map data
        File webDir = new File(blueMapDir, "web");
        if (webDir.exists()) {
            if (deleteWorldFolder(webDir)) {
                log("  BlueMap web directory deleted successfully");
            } else {
                log("  WARNING: Could not fully delete BlueMap web directory");
            }
        }

        // Also delete the maps directory which contains per-world map data
        File mapsDir = new File(blueMapDir, "maps");
        if (mapsDir.exists()) {
            if (deleteWorldFolder(mapsDir)) {
                log("  BlueMap maps directory deleted successfully");
            } else {
                log("  WARNING: Could not fully delete BlueMap maps directory");
            }
        }

        log("BlueMap directory cleanup complete");
    }

    private void deleteWorldFolderWithRetry(File folder, int retries) {
        if (folder == null || !folder.exists()) return;

        // Try to delete, if it fails and we have retries left, wait and try again
        if (!deleteWorldFolder(folder)) {
            if (retries > 0) {
                log("Some files couldn't be deleted, retrying in 1 second... (" + retries + " retries left)");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                deleteWorldFolderWithRetry(folder, retries - 1);
            } else {
                log("WARNING: Could not delete all files in world folder after retries");
            }
        }
    }

    private boolean deleteWorldFolder(File folder) {
        if (folder == null || !folder.exists()) return true;

        boolean allDeleted = true;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (!deleteWorldFolder(file)) {
                        allDeleted = false;
                    }
                } else {
                    if (!file.delete()) {
                        log("Warning: Failed to delete file: " + file.getAbsolutePath());
                        allDeleted = false;
                    }
                }
            }
        }
        if (!folder.delete()) {
            log("Warning: Failed to delete folder: " + folder.getAbsolutePath());
            allDeleted = false;
        }
        return allDeleted;
    }

    private void setWorldBorder(World world) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(configManager.getBorderCenterX(), configManager.getBorderCenterZ());
        border.setSize(configManager.getBorderSize());
        border.setDamageAmount(configManager.getBorderDamageAmount());
        border.setDamageBuffer(configManager.getBorderDamageBuffer());
        border.setWarningDistance(configManager.getBorderWarningDistance());
        log("World border set: size=" + configManager.getBorderSize() +
                ", center=(" + configManager.getBorderCenterX() + ", " + configManager.getBorderCenterZ() + ")");
    }

    private void resetTeamSpawns(World world) {
        // Red team spawn from config
        int redCenterX = configManager.getRedSpawnX();
        int redCenterZ = configManager.getRedSpawnZ();
        int redY = world.getHighestBlockYAt(redCenterX, redCenterZ) + 1;
        Location redSpawn = new Location(world, redCenterX + 0.5, redY, redCenterZ + 0.5, 0.0f, 0.0f);
        teamService.setTeamSpawn("red", redSpawn);
        log("Red team spawn set at: " + formatLocation(redSpawn));

        // Blue team spawn from config
        int blueCenterX = configManager.getBlueSpawnX();
        int blueCenterZ = configManager.getBlueSpawnZ();
        int blueY = world.getHighestBlockYAt(blueCenterX, blueCenterZ) + 1;
        Location blueSpawn = new Location(world, blueCenterX + 0.5, blueY, blueCenterZ + 0.5, 0.0f, 0.0f);
        teamService.setTeamSpawn("blue", blueSpawn);
        log("Blue team spawn set at: " + formatLocation(blueSpawn));

        // Also update region claims for the team home regions
        int halfRegion = configManager.getRegionSize() / 2;
        teamDb.claimRegion(redCenterX - halfRegion, redCenterZ - halfRegion, "red");
        teamDb.claimRegion(blueCenterX - halfRegion, blueCenterZ - halfRegion, "blue");
        log("Team home regions claimed");
    }

    private String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    private void broadcastMessage(String message) {
        Bukkit.broadcastMessage(message);
    }

    private void log(String message) {
        plugin.getLogger().info("[NewRoundInit] " + message);
    }

    /**
     * Starts Chunky pregeneration and monitors for completion.
     * Calls onComplete when pregeneration is finished or if Chunky is unavailable.
     */
    private void startChunkyPregen(World world, Runnable onComplete) {
        // Check if pregeneration is enabled in config
        if (!configManager.isPregenEnabled()) {
            log("Chunk pregeneration disabled in config - skipping");
            onComplete.run();
            return;
        }

        // Check if Chunky plugin is available
        org.bukkit.plugin.Plugin chunkyPlugin = Bukkit.getPluginManager().getPlugin("Chunky");
        if (chunkyPlugin == null) {
            log("Chunky plugin not found - skipping pregeneration");
            onComplete.run();
            return;
        }

        int radiusChunks = configManager.getPregenRadiusChunks();
        log("Chunky detected - starting pregeneration...");
        log("Pregeneration settings: center=(0,0), radius=" + radiusChunks + " chunks");

        // Start Chunky pregeneration via commands
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky world " + world.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky center 0 0");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky radius " + radiusChunks);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky start");

            log("Chunky pregeneration started for world: " + world.getName());
            log("Monitoring for completion (checking every 10 seconds)...");

            // Start monitoring for completion by checking Chunky's task status
            monitorChunkyCompletion(world.getName(), onComplete, 0);
        });
    }

    /**
     * Monitors Chunky by checking if the generation task is still running.
     * Uses Chunky's API to check task status rather than estimating time.
     */
    private void monitorChunkyCompletion(String worldName, Runnable onComplete, int checkCount) {
        // Timeout after max checks (1 hour at 10 sec intervals = 360 checks)
        if (checkCount >= CHUNKY_MAX_CHECKS) {
            log("WARNING: Chunky pregeneration timeout reached after " + (CHUNKY_MAX_CHECKS * 10 / 60) + " minutes. Continuing anyway...");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky cancel");
            Bukkit.getScheduler().runTaskLater(plugin, onComplete, 40L);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Try to check if Chunky is still running using its API
            boolean isStillRunning = isChunkyTaskRunning(worldName);

            if (isStillRunning) {
                // Still running - log progress every minute (6 checks)
                if (checkCount % 6 == 0) {
                    int minutesElapsed = (checkCount * 10) / 60;
                    log("Chunky still generating... (" + minutesElapsed + " minutes elapsed)");
                }
                // Check again in 10 seconds
                monitorChunkyCompletion(worldName, onComplete, checkCount + 1);
            } else {
                // Chunky finished!
                int minutesElapsed = (checkCount * 10) / 60;
                log("Chunky pregeneration complete! (took ~" + minutesElapsed + " minutes)");

                // Small delay then continue
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    log("Proceeding with round initialization...");
                    onComplete.run();
                }, 40L); // 2 second delay
            }
        }, CHUNKY_CHECK_INTERVAL_TICKS);
    }

    /**
     * Checks if Chunky has an active generation task for the given world.
     * Uses reflection to access Chunky's API since we don't have a compile-time dependency.
     */
    private boolean isChunkyTaskRunning(String worldName) {
        try {
            org.bukkit.plugin.Plugin chunkyPlugin = Bukkit.getPluginManager().getPlugin("Chunky");
            if (chunkyPlugin == null) return false;

            // Try to access Chunky's API via reflection
            // Chunky stores tasks in its main class - we check if any task exists for our world
            Class<?> chunkyClass = chunkyPlugin.getClass();

            // Try to get the Chunky instance and check for running tasks
            // Method: getChunky().getGenerationTasks()
            try {
                Object chunkyInstance = chunkyClass.getMethod("getChunky").invoke(chunkyPlugin);
                if (chunkyInstance != null) {
                    Object tasksMap = chunkyInstance.getClass().getMethod("getGenerationTasks").invoke(chunkyInstance);
                    if (tasksMap instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, ?> tasks = (java.util.Map<String, ?>) tasksMap;
                        // Check if our world has an active task
                        return tasks.containsKey(worldName);
                    }
                }
            } catch (NoSuchMethodException e) {
                // Try alternative method structure for different Chunky versions
                try {
                    // Some versions use getServer().getGenerationTasks()
                    Object serverInstance = chunkyClass.getMethod("getServer").invoke(chunkyPlugin);
                    if (serverInstance != null) {
                        Object tasksMap = serverInstance.getClass().getMethod("getGenerationTasks").invoke(serverInstance);
                        if (tasksMap instanceof java.util.Map) {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, ?> tasks = (java.util.Map<String, ?>) tasksMap;
                            return tasks.containsKey(worldName);
                        }
                    }
                } catch (Exception ignored) {
                    // Fall through to command-based check
                }
            }
        } catch (Exception e) {
            // Reflection failed - fall back to assuming it's still running for a while
            log("Could not check Chunky status via API: " + e.getMessage());
        }

        // Fallback: We can't detect via API, so use a simple heuristic
        // Check if the world folder is being actively written to by checking region file count
        return isWorldBeingGenerated(worldName);
    }

    /**
     * Fallback check: See if the world's region folder is still growing.
     */
    private long lastRegionFileCount = 0;
    private int stableCheckCount = 0;

    private boolean isWorldBeingGenerated(String worldName) {
        try {
            java.io.File worldFolder = new java.io.File(Bukkit.getWorldContainer(), worldName);
            java.io.File regionFolder = new java.io.File(worldFolder, "region");

            if (!regionFolder.exists()) return true; // Still starting up

            java.io.File[] regionFiles = regionFolder.listFiles((dir, name) -> name.endsWith(".mca"));
            long currentCount = regionFiles != null ? regionFiles.length : 0;

            if (currentCount == lastRegionFileCount) {
                stableCheckCount++;
                // If file count hasn't changed for 3 checks (30 seconds), assume done
                if (stableCheckCount >= 3) {
                    return false;
                }
            } else {
                stableCheckCount = 0;
                lastRegionFileCount = currentCount;
            }

            return true; // Still growing or not stable yet
        } catch (Exception e) {
            return true; // Assume still running on error
        }
    }
}
