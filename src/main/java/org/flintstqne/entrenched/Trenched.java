package org.flintstqne.entrenched;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.BlueMapHook.BlueMapIntegration;
import org.flintstqne.entrenched.ChatLogic.ChatChannelManager;
import org.flintstqne.entrenched.ChatLogic.ChatCommand;
import org.flintstqne.entrenched.DivisionLogic.*;
import org.flintstqne.entrenched.LinkLogic.*;
import org.flintstqne.entrenched.MeritLogic.*;
import org.flintstqne.entrenched.ObjectiveLogic.*;
import org.flintstqne.entrenched.PartyLogic.*;
import org.flintstqne.entrenched.RegionLogic.*;
import org.flintstqne.entrenched.RoadLogic.*;
import org.flintstqne.entrenched.RoundLogic.RoundCommand;
import org.flintstqne.entrenched.RoundLogic.RoundDb;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.RoundLogic.SqlRoundService;
import org.flintstqne.entrenched.RoundLogic.NewRoundInitializer;
import org.flintstqne.entrenched.RoundLogic.PhaseScheduler;
import org.flintstqne.entrenched.RoundLogic.EndgameDb;
import org.flintstqne.entrenched.RoundLogic.RoundEndgameManager;
import org.flintstqne.entrenched.TeamLogic.*;
import org.flintstqne.entrenched.Utils.ChatUtil;
import org.flintstqne.entrenched.Utils.PlaceholderExpansion;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Trenched extends JavaPlugin {

    private ConfigManager configManager;
    private boolean bluemapAvailable;
    private TeamService teamService;
    private TeamDb teamDb;
    private RoundDb roundDb;
    private RoundService roundService;
    private RegionRenderer regionRenderer;
    private ScoreboardUtil scoreboardUtil;
    private NewRoundInitializer newRoundInitializer;
    private PhaseScheduler phaseScheduler;
    private DivisionDb divisionDb;
    private DivisionService divisionService;
    private PartyDb partyDb;
    private PartyService partyService;
    private ChatChannelManager chatChannelManager;
    private RegionDb regionDb;
    private RegionService regionService;
    private RegionNotificationManager regionNotificationManager;
    private RegionCaptureListener regionCaptureListener;
    private RoadDb roadDb;
    private RoadService roadService;
    private RoadListener roadListener;
    private DeathListener deathListener;
    private SupplyPenaltyListener supplyPenaltyListener;
    private MeritDb meritDb;
    private MeritService meritService;
    private MeritListener meritListener;
    private ObjectiveDb objectiveDb;
    private ObjectiveService objectiveService;
    private ObjectiveUIManager objectiveUIManager;
    private ObjectiveListener objectiveListener;
    private BuildingBenefitManager buildingBenefitManager;
    private GarrisonSpawnService garrisonSpawnService;
    private GarrisonSpawnListener garrisonSpawnListener;
    private ContainerProtectionListener containerProtectionListener;

    // Endgame System
    private EndgameDb endgameDb;
    private RoundEndgameManager endgameManager;

    // Division Depot System
    private DepotService depotService;
    private DepotItem depotItem;
    private DepotRecipes depotRecipes;
    private DepotListener depotListener;
    private DepotParticleManager depotParticleManager;

    // Player Settings
    private org.flintstqne.entrenched.Utils.SettingsCommand settingsCommand;

    // Discord â†” Minecraft Linking
    private LinkDb linkDb;
    private LinkService linkService;

    // Statistics System
    private org.flintstqne.entrenched.StatLogic.StatDb statDb;
    private org.flintstqne.entrenched.StatLogic.StatService statService;
    private org.flintstqne.entrenched.StatLogic.StatListener statListener;
    private org.flintstqne.entrenched.StatLogic.StatApiServer statApiServer;

    // Player-Placed Block Tracking
    private org.flintstqne.entrenched.ObjectiveLogic.PlacedBlockDb placedBlockDb;
    private org.flintstqne.entrenched.ObjectiveLogic.PlacedBlockTracker placedBlockTracker;

    @Override
    public void onEnable() {
        // Initialize configuration first
        configManager = new ConfigManager(this);
        getLogger().info("[Trenched] Configuration loaded");

        // Initialize database and round service first so we can get the game world
        teamDb = TeamBootstrap.createDb(this);
        roundDb = new RoundDb(this);
        roundService = new SqlRoundService(roundDb);
        getLogger().info("[Trenched] Round system initialized");

        // If there's an active round with a timestamped world name (e.g. world_1711234567890),
        // Bukkit won't auto-load it because server.properties still says level-name=world.
        // Load it from disk now so the rest of the plugin can find it.
        ensureGameWorldLoaded();

        // Get the game world - prefer the world from active round, fall back to config
        World world = roundService.getGameWorld()
                .orElseGet(() -> Bukkit.getWorld(configManager.getWorldName()));

        if (world == null) {
            getLogger().warning("[Trenched] Game world not found! Using default 'world'");
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }

        // Store for later use
        final World gameWorld = world;

        // BlueMap integration (only if enabled in config)
        if (configManager.isBlueMapEnabled()) {
            bluemapAvailable = BlueMapIntegration.initialize(this,
                    api -> {
                        // Fires every time BlueMap finishes loading (including /bluemap reload)
                        if (gameWorld != null && regionRenderer != null) {
                            regionRenderer.generateAndPersistNamesForCurrentRound(gameWorld);
                            regionRenderer.refreshMarkers(gameWorld);
                        }
                    },
                    api -> getLogger().info("BlueMap shutting down â€” markers will be re-created on next load."));
            if (bluemapAvailable) {
                getLogger().info("BlueMap integration enabled.");
            } else {
                getLogger().warning("BlueMap not available - region rendering disabled.");
            }
        } else {
            bluemapAvailable = false;
            getLogger().info("BlueMap integration disabled in config.");
        }

        if (gameWorld != null) {
            setWorldBorder(gameWorld);
        }

        teamService = TeamBootstrap.createService(teamDb, gameWorld);

        // Initialize Division system
        divisionDb = new DivisionDb(this);
        divisionService = new SqlDivisionService(divisionDb, roundService, teamService, configManager);
        getLogger().info("[Entrenched] Division system initialized");

        // Initialize Party system
        partyDb = new PartyDb(this);
        partyService = new SqlPartyService(partyDb, roundService, teamService, configManager);
        getLogger().info("[Entrenched] Party system initialized");

        // Initialize Region Capture system
        regionDb = new RegionDb(this);
        SqlRegionService sqlRegionService = new SqlRegionService(regionDb, roundService, configManager);
        regionService = sqlRegionService;

        // Instantiate RegionRenderer AFTER regionService so it can color captured regions
        regionRenderer = new RegionRenderer(this, roundService, regionService);

        regionNotificationManager = new RegionNotificationManager(this, regionService, teamService, configManager, regionRenderer);
        regionCaptureListener = new RegionCaptureListener(regionService, teamService, configManager, regionRenderer);

        // NOTE: Capture callback is set later, after roadService is initialized
        getLogger().info("[Entrenched] Region capture system initialized");

        // Initialize Objective System (before road service so we can use it in capture callback)
        objectiveDb = new ObjectiveDb(this);
        objectiveService = new SqlObjectiveService(this, objectiveDb, roundService, regionService, configManager);

        // Initialize player-placed block tracking for structure detection
        if (configManager.isPlayerPlacedTrackingEnabled()) {
            placedBlockDb = new org.flintstqne.entrenched.ObjectiveLogic.PlacedBlockDb(this);
            placedBlockTracker = new org.flintstqne.entrenched.ObjectiveLogic.PlacedBlockTracker(
                    this, placedBlockDb,
                    configManager.getPlayerPlacedFlushIntervalSeconds(),
                    configManager.getPlayerPlacedCleanupIntervalMinutes());
            placedBlockTracker.setRegionActiveChecker(regionId -> {
                // A region is "active" if it has building objectives or registered buildings
                var settlements = objectiveService.getActiveObjectives(regionId, org.flintstqne.entrenched.ObjectiveLogic.ObjectiveCategory.SETTLEMENT);
                boolean hasBuildingObj = settlements.stream().anyMatch(o ->
                        o.type() == org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType.SETTLEMENT_ESTABLISH_OUTPOST
                        || o.type() == org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType.SETTLEMENT_WATCHTOWER
                        || o.type() == org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType.SETTLEMENT_GARRISON_QUARTERS);
                if (hasBuildingObj) return true;
                // Also check if there are registered buildings
                return objectiveService.getAllActiveBuildings().stream()
                        .anyMatch(b -> b.regionId().equals(regionId));
            });
            placedBlockTracker.start();
            ((SqlObjectiveService) objectiveService).setPlacedBlockTracker(placedBlockTracker);

            // Load tracked blocks for regions that already have active building objectives
            loadTrackedBlocksForActiveObjectives();

            getLogger().info("[Trenched] Player-placed block tracking enabled");
        }

        // Wire up division and team services for assassination objective (avoids circular dependency)
        ((SqlObjectiveService) objectiveService).setDivisionService(divisionService);
        ((SqlObjectiveService) objectiveService).setTeamService(teamService);
        ((SqlObjectiveService) objectiveService).setRegionRenderer(regionRenderer);

        objectiveUIManager = new ObjectiveUIManager(this, objectiveService, regionService, roundService, teamService, configManager);
        objectiveListener = new ObjectiveListener(this, objectiveService, objectiveUIManager, regionService, teamService, configManager);
        ((SqlObjectiveService) objectiveService).setObjectiveListener(objectiveListener);

        // Wire up building destroyed callback â€” broadcasts specific repair needs to team
        objectiveService.setBuildingDestroyedCallback((building, regionName, detectionResult) -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Optional<String> playerTeam = teamService.getPlayerTeam(player.getUniqueId());
                if (playerTeam.isPresent() && playerTeam.get().equalsIgnoreCase(building.team())) {
                    player.sendMessage(configManager.getPrefix() + org.bukkit.ChatColor.RED + "âš  [Alert] " +
                            building.type().getDisplayName() + " destroyed in " + regionName + "!");
                    player.sendMessage(org.bukkit.ChatColor.GRAY + "  Location: " + org.bukkit.ChatColor.WHITE +
                            building.anchorX() + ", " + building.anchorY() + ", " + building.anchorZ());

                    // Show what specific aspects of the building need repair
                    if (detectionResult != null) {
                        java.util.List<String> checklist = detectionResult.getChecklist();
                        for (String line : checklist) {
                            if (line.isEmpty()) continue;
                            boolean failing = line.startsWith("âœ—") || line.contains("âœ—");
                            boolean isHeader = line.startsWith("Â§");
                            if (failing) {
                                // Only show failing items â€” these are what needs repair
                                player.sendMessage(org.bukkit.ChatColor.RED + "  " + line);
                            } else if (isHeader) {
                                // Skip variant headers for destroyed building alerts
                            }
                            // Skip passing items (âœ“) to keep the alert concise
                        }
                        player.sendMessage(org.bukkit.ChatColor.YELLOW + "  ðŸ”§ Repair these issues to restore the building!");
                    }
                }
            }
        });

        // Wire placed block tracker to objective listener if enabled
        if (placedBlockTracker != null) {
            objectiveListener.setPlacedBlockTracker(placedBlockTracker);
        }

        // Initialize Building Benefit Manager for outpost buffs, watchtower detection, etc.
        buildingBenefitManager = new BuildingBenefitManager(this, objectiveService, regionService, teamService, roundService, configManager);
        buildingBenefitManager.setRegionRenderer(regionRenderer);
        buildingBenefitManager.start();
        // Register as event listener â€” required for the spyglass spotting system
        getServer().getPluginManager().registerEvents(buildingBenefitManager, this);

        // Initialize Garrison Spawn Service for quick-travel system
        garrisonSpawnService = new GarrisonSpawnService(this, objectiveService, regionService, teamService, roundService, configManager);
        garrisonSpawnService.setRegionRenderer(regionRenderer);
        garrisonSpawnService.start();
        garrisonSpawnListener = new GarrisonSpawnListener(garrisonSpawnService);
        getServer().getPluginManager().registerEvents(garrisonSpawnListener, this);

        getLogger().info("[Entrenched] Objective system initialized");

        // Check if a round is already active, if not start a new one (if enabled in config)
        // This must be done AFTER regionService and regionNotificationManager are initialized
        if (configManager.isAutoStartRoundEnabled()) {
            checkAndInitializeRound(gameWorld);
        }

        // Scoreboard util needs regionRenderer, teamService, and regionService; create after they are available
        scoreboardUtil = new ScoreboardUtil(teamService, regionRenderer, roundService, regionService, configManager);
        scoreboardUtil.startUpdateTask(this); // Start automatic 3-second scoreboard updates

        // Create and start the PhaseScheduler for auto phase advancement
        phaseScheduler = new PhaseScheduler(this, roundService, configManager, scoreboardUtil);

        // Initialize Endgame System for automatic win detection
        endgameDb = new EndgameDb(this);
        endgameManager = new RoundEndgameManager(this, endgameDb, roundService, regionService, configManager);
        endgameManager.setRegionRenderer(regionRenderer);
        endgameManager.setRoundEndCallback(this::handleRoundEnd);
        phaseScheduler.setEndgameManager(endgameManager);

        // Wire up heat callback for endgame overtime target selection
        sqlRegionService.setHeatCallback((regionId, heat) -> {
            if (endgameManager != null) {
                endgameManager.recordHeat(regionId, heat);
            }
        });

        endgameManager.start();

        phaseScheduler.start();

        // Create the NewRoundInitializer for /round new command (needs phaseScheduler)
        newRoundInitializer = new NewRoundInitializer(
                this, roundService, teamService, teamDb, regionRenderer, scoreboardUtil, configManager, phaseScheduler
        );
        // Wire up available services immediately
        newRoundInitializer.setRegionService(regionService);
        newRoundInitializer.setRegionNotificationManager(regionNotificationManager);
        newRoundInitializer.setObjectiveService(objectiveService);
        newRoundInitializer.setEndgameManager(endgameManager);
        if (placedBlockTracker != null) {
            newRoundInitializer.setPlacedBlockTracker(placedBlockTracker);
        }

        // Initialize Road/Supply Line system
        roadDb = new RoadDb(this);
        roadService = new SqlRoadService(roadDb, roundService, regionService, configManager);
        roadListener = new RoadListener(this, roadService, teamService, regionService, configManager, regionRenderer);
        deathListener = new DeathListener(this, roadService, teamService, configManager);
        supplyPenaltyListener = new SupplyPenaltyListener(roadService, teamService, deathListener);

        // Wire roadService to NewRoundInitializer (created earlier but roadService wasn't available yet)
        newRoundInitializer.setRoadService(roadService);

        // Hook player death â†’ immediately drop building benefit tracking so no spurious exit buff fires
        deathListener.setDeathCallback(player ->
                buildingBenefitManager.notifyPlayerDied(player.getUniqueId()));

        // Connect death listener to garrison spawn service for spawn map
        deathListener.setRespawnCallback((player, spawnLocation) -> {
            if (garrisonSpawnService != null) {
                garrisonSpawnService.giveSpawnMap(player, spawnLocation);
            }
        });

        // Connect scoreboard to road service for accurate supply display
        scoreboardUtil.setRoadService(roadService);

        // Connect road service to region service for accurate supply calculations
        sqlRegionService.setRoadService(roadService);

        // NOTE: Stat listener is wired to region service later after stat system initialization

        // Connect road listener to objective service for building exclusion
        roadListener.setObjectiveService(objectiveService);

        // Connect road disruption callback to notification manager
        roadListener.setDisruptionCallback((team, affectedRegions, destroyedBlock) -> {
            String sourceRegion = destroyedBlock != null ? destroyedBlock.regionId() : null;
            regionNotificationManager.broadcastSupplyDisrupted(team, affectedRegions, sourceRegion);
        });


        // Connect capture callback to notification manager AND auto-scan for roads
        // NOTE: Use roundService.getGameWorld() dynamically instead of closing over a World
        // reference, so that after a new round creates a new world, this callback still works.
        sqlRegionService.setCaptureCallback((regionId, newOwner, previousOwner) -> {
            // Broadcast capture notification
            regionNotificationManager.broadcastCapture(regionId, newOwner, previousOwner);

            // Expire any active objectives in the captured region
            objectiveService.expireObjectivesInRegion(regionId);

            // Clear player-placed block tracking for the captured region
            // The new owner's builds will be tracked fresh
            if (placedBlockTracker != null) {
                placedBlockTracker.clearRegion(regionId);
                getLogger().info("[PlacedBlocks] Cleared tracking for captured region " + regionId);
            }

            // Record capture heat for endgame overtime target selection
            if (endgameManager != null) {
                endgameManager.recordCapture(regionId);
            }

            // Notify divisions whose depots in this region just became vulnerable
            if (depotService != null && divisionService != null && previousOwner != null) {
                List<org.flintstqne.entrenched.DivisionLogic.DepotLocation> depotsInRegion =
                        depotService.getDepotsInRegion(regionId);
                // Find depots belonging to the previous owner's team â€” they are now vulnerable
                java.util.Set<Integer> notifiedDivisions = new java.util.HashSet<>();
                for (org.flintstqne.entrenched.DivisionLogic.DepotLocation depot : depotsInRegion) {
                    if (notifiedDivisions.contains(depot.divisionId())) continue;
                    Optional<org.flintstqne.entrenched.DivisionLogic.Division> divOpt =
                            divisionService.getDivision(depot.divisionId());
                    if (divOpt.isEmpty()) continue;
                    org.flintstqne.entrenched.DivisionLogic.Division division = divOpt.get();
                    // Only alert if the depot's team lost this region (i.e., depot is now vulnerable)
                    if (!division.team().equalsIgnoreCase(previousOwner)) continue;
                    notifiedDivisions.add(depot.divisionId());
                    String regionName = regionNotificationManager.getRegionDisplayName(regionId);
                    for (org.flintstqne.entrenched.DivisionLogic.DivisionMember member :
                            divisionService.getMembers(division.divisionId())) {
                        Player memberPlayer = Bukkit.getPlayer(java.util.UUID.fromString(member.playerUuid()));
                        if (memberPlayer != null && memberPlayer.isOnline()) {
                            memberPlayer.sendMessage(configManager.getPrefix() +
                                    ChatColor.RED + "âš  " + ChatColor.YELLOW +
                                    "Your division depot in " + ChatColor.WHITE + regionName +
                                    ChatColor.YELLOW + " is now " + ChatColor.RED + "VULNERABLE" +
                                    ChatColor.YELLOW + "! Enemies can raid it.");
                        }
                    }
                }
            }

            // Auto-scan for existing road blocks in the captured region.
            // Dynamically resolve the current game world so this works after new rounds.
            World currentGameWorld = roundService.getGameWorld().orElse(null);
            if (currentGameWorld != null && newOwner != null) {
                // Delay slightly to ensure capture is fully processed
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    int found = roadService.scanRegionForRoads(regionId, newOwner, currentGameWorld);
                    if (found > 0) {
                        getLogger().info("[Entrenched] Auto-scanned " + regionId + " on capture: " + found + " road blocks found for " + newOwner);
                    }

                    // Also recalculate supply for the previous owner â€” they just lost
                    // a region, so their supply chain may be broken
                    if (previousOwner != null && !previousOwner.isEmpty()) {
                        roadService.recalculateSupply(previousOwner);
                    }
                }, 20L); // 1 second delay
            }
        });

        getLogger().info("[Entrenched] Road/supply line system initialized");

        // Initialize Merit System
        meritDb = new MeritDb(this);
        meritService = new SqlMeritService(meritDb, configManager);
        meritListener = new MeritListener(this, meritService, teamService, regionService, roundService, configManager);

        // Initialize nametag manager for rank display above heads
        MeritNametagManager nametagManager = new MeritNametagManager(this, meritService, teamService, configManager);
        nametagManager.start();
        meritListener.setNametagManager(nametagManager);

        // Connect scoreboard to merit service for rank/token display
        scoreboardUtil.setMeritService(meritService);
        // Connect scoreboard to objective service for compass display
        scoreboardUtil.setObjectiveService(objectiveService);
        // Connect scoreboard to endgame manager for endgame state display
        scoreboardUtil.setEndgameManager(endgameManager);
        getLogger().info("[Entrenched] Merit system initialized");

        // Start objective UI and listeners (initialized earlier in startup)
        objectiveUIManager.start();
        objectiveListener.setRoundService(roundService);
        // NOTE: statListener is wired to objectiveListener later after stat system initialization
        objectiveListener.start();
        getServer().getPluginManager().registerEvents(objectiveListener, this);

        // Initialize and register container protection listener
        containerProtectionListener = new ContainerProtectionListener(regionService, teamService, objectiveService, configManager);
        getServer().getPluginManager().registerEvents(containerProtectionListener, this);
        getLogger().info("[Entrenched] Container protection system initialized");

        // Initialize Division Depot System
        if (configManager.isDepotSystemEnabled()) {
            depotItem = new DepotItem(this);
            depotService = new SqlDepotService(this, divisionDb, divisionService, regionService, teamService, roundService, configManager);
            depotRecipes = new DepotRecipes(this, depotItem);
            depotRecipes.registerRecipes();
            depotListener = new DepotListener(this, depotService, divisionService, teamService, regionService, configManager, depotItem);
            getServer().getPluginManager().registerEvents(depotListener, this);

            // Initialize and start particle manager for depot visual effects
            depotParticleManager = new DepotParticleManager(this, depotService, divisionService, teamService, configManager);
            depotParticleManager.start();
            depotListener.setParticleManager(depotParticleManager);

            // Connect depot service to container protection (so it can exclude depot blocks)
            containerProtectionListener.setDepotService(depotService, depotItem);

            getLogger().info("[Trenched] Division Depot system initialized");
        } else {
            getLogger().info("[Trenched] Division Depot system disabled in config");
        }

        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new org.flintstqne.entrenched.Utils.PlaceholderExpansion(meritService, teamService, divisionService, configManager).register();
            getLogger().info("[Entrenched] PlaceholderAPI expansion registered");
        } else {
            getLogger().info("[Entrenched] PlaceholderAPI not found - placeholders not available");
        }

        // Initialize Statistics System
        statDb = new org.flintstqne.entrenched.StatLogic.StatDb(this);
        statService = new org.flintstqne.entrenched.StatLogic.SqlStatService(this, statDb, roundService, configManager);
        statListener = new org.flintstqne.entrenched.StatLogic.StatListener(this, statService, teamService, regionService, roundService, configManager);
        statListener.setDivisionService(divisionService);
        getServer().getPluginManager().registerEvents(statListener, this);
        ((org.flintstqne.entrenched.StatLogic.SqlStatService) statService).start();
        statListener.startTimeTracking();

        // Wire stat listener to objective service for objective completion tracking
        ((org.flintstqne.entrenched.ObjectiveLogic.SqlObjectiveService) objectiveService).setStatListener(statListener);

        // Wire stat listener to objective listener for defensive objective tracking (TNT defusal, etc.)
        objectiveListener.setStatListener(statListener);

        // Wire stat listener to depot listener for depot placement/raid tracking
        if (depotListener != null) {
            depotListener.setStatListener(statListener);
        }

        // Wire stat listener to region service for IP earned tracking
        ((org.flintstqne.entrenched.RegionLogic.SqlRegionService) regionService).setStatListener(statListener);

        // Wire stat listener to road listener for road build/damage tracking
        roadListener.setStatListener(statListener);

        getLogger().info("[Trenched] Statistics system initialized");

        // Wire statService to NewRoundInitializer (created earlier but statService wasn't available yet)
        newRoundInitializer.setStatService(statService);

        // Initialize Discord â†” Minecraft linking system (always, so /link works in-game)
        linkDb = new LinkDb(this);
        linkService = new LinkService(linkDb);
        getLogger().info("[Trenched] Discord linking system initialized");

        // Start Stats API server if enabled
        if (configManager.isStatApiEnabled()) {
            statApiServer = new org.flintstqne.entrenched.StatLogic.StatApiServer(
                    this, statService, configManager, meritService, divisionService, regionService, teamService);
            statApiServer.setLinkService(linkService);
            statApiServer.start();
            getLogger().info("[Trenched] Stats API server started on port " + configManager.getStatApiPort());
        }

        // Recalculate supply for both teams on startup to ensure correct values
        // This is especially important after server restarts when road data persists
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            getLogger().info("[Entrenched] Recalculating supply levels on startup...");
            roadService.recalculateSupply("red");
            roadService.recalculateSupply("blue");
            getLogger().info("[Entrenched] Supply recalculation complete");
        }, 40L); // 2 second delay to ensure everything is loaded

        // Start influence decay scheduler (runs every minute)
        // Decays IP in contested regions where no activity is happening
        startInfluenceDecayScheduler();

        // Initialize Chat Channel Manager
        chatChannelManager = new ChatChannelManager();
        getLogger().info("[Trenched] Chat channel system initialized");

        // Commands registration
        RoundCommand roundCommand = new RoundCommand(roundService, teamService, regionRenderer, scoreboardUtil, phaseScheduler, configManager);
        roundCommand.setNewRoundInitializer(newRoundInitializer);
        roundCommand.setEndgameManager(endgameManager);
        // Wire placed block tracker cleanup for manual /round end
        if (placedBlockTracker != null) {
            final var tracker = placedBlockTracker;
            roundCommand.setRoundEndCleanup(() -> {
                tracker.clearAll();
                getLogger().info("[PlacedBlocks] Cleared all tracking data (manual round end)");
            });
        }
        var roundCmd = Objects.requireNonNull(getCommand("round"), "Command `round` missing from plugin.yml");
        roundCmd.setExecutor(roundCommand);
        roundCmd.setTabCompleter(roundCommand);

        TeamCommand teamCommand = new TeamCommand(teamService, scoreboardUtil);
        var teamCmd = Objects.requireNonNull(getCommand("team"), "Command `team` missing from plugin.yml");
        teamCmd.setExecutor(teamCommand);
        teamCmd.setTabCompleter(teamCommand);

        Objects.requireNonNull(getCommand("teamgui"), "Command `teamgui` missing from plugin.yml")
                .setExecutor(new TeamGuiCommand(teamService, this, scoreboardUtil, deathListener));

        // Division commands
        DivisionCommand divisionCommand = new DivisionCommand(divisionService, teamService, configManager);
        var divisionCmd = Objects.requireNonNull(getCommand("division"), "Command `division` missing from plugin.yml");
        divisionCmd.setExecutor(divisionCommand);
        divisionCmd.setTabCompleter(divisionCommand);

        // Confirm command (for division creation, etc.)
        org.flintstqne.entrenched.Utils.ConfirmCommand confirmCommand =
                new org.flintstqne.entrenched.Utils.ConfirmCommand(divisionCommand, configManager);
        Objects.requireNonNull(getCommand("confirm"), "Command `confirm` missing from plugin.yml")
                .setExecutor(confirmCommand);

        // Party commands
        PartyCommand partyCommand = new PartyCommand(partyService, configManager);
        var partyCmd = Objects.requireNonNull(getCommand("party"), "Command `party` missing from plugin.yml");
        partyCmd.setExecutor(partyCommand);
        partyCmd.setTabCompleter(partyCommand);

        // Chat channel commands (/dc, /g, /tc, /pc, /rc)
        ChatCommand chatCommand = new ChatCommand(chatChannelManager, divisionService, partyService, teamService, configManager, regionRenderer);
        Objects.requireNonNull(getCommand("dc"), "Command `dc` missing from plugin.yml")
                .setExecutor(chatCommand);
        Objects.requireNonNull(getCommand("g"), "Command `g` missing from plugin.yml")
                .setExecutor(chatCommand);
        Objects.requireNonNull(getCommand("tc"), "Command `tc` missing from plugin.yml")
                .setExecutor(chatCommand);
        Objects.requireNonNull(getCommand("pc"), "Command `pc` missing from plugin.yml")
                .setExecutor(chatCommand);
        Objects.requireNonNull(getCommand("rc"), "Command `rc` missing from plugin.yml")
                .setExecutor(chatCommand);

        // Region commands
        RegionCommand regionCommand = new RegionCommand(regionService, teamService, configManager, regionRenderer);
        var regionCmd = Objects.requireNonNull(getCommand("region"), "Command `region` missing from plugin.yml");
        regionCmd.setExecutor(regionCommand);
        regionCmd.setTabCompleter(regionCommand);

        // Supply commands
        org.flintstqne.entrenched.RoadLogic.SupplyCommand supplyCommand = new org.flintstqne.entrenched.RoadLogic.SupplyCommand(
                roadService, regionService, teamService, configManager
        );
        var supplyCmd = Objects.requireNonNull(getCommand("supply"), "Command `supply` missing from plugin.yml");
        supplyCmd.setExecutor(supplyCommand);
        supplyCmd.setTabCompleter(supplyCommand);

        // Admin commands
        org.flintstqne.entrenched.AdminLogic.AdminCommand adminCommand = new org.flintstqne.entrenched.AdminLogic.AdminCommand(
                this, roundService, teamService, regionService, roadService, deathListener, phaseScheduler, configManager, regionRenderer, meritService
        );
        adminCommand.setNewRoundInitializer(newRoundInitializer);
        adminCommand.setEndgameManager(endgameManager);
        adminCommand.setObjectiveService(objectiveService);
        adminCommand.setDivisionService(divisionService);
        adminCommand.setStatService(statService);
        if (depotService != null && depotItem != null) {
            adminCommand.setDepotService(depotService, depotItem);
        }
        var adminCmd = Objects.requireNonNull(getCommand("admin"), "Command `admin` missing from plugin.yml");
        adminCmd.setExecutor(adminCommand);
        adminCmd.setTabCompleter(adminCommand);

        // Merit commands
        MeritCommand meritCommand = new MeritCommand(meritService, teamService, configManager);
        var meritCmd = Objects.requireNonNull(getCommand("merit"), "Command `merit` missing from plugin.yml");
        meritCmd.setExecutor(meritCommand);
        meritCmd.setTabCompleter(meritCommand);

        var meritsCmd = Objects.requireNonNull(getCommand("merits"), "Command `merits` missing from plugin.yml");
        meritsCmd.setExecutor(meritCommand);
        meritsCmd.setTabCompleter(meritCommand);

        var ranksCmd = Objects.requireNonNull(getCommand("ranks"), "Command `ranks` missing from plugin.yml");
        ranksCmd.setExecutor(meritCommand);

        // Achievement commands
        org.flintstqne.entrenched.MeritLogic.AchievementCommand achievementCommand =
                new org.flintstqne.entrenched.MeritLogic.AchievementCommand(meritService, configManager);
        var achievementsCmd = Objects.requireNonNull(getCommand("achievements"), "Command `achievements` missing from plugin.yml");
        achievementsCmd.setExecutor(achievementCommand);
        achievementsCmd.setTabCompleter(achievementCommand);

        // Objective commands
        ObjectiveCommand objectiveCommand = new ObjectiveCommand(objectiveService, regionService, teamService, configManager);
        var objectiveCmd = Objects.requireNonNull(getCommand("objective"), "Command `objective` missing from plugin.yml");
        objectiveCmd.setExecutor(objectiveCommand);
        objectiveCmd.setTabCompleter(objectiveCommand);

        // Stats commands
        org.flintstqne.entrenched.StatLogic.StatCommand statCommand =
                new org.flintstqne.entrenched.StatLogic.StatCommand(statService, teamService, roundService, configManager);
        var statsCmd = Objects.requireNonNull(getCommand("stats"), "Command `stats` missing from plugin.yml");
        statsCmd.setExecutor(statCommand);
        statsCmd.setTabCompleter(statCommand);

        // Link commands (/link, /unlink)
        LinkCommand linkCommand = new LinkCommand(linkService);
        Objects.requireNonNull(getCommand("link"), "Command `link` missing from plugin.yml")
                .setExecutor(linkCommand);
        Objects.requireNonNull(getCommand("unlink"), "Command `unlink` missing from plugin.yml")
                .setExecutor(linkCommand);

        // Settings command (/settings particles)
        settingsCommand = new org.flintstqne.entrenched.Utils.SettingsCommand(this);
        var settingsCmd = Objects.requireNonNull(getCommand("settings"), "Command `settings` missing from plugin.yml");
        settingsCmd.setExecutor(settingsCommand);
        settingsCmd.setTabCompleter(settingsCommand);

        // Wire settings into building benefit manager for particle toggle
        if (buildingBenefitManager != null) {
            buildingBenefitManager.setSettingsCommand(settingsCommand);
        }
        // Wire settings into depot particle manager for particle toggle
        if (depotParticleManager != null) {
            depotParticleManager.setSettingsCommand(settingsCommand);
        }

        // Events registration
        getServer().getPluginManager().registerEvents(new TeamListener(teamService, scoreboardUtil, this), this);
        getServer().getPluginManager().registerEvents(new ChatUtil(teamService, divisionService, partyService, chatChannelManager, regionRenderer, configManager, meritService), this);
        getServer().getPluginManager().registerEvents(regionCaptureListener, this);
        getServer().getPluginManager().registerEvents(meritListener, this);
        getServer().getPluginManager().registerEvents(roadListener, this);
        getServer().getPluginManager().registerEvents(deathListener, this);
        getServer().getPluginManager().registerEvents(supplyPenaltyListener, this);

        if (bluemapAvailable) {
            // Schedule BlueMap updates only when BlueMap is available
            if (gameWorld != null) {
                regionRenderer.scheduleUpdateForOverworld(gameWorld);
            } else {
                getLogger().warning("World not found; skipping BlueMap scheduling.");
            }
            getLogger().info("[Entrenched] Region renderer initialized with round support");

        }

        Bukkit.getPluginManager().registerEvents(new PlayerJoinTeleportListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerTeleportListener(), this);

        // Start periodic task to flush pending road recalculations (every 5 seconds = 100 ticks)
        // Runs ASYNC to avoid blocking main thread during expensive pathfinding
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (roadService != null && roadService.hasPendingRecalculations()) {
                roadService.flushPendingRecalculations();
            }
        }, 100L, 100L); // Initial delay: 100 ticks (5 sec), Period: 100 ticks (5 sec)
        getLogger().info("[Entrenched] Road recalculation scheduler started (async)");
    }

    private void setWorldBorder(World world) {
        if (world == null) return;

        WorldBorder border = world.getWorldBorder();
        border.setCenter(configManager.getBorderCenterX(), configManager.getBorderCenterZ());
        border.setSize(configManager.getBorderSize());
        border.setDamageAmount(configManager.getBorderDamageAmount());
        border.setDamageBuffer(configManager.getBorderDamageBuffer());
        border.setWarningDistance(configManager.getBorderWarningDistance());

        getLogger().info("[Entrenched] World border set: size=" + configManager.getBorderSize() +
                ", center=(" + configManager.getBorderCenterX() + ", " + configManager.getBorderCenterZ() + ")");
    }

    private void startChunkyPregen(World world) {
        if (world == null) return;

        int radiusChunks = configManager.getPregenRadiusChunks();
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky world " + world.getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky center 0 0");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky radius " + radiusChunks);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "chunky start");

            getLogger().info("[Entrenched] Started Chunky pregeneration: center=(0,0), radius=" +
                    radiusChunks + " chunks");
        });
    }

    private final class PlayerJoinTeleportListener implements Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            // Safety check: Don't teleport during world initialization
            if (newRoundInitializer != null && newRoundInitializer.isInitializationInProgress()) {
                event.getPlayer().sendMessage(configManager.getPrefix() + ChatColor.YELLOW +
                        "Server is still initializing. Please wait...");
                return;
            }

            // Get the current game world dynamically
            World gameWorld = roundService.getGameWorld().orElse(null);
            if (gameWorld == null) return;

            Player player = event.getPlayer();

            // Check if player is in the game world
            if (!player.getWorld().getName().startsWith(configManager.getWorldName())) {
                // Player is not in a game world - teleport them to the game world
                player.teleport(gameWorld.getSpawnLocation());
            }

            Optional<String> teamIdOpt = teamService.getPlayerTeam(player.getUniqueId());

            if (teamIdOpt.isPresent()) {
                Optional<Location> spawnOpt = teamService.getTeamSpawn(teamIdOpt.get());
                if (spawnOpt.isPresent()) {
                    player.teleport(spawnOpt.get());
                    player.sendMessage(configManager.getPrefix() + ChatColor.GRAY + "Welcome back! Teleported to team spawn.");
                    return;
                }
            }

            // Teleport to world spawn if no team (if enabled)
            if (configManager.isNoTeamToWorldSpawn()) {
                Location worldSpawn = gameWorld.getSpawnLocation();
                player.teleport(worldSpawn);
            }

            // Show team selection GUI if no team (if enabled)
            if (configManager.isShowTeamGuiOnJoin()) {
                Bukkit.getScheduler().runTaskLater(Trenched.this, () -> {
                    TeamSelectionView view = new TeamSelectionView(teamService, Trenched.this, scoreboardUtil, deathListener);
                    view.createGui().show(player);
                    player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW + "Please select a team to continue!");
                }, configManager.getTeamGuiDelayTicks());
            }

            // TeamListener will schedule a scoreboard update shortly after join; no-op here.

            // Load player settings (particle toggle, etc.)
            if (settingsCommand != null) {
                settingsCommand.loadPlayerPreference(player);
            }
        }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            // Cleanup respawn state
            if (deathListener != null) {
                deathListener.onPlayerQuit(event.getPlayer().getUniqueId());
            }
            // Cleanup region notification state
            if (regionNotificationManager != null) {
                regionNotificationManager.onPlayerQuit(event.getPlayer());
            }
            // Unload player settings
            if (settingsCommand != null) {
                settingsCommand.unloadPlayer(event.getPlayer().getUniqueId());
            }
        }

    }

    private final class PlayerTeleportListener implements Listener {

        @EventHandler
        public void onPlayerTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
            if (scoreboardUtil == null) return;

            Player player = event.getPlayer();

            // Schedule the scoreboard update for next tick to ensure teleport is complete
            Bukkit.getScheduler().runTask(Trenched.this, () -> {
                scoreboardUtil.updatePlayerScoreboard(player);
            });
        }
    }

    /**
     * If there's an active round whose world name differs from the default (server.properties),
     * Bukkit won't auto-load it on startup.  This method:
     * <ol>
     *   <li>Loads the game world from disk via {@link WorldCreator} so Bukkit knows about it.</li>
     *   <li>Patches {@code server.properties} so the <em>next</em> restart uses the game world
     *       directly â€” preventing a stale "world" folder from being created.</li>
     *   <li>Removes BlueMap's auto-generated map configs for the old "world" so it doesn't
     *       appear in the BlueMap web UI.</li>
     * </ol>
     */
    private void ensureGameWorldLoaded() {
        Optional<org.flintstqne.entrenched.RoundLogic.Round> roundOpt = roundService.getCurrentRound();
        if (roundOpt.isEmpty()) return;

        String worldName = roundOpt.get().worldName();
        if (worldName == null || worldName.isEmpty()) return;

        // â”€â”€ 1. Load the game world into Bukkit if it isn't already â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (Bukkit.getWorld(worldName) == null) {
            java.io.File worldFolder = new java.io.File(Bukkit.getWorldContainer(), worldName);
            if (!worldFolder.isDirectory()) {
                getLogger().warning("[Trenched] Game world folder '" + worldName +
                        "' not found on disk â€” cannot load. A new round may be needed.");
                return;
            }

            getLogger().info("[Trenched] Loading game world '" + worldName + "' from disk...");

            WorldCreator creator = new WorldCreator(worldName);
            creator.environment(World.Environment.NORMAL);

            String generatorName = configManager.getWorldGenerator();
            if (generatorName != null && !generatorName.isEmpty()) {
                creator.generator(generatorName);
                getLogger().info("[Trenched]   Using generator: " + generatorName);
            }

            World loaded = Bukkit.createWorld(creator);
            if (loaded != null) {
                getLogger().info("[Trenched] Game world '" + worldName + "' loaded successfully (seed=" + loaded.getSeed() + ").");
            } else {
                getLogger().severe("[Trenched] Failed to load game world '" + worldName + "'!");
                return;
            }
        } else {
            getLogger().info("[Trenched] Game world '" + worldName + "' is already loaded.");
        }

        // â”€â”€ 2. Patch server.properties so the next restart uses the game world â”€â”€â”€â”€
        updateServerPropertiesLevelName(worldName);

        // â”€â”€ 3. Remove BlueMap map configs for stale "world" so it doesn't render â”€â”€
        removeStaleBlueMapConfigs(worldName);
    }

    /**
     * Rewrites {@code server.properties} to set {@code level-name} to the given world name.
     * Takes effect on the next server restart â€” prevents the server from auto-creating a
     * stale "world" folder when the actual game world is {@code world_<timestamp>}.
     */
    private void updateServerPropertiesLevelName(String gameWorldName) {
        java.io.File propsFile = new java.io.File("server.properties");
        if (!propsFile.exists()) {
            getLogger().warning("[Trenched] server.properties not found â€” cannot update level-name.");
            return;
        }

        try {
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
                props.load(fis);
            }

            String currentLevel = props.getProperty("level-name", "world");
            if (currentLevel.equals(gameWorldName)) {
                return; // Already correct
            }

            getLogger().info("[Trenched] Updating server.properties level-name: '" +
                    currentLevel + "' â†’ '" + gameWorldName + "' (takes effect on next restart)");

            // Read the raw file, replace the line, and write back to preserve comments/ordering
            java.nio.file.Path path = propsFile.toPath();
            java.util.List<String> lines = java.nio.file.Files.readAllLines(path);
            java.util.List<String> updated = new java.util.ArrayList<>(lines.size());
            boolean replaced = false;
            for (String line : lines) {
                if (line.startsWith("level-name=")) {
                    updated.add("level-name=" + gameWorldName);
                    replaced = true;
                } else {
                    updated.add(line);
                }
            }
            if (!replaced) {
                updated.add("level-name=" + gameWorldName);
            }
            java.nio.file.Files.write(path, updated);

        } catch (Exception e) {
            getLogger().warning("[Trenched] Failed to update server.properties: " + e.getMessage());
        }
    }

    /**
     * Removes BlueMap's auto-generated map configuration files for worlds that are NOT the
     * active game world.  BlueMap auto-discovers all loaded Bukkit worlds and creates
     * {@code .conf} files under {@code plugins/BlueMap/maps/}.  Removing the stale configs
     * and scheduling a BlueMap reload hides the old "world" from the web UI.
     */
    private void removeStaleBlueMapConfigs(String activeGameWorld) {
        java.io.File blueMapMapsDir = new java.io.File("plugins/BlueMap/maps");
        if (!blueMapMapsDir.isDirectory()) return; // BlueMap not installed or not yet initialized

        java.io.File[] configFiles = blueMapMapsDir.listFiles((dir, name) -> name.endsWith(".conf"));
        if (configFiles == null) return;

        boolean removedAny = false;
        for (java.io.File conf : configFiles) {
            String fileName = conf.getName().replace(".conf", "");
            // BlueMap names its default map configs after the world, e.g. "world.conf",
            // "world_nether.conf", "world_the_end.conf".  Remove any that start with
            // the default "world" name but DON'T match the active game world.
            if (fileName.startsWith("world") && !fileName.startsWith(activeGameWorld)) {
                if (conf.delete()) {
                    getLogger().info("[Trenched] Removed stale BlueMap map config: " + conf.getName());
                    removedAny = true;
                }
            }
        }

        // Also clean up BlueMap's web/maps data directory for the stale world
        java.io.File blueMapWebMaps = new java.io.File("plugins/BlueMap/web/maps");
        if (blueMapWebMaps.isDirectory()) {
            java.io.File[] webDirs = blueMapWebMaps.listFiles(java.io.File::isDirectory);
            if (webDirs != null) {
                for (java.io.File dir : webDirs) {
                    if (dir.getName().startsWith("world") && !dir.getName().startsWith(activeGameWorld)) {
                        getLogger().info("[Trenched] Removing stale BlueMap web data: " + dir.getName());
                        deleteDirectory(dir);
                        removedAny = true;
                    }
                }
            }
        }

        if (removedAny) {
            // Schedule a BlueMap reload after 5 seconds to pick up the config changes.
            // This must run after BlueMap has finished its own initialization.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                getLogger().info("[Trenched] Triggering BlueMap reload to clear stale world maps...");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bluemap reload");
            }, 20L * 10); // 10 seconds
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteDirectory(java.io.File dir) {
        java.io.File[] children = dir.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    private void checkAndInitializeRound(World world) {
        Optional<org.flintstqne.entrenched.RoundLogic.Round> currentRound = roundService.getCurrentRound();

        if (currentRound.isPresent()) {
            org.flintstqne.entrenched.RoundLogic.Round round = currentRound.get();
            getLogger().info("[Entrenched] ========================================");
            getLogger().info("[Entrenched] Continuing with existing round:");
            getLogger().info("[Entrenched]   Round ID: " + round.roundId());
            getLogger().info("[Entrenched]   Status: " + round.status());
            getLogger().info("[Entrenched]   Phase: " + round.currentPhase());
            getLogger().info("[Entrenched]   World Seed: " + round.worldSeed());
            getLogger().info("[Entrenched] ========================================");

            // Load region names from database for this existing round
            if (regionRenderer != null) {
                boolean loaded = regionRenderer.loadNamesForCurrentRound();
                if (loaded) {
                    getLogger().info("[Entrenched] Region names loaded from database");
                } else {
                    getLogger().info("[Entrenched] No saved region names found - generating and persisting now");
                    regionRenderer.generateAndPersistNamesForCurrentRound(world);
                }
            }

            // Start region notification manager for existing round
            regionNotificationManager.start();
        } else {
            getLogger().info("[Entrenched] ========================================");
            getLogger().info("[Entrenched] No active round found - starting new round");

            // Use the world seed if available, otherwise generate a random one
            long worldSeed = (world != null) ? world.getSeed() : System.currentTimeMillis();

            org.flintstqne.entrenched.RoundLogic.Round newRound = roundService.startNewRound(worldSeed);

            // Initialize regions for the new round
            regionService.initializeRegionsForRound(
                    newRound.roundId(),
                    configManager.getRegionRedHome(),
                    configManager.getRegionBlueHome()
            );
            getLogger().info("[Entrenched] Regions initialized for new round");

            // Start region notification manager
            regionNotificationManager.start();

            getLogger().info("[Entrenched]   New Round ID: " + newRound.roundId());
            getLogger().info("[Entrenched]   Status: " + newRound.status());
            getLogger().info("[Entrenched]   Phase: " + newRound.currentPhase());
            getLogger().info("[Entrenched]   World Seed: " + newRound.worldSeed());
            getLogger().info("[Entrenched] Round started automatically!");
            getLogger().info("[Entrenched] ========================================");
        }
    }

    /**
     * Loads placed block caches for regions that already have active building objectives.
     * Called on startup to restore tracking state after a restart.
     */
    private void loadTrackedBlocksForActiveObjectives() {
        if (placedBlockTracker == null || objectiveService == null) return;

        // Load for all regions that have tracked data in the DB
        for (String regionId : placedBlockDb.getTrackedRegions()) {
            placedBlockTracker.loadRegion(regionId);
        }
    }

    @Override
    public void onDisable() {
        // Unregister BlueMap lifecycle callbacks
        BlueMapIntegration.shutdown();

        if (scoreboardUtil != null) scoreboardUtil.stopUpdateTask();
        if (objectiveUIManager != null) objectiveUIManager.stop();
        if (objectiveListener != null) objectiveListener.stop();
        if (buildingBenefitManager != null) buildingBenefitManager.stop();
        if (garrisonSpawnService != null) garrisonSpawnService.stop();
        if (endgameManager != null) endgameManager.stop();
        if (regionNotificationManager != null) regionNotificationManager.stop();
        if (phaseScheduler != null) phaseScheduler.stop();

        // Stop stat system
        if (statListener != null) statListener.stopTimeTracking();
        if (statService != null) statService.stop();
        if (statApiServer != null) statApiServer.stop();
        if (statDb != null) statDb.close();

        // Close link database
        if (linkDb != null) {
            try { linkDb.close(); } catch (Exception e) {
                getLogger().warning("[Trenched] Error closing link database: " + e.getMessage());
            }
        }

        // Unregister depot recipes
        if (depotRecipes != null) depotRecipes.unregisterRecipes();

        // Stop depot particle manager
        if (depotParticleManager != null) depotParticleManager.stop();

        // Stop placed block tracker (flushes pending writes)
        if (placedBlockTracker != null) placedBlockTracker.stop();

        // Close databases
        if (placedBlockDb != null) placedBlockDb.close();
        if (endgameDb != null) endgameDb.close();
        if (objectiveDb != null) objectiveDb.close();
        if (roadDb != null) roadDb.close();
        if (regionDb != null) regionDb.close();
        if (meritDb != null) meritDb.close();
        if (partyDb != null) partyDb.close();
        if (divisionDb != null) divisionDb.close();
        if (teamDb != null) teamDb.close();
        if (roundDb != null) roundDb.close();
        getLogger().info("Entrenched disabled.");
    }

    /**
     * Gets the ConfigManager instance.
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Gets the PhaseScheduler instance.
     */
    public PhaseScheduler getPhaseScheduler() {
        return phaseScheduler;
    }

    /**
     * Gets the DepotService instance, or null if depots are disabled.
     */
    public DepotService getDepotService() {
        return depotService;
    }

    /**
     * Gets the DepotItem factory, or null if depots are disabled.
     */
    public DepotItem getDepotItem() {
        return depotItem;
    }

    /**
     * Checks if the Division Depot system is enabled.
     */
    public boolean isDepotSystemEnabled() {
        return depotService != null;
    }

    /**
     * Gets the player settings command, or null if not initialized.
     */
    public org.flintstqne.entrenched.Utils.SettingsCommand getSettingsCommand() {
        return settingsCommand;
    }

    /**
     * Starts the influence decay scheduler.
     * Runs every minute to:
     * - Decay IP in contested regions with no activity
     * - Check and remove expired fortifications
     */
    private void startInfluenceDecayScheduler() {
        double decayRate = configManager.getRegionInfluenceDecayPerMinute();

        if (decayRate <= 0) {
            getLogger().info("[Entrenched] Influence decay disabled (rate = 0)");
            return;
        }

        // Run every minute (1200 ticks = 60 seconds)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Only run if there's an active round
            if (roundService.getCurrentRound().isEmpty()) return;

            // Apply influence decay to contested regions
            regionService.applyInfluenceDecay();

            // Update fortification status (remove expired)
            regionService.updateFortificationStatus();

        }, 1200L, 1200L); // Initial delay: 1 minute, repeat every 1 minute

        getLogger().info("[Entrenched] Influence decay scheduler started (rate: " + decayRate + " IP/minute)");
    }

    /**
     * Handles round end callback from the endgame manager.
     * @param winner "RED", "BLUE", or "DRAW"
     */
    private void handleRoundEnd(String winner) {
        getLogger().info("[Endgame] Round ended - winner: " + winner);

        // Capture round reference BEFORE ending it (endRound clears the active round)
        Optional<org.flintstqne.entrenched.RoundLogic.Round> roundOpt = roundService.getCurrentRound();

        // Stop the phase scheduler
        if (phaseScheduler != null) {
            phaseScheduler.stop();
        }

        // Stop the endgame manager
        if (endgameManager != null) {
            endgameManager.stop();
        }

        // End the round in the round service
        roundService.endRound(winner);

        // Log the result
        if ("DRAW".equalsIgnoreCase(winner)) {
            getLogger().info("[Endgame] Round ended in a DRAW");
        } else {
            getLogger().info("[Endgame] Round won by " + winner.toUpperCase());
        }

        // Clear all player-placed block tracking data for the finished round
        if (placedBlockTracker != null) {
            placedBlockTracker.clearAll();
            getLogger().info("[PlacedBlocks] Cleared all tracking data for round end");
        }

        // Save round-end stats (MVP, rounds_won, round metadata)
        if (statService != null && roundOpt.isPresent()) {
            org.flintstqne.entrenched.RoundLogic.Round round = roundOpt.get();
            long endTime = System.currentTimeMillis();
            statService.saveRoundEnd(round.roundId(), winner, round.startTime(), endTime);
            getLogger().info("[Stats] Round " + round.roundId() + " end stats saved");

            // Broadcast round-end summary to all players
            broadcastRoundSummary(round.roundId(), winner, round.startTime(), endTime);
        }
    }

    /**
     * Broadcasts a round-end summary to all online players.
     */
    private void broadcastRoundSummary(int roundId, String winner, long startTime, long endTime) {
        long durationMinutes = (endTime - startTime) / (1000 * 60);
        long hours = durationMinutes / 60;
        long mins = durationMinutes % 60;
        String durationStr = hours > 0 ? hours + "h " + mins + "m" : mins + "m";

        // Header
        net.kyori.adventure.text.Component divider = net.kyori.adventure.text.Component.text(
                "â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”â”", net.kyori.adventure.text.format.NamedTextColor.GOLD);

        net.kyori.adventure.text.Component header = net.kyori.adventure.text.Component.text(
                "âš” ROUND OVER âš”", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);

        // Winner line
        net.kyori.adventure.text.Component winnerLine;
        if ("DRAW".equalsIgnoreCase(winner)) {
            winnerLine = net.kyori.adventure.text.Component.text("Result: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text("DRAW", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
        } else {
            net.kyori.adventure.text.format.NamedTextColor winColor = "RED".equalsIgnoreCase(winner)
                    ? net.kyori.adventure.text.format.NamedTextColor.RED
                    : net.kyori.adventure.text.format.NamedTextColor.BLUE;
            winnerLine = net.kyori.adventure.text.Component.text("Winner: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text(winner.toUpperCase() + " TEAM", winColor)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
        }

        // Duration
        net.kyori.adventure.text.Component durationLine = net.kyori.adventure.text.Component.text(
                "Duration: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text(durationStr, net.kyori.adventure.text.format.NamedTextColor.WHITE));

        // Territory counts
        int[] territory = endgameManager != null ? endgameManager.getTerritoryCount() : new int[]{0, 0};
        net.kyori.adventure.text.Component territoryLine = net.kyori.adventure.text.Component.text(
                "Territory: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(net.kyori.adventure.text.Component.text(territory[0] + " ", net.kyori.adventure.text.format.NamedTextColor.RED))
                .append(net.kyori.adventure.text.Component.text("vs ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .append(net.kyori.adventure.text.Component.text(territory[1] + " ", net.kyori.adventure.text.format.NamedTextColor.BLUE))
                .append(net.kyori.adventure.text.Component.text("regions", net.kyori.adventure.text.format.NamedTextColor.GRAY));

        // MVP
        UUID mvpUuid = statService.calculateMVP(roundId);
        net.kyori.adventure.text.Component mvpLine;
        if (mvpUuid != null) {
            org.bukkit.entity.Player mvpPlayer = Bukkit.getPlayer(mvpUuid);
            String mvpName = mvpPlayer != null ? mvpPlayer.getName() : "Unknown";
            // Try to get name from stats if player is offline
            if (mvpPlayer == null) {
                java.util.Optional<org.flintstqne.entrenched.StatLogic.PlayerStats> mvpStats =
                        statService.getPlayerStats(mvpUuid);
                if (mvpStats.isPresent()) {
                    mvpName = mvpStats.get().getLastKnownName();
                }
            }
            java.util.Optional<org.flintstqne.entrenched.StatLogic.PlayerStats> roundStats =
                    statService.getPlayerRoundStats(mvpUuid, roundId);
            String scoreStr = roundStats.map(s -> String.format(" (%.0f pts)", s.getMVPScore())).orElse("");

            mvpLine = net.kyori.adventure.text.Component.text("MVP: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text("â˜… " + mvpName + scoreStr,
                            net.kyori.adventure.text.format.NamedTextColor.GOLD)
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
        } else {
            mvpLine = net.kyori.adventure.text.Component.text("MVP: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text("None", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }

        // Team stats
        org.flintstqne.entrenched.StatLogic.TeamStats redStats = statService.getTeamStats("red", roundId);
        org.flintstqne.entrenched.StatLogic.TeamStats blueStats = statService.getTeamStats("blue", roundId);

        net.kyori.adventure.text.Component redLine = net.kyori.adventure.text.Component.text(
                "RED: ", net.kyori.adventure.text.format.NamedTextColor.RED)
                .append(net.kyori.adventure.text.Component.text(String.format("%.0f kills | %.0f objectives | %d players",
                        redStats.getTotal(org.flintstqne.entrenched.StatLogic.StatCategory.KILLS),
                        redStats.getTotal(org.flintstqne.entrenched.StatLogic.StatCategory.OBJECTIVES_COMPLETED),
                        redStats.playerCount()), net.kyori.adventure.text.format.NamedTextColor.WHITE));

        net.kyori.adventure.text.Component blueLine = net.kyori.adventure.text.Component.text(
                "BLU: ", net.kyori.adventure.text.format.NamedTextColor.BLUE)
                .append(net.kyori.adventure.text.Component.text(String.format("%.0f kills | %.0f objectives | %d players",
                        blueStats.getTotal(org.flintstqne.entrenched.StatLogic.StatCategory.KILLS),
                        blueStats.getTotal(org.flintstqne.entrenched.StatLogic.StatCategory.OBJECTIVES_COMPLETED),
                        blueStats.playerCount()), net.kyori.adventure.text.format.NamedTextColor.WHITE));

        // Send title + chat summary to all players
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            // Title
            net.kyori.adventure.text.Component titleMain;
            if ("DRAW".equalsIgnoreCase(winner)) {
                titleMain = net.kyori.adventure.text.Component.text("DRAW",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
            } else {
                net.kyori.adventure.text.format.NamedTextColor winColor = "RED".equalsIgnoreCase(winner)
                        ? net.kyori.adventure.text.format.NamedTextColor.RED
                        : net.kyori.adventure.text.format.NamedTextColor.BLUE;
                titleMain = net.kyori.adventure.text.Component.text(winner.toUpperCase() + " WINS!",
                        winColor).decorate(net.kyori.adventure.text.format.TextDecoration.BOLD);
            }

            net.kyori.adventure.text.Component titleSub = net.kyori.adventure.text.Component.text(
                    "Round " + roundId + " Complete", net.kyori.adventure.text.format.NamedTextColor.GRAY);

            player.showTitle(net.kyori.adventure.title.Title.title(titleMain, titleSub,
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofSeconds(5),
                            java.time.Duration.ofSeconds(2))));

            // Chat summary
            player.sendMessage(divider);
            player.sendMessage(header);
            player.sendMessage(winnerLine);
            player.sendMessage(durationLine);
            player.sendMessage(territoryLine);
            player.sendMessage(mvpLine);
            player.sendMessage(net.kyori.adventure.text.Component.empty());
            player.sendMessage(redLine);
            player.sendMessage(blueLine);
            player.sendMessage(divider);

            // Sound effects
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    /**
     * Gets the RoundEndgameManager instance.
     */
    public RoundEndgameManager getEndgameManager() {
        return endgameManager;
    }
}
