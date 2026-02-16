package org.flintstqne.entrenched.RoadLogic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;

/**
 * Command for viewing supply status and finding road gaps.
 * Usage: /supply [gaps|status|help]
 */
public final class SupplyCommand implements CommandExecutor, TabCompleter {

    private final RoadService roadService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    public SupplyCommand(RoadService roadService, RegionService regionService,
                         TeamService teamService, ConfigManager configManager) {
        this.roadService = roadService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(configManager.getPrefix() + ChatColor.RED + "You must be on a team to use this command.");
            return true;
        }

        String team = teamOpt.get();

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "gaps" -> handleGaps(player, team, args);
            case "status" -> handleStatus(player, team);
            case "recalculate", "recalc", "refresh" -> handleRecalculate(player, team);
            case "help" -> {
                showHelp(player);
                yield true;
            }
            default -> {
                player.sendMessage(ChatColor.RED + "Unknown subcommand. Use /supply help");
                yield true;
            }
        };
    }

    private void showHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== Supply Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/supply gaps" + ChatColor.GRAY + " - Find road gaps in your regions");
        player.sendMessage(ChatColor.YELLOW + "/supply gaps <region>" + ChatColor.GRAY + " - Check gaps in specific region");
        player.sendMessage(ChatColor.YELLOW + "/supply status" + ChatColor.GRAY + " - View supply status of all regions");
        player.sendMessage(ChatColor.YELLOW + "/supply recalculate" + ChatColor.GRAY + " - Force refresh supply calculations");
    }

    private boolean handleRecalculate(Player player, String team) {
        player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW + "Recalculating supply for " + team + " team...");
        roadService.recalculateSupply(team);
        player.sendMessage(configManager.getPrefix() + ChatColor.GREEN + "Supply recalculation complete!");

        // Show updated status
        return handleStatus(player, team);
    }

    private boolean handleGaps(Player player, String team, String[] args) {
        player.sendMessage(ChatColor.GOLD + "=== Road Gap Analysis ===");
        player.sendMessage(ChatColor.GRAY + "Analyzing supply routes for " +
                (team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE) + team.toUpperCase() + ChatColor.GRAY + " team...");

        // Get home region
        String homeRegion = team.equalsIgnoreCase("red") ?
                configManager.getRegionRedHome() : configManager.getRegionBlueHome();

        // Get all owned regions
        List<RegionStatus> ownedRegions = regionService.getRegionsByOwner(team);

        if (ownedRegions.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Your team doesn't own any regions.");
            return true;
        }

        // If specific region requested
        if (args.length >= 2) {
            String regionId = args[1].toUpperCase();
            Optional<RegionStatus> regionOpt = ownedRegions.stream()
                    .filter(r -> r.regionId().equalsIgnoreCase(regionId))
                    .findFirst();

            if (regionOpt.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Region " + regionId + " is not owned by your team.");
                return true;
            }

            analyzeRegionGaps(player, team, regionId, homeRegion);
            return true;
        }

        // Analyze all regions
        List<String> regionsWithGaps = new ArrayList<>();
        List<String> disconnectedRegions = new ArrayList<>();
        Set<String> connected = roadService.getConnectedRegions(team);

        for (RegionStatus region : ownedRegions) {
            String regionId = region.regionId();
            SupplyLevel level = roadService.getSupplyLevel(regionId, team);

            if (!connected.contains(regionId)) {
                disconnectedRegions.add(regionId);
            } else if (level == SupplyLevel.PARTIAL) {
                regionsWithGaps.add(regionId);
            }
        }

        // Report disconnected regions
        if (!disconnectedRegions.isEmpty()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "⚠ Disconnected Regions (no road to home):");
            for (String regionId : disconnectedRegions) {
                // Find which adjacent regions could be connected to
                List<String> adjacentOwned = new ArrayList<>();
                for (String adj : regionService.getAdjacentRegions(regionId)) {
                    if (connected.contains(adj)) {
                        adjacentOwned.add(adj);
                    }
                }

                player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.WHITE + regionId +
                        ChatColor.GRAY + " - Build roads to: " + ChatColor.YELLOW +
                        (adjacentOwned.isEmpty() ? "No adjacent connected regions!" : String.join(", ", adjacentOwned)));
            }
        }

        // Report regions with internal gaps
        if (!regionsWithGaps.isEmpty()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.YELLOW + "⚠ Regions with Road Gaps (50% supply):");
            for (String regionId : regionsWithGaps) {
                player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.WHITE + regionId +
                        ChatColor.GRAY + " - Use " + ChatColor.YELLOW + "/supply gaps " + regionId +
                        ChatColor.GRAY + " for details");
            }
        }

        // Summary
        if (disconnectedRegions.isEmpty() && regionsWithGaps.isEmpty()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GREEN + "✓ All supply routes are intact! Full supply to all regions.");
        } else {
            player.sendMessage("");
            int totalIssues = disconnectedRegions.size() + regionsWithGaps.size();
            player.sendMessage(ChatColor.YELLOW + "Total regions with supply issues: " + ChatColor.WHITE + totalIssues);
        }

        return true;
    }

    private void analyzeRegionGaps(Player player, String team, String regionId, String homeRegion) {
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== Gap Analysis: " + regionId + " ===");

        // Check if it's connected
        Set<String> connected = roadService.getConnectedRegions(team);
        SupplyLevel level = roadService.getSupplyLevel(regionId, team);

        player.sendMessage(ChatColor.GRAY + "Supply Level: " + getSupplyLevelDisplay(level));
        player.sendMessage(ChatColor.GRAY + "Connected to Home: " +
                (connected.contains(regionId) ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));

        // Get road blocks in this region
        List<RoadBlock> blocks = roadService.getRoadBlocksInRegion(regionId, team);
        player.sendMessage(ChatColor.GRAY + "Road Blocks: " + ChatColor.WHITE + blocks.size());

        if (blocks.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No road blocks in this region! Build a road through it.");
            return;
        }

        // Find adjacent owned regions
        List<String> adjacentOwned = new ArrayList<>();
        for (String adj : regionService.getAdjacentRegions(regionId)) {
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
            if (adjStatus.isPresent() && adjStatus.get().isOwnedBy(team)) {
                adjacentOwned.add(adj);
            }
        }

        if (adjacentOwned.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No adjacent owned regions! This region is isolated.");
            return;
        }

        // Check border connections
        player.sendMessage("");
        player.sendMessage(ChatColor.YELLOW + "Border Connections:");
        for (String adj : adjacentOwned) {
            boolean hasConnection = roadService.hasRoadConnection(regionId, adj, team);
            ChatColor color = hasConnection ? ChatColor.GREEN : ChatColor.RED;
            String symbol = hasConnection ? "✓" : "✗";
            player.sendMessage(ChatColor.GRAY + "  " + regionId + " <-> " + adj + ": " +
                    color + symbol + " " + (hasConnection ? "Connected" : "No road connection"));

            if (!hasConnection) {
                // Give hint about where to build
                player.sendMessage(ChatColor.GRAY + "    → Build road blocks at the border between " +
                        ChatColor.WHITE + regionId + ChatColor.GRAY + " and " + ChatColor.WHITE + adj);
            }
        }

        // Find and display internal road gaps with coordinates
        List<String> gapInfo = roadService.findRoadGaps(regionId, team);
        if (!gapInfo.isEmpty()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "Internal Road Gaps:");
            for (String info : gapInfo) {
                // Color code the output
                if (info.startsWith("Found")) {
                    player.sendMessage(ChatColor.RED + info);
                } else if (info.startsWith("  Segment")) {
                    player.sendMessage(ChatColor.YELLOW + info);
                } else if (info.contains("→")) {
                    player.sendMessage(ChatColor.AQUA + info);
                } else {
                    player.sendMessage(ChatColor.GRAY + info);
                }
            }
        } else if (level == SupplyLevel.PARTIAL) {
            // PARTIAL but no gaps found - explain why
            player.sendMessage("");
            player.sendMessage(ChatColor.YELLOW + "Why 50% Supply?");
            if (!connected.contains(regionId)) {
                player.sendMessage(ChatColor.GRAY + "  • No continuous road path to home region (" + homeRegion + ")");
            } else {
                player.sendMessage(ChatColor.GRAY + "  • Missing border connections to adjacent regions");
                player.sendMessage(ChatColor.GRAY + "  • Check the border connections above");
            }
        }
    }

    private boolean handleStatus(Player player, String team) {
        player.sendMessage(ChatColor.GOLD + "=== Supply Status ===");

        List<RegionStatus> ownedRegions = regionService.getRegionsByOwner(team);

        if (ownedRegions.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Your team doesn't own any regions.");
            return true;
        }

        // Group by supply level
        Map<SupplyLevel, List<String>> byLevel = new EnumMap<>(SupplyLevel.class);
        for (SupplyLevel level : SupplyLevel.values()) {
            byLevel.put(level, new ArrayList<>());
        }

        for (RegionStatus region : ownedRegions) {
            SupplyLevel level = roadService.getSupplyLevel(region.regionId(), team);
            byLevel.get(level).add(region.regionId());
        }

        // Display each level
        for (SupplyLevel level : SupplyLevel.values()) {
            List<String> regions = byLevel.get(level);
            if (!regions.isEmpty()) {
                player.sendMessage(getSupplyLevelDisplay(level) + ChatColor.GRAY + ": " +
                        ChatColor.WHITE + String.join(", ", regions));
            }
        }

        return true;
    }

    private String getSupplyLevelDisplay(SupplyLevel level) {
        return switch (level) {
            case SUPPLIED -> ChatColor.GREEN + "100% (Full Supply)";
            case PARTIAL -> ChatColor.YELLOW + "50% (Partial)";
            case UNSUPPLIED -> ChatColor.RED + "25% (Cut Off)";
            case ISOLATED -> ChatColor.DARK_RED + "0% (Isolated)";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("gaps", "status", "recalculate", "help"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("gaps")) {
            // Suggest owned regions
            Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (teamOpt.isEmpty()) return Collections.emptyList();

            List<String> regionIds = regionService.getRegionsByOwner(teamOpt.get()).stream()
                    .map(RegionStatus::regionId)
                    .toList();
            return filterStartsWith(regionIds, args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lowerPrefix))
                .toList();
    }
}

