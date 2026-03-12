package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.entrenched.ConfigManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Evaluates organic Minecraft builds around settlement objective anchors.
 */
public final class BuildingDetector {

    private static final double OUTPOST_REQUIRED_SCORE = 70.0;
    private static final double WATCHTOWER_REQUIRED_SCORE = 65.0;
    private static final double GARRISON_REQUIRED_SCORE = 75.0;

    private static final Logger LOGGER = Bukkit.getLogger();

    private final ConfigManager config;
    private final boolean debugEnabled;

    public BuildingDetector(ConfigManager config) {
        this.config = config;
        this.debugEnabled = config.isBuildingDetectionDebugEnabled();
    }

    private void debug(String message) {
        if (debugEnabled) {
            LOGGER.info("[Buildings] " + message);
        }
    }

    private void debug(String format, Object... args) {
        if (debugEnabled) {
            LOGGER.info("[Buildings] " + String.format(format, args));
        }
    }

    public BuildingDetectionResult scan(World world, RegionObjective objective, BuildingType type, String team) {
        debug("=== SCAN START: %s in region %s ===", type.name(), objective.regionId());

        if (world == null || objective == null || !objective.hasLocation()) {
            debug("ABORT: Objective missing anchor location");
            return invalid(type, "Objective is missing an anchor location.");
        }

        int radius = config.getBuildingDetectionRadius();
        int verticalRange = config.getBuildingDetectionVerticalRange();

        // Watchtowers need extended vertical range since they are tall structures
        if (type == BuildingType.WATCHTOWER) {
            verticalRange = Math.max(verticalRange, 32); // At least 32 blocks vertical for watchtowers
        }

        int centerX = objective.locationX();
        int centerY = objective.locationY();
        int centerZ = objective.locationZ();

        debug("Scanning at %d,%d,%d (radius=%d, vertical=%d)", centerX, centerY, centerZ, radius, verticalRange);

        Map<BlockPos, Material> relevantBlocks = new HashMap<>();
        int minY = Math.max(world.getMinHeight(), centerY - verticalRange);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + verticalRange);

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                int dx = x - centerX;
                int dz = z - centerZ;
                if ((dx * dx) + (dz * dz) > radius * radius) {
                    continue;
                }

                for (int y = minY; y <= maxY; y++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (isRelevantMaterial(material, type)) {
                        relevantBlocks.put(new BlockPos(x, y, z), material);
                    }
                }
            }
        }

        debug("Found %d relevant blocks in scan area", relevantBlocks.size());

        if (relevantBlocks.isEmpty()) {
            debug("RESULT: No qualifying structure blocks found");
            return invalid(type, "No qualifying structure blocks found near the objective.");
        }

        List<Set<BlockPos>> components = splitIntoComponents(relevantBlocks.keySet());

        // Merge nearby components that are separated by natural terrain (sand, dirt, grass, etc.)
        // Two components are merged if any of their blocks are within 2 blocks of each other
        components = mergeNearbyComponents(components);

        int anchorRadius = Math.max(5, radius / 2);

        debug("Split into %d connected components (anchor radius=%d)", components.size(), anchorRadius);

        BuildingDetectionResult best = invalid(type, "No structure is anchored close enough to the objective.");
        int componentIndex = 0;
        int anchoredCount = 0;

        for (Set<BlockPos> component : components) {
            componentIndex++;

            // Debug: show Y range of this component
            int compMinY = component.stream().mapToInt(p -> p.y).min().orElse(0);
            int compMaxY = component.stream().mapToInt(p -> p.y).max().orElse(0);
            debug("Component #%d (%d blocks): Y range %d to %d (height span: %d)",
                  componentIndex, component.size(), compMinY, compMaxY, compMaxY - compMinY);

            if (!isAnchoredNearObjective(component, centerX, centerY, centerZ, anchorRadius)) {
                debug("Component #%d (%d blocks): NOT anchored near objective", componentIndex, component.size());
                continue;
            }

            anchoredCount++;
            debug("Component #%d (%d blocks): Anchored - evaluating...", componentIndex, component.size());

            BuildingDetectionResult result = evaluateComponent(world, objective, type, team, component);
            debug("Component #%d score: %.1f/%.1f (%s)",
                componentIndex, result.totalScore(), result.requiredScore(),
                result.valid() ? "VALID" : result.summary());

            if (result.totalScore() > best.totalScore()) {
                best = result;
                debug("Component #%d is new best", componentIndex);
            }
        }

        debug("RESULT: %d anchored components, best score=%.1f, valid=%s",
            anchoredCount, best.totalScore(), best.valid());
        debug("=== SCAN END: %s ===", type.name());

        return best;
    }

    private List<Set<BlockPos>> splitIntoComponents(Set<BlockPos> positions) {
        Set<BlockPos> remaining = new HashSet<>(positions);
        List<Set<BlockPos>> components = new ArrayList<>();

        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> component = new HashSet<>();
            queue.add(start);
            remaining.remove(start);

            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                component.add(current);

                for (BlockPos neighbor : current.cardinalNeighbors()) {
                    if (remaining.remove(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            components.add(component);
        }

        return components;
    }

    /**
     * Merges components that are within 2 blocks of each other.
     * This handles cases where natural terrain (sand, dirt, grass) breaks
     * flood-fill connectivity between walls, floors, and roofs.
     * For example: walls placed on sand don't connect to a placed floor
     * because sand isn't a construction material.
     */
    private List<Set<BlockPos>> mergeNearbyComponents(List<Set<BlockPos>> components) {
        if (components.size() <= 1) return components;

        // Build a spatial index for fast proximity lookups
        // Key: (x>>1, y>>1, z>>1) -> list of component indices
        Map<Long, List<Integer>> spatialIndex = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (BlockPos pos : components.get(i)) {
                // Index by 2-block cells to efficiently find neighbors within distance 2
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long key = packPos(pos.x + dx, pos.y + dy, pos.z + dz);
                            spatialIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
                        }
                    }
                }
            }
        }

        // Union-Find to track which components should merge
        int[] parent = new int[components.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        // For each block in each component, check if any block within distance 2
        // belongs to a different component
        for (int i = 0; i < components.size(); i++) {
            for (BlockPos pos : components.get(i)) {
                long key = packPos(pos.x, pos.y, pos.z);
                List<Integer> nearby = spatialIndex.get(key);
                if (nearby == null) continue;
                for (int j : nearby) {
                    if (find(parent, i) != find(parent, j)) {
                        union(parent, i, j);
                    }
                }
            }
        }

        // Group components by their root parent
        Map<Integer, Set<BlockPos>> merged = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            int root = find(parent, i);
            merged.computeIfAbsent(root, k -> new HashSet<>()).addAll(components.get(i));
        }

        return new ArrayList<>(merged.values());
    }

    private long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]; // path compression
            i = parent[i];
        }
        return i;
    }

    private void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private boolean isAnchoredNearObjective(Set<BlockPos> component, int centerX, int centerY, int centerZ, int anchorRadius) {
        int radiusSquared = anchorRadius * anchorRadius;
        for (BlockPos pos : component) {
            int dx = pos.x - centerX;
            int dz = pos.z - centerZ;
            if ((dx * dx) + (dz * dz) <= radiusSquared && Math.abs(pos.y - centerY) <= anchorRadius) {
                return true;
            }
        }
        return false;
    }

    private BuildingDetectionResult evaluateComponent(World world, RegionObjective objective, BuildingType type,
                                                      String team, Set<BlockPos> component) {
        Bounds bounds = Bounds.from(component);
        ComponentStats stats = collectComponentStats(world, component, bounds, team);
        InteriorStats interior = analyzeInterior(world, bounds);

        return switch (type) {
            case OUTPOST -> scoreOutpost(world, objective, bounds, stats, interior);
            case WATCHTOWER -> scoreWatchtower(world, objective, bounds, stats);
            case GARRISON -> scoreGarrison(bounds, stats, interior, team);
        };
    }

    private BuildingDetectionResult scoreOutpost(World world, RegionObjective objective, Bounds bounds,
                                                 ComponentStats stats, InteriorStats interior) {
        debug("  [OUTPOST] Scoring component at bounds %d,%d,%d to %d,%d,%d",
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        VariantScore variant = detectOutpostVariant(world, objective, bounds);

        boolean enoughStructure = stats.structuralBlocks >= 24;
        boolean enoughFootprint = stats.footprint >= 14;
        boolean enoughInterior = interior.interiorCells >= 3;
        // Roof is valid if: 4+ roofed cells (small covered area) OR 40% coverage (larger builds)
        boolean roofed = interior.roofedCells >= 4 || interior.roofCoverage >= 0.40;
        boolean hasChest = stats.storageBlocks >= 1;
        boolean hasCrafting = stats.craftingTables >= 1;
        boolean hasEntrance = interior.hasEntrance || stats.entranceBlocks >= 1;

        debug("  [OUTPOST] Stats: structural=%d (need 24), footprint=%d (need 14), interior=%d (need 3)",
            stats.structuralBlocks, stats.footprint, interior.interiorCells);
        debug("  [OUTPOST] Features: roofed=%d cells (need 4+), roof=%.0f%% (or need 40%%), chests=%d, crafting=%d, entrances=%d",
            interior.roofedCells, interior.roofCoverage * 100, stats.storageBlocks, stats.craftingTables, stats.entranceBlocks);

        double structureScore = clamp((stats.structuralBlocks / 42.0) * 15.0
                + (stats.footprint / 18.0) * 9.0
                + interior.roofCoverage * 6.0, 0.0, 30.0);
        double interiorScore = clamp((interior.interiorCells / 10.0) * 13.0
                + (interior.floorArea / 12.0) * 8.0
                + interior.enclosureQuality * 4.0, 0.0, 25.0);
        double accessScore = clamp((hasEntrance ? 10.0 : 0.0)
                + Math.min(6.0, stats.entranceBlocks * 2.0)
                + Math.min(4.0, stats.storageBlocks + stats.craftingTables), 0.0, 20.0);
        double signatureScore = clamp((hasChest ? 9.0 : 0.0)
                + (hasCrafting ? 9.0 : 0.0)
                + Math.min(2.0, stats.utilityBlocks), 0.0, 20.0);
        double contextScore = clamp(variant.score, 0.0, 5.0);

        double total = structureScore + interiorScore + accessScore + signatureScore + contextScore;

        debug("  [OUTPOST] Scores: structure=%.1f/30, interior=%.1f/25, access=%.1f/20, signature=%.1f/20, context=%.1f/5",
            structureScore, interiorScore, accessScore, signatureScore, contextScore);
        debug("  [OUTPOST] TOTAL: %.1f/%.1f (%.0f%%)", total, OUTPOST_REQUIRED_SCORE, (total / OUTPOST_REQUIRED_SCORE) * 100);

        List<String> failures = new ArrayList<>();
        if (!enoughStructure) failures.add("more structural mass");
        if (!enoughFootprint) failures.add("a wider footprint");
        if (!enoughInterior) failures.add("more interior rooms (spaces with floor, roof, and 2+ walls)");
        if (!roofed) failures.add("better roof coverage");
        if (!hasChest) failures.add("a chest");
        if (!hasCrafting) failures.add("a crafting table");
        if (!hasEntrance) failures.add("a real entrance");
        if (total < OUTPOST_REQUIRED_SCORE) failures.add("higher build quality");

        boolean valid = failures.isEmpty();

        if (!valid) {
            debug("  [OUTPOST] FAILING CHECKS: %s", String.join(", ", failures));
            debug("  [OUTPOST] Check details: structure=%b, footprint=%b, interior=%b, roof=%b, chest=%b, crafting=%b, entrance=%b, score=%b",
                enoughStructure, enoughFootprint, enoughInterior, roofed, hasChest, hasCrafting, hasEntrance, total >= OUTPOST_REQUIRED_SCORE);
        }

        String summary = valid
                ? "Valid outpost" + (variant.variant.equals("Standard") ? "" : " (" + variant.variant + ")")
                : "Outpost needs " + String.join(", ", failures) + ".";

        return result(BuildingType.OUTPOST, valid, total, OUTPOST_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                variant.variant, summary, stats.structuralBlocks, stats.footprint, bounds);
    }

    private BuildingDetectionResult scoreWatchtower(World world, RegionObjective objective, Bounds bounds,
                                                    ComponentStats stats) {
        debug("  [WATCHTOWER] Scoring component at bounds %d,%d,%d to %d,%d,%d",
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        TowerStats tower = analyzeTower(world, objective, stats, bounds);

        debug("  [WATCHTOWER] Tower analysis: height=%d (need 14), platform=%d (need 4), base=%d (need 3)",
            tower.height, tower.platformSize, tower.baseFootprint);
        debug("  [WATCHTOWER] Tower ratios: access=%.0f%% (need 55%%), support=%.0f%% (need 65%%), openness=%.0f%% (need 35%%)",
            tower.accessCoverage * 100, tower.supportStrength * 100, tower.openness * 100);

        boolean enoughHeight = tower.height >= 14;
        boolean enoughPlatform = tower.platformSize >= 4;
        boolean enoughAccess = tower.accessCoverage >= 0.55;
        boolean enoughSupport = tower.supportStrength >= 0.65;
        boolean enoughOpenness = tower.openness >= 0.35;
        boolean strongBase = tower.baseFootprint >= 3;

        double structureScore = clamp((tower.height / 16.0) * 15.0
                + (stats.structuralBlocks / 45.0) * 10.0
                + (tower.baseFootprint / 5.0) * 10.0, 0.0, 35.0);
        double interiorScore = clamp(tower.supportStrength * 15.0, 0.0, 15.0);
        double accessScore = clamp(tower.accessCoverage * 20.0
                + Math.min(5.0, stats.entranceBlocks * 1.5), 0.0, 25.0);
        double signatureScore = clamp((tower.platformSize / 6.0) * 15.0
                + tower.openness * 10.0, 0.0, 25.0);
        double contextScore = clamp((tower.skyExposure * 10.0) + (tower.exposedTerrain ? 5.0 : 0.0), 0.0, 15.0);

        double total = structureScore + interiorScore + accessScore + signatureScore + contextScore;

        debug("  [WATCHTOWER] Scores: structure=%.1f/35, interior=%.1f/15, access=%.1f/25, signature=%.1f/25, context=%.1f/15",
            structureScore, interiorScore, accessScore, signatureScore, contextScore);
        debug("  [WATCHTOWER] TOTAL: %.1f/%.1f (%.0f%%)", total, WATCHTOWER_REQUIRED_SCORE, (total / WATCHTOWER_REQUIRED_SCORE) * 100);

        List<String> failures = new ArrayList<>();
        if (!enoughHeight) failures.add("more height");
        if (!enoughPlatform) failures.add("a larger top platform");
        if (!enoughAccess) failures.add("a climbable route");
        if (!enoughSupport) failures.add("stronger support under the tower");
        if (!enoughOpenness) failures.add("clearer top visibility");
        if (!strongBase) failures.add("a thicker base");
        if (total < WATCHTOWER_REQUIRED_SCORE) failures.add("a more convincing tower shape");

        boolean valid = failures.isEmpty();

        if (!valid) {
            debug("  [WATCHTOWER] FAILING CHECKS: %s", String.join(", ", failures));
            debug("  [WATCHTOWER] Check details: height=%b, platform=%b, access=%b, support=%b, openness=%b, base=%b, score=%b",
                enoughHeight, enoughPlatform, enoughAccess, enoughSupport, enoughOpenness, strongBase, total >= WATCHTOWER_REQUIRED_SCORE);
        }

        String summary = valid ? "Valid watchtower" : "Watchtower needs " + String.join(", ", failures) + ".";

        return result(BuildingType.WATCHTOWER, valid, total, WATCHTOWER_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                "Watchtower", summary, stats.structuralBlocks, stats.footprint, bounds);
    }

    private BuildingDetectionResult scoreGarrison(Bounds bounds, ComponentStats stats,
                                                  InteriorStats interior, String team) {
        debug("  [GARRISON] Scoring component at bounds %d,%d,%d to %d,%d,%d",
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        VariantScore variant = detectGarrisonVariant(stats);
        int countedBeds = team == null || team.isBlank() ? stats.beds : stats.teamBeds;

        debug("  [GARRISON] Stats: structural=%d (need 34), floor=%d (need 12), interior=%d (need 10)",
            stats.structuralBlocks, interior.floorArea, interior.interiorCells);
        debug("  [GARRISON] Features: roof=%.0f%% (need 65%%), beds=%d/%d (need 3 team), entrances=%d",
            interior.roofCoverage * 100, countedBeds, stats.beds, stats.entranceBlocks);
        debug("  [GARRISON] Team='%s', teamBeds=%d, allBeds=%d", team, stats.teamBeds, stats.beds);

        boolean enoughStructure = stats.structuralBlocks >= 34;
        boolean enoughFloor = interior.floorArea >= 12;
        boolean enoughInterior = interior.interiorCells >= 10;
        boolean roofed = interior.roofCoverage >= 0.65;
        boolean enoughBeds = countedBeds >= 3;
        boolean hasEntrance = interior.hasEntrance || stats.entranceBlocks >= 1;

        double structureScore = clamp((stats.structuralBlocks / 48.0) * 15.0
                + (stats.footprint / 18.0) * 8.0
                + interior.roofCoverage * 7.0, 0.0, 30.0);
        double interiorScore = clamp((interior.interiorCells / 14.0) * 12.0
                + (interior.floorArea / 16.0) * 8.0
                + interior.enclosureQuality * 5.0, 0.0, 25.0);
        double accessScore = clamp((hasEntrance ? 8.0 : 0.0)
                + Math.min(7.0, stats.entranceBlocks * 2.0), 0.0, 15.0);
        double signatureScore = clamp(Math.min(12.0, countedBeds * 4.0)
                + Math.min(8.0, stats.storageBlocks + stats.militaryUtilityBlocks), 0.0, 20.0);
        double contextScore = clamp(variant.score, 0.0, 10.0);

        double total = structureScore + interiorScore + accessScore + signatureScore + contextScore;

        debug("  [GARRISON] Scores: structure=%.1f/30, interior=%.1f/25, access=%.1f/15, signature=%.1f/20, context=%.1f/10",
            structureScore, interiorScore, accessScore, signatureScore, contextScore);
        debug("  [GARRISON] TOTAL: %.1f/%.1f (%.0f%%)", total, GARRISON_REQUIRED_SCORE, (total / GARRISON_REQUIRED_SCORE) * 100);

        List<String> failures = new ArrayList<>();
        if (!enoughStructure) failures.add("more structural mass");
        if (!enoughFloor) failures.add("more floor space");
        if (!enoughInterior) failures.add("more interior rooms (spaces with floor, roof, and 2+ walls)");
        if (!roofed) failures.add("better roof coverage");
        if (!enoughBeds) {
            failures.add(team == null || team.isBlank()
                    ? "at least 3 beds"
                    : "at least 3 " + team.toLowerCase() + " beds");
        }
        if (!hasEntrance) failures.add("a proper entrance");
        if (total < GARRISON_REQUIRED_SCORE) failures.add("more barracks detail");

        boolean valid = failures.isEmpty();

        if (!valid) {
            debug("  [GARRISON] FAILING CHECKS: %s", String.join(", ", failures));
            debug("  [GARRISON] Check details: structure=%b, floor=%b, interior=%b, roof=%b, beds=%b, entrance=%b, score=%b",
                enoughStructure, enoughFloor, enoughInterior, roofed, enoughBeds, hasEntrance, total >= GARRISON_REQUIRED_SCORE);
        }

        String summary = valid
                ? "Valid garrison" + (variant.variant.equals("Barracks") ? "" : " (" + variant.variant + ")")
                : "Garrison needs " + String.join(", ", failures) + ".";

        return result(BuildingType.GARRISON, valid, total, GARRISON_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                variant.variant, summary, stats.structuralBlocks, stats.footprint, bounds);
    }

    private ComponentStats collectComponentStats(World world, Set<BlockPos> component, Bounds bounds, String team) {
        Set<String> footprint = new HashSet<>();
        Set<Integer> baseFootprint = new HashSet<>();
        Set<Integer> accessLevels = new HashSet<>();
        int structuralBlocks = 0;
        int storageBlocks = 0;
        int craftingTables = 0;
        int beds = 0;
        int teamBeds = 0;
        int entranceBlocks = 0;
        int utilityBlocks = 0;
        int militaryUtilityBlocks = 0;
        int defensiveBlocks = 0;

        for (BlockPos pos : component) {
            Block block = world.getBlockAt(pos.x, pos.y, pos.z);
            Material material = block.getType();

            if (isConstructionMaterial(material)) {
                structuralBlocks++;
                footprint.add(pos.x + ":" + pos.z);
                if (pos.y <= bounds.minY + 2) {
                    baseFootprint.add((pos.x << 16) ^ pos.z);
                }
            }

            if (isStorageMaterial(material)) {
                storageBlocks++;
            }
            if (material == Material.CRAFTING_TABLE) {
                craftingTables++;
            }
            if (isBed(material) && isBedHead(block.getBlockData())) {
                beds++;
                if (matchesTeamBed(material, team)) {
                    teamBeds++;
                }
            }
            if (isEntranceMaterial(material)) {
                entranceBlocks++;
            }
            if (isAccessMaterial(material)) {
                accessLevels.add(pos.y);
            }
            if (isGeneralUtility(material)) {
                utilityBlocks++;
            }
            if (isMilitaryUtility(material)) {
                militaryUtilityBlocks++;
            }
            if (isDefensiveBlock(material)) {
                defensiveBlocks++;
            }
        }

        return new ComponentStats(
                structuralBlocks,
                footprint.size(),
                baseFootprint.size(),
                storageBlocks,
                craftingTables,
                beds,
                teamBeds,
                entranceBlocks,
                utilityBlocks,
                militaryUtilityBlocks,
                defensiveBlocks,
                accessLevels
        );
    }

    private InteriorStats analyzeInterior(World world, Bounds bounds) {
        int usableCells = 0;
        int interiorCells = 0;
        int roofedCells = 0;
        int shelteredCells = 0;
        int openings = 0;
        Set<String> floorArea = new HashSet<>();

        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int y = bounds.minY; y <= bounds.maxY; y++) {
                    Material feetBlock = world.getBlockAt(x, y, z).getType();
                    Material headBlock = world.getBlockAt(x, y + 1, z).getType();

                    // Check if this is a standing space (passable at feet and head level)
                    if (!isPassable(feetBlock) || !isPassable(headBlock)) {
                        continue;
                    }

                    // Check for floor below (can be 1 or 2 blocks below for multi-level or raised buildings)
                    Material floor1 = world.getBlockAt(x, y - 1, z).getType();
                    Material floor2 = world.getBlockAt(x, y - 2, z).getType();
                    boolean hasFloor = isConstructionMaterial(floor1)
                                     || floor1.name().contains("SLAB")
                                     || floor1.name().contains("STAIR")
                                     || isConstructionMaterial(floor2)
                                     || floor2.name().contains("SLAB")
                                     || floor2.name().contains("STAIR");

                    if (!hasFloor) {
                        continue;
                    }

                    // Count walls on all 4 sides (check both lower and upper blocks)
                    int sideWalls = 0;
                    boolean opening = false;
                    BlockPos pos = new BlockPos(x, y, z);
                    for (BlockPos neighbor : pos.horizontalNeighbors()) {
                        Material side = world.getBlockAt(neighbor.x, neighbor.y, neighbor.z).getType();
                        Material sideHead = world.getBlockAt(neighbor.x, neighbor.y + 1, neighbor.z).getType();

                        // Count as wall if either level is blocked
                        boolean isWall = isConstructionMaterial(side) || isEntranceMaterial(side)
                                || isConstructionMaterial(sideHead) || isEntranceMaterial(sideHead)
                                || side.name().contains("FENCE") || side.name().contains("WALL")
                                || sideHead.name().contains("FENCE") || sideHead.name().contains("WALL");

                        if (isWall) {
                            sideWalls++;
                        } else if (isPassable(side) && isPassable(sideHead)) {
                            opening = true;
                        }
                    }

                    // Check for roof (extended range: 2-8 blocks above)
                    boolean roof = hasRoofExtended(world, x, y, z);

                    // Only count as "usable" if it has EITHER a roof OR walls
                    if (roof || sideWalls >= 1) {
                        usableCells++;
                    }

                    if (roof) {
                        roofedCells++;
                    }
                    if (sideWalls >= 2) {
                        shelteredCells++;
                    }
                    // Interior cell needs roof AND at least 1 wall
                    if (roof && sideWalls >= 1) {
                        interiorCells++;
                        floorArea.add(x + ":" + z);
                        if (opening) {
                            openings++;
                        }
                    }
                }
            }
        }


        double roofCoverage = usableCells <= 0 ? 0.0 : (double) roofedCells / usableCells;
        double enclosureQuality = usableCells <= 0 ? 0.0 : (double) shelteredCells / usableCells;
        return new InteriorStats(usableCells, interiorCells, roofedCells, floorArea.size(), roofCoverage, enclosureQuality, openings > 0);
    }

    private boolean hasRoofExtended(World world, int x, int y, int z) {
        // y is the feet level where player stands (air)
        // y+1 is head level (also air for standing space)
        // Roof could be at y+2 (right above head) up to y+8 (tall ceiling)
        for (int dy = 2; dy <= 8; dy++) {
            Material above = world.getBlockAt(x, y + dy, z).getType();
            if (above == null) continue;

            // Check if this is a roof-like material
            if (isConstructionMaterial(above)) return true;
            if (isEntranceMaterial(above)) return true; // Trapdoors can be roof

            String name = above.name();
            if (name.contains("SLAB") || name.contains("STAIR") || name.contains("CARPET")
                    || name.contains("GLASS") || name.contains("LEAVES")) {
                return true;
            }
        }
        return false;
    }

    private TowerStats analyzeTower(World world, RegionObjective objective, ComponentStats stats, Bounds bounds) {
        int objectiveBaseY = objective.locationY() == null ? bounds.minY : objective.locationY();
        int highestWalkableY = Integer.MIN_VALUE;
        Map<Integer, Integer> walkableByY = new HashMap<>();
        double exposedSides = 0.0;
        int exposedCount = 0;
        int skyVisibleCount = 0;

        debug("  [WATCHTOWER] Analyzing tower: objectiveBaseY=%d, bounds Y range=%d to %d",
              objectiveBaseY, bounds.minY, bounds.maxY);

        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (!isConstructionMaterial(current)) {
                        continue;
                    }
                    Material above = world.getBlockAt(x, y + 1, z).getType();
                    // Use more lenient check - allow stairs/slabs/fences above for roofed platforms
                    if (!isWalkableAbove(above)) {
                        continue;
                    }
                    if (y > highestWalkableY) {
                        highestWalkableY = y;
                        debug("  [WATCHTOWER] New highest walkable: Y=%d at %d,%d,%d (block=%s, above=%s)",
                              y, x, y, z, current.name(), above.name());
                    }
                    walkableByY.merge(y, 1, Integer::sum);
                }
            }
        }

        debug("  [WATCHTOWER] Highest walkable Y=%d, walkable levels=%d",
              highestWalkableY, walkableByY.size());

        int platformSize = highestWalkableY == Integer.MIN_VALUE ? 0 : walkableByY.getOrDefault(highestWalkableY, 0);
        if (highestWalkableY != Integer.MIN_VALUE) {
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Material current = world.getBlockAt(x, highestWalkableY, z).getType();
                    // Use more lenient check for platform with roofs
                    if (!isConstructionMaterial(current) || !isWalkableAbove(world.getBlockAt(x, highestWalkableY + 1, z).getType())) {
                        continue;
                    }

                    int openSides = 0;
                    for (BlockPos neighbor : new BlockPos(x, highestWalkableY + 1, z).horizontalNeighbors()) {
                        if (isPassable(world.getBlockAt(neighbor.x, neighbor.y, neighbor.z).getType())) {
                            openSides++;
                        }
                    }
                    exposedSides += openSides / 4.0;
                    exposedCount++;
                    // For sky visibility, check if no solid blocks above (stairs/slabs still count as exposed)
                    int highestBlock = world.getHighestBlockYAt(x, z);
                    Material highestMat = world.getBlockAt(x, highestBlock, z).getType();
                    boolean isOpenRoof = highestMat.name().contains("STAIR") || highestMat.name().contains("SLAB")
                                       || highestMat.name().contains("FENCE");
                    if (highestBlock <= highestWalkableY + 3 || isOpenRoof) {
                        skyVisibleCount++;
                    }
                }
            }
        }

        double openness = exposedCount == 0 ? 0.0 : exposedSides / exposedCount;
        double skyExposure = exposedCount == 0 ? 0.0 : (double) skyVisibleCount / exposedCount;
        int height = highestWalkableY == Integer.MIN_VALUE ? 0 : highestWalkableY - objectiveBaseY + 1;
        int levelSpan = Math.max(1, highestWalkableY - bounds.minY + 1);
        double accessCoverage = stats.accessLevels.isEmpty() ? 0.0
                : clamp((double) stats.accessLevels.size() / levelSpan, 0.0, 1.0);
        double supportStrength = clamp((stats.structuralBlocks / Math.max(1.0, height * 3.25))
                + (stats.baseFootprint / 6.0), 0.0, 1.0);
        boolean exposedTerrain = objective.locationY() != null
                && objective.locationY() >= world.getSeaLevel() + 12;

        debug("  [WATCHTOWER] Final: height=%d, platformSize=%d, openness=%.2f, access=%.2f, skyExposure=%.2f",
              height, platformSize, openness, accessCoverage, skyExposure);

        return new TowerStats(height, platformSize, openness, accessCoverage, stats.baseFootprint, supportStrength, skyExposure, exposedTerrain);
    }

    private VariantScore detectOutpostVariant(World world, RegionObjective objective, Bounds bounds) {
        int x = objective.locationX();
        int y = objective.locationY();
        int z = objective.locationZ();
        int radius = 8;

        // ===== Phase 1: Environment scan (determines which variant is POSSIBLE) =====
        int water = 0;
        int crops = 0;
        int logs = 0;
        int leaves = 0;
        int sand = 0;
        int ore = 0;

        for (int scanX = x - radius; scanX <= x + radius; scanX++) {
            for (int scanZ = z - radius; scanZ <= z + radius; scanZ++) {
                for (int scanY = Math.max(world.getMinHeight(), y - 4); scanY <= Math.min(world.getMaxHeight() - 1, y + 4); scanY++) {
                    Material material = world.getBlockAt(scanX, scanY, scanZ).getType();
                    String name = material.name();
                    if (material == Material.WATER) water++;
                    if (name.contains("WHEAT") || name.contains("CARROT") || name.contains("POTATO")
                            || name.contains("BEETROOT") || material == Material.FARMLAND) crops++;
                    if (name.contains("LOG") || name.contains("WOOD")) logs++;
                    if (name.contains("LEAVES")) leaves++;
                    if (name.contains("SAND")) sand++;
                    if (name.contains("ORE")) ore++;
                }
            }
        }

        String biomeName = world.getBiome(x, y, z).getKey().toString().toUpperCase();

        // ===== Phase 2: Scan building area for blocks and chest contents =====
        // Scan the building bounds (plus small margin) for specific blocks and items
        int scanMinX = bounds.minX - 2, scanMaxX = bounds.maxX + 2;
        int scanMinY = bounds.minY - 1, scanMaxY = bounds.maxY + 2;
        int scanMinZ = bounds.minZ - 2, scanMaxZ = bounds.maxZ + 2;

        boolean hasFurnace = false;
        boolean hasLadder = false;
        int woolBlocks = 0;
        int cactusBlocks = 0;

        // Items found in chests
        boolean hasPickaxe = false;
        boolean hasFishingRod = false;
        boolean hasHoe = false;
        boolean hasAxe = false;
        boolean hasWaterBucket = false;
        int logsInChests = 0;

        for (int scanX = scanMinX; scanX <= scanMaxX; scanX++) {
            for (int scanZ = scanMinZ; scanZ <= scanMaxZ; scanZ++) {
                for (int scanY = Math.max(world.getMinHeight(), scanMinY); scanY <= Math.min(world.getMaxHeight() - 1, scanMaxY); scanY++) {
                    Block block = world.getBlockAt(scanX, scanY, scanZ);
                    Material mat = block.getType();

                    // Check for specific blocks
                    if (mat == Material.FURNACE || mat == Material.BLAST_FURNACE) hasFurnace = true;
                    if (mat == Material.LADDER || mat == Material.VINE) hasLadder = true;
                    if (mat.name().contains("WOOL")) woolBlocks++;
                    if (mat == Material.CACTUS) cactusBlocks++;

                    // Check chest contents
                    if (block.getState() instanceof Container container) {
                        for (ItemStack item : container.getInventory().getContents()) {
                            if (item == null || item.getType().isAir()) continue;
                            Material itemType = item.getType();
                            String itemName = itemType.name();

                            if (itemName.contains("PICKAXE")) hasPickaxe = true;
                            if (itemType == Material.FISHING_ROD) hasFishingRod = true;
                            if (itemName.contains("HOE")) hasHoe = true;
                            if (itemName.contains("AXE") && !itemName.contains("PICKAXE")) hasAxe = true;
                            if (itemType == Material.WATER_BUCKET) hasWaterBucket = true;
                            if (itemName.contains("LOG") || itemName.contains("WOOD")) {
                                logsInChests += item.getAmount();
                            }
                        }
                    }
                }
            }
        }

        // ===== Phase 3: Match variant with BOTH environment AND additional requirements =====

        // Fishing Outpost: Near water + Fishing Rod in chest
        if (water >= 35) {
            if (hasFishingRod) {
                return new VariantScore("Fishing Outpost", 5.0);
            }
            debug("  [VARIANT] Fishing environment detected but missing Fishing Rod in chest");
            return new VariantScore("Fishing Outpost (needs Fishing Rod in chest)", 3.0);
        }

        // Farm Outpost: Crops nearby + Hoe in chest
        if (crops >= 18) {
            if (hasHoe) {
                return new VariantScore("Farm Outpost", 5.0);
            }
            debug("  [VARIANT] Farm environment detected but missing Hoe in chest");
            return new VariantScore("Farm Outpost (needs Hoe in chest)", 3.0);
        }

        // Desert Outpost: Desert biome/sand + 3+ cactus + Water Bucket in chest
        if (sand >= 30 || biomeName.contains("DESERT") || biomeName.contains("BADLANDS")) {
            if (cactusBlocks >= 3 && hasWaterBucket) {
                return new VariantScore("Desert Outpost", 5.0);
            }
            List<String> missing = new ArrayList<>();
            if (cactusBlocks < 3) missing.add("3+ Cactus blocks");
            if (!hasWaterBucket) missing.add("Water Bucket in chest");
            debug("  [VARIANT] Desert environment detected but missing: cactus=%d/3, waterBucket=%b", cactusBlocks, hasWaterBucket);
            return new VariantScore("Desert Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }

        // Mountain Outpost: High elevation + Ladder access + 3+ wool blocks
        if (objective.locationY() != null && objective.locationY() >= world.getSeaLevel() + 22) {
            if (hasLadder && woolBlocks >= 3) {
                return new VariantScore("Mountain Outpost", 5.0);
            }
            List<String> missing = new ArrayList<>();
            if (!hasLadder) missing.add("Ladder");
            if (woolBlocks < 3) missing.add("3+ Wool blocks");
            debug("  [VARIANT] Mountain environment detected but missing: ladder=%b, wool=%d/3", hasLadder, woolBlocks);
            return new VariantScore("Mountain Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }

        // Mining Outpost: Near ore/underground + Furnace + Pickaxe in chest
        if (ore >= 6 || (objective.locationY() != null && objective.locationY() <= world.getSeaLevel() - 10)) {
            if (hasFurnace && hasPickaxe) {
                return new VariantScore("Mining Outpost", 5.0);
            }
            List<String> missing = new ArrayList<>();
            if (!hasFurnace) missing.add("Furnace");
            if (!hasPickaxe) missing.add("Pickaxe in chest");
            debug("  [VARIANT] Mining environment detected but missing: furnace=%b, pickaxe=%b", hasFurnace, hasPickaxe);
            return new VariantScore("Mining Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }

        // Forest Outpost: Forest biome/trees + 10+ logs in chest + Axe in chest
        if ((logs + leaves) >= 40 || biomeName.contains("FOREST") || biomeName.contains("TAIGA") || biomeName.contains("JUNGLE")) {
            if (logsInChests >= 10 && hasAxe) {
                return new VariantScore("Forest Outpost", 5.0);
            }
            List<String> missing = new ArrayList<>();
            if (logsInChests < 10) missing.add("10+ Logs in chest");
            if (!hasAxe) missing.add("Axe in chest");
            debug("  [VARIANT] Forest environment detected but missing: logsInChest=%d/10, axe=%b", logsInChests, hasAxe);
            return new VariantScore("Forest Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }

        return new VariantScore("Standard", 2.0);
    }

    private VariantScore detectGarrisonVariant(ComponentStats stats) {
        // Variants from docs:
        // Medical Garrison: + Brewing Stand, + 3 Healing Potions in chest
        // Armory Garrison: + Anvil, + 5 Iron Ingots in chest
        // Command Garrison: + Lectern with written book, + Banner
        // Supply Garrison: + 4 Chests (min 64 items total)
        // Fortified Garrison: + 20 defensive blocks (walls/fences) surrounding

        // Check for Fortified (most defensive blocks)
        if (stats.defensiveBlocks >= 20) {
            return new VariantScore("Fortified Garrison", 10.0);
        }

        // Check for Command (military utility = lecterns, banners)
        if (stats.militaryUtilityBlocks >= 2) {
            return new VariantScore("Command Garrison", 9.0);
        }

        // Check for Supply (lots of storage)
        if (stats.storageBlocks >= 4) {
            return new VariantScore("Supply Garrison", 8.0);
        }

        // Check for Armory (anvils count as military utility)
        if (stats.militaryUtilityBlocks >= 1 && stats.storageBlocks >= 1) {
            return new VariantScore("Armory Garrison", 7.0);
        }

        // Check for Medical (brewing stands count as utility)
        if (stats.utilityBlocks >= 2 && stats.storageBlocks >= 1) {
            return new VariantScore("Medical Garrison", 6.0);
        }

        return new VariantScore("Basic Garrison", 5.0);
    }


    private BuildingDetectionResult result(BuildingType type, boolean valid, double total, double required,
                                           double structureScore, double interiorScore, double accessScore,
                                           double signatureScore, double contextScore, String variant, String summary,
                                           int structuralBlocks, int footprint, Bounds bounds) {
        double progressRatio = valid ? 1.0 : clamp(total / required, 0.0, 0.99);
        return new BuildingDetectionResult(
                type,
                valid,
                progressRatio,
                total,
                required,
                structureScore,
                interiorScore,
                accessScore,
                signatureScore,
                contextScore,
                variant,
                summary,
                structuralBlocks,
                footprint,
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ
        );
    }

    private BuildingDetectionResult invalid(BuildingType type, String summary) {
        return new BuildingDetectionResult(type, false, 0.0, 0.0, 100.0,
                0.0, 0.0, 0.0, 0.0, 0.0, "None", summary, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private boolean isRelevantMaterial(Material material, BuildingType type) {
        return isConstructionMaterial(material)
                || isStorageMaterial(material)
                || material == Material.CRAFTING_TABLE
                || isBed(material)
                || isEntranceMaterial(material)
                || isAccessMaterial(material)
                || isGeneralUtility(material)
                || (type == BuildingType.WATCHTOWER && material == Material.CAMPFIRE);
    }

    private boolean isConstructionMaterial(Material material) {
        if (material == null || material.isAir() || !material.isBlock()) {
            return false;
        }
        if (!material.isSolid()) {
            return false;
        }

        String name = material.name();
        if (name.contains("LEAVES") || name.contains("ORE")) {
            return false;
        }

        return switch (material) {
            case STONE, GRANITE, DIORITE, ANDESITE, DEEPSLATE, TUFF, CALCITE, DRIPSTONE_BLOCK,
                    DIRT, COARSE_DIRT, ROOTED_DIRT, GRASS_BLOCK, PODZOL, MYCELIUM, MUD, CLAY,
                    SAND, RED_SAND, GRAVEL, SNOW_BLOCK, POWDER_SNOW, ICE, PACKED_ICE, BLUE_ICE,
                    BEDROCK, NETHERRACK, END_STONE, SOUL_SAND, SOUL_SOIL -> false;
            default -> true;
        };
    }

    private boolean isStorageMaterial(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX,
                    WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                    LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX,
                    PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                    CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX,
                    BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                    BLACK_SHULKER_BOX -> true;
            default -> false;
        };
    }

    private boolean isEntranceMaterial(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.contains("DOOR") || name.contains("TRAPDOOR") || name.contains("FENCE_GATE");
    }

    private boolean isAccessMaterial(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return material == Material.LADDER
                || material == Material.SCAFFOLDING
                || name.contains("VINE")
                || name.contains("STAIRS");
    }

    private boolean isGeneralUtility(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case FURNACE, BLAST_FURNACE, SMOKER, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                    CARTOGRAPHY_TABLE, STONECUTTER, LOOM, CAULDRON, BREWING_STAND -> true;
            default -> false;
        };
    }

    private boolean isMilitaryUtility(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case FURNACE, BLAST_FURNACE, SMOKER, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                    SMITHING_TABLE, GRINDSTONE, FLETCHING_TABLE, BREWING_STAND, CAULDRON -> true;
            default -> false;
        };
    }

    private boolean isDefensiveBlock(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        // Walls, fences, iron bars, etc.
        return name.contains("WALL") || name.contains("FENCE") || name.contains("IRON_BARS")
                || name.contains("COBWEB") || name.contains("CHAIN");
    }

    private boolean isBed(Material material) {
        return material != null && material.name().endsWith("_BED");
    }

    private boolean isBedHead(BlockData blockData) {
        if (!(blockData instanceof org.bukkit.block.data.type.Bed bed)) {
            return false;
        }
        return bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD;
    }

    private boolean matchesTeamBed(Material material, String team) {
        if (team == null || team.isBlank()) {
            return true;
        }
        return switch (team.toLowerCase()) {
            case "red" -> material == Material.RED_BED;
            case "blue" -> material == Material.BLUE_BED;
            default -> true;
        };
    }

    private boolean isPassable(Material material) {
        if (material == null) {
            return true;
        }
        if (material.isAir()) {
            return true;
        }
        return switch (material) {
            case WATER, LAVA, CAVE_AIR, VOID_AIR, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                    TORCH, WALL_TORCH, REDSTONE_TORCH, SOUL_TORCH, SOUL_WALL_TORCH -> true;
            default -> !material.isSolid() && !isEntranceMaterial(material);
        };
    }

    /**
     * Checks if a material above a block still allows the block to be considered "walkable".
     * More lenient than isPassable - allows stairs, slabs, fences, etc. above platform blocks
     * since these are common roofing/railing elements on watchtowers.
     */
    private boolean isWalkableAbove(Material material) {
        if (isPassable(material)) {
            return true;
        }
        if (material == null) {
            return true;
        }
        String name = material.name();
        // Allow stairs, slabs, fences, walls, signs, banners, and other decorative elements
        return name.contains("STAIR") || name.contains("SLAB") ||
               name.contains("FENCE") || name.contains("WALL") ||
               name.contains("SIGN") || name.contains("BANNER") ||
               name.contains("LANTERN") || name.contains("CHAIN") ||
               name.contains("CANDLE") || name.contains("CAMPFIRE");
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record BlockPos(int x, int y, int z) {
        List<BlockPos> cardinalNeighbors() {
            return List.of(
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x, y - 1, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1)
            );
        }

        List<BlockPos> horizontalNeighbors() {
            return List.of(
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1)
            );
        }
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds from(Set<BlockPos> component) {
            OptionalInt minX = component.stream().mapToInt(BlockPos::x).min();
            OptionalInt minY = component.stream().mapToInt(BlockPos::y).min();
            OptionalInt minZ = component.stream().mapToInt(BlockPos::z).min();
            OptionalInt maxX = component.stream().mapToInt(BlockPos::x).max();
            OptionalInt maxY = component.stream().mapToInt(BlockPos::y).max();
            OptionalInt maxZ = component.stream().mapToInt(BlockPos::z).max();
            return new Bounds(minX.orElse(0), minY.orElse(0), minZ.orElse(0),
                    maxX.orElse(0), maxY.orElse(0), maxZ.orElse(0));
        }
    }

    private record InteriorStats(
            int usableCells,
            int interiorCells,
            int roofedCells,
            int floorArea,
            double roofCoverage,
            double enclosureQuality,
            boolean hasEntrance
    ) {
    }

    private record ComponentStats(
            int structuralBlocks,
            int footprint,
            int baseFootprint,
            int storageBlocks,
            int craftingTables,
            int beds,
            int teamBeds,
            int entranceBlocks,
            int utilityBlocks,
            int militaryUtilityBlocks,
            int defensiveBlocks,
            Set<Integer> accessLevels
    ) {
    }

    private record TowerStats(
            int height,
            int platformSize,
            double openness,
            double accessCoverage,
            int baseFootprint,
            double supportStrength,
            double skyExposure,
            boolean exposedTerrain
    ) {
    }

    private record VariantScore(String variant, double score) {
    }
}
