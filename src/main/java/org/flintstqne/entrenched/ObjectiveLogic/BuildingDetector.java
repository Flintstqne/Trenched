package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
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
    private PlacedBlockTracker placedBlockTracker;

    public BuildingDetector(ConfigManager config) {
        this.config = config;
        this.debugEnabled = config.isBuildingDetectionDebugEnabled();
    }

    /**
     * Sets the player-placed block tracker for filtering natural terrain.
     */
    public void setPlacedBlockTracker(PlacedBlockTracker tracker) {
        this.placedBlockTracker = tracker;
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

    private static final int EXPANSION_SHELL_DEPTH = 8;

    public BuildingDetectionResult scan(World world, RegionObjective objective, BuildingType type, String team) {
        debug("=== SCAN START: %s in region %s ===", type.name(), objective.regionId());

        if (world == null || objective == null || !objective.hasLocation()) {
            debug("ABORT: Objective missing anchor location");
            return invalid(type, "Objective is missing an anchor location.");
        }

        int initialRadius = config.getBuildingDetectionRadius();
        int maxExpansionRadius = config.getBuildingMaxExpansionRadius();
        int verticalRange = config.getBuildingDetectionVerticalRange();

        // Watchtowers need extended vertical range since they are tall structures
        if (type == BuildingType.WATCHTOWER) {
            verticalRange = Math.max(verticalRange, 32); // At least 32 blocks vertical for watchtowers
        }

        int centerX = objective.locationX();
        int centerY = objective.locationY();
        int centerZ = objective.locationZ();

        // Determine if we should use player-placed block filtering
        boolean useTracking = config.isPlayerPlacedTrackingEnabled()
                && placedBlockTracker != null
                && placedBlockTracker.isRegionLoaded(objective.regionId());
        Set<Long> placedSet = useTracking ? placedBlockTracker.getRegionSet(objective.regionId()) : null;

        if (useTracking) {
            debug("Using player-placed block filtering (tracked blocks: %d)", placedSet != null ? placedSet.size() : 0);
        } else {
            debug("Using material-based filtering (tracking %s)",
                    placedBlockTracker == null ? "not available" :
                    !config.isPlayerPlacedTrackingEnabled() ? "disabled" : "region not loaded");
        }

        int minY = Math.max(world.getMinHeight(), centerY - verticalRange);
        int maxY = Math.min(world.getMaxHeight() - 1, centerY + verticalRange);

        // ── Adaptive scan: start at initial radius, expand outward if blocks touch the edge ──
        Map<BlockPos, Material> relevantBlocks = new HashMap<>();
        int scannedRadius = 0;
        int currentRadius = initialRadius;

        while (currentRadius <= maxExpansionRadius) {
            int before = relevantBlocks.size();
            scanShell(world, type, centerX, centerZ, minY, maxY,
                      scannedRadius, currentRadius, useTracking, placedSet, relevantBlocks);
            int found = relevantBlocks.size() - before;

            debug("Scan shell r=%d→%d: found %d new blocks (%d total)",
                  scannedRadius, currentRadius, found, relevantBlocks.size());

            // Check if any block is within 2 blocks of the current scan edge.
            // If so, the building may extend beyond — expand.
            int edgeThresholdSq = (currentRadius - 2) * (currentRadius - 2);
            boolean hasEdgeBlocks = false;
            for (BlockPos pos : relevantBlocks.keySet()) {
                int dx = pos.x - centerX;
                int dz = pos.z - centerZ;
                if (dx * dx + dz * dz >= edgeThresholdSq) {
                    hasEdgeBlocks = true;
                    break;
                }
            }

            scannedRadius = currentRadius;

            if (!hasEdgeBlocks) {
                debug("No blocks near edge at r=%d — stopping expansion", currentRadius);
                break;
            }

            if (currentRadius >= maxExpansionRadius) {
                debug("Hit max expansion radius %d — stopping", maxExpansionRadius);
                break;
            }

            // Expand
            currentRadius = Math.min(currentRadius + EXPANSION_SHELL_DEPTH, maxExpansionRadius);
            debug("Blocks near edge detected — expanding to r=%d", currentRadius);
        }

        debug("Scanning at %d,%d,%d (effective radius=%d, vertical=%d)", centerX, centerY, centerZ, scannedRadius, verticalRange);
        debug("Found %d relevant blocks in scan area", relevantBlocks.size());

        if (relevantBlocks.isEmpty()) {
            debug("RESULT: No qualifying structure blocks found");
            return invalid(type, "No qualifying structure blocks found near the objective.");
        }

        List<Set<BlockPos>> components = splitIntoComponents(relevantBlocks.keySet());

        // Merge nearby components that are separated by natural terrain (sand, dirt, grass, etc.)
        // Two components are merged if any of their blocks are within MERGE_DISTANCE blocks of each other
        components = mergeNearbyComponents(components);

        // Use the actual scanned radius for anchoring — any block within the scan area is
        // "near enough" to the objective.
        int anchorRadius = scannedRadius;
        int totalRelevantBlockCount = relevantBlocks.size();

        debug("Split into %d connected components (anchor radius=%d)", components.size(), anchorRadius);

        // Collect all anchored components and merge them into a single building.
        // In the scan area around one objective, all player-placed blocks are almost
        // certainly part of the same building, even if flood-fill couldn't connect them
        // (e.g. rooms separated by natural ground floors, wide doorways, or hallways).
        Set<BlockPos> mergedBuilding = new HashSet<>();
        int anchoredCount = 0;
        int componentIndex = 0;

        for (Set<BlockPos> component : components) {
            componentIndex++;

            int compMinY = component.stream().mapToInt(p -> p.y).min().orElse(0);
            int compMaxY = component.stream().mapToInt(p -> p.y).max().orElse(0);
            debug("Component #%d (%d blocks): Y range %d to %d (height span: %d)",
                  componentIndex, component.size(), compMinY, compMaxY, compMaxY - compMinY);

            if (!isAnchoredNearObjective(component, centerX, centerY, centerZ, anchorRadius)) {
                debug("Component #%d (%d blocks): NOT anchored near objective", componentIndex, component.size());
                continue;
            }

            anchoredCount++;
            debug("Component #%d (%d blocks): Anchored — merging into building", componentIndex, component.size());
            mergedBuilding.addAll(component);
        }

        if (mergedBuilding.isEmpty()) {
            debug("RESULT: No anchored components found");
            debug("=== SCAN END: %s ===", type.name());
            return invalid(type, "No structure is anchored close enough to the objective.");
        }

        debug("Merged %d anchored components into single building (%d blocks)", anchoredCount, mergedBuilding.size());

        ScanContext scanCtx = new ScanContext(components.size(), anchoredCount, totalRelevantBlockCount);
        BuildingDetectionResult best = evaluateComponent(world, objective, type, team, mergedBuilding, useTracking, placedSet, scanCtx);

        debug("RESULT: %d anchored components (%d blocks), score=%.1f, valid=%s",
            anchoredCount, mergedBuilding.size(), best.totalScore(), best.valid());
        debug("=== SCAN END: %s ===", type.name());

        return best;
    }

    /**
     * Scans a cylindrical shell between {@code innerRadius} (exclusive) and {@code outerRadius}
     * (inclusive) around ({@code centerX}, {@code centerZ}), adding any relevant player-built
     * blocks to {@code out}. On the first call pass {@code innerRadius = 0} to scan the full circle.
     */
    private void scanShell(World world, BuildingType type, int centerX, int centerZ,
                           int minY, int maxY, int innerRadius, int outerRadius,
                           boolean useTracking, Set<Long> placedSet,
                           Map<BlockPos, Material> out) {
        int innerSq = innerRadius * innerRadius;
        int outerSq = outerRadius * outerRadius;

        for (int x = centerX - outerRadius; x <= centerX + outerRadius; x++) {
            for (int z = centerZ - outerRadius; z <= centerZ + outerRadius; z++) {
                int dx = x - centerX;
                int dz = z - centerZ;
                int distSq = dx * dx + dz * dz;

                // Only scan the shell (skip already-scanned interior)
                if (distSq > outerSq || distSq <= innerSq) continue;

                for (int y = minY; y <= maxY; y++) {
                    Material material = world.getBlockAt(x, y, z).getType();
                    if (!isRelevantMaterial(material, type)) continue;

                    if (useTracking) {
                        if (isInherentlyPlayerPlaced(material)
                                || isProcessedConstruction(material)
                                || (placedSet != null && placedSet.contains(PlacedBlockTracker.packCoord(x, y, z)))) {
                            out.put(new BlockPos(x, y, z), material);
                        }
                    } else {
                        if (isInherentlyPlayerPlaced(material) || isProcessedConstruction(material)) {
                            out.put(new BlockPos(x, y, z), material);
                        }
                    }
                }
            }
        }
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
     * Merges components that are within {@code MERGE_DISTANCE} blocks of each other.
     * This handles cases where natural terrain (sand, dirt, grass) breaks
     * flood-fill connectivity between walls, floors, and roofs.
     * For example: walls placed on sand don't connect to a placed floor
     * because sand isn't a construction material.  A distance of 3 covers
     * 2-block-wide doorways and moderate hallway gaps between rooms.
     */
    private static final int MERGE_DISTANCE = 3;

    private List<Set<BlockPos>> mergeNearbyComponents(List<Set<BlockPos>> components) {
        if (components.size() <= 1) return components;

        // Build a spatial index for fast proximity lookups.
        // Key: raw block coordinate -> list of component indices whose blocks are within MERGE_DISTANCE of that position.
        // Each block in component i is written to all positions in the ±MERGE_DISTANCE neighbourhood around it.
        // The query phase then does a single exact lookup of the query block's coordinate;
        // if component i wrote to that coordinate, the two blocks are ≤MERGE_DISTANCE apart (Chebyshev).
        Map<Long, List<Integer>> spatialIndex = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (BlockPos pos : components.get(i)) {
                for (int dx = -MERGE_DISTANCE; dx <= MERGE_DISTANCE; dx++) {
                    for (int dy = -MERGE_DISTANCE; dy <= MERGE_DISTANCE; dy++) {
                        for (int dz = -MERGE_DISTANCE; dz <= MERGE_DISTANCE; dz++) {
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
        // Use a more generous vertical tolerance than the horizontal anchor radius.
        // Objectives can end up slightly underground or at a different elevation
        // than the player's build. Allow the full vertical scan range for matching.
        int verticalTolerance = Math.max(anchorRadius, config.getBuildingDetectionVerticalRange());
        for (BlockPos pos : component) {
            int dx = pos.x - centerX;
            int dz = pos.z - centerZ;
            if ((dx * dx) + (dz * dz) <= radiusSquared && Math.abs(pos.y - centerY) <= verticalTolerance) {
                return true;
            }
        }
        return false;
    }

    private BuildingDetectionResult evaluateComponent(World world, RegionObjective objective, BuildingType type,
                                                      String team, Set<BlockPos> component,
                                                      boolean useTracking, Set<Long> placedSet, ScanContext scanCtx) {
        Bounds bounds = Bounds.from(component);
        ComponentStats stats = collectComponentStats(world, component, bounds, team);
        InteriorStats interior = analyzeInterior(world, bounds, useTracking, placedSet);

        return switch (type) {
            case OUTPOST -> scoreOutpost(world, objective, bounds, stats, interior, scanCtx);
            case WATCHTOWER -> scoreWatchtower(world, objective, bounds, stats, scanCtx);
            case GARRISON -> scoreGarrison(world, bounds, stats, interior, team, scanCtx);
        };
    }

    private BuildingDetectionResult scoreOutpost(World world, RegionObjective objective, Bounds bounds,
                                                  ComponentStats stats, InteriorStats interior, ScanContext scanCtx) {
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
                variant.variant, summary, stats.structuralBlocks, stats.footprint, 0, bounds, scanCtx);
    }

    private BuildingDetectionResult scoreWatchtower(World world, RegionObjective objective, Bounds bounds,
                                                    ComponentStats stats, ScanContext scanCtx) {
        debug("  [WATCHTOWER] Scoring component at bounds %d,%d,%d to %d,%d,%d",
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        TowerStats tower = analyzeTower(world, objective, stats, bounds);

        debug("  [WATCHTOWER] Tower analysis: height=%d (need 14), platform=%d (need 9), base=%d (need 9), structural=%d (need 40)",
            tower.height, tower.platformSize, tower.baseFootprint, stats.structuralBlocks);
        debug("  [WATCHTOWER] Tower ratios: access=%.0f%% (need 55%%), support=%.0f%% (need 65%%), openness=%.0f%% (need 35%%), bodyDensity=%.0f%% (need 30%%)",
            tower.accessCoverage * 100, tower.supportStrength * 100, tower.openness * 100, tower.midSectionDensity * 100);

        boolean enoughHeight = tower.height >= 14;
        boolean enoughPlatform = tower.platformSize >= 9;
        boolean enoughAccess = tower.accessCoverage >= 0.55;
        boolean enoughSupport = tower.supportStrength >= 0.65;
        boolean enoughOpenness = tower.openness >= 0.35;
        boolean strongBase = tower.baseFootprint >= 9;
        boolean enoughStructure = stats.structuralBlocks >= 40;
        boolean enoughBody = tower.midSectionDensity >= 0.30;

        double structureScore = clamp((tower.height / 16.0) * 12.0
                + (stats.structuralBlocks / 50.0) * 10.0
                + (tower.baseFootprint / 12.0) * 8.0
                + tower.midSectionDensity * 5.0, 0.0, 35.0);
        double interiorScore = clamp(tower.supportStrength * 15.0, 0.0, 15.0);
        double accessScore = clamp(tower.accessCoverage * 20.0
                + Math.min(5.0, stats.entranceBlocks * 1.5), 0.0, 25.0);
        double signatureScore = clamp((tower.platformSize / 12.0) * 15.0
                + tower.openness * 10.0, 0.0, 25.0);
        double contextScore = clamp((tower.skyExposure * 10.0) + (tower.exposedTerrain ? 5.0 : 0.0), 0.0, 15.0);

        double total = structureScore + interiorScore + accessScore + signatureScore + contextScore;

        debug("  [WATCHTOWER] Scores: structure=%.1f/35, interior=%.1f/15, access=%.1f/25, signature=%.1f/25, context=%.1f/15",
            structureScore, interiorScore, accessScore, signatureScore, contextScore);
        debug("  [WATCHTOWER] TOTAL: %.1f/%.1f (%.0f%%)", total, WATCHTOWER_REQUIRED_SCORE, (total / WATCHTOWER_REQUIRED_SCORE) * 100);

        List<String> failures = new ArrayList<>();
        if (!enoughHeight) failures.add("more height (need 14+ blocks tall)");
        if (!enoughPlatform) failures.add("a larger top platform (need 3x3 minimum)");
        if (!enoughAccess) failures.add("a climbable route (ladder or stairs)");
        if (!enoughSupport) failures.add("stronger structural support");
        if (!enoughOpenness) failures.add("clearer top visibility");
        if (!strongBase) failures.add("a wider base (need 3x3 minimum footprint)");
        if (!enoughStructure) failures.add("more structural blocks (walls, supports)");
        if (!enoughBody) failures.add("a wider tower body (not just a column — add walls or supports through the midsection)");
        if (total < WATCHTOWER_REQUIRED_SCORE) failures.add("a more convincing tower shape");

        boolean valid = failures.isEmpty();

        if (!valid) {
            debug("  [WATCHTOWER] FAILING CHECKS: %s", String.join(", ", failures));
            debug("  [WATCHTOWER] Check details: height=%b, platform=%b, access=%b, support=%b, openness=%b, base=%b, structure=%b, body=%b, score=%b",
                enoughHeight, enoughPlatform, enoughAccess, enoughSupport, enoughOpenness, strongBase, enoughStructure, enoughBody, total >= WATCHTOWER_REQUIRED_SCORE);
        }

        String summary = valid ? "Valid watchtower" : "Watchtower needs " + String.join(", ", failures) + ".";

        return result(BuildingType.WATCHTOWER, valid, total, WATCHTOWER_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                "Watchtower", summary, stats.structuralBlocks, stats.footprint, 0, bounds, scanCtx);
    }

    private BuildingDetectionResult scoreGarrison(World world, Bounds bounds, ComponentStats stats,
                                                  InteriorStats interior, String team, ScanContext scanCtx) {
        debug("  [GARRISON] Scoring component at bounds %d,%d,%d to %d,%d,%d",
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);

        VariantScore variant = detectGarrisonVariant(world, bounds, stats);
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
                ? "Valid garrison" + (variant.variant.equals("Basic Garrison") ? "" : " (" + variant.variant + ")")
                : "Garrison needs " + String.join(", ", failures) + ".";

        return result(BuildingType.GARRISON, valid, total, GARRISON_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                variant.variant, summary, stats.structuralBlocks, stats.footprint, countedBeds, bounds, scanCtx);
    }

    private ComponentStats collectComponentStats(World world, Set<BlockPos> component, Bounds bounds, String team) {
        Set<String> footprint = new HashSet<>();
        Set<String> baseFootprint = new HashSet<>();
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
                    baseFootprint.add(pos.x + ":" + pos.z);
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

    private InteriorStats analyzeInterior(World world, Bounds bounds, boolean useTracking, Set<Long> placedSet) {
        int usableCells = 0;
        int interiorCells = 0;
        int roofedCells = 0;
        int shelteredCells = 0;
        int openings = 0;
        Set<String> floorArea = new HashSet<>();

        // Expand scan area by 1 block in each direction so that standing spaces right
        // at the edge of the component (e.g. next to a door) are still evaluated.
        int scanMinX = bounds.minX - 1, scanMaxX = bounds.maxX + 1;
        int scanMinZ = bounds.minZ - 1, scanMaxZ = bounds.maxZ + 1;
        // Expand Y downward by 1 to catch standing positions whose floor is bounds.minY
        int scanMinY = bounds.minY - 1, scanMaxY = bounds.maxY + 1;

        for (int x = scanMinX; x <= scanMaxX; x++) {
            for (int z = scanMinZ; z <= scanMaxZ; z++) {
                for (int y = Math.max(world.getMinHeight(), scanMinY); y <= Math.min(world.getMaxHeight() - 2, scanMaxY); y++) {
                    Material feetBlock = world.getBlockAt(x, y, z).getType();
                    Material headBlock = world.getBlockAt(x, y + 1, z).getType();

                    // Check if this is a standing space (passable at feet and head level)
                    if (!isPassable(feetBlock) || !isPassable(headBlock)) {
                        continue;
                    }

                    // Check for floor below (can be 1 or 2 blocks below for multi-level or raised buildings).
                    // Accept any solid block as floor — natural ground (dirt, grass, stone) is a
                    // valid surface to stand on. The building quality comes from the walls & roof,
                    // not from whether the player replaced the ground.
                    Material floor1 = world.getBlockAt(x, y - 1, z).getType();
                    Material floor2 = world.getBlockAt(x, y - 2, z).getType();
                    boolean hasFloor = isValidFloorBlock(floor1) || isValidFloorBlock(floor2);

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
                        String sideName = side.name();
                        String sideHeadName = sideHead.name();

                        // Count as wall if either level is blocked.
                        // Use endsWith("_WALL") / endsWith("_FENCE") to avoid matching
                        // WALL_TORCH, WALL_SIGN, WALL_BANNER, etc.
                        boolean isWall = isConstructionMaterial(side) || isEntranceMaterial(side)
                                || isConstructionMaterial(sideHead) || isEntranceMaterial(sideHead)
                                || sideName.endsWith("_FENCE") || sideName.endsWith("_WALL")
                                || sideHeadName.endsWith("_FENCE") || sideHeadName.endsWith("_WALL");

                        if (isWall) {
                            sideWalls++;
                        } else if (isPassable(side) && isPassable(sideHead)) {
                            opening = true;
                        }
                    }

                    // Check for roof (extended range: 2-8 blocks above)
                    boolean roof = hasRoofExtended(world, x, y, z, useTracking, placedSet);

                    // Only count as "usable" if it has a roof OR is enclosed by 2+ walls.
                    // Cells with just 1 wall (exterior face of the building) are typically
                    // outdoor and should not inflate the denominator for roofCoverage.
                    if (roof || sideWalls >= 2) {
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

    /**
     * Checks if a material is a valid floor surface for interior analysis.
     * Accepts construction materials, slabs, stairs, AND natural solid ground.
     * Natural ground (dirt, grass, stone) is a valid surface to stand on — the building
     * quality comes from walls and roof, not from whether the player replaced the terrain.
     */
    private boolean isValidFloorBlock(Material material) {
        if (material == null || material.isAir()) return false;
        if (isConstructionMaterial(material)) return true;
        String name = material.name();
        if (name.contains("SLAB") || name.contains("STAIR")) return true;
        // Accept any solid block as a floor surface (dirt, grass, stone, sand, etc.)
        return material.isSolid();
    }

    private boolean hasRoofExtended(World world, int x, int y, int z, boolean useTracking, Set<Long> placedSet) {
        // y is the feet level where player stands (air)
        // y+1 is head level (also air for standing space)
        // Roof could be at y+2 (right above head) up to y+8 (tall ceiling)
        for (int dy = 2; dy <= 8; dy++) {
            int roofY = y + dy;
            Material above = world.getBlockAt(x, roofY, z).getType();
            if (above == null) continue;

            // Check if this is a roof-like material
            if (isConstructionMaterial(above)) return true;
            if (isEntranceMaterial(above)) return true; // Trapdoors can be roof

            String name = above.name();
            if (name.contains("SLAB") || name.contains("STAIR") || name.contains("CARPET")
                    || name.contains("GLASS")) {
                return true;
            }

            // Leaves only count as roof if player-placed (not natural trees).
            // With tracking enabled: must be in the placed set.
            // With tracking disabled: leaves are never counted (can't distinguish from trees).
            if (name.contains("LEAVES")) {
                if (useTracking && placedSet != null
                        && placedSet.contains(PlacedBlockTracker.packCoord(x, roofY, z))) {
                    return true;
                }
                // Natural leaves — don't count, but don't stop scanning either
                // (there could be a real roof block above the tree canopy)
            }
        }
        return false;
    }

    private TowerStats analyzeTower(World world, RegionObjective objective, ComponentStats stats, Bounds bounds) {
        int objectiveBaseY = objective.locationY() == null ? bounds.minY : objective.locationY();
        int highestWalkableY = Integer.MIN_VALUE;
        Map<Integer, Integer> walkableByY = new HashMap<>();
        Map<Integer, Integer> structuralByY = new HashMap<>(); // ALL construction blocks per Y level
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

                    // Count ALL structural blocks per Y for body density analysis
                    structuralByY.merge(y, 1, Integer::sum);

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

        // Mid-section density: what fraction of body Y levels (between base+3 and platform-2)
        // have at least 3 structural blocks? This prevents thin ladder-column cheats where
        // only the base and top platform have any width.
        int bodyStart = bounds.minY + 3;
        int bodyEnd = highestWalkableY == Integer.MIN_VALUE ? bounds.minY : highestWalkableY - 2;
        int bodyLevels = 0;
        int denseBodyLevels = 0;
        if (bodyEnd >= bodyStart) {
            for (int y = bodyStart; y <= bodyEnd; y++) {
                bodyLevels++;
                if (structuralByY.getOrDefault(y, 0) >= 3) {
                    denseBodyLevels++;
                }
            }
        }
        double midSectionDensity = bodyLevels <= 0 ? 0.0 : (double) denseBodyLevels / bodyLevels;

        debug("  [WATCHTOWER] Final: height=%d, platformSize=%d, openness=%.2f, access=%.2f, skyExposure=%.2f, bodyDensity=%.0f%% (%d/%d levels)",
              height, platformSize, openness, accessCoverage, skyExposure,
              midSectionDensity * 100, denseBodyLevels, bodyLevels);

        return new TowerStats(height, platformSize, openness, accessCoverage, stats.baseFootprint,
                supportStrength, skyExposure, exposedTerrain, midSectionDensity);
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
        // Two-pass approach: first check for full matches (environment + items), then
        // fall back to the best partial-match hint. This prevents a high-priority environment
        // (e.g. water) from blocking a lower-priority but fully-matched variant (e.g. Farm).

        // --- Pass 1: Full matches (return immediately if found) ---
        if (water >= 35 && hasFishingRod) {
            return new VariantScore("Fishing Outpost", 5.0);
        }
        if (crops >= 18 && hasHoe) {
            return new VariantScore("Farm Outpost", 5.0);
        }
        boolean isDesertEnv = sand >= 30 || biomeName.contains("DESERT") || biomeName.contains("BADLANDS");
        if (isDesertEnv && cactusBlocks >= 3 && hasWaterBucket) {
            return new VariantScore("Desert Outpost", 5.0);
        }
        boolean isMountainEnv = objective.locationY() != null && objective.locationY() >= world.getSeaLevel() + 22;
        if (isMountainEnv && hasLadder && woolBlocks >= 3) {
            return new VariantScore("Mountain Outpost", 5.0);
        }
        boolean isMiningEnv = ore >= 6 || (objective.locationY() != null && objective.locationY() <= world.getSeaLevel() - 10);
        if (isMiningEnv && hasFurnace && hasPickaxe) {
            return new VariantScore("Mining Outpost", 5.0);
        }
        boolean isForestEnv = (logs + leaves) >= 40 || biomeName.contains("FOREST") || biomeName.contains("TAIGA") || biomeName.contains("JUNGLE");
        if (isForestEnv && logsInChests >= 10 && hasAxe) {
            return new VariantScore("Forest Outpost", 5.0);
        }

        // --- Pass 2: No full match — show hint for the best partially-matched variant ---
        if (water >= 35) {
            debug("  [VARIANT] Fishing environment detected but missing Fishing Rod in chest");
            return new VariantScore("Fishing Outpost (needs Fishing Rod in chest)", 3.0);
        }
        if (crops >= 18) {
            debug("  [VARIANT] Farm environment detected but missing Hoe in chest");
            return new VariantScore("Farm Outpost (needs Hoe in chest)", 3.0);
        }
        if (isDesertEnv) {
            List<String> missing = new ArrayList<>();
            if (cactusBlocks < 3) missing.add("3+ Cactus blocks");
            if (!hasWaterBucket) missing.add("Water Bucket in chest");
            debug("  [VARIANT] Desert environment detected but missing: cactus=%d/3, waterBucket=%b", cactusBlocks, hasWaterBucket);
            return new VariantScore("Desert Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }
        if (isMountainEnv) {
            List<String> missing = new ArrayList<>();
            if (!hasLadder) missing.add("Ladder");
            if (woolBlocks < 3) missing.add("3+ Wool blocks");
            debug("  [VARIANT] Mountain environment detected but missing: ladder=%b, wool=%d/3", hasLadder, woolBlocks);
            return new VariantScore("Mountain Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }
        if (isMiningEnv) {
            List<String> missing = new ArrayList<>();
            if (!hasFurnace) missing.add("Furnace");
            if (!hasPickaxe) missing.add("Pickaxe in chest");
            debug("  [VARIANT] Mining environment detected but missing: furnace=%b, pickaxe=%b", hasFurnace, hasPickaxe);
            return new VariantScore("Mining Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }
        if (isForestEnv) {
            List<String> missing = new ArrayList<>();
            if (logsInChests < 10) missing.add("10+ Logs in chest");
            if (!hasAxe) missing.add("Axe in chest");
            debug("  [VARIANT] Forest environment detected but missing: logsInChest=%d/10, axe=%b", logsInChests, hasAxe);
            return new VariantScore("Forest Outpost (needs " + String.join(", ", missing) + ")", 3.0);
        }

        return new VariantScore("Standard", 2.0);
    }

    private VariantScore detectGarrisonVariant(World world, Bounds bounds, ComponentStats stats) {
        // Variants do NOT stack — first match in priority order wins.
        // Priority: Fortified > Command > Supply > Armory > Medical > Basic

        // === PHASE 1: Scan container contents within building bounds ===
        // Mirrors detectOutpostVariant Phase 2: iterate the bounds (+2 margin) and read every Container.
        int scanMinX = bounds.minX - 2, scanMaxX = bounds.maxX + 2;
        int scanMinY = bounds.minY - 1, scanMaxY = bounds.maxY + 2;
        int scanMinZ = bounds.minZ - 2, scanMaxZ = bounds.maxZ + 2;

        int healingPotions = 0;  // potions of Healing or Regeneration — for Medical
        int ironIngots    = 0;   // iron ingots — for Armory
        int totalItems    = 0;   // all items summed — for Supply (need 64+)

        for (int sx = scanMinX; sx <= scanMaxX; sx++) {
            for (int sz = scanMinZ; sz <= scanMaxZ; sz++) {
                for (int sy = Math.max(world.getMinHeight(), scanMinY);
                         sy <= Math.min(world.getMaxHeight() - 1, scanMaxY); sy++) {

                    if (!(world.getBlockAt(sx, sy, sz).getState() instanceof Container container)) continue;

                    for (ItemStack item : container.getInventory().getContents()) {
                        if (item == null || item.getType().isAir()) continue;

                        int amount = item.getAmount();
                        totalItems += amount;

                        Material itemType = item.getType();

                        if (itemType == Material.IRON_INGOT) {
                            ironIngots += amount;
                        }

                        if ((itemType == Material.POTION
                                || itemType == Material.SPLASH_POTION
                                || itemType == Material.LINGERING_POTION)
                                && item.getItemMeta() instanceof PotionMeta meta) {
                            PotionType base = meta.getBasePotionType();
                            if (base == PotionType.HEALING
                                    || base == PotionType.STRONG_HEALING
                                    || base == PotionType.REGENERATION
                                    || base == PotionType.LONG_REGENERATION
                                    || base == PotionType.STRONG_REGENERATION) {
                                healingPotions += amount;
                            }
                        }
                    }
                }
            }
        }

        debug("  [GARRISON-VARIANT] Chest scan: healingPotions=%d/3, ironIngots=%d/5, totalItems=%d/64",
                healingPotions, ironIngots, totalItems);
        debug("  [GARRISON-VARIANT] Block counts: defensive=%d/20, military=%d/2, storage=%d/4, utility=%d",
                stats.defensiveBlocks, stats.militaryUtilityBlocks, stats.storageBlocks, stats.utilityBlocks);

        // === PHASE 2: Full variants (first match wins) ===

        // Fortified: 20+ defensive blocks — no chest item needed
        if (stats.defensiveBlocks >= 20) {
            return new VariantScore("Fortified Garrison", 10.0);
        }

        // Command: 2+ military utility (lectern + banner) — no extra chest item needed;
        // the combination of command-post furniture IS the signal
        if (stats.militaryUtilityBlocks >= 2) {
            return new VariantScore("Command Garrison", 9.0);
        }

        // Supply: 4+ storage containers AND 64+ items total across all storage
        if (stats.storageBlocks >= 4) {
            if (totalItems >= 64) {
                return new VariantScore("Supply Garrison", 8.0);
            }
            int needed = 64 - totalItems;
            debug("  [GARRISON-VARIANT] Supply blocks met but only %d/64 items", totalItems);
            return new VariantScore("Supply Garrison (needs " + needed + " more items in chests)", 5.5);
        }

        // Armory: anvil/smithing table + storage + 5 iron ingots in chest
        if (stats.militaryUtilityBlocks >= 1 && stats.storageBlocks >= 1) {
            if (ironIngots >= 5) {
                return new VariantScore("Armory Garrison", 7.0);
            }
            int needed = 5 - ironIngots;
            debug("  [GARRISON-VARIANT] Armory blocks met but only %d/5 iron ingots", ironIngots);
            return new VariantScore("Armory Garrison (needs " + needed + " more iron ingots in chest)", 5.5);
        }

        // Medical: brewing stand/cauldron + storage + 3 healing/regen potions in chest
        if (stats.utilityBlocks >= 1 && stats.storageBlocks >= 1) {
            if (healingPotions >= 3) {
                return new VariantScore("Medical Garrison", 6.0);
            }
            int needed = 3 - healingPotions;
            debug("  [GARRISON-VARIANT] Medical blocks met but only %d/3 healing potions", healingPotions);
            return new VariantScore("Medical Garrison (needs " + needed + " more healing potions in chest)", 5.5);
        }

        // === PHASE 3: Near-miss hints (one hint, highest achievable tier) ===

        if (stats.defensiveBlocks >= 10) {
            int needed = 20 - stats.defensiveBlocks;
            return new VariantScore("Fortified Garrison (needs " + needed + " more wall/fence blocks)", 5.5);
        }

        if (stats.militaryUtilityBlocks == 1 && stats.storageBlocks == 0) {
            return new VariantScore("Armory Garrison (needs a chest + 5 iron ingots)", 5.5);
        }

        if (stats.storageBlocks == 3) {
            return new VariantScore("Supply Garrison (needs 1 more chest and items)", 5.5);
        }

        if (stats.utilityBlocks >= 1 && stats.storageBlocks == 0) {
            return new VariantScore("Medical Garrison (needs a chest + 3 healing potions)", 5.5);
        }

        return new VariantScore("Basic Garrison", 5.0);
    }


    private record ScanContext(int componentCount, int anchoredCount, int totalRelevantBlocks) {}

    private BuildingDetectionResult result(BuildingType type, boolean valid, double total, double required,
                                           double structureScore, double interiorScore, double accessScore,
                                           double signatureScore, double contextScore, String variant, String summary,
                                           int structuralBlocks, int footprint, int bedCount, Bounds bounds,
                                           ScanContext scanCtx) {
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
                bounds.maxZ,
                bedCount,
                scanCtx.componentCount,
                scanCtx.anchoredCount,
                scanCtx.totalRelevantBlocks
        );
    }

    private BuildingDetectionResult invalid(BuildingType type, String summary) {
        return new BuildingDetectionResult(type, false, 0.0, 0.0, 100.0,
                0.0, 0.0, 0.0, 0.0, 0.0, "None", summary, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private boolean isRelevantMaterial(Material material, BuildingType type) {
        return isConstructionMaterial(material)
                || isStorageMaterial(material)
                || material == Material.CRAFTING_TABLE
                || isBed(material)
                || isEntranceMaterial(material)
                || isAccessMaterial(material)
                || isGeneralUtility(material)
                || isMilitaryUtility(material)
                || isDefensiveBlock(material)
                || (type == BuildingType.WATCHTOWER && material == Material.CAMPFIRE);
    }

    /**
     * Returns true for materials that never generate naturally in the world.
     * These blocks don't need player-placed tracking because their mere existence
     * at a location proves a player placed them.
     */
    private boolean isInherentlyPlayerPlaced(Material material) {
        if (material == null) return false;
        // Storage blocks
        if (isStorageMaterial(material)) return true;
        // Utility blocks
        if (isGeneralUtility(material)) return true;
        // Entrance blocks (doors, trapdoors, fence gates)
        if (isEntranceMaterial(material)) return true;
        // Other inherently player-placed blocks
        return switch (material) {
            case CRAFTING_TABLE, CAMPFIRE, SOUL_CAMPFIRE,
                    LADDER, SCAFFOLDING,
                    LECTERN, ENCHANTING_TABLE,
                    SMITHING_TABLE, GRINDSTONE, FLETCHING_TABLE,
                    BOOKSHELF, LANTERN, SOUL_LANTERN,
                    TORCH, WALL_TORCH, SOUL_TORCH, SOUL_WALL_TORCH,
                    REDSTONE_TORCH, REDSTONE_WALL_TORCH,
                    // Glass blocks (never generate naturally)
                    GLASS, GLASS_PANE, TINTED_GLASS,
                    // Iron bars can generate in some structures but are overwhelmingly player-placed
                    IRON_BARS -> true;
            default -> {
                String name = material.name();
                // Beds, banners, stained glass, stained glass panes, and carpet are always player-placed
                yield name.contains("_BED") || name.contains("_BANNER")
                        || name.contains("STAINED_GLASS")
                        || name.contains("_CARPET");
            }
        };
    }

    /**
     * Returns true for materials that require crafting or smelting — their presence
     * proves intentional player construction even without placed-block tracking.
     * Examples: planks (crafted from logs), bricks (smelted from clay), polished stone
     * variants, concrete, cut sandstone, etc.
     */
    private boolean isProcessedConstruction(Material material) {
        if (material == null) return false;
        String name = material.name();

        // Cobblestone (obtained by mining stone — never generates as natural terrain)
        if (material == Material.COBBLESTONE || material == Material.MOSSY_COBBLESTONE
                || material == Material.COBBLED_DEEPSLATE) {
            return true;
        }

        // Sandstone / Red Sandstone (crafted from 4 sand)
        if (material == Material.SANDSTONE || material == Material.RED_SANDSTONE) return true;

        // Planks (crafted from logs)
        if (name.endsWith("_PLANKS")) return true;

        // Slabs and stairs (crafted from base materials)
        if (name.contains("SLAB") || name.contains("STAIRS")) return true;

        // Bricks (smelted/crafted)
        if (material == Material.BRICKS || material == Material.BRICK_WALL
                || material == Material.BRICK_SLAB || material == Material.BRICK_STAIRS
                || material == Material.NETHER_BRICKS || material == Material.NETHER_BRICK_WALL
                || material == Material.NETHER_BRICK_SLAB || material == Material.NETHER_BRICK_STAIRS
                || material == Material.RED_NETHER_BRICKS || material == Material.RED_NETHER_BRICK_WALL
                || material == Material.RED_NETHER_BRICK_SLAB || material == Material.RED_NETHER_BRICK_STAIRS) {
            return true;
        }

        // Polished/cut/smooth variants (require stonecutting or smelting)
        if (name.startsWith("POLISHED_") || name.startsWith("SMOOTH_")
                || name.startsWith("CUT_") || name.startsWith("CHISELED_")) {
            return true;
        }

        // Stone bricks (crafted from stone)
        if (name.contains("STONE_BRICK")) return true;

        // Concrete (formed from concrete powder + water)
        if (name.endsWith("_CONCRETE") && !name.contains("POWDER")) return true;

        // Terracotta (smelted from clay) — includes glazed and colored variants
        if (name.contains("TERRACOTTA")) return true;

        // Stripped logs/wood (player stripped)
        if (name.contains("STRIPPED_")) return true;

        // Quartz blocks (crafted from nether quartz)
        if (name.contains("QUARTZ") && !name.equals("NETHER_QUARTZ_ORE")) return true;

        // Prismarine variants (crafted)
        if (material == Material.PRISMARINE_BRICKS || material == Material.DARK_PRISMARINE) return true;

        // Purpur (crafted from chorus fruit)
        if (name.contains("PURPUR")) return true;

        // Glass (smelted from sand)
        if (name.contains("GLASS")) return true;

        // Wool (crafted/dyed)
        if (name.contains("WOOL")) return true;

        // Copper variants that are waxed or cut (player-processed)
        if (name.startsWith("WAXED_") || name.startsWith("CUT_COPPER")) return true;

        // Wall blocks — ALL wall-type blocks are crafted via stonecutter/crafting table
        // Uses endsWith to avoid matching WALL_TORCH, WALL_SIGN, etc.
        if (name.endsWith("_WALL")) return true;

        // Fence blocks — ALL fences are crafted (OAK_FENCE, NETHER_BRICK_FENCE, etc.)
        // Uses endsWith to avoid matching FENCE_GATE (entrance block)
        if (name.endsWith("_FENCE")) return true;

        // Metal/mineral blocks (crafted from 9 ingots/gems)
        if (material == Material.IRON_BLOCK || material == Material.GOLD_BLOCK
                || material == Material.DIAMOND_BLOCK || material == Material.EMERALD_BLOCK
                || material == Material.LAPIS_BLOCK || material == Material.REDSTONE_BLOCK
                || material == Material.COPPER_BLOCK || material == Material.RAW_IRON_BLOCK
                || material == Material.RAW_GOLD_BLOCK || material == Material.RAW_COPPER_BLOCK) {
            return true;
        }

        return false;
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
        // Exclude unstripped logs/wood — these are natural tree blocks.
        // Stripped variants (STRIPPED_OAK_LOG, etc.) still count since stripping requires player action.
        if ((name.contains("LOG") || name.contains("WOOD")) && !name.contains("STRIPPED")) {
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
        // Civilian crafting/utility — Medical Garrison indicator (BREWING_STAND)
        // No overlap with isMilitaryUtility
        return switch (material) {
            case FURNACE, BLAST_FURNACE, SMOKER,
                    CARTOGRAPHY_TABLE, STONECUTTER, LOOM,
                    CAULDRON, BREWING_STAND -> true;
            default -> false;
        };
    }

    private boolean isMilitaryUtility(Material material) {
        if (material == null) {
            return false;
        }
        // Combat/tactical — Armory (ANVIL) and Command (LECTERN + banner) indicators
        // No overlap with isGeneralUtility
        return switch (material) {
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                    SMITHING_TABLE, GRINDSTONE, FLETCHING_TABLE,
                    LECTERN -> true;
            default -> material.name().endsWith("_BANNER"); // wall and standing banners
        };
    }

    private boolean isDefensiveBlock(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        // endsWith("_WALL")  → matches cobblestone_wall, stone_brick_wall, etc.
        //                      excludes WALL_TORCH, WALL_SIGN, WALL_BANNER (all start with "WALL_")
        // endsWith("_FENCE") → matches oak_fence, nether_brick_fence, etc.
        //                      excludes FENCE_GATE (entrance block, not defensive)
        return name.endsWith("_WALL") || name.endsWith("_FENCE")
                || material == Material.IRON_BARS
                || material == Material.COBWEB
                || name.equals("CHAIN");  // Material.CHAIN added 1.16 — use name to avoid snapshot resolution issues
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
            boolean exposedTerrain,
            double midSectionDensity
    ) {
    }

    private record VariantScore(String variant, double score) {
    }
}
