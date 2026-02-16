package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.BlueMapHook.RegionRenderer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles /region commands for viewing region information.
 */
public final class RegionCommand implements CommandExecutor, TabCompleter {

    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager configManager;
    private final RegionRenderer regionRenderer;

    public RegionCommand(RegionService regionService, TeamService teamService,
                         ConfigManager configManager, RegionRenderer regionRenderer) {
        this.regionService = regionService;
        this.teamService = teamService;
        this.configManager = configManager;
        this.regionRenderer = regionRenderer;
    }

    /**
     * Gets the display name for a region (e.g., "Shadowfen Valley" instead of "A1").
     */
    private String getRegionDisplayName(String regionId) {
        if (regionRenderer != null) {
            return regionRenderer.getRegionName(regionId).orElse(regionId);
        }
        return regionId;
    }

    /**
     * Resolves a region input (could be name or ID) to region ID.
     */
    private String resolveRegionId(String input) {
        if (input == null) return null;

        // First check if it's already a valid region ID (e.g., "A1", "B2")
        String upperInput = input.toUpperCase();
        if (upperInput.matches("[A-D][1-4]")) {
            return upperInput;
        }

        // Try to find by name
        if (regionRenderer != null) {
            Optional<String> regionId = regionRenderer.getRegionIdByName(input);
            if (regionId.isPresent()) {
                return regionId.get();
            }
        }

        // Return as-is (will likely fail validation later)
        return upperInput;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            return handleInfo(player, null);
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "info" -> {
                // Join args after "info" to support multi-word region names
                String regionArg = args.length > 1
                        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                        : null;
                yield handleInfo(player, regionArg);
            }
            case "map" -> handleMap(player);
            case "list" -> handleList(player, args.length > 1 ? args[1] : null);
            case "supply" -> handleSupply(player);
            default -> {
                sendHelp(player);
                yield true;
            }
        };
    }

    private void sendHelp(Player player) {
        String prefix = configManager.getPrefix();
        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Region Commands:");
        player.sendMessage(ChatColor.GRAY + "  /region info [name]" + ChatColor.WHITE + " - View region info (current if no name)");
        player.sendMessage(ChatColor.GRAY + "  /region map" + ChatColor.WHITE + " - Show ASCII map of all regions");
        player.sendMessage(ChatColor.GRAY + "  /region list [team]" + ChatColor.WHITE + " - List regions owned by team");
        player.sendMessage(ChatColor.GRAY + "  /region supply" + ChatColor.WHITE + " - Show your team's supply lines");
        player.sendMessage("");
    }

    private boolean handleInfo(Player player, String regionArg) {
        String prefix = configManager.getPrefix();

        String regionId;
        if (regionArg == null) {
            regionId = regionService.getRegionIdForLocation(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ()
            );
        } else {
            // Join multiple args in case region name has spaces
            regionId = resolveRegionId(regionArg);
        }

        if (regionId == null) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a valid region.");
            return true;
        }

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "Region not found: " + regionArg);
            return true;
        }

        RegionStatus status = statusOpt.get();
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        String regionName = getRegionDisplayName(regionId);

        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "═══════════════════════════════════");
        player.sendMessage(ChatColor.GOLD + "  Region: " + ChatColor.WHITE + ChatColor.BOLD + regionName);
        player.sendMessage(ChatColor.DARK_GRAY + "═══════════════════════════════════");

        // Owner
        if (status.ownerTeam() != null) {
            ChatColor teamColor = status.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
            String teamName = status.ownerTeam().equalsIgnoreCase("red") ? "Red Team" : "Blue Team";
            player.sendMessage(ChatColor.GRAY + "  Owner: " + teamColor + teamName);
        } else {
            player.sendMessage(ChatColor.GRAY + "  Owner: " + ChatColor.WHITE + "Neutral");
        }

        // State
        player.sendMessage(ChatColor.GRAY + "  Status: " + getStateColor(status.state()) + status.state().name());

        // Fortification
        if (status.isFortified() && status.fortifiedUntil() != null) {
            long remainingMs = status.fortifiedUntil() - System.currentTimeMillis();
            long remainingMin = remainingMs / (1000 * 60);
            long remainingSec = (remainingMs / 1000) % 60;
            player.sendMessage(ChatColor.GRAY + "  Fortified for: " + ChatColor.AQUA +
                    remainingMin + "m " + remainingSec + "s");
        }

        // Influence
        if (status.redInfluence() > 0 || status.blueInfluence() > 0) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "  Influence:");

            double required;
            if (status.ownerTeam() == null) {
                required = configManager.getRegionNeutralCaptureThreshold();
            } else {
                String opposingTeam = status.ownerTeam().equals("red") ? "blue" : "red";
                required = regionService.getInfluenceRequired(regionId, opposingTeam);
            }

            player.sendMessage(ChatColor.RED + "    Red: " + (int) status.redInfluence() +
                    ChatColor.GRAY + " / " + (int) required + " (" +
                    (int)((status.redInfluence() / required) * 100) + "%)");
            player.sendMessage(ChatColor.BLUE + "    Blue: " + (int) status.blueInfluence() +
                    ChatColor.GRAY + " / " + (int) required + " (" +
                    (int)((status.blueInfluence() / required) * 100) + "%)");
        }


        // Supply efficiency (show to owners)
        if (playerTeamOpt.isPresent() && status.isOwnedBy(playerTeamOpt.get())) {
            double supply = regionService.getSupplyEfficiency(regionId, playerTeamOpt.get());
            ChatColor supplyColor = supply >= 0.8 ? ChatColor.GREEN :
                                   supply >= 0.5 ? ChatColor.YELLOW : ChatColor.RED;
            player.sendMessage("");
            player.sendMessage(ChatColor.GRAY + "  Supply Efficiency: " + supplyColor +
                    (int)(supply * 100) + "%");

            if (!regionService.isConnectedToHome(regionId, playerTeamOpt.get())) {
                player.sendMessage(ChatColor.RED + "  ⚠ UNSUPPLIED - Not connected to home!");
            }
        }

        // Adjacent regions
        List<String> adjacent = regionService.getAdjacentRegions(regionId);
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "  Adjacent Regions:");
        for (String adj : adjacent) {
            String adjName = getRegionDisplayName(adj);
            Optional<RegionStatus> adjStatus = regionService.getRegionStatus(adj);
            String adjOwner = adjStatus.map(s -> {
                if (s.ownerTeam() == null) return ChatColor.WHITE + "Neutral";
                ChatColor c = s.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
                return c + s.ownerTeam();
            }).orElse(ChatColor.GRAY + "Unknown");
            player.sendMessage(ChatColor.GRAY + "    - " + ChatColor.WHITE + adjName + " " + adjOwner);
        }

        player.sendMessage(ChatColor.DARK_GRAY + "═══════════════════════════════════");
        player.sendMessage("");

        return true;
    }

    private boolean handleMap(Player player) {
        String prefix = configManager.getPrefix();

        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Region Map:");
        player.sendMessage("");

        // Header
        player.sendMessage(ChatColor.GRAY + "      1     2     3     4");
        player.sendMessage(ChatColor.GRAY + "   ┌─────┬─────┬─────┬─────┐");

        for (int row = 0; row < 4; row++) {
            char rowLabel = (char) ('A' + row);
            StringBuilder line = new StringBuilder();
            line.append(ChatColor.GRAY).append(" ").append(rowLabel).append(" │");

            for (int col = 1; col <= 4; col++) {
                String regionId = rowLabel + String.valueOf(col);
                Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);

                String cell;
                if (statusOpt.isPresent()) {
                    RegionStatus status = statusOpt.get();
                    cell = getMapCell(status);
                } else {
                    cell = ChatColor.DARK_GRAY + " ??? ";
                }

                line.append(cell).append(ChatColor.GRAY).append("│");
            }

            player.sendMessage(line.toString());

            if (row < 3) {
                player.sendMessage(ChatColor.GRAY + "   ├─────┼─────┼─────┼─────┤");
            }
        }

        player.sendMessage(ChatColor.GRAY + "   └─────┴─────┴─────┴─────┘");

        // Legend
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "Legend:");
        player.sendMessage(ChatColor.RED + "  RED " + ChatColor.GRAY + "= Red Team  " +
                           ChatColor.BLUE + "BLU " + ChatColor.GRAY + "= Blue Team");
        player.sendMessage(ChatColor.WHITE + "  NEU " + ChatColor.GRAY + "= Neutral   " +
                           ChatColor.YELLOW + "CON " + ChatColor.GRAY + "= Contested");
        player.sendMessage(ChatColor.AQUA + "  FRT " + ChatColor.GRAY + "= Fortified " +
                           ChatColor.GOLD + "★ " + ChatColor.GRAY + "= Home Region");
        player.sendMessage("");

        // Current location
        String currentRegion = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );
        if (currentRegion != null) {
            player.sendMessage(ChatColor.GRAY + "You are in: " + ChatColor.WHITE + currentRegion);
        }
        player.sendMessage("");

        return true;
    }

    private String getMapCell(RegionStatus status) {
        String label;
        ChatColor color;

        if (status.state() == RegionState.PROTECTED) {
            color = status.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
            label = " " + color + "★" + status.ownerTeam().substring(0, 1).toUpperCase() + "★ ";
        } else if (status.state() == RegionState.FORTIFIED) {
            color = status.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
            label = color + " FRT ";
        } else if (status.state() == RegionState.CONTESTED) {
            label = ChatColor.YELLOW + " CON ";
        } else if (status.ownerTeam() != null) {
            color = status.ownerTeam().equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;
            label = color + " " + status.ownerTeam().substring(0, 3).toUpperCase() + " ";
        } else {
            label = ChatColor.WHITE + " NEU ";
        }

        return label;
    }

    private boolean handleList(Player player, String teamArg) {
        String prefix = configManager.getPrefix();
        String team = teamArg;

        if (team == null) {
            Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
            if (playerTeamOpt.isEmpty()) {
                player.sendMessage(prefix + ChatColor.RED + "Specify a team: /region list <red|blue>");
                return true;
            }
            team = playerTeamOpt.get();
        }

        team = team.toLowerCase();
        if (!team.equals("red") && !team.equals("blue")) {
            player.sendMessage(prefix + ChatColor.RED + "Invalid team. Use 'red' or 'blue'.");
            return true;
        }

        List<RegionStatus> regions = regionService.getRegionsByOwner(team);
        ChatColor teamColor = team.equals("red") ? ChatColor.RED : ChatColor.BLUE;
        String teamName = team.equals("red") ? "Red Team" : "Blue Team";

        player.sendMessage("");
        player.sendMessage(prefix + teamColor + teamName + ChatColor.GRAY + " Regions (" + regions.size() + "):");

        if (regions.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "  (No regions owned)");
        } else {
            for (RegionStatus status : regions) {
                String regionName = getRegionDisplayName(status.regionId());
                String stateStr = switch (status.state()) {
                    case PROTECTED -> ChatColor.GOLD + " ★ HOME";
                    case FORTIFIED -> ChatColor.AQUA + " (Fortified)";
                    case CONTESTED -> ChatColor.YELLOW + " ⚔ CONTESTED";
                    default -> "";
                };
                player.sendMessage(ChatColor.WHITE + "  - " + regionName + stateStr);
            }
        }
        player.sendMessage("");

        return true;
    }

    private boolean handleSupply(Player player) {
        String prefix = configManager.getPrefix();

        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You must be on a team to view supply lines.");
            return true;
        }

        String team = playerTeamOpt.get();
        ChatColor teamColor = team.equalsIgnoreCase("red") ? ChatColor.RED : ChatColor.BLUE;

        player.sendMessage("");
        player.sendMessage(prefix + teamColor + team.toUpperCase() + ChatColor.GRAY + " Supply Lines:");
        player.sendMessage("");

        List<RegionStatus> regions = regionService.getRegionsByOwner(team);

        for (RegionStatus status : regions) {
            String regionName = getRegionDisplayName(status.regionId());
            double supply = regionService.getSupplyEfficiency(status.regionId(), team);
            boolean connected = regionService.isConnectedToHome(status.regionId(), team);

            ChatColor supplyColor;
            if (!connected) {
                supplyColor = ChatColor.DARK_RED;
            } else if (supply >= 0.8) {
                supplyColor = ChatColor.GREEN;
            } else if (supply >= 0.5) {
                supplyColor = ChatColor.YELLOW;
            } else {
                supplyColor = ChatColor.RED;
            }

            String connectStatus = connected ? "" : ChatColor.RED + " ⚠ UNSUPPLIED";

            player.sendMessage(ChatColor.WHITE + "  " + regionName + ": " +
                    supplyColor + (int)(supply * 100) + "%" + connectStatus);
        }
        player.sendMessage("");

        return true;
    }

    private ChatColor getStateColor(RegionState state) {
        return switch (state) {
            case NEUTRAL -> ChatColor.GRAY;
            case OWNED -> ChatColor.GREEN;
            case CONTESTED -> ChatColor.YELLOW;
            case FORTIFIED -> ChatColor.AQUA;
            case PROTECTED -> ChatColor.GOLD;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("info", "map", "list", "supply").stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info")) {
                // Return all region names for tab completion
                return getAllRegionNames().stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sub.equals("list")) {
                return Arrays.asList("red", "blue").stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
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

    private List<String> getAllRegionIds() {
        List<String> ids = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            for (int col = 1; col <= 4; col++) {
                ids.add(String.valueOf((char)('A' + row)) + col);
            }
        }
        return ids;
    }
}

