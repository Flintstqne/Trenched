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
import org.flintstqne.entrenched.MeritLogic.*;
import org.flintstqne.entrenched.PartyLogic.*;
import org.flintstqne.entrenched.RegionLogic.*;
import org.flintstqne.entrenched.RoadLogic.*;
import org.flintstqne.entrenched.RoundLogic.RoundCommand;
import org.flintstqne.entrenched.RoundLogic.RoundDb;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.RoundLogic.SqlRoundService;
import org.flintstqne.entrenched.RoundLogic.NewRoundInitializer;
import org.flintstqne.entrenched.RoundLogic.PhaseScheduler;
import org.flintstqne.entrenched.TeamLogic.*;
import org.flintstqne.entrenched.Utils.ChatUtil;
import org.flintstqne.entrenched.Utils.PlaceholderExpansion;
import org.flintstqne.entrenched.Utils.ScoreboardUtil;

import java.util.Objects;
import java.util.Optional;

public final class Trenched extends JavaPlugin {

    public static final String WORLD_NAME = "world";

    private ConfigManager configManager;
    private boolean bluemapAvailable;
    private static TeamService teamService;
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
            bluemapAvailable = BlueMapIntegration.initialize(this);
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
        getLogger().info("[TerrainGen] Division system initialized");

        // Initialize Party system
        partyDb = new PartyDb(this);
        partyService = new SqlPartyService(partyDb, roundService, teamService, configManager);
        getLogger().info("[TerrainGen] Party system initialized");

        // Initialize Region Capture system
        regionDb = new RegionDb(this);
        SqlRegionService sqlRegionService = new SqlRegionService(regionDb, roundService, configManager);
        regionService = sqlRegionService;

        // Instantiate RegionRenderer AFTER regionService so it can color captured regions
        regionRenderer = new RegionRenderer(this, roundService, regionService);

        regionNotificationManager = new RegionNotificationManager(this, regionService, teamService, configManager, regionRenderer);
        regionCaptureListener = new RegionCaptureListener(regionService, teamService, configManager, regionRenderer);

        // Connect capture callback to notification manager
        sqlRegionService.setCaptureCallback((regionId, newOwner, previousOwner) -> {
            regionNotificationManager.broadcastCapture(regionId, newOwner, previousOwner);
        });
        getLogger().info("[TerrainGen] Region capture system initialized");

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
        phaseScheduler.start();

        // Create the NewRoundInitializer for /round new command (needs phaseScheduler)
        newRoundInitializer = new NewRoundInitializer(
                this, roundService, teamService, teamDb, regionRenderer, scoreboardUtil, configManager, phaseScheduler
        );

        // Initialize Road/Supply Line system
        roadDb = new RoadDb(this);
        roadService = new SqlRoadService(roadDb, roundService, regionService, configManager);
        roadListener = new RoadListener(this, roadService, teamService, configManager, regionRenderer);
        deathListener = new DeathListener(this, roadService, teamService, configManager);
        supplyPenaltyListener = new SupplyPenaltyListener(roadService, teamService, deathListener);

        // Connect scoreboard to road service for accurate supply display
        scoreboardUtil.setRoadService(roadService);

        // Connect road service to region service for accurate supply calculations
        sqlRegionService.setRoadService(roadService);

        // Connect road disruption callback to notification manager
        roadListener.setDisruptionCallback((team, affectedRegions, destroyedBlock) -> {
            String sourceRegion = destroyedBlock != null ? destroyedBlock.regionId() : null;
            regionNotificationManager.broadcastSupplyDisrupted(team, affectedRegions, sourceRegion);
        });
        getLogger().info("[TerrainGen] Road/supply line system initialized");

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
        getLogger().info("[TerrainGen] Merit system initialized");

        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new org.flintstqne.entrenched.Utils.PlaceholderExpansion(meritService, teamService, divisionService, configManager).register();
            getLogger().info("[TerrainGen] PlaceholderAPI expansion registered");
        } else {
            getLogger().info("[TerrainGen] PlaceholderAPI not found - placeholders not available");
        }

        // Recalculate supply for both teams on startup to ensure correct values
        // This is especially important after server restarts when road data persists
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            getLogger().info("[TerrainGen] Recalculating supply levels on startup...");
            roadService.recalculateSupply("red");
            roadService.recalculateSupply("blue");
            getLogger().info("[TerrainGen] Supply recalculation complete");
        }, 40L); // 2 second delay to ensure everything is loaded

        // Initialize Chat Channel Manager
        chatChannelManager = new ChatChannelManager();
        getLogger().info("[Trenched] Chat channel system initialized");

        // Commands registration
        RoundCommand roundCommand = new RoundCommand(roundService, teamService, regionRenderer, scoreboardUtil, phaseScheduler, configManager);
        roundCommand.setNewRoundInitializer(newRoundInitializer);
        var roundCmd = Objects.requireNonNull(getCommand("round"), "Command `round` missing from plugin.yml");
        roundCmd.setExecutor(roundCommand);
        roundCmd.setTabCompleter(roundCommand);

        TeamCommand teamCommand = new TeamCommand(teamService, scoreboardUtil);
        var teamCmd = Objects.requireNonNull(getCommand("team"), "Command `team` missing from plugin.yml");
        teamCmd.setExecutor(teamCommand);
        teamCmd.setTabCompleter(teamCommand);

        Objects.requireNonNull(getCommand("teamgui"), "Command `teamgui` missing from plugin.yml")
                .setExecutor(new TeamGuiCommand(teamService, this, scoreboardUtil));

        // Division commands
        DivisionCommand divisionCommand = new DivisionCommand(divisionService, teamService, configManager);
        var divisionCmd = Objects.requireNonNull(getCommand("division"), "Command `division` missing from plugin.yml");
        divisionCmd.setExecutor(divisionCommand);
        divisionCmd.setTabCompleter(divisionCommand);

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
            getLogger().info("[TerrainGen] Region renderer initialized with round support");

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
        getLogger().info("[TerrainGen] Road recalculation scheduler started (async)");
    }

    private void setWorldBorder(World world) {
        if (world == null) return;

        WorldBorder border = world.getWorldBorder();
        border.setCenter(configManager.getBorderCenterX(), configManager.getBorderCenterZ());
        border.setSize(configManager.getBorderSize());
        border.setDamageAmount(configManager.getBorderDamageAmount());
        border.setDamageBuffer(configManager.getBorderDamageBuffer());
        border.setWarningDistance(configManager.getBorderWarningDistance());

        getLogger().info("[TerrainGen] World border set: size=" + configManager.getBorderSize() +
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

            getLogger().info("[TerrainGen] Started Chunky pregeneration: center=(0,0), radius=" +
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
                    TeamSelectionView view = new TeamSelectionView(teamService, Trenched.this, scoreboardUtil);
                    view.createGui().show(player);
                    player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW + "Please select a team to continue!");
                }, configManager.getTeamGuiDelayTicks());
            }

            // TeamListener will schedule a scoreboard update shortly after join; no-op here.
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

    private void checkAndInitializeRound(World world) {
        Optional<org.flintstqne.entrenched.RoundLogic.Round> currentRound = roundService.getCurrentRound();

        if (currentRound.isPresent()) {
            org.flintstqne.entrenched.RoundLogic.Round round = currentRound.get();
            getLogger().info("[TerrainGen] ========================================");
            getLogger().info("[TerrainGen] Continuing with existing round:");
            getLogger().info("[TerrainGen]   Round ID: " + round.roundId());
            getLogger().info("[TerrainGen]   Status: " + round.status());
            getLogger().info("[TerrainGen]   Phase: " + round.currentPhase());
            getLogger().info("[TerrainGen]   World Seed: " + round.worldSeed());
            getLogger().info("[TerrainGen] ========================================");

            // Load region names from database for this existing round
            if (regionRenderer != null) {
                boolean loaded = regionRenderer.loadNamesForCurrentRound();
                if (loaded) {
                    getLogger().info("[TerrainGen] Region names loaded from database");
                } else {
                    getLogger().info("[TerrainGen] No saved region names found - generating and persisting now");
                    regionRenderer.generateAndPersistNamesForCurrentRound(world);
                }
            }

            // Start region notification manager for existing round
            regionNotificationManager.start();
        } else {
            getLogger().info("[TerrainGen] ========================================");
            getLogger().info("[TerrainGen] No active round found - starting new round");

            // Use the world seed if available, otherwise generate a random one
            long worldSeed = (world != null) ? world.getSeed() : System.currentTimeMillis();

            org.flintstqne.entrenched.RoundLogic.Round newRound = roundService.startNewRound(worldSeed);

            // Initialize regions for the new round
            regionService.initializeRegionsForRound(
                    newRound.roundId(),
                    configManager.getRegionRedHome(),
                    configManager.getRegionBlueHome()
            );
            getLogger().info("[TerrainGen] Regions initialized for new round");

            // Start region notification manager
            regionNotificationManager.start();

            getLogger().info("[TerrainGen]   New Round ID: " + newRound.roundId());
            getLogger().info("[TerrainGen]   Status: " + newRound.status());
            getLogger().info("[TerrainGen]   Phase: " + newRound.currentPhase());
            getLogger().info("[TerrainGen]   World Seed: " + newRound.worldSeed());
            getLogger().info("[TerrainGen] Round started automatically!");
            getLogger().info("[TerrainGen] ========================================");
        }
    }

    @Override
    public void onDisable() {
        if (scoreboardUtil != null) scoreboardUtil.stopUpdateTask();
        if (regionNotificationManager != null) regionNotificationManager.stop();
        if (phaseScheduler != null) phaseScheduler.stop();
        if (roadDb != null) roadDb.close();
        if (regionDb != null) regionDb.close();
        if (partyDb != null) partyDb.close();
        if (divisionDb != null) divisionDb.close();
        if (teamDb != null) teamDb.close();
        if (roundDb != null) roundDb.close();
        getLogger().info("TerrainGen disabled.");
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
}
