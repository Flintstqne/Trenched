package org.flintstqne.entrenched.ObjectiveLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionState;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Command to view and track objectives.
 * Usage:
 *   /objective - Shows objectives in current region
 *   /objective list - Shows all objectives in current region
 *   /objective track <id> - Tracks a specific objective
 *   /objective stats - Shows player's objective completion stats
 */
public class ObjectiveCommand implements CommandExecutor, TabCompleter {

    private final ObjectiveService objectiveService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final ConfigManager config;

    public ObjectiveCommand(ObjectiveService objectiveService, RegionService regionService,
                             TeamService teamService, ConfigManager config) {
        this.objectiveService = objectiveService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.")
                    .color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            return showObjectives(player);
        }

        if (args[0].equalsIgnoreCase("stats")) {
            return showStats(player);
        }

        if (args[0].equalsIgnoreCase("track") && args.length >= 2) {
            try {
                int objectiveId = Integer.parseInt(args[1]);
                return trackObjective(player, objectiveId);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid objective ID.")
                        .color(NamedTextColor.RED));
                return true;
            }
        }

        // Help
        showHelp(player);
        return true;
    }

    private boolean showObjectives(Player player) {
        // Get player's current region
        String regionId = regionService.getRegionIdForLocation(
                player.getLocation().getBlockX(),
                player.getLocation().getBlockZ()
        );

        if (regionId == null) {
            player.sendMessage(Component.text("You are not in a valid region.")
                    .color(NamedTextColor.RED));
            return true;
        }

        // Get player's team
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(Component.text("You must be on a team to view objectives.")
                    .color(NamedTextColor.RED));
            return true;
        }

        String team = teamOpt.get();

        // Get region status
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            player.sendMessage(Component.text("Unable to get region status.")
                    .color(NamedTextColor.RED));
            return true;
        }

        RegionStatus status = statusOpt.get();

        // Always show registered buildings regardless of region state or adjacency
        showRegisteredBuildings(player, regionId, team);

        // Check if region is valid for capturing (adjacent to team territory)
        if (!isRegionValidForCapture(regionId, team, status)) {
            player.sendMessage(Component.text("═══ ")
                    .color(NamedTextColor.GOLD)
                    .append(Component.text("Objectives in " + regionId)
                            .color(NamedTextColor.YELLOW)
                            .decorate(TextDecoration.BOLD))
                    .append(Component.text(" ═══")
                            .color(NamedTextColor.GOLD)));
            player.sendMessage(Component.text("This region is not adjacent to your territory.")
                    .color(NamedTextColor.GRAY));
            player.sendMessage(Component.text("Capture adjacent regions first to expand your frontline.")
                    .color(NamedTextColor.GRAY));
            return true;
        }

        // Determine relevant category
        ObjectiveCategory relevantCategory = getRelevantCategory(status, team);

        // Header
        Component header = Component.text("═══ ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Objectives in " + regionId)
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" ═══")
                        .color(NamedTextColor.GOLD));
        player.sendMessage(header);

        if (relevantCategory == null) {
            // Player is defending or region is protected/fortified
            player.sendMessage(Component.text("No objectives available in this region for your team.")
                    .color(NamedTextColor.GRAY));

            // Show region state
            String stateInfo = switch (status.state()) {
                case PROTECTED -> "This is a protected home region.";
                case FORTIFIED -> "This region is fortified and cannot be attacked.";
                case OWNED -> "You own this region. Defend it from attackers!";
                default -> "";
            };
            if (!stateInfo.isEmpty()) {
                player.sendMessage(Component.text(stateInfo).color(NamedTextColor.AQUA));
            }
            return true;
        }

        // Get objectives
        List<RegionObjective> objectives = objectiveService.getActiveObjectives(regionId, relevantCategory);

        if (objectives.isEmpty()) {
            player.sendMessage(Component.text("No active objectives. New objectives spawn periodically.")
                    .color(NamedTextColor.GRAY));
            return true;
        }

        // Category header
        String categoryLabel = relevantCategory == ObjectiveCategory.RAID ? "⚔ RAID OBJECTIVES" : "⚒ SETTLEMENT OBJECTIVES";
        NamedTextColor categoryColor = relevantCategory == ObjectiveCategory.RAID ? NamedTextColor.RED : NamedTextColor.GREEN;
        player.sendMessage(Component.text(categoryLabel).color(categoryColor).decorate(TextDecoration.BOLD));

        // List objectives
        for (RegionObjective obj : objectives) {
            Component objLine = Component.text("  • ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(obj.type().getDisplayName())
                            .color(NamedTextColor.WHITE))
                    .append(Component.text(" [" + obj.getInfluenceReward() + " IP]")
                            .color(NamedTextColor.GOLD));

            // Add progress if applicable
            if (obj.progress() > 0) {
                objLine = objLine.append(Component.text(" - " + obj.getProgressPercent() + "% complete")
                        .color(NamedTextColor.YELLOW));
            }

            // Add location if available
            if (obj.hasLocation()) {
                int dist = (int) Math.sqrt(
                        Math.pow(obj.locationX() - player.getLocation().getX(), 2) +
                        Math.pow(obj.locationZ() - player.getLocation().getZ(), 2)
                );
                objLine = objLine.append(Component.text(" (" + dist + "m away — "
                                + obj.locationX() + ", " + obj.locationY() + ", " + obj.locationZ() + ")")
                        .color(NamedTextColor.GRAY));
            }

            // Add ID for tracking
            objLine = objLine.append(Component.text(" #" + obj.id())
                    .color(NamedTextColor.DARK_GRAY));

            player.sendMessage(objLine);

            // Show description - use actual counts for resource depot
            String description;
            if (obj.type() == ObjectiveType.SETTLEMENT_RESOURCE_DEPOT) {
                Optional<int[]> countsOpt = objectiveService.getResourceDepotCounts(obj.regionId());
                if (countsOpt.isPresent()) {
                    int[] counts = countsOpt.get();
                    // counts = [qualifyingContainers, totalItems, requiredContainers, minItemsPerContainer]
                    description = "Stock " + counts[2] + " containers with " + counts[3] + "+ items each (" + counts[0] + "/" + counts[2] + " done)";
                } else {
                    description = obj.getProgressDescription();
                }
            } else {
                description = obj.getProgressDescription();
            }
            player.sendMessage(Component.text("    " + description)
                    .color(NamedTextColor.GRAY));

            // For building-type objectives (outpost, watchtower, garrison),
            // show the full requirements checklist as subpoints.
            if (BuildingType.fromObjectiveType(obj.type()).isPresent()) {
                objectiveService.getBuildingDetectionResult(obj.id()).ifPresent(detection -> {
                    List<String> checklist = detection.getChecklist();
                    for (String line : checklist) {
                        if (line.isEmpty()) continue;
                        // Lines may contain legacy §-color codes from getChecklist()
                        boolean done = line.startsWith("✓") || line.contains("✓");
                        boolean isHeader = line.startsWith("§");
                        if (isHeader) {
                            // Variant headers / guide lines — render with legacy codes
                            player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                    .legacySection().deserialize("      " + line));
                        } else {
                            NamedTextColor color = done ? NamedTextColor.GREEN : NamedTextColor.RED;
                            player.sendMessage(Component.text("      " + line).color(color));
                        }
                    }
                });
            }
        }

        // Footer with tip
        player.sendMessage(Component.text("─────────────────────────────")
                .color(NamedTextColor.DARK_GRAY));

        player.sendMessage(Component.text("Tip: Complete objectives to earn Influence Points!")
                .color(NamedTextColor.AQUA));

        return true;
    }

    private boolean showStats(Player player) {
        List<RegionObjective> completed = objectiveService.getCompletedByPlayer(player.getUniqueId());

        // Header
        Component header = Component.text("═══ ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Your Objective Stats")
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" ═══")
                        .color(NamedTextColor.GOLD));
        player.sendMessage(header);

        if (completed.isEmpty()) {
            player.sendMessage(Component.text("You haven't completed any objectives this round.")
                    .color(NamedTextColor.GRAY));
            return true;
        }

        // Count by type
        int totalIP = 0;
        int raidCount = 0;
        int settlementCount = 0;

        for (RegionObjective obj : completed) {
            totalIP += obj.getInfluenceReward();
            if (obj.type().isRaid()) {
                raidCount++;
            } else {
                settlementCount++;
            }
        }

        player.sendMessage(Component.text("Objectives Completed: ")
                .color(NamedTextColor.WHITE)
                .append(Component.text(String.valueOf(completed.size()))
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)));

        player.sendMessage(Component.text("  ⚔ Raid Objectives: ")
                .color(NamedTextColor.RED)
                .append(Component.text(String.valueOf(raidCount))
                        .color(NamedTextColor.WHITE)));

        player.sendMessage(Component.text("  ⚒ Settlement Objectives: ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(String.valueOf(settlementCount))
                        .color(NamedTextColor.WHITE)));

        player.sendMessage(Component.text("Total IP Earned from Objectives: ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(totalIP))
                        .color(NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)));

        return true;
    }

    private boolean trackObjective(Player player, int objectiveId) {
        Optional<RegionObjective> objOpt = objectiveService.getObjective(objectiveId);

        if (objOpt.isEmpty()) {
            player.sendMessage(Component.text("Objective not found.")
                    .color(NamedTextColor.RED));
            return true;
        }

        RegionObjective obj = objOpt.get();

        if (!obj.isActive()) {
            player.sendMessage(Component.text("This objective is no longer active.")
                    .color(NamedTextColor.RED));
            return true;
        }

        // Show detailed info about the objective
        Component header = Component.text("═══ ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Tracking: " + obj.type().getDisplayName())
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" ═══")
                        .color(NamedTextColor.GOLD));
        player.sendMessage(header);

        player.sendMessage(Component.text("Region: ")
                .color(NamedTextColor.WHITE)
                .append(Component.text(obj.regionId())
                        .color(NamedTextColor.AQUA)));

        player.sendMessage(Component.text("Reward: ")
                .color(NamedTextColor.WHITE)
                .append(Component.text(obj.getInfluenceReward() + " IP")
                        .color(NamedTextColor.GOLD)));

        player.sendMessage(Component.text("Progress: ")
                .color(NamedTextColor.WHITE)
                .append(Component.text(obj.getProgressPercent() + "%")
                        .color(NamedTextColor.YELLOW)));

        player.sendMessage(Component.text("Description: ")
                .color(NamedTextColor.WHITE)
                .append(Component.text(obj.getProgressDescription())
                        .color(NamedTextColor.GRAY)));

        objectiveService.getBuildingDetectionResult(obj.id()).ifPresent(detection -> {
            player.sendMessage(Component.text("Build Scan: ")
                    .color(NamedTextColor.WHITE)
                    .append(Component.text(detection.getProgressPercent() + "%")
                            .color(detection.valid() ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                    .append(Component.text(" [" + String.format("%.1f", detection.totalScore()) + "/"
                                    + String.format("%.1f", detection.requiredScore()) + "]")
                            .color(NamedTextColor.GRAY)));

            player.sendMessage(Component.text("  Structure/Interior/Access: ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(String.format("%.1f / %.1f / %.1f",
                                    detection.structureScore(), detection.interiorScore(), detection.accessScore()))
                            .color(NamedTextColor.WHITE)));

            player.sendMessage(Component.text("  Signature/Context: ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(String.format("%.1f / %.1f",
                                    detection.signatureScore(), detection.contextScore()))
                            .color(NamedTextColor.WHITE))
                    .append(Component.text("  Variant: " + detection.variant())
                            .color(NamedTextColor.AQUA)));

            player.sendMessage(Component.text("  " + detection.summary())
                    .color(detection.valid() ? NamedTextColor.GREEN : NamedTextColor.GRAY));

            // Show checklist for building objectives (includes variant guide for garrisons)
            List<String> checklist = detection.getChecklist();
            if (!checklist.isEmpty()) {
                player.sendMessage(Component.text("─── Checklist ───")
                        .color(NamedTextColor.DARK_GRAY));
                for (String line : checklist) {
                    if (line.isEmpty()) continue;
                    // Use legacy color codes in the checklist strings (§ codes)
                    player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize("  " + line));
                }
            }
        });

        objectiveService.getRegisteredBuilding(obj.id()).ifPresent(building -> {
            String variantDisplay = "";
            if (building.variant() != null && !building.variant().equals("Standard") && !building.variant().equals("None")) {
                if (building.variant().contains("(needs")) {
                    // Incomplete variant - show upgrade hint
                    String baseName = building.variant().substring(0, building.variant().indexOf(" (needs")).trim();
                    String needs = building.variant().substring(building.variant().indexOf("(needs ") + 7, building.variant().length() - 1);
                    variantDisplay = " — " + baseName + " (incomplete)";

                    player.sendMessage(Component.text("Registered Building: ")
                            .color(NamedTextColor.WHITE)
                            .append(Component.text(building.type().getDisplayName() + " [" + building.status() + "]")
                                    .color(building.status() == RegisteredBuildingStatus.ACTIVE
                                            ? NamedTextColor.GREEN
                                            : NamedTextColor.RED))
                            .append(Component.text(variantDisplay)
                                    .color(NamedTextColor.YELLOW)));

                    player.sendMessage(Component.text("  ⬆ Upgrade to " + baseName + ": ")
                            .color(NamedTextColor.YELLOW)
                            .append(Component.text("add " + needs)
                                    .color(NamedTextColor.GRAY)));
                } else {
                    // Complete variant
                    variantDisplay = " — " + building.variant();
                    player.sendMessage(Component.text("Registered Building: ")
                            .color(NamedTextColor.WHITE)
                            .append(Component.text(building.type().getDisplayName() + " [" + building.status() + "]")
                                    .color(building.status() == RegisteredBuildingStatus.ACTIVE
                                            ? NamedTextColor.GREEN
                                            : NamedTextColor.RED))
                            .append(Component.text(variantDisplay)
                                    .color(NamedTextColor.AQUA)));
                }
            } else {
                player.sendMessage(Component.text("Registered Building: ")
                        .color(NamedTextColor.WHITE)
                        .append(Component.text(building.type().getDisplayName() + " [" + building.status() + "]")
                                .color(building.status() == RegisteredBuildingStatus.ACTIVE
                                        ? NamedTextColor.GREEN
                                        : NamedTextColor.RED)));
            }
        });

        if (obj.hasLocation()) {
            int dist = (int) Math.sqrt(
                    Math.pow(obj.locationX() - player.getLocation().getX(), 2) +
                    Math.pow(obj.locationZ() - player.getLocation().getZ(), 2)
            );
            player.sendMessage(Component.text("Location: ")
                    .color(NamedTextColor.WHITE)
                    .append(Component.text(obj.locationX() + ", " + obj.locationZ())
                            .color(NamedTextColor.AQUA))
                    .append(Component.text(" (" + dist + "m away)")
                            .color(NamedTextColor.GRAY)));
        }

        // Check cooldown
        if (objectiveService.isOnCooldown(player.getUniqueId(), obj.regionId(), obj.type())) {
            long remaining = objectiveService.getCooldownRemaining(player.getUniqueId(), obj.regionId(), obj.type());
            player.sendMessage(Component.text("⚠ Cooldown: ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text(remaining + " seconds remaining")
                            .color(NamedTextColor.RED)));
        }

        return true;
    }

    private void showHelp(Player player) {
        Component header = Component.text("═══ ")
                .color(NamedTextColor.GOLD)
                .append(Component.text("Objective Commands")
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(" ═══")
                        .color(NamedTextColor.GOLD));
        player.sendMessage(header);

        player.sendMessage(Component.text("/objective")
                .color(NamedTextColor.AQUA)
                .append(Component.text(" - View objectives in current region")
                        .color(NamedTextColor.GRAY)));

        player.sendMessage(Component.text("/objective list")
                .color(NamedTextColor.AQUA)
                .append(Component.text(" - Same as above")
                        .color(NamedTextColor.GRAY)));

        player.sendMessage(Component.text("/objective stats")
                .color(NamedTextColor.AQUA)
                .append(Component.text(" - View your objective completion stats")
                        .color(NamedTextColor.GRAY)));

        player.sendMessage(Component.text("/objective track <id>")
                .color(NamedTextColor.AQUA)
                .append(Component.text(" - View details about a specific objective")
                        .color(NamedTextColor.GRAY)));
    }

    private ObjectiveCategory getRelevantCategory(RegionStatus status, String playerTeam) {
        if (status.state() == RegionState.NEUTRAL) {
            return ObjectiveCategory.SETTLEMENT;
        } else if (status.state() == RegionState.OWNED || status.state() == RegionState.CONTESTED) {
            if (!playerTeam.equalsIgnoreCase(status.ownerTeam())) {
                return ObjectiveCategory.RAID;
            }
        }
        return null;
    }

    /**
     * Shows registered buildings belonging to the player's team in the given region.
     * This is called before any early returns so buildings always display.
     */
    private void showRegisteredBuildings(Player player, String regionId, String team) {
        List<RegisteredBuilding> buildings = objectiveService.getAllBuildings().stream()
                .filter(b -> b.regionId().equals(regionId) && b.team().equalsIgnoreCase(team))
                .toList();

        if (buildings.isEmpty()) return;

        player.sendMessage(Component.text("🏗 YOUR BUILDINGS")
                .color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD));

        for (RegisteredBuilding building : buildings) {
            String variant = building.variant();

            if (building.status() == RegisteredBuildingStatus.INVALID) {
                // Destroyed building — show repair hint
                Component line = Component.text("  ✗ ")
                        .color(NamedTextColor.RED)
                        .append(Component.text(building.type().getDisplayName())
                                .color(NamedTextColor.GRAY)
                                .decorate(TextDecoration.STRIKETHROUGH))
                        .append(Component.text(" — DESTROYED")
                                .color(NamedTextColor.RED));
                player.sendMessage(line);
                player.sendMessage(Component.text("    🔧 Rebuild near (")
                        .color(NamedTextColor.YELLOW)
                        .append(Component.text(building.anchorX() + ", " + building.anchorZ())
                                .color(NamedTextColor.WHITE))
                        .append(Component.text(") to repair:")
                                .color(NamedTextColor.YELLOW)));

                // Show specific failing requirements from the last detection result
                objectiveService.getBuildingDetectionResult(building.objectiveId()).ifPresent(detection -> {
                    java.util.List<String> checklist = detection.getChecklist();
                    for (String cl : checklist) {
                        if (cl.isEmpty()) continue;
                        boolean failing = cl.startsWith("✗") || cl.contains("✗");
                        if (failing) {
                            player.sendMessage(Component.text("      " + cl).color(NamedTextColor.RED));
                        }
                    }
                });
                continue;
            }

            // Active building
            Component line = Component.text("  ✓ ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(building.type().getDisplayName())
                            .color(NamedTextColor.WHITE));

            if (variant != null && !variant.equals("Standard") && !variant.equals("None") && !variant.isEmpty()) {
                if (variant.contains("(needs")) {
                    String baseName = variant.substring(0, variant.indexOf(" (needs")).trim();
                    String needs = variant.substring(variant.indexOf("(needs ") + 7, variant.length() - 1);
                    line = line.append(Component.text(" — " + baseName + " ")
                                    .color(NamedTextColor.YELLOW))
                            .append(Component.text("(upgrade: add " + needs + ")")
                                    .color(NamedTextColor.GRAY));
                } else {
                    line = line.append(Component.text(" — " + variant + " ✓")
                            .color(NamedTextColor.AQUA));
                }
            }

            player.sendMessage(line);

            // For garrison buildings, show the variant upgrade path
            if (building.type() == BuildingType.GARRISON) {
                showGarrisonVariantGuide(player, building);
            }
        }

        player.sendMessage(Component.text("─────────────────────────────")
                .color(NamedTextColor.DARK_GRAY));
    }

    /**
     * Shows the garrison variant upgrade guide under a registered garrison building.
     * Highlights the current variant and shows what's needed for each tier.
     */
    private void showGarrisonVariantGuide(Player player, RegisteredBuilding building) {
        String current = building.variant() != null ? building.variant() : "Basic Garrison";
        // Strip "(needs ...)" suffix to get the base variant name
        String currentBase = current.contains("(needs")
                ? current.substring(0, current.indexOf(" (needs")).trim()
                : current;

        var serializer = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

        player.sendMessage(serializer.deserialize("    §6─── Upgrade Paths ───"));

        // Define variants in priority order (highest first)
        record VariantInfo(String name, String requirements, String reward) {}
        List<VariantInfo> variants = List.of(
                new VariantInfo("Fortified",  "20+ wall/fence blocks",                          "§9Resistance II§7 (15s on arrival)"),
                new VariantInfo("Command",    "2+ Lecterns or Banners",                         "§cStrength I§7 (30s on arrival)"),
                new VariantInfo("Supply",     "4+ chests + 64+ items total",                    "§6Saturation§7 (60s) + §a+1 spawn"),
                new VariantInfo("Armory",     "Anvil/Smithing + chest + 5 iron ingots",         "§bResistance I§7 (30s on arrival)"),
                new VariantInfo("Medical",    "Brewing Stand + chest + 3 healing potions",      "§dRegen I§7 (30s on arrival)"),
                new VariantInfo("Basic",      "3+ team beds (default)",                         "§7Spawn point")
        );

        for (VariantInfo v : variants) {
            boolean isActive = currentBase.contains(v.name);
            String prefix;
            String nameColor;
            if (isActive) {
                prefix = "    §a✓ ";
                nameColor = "§a";
            } else {
                prefix = "    §7  ";
                nameColor = "§7";
            }
            player.sendMessage(serializer.deserialize(
                    prefix + nameColor + v.name + "§8: §f" + v.requirements + " §8→ " + v.reward));
        }
    }

    /**
     * Checks if a region is valid for the team to capture/influence.
     * A region is valid if:
     * - It's adjacent to territory the team owns, OR
     * - It's a contested region where the team is the original owner (defending)
     */
    private boolean isRegionValidForCapture(String regionId, String playerTeam, RegionStatus status) {
        // If team already owns this region fully, they can't "capture" it
        if (status.isOwnedBy(playerTeam)) {
            return false;
        }

        // Check if region is adjacent to team's territory
        if (regionService.isAdjacentToTeam(regionId, playerTeam)) {
            return true;
        }

        // Special case: contested region where this team is the original owner
        if (status.state() == RegionState.CONTESTED && playerTeam.equalsIgnoreCase(status.ownerTeam())) {
            return true;
        }

        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String label, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            suggestions.add("list");
            suggestions.add("stats");
            suggestions.add("track");
        }

        return suggestions;
    }
}

