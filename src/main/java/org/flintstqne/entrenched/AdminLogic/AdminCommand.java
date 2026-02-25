package org.flintstqne.entrenched.AdminLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.DivisionLogic.DepotItem;
import org.flintstqne.entrenched.DivisionLogic.DepotLocation;
import org.flintstqne.entrenched.DivisionLogic.DepotService;
import org.flintstqne.entrenched.DivisionLogic.Division;
import org.flintstqne.entrenched.DivisionLogic.DivisionService;
import org.flintstqne.entrenched.MeritLogic.MeritRank;
import org.flintstqne.entrenched.MeritLogic.MeritService;
import org.flintstqne.entrenched.MeritLogic.PlayerMeritData;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoadLogic.DeathListener;
import org.flintstqne.entrenched.RoadLogic.RoadBlock;
import org.flintstqne.entrenched.RoadLogic.RoadService;
import org.flintstqne.entrenched.RoadLogic.SupplyLevel;
import org.flintstqne.entrenched.RoundLogic.NewRoundInitializer;
import org.flintstqne.entrenched.RoundLogic.PhaseScheduler;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.JoinReason;
import org.flintstqne.entrenched.TeamLogic.TeamService;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveCategory;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveService;
import org.flintstqne.entrenched.ObjectiveLogic.ObjectiveType;
import org.flintstqne.entrenched.ObjectiveLogic.RegionObjective;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin commands for server management.
 * Permission: entrenched.admin
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "entrenched.admin";

    private final JavaPlugin plugin;
    private final RoundService roundService;
    private final TeamService teamService;
    private final RegionService regionService;
    private final RoadService roadService;
    private final DeathListener deathListener;
    private final PhaseScheduler phaseScheduler;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer;
    private final MeritService meritService;
    private ObjectiveService objectiveService;
    private NewRoundInitializer newRoundInitializer;
    private DepotService depotService;
    private DepotItem depotItem;
    private DivisionService divisionService;

    public AdminCommand(JavaPlugin plugin, RoundService roundService, TeamService teamService, RegionService regionService,
                        RoadService roadService, DeathListener deathListener, PhaseScheduler phaseScheduler,
                        ConfigManager configManager, RegionRenderer regionRenderer, MeritService meritService) {
        this.plugin = plugin;
        this.roundService = roundService;
        this.teamService = teamService;
        this.regionService = regionService;
        this.roadService = roadService;
        this.deathListener = deathListener;
        this.phaseScheduler = phaseScheduler;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
        this.meritService = meritService;
    }

    public void setNewRoundInitializer(NewRoundInitializer initializer) {
        this.newRoundInitializer = initializer;
    }

    public void setObjectiveService(ObjectiveService objectiveService) {
        this.objectiveService = objectiveService;
    }

    public void setDepotService(DepotService depotService, DepotItem depotItem) {
        this.depotService = depotService;
        this.depotItem = depotItem;
    }

    public void setDivisionService(DivisionService divisionService) {
        this.divisionService = divisionService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use admin commands.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            // Round commands
            case "round" -> handleRound(sender, args);
            case "phase" -> handlePhase(sender, args);

            // Region commands
            case "region" -> handleRegion(sender, args);

            // Team commands
            case "team" -> handleTeam(sender, args);

            // Player commands
            case "player" -> handlePlayer(sender, args);

            // Supply commands
            case "supply" -> handleSupply(sender, args);

            // Merit commands
            case "merit" -> handleMerit(sender, args);

            // Objective commands
            case "objective", "obj" -> handleObjective(sender, args);

            // Depot commands
            case "depot" -> handleDepot(sender, args);

            // Server commands
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);

            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                sendHelp(sender);
                yield true;
            }
        };
    }

    // ==================== ROUND COMMANDS ====================

    private boolean handleRound(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin round <new|end|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "new" -> {
                if (newRoundInitializer == null) {
                    sender.sendMessage(ChatColor.RED + "Round initializer not available.");
                    break;
                }
                sender.sendMessage(configManager.getPrefix() + ChatColor.YELLOW + "Starting new round...");
                newRoundInitializer.initiateNewRound(sender);
            }
            case "end" -> {
                String winner = args.length > 2 ? args[2].toLowerCase() : null;
                if (winner != null && !winner.equals("red") && !winner.equals("blue") && !winner.equals("draw")) {
                    sender.sendMessage(ChatColor.RED + "Winner must be: red, blue, or draw");
                    break;
                }
                roundService.endRound(winner);
                if (phaseScheduler != null) phaseScheduler.stop();
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Round ended" +
                        (winner != null ? " with winner: " + winner : ""));
            }
            case "info" -> {
                Optional<Round> roundOpt = roundService.getCurrentRound();
                if (roundOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No active round.");
                    break;
                }
                Round round = roundOpt.get();
                sender.sendMessage(ChatColor.GOLD + "=== Round Info ===");
                sender.sendMessage(ChatColor.GRAY + "ID: " + ChatColor.WHITE + round.roundId());
                sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.WHITE + round.status());
                sender.sendMessage(ChatColor.GRAY + "Phase: " + ChatColor.WHITE + round.currentPhase());
                sender.sendMessage(ChatColor.GRAY + "World Seed: " + ChatColor.WHITE + round.worldSeed());
                sender.sendMessage(ChatColor.GRAY + "Started: " + ChatColor.WHITE + new Date(round.startTime()));
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown round action: " + action);
            }
        }
        return true;
    }

    // ==================== PHASE COMMANDS ====================

    private boolean handlePhase(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin phase <advance|set|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "advance" -> {
                RoundService.PhaseResult result = roundService.advancePhase();
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Phase advance result: " + result);
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin phase set <number>");
                    break;
                }
                try {
                    int phase = Integer.parseInt(args[2]);
                    if (phase < 1 || phase > configManager.getMaxPhases()) {
                        sender.sendMessage(ChatColor.RED + "Phase must be between 1 and " + configManager.getMaxPhases());
                        break;
                    }
                    // Set phase directly (would need to add this method to RoundService)
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Phase set to: " + phase);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid phase number.");
                }
            }
            case "info" -> {
                Optional<Round> roundOpt = roundService.getCurrentRound();
                if (roundOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No active round.");
                    break;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Phase Info ===");
                sender.sendMessage(ChatColor.GRAY + "Current Phase: " + ChatColor.WHITE + roundOpt.get().currentPhase());
                sender.sendMessage(ChatColor.GRAY + "Max Phases: " + ChatColor.WHITE + configManager.getMaxPhases());
                sender.sendMessage(ChatColor.GRAY + "Phase Duration: " + ChatColor.WHITE + configManager.getPhaseDurationMinutes() + " minutes");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown phase action: " + action);
            }
        }// Set phase directly (would need to add this method to RoundService)
        return true;
    }

    // ==================== REGION COMMANDS ====================

    private boolean handleRegion(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin region <capture|reset|setstate|setowner|addip|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "capture" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region capture <regionId> <red|blue>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                String team = args[3].toLowerCase();
                if (!team.equals("red") && !team.equals("blue")) {
                    sender.sendMessage(ChatColor.RED + "Team must be: red or blue");
                    yield true;
                }

                // Force capture region
                long fortifyUntil = System.currentTimeMillis() + (configManager.getRegionFortificationMinutes() * 60 * 1000);
                regionService.captureRegion(regionId, team, fortifyUntil);
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Region " + regionId + " captured by " + team);
                yield true;
            }
            case "reset" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region reset <regionId|all>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                if (regionId.equals("ALL")) {
                    // Expire all objectives first
                    if (objectiveService != null) {
                        for (RegionStatus status : regionService.getAllRegionStatuses()) {
                            objectiveService.expireObjectivesInRegion(status.regionId());
                        }
                        plugin.getLogger().info("[Admin] Expired objectives in all regions");
                    } else {
                        plugin.getLogger().warning("[Admin] ObjectiveService is null - cannot expire objectives!");
                    }
                    regionService.initializeRegions(
                            configManager.getRegionRedHome(),
                            configManager.getRegionBlueHome()
                    );
                    // Spawn new objectives in all regions after reset
                    if (objectiveService != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            for (RegionStatus status : regionService.getAllRegionStatuses()) {
                                if (status.state() != RegionState.PROTECTED && status.state() != RegionState.FORTIFIED) {
                                    objectiveService.spawnObjectivesForRegion(status.regionId());
                                }
                            }
                            plugin.getLogger().info("[Admin] Spawned new objectives in all regions");
                        }, 20L); // 1 second delay to ensure region status is updated
                    }
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "All regions reset.");
                } else {
                    // Expire objectives in this region
                    if (objectiveService != null) {
                        objectiveService.expireObjectivesInRegion(regionId);
                        plugin.getLogger().info("[Admin] Expired objectives in region " + regionId);
                    } else {
                        plugin.getLogger().warning("[Admin] ObjectiveService is null - cannot expire objectives!");
                    }
                    regionService.resetRegion(regionId);
                    // Spawn new objectives in this region after reset
                    if (objectiveService != null) {
                        final String finalRegionId = regionId;
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            objectiveService.spawnObjectivesForRegion(finalRegionId);
                            plugin.getLogger().info("[Admin] Spawned new objectives in region " + finalRegionId);
                        }, 20L); // 1 second delay to ensure region status is updated
                    }
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Region " + regionId + " reset to neutral.");
                }
                yield true;
            }
            case "setstate" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region setstate <regionId> <state>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                String stateName = args[3].toUpperCase();
                try {
                    RegionState state = RegionState.valueOf(stateName);
                    regionService.setRegionState(regionId, state);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Region " + regionId + " state set to " + state);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid state. Valid: NEUTRAL, OWNED, CONTESTED, FORTIFIED, PROTECTED");
                }
                yield true;
            }
            case "addip" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region addip <regionId> <red|blue> <amount>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                String team = args[3].toLowerCase();
                try {
                    double amount = Double.parseDouble(args[4]);
                    regionService.addInfluence(regionId, team, amount, null);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Added " + amount + " IP to " + team + " in " + regionId);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount.");
                }
                yield true;
            }
            case "info" -> {
                String regionId;
                if (args.length >= 3) {
                    regionId = args[2].toUpperCase();
                } else if (sender instanceof Player player) {
                    regionId = getRegionIdForPlayer(player);
                } else {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region info <regionId>");
                    yield true;
                }

                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Region not found: " + regionId);
                    yield true;
                }
                RegionStatus status = statusOpt.get();
                sender.sendMessage(ChatColor.GOLD + "=== Region " + regionId + " ===");
                sender.sendMessage(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + (status.ownerTeam() != null ? status.ownerTeam() : "None"));
                sender.sendMessage(ChatColor.GRAY + "State: " + ChatColor.WHITE + status.state());
                sender.sendMessage(ChatColor.GRAY + "Red IP: " + ChatColor.RED + String.format("%.1f", status.redInfluence()));
                sender.sendMessage(ChatColor.GRAY + "Blue IP: " + ChatColor.BLUE + String.format("%.1f", status.blueInfluence()));
                sender.sendMessage(ChatColor.GRAY + "Times Captured: " + ChatColor.WHITE + status.timesCaptured());
                if (status.fortifiedUntil() != null && status.fortifiedUntil() > System.currentTimeMillis()) {
                    long remaining = (status.fortifiedUntil() - System.currentTimeMillis()) / 1000;
                    sender.sendMessage(ChatColor.GRAY + "Fortified for: " + ChatColor.AQUA + remaining + "s");
                }
                yield true;
            }
            case "setowner" -> {
                // Set region owner to a team and state to OWNED (no fortification)
                // This allows regular gameplay to resume immediately
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin region setowner <regionId> <red|blue>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                String team = args[3].toLowerCase();
                if (!team.equals("red") && !team.equals("blue")) {
                    sender.sendMessage(ChatColor.RED + "Team must be: red or blue");
                    yield true;
                }

                // Check if region exists
                Optional<RegionStatus> existingStatus = regionService.getRegionStatus(regionId);
                if (existingStatus.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Region not found: " + regionId);
                    yield true;
                }

                // Expire any existing objectives
                if (objectiveService != null) {
                    objectiveService.expireObjectivesInRegion(regionId);
                }

                // Set owner and state to OWNED (no fortification timer)
                regionService.setRegionOwner(regionId, team);
                regionService.setRegionState(regionId, RegionState.OWNED);

                // Clear influence points to start fresh
                regionService.resetInfluence(regionId);

                // Update BlueMap
                if (regionRenderer != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        World world = roundService.getGameWorld().orElse(null);
                        if (world != null) {
                            regionRenderer.scheduleUpdateForOverworld(world);
                        }
                    }, 5L);
                }

                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Region " + regionId +
                        " is now owned by " + (team.equals("red") ? ChatColor.RED : ChatColor.BLUE) + team.toUpperCase() +
                        ChatColor.GREEN + " (OWNED state, no fortification)");
                sender.sendMessage(ChatColor.GRAY + "Regular gameplay can now resume - enemies can contest this region.");
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown region action: " + action);
                yield true;
            }
        };
    }

    // ==================== TEAM COMMANDS ====================

    private boolean handleTeam(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin team <setspawn|wipe|info>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "setspawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                    yield true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin team setspawn <red|blue>");
                    yield true;
                }
                String team = args[2].toLowerCase();
                if (!team.equals("red") && !team.equals("blue")) {
                    sender.sendMessage(ChatColor.RED + "Team must be: red or blue");
                    yield true;
                }
                Location loc = player.getLocation();
                teamService.setTeamSpawn(team, loc);
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + team.toUpperCase() + " spawn set to your location.");
                yield true;
            }
            case "wipe" -> {
                teamService.resetAllTeams();
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "All team memberships wiped.");
                yield true;
            }
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin team info <red|blue>");
                    yield true;
                }
                String team = args[2].toLowerCase();
                long count = teamService.countTeamMembers(team);
                Optional<Location> spawn = teamService.getTeamSpawn(team);
                sender.sendMessage(ChatColor.GOLD + "=== Team " + team.toUpperCase() + " ===");
                sender.sendMessage(ChatColor.GRAY + "Members: " + ChatColor.WHITE + count);
                sender.sendMessage(ChatColor.GRAY + "Spawn: " + ChatColor.WHITE +
                        (spawn.isPresent() ? formatLocation(spawn.get()) : "Not set"));
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown team action: " + action);
                yield true;
            }
        };
    }

    // ==================== PLAYER COMMANDS ====================

    private boolean handlePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin player <setteam|kick|respawn|tp>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "setteam" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin player setteam <player> <red|blue|none>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                String team = args[3].toLowerCase();
                if (team.equals("none")) {
                    teamService.leaveTeam(target.getUniqueId());
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + target.getName() + " removed from their team.");
                } else if (team.equals("red") || team.equals("blue")) {
                    teamService.leaveTeam(target.getUniqueId());
                    teamService.joinTeam(target.getUniqueId(), team, JoinReason.ADMIN_ASSIGN);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + target.getName() + " set to " + team + " team.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Team must be: red, blue, or none");
                }
                yield true;
            }
            case "respawn" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin player respawn <player>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                if (deathListener != null) {
                    deathListener.forceCompleteRespawn(target);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Force respawned " + target.getName());
                } else {
                    sender.sendMessage(ChatColor.RED + "Death listener not available.");
                }
                yield true;
            }
            case "tp" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin player tp <player> <regionName>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                // Join remaining args in case region name has spaces
                String regionNameInput = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

                // Try to find region ID by name first, fallback to treating input as ID
                String regionId = regionRenderer.getRegionIdByName(regionNameInput)
                        .orElse(regionNameInput.toUpperCase());

                Location regionCenter = getRegionCenter(regionId, target.getWorld());
                if (regionCenter == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid region: " + regionNameInput);
                    yield true;
                }
                target.teleport(regionCenter);
                String displayName = regionRenderer.getRegionName(regionId).orElse(regionId);
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Teleported " + target.getName() + " to " + displayName);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown player action: " + action);
                yield true;
            }
        };
    }

    // ==================== SUPPLY COMMANDS ====================

    /**
     * Gets the display name for a region (e.g., "Shadowfen Valley" instead of "A1").
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null && regionId != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId != null ? regionId : "Unknown";
    }

    /**
     * Resolves a region input (name or ID) to the region ID.
     */
    private String resolveRegionId(String input) {
        // First check if it's already a valid region ID (e.g., "A1", "B2")
        if (input.matches("[A-Da-d][1-4]")) {
            return input.toUpperCase();
        }
        // Otherwise, try to find by name
        if (regionRenderer != null) {
            return regionRenderer.getRegionIdByName(input).orElse(input.toUpperCase());
        }
        return input.toUpperCase();
    }

    private boolean handleSupply(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin supply <recalculate|info|debug|diagnose|clear>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "diagnose" -> {
                // Comprehensive diagnostic check for supply system
                sender.sendMessage(ChatColor.GOLD + "=== Supply System Diagnostics ===");

                // 1. Check for active round
                var currentRound = roundService.getCurrentRound();
                if (currentRound.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "✗ NO ACTIVE ROUND!");
                    sender.sendMessage(ChatColor.GRAY + "  Supply system cannot function without a round.");
                    sender.sendMessage(ChatColor.YELLOW + "  Fix: Run /round new or restart server.");
                    yield true;
                }
                sender.sendMessage(ChatColor.GREEN + "✓ Active Round: " + currentRound.get().roundId());

                // 2. Check region initialization
                int redRegions = 0, blueRegions = 0, neutralRegions = 0;
                for (char row = 'A'; row <= 'D'; row++) {
                    for (int col = 1; col <= 4; col++) {
                        String regId = row + String.valueOf(col);
                        Optional<RegionStatus> status = regionService.getRegionStatus(regId);
                        if (status.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "✗ Region " + regId + " not initialized!");
                        } else {
                            String owner = status.get().ownerTeam();
                            if ("red".equalsIgnoreCase(owner)) redRegions++;
                            else if ("blue".equalsIgnoreCase(owner)) blueRegions++;
                            else neutralRegions++;
                        }
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "✓ Regions: " +
                        ChatColor.RED + redRegions + " red" + ChatColor.WHITE + ", " +
                        ChatColor.BLUE + blueRegions + " blue" + ChatColor.WHITE + ", " +
                        ChatColor.GRAY + neutralRegions + " neutral");

                // 3. Check road blocks in database
                int redRoads = 0, blueRoads = 0;
                for (char row = 'A'; row <= 'D'; row++) {
                    for (int col = 1; col <= 4; col++) {
                        String regId = row + String.valueOf(col);
                        redRoads += roadService.getRoadBlockCount(regId, "red");
                        blueRoads += roadService.getRoadBlockCount(regId, "blue");
                    }
                }
                if (redRoads == 0 && blueRoads == 0) {
                    sender.sendMessage(ChatColor.YELLOW + "⚠ No road blocks registered!");
                    sender.sendMessage(ChatColor.GRAY + "  Players need to place path blocks, OR run:");
                    sender.sendMessage(ChatColor.GRAY + "  /admin supply register <region> <team>");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "✓ Road Blocks: " +
                            ChatColor.RED + redRoads + " red" + ChatColor.WHITE + ", " +
                            ChatColor.BLUE + blueRoads + " blue");
                }

                // 4. Check path block configuration
                List<String> pathBlocks = configManager.getSupplyPathBlocks();
                if (pathBlocks == null || pathBlocks.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "⚠ Using default path blocks (DIRT_PATH, GRAVEL, etc.)");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "✓ Path Blocks: " + String.join(", ", pathBlocks));
                }

                // 5. Check supply status for owned regions
                sender.sendMessage(ChatColor.GOLD + "=== Supply Status by Region ===");
                for (String teamCheck : new String[]{"red", "blue"}) {
                    String homeRegion = teamCheck.equals("red") ?
                            configManager.getRegionRedHome() : configManager.getRegionBlueHome();
                    Set<String> connectedRegions = roadService.getConnectedRegions(teamCheck);

                    StringBuilder teamStatus = new StringBuilder();
                    int ownedCount = 0;
                    int suppliedCount = 0;
                    int partialCount = 0;
                    int unsuppliedCount = 0;

                    for (char row = 'A'; row <= 'D'; row++) {
                        for (int col = 1; col <= 4; col++) {
                            String regId = row + String.valueOf(col);
                            Optional<RegionStatus> status = regionService.getRegionStatus(regId);
                            if (status.isPresent() && teamCheck.equalsIgnoreCase(status.get().ownerTeam())) {
                                ownedCount++;
                                SupplyLevel level = roadService.getSupplyLevel(regId, teamCheck);
                                switch (level) {
                                    case SUPPLIED -> suppliedCount++;
                                    case PARTIAL -> partialCount++;
                                    default -> unsuppliedCount++;
                                }
                            }
                        }
                    }

                    ChatColor teamColor = teamCheck.equals("red") ? ChatColor.RED : ChatColor.BLUE;
                    if (ownedCount > 0) {
                        sender.sendMessage(teamColor + teamCheck.toUpperCase() + ChatColor.WHITE +
                                ": " + ownedCount + " regions owned - " +
                                ChatColor.GREEN + suppliedCount + " supplied" + ChatColor.WHITE + ", " +
                                ChatColor.YELLOW + partialCount + " partial" + ChatColor.WHITE + ", " +
                                ChatColor.RED + unsuppliedCount + " unsupplied");

                        // Show problem regions
                        if (partialCount > 0 || unsuppliedCount > 0) {
                            for (char row = 'A'; row <= 'D'; row++) {
                                for (int col = 1; col <= 4; col++) {
                                    String regId = row + String.valueOf(col);
                                    Optional<RegionStatus> status = regionService.getRegionStatus(regId);
                                    if (status.isPresent() && teamCheck.equalsIgnoreCase(status.get().ownerTeam())) {
                                        SupplyLevel level = roadService.getSupplyLevel(regId, teamCheck);
                                        if (level != SupplyLevel.SUPPLIED) {
                                            String regName = getRegionDisplayName(regId);
                                            int blocks = roadService.getRoadBlockCount(regId, teamCheck);
                                            boolean connected = connectedRegions.contains(regId);
                                            sender.sendMessage(ChatColor.GRAY + "  " + regName + " [" + regId + "]: " +
                                                    (level == SupplyLevel.PARTIAL ? ChatColor.YELLOW : ChatColor.RED) + level +
                                                    ChatColor.GRAY + " (" + blocks + " blocks, " +
                                                    (connected ? "connected" : "disconnected") + ")");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. Summary
                sender.sendMessage(ChatColor.GOLD + "=== Diagnostics Complete ===");
                sender.sendMessage(ChatColor.GRAY + "For detailed debug: /admin supply debug <region> <team>");
                sender.sendMessage(ChatColor.GRAY + "To force recalculate: /admin supply recalculate");
                yield true;
            }
            case "recalculate" -> {
                if (args.length < 3) {
                    roadService.recalculateSupply("red");
                    roadService.recalculateSupply("blue");
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Supply recalculated for all teams.");
                } else {
                    String team = args[2].toLowerCase();
                    roadService.recalculateSupply(team);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Supply recalculated for " + team);
                }
                yield true;
            }
            case "info" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply info <region> <red|blue>");
                    yield true;
                }
                String regionId = resolveRegionId(args[2]);
                String regionName = getRegionDisplayName(regionId);
                String team = args[3].toLowerCase();
                SupplyLevel level = roadService.getSupplyLevel(regionId, team);
                boolean connected = roadService.isConnectedToHome(regionId, team);
                int roadBlocks = roadService.getRoadBlockCount(regionId, team);

                sender.sendMessage(ChatColor.GOLD + "=== Supply Info: " + regionName + " [" + regionId + "] (" + team + ") ===");
                sender.sendMessage(ChatColor.GRAY + "Level: " + ChatColor.WHITE + level);
                sender.sendMessage(ChatColor.GRAY + "Connected to Home: " + ChatColor.WHITE + connected);
                sender.sendMessage(ChatColor.GRAY + "Road Blocks: " + ChatColor.WHITE + roadBlocks);
                sender.sendMessage(ChatColor.GRAY + "Respawn Delay: " + ChatColor.WHITE + "+" + level.getRespawnDelay() + "s");
                sender.sendMessage(ChatColor.GRAY + "Health Regen: " + ChatColor.WHITE + (int) (level.getHealthRegenMultiplier() * 100) + "%");
                yield true;
            }
            case "debug" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply debug <region> <red|blue>");
                    yield true;
                }
                String regionId = resolveRegionId(args[2]);
                String regionName = getRegionDisplayName(regionId);
                String team = args[3].toLowerCase();

                sender.sendMessage(ChatColor.GOLD + "=== Supply Debug: " + regionName + " [" + regionId + "] (" + team + ") ===");

                // Basic info
                SupplyLevel level = roadService.getSupplyLevel(regionId, team);
                sender.sendMessage(ChatColor.GRAY + "Current Level: " + ChatColor.WHITE + level);

                // Check region ownership
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                if (statusOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Region not found in database!");
                    yield true;
                }
                RegionStatus status = statusOpt.get();
                sender.sendMessage(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + (status.ownerTeam() != null ? status.ownerTeam() : "Neutral"));
                sender.sendMessage(ChatColor.GRAY + "Owned by " + team + ": " + ChatColor.WHITE + status.isOwnedBy(team));

                // Check home region
                String homeRegion = team.equalsIgnoreCase("red") ?
                        configManager.getRegionRedHome() : configManager.getRegionBlueHome();
                String homeName = getRegionDisplayName(homeRegion);
                sender.sendMessage(ChatColor.GRAY + "Home Region: " + ChatColor.WHITE + homeName + " [" + homeRegion + "]");
                sender.sendMessage(ChatColor.GRAY + "Is Home: " + ChatColor.WHITE + regionId.equals(homeRegion));

                // Check adjacent regions
                List<String> adjacent = regionService.getAdjacentRegions(regionId);
                sender.sendMessage(ChatColor.GRAY + "Adjacent Regions: " + ChatColor.WHITE + adjacent.size());
                for (String adj : adjacent) {
                    Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
                    boolean owned = adjStatus.isPresent() && adjStatus.get().isOwnedBy(team);
                    boolean hasRoadConnection = roadService.hasRoadConnection(regionId, adj, team);
                    String adjName = getRegionDisplayName(adj);
                    ChatColor color = owned ? (hasRoadConnection ? ChatColor.GREEN : ChatColor.YELLOW) : ChatColor.RED;
                    sender.sendMessage(ChatColor.GRAY + "  " + adjName + " [" + adj + "]: " +
                            color + (owned ? "Owned" : "Not Owned") +
                            (owned ? (hasRoadConnection ? " ✓ Road" : " ✗ No Road") : ""));
                }

                // Check road path to home
                int pathLength = roadService.findRoadPathToHome(regionId, team);
                sender.sendMessage(ChatColor.GRAY + "Road Path to Home: " +
                        (pathLength >= 0 ? ChatColor.GREEN + "Found (" + pathLength + " hops)" : ChatColor.RED + "Not Found"));

                // Check road blocks in region
                int roadBlocks = roadService.getRoadBlockCount(regionId, team);
                sender.sendMessage(ChatColor.GRAY + "Road Blocks in Region: " + ChatColor.WHITE + roadBlocks);

                // Check each border connection in detail
                sender.sendMessage(ChatColor.YELLOW + "--- Border Connection Details ---");
                boolean anyDisconnected = false;
                for (String adj : adjacent) {
                    Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
                    if (adjStatus.isEmpty() || !adjStatus.get().isOwnedBy(team)) continue;

                    String adjName = getRegionDisplayName(adj);
                    boolean borderConn = roadService.checkBorderRoadConnection(regionId, adj, team);
                    sender.sendMessage(ChatColor.GRAY + "Border " + regionId + " <-> " + adj + " (" + adjName + "): " +
                            (borderConn ? ChatColor.GREEN + "Connected" : ChatColor.RED + "Not Connected"));
                    if (!borderConn) anyDisconnected = true;
                }

                // Show tip if any borders are disconnected
                if (anyDisconnected && pathLength < 0) {
                    sender.sendMessage(ChatColor.YELLOW + "TIP: " + ChatColor.GRAY +
                            "Build a road (path blocks) connecting this region to an adjacent supplied region.");
                }

                // Show connected regions via roads
                Set<String> connected = roadService.getConnectedRegions(team);
                sender.sendMessage(ChatColor.YELLOW + "--- Regions Connected via Roads ---");
                StringBuilder connectedList = new StringBuilder();
                for (String conn : connected) {
                    String connName = getRegionDisplayName(conn);
                    connectedList.append(connName).append(" [").append(conn).append("], ");
                }
                if (connectedList.length() > 0) {
                    connectedList.setLength(connectedList.length() - 2);
                }
                sender.sendMessage(ChatColor.WHITE + (connectedList.length() > 0 ? connectedList.toString() : "None"));

                // Check if this region is in the connected set
                sender.sendMessage(ChatColor.GRAY + "This region in connected set: " +
                        (connected.contains(regionId) ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO"));

                yield true;
            }
            case "gaptest" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply gaptest <region> <red|blue>");
                    yield true;
                }
                String regionId = resolveRegionId(args[2]);
                String team = args[3].toLowerCase();

                sender.sendMessage(ChatColor.GOLD + "Running gap detection debug...");

                List<String> debugOutput = roadService.debugGapDetection(regionId, team);
                for (String line : debugOutput) {
                    if (line.startsWith("ERROR") || line.contains("FALSE")) {
                        sender.sendMessage(ChatColor.RED + line);
                    } else if (line.contains("TRUE") || line.contains("GAP DETECTED")) {
                        sender.sendMessage(ChatColor.GREEN + line);
                    } else if (line.startsWith("===") || line.isEmpty()) {
                        sender.sendMessage(ChatColor.GOLD + line);
                    } else {
                        sender.sendMessage(ChatColor.GRAY + line);
                    }
                }

                yield true;
            }
            case "borderinfo" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply borderinfo <region1> <region2> <red|blue>");
                    yield true;
                }
                String region1 = resolveRegionId(args[2]);
                String region2 = resolveRegionId(args[3]);
                String team = args[4].toLowerCase();

                String name1 = getRegionDisplayName(region1);
                String name2 = getRegionDisplayName(region2);

                sender.sendMessage(ChatColor.GOLD + "=== Border Debug: " + name1 + " [" + region1 + "] <-> " + name2 + " [" + region2 + "] (" + team + ") ===");

                // Get border info
                int borderWidth = configManager.getSupplyBorderWidth();
                int adjacencyRadius = configManager.getSupplyAdjacencyRadius();
                sender.sendMessage(ChatColor.GRAY + "Border Width: " + ChatColor.WHITE + borderWidth + " blocks");
                sender.sendMessage(ChatColor.GRAY + "Adjacency Radius: " + ChatColor.WHITE + adjacencyRadius + " blocks");

                // Calculate and show the actual border search area
                int[] borderArea = calculateBorderArea(region1, region2, borderWidth);
                if (borderArea != null) {
                    sender.sendMessage(ChatColor.YELLOW + "Border Search Area:");
                    sender.sendMessage(ChatColor.GRAY + "  X: " + borderArea[0] + " to " + borderArea[1]);
                    sender.sendMessage(ChatColor.GRAY + "  Z: " + borderArea[2] + " to " + borderArea[3]);

                    // Show region boundaries for reference
                    sender.sendMessage(ChatColor.YELLOW + "Region Boundaries:");
                    int[] r1Bounds = getRegionBounds(region1);
                    int[] r2Bounds = getRegionBounds(region2);
                    if (r1Bounds != null) {
                        sender.sendMessage(ChatColor.GRAY + "  " + region1 + ": X[" + r1Bounds[0] + " to " + r1Bounds[1] + "] Z[" + r1Bounds[2] + " to " + r1Bounds[3] + "]");
                    }
                    if (r2Bounds != null) {
                        sender.sendMessage(ChatColor.GRAY + "  " + region2 + ": X[" + r2Bounds[0] + " to " + r2Bounds[1] + "] Z[" + r2Bounds[2] + " to " + r2Bounds[3] + "]");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Regions are not adjacent!");
                    yield true;
                }

                // Count road blocks in each region
                List<RoadBlock> blocks1 = roadService.getRoadBlocksInRegion(region1, team);
                List<RoadBlock> blocks2 = roadService.getRoadBlocksInRegion(region2, team);

                sender.sendMessage(ChatColor.GRAY + "Total road blocks in " + region1 + ": " + ChatColor.WHITE + blocks1.size());
                sender.sendMessage(ChatColor.GRAY + "Total road blocks in " + region2 + ": " + ChatColor.WHITE + blocks2.size());

                // Find blocks that are actually in the border area
                List<RoadBlock> blocks1InBorder = blocks1.stream()
                        .filter(b -> b.x() >= borderArea[0] && b.x() <= borderArea[1] &&
                                b.z() >= borderArea[2] && b.z() <= borderArea[3])
                        .toList();
                List<RoadBlock> blocks2InBorder = blocks2.stream()
                        .filter(b -> b.x() >= borderArea[0] && b.x() <= borderArea[1] &&
                                b.z() >= borderArea[2] && b.z() <= borderArea[3])
                        .toList();

                sender.sendMessage(ChatColor.YELLOW + "Blocks in border area:");
                sender.sendMessage(ChatColor.GRAY + "  " + region1 + ": " +
                        (blocks1InBorder.isEmpty() ? ChatColor.RED + "0 (NONE!)" : ChatColor.GREEN + String.valueOf(blocks1InBorder.size())));
                sender.sendMessage(ChatColor.GRAY + "  " + region2 + ": " +
                        (blocks2InBorder.isEmpty() ? ChatColor.RED + "0 (NONE!)" : ChatColor.GREEN + String.valueOf(blocks2InBorder.size())));

                // Check border connection
                boolean borderConn = roadService.checkBorderRoadConnection(region1, region2, team);
                sender.sendMessage(ChatColor.GRAY + "Border Connected: " +
                        (borderConn ? ChatColor.GREEN + "YES" : ChatColor.RED + "NO"));

                // Show blocks in border area
                if (!blocks1InBorder.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Blocks from " + region1 + " in border area:");
                    int count = 0;
                    for (RoadBlock b : blocks1InBorder) {
                        if (count++ >= 5) {
                            sender.sendMessage(ChatColor.GRAY + "  ... and " + (blocks1InBorder.size() - 5) + " more");
                            break;
                        }
                        sender.sendMessage(ChatColor.GRAY + "  " + b.x() + ", " + b.y() + ", " + b.z());
                    }
                }

                if (!blocks2InBorder.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Blocks from " + region2 + " in border area:");
                    int count = 0;
                    for (RoadBlock b : blocks2InBorder) {
                        if (count++ >= 5) {
                            sender.sendMessage(ChatColor.GRAY + "  ... and " + (blocks2InBorder.size() - 5) + " more");
                            break;
                        }
                        sender.sendMessage(ChatColor.GRAY + "  " + b.x() + ", " + b.y() + ", " + b.z());
                    }
                }

                // Show sample blocks from each region (for reference)
                if (!blocks1.isEmpty() && blocks1InBorder.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "WARNING: " + region1 + " has road blocks but NONE near the border!");
                    sender.sendMessage(ChatColor.YELLOW + "Sample blocks in " + region1 + " (not in border area):");
                    int count = 0;
                    for (RoadBlock b : blocks1) {
                        if (count++ >= 3) break;
                        sender.sendMessage(ChatColor.GRAY + "  " + b.x() + ", " + b.y() + ", " + b.z());
                    }
                }

                if (!blocks2.isEmpty() && blocks2InBorder.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "WARNING: " + region2 + " has road blocks but NONE near the border!");
                    sender.sendMessage(ChatColor.YELLOW + "Sample blocks in " + region2 + " (not in border area):");
                    int count = 0;
                    for (RoadBlock b : blocks2) {
                        if (count++ >= 3) break;
                        sender.sendMessage(ChatColor.GRAY + "  " + b.x() + ", " + b.y() + ", " + b.z());
                    }
                }

                yield true;
            }

            case "roadpath" -> {
                if (args.length < 6) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply roadpath <region> <fromRegion> <toRegion> <red|blue>");
                    sender.sendMessage(ChatColor.GRAY + "Example: /admin supply roadpath A2 A1 A3 red");
                    sender.sendMessage(ChatColor.GRAY + "This checks if there's a continuous road through <region> from <fromRegion> to <toRegion>");
                    yield true;
                }
                String regionId = resolveRegionId(args[2]);
                String fromRegion = resolveRegionId(args[3]);
                String toRegion = resolveRegionId(args[4]);
                String team = args[5].toLowerCase();

                String regionName = getRegionDisplayName(regionId);
                String fromName = getRegionDisplayName(fromRegion);
                String toName = getRegionDisplayName(toRegion);

                sender.sendMessage(ChatColor.GOLD + "=== Road Path Debug ===");
                sender.sendMessage(ChatColor.GRAY + "Checking continuous road through " + ChatColor.WHITE + regionName + " [" + regionId + "]");
                sender.sendMessage(ChatColor.GRAY + "From: " + ChatColor.WHITE + fromName + " [" + fromRegion + "]");
                sender.sendMessage(ChatColor.GRAY + "To: " + ChatColor.WHITE + toName + " [" + toRegion + "]");
                sender.sendMessage(ChatColor.GRAY + "Team: " + ChatColor.WHITE + team);

                // Get road blocks in the region
                List<RoadBlock> blocks = roadService.getRoadBlocksInRegion(regionId, team);
                sender.sendMessage(ChatColor.GRAY + "Total road blocks in " + regionId + ": " + ChatColor.WHITE + blocks.size());

                if (blocks.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No road blocks in this region! Cannot have a continuous road.");
                    yield true;
                }

                // Get border areas
                int[] entryBorder = calculateBorderArea(regionId, fromRegion, configManager.getSupplyBorderWidth());
                int[] exitBorder = calculateBorderArea(regionId, toRegion, configManager.getSupplyBorderWidth());

                if (entryBorder == null) {
                    sender.sendMessage(ChatColor.RED + regionId + " and " + fromRegion + " are not adjacent!");
                    yield true;
                }
                if (exitBorder == null) {
                    sender.sendMessage(ChatColor.RED + regionId + " and " + toRegion + " are not adjacent!");
                    yield true;
                }

                sender.sendMessage(ChatColor.YELLOW + "Entry border (" + fromRegion + "): X[" + entryBorder[0] + " to " + entryBorder[1] + "] Z[" + entryBorder[2] + " to " + entryBorder[3] + "]");
                sender.sendMessage(ChatColor.YELLOW + "Exit border (" + toRegion + "): X[" + exitBorder[0] + " to " + exitBorder[1] + "] Z[" + exitBorder[2] + " to " + exitBorder[3] + "]");

                // Find blocks at each border
                List<RoadBlock> entryBlocks = blocks.stream()
                        .filter(b -> b.x() >= entryBorder[0] && b.x() <= entryBorder[1] &&
                                     b.z() >= entryBorder[2] && b.z() <= entryBorder[3])
                        .toList();
                List<RoadBlock> exitBlocks = blocks.stream()
                        .filter(b -> b.x() >= exitBorder[0] && b.x() <= exitBorder[1] &&
                                     b.z() >= exitBorder[2] && b.z() <= exitBorder[3])
                        .toList();

                sender.sendMessage(ChatColor.GRAY + "Blocks at entry border: " +
                        (entryBlocks.isEmpty() ? ChatColor.RED + "0 (NONE!)" : ChatColor.GREEN + String.valueOf(entryBlocks.size())));
                sender.sendMessage(ChatColor.GRAY + "Blocks at exit border: " +
                        (exitBlocks.isEmpty() ? ChatColor.RED + "0 (NONE!)" : ChatColor.GREEN + String.valueOf(exitBlocks.size())));

                if (entryBlocks.isEmpty() || exitBlocks.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Missing road blocks at one or both borders!");
                    yield true;
                }

                // Try to pathfind using spatial hash for O(n) adjacency lookup
                int xzRadius = configManager.getSupplyAdjacencyRadius();
                int yTolerance = configManager.getSupplyYTolerance();
                sender.sendMessage(ChatColor.GRAY + "X/Z adjacency radius: " + ChatColor.WHITE + xzRadius);
                sender.sendMessage(ChatColor.GRAY + "Y tolerance: " + ChatColor.WHITE + yTolerance);
                sender.sendMessage(ChatColor.GRAY + "Building spatial index...");

                // Build spatial hash map for O(1) neighbor lookups
                // Key format: "x,y,z" -> RoadBlock
                Map<String, RoadBlock> spatialIndex = new HashMap<>();
                for (RoadBlock block : blocks) {
                    spatialIndex.put(block.toKey(), block);
                }

                // BFS from entry blocks to exit blocks using spatial lookup
                Set<String> exitKeys = exitBlocks.stream().map(RoadBlock::toKey).collect(Collectors.toSet());
                Set<String> visited = new HashSet<>();
                Queue<String> queue = new LinkedList<>();
                Map<String, String> parent = new HashMap<>();

                for (RoadBlock entry : entryBlocks) {
                    String key = entry.toKey();
                    queue.add(key);
                    visited.add(key);
                    parent.put(key, null);
                }

                sender.sendMessage(ChatColor.GRAY + "Searching for path...");

                String foundExit = null;
                int iterations = 0;
                while (!queue.isEmpty()) {
                    String currentKey = queue.poll();
                    iterations++;

                    if (exitKeys.contains(currentKey)) {
                        foundExit = currentKey;
                        break;
                    }

                    RoadBlock current = spatialIndex.get(currentKey);
                    if (current == null) continue;

                    // Check all neighbors within X/Z radius and Y tolerance using spatial lookup
                    for (int dx = -xzRadius; dx <= xzRadius; dx++) {
                        for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                            for (int dz = -xzRadius; dz <= xzRadius; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;

                                String neighborKey = RoadBlock.toKey(current.x() + dx, current.y() + dy, current.z() + dz);
                                if (!visited.contains(neighborKey) && spatialIndex.containsKey(neighborKey)) {
                                    visited.add(neighborKey);
                                    queue.add(neighborKey);
                                    parent.put(neighborKey, currentKey);
                                }
                            }
                        }
                    }
                }

                if (foundExit != null) {
                    // Reconstruct path
                    List<String> path = new ArrayList<>();
                    String current = foundExit;
                    while (current != null) {
                        path.add(current);
                        current = parent.get(current);
                    }
                    Collections.reverse(path);

                    sender.sendMessage(ChatColor.GREEN + "✓ CONTINUOUS ROAD FOUND!");
                    sender.sendMessage(ChatColor.GRAY + "Path length: " + ChatColor.WHITE + path.size() + " blocks");
                    sender.sendMessage(ChatColor.GRAY + "Blocks visited during search: " + ChatColor.WHITE + visited.size() + "/" + blocks.size());
                    sender.sendMessage(ChatColor.GRAY + "Search iterations: " + ChatColor.WHITE + iterations);
                } else {
                    sender.sendMessage(ChatColor.RED + "✗ NO CONTINUOUS ROAD!");
                    sender.sendMessage(ChatColor.GRAY + "Blocks reachable from entry: " + ChatColor.WHITE + visited.size() + "/" + blocks.size());
                    sender.sendMessage(ChatColor.YELLOW + "The road has gaps! Ensure blocks are within " + xzRadius + " blocks horizontally of each other.");

                    // Find where the gap is - what's the furthest X coordinate reached?
                    int minReachedX = Integer.MAX_VALUE;
                    int maxReachedX = Integer.MIN_VALUE;
                    for (String key : visited) {
                        RoadBlock b = spatialIndex.get(key);
                        if (b != null) {
                            minReachedX = Math.min(minReachedX, b.x());
                            maxReachedX = Math.max(maxReachedX, b.x());
                        }
                    }

                    int minUnreachedX = Integer.MAX_VALUE;
                    int maxUnreachedX = Integer.MIN_VALUE;
                    for (RoadBlock b : blocks) {
                        if (!visited.contains(b.toKey())) {
                            minUnreachedX = Math.min(minUnreachedX, b.x());
                            maxUnreachedX = Math.max(maxUnreachedX, b.x());
                        }
                    }

                    sender.sendMessage(ChatColor.YELLOW + "Gap Analysis:");
                    sender.sendMessage(ChatColor.GRAY + "  Reachable blocks span X: " + ChatColor.WHITE + minReachedX + " to " + maxReachedX);
                    sender.sendMessage(ChatColor.GRAY + "  Unreachable blocks span X: " + ChatColor.WHITE + minUnreachedX + " to " + maxUnreachedX);

                    // The gap is likely between maxReachedX and minUnreachedX
                    if (maxReachedX < minUnreachedX) {
                        int gapSize = minUnreachedX - maxReachedX;
                        sender.sendMessage(ChatColor.RED + "  GAP DETECTED around X=" + maxReachedX + " to X=" + minUnreachedX + " (gap size: " + gapSize + " blocks)");

                        // Show blocks at the edge of reachable area (near maxReachedX)
                        sender.sendMessage(ChatColor.YELLOW + "  Blocks at END of reachable road (X >= " + (maxReachedX - 5) + "):");
                        final int showMaxX = maxReachedX;
                        List<RoadBlock> edgeBlocks = visited.stream()
                                .map(spatialIndex::get)
                                .filter(b -> b != null && b.x() >= showMaxX - 5)
                                .sorted((a, b) -> Integer.compare(b.x(), a.x()))
                                .limit(5)
                                .toList();
                        edgeBlocks.forEach(b -> sender.sendMessage(ChatColor.GRAY + "    X=" + b.x() + ", Y=" + ChatColor.AQUA + b.y() + ChatColor.GRAY + ", Z=" + b.z()));

                        // Show blocks at the start of unreachable area (near minUnreachedX)
                        sender.sendMessage(ChatColor.YELLOW + "  Blocks at START of unreachable road (X <= " + (minUnreachedX + 5) + "):");
                        final int showMinX = minUnreachedX;
                        List<RoadBlock> startBlocks = blocks.stream()
                                .filter(b -> !visited.contains(b.toKey()))
                                .filter(b -> b.x() <= showMinX + 5)
                                .sorted(Comparator.comparingInt(RoadBlock::x))
                                .limit(5)
                                .toList();
                        startBlocks.forEach(b -> sender.sendMessage(ChatColor.GRAY + "    X=" + b.x() + ", Y=" + ChatColor.AQUA + b.y() + ChatColor.GRAY + ", Z=" + b.z()));

                        // Analyze if it's a Y-level difference or a true horizontal gap
                        int avgEdgeY = edgeBlocks.isEmpty() ? 0 : (int) edgeBlocks.stream().mapToInt(RoadBlock::y).average().orElse(0);
                        int avgStartY = startBlocks.isEmpty() ? 0 : (int) startBlocks.stream().mapToInt(RoadBlock::y).average().orElse(0);
                        int yDiff = Math.abs(avgEdgeY - avgStartY);

                        if (yDiff > yTolerance) {
                            sender.sendMessage(ChatColor.RED + "  ⚠ Y-LEVEL ISSUE! Gap Y-diff: " + yDiff + " blocks (tolerance: " + yTolerance + ")");
                            sender.sendMessage(ChatColor.YELLOW + "  Build a ramp/bridge or increase y-tolerance in config.");
                        } else if (gapSize > xzRadius) {
                            sender.sendMessage(ChatColor.RED + "  ⚠ HORIZONTAL GAP! Gap is " + gapSize + " blocks but adjacency-radius is only " + xzRadius);
                            sender.sendMessage(ChatColor.YELLOW + "  Fill in the road between X=" + maxReachedX + " and X=" + minUnreachedX);
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "  ✓ Gap should be bridgeable with current settings.");
                            sender.sendMessage(ChatColor.YELLOW + "  If still not connecting, check Z coordinates match up.");
                        }
                    } else {
                        // Overlapping - might be Y gaps or multiple disconnected sections
                        sender.sendMessage(ChatColor.YELLOW + "  Road sections overlap in X - gap may be due to Y-level differences");

                        // Show blocks at the edge of the reached area
                        sender.sendMessage(ChatColor.YELLOW + "  Blocks at edge of reachable area (highest X):");
                        final int finalMaxReachedX = maxReachedX;
                        visited.stream()
                                .map(spatialIndex::get)
                                .filter(b -> b != null && b.x() >= finalMaxReachedX - 5)
                                .sorted((a, b) -> Integer.compare(b.x(), a.x()))
                                .limit(5)
                                .forEach(b -> sender.sendMessage(ChatColor.GRAY + "    " + b.x() + ", " + b.y() + ", " + b.z()));

                        sender.sendMessage(ChatColor.YELLOW + "  Nearest unreachable blocks:");
                        blocks.stream()
                                .filter(b -> !visited.contains(b.toKey()))
                                .filter(b -> b.x() >= finalMaxReachedX - 10 && b.x() <= finalMaxReachedX + 10)
                                .sorted((a, b) -> Integer.compare(a.x(), b.x()))
                                .limit(5)
                                .forEach(b -> sender.sendMessage(ChatColor.GRAY + "    " + b.x() + ", " + b.y() + ", " + b.z()));
                    }
                }

                yield true;
            }

            case "scan" -> {
                // Scan all regions and show road block counts
                String team = args.length >= 3 ? args[2].toLowerCase() : "red";
                sender.sendMessage(ChatColor.GOLD + "=== Road Block Scan for " + team + " ===");

                int totalBlocks = 0;
                String[] rows = {"A", "B", "C", "D"};
                for (String row : rows) {
                    StringBuilder rowOutput = new StringBuilder();
                    rowOutput.append(ChatColor.YELLOW).append(row).append(": ");
                    for (int col = 1; col <= 4; col++) {
                        String regionId = row + col;
                        int count = roadService.getRoadBlockCount(regionId, team);
                        totalBlocks += count;
                        if (count > 0) {
                            rowOutput.append(ChatColor.GREEN);
                        } else {
                            rowOutput.append(ChatColor.GRAY);
                        }
                        rowOutput.append(regionId).append("=").append(count).append(" ");
                    }
                    sender.sendMessage(rowOutput.toString());
                }
                sender.sendMessage(ChatColor.GRAY + "Total road blocks: " + ChatColor.WHITE + totalBlocks);
                yield true;
            }

            case "scantest" -> {
                // Test the auto-scan functionality for a region
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command must be run by a player.");
                    yield true;
                }
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply scantest <region> <red|blue>");
                    sender.sendMessage(ChatColor.GRAY + "Tests the auto-scan that runs when a region is captured.");
                    sender.sendMessage(ChatColor.GRAY + "Check server console for detailed debug output.");
                    yield true;
                }

                String regionId = resolveRegionId(args[2]);
                String team = args[3].toLowerCase();
                String regionName = getRegionDisplayName(regionId);
                World world = player.getWorld();

                sender.sendMessage(ChatColor.YELLOW + "Running auto-scan test for " + regionName + " [" + regionId + "]...");
                sender.sendMessage(ChatColor.GRAY + "Check server console for detailed debug logs.");

                // Show current state before scan
                int beforeBlocks = roadService.getRoadBlockCount(regionId, team);
                sender.sendMessage(ChatColor.GRAY + "Road blocks BEFORE scan: " + beforeBlocks);

                // Run the scan (this will log to console)
                int found = roadService.scanRegionForRoads(regionId, team, world);

                // Show state after scan
                int afterBlocks = roadService.getRoadBlockCount(regionId, team);
                sender.sendMessage(ChatColor.GREEN + "Scan complete!");
                sender.sendMessage(ChatColor.GRAY + "New blocks found: " + ChatColor.WHITE + found);
                sender.sendMessage(ChatColor.GRAY + "Road blocks AFTER scan: " + ChatColor.WHITE + afterBlocks);

                // Check adjacent regions too
                sender.sendMessage(ChatColor.YELLOW + "Adjacent region status:");
                for (String adj : regionService.getAdjacentRegions(regionId)) {
                    Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
                    String adjName = getRegionDisplayName(adj);
                    String owner = adjStatus.map(s -> s.ownerTeam() != null ? s.ownerTeam() : "neutral").orElse("unknown");
                    int adjBlocks = roadService.getRoadBlockCount(adj, team);
                    boolean connected = roadService.checkBorderRoadConnection(regionId, adj, team);

                    ChatColor color = owner.equalsIgnoreCase(team) ? ChatColor.GREEN : ChatColor.GRAY;
                    sender.sendMessage(color + "  " + adjName + " [" + adj + "]: owner=" + owner +
                            ", blocks=" + adjBlocks + ", border=" + (connected ? "CONNECTED" : "disconnected"));
                }

                yield true;
            }

            case "scanborder" -> {
                // Scan the border area between two regions for path blocks
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command must be run by a player.");
                    yield true;
                }
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply scanborder <region1> <region2> <red|blue>");
                    sender.sendMessage(ChatColor.GRAY + "Scans the border area between two regions for path blocks.");
                    yield true;
                }

                String region1 = resolveRegionId(args[2]);
                String region2 = resolveRegionId(args[3]);
                String team = args[4].toLowerCase();
                World world = player.getWorld();

                sender.sendMessage(ChatColor.YELLOW + "Scanning border between " + region1 + " and " + region2 + "...");

                // Get border area
                int[] border = roadService.getBorderAreaPublic(region1, region2);
                if (border == null) {
                    sender.sendMessage(ChatColor.RED + "These regions are not adjacent!");
                    yield true;
                }

                sender.sendMessage(ChatColor.GRAY + "Border area: X[" + border[0] + " to " + border[1] +
                        "] Z[" + border[2] + " to " + border[3] + "]");

                // Count blocks before
                int beforeR1 = roadService.getRoadBlockCount(region1, team);
                int beforeR2 = roadService.getRoadBlockCount(region2, team);

                // Scan the border area
                int found = roadService.scanBorderArea(region1, region2, team, world);

                // Count blocks after
                int afterR1 = roadService.getRoadBlockCount(region1, team);
                int afterR2 = roadService.getRoadBlockCount(region2, team);

                sender.sendMessage(ChatColor.GREEN + "Border scan complete!");
                sender.sendMessage(ChatColor.GRAY + "New blocks found: " + ChatColor.WHITE + found);
                sender.sendMessage(ChatColor.GRAY + region1 + " blocks: " + beforeR1 + " → " + afterR1);
                sender.sendMessage(ChatColor.GRAY + region2 + " blocks: " + beforeR2 + " → " + afterR2);

                // Check connection after scan
                boolean connected = roadService.checkBorderRoadConnection(region1, region2, team);
                sender.sendMessage(ChatColor.GRAY + "Border connection: " +
                        (connected ? ChatColor.GREEN + "CONNECTED" : ChatColor.RED + "NOT CONNECTED"));

                // Trigger recalculation
                if (found > 0) {
                    roadService.recalculateSupply(team);
                    sender.sendMessage(ChatColor.YELLOW + "Supply recalculated for " + team);
                }

                yield true;
            }

            case "worldscan" -> {
                // Inspect what blocks are actually in a border area in the world
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command must be run by a player.");
                    yield true;
                }
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply worldscan <region1> <region2>");
                    sender.sendMessage(ChatColor.GRAY + "Shows what path blocks exist at the border in the world.");
                    yield true;
                }

                String region1 = resolveRegionId(args[2]);
                String region2 = resolveRegionId(args[3]);
                World world = player.getWorld();

                int[] border = roadService.getBorderAreaPublic(region1, region2);
                if (border == null) {
                    sender.sendMessage(ChatColor.RED + "These regions are not adjacent!");
                    yield true;
                }

                sender.sendMessage(ChatColor.YELLOW + "Scanning world blocks at border " + region1 + "<->" + region2 + "...");
                sender.sendMessage(ChatColor.GRAY + "Border area: X[" + border[0] + " to " + border[1] +
                        "] Z[" + border[2] + " to " + border[3] + "]");

                // Get configured path blocks
                List<String> pathBlockNames = configManager.getSupplyPathBlocks();
                Set<Material> pathBlocks = new java.util.HashSet<>();
                if (pathBlockNames == null || pathBlockNames.isEmpty()) {
                    pathBlocks.add(Material.DIRT_PATH);
                    pathBlocks.add(Material.GRAVEL);
                    pathBlocks.add(Material.COBBLESTONE);
                    pathBlocks.add(Material.STONE_BRICKS);
                    pathBlocks.add(Material.POLISHED_ANDESITE);
                } else {
                    for (String name : pathBlockNames) {
                        try {
                            pathBlocks.add(Material.valueOf(name.toUpperCase()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                sender.sendMessage(ChatColor.GRAY + "Looking for: " + pathBlocks.stream()
                        .map(Material::name).collect(java.util.stream.Collectors.joining(", ")));

                // Scan the border area and count path blocks
                Map<Material, Integer> blockCounts = new java.util.HashMap<>();
                int totalPathBlocks = 0;
                int sampleX = 0, sampleY = 0, sampleZ = 0;
                Material sampleMat = null;

                final int START_Y = 52;
                final int AIR_THRESHOLD = 100; // High threshold for elevated roads/bridges

                for (int x = border[0]; x <= border[1]; x++) {
                    for (int z = border[2]; z <= border[3]; z++) {
                        int consecutiveAir = 0;
                        for (int y = START_Y; y < world.getMaxHeight() && consecutiveAir < AIR_THRESHOLD; y++) {
                            Material type = world.getBlockAt(x, y, z).getType();
                            if (type.isAir()) {
                                consecutiveAir++;
                            } else {
                                consecutiveAir = 0;
                                if (pathBlocks.contains(type)) {
                                    totalPathBlocks++;
                                    blockCounts.merge(type, 1, Integer::sum);
                                    if (sampleMat == null) {
                                        sampleX = x; sampleY = y; sampleZ = z; sampleMat = type;
                                    }
                                }
                            }
                        }
                    }
                }

                sender.sendMessage(ChatColor.GREEN + "World scan complete!");
                sender.sendMessage(ChatColor.GRAY + "Total path blocks in border area: " + ChatColor.WHITE + totalPathBlocks);

                if (totalPathBlocks > 0) {
                    sender.sendMessage(ChatColor.GRAY + "Block types found:");
                    for (Map.Entry<Material, Integer> entry : blockCounts.entrySet()) {
                        sender.sendMessage(ChatColor.GRAY + "  " + entry.getKey().name() + ": " + entry.getValue());
                    }
                    sender.sendMessage(ChatColor.GRAY + "Sample location: " + sampleX + "," + sampleY + "," + sampleZ + " (" + sampleMat + ")");
                } else {
                    sender.sendMessage(ChatColor.RED + "No path blocks found in border area!");
                    sender.sendMessage(ChatColor.YELLOW + "TIP: Build a road using path blocks to connect these regions.");
                    sender.sendMessage(ChatColor.GRAY + "Valid blocks: DIRT_PATH, STONE_BRICKS, POLISHED_ANDESITE, etc.");
                }

                yield true;
            }

            case "register" -> {
                // Register existing road blocks in the world for a team
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command must be run by a player.");
                    yield true;
                }
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply register <region> <red|blue>");
                    sender.sendMessage(ChatColor.GRAY + "Scans the region for existing path blocks and registers them.");
                    yield true;
                }

                String regionId = resolveRegionId(args[2]);
                String team = args[3].toLowerCase();
                String regionName = getRegionDisplayName(regionId);

                // Check for active round FIRST
                if (roundService.getCurrentRound().isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No active round! Road blocks cannot be registered without an active round.");
                    sender.sendMessage(ChatColor.GRAY + "Start a new round with /round new or restart the server.");
                    yield true;
                }

                // Get region bounds
                int[] bounds = getRegionBounds(regionId);
                if (bounds == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid region: " + regionId);
                    yield true;
                }

                sender.sendMessage(ChatColor.YELLOW + "Scanning " + regionName + " [" + regionId + "] for path blocks...");
                sender.sendMessage(ChatColor.GRAY + "Round ID: " + roundService.getCurrentRound().get().roundId());
                sender.sendMessage(ChatColor.GRAY + "Using adaptive terrain scanning (async)...");

                World world = player.getWorld();
                int minX = bounds[0], maxX = bounds[1], minZ = bounds[2], maxZ = bounds[3];

                // Get valid path block types from config
                List<String> pathBlockNames = configManager.getSupplyPathBlocks();
                Set<Material> pathBlocks = new HashSet<>();
                if (pathBlockNames == null || pathBlockNames.isEmpty()) {
                    pathBlocks.add(Material.DIRT_PATH);
                    pathBlocks.add(Material.GRAVEL);
                    pathBlocks.add(Material.COBBLESTONE);
                    pathBlocks.add(Material.STONE_BRICKS);
                    pathBlocks.add(Material.POLISHED_ANDESITE);
                } else {
                    for (String name : pathBlockNames) {
                        try {
                            pathBlocks.add(Material.valueOf(name.toUpperCase()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                UUID playerUuid = player.getUniqueId();
                final int START_Y = 52;
                final int AIR_THRESHOLD = 100; // High threshold for elevated roads/bridges
                final int SOLID_THRESHOLD = 10;

                // Collect block positions in chunks on main thread, then batch insert async
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<int[]> foundBlocks = new ArrayList<>();
                    long startTime = System.currentTimeMillis();

                    // Scan the world on main thread in batches
                    final int BATCH_SIZE = 16; // Process 16 X columns at a time

                    for (int xStart = minX; xStart <= maxX; xStart += BATCH_SIZE) {
                        final int xBatchStart = xStart;
                        final int xBatchEnd = Math.min(xStart + BATCH_SIZE - 1, maxX);

                        // Check if plugin is still enabled
                        if (!plugin.isEnabled()) {
                            return;
                        }

                        // Collect blocks for this batch on main thread
                        List<int[]> batchBlocks;
                        try {
                            batchBlocks = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                                List<int[]> batch = new ArrayList<>();
                                Set<String> scanned = new HashSet<>();

                                for (int x = xBatchStart; x <= xBatchEnd; x++) {
                                    for (int z = minZ; z <= maxZ; z++) {
                                        // Scan up from START_Y
                                        int consecutiveAir = 0;
                                        for (int y = START_Y; y < world.getMaxHeight() && consecutiveAir < AIR_THRESHOLD; y++) {
                                            Block block = world.getBlockAt(x, y, z);
                                            Material type = block.getType();

                                            if (type.isAir()) {
                                                consecutiveAir++;
                                            } else {
                                                consecutiveAir = 0;
                                                if (pathBlocks.contains(type)) {
                                                    String key = x + "," + y + "," + z;
                                                    if (!scanned.contains(key)) {
                                                        scanned.add(key);
                                                        batch.add(new int[]{x, y, z});
                                                    }
                                                }
                                            }
                                        }

                                        // Scan down from START_Y
                                        int consecutiveSolid = 0;
                                        for (int y = START_Y - 1; y >= world.getMinHeight() && consecutiveSolid < SOLID_THRESHOLD; y--) {
                                            Block block = world.getBlockAt(x, y, z);
                                            Material type = block.getType();

                                            if (pathBlocks.contains(type)) {
                                                String key = x + "," + y + "," + z;
                                                if (!scanned.contains(key)) {
                                                    scanned.add(key);
                                                    batch.add(new int[]{x, y, z});
                                                }
                                                consecutiveSolid = 0;
                                            } else if (type.isAir()) {
                                                consecutiveSolid = 0;
                                            } else {
                                                consecutiveSolid++;
                                            }
                                        }
                                    }
                                }
                                return batch;
                            }).get();
                        } catch (Exception e) {
                            continue; // Skip this batch on error
                        }

                        foundBlocks.addAll(batchBlocks);

                        // Progress update
                        int progress = (int) (((double)(xBatchEnd - minX) / (maxX - minX)) * 100);
                        if (plugin.isEnabled() && player.isOnline()) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                player.sendMessage(ChatColor.GRAY + "Scanning: " + progress + "% (" + foundBlocks.size() + " blocks found)"));
                        }
                    }

                    // Now batch insert all found blocks WITHOUT triggering recalculation
                    int registered = 0;
                    for (int[] coords : foundBlocks) {
                        roadService.insertRoadBlockWithoutRecalculation(coords[0], coords[1], coords[2], playerUuid, team);
                        registered++;
                    }

                    // Recalculate supply ONCE at the end
                    roadService.recalculateSupply(team);

                    long elapsed = System.currentTimeMillis() - startTime;
                    final int totalRegistered = registered;

                    // Send completion message on main thread
                    if (plugin.isEnabled() && player.isOnline()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.sendMessage("");
                            player.sendMessage(ChatColor.GREEN + "✓ " + ChatColor.BOLD + "SCAN COMPLETE!");
                            player.sendMessage(ChatColor.GRAY + "Region: " + ChatColor.WHITE + regionName + " [" + regionId + "]");
                            player.sendMessage(ChatColor.GRAY + "Team: " + ChatColor.WHITE + team);
                            player.sendMessage(ChatColor.GRAY + "Blocks Registered: " + ChatColor.GREEN + totalRegistered);
                            player.sendMessage(ChatColor.GRAY + "Time: " + ChatColor.WHITE + (elapsed / 1000.0) + "s");
                            player.sendMessage("");
                            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                        });
                    }
                });

                yield true;
            }

            case "checkgap" -> {
                // Check what blocks exist in a specific X range and compare DB vs World
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command must be run by a player.");
                    yield true;
                }
                if (args.length < 6) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin supply checkgap <region> <minX> <maxX> <red|blue>");
                    sender.sendMessage(ChatColor.GRAY + "Checks what road blocks exist in DB and world between minX and maxX.");
                    yield true;
                }

                String regionId = resolveRegionId(args[2]);
                int minX, maxX;
                try {
                    minX = Integer.parseInt(args[3]);
                    maxX = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid X coordinates.");
                    yield true;
                }
                String team = args[5].toLowerCase();
                String regionName = getRegionDisplayName(regionId);

                sender.sendMessage(ChatColor.GOLD + "=== Gap Check: " + regionName + " [" + regionId + "] ===");
                sender.sendMessage(ChatColor.GRAY + "Checking X range: " + minX + " to " + maxX);
                sender.sendMessage(ChatColor.GRAY + "Team: " + team);

                // Get all road blocks in the region
                List<RoadBlock> allBlocks = roadService.getRoadBlocksInRegion(regionId, team);

                // Filter to the X range
                List<RoadBlock> blocksInRange = allBlocks.stream()
                        .filter(b -> b.x() >= minX && b.x() <= maxX)
                        .sorted(Comparator.comparingInt(RoadBlock::x))
                        .toList();

                sender.sendMessage(ChatColor.YELLOW + "Blocks in DB for X range: " + ChatColor.WHITE + blocksInRange.size());

                if (blocksInRange.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "NO blocks registered in DB for this X range!");
                } else {
                    // Show blocks grouped by X
                    Map<Integer, List<RoadBlock>> byX = blocksInRange.stream()
                            .collect(Collectors.groupingBy(RoadBlock::x));

                    sender.sendMessage(ChatColor.YELLOW + "Blocks by X coordinate:");
                    byX.keySet().stream().sorted().limit(20).forEach(x -> {
                        List<RoadBlock> xBlocks = byX.get(x);
                        sender.sendMessage(ChatColor.GRAY + "  X=" + x + ": " + ChatColor.WHITE + xBlocks.size() + " blocks" +
                                " (Y range: " + xBlocks.stream().mapToInt(RoadBlock::y).min().orElse(0) +
                                "-" + xBlocks.stream().mapToInt(RoadBlock::y).max().orElse(0) + ")");
                    });
                    if (byX.size() > 20) {
                        sender.sendMessage(ChatColor.GRAY + "  ... and " + (byX.size() - 20) + " more X coordinates");
                    }
                }

                // Now check the actual world
                sender.sendMessage(ChatColor.YELLOW + "Scanning actual world blocks...");
                World world = player.getWorld();

                // Get valid path block types
                List<String> pathBlockNames = configManager.getSupplyPathBlocks();
                Set<Material> pathBlocks = new HashSet<>();
                if (pathBlockNames == null || pathBlockNames.isEmpty()) {
                    pathBlocks.add(Material.DIRT_PATH);
                    pathBlocks.add(Material.GRAVEL);
                    pathBlocks.add(Material.COBBLESTONE);
                    pathBlocks.add(Material.STONE_BRICKS);
                    pathBlocks.add(Material.POLISHED_ANDESITE);
                } else {
                    for (String name : pathBlockNames) {
                        try {
                            pathBlocks.add(Material.valueOf(name.toUpperCase()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                // Get region Z bounds
                int[] bounds = getRegionBounds(regionId);
                if (bounds == null) {
                    sender.sendMessage(ChatColor.RED + "Could not get region bounds.");
                    yield true;
                }
                int minZ = bounds[2], maxZ = bounds[3];

                // Count actual world blocks in the X range
                int worldBlockCount = 0;
                Map<Integer, Integer> worldByX = new TreeMap<>();

                for (int x = minX; x <= maxX; x++) {
                    int xCount = 0;
                    for (int z = minZ; z <= maxZ; z++) {
                        // Scan Y levels around common road heights
                        for (int y = 60; y <= 130; y++) {
                            Block block = world.getBlockAt(x, y, z);
                            if (pathBlocks.contains(block.getType())) {
                                xCount++;
                                worldBlockCount++;
                            }
                        }
                    }
                    if (xCount > 0) {
                        worldByX.put(x, xCount);
                    }
                }

                sender.sendMessage(ChatColor.YELLOW + "Blocks in WORLD for X range: " + ChatColor.WHITE + worldBlockCount);

                if (worldBlockCount == 0) {
                    sender.sendMessage(ChatColor.RED + "NO path blocks found in world for this X range!");
                    sender.sendMessage(ChatColor.GRAY + "Path block types checked: " + pathBlocks);
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "World blocks by X coordinate:");
                    worldByX.keySet().stream().limit(20).forEach(x -> {
                        int count = worldByX.get(x);
                        boolean inDb = blocksInRange.stream().anyMatch(b -> b.x() == x);
                        ChatColor color = inDb ? ChatColor.GREEN : ChatColor.RED;
                        sender.sendMessage(ChatColor.GRAY + "  X=" + x + ": " + color + count + " blocks" +
                                (inDb ? " ✓ in DB" : " ✗ NOT in DB!"));
                    });
                    if (worldByX.size() > 20) {
                        sender.sendMessage(ChatColor.GRAY + "  ... and " + (worldByX.size() - 20) + " more X coordinates");
                    }
                }

                // Summary
                int dbXCount = (int) blocksInRange.stream().map(RoadBlock::x).distinct().count();
                int worldXCount = worldByX.size();

                if (worldXCount > 0 && dbXCount == 0) {
                    sender.sendMessage(ChatColor.RED + "⚠ PROBLEM: World has " + worldXCount + " X positions with roads, but DB has NONE!");
                    sender.sendMessage(ChatColor.YELLOW + "Run: /admin supply register " + regionId + " " + team);
                } else if (worldXCount > dbXCount) {
                    sender.sendMessage(ChatColor.YELLOW + "⚠ World has more X positions (" + worldXCount + ") than DB (" + dbXCount + ").");
                    sender.sendMessage(ChatColor.GRAY + "Some blocks may not be registered. Try re-registering.");
                } else if (dbXCount > 0 && worldXCount == 0) {
                    sender.sendMessage(ChatColor.RED + "⚠ DB has blocks but world doesn't! DB data may be stale.");
                }

                yield true;
            }

            case "clear" -> {
                if (args.length < 3) {
                    roadService.clearAllData();
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "All road data cleared.");
                } else {
                    String regionId = resolveRegionId(args[2]);
                    String regionName = getRegionDisplayName(regionId);
                    roadService.clearRegionData(regionId);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Road data cleared for " + regionName + " [" + regionId + "]");
                }
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown supply action: " + action);
                yield true;
            }
        };
    }

    // ==================== MERIT COMMANDS ====================

    private boolean handleMerit(CommandSender sender, String[] args) {
        if (meritService == null) {
            sender.sendMessage(ChatColor.RED + "Merit service not available.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin merit <give|givetokens|set|reset|info|leaderboard>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "give" -> {
                // /admin merit give <player> <amount> [merits|tokens]
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin merit give <player> <amount> [merits|tokens]");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                try {
                    int amount = Integer.parseInt(args[3]);
                    if (amount <= 0) {
                        sender.sendMessage(ChatColor.RED + "Amount must be positive.");
                        yield true;
                    }
                    String type = args.length > 4 ? args[4].toLowerCase() : "merits";
                    if (type.equals("tokens")) {
                        meritService.adminGiveTokens(target.getUniqueId(), amount);
                        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Gave " + amount + " tokens to " + target.getName());
                        target.sendMessage(ChatColor.GOLD + "You received " + amount + " merit tokens from an admin!");
                    } else {
                        meritService.adminGiveMerits(target.getUniqueId(), amount);
                        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Gave " + amount + " merits to " + target.getName());
                        target.sendMessage(ChatColor.GOLD + "You received " + amount + " merits from an admin!");
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[3]);
                }
                yield true;
            }
            case "givetokens" -> {
                // Shortcut for give tokens
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin merit givetokens <player> <amount>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                try {
                    int amount = Integer.parseInt(args[3]);
                    if (amount <= 0) {
                        sender.sendMessage(ChatColor.RED + "Amount must be positive.");
                        yield true;
                    }
                    meritService.adminGiveTokens(target.getUniqueId(), amount);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Gave " + amount + " tokens to " + target.getName());
                    target.sendMessage(ChatColor.GOLD + "You received " + amount + " merit tokens from an admin!");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[3]);
                }
                yield true;
            }
            case "set" -> {
                // /admin merit set <player> <amount>
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin merit set <player> <amount>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                try {
                    int amount = Integer.parseInt(args[3]);
                    if (amount < 0) {
                        sender.sendMessage(ChatColor.RED + "Amount cannot be negative.");
                        yield true;
                    }
                    meritService.adminSetMerits(target.getUniqueId(), amount);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Set " + target.getName() + "'s merits to " + amount);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[3]);
                }
                yield true;
            }
            case "reset" -> {
                // /admin merit reset <player>
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin merit reset <player>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                meritService.adminReset(target.getUniqueId());
                sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Reset merit data for " + target.getName());
                yield true;
            }
            case "info" -> {
                // /admin merit info <player>
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin merit info <player>");
                    yield true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    yield true;
                }
                var dataOpt = meritService.getPlayerData(target.getUniqueId());
                if (dataOpt.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No merit data for " + target.getName());
                    yield true;
                }
                PlayerMeritData data = dataOpt.get();
                MeritRank rank = data.getRank();
                sender.sendMessage(ChatColor.GOLD + "=== Merit Info: " + target.getName() + " ===");
                sender.sendMessage(ChatColor.GRAY + "Rank: " + rank.getColor() + rank.getDisplayName() + " [" + rank.getTag() + "]");
                sender.sendMessage(ChatColor.GRAY + "Tokens: " + ChatColor.WHITE + data.tokenBalance());
                sender.sendMessage(ChatColor.GRAY + "Received Merits: " + ChatColor.WHITE + data.receivedMerits());
                sender.sendMessage(ChatColor.GRAY + "Total Kills: " + ChatColor.WHITE + data.lifetimeKills());
                sender.sendMessage(ChatColor.GRAY + "Region Captures: " + ChatColor.WHITE + data.lifetimeCaptures());
                sender.sendMessage(ChatColor.GRAY + "Login Streak: " + ChatColor.WHITE + data.loginStreak() + " days");
                MeritRank nextRank = rank.getNextRank();
                if (nextRank != null) {
                    int needed = nextRank.getMeritsRequired() - data.receivedMerits();
                    sender.sendMessage(ChatColor.GRAY + "Next Rank: " + nextRank.getColor() + nextRank.getDisplayName() +
                            ChatColor.GRAY + " (" + needed + " merits needed)");
                }
                yield true;
            }
            case "leaderboard" -> {
                // /admin merit leaderboard [count]
                int count = 10;
                if (args.length > 2) {
                    try {
                        count = Integer.parseInt(args[2]);
                        count = Math.min(count, 50); // Cap at 50
                    } catch (NumberFormatException ignored) {}
                }
                List<PlayerMeritData> leaderboard = meritService.getLeaderboard(count);
                sender.sendMessage(ChatColor.GOLD + "=== Merit Leaderboard (Top " + count + ") ===");
                int position = 1;
                for (PlayerMeritData data : leaderboard) {
                    MeritRank rank = data.getRank();
                    String playerName = Bukkit.getOfflinePlayer(data.uuid()).getName();
                    if (playerName == null) playerName = data.uuid().toString().substring(0, 8);
                    sender.sendMessage(ChatColor.YELLOW + "#" + position + " " +
                            rank.getColor() + "[" + rank.getTag() + "] " +
                            ChatColor.WHITE + playerName +
                            ChatColor.GRAY + " - " + data.receivedMerits() + " merits");
                    position++;
                }
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown merit action: " + action);
                sender.sendMessage(ChatColor.YELLOW + "Usage: /admin merit <give|givetokens|set|reset|info|leaderboard>");
                yield true;
            }
        };
    }

    // ==================== OBJECTIVE COMMANDS ====================

    private boolean handleObjective(CommandSender sender, String[] args) {
        if (objectiveService == null) {
            sender.sendMessage(ChatColor.RED + "Objective service not available.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin objective <list|info|spawn|expire|tp>");
            return true;
        }

        String action = args[1].toLowerCase();

        return switch (action) {
            case "list" -> {
                // List objectives in a region or all regions
                String regionId = args.length >= 3 ? args[2].toUpperCase() : null;

                if (regionId != null) {
                    // List objectives in specific region
                    listObjectivesInRegion(sender, regionId);
                } else {
                    // List objectives in all regions
                    sender.sendMessage(ChatColor.GOLD + "=== Active Objectives (All Regions) ===");
                    int total = 0;
                    for (RegionStatus status : regionService.getAllRegionStatuses()) {
                        List<RegionObjective> objectives = objectiveService.getActiveObjectives(status.regionId(), null);
                        if (!objectives.isEmpty()) {
                            total += objectives.size();
                            sender.sendMessage(ChatColor.YELLOW + status.regionId() + ChatColor.GRAY + " (" + status.state() + ")" + ChatColor.WHITE + ": " + objectives.size() + " objectives");
                        }
                    }
                    sender.sendMessage(ChatColor.GRAY + "Total: " + ChatColor.WHITE + total + " active objectives");
                    sender.sendMessage(ChatColor.GRAY + "Use /admin objective list <regionId> for details");
                }
                yield true;
            }
            case "info" -> {
                // Show detailed info about objectives in a region
                if (args.length < 3) {
                    // If player, use their current region
                    if (sender instanceof Player player) {
                        String regionId = regionService.getRegionIdForLocation(
                                player.getLocation().getBlockX(),
                                player.getLocation().getBlockZ()
                        );
                        if (regionId != null) {
                            listObjectivesInRegion(sender, regionId);
                        } else {
                            sender.sendMessage(ChatColor.RED + "You are not in a valid region.");
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Usage: /admin objective info <regionId>");
                    }
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                listObjectivesInRegion(sender, regionId);
                yield true;
            }
            case "spawn" -> {
                // Manually spawn an objective
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin objective spawn <regionId> <type>");
                    sender.sendMessage(ChatColor.GRAY + "Types: SETTLEMENT_RESOURCE_DEPOT, SETTLEMENT_SECURE_PERIMETER, etc.");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                String typeName = args[3].toUpperCase();
                try {
                    ObjectiveType type = ObjectiveType.valueOf(typeName);
                    ObjectiveService.SpawnResult result = objectiveService.spawnObjective(regionId, type);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Spawn result: " + result);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid objective type: " + typeName);
                    sender.sendMessage(ChatColor.GRAY + "Valid types: ");
                    for (ObjectiveType t : ObjectiveType.values()) {
                        sender.sendMessage(ChatColor.GRAY + "  - " + t.name());
                    }
                }
                yield true;
            }
            case "expire" -> {
                // Expire objectives
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin objective expire <regionId|all>");
                    yield true;
                }
                String regionId = args[2].toUpperCase();
                if (regionId.equals("ALL")) {
                    for (RegionStatus status : regionService.getAllRegionStatuses()) {
                        objectiveService.expireObjectivesInRegion(status.regionId());
                    }
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Expired all objectives in all regions.");
                } else {
                    objectiveService.expireObjectivesInRegion(regionId);
                    sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Expired all objectives in " + regionId);
                }
                yield true;
            }
            case "tp" -> {
                // Teleport to an objective
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                    yield true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /admin objective tp <objectiveId>");
                    yield true;
                }
                try {
                    int objectiveId = Integer.parseInt(args[2]);
                    // Find the objective
                    boolean found = false;
                    for (RegionStatus status : regionService.getAllRegionStatuses()) {
                        for (RegionObjective obj : objectiveService.getActiveObjectives(status.regionId(), null)) {
                            if (obj.id() == objectiveId) {
                                if (obj.hasLocation()) {
                                    Location loc = obj.getLocation(player.getWorld());
                                    if (loc != null) {
                                        player.teleport(loc);
                                        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN +
                                                "Teleported to " + obj.type().getDisplayName() + " at " +
                                                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                                    }
                                } else {
                                    sender.sendMessage(ChatColor.RED + "This objective has no location.");
                                }
                                found = true;
                                break;
                            }
                        }
                        if (found) break;
                    }
                    if (!found) {
                        sender.sendMessage(ChatColor.RED + "Objective #" + objectiveId + " not found.");
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid objective ID.");
                }
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown objective action: " + action);
                sender.sendMessage(ChatColor.YELLOW + "Usage: /admin objective <list|info|spawn|expire|tp>");
                yield true;
            }
        };
    }

    /**
     * Lists objectives in a specific region with full details.
     */
    private void listObjectivesInRegion(CommandSender sender, String regionId) {
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Region not found: " + regionId);
            return;
        }

        RegionStatus status = statusOpt.get();
        sender.sendMessage(ChatColor.GOLD + "=== Objectives in " + regionId + " ===");
        sender.sendMessage(ChatColor.GRAY + "State: " + ChatColor.WHITE + status.state() +
                (status.ownerTeam() != null ? ChatColor.GRAY + " (Owner: " + status.ownerTeam() + ")" : ""));

        // Get all objectives (both categories)
        List<RegionObjective> settlementObjs = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.SETTLEMENT);
        List<RegionObjective> raidObjs = objectiveService.getActiveObjectives(regionId, ObjectiveCategory.RAID);

        if (settlementObjs.isEmpty() && raidObjs.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No active objectives in this region.");
            return;
        }

        // Show settlement objectives
        if (!settlementObjs.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + "⚒ Settlement Objectives:");
            for (RegionObjective obj : settlementObjs) {
                showObjectiveDetails(sender, obj);
            }
        }

        // Show raid objectives
        if (!raidObjs.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "⚔ Raid Objectives:");
            for (RegionObjective obj : raidObjs) {
                showObjectiveDetails(sender, obj);
            }
        }
    }

    /**
     * Shows detailed information about a single objective.
     */
    private void showObjectiveDetails(CommandSender sender, RegionObjective obj) {
        sender.sendMessage(ChatColor.YELLOW + "  #" + obj.id() + " " + ChatColor.WHITE + obj.type().getDisplayName());
        sender.sendMessage(ChatColor.GRAY + "    Progress: " + ChatColor.WHITE + obj.getProgressPercent() + "%");
        sender.sendMessage(ChatColor.GRAY + "    IP Reward: " + ChatColor.GOLD + obj.getInfluenceReward());

        if (obj.hasLocation()) {
            sender.sendMessage(ChatColor.GRAY + "    Location: " + ChatColor.AQUA +
                    obj.locationX() + ", " + obj.locationY() + ", " + obj.locationZ());
        } else {
            sender.sendMessage(ChatColor.GRAY + "    Location: " + ChatColor.DARK_GRAY + "No specific location");
        }

        sender.sendMessage(ChatColor.GRAY + "    Description: " + ChatColor.WHITE + obj.getProgressDescription());
    }

    // ==================== SERVER COMMANDS ====================

    private boolean handleReload(CommandSender sender) {
        configManager.reload();
        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Configuration reloaded.");
        return true;
    }

    // ==================== DEPOT COMMANDS ====================

    private boolean handleDepot(CommandSender sender, String[] args) {
        if (depotService == null) {
            sender.sendMessage(ChatColor.RED + "Depot system is not enabled.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin depot <list|info|give|givetool|clear|remove>");
            return true;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> handleDepotList(sender, args);
            case "info" -> handleDepotInfo(sender, args);
            case "give" -> handleDepotGive(sender, args);
            case "givetool" -> handleDepotGiveTool(sender, args);
            case "clear" -> handleDepotClear(sender, args);
            case "remove" -> handleDepotRemove(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Unknown depot action: " + action);
        }

        return true;
    }

    private void handleDepotList(CommandSender sender, String[] args) {
        // /admin depot list [team|region|division]
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin depot list <team|region|division> <name>");
            sender.sendMessage(ChatColor.GRAY + "Examples:");
            sender.sendMessage(ChatColor.GRAY + "  /admin depot list team red");
            sender.sendMessage(ChatColor.GRAY + "  /admin depot list region B2");
            sender.sendMessage(ChatColor.GRAY + "  /admin depot list division 1");
            return;
        }

        String type = args[2].toLowerCase();

        if (args.length < 4 && !type.equals("all")) {
            sender.sendMessage(ChatColor.RED + "Please specify a " + type + " name.");
            return;
        }

        List<DepotLocation> depots;
        String label;

        switch (type) {
            case "team" -> {
                String team = args[3].toLowerCase();
                depots = depotService.getDepotsForTeam(team);
                label = "Team " + team.toUpperCase();
            }
            case "region" -> {
                String regionId = args[3].toUpperCase();
                depots = depotService.getDepotsInRegion(regionId);
                label = "Region " + regionId;
            }
            case "division" -> {
                try {
                    int divisionId = Integer.parseInt(args[3]);
                    depots = depotService.getDepotsForDivision(divisionId);
                    label = "Division #" + divisionId;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid division ID: " + args[3]);
                    return;
                }
            }
            case "all" -> {
                List<DepotLocation> red = depotService.getDepotsForTeam("red");
                List<DepotLocation> blue = depotService.getDepotsForTeam("blue");
                depots = new ArrayList<>(red);
                depots.addAll(blue);
                label = "All Depots";
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Invalid type. Use: team, region, division, or all");
                return;
            }
        }

        sender.sendMessage(ChatColor.GOLD + "=== " + label + " Depots (" + depots.size() + ") ===");

        if (depots.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No depots found.");
            return;
        }

        for (DepotLocation depot : depots) {
            String divName = divisionService != null ?
                    divisionService.getDivision(depot.divisionId())
                            .map(Division::formattedTag).orElse("Unknown") : "Div#" + depot.divisionId();
            boolean vulnerable = depotService.isDepotVulnerable(depot);

            sender.sendMessage(ChatColor.GRAY + " - " +
                    ChatColor.WHITE + depot.regionId() + " " +
                    ChatColor.GRAY + "(" + depot.x() + "," + depot.y() + "," + depot.z() + ") " +
                    ChatColor.YELLOW + divName + " " +
                    (vulnerable ? ChatColor.RED + "[VULNERABLE]" : ChatColor.GREEN + "[PROTECTED]"));
        }
    }

    private void handleDepotInfo(CommandSender sender, String[] args) {
        // /admin depot info - shows depot at player location
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return;
        }

        // Check the block the player is looking at
        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null) {
            sender.sendMessage(ChatColor.RED + "Look at a depot block to get info.");
            return;
        }

        Optional<DepotLocation> depotOpt = depotService.getDepotAt(targetBlock.getLocation());
        if (depotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "That block is not a division depot.");
            return;
        }

        DepotLocation depot = depotOpt.get();
        String divName = divisionService != null ?
                divisionService.getDivision(depot.divisionId())
                        .map(d -> d.formattedTag() + " (" + d.name() + ")").orElse("Unknown") :
                "Division #" + depot.divisionId();

        sender.sendMessage(ChatColor.GOLD + "=== Depot Info ===");
        sender.sendMessage(ChatColor.GRAY + "Location: " + ChatColor.WHITE + depot.x() + ", " + depot.y() + ", " + depot.z());
        sender.sendMessage(ChatColor.GRAY + "Region: " + ChatColor.WHITE + depot.regionId());
        sender.sendMessage(ChatColor.GRAY + "Division: " + ChatColor.WHITE + divName);
        sender.sendMessage(ChatColor.GRAY + "Placed By: " + ChatColor.WHITE + depot.placedBy());
        sender.sendMessage(ChatColor.GRAY + "Placed At: " + ChatColor.WHITE + new Date(depot.placedAt()));
        sender.sendMessage(ChatColor.GRAY + "Vulnerable: " +
                (depotService.isDepotVulnerable(depot) ? ChatColor.RED + "YES" : ChatColor.GREEN + "NO"));
    }

    private void handleDepotGive(CommandSender sender, String[] args) {
        // /admin depot give <player> [amount]
        if (depotItem == null) {
            sender.sendMessage(ChatColor.RED + "Depot item factory not available.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin depot give <player> [amount]");
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                amount = Math.min(64, Math.max(1, amount));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[3]);
                return;
            }
        }

        var item = depotItem.createGenericDepotBlock();
        if (item != null) {
            item.setAmount(amount);
            target.getInventory().addItem(item);
            sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Gave " + amount +
                    " Division Depot block(s) to " + target.getName());
            target.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "You received " + amount +
                    " Division Depot block(s) from an admin.");
        } else {
            sender.sendMessage(ChatColor.RED + "Failed to create depot item.");
        }
    }

    private void handleDepotGiveTool(CommandSender sender, String[] args) {
        // /admin depot givetool <player> [amount]
        if (depotItem == null) {
            sender.sendMessage(ChatColor.RED + "Depot item factory not available.");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin depot givetool <player> [amount]");
            return;
        }

        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                amount = Math.min(64, Math.max(1, amount));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid amount: " + args[3]);
                return;
            }
        }

        var item = depotItem.createRaidTool();
        if (item != null) {
            item.setAmount(amount);
            target.getInventory().addItem(item);
            sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Gave " + amount +
                    " Raid Tool(s) to " + target.getName());
            target.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "You received " + amount +
                    " Raid Tool(s) from an admin.");
        } else {
            sender.sendMessage(ChatColor.RED + "Failed to create raid tool.");
        }
    }

    private void handleDepotClear(CommandSender sender, String[] args) {
        // /admin depot clear <team|region|division|all> <name>
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /admin depot clear <team|region|division|all> [name]");
            return;
        }

        String type = args[2].toLowerCase();
        List<DepotLocation> depotsToRemove;
        String label;

        switch (type) {
            case "team" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Specify team: red or blue");
                    return;
                }
                String team = args[3].toLowerCase();
                depotsToRemove = depotService.getDepotsForTeam(team);
                label = "team " + team;
            }
            case "region" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Specify region ID");
                    return;
                }
                String regionId = args[3].toUpperCase();
                depotsToRemove = depotService.getDepotsInRegion(regionId);
                label = "region " + regionId;
            }
            case "division" -> {
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Specify division ID");
                    return;
                }
                try {
                    int divisionId = Integer.parseInt(args[3]);
                    depotsToRemove = depotService.getDepotsForDivision(divisionId);
                    label = "division #" + divisionId;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid division ID");
                    return;
                }
            }
            case "all" -> {
                List<DepotLocation> red = depotService.getDepotsForTeam("red");
                List<DepotLocation> blue = depotService.getDepotsForTeam("blue");
                depotsToRemove = new ArrayList<>(red);
                depotsToRemove.addAll(blue);
                label = "ALL depots";
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Invalid type. Use: team, region, division, or all");
                return;
            }
        }

        int count = depotsToRemove.size();
        for (DepotLocation depot : depotsToRemove) {
            // Remove the physical block
            World world = Bukkit.getWorld(depot.world());
            if (world != null) {
                world.getBlockAt(depot.x(), depot.y(), depot.z()).setType(Material.AIR);
            }
            // Remove from database
            depotService.breakDepot(null, new Location(world, depot.x(), depot.y(), depot.z()));
        }

        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Cleared " + count + " depot(s) from " + label);
    }

    private void handleDepotRemove(CommandSender sender, String[] args) {
        // /admin depot remove - removes depot player is looking at
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null) {
            sender.sendMessage(ChatColor.RED + "Look at a depot block to remove it.");
            return;
        }

        Optional<DepotLocation> depotOpt = depotService.getDepotAt(targetBlock.getLocation());
        if (depotOpt.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "That block is not a division depot.");
            return;
        }

        DepotLocation depot = depotOpt.get();

        // Remove the block
        targetBlock.setType(Material.AIR);

        // Remove from database
        depotService.breakDepot(null, targetBlock.getLocation());

        sender.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Removed depot at " +
                depot.x() + ", " + depot.y() + ", " + depot.z());
    }

    private boolean handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Server Status ===");

        // Round info
        Optional<Round> roundOpt = roundService.getCurrentRound();
        if (roundOpt.isPresent()) {
            Round round = roundOpt.get();
            sender.sendMessage(ChatColor.GRAY + "Round: " + ChatColor.WHITE + round.roundId() +
                    " (Phase " + round.currentPhase() + "/" + configManager.getMaxPhases() + ")");
        } else {
            sender.sendMessage(ChatColor.GRAY + "Round: " + ChatColor.YELLOW + "None active");
        }

        // Team counts
        long redCount = teamService.countTeamMembers("red");
        long blueCount = teamService.countTeamMembers("blue");
        sender.sendMessage(ChatColor.GRAY + "Teams: " + ChatColor.RED + redCount + " Red" +
                ChatColor.GRAY + " vs " + ChatColor.BLUE + blueCount + " Blue");

        // Online players
        sender.sendMessage(ChatColor.GRAY + "Online: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size());

        return true;
    }

    // ==================== HELP ====================

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Admin Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/admin round <new|end|info>" + ChatColor.GRAY + " - Round management");
        sender.sendMessage(ChatColor.YELLOW + "/admin phase <advance|set|info>" + ChatColor.GRAY + " - Phase management");
        sender.sendMessage(ChatColor.YELLOW + "/admin region <capture|reset|setstate|setowner|addip|info>" + ChatColor.GRAY + " - Region control");
        sender.sendMessage(ChatColor.YELLOW + "/admin team <setspawn|wipe|info>" + ChatColor.GRAY + " - Team management");
        sender.sendMessage(ChatColor.YELLOW + "/admin player <setteam|respawn|tp>" + ChatColor.GRAY + " - Player control");
        sender.sendMessage(ChatColor.YELLOW + "/admin supply <recalculate|info|clear>" + ChatColor.GRAY + " - Supply lines");
        sender.sendMessage(ChatColor.YELLOW + "/admin merit <give|givetokens|set|reset|info|leaderboard>" + ChatColor.GRAY + " - Merit system");
        sender.sendMessage(ChatColor.YELLOW + "/admin depot <list|info|give|givetool|clear|remove>" + ChatColor.GRAY + " - Division depots");
        sender.sendMessage(ChatColor.YELLOW + "/admin reload" + ChatColor.GRAY + " - Reload config");
        sender.sendMessage(ChatColor.YELLOW + "/admin status" + ChatColor.GRAY + " - Server status");
    }

    // ==================== TAB COMPLETION ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return Collections.emptyList();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Main subcommands
            completions.addAll(Arrays.asList("round", "phase", "region", "team", "player", "supply", "merit", "depot", "reload", "status"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "round" -> completions.addAll(Arrays.asList("new", "end", "info"));
                case "phase" -> completions.addAll(Arrays.asList("advance", "set", "info"));
                case "region" -> completions.addAll(Arrays.asList("capture", "reset", "setstate", "setowner", "addip", "info"));
                case "team" -> completions.addAll(Arrays.asList("setspawn", "wipe", "info"));
                case "player" -> completions.addAll(Arrays.asList("setteam", "respawn", "tp"));
                case "supply" -> completions.addAll(Arrays.asList("recalculate", "info", "debug", "diagnose", "gaptest", "borderinfo", "roadpath", "scan", "scantest", "scanborder", "worldscan", "register", "clear"));
                case "merit" -> completions.addAll(Arrays.asList("give", "givetokens", "set", "reset", "info", "leaderboard"));
                case "depot" -> completions.addAll(Arrays.asList("list", "info", "give", "givetool", "clear", "remove"));
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();

            switch (sub) {
                case "round" -> {
                    if (action.equals("end")) {
                        completions.addAll(Arrays.asList("red", "blue", "draw"));
                    }
                }
                case "phase" -> {
                    if (action.equals("set")) {
                        for (int i = 1; i <= configManager.getMaxPhases(); i++) {
                            completions.add(String.valueOf(i));
                        }
                    }
                }
                case "region" -> {
                    if (action.equals("capture") || action.equals("reset") || action.equals("setstate") ||
                            action.equals("setowner") || action.equals("addip") || action.equals("info")) {
                        completions.addAll(getAllRegionIds());
                        if (action.equals("reset")) {
                            completions.add("all");
                        }
                    }
                }
                case "team" -> {
                    if (action.equals("setspawn") || action.equals("info")) {
                        completions.addAll(Arrays.asList("red", "blue"));
                    }
                }
                case "player" -> {
                    // Player names
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                }
                case "supply" -> {
                    if (action.equals("recalculate")) {
                        completions.addAll(Arrays.asList("red", "blue"));
                    } else if (action.equals("info") || action.equals("debug") || action.equals("clear")) {
                        // Add both region IDs and region names
                        completions.addAll(getAllRegionIds());
                        completions.addAll(getAllRegionNames());
                    }
                }
                case "merit" -> {
                    if (action.equals("give") || action.equals("givetokens") || action.equals("set") ||
                            action.equals("reset") || action.equals("info")) {
                        // Player names
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            completions.add(p.getName());
                        }
                    } else if (action.equals("leaderboard")) {
                        completions.addAll(Arrays.asList("5", "10", "20", "50"));
                    }
                }
                case "depot" -> {
                    if (action.equals("list") || action.equals("clear")) {
                        completions.addAll(Arrays.asList("team", "region", "division", "all"));
                    } else if (action.equals("give") || action.equals("givetool")) {
                        // Player names
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            completions.add(p.getName());
                        }
                    }
                }
            }
        } else if (args.length == 4) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();

            switch (sub) {
                case "region" -> {
                    if (action.equals("capture") || action.equals("setowner") || action.equals("addip")) {
                        completions.addAll(Arrays.asList("red", "blue"));
                    } else if (action.equals("setstate")) {
                        for (RegionState state : RegionState.values()) {
                            completions.add(state.name().toLowerCase());
                        }
                    }
                }
                case "player" -> {
                    if (action.equals("setteam")) {
                        completions.addAll(Arrays.asList("red", "blue", "none"));
                    } else if (action.equals("tp")) {
                        completions.addAll(getAllRegionNames());
                    }
                }
                case "supply" -> {
                    if (action.equals("info") || action.equals("debug")) {
                        completions.addAll(Arrays.asList("red", "blue"));
                    }
                }
                case "merit" -> {
                    if (action.equals("give") || action.equals("givetokens") || action.equals("set")) {
                        // Suggest common amounts
                        completions.addAll(Arrays.asList("1", "5", "10", "25", "50", "100"));
                    }
                }
                case "depot" -> {
                    String typeArg = args[2].toLowerCase();
                    if (action.equals("list") || action.equals("clear")) {
                        if (typeArg.equals("team")) {
                            completions.addAll(Arrays.asList("red", "blue"));
                        } else if (typeArg.equals("region")) {
                            completions.addAll(getAllRegionIds());
                        } else if (typeArg.equals("division")) {
                            completions.addAll(Arrays.asList("1", "2", "3", "4", "5"));
                        }
                    } else if (action.equals("give") || action.equals("givetool")) {
                        // Amounts
                        completions.addAll(Arrays.asList("1", "2", "5", "10"));
                    }
                }
            }
        } else if (args.length == 5) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();

            if (sub.equals("merit") && action.equals("give")) {
                completions.addAll(Arrays.asList("merits", "tokens"));
            }
        }

        // Filter by current input
        String currentArg = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(currentArg))
                .collect(Collectors.toList());
    }

    // ==================== HELPER METHODS ====================

    private List<String> getAllRegionIds() {
        List<String> regions = new ArrayList<>();
        for (char row = 'A'; row <= 'D'; row++) {
            for (int col = 1; col <= 4; col++) {
                regions.add(row + String.valueOf(col));
            }
        }
        return regions;
    }

    private List<String> getAllRegionNames() {
        if (regionRenderer != null) {
            Collection<String> names = regionRenderer.getAllRegionNames();
            if (!names.isEmpty()) {
                return new ArrayList<>(names);
            }
        }
        // Fallback to IDs if names not available
        return getAllRegionIds();
    }

    private String getRegionIdForPlayer(Player player) {
        int blockX = player.getLocation().getBlockX();
        int blockZ = player.getLocation().getBlockZ();

        int gridSize = 4;
        int regionBlocks = 512;
        int halfSize = (gridSize * regionBlocks) / 2;

        int gridX = (blockX + halfSize) / regionBlocks;
        int gridZ = (blockZ + halfSize) / regionBlocks;

        if (gridX < 0 || gridX >= gridSize || gridZ < 0 || gridZ >= gridSize) {
            return "Unknown";
        }

        char rowLabel = (char) ('A' + gridZ);
        return rowLabel + String.valueOf(gridX + 1);
    }

    private Location getRegionCenter(String regionId, World world) {
        if (regionId.length() < 2) return null;

        char row = regionId.charAt(0);
        int col;
        try {
            col = Integer.parseInt(regionId.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }

        int gridSize = 4;
        int regionBlocks = 512;
        int halfSize = (gridSize * regionBlocks) / 2;

        int gridZ = row - 'A';
        int gridX = col - 1;

        if (gridX < 0 || gridX >= gridSize || gridZ < 0 || gridZ >= gridSize) {
            return null;
        }

        int centerX = (gridX * regionBlocks) - halfSize + (regionBlocks / 2);
        int centerZ = (gridZ * regionBlocks) - halfSize + (regionBlocks / 2);
        int y = world.getHighestBlockYAt(centerX, centerZ) + 1;

        return new Location(world, centerX, y, centerZ);
    }

    private String formatLocation(Location loc) {
        return String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * Calculates the border search area between two adjacent regions.
     * Returns [minX, maxX, minZ, maxZ] or null if not adjacent.
     */
    private int[] calculateBorderArea(String region1, String region2, int borderWidth) {
        if (region1.length() < 2 || region2.length() < 2) return null;

        int gridSize = 4;
        int regionBlocks = 512;
        int halfSize = (gridSize * regionBlocks) / 2;

        char row1 = region1.charAt(0);
        int col1 = Integer.parseInt(region1.substring(1));
        char row2 = region2.charAt(0);
        int col2 = Integer.parseInt(region2.substring(1));

        // Calculate region bounds
        int region1MinX = (col1 - 1) * regionBlocks - halfSize;
        int region1MaxX = col1 * regionBlocks - halfSize - 1;
        int region1MinZ = (row1 - 'A') * regionBlocks - halfSize;
        int region1MaxZ = (row1 - 'A' + 1) * regionBlocks - halfSize - 1;

        int region2MinX = (col2 - 1) * regionBlocks - halfSize;
        int region2MaxX = col2 * regionBlocks - halfSize - 1;
        int region2MinZ = (row2 - 'A') * regionBlocks - halfSize;
        int region2MaxZ = (row2 - 'A' + 1) * regionBlocks - halfSize - 1;

        if (col1 == col2 && Math.abs(row1 - row2) == 1) {
            // Vertical neighbors (N/S)
            int borderZ = (row1 < row2) ? region1MaxZ : region1MinZ;
            return new int[]{
                    Math.max(region1MinX, region2MinX),
                    Math.min(region1MaxX, region2MaxX),
                    borderZ - borderWidth,
                    borderZ + borderWidth
            };
        } else if (row1 == row2 && Math.abs(col1 - col2) == 1) {
            // Horizontal neighbors (E/W)
            int borderX = (col1 < col2) ? region1MaxX : region1MinX;
            return new int[]{
                    borderX - borderWidth,
                    borderX + borderWidth,
                    Math.max(region1MinZ, region2MinZ),
                    Math.min(region1MaxZ, region2MaxZ)
            };
        }

        return null; // Not adjacent
    }

    /**
     * Gets the bounds of a region.
     * Returns [minX, maxX, minZ, maxZ].
     */
    private int[] getRegionBounds(String regionId) {
        if (regionId.length() < 2) return null;

        int gridSize = 4;
        int regionBlocks = 512;
        int halfSize = (gridSize * regionBlocks) / 2;

        char row = regionId.charAt(0);
        int col = Integer.parseInt(regionId.substring(1));

        int minX = (col - 1) * regionBlocks - halfSize;
        int maxX = col * regionBlocks - halfSize - 1;
        int minZ = (row - 'A') * regionBlocks - halfSize;
        int maxZ = (row - 'A' + 1) * regionBlocks - halfSize - 1;

        return new int[]{minX, maxX, minZ, maxZ};
    }
}

