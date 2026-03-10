package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.flintstqne.entrenched.ConfigManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Evaluates organic Minecraft builds around settlement objective anchors.
 */
public final class BuildingDetector {

    private static final double OUTPOST_REQUIRED_SCORE = 70.0;
    private static final double WATCHTOWER_REQUIRED_SCORE = 65.0;
    private static final double GARRISON_REQUIRED_SCORE = 75.0;

    private final ConfigManager config;

    public BuildingDetector(ConfigManager config) {
        this.config = config;
    }

    public BuildingDetectionResult scan(World world, RegionObjective objective, BuildingType type, String team) {
        if (world == null || objective == null || !objective.hasLocation()) {
            return invalid(type, "Objective is missing an anchor location.");
        }

        int radius = config.getBuildingDetectionRadius();
        int verticalRange = config.getBuildingDetectionVerticalRange();
        int centerX = objective.locationX();
        int centerY = objective.locationY();
        int centerZ = objective.locationZ();

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

        if (relevantBlocks.isEmpty()) {
            return invalid(type, "No qualifying structure blocks found near the objective.");
        }

        List<Set<BlockPos>> components = splitIntoComponents(relevantBlocks.keySet());
        int anchorRadius = Math.max(5, radius / 2);

        BuildingDetectionResult best = invalid(type, "No structure is anchored close enough to the objective.");
        for (Set<BlockPos> component : components) {
            if (!isAnchoredNearObjective(component, centerX, centerY, centerZ, anchorRadius)) {
                continue;
            }

            BuildingDetectionResult result = evaluateComponent(world, objective, type, team, component);
            if (result.totalScore() > best.totalScore()) {
                best = result;
            }
        }

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
        VariantScore variant = detectOutpostVariant(world, objective);

        boolean enoughStructure = stats.structuralBlocks >= 24;
        boolean enoughFootprint = stats.footprint >= 14;
        boolean enoughInterior = interior.interiorCells >= 6;
        boolean roofed = interior.roofCoverage >= 0.60;
        boolean hasChest = stats.storageBlocks >= 1;
        boolean hasCrafting = stats.craftingTables >= 1;
        boolean hasEntrance = interior.hasEntrance || stats.entranceBlocks >= 1;

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

        List<String> failures = new ArrayList<>();
        if (!enoughStructure) failures.add("more structural mass");
        if (!enoughFootprint) failures.add("a wider footprint");
        if (!enoughInterior) failures.add("more usable interior space");
        if (!roofed) failures.add("better roof coverage");
        if (!hasChest) failures.add("a chest");
        if (!hasCrafting) failures.add("a crafting table");
        if (!hasEntrance) failures.add("a real entrance");
        if (total < OUTPOST_REQUIRED_SCORE) failures.add("higher build quality");

        boolean valid = failures.isEmpty();
        String summary = valid
                ? "Valid outpost" + (variant.variant.equals("Standard") ? "" : " (" + variant.variant + ")")
                : "Outpost needs " + String.join(", ", failures) + ".";

        return result(BuildingType.OUTPOST, valid, total, OUTPOST_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                variant.variant, summary, stats.structuralBlocks, stats.footprint, bounds);
    }

    private BuildingDetectionResult scoreWatchtower(World world, RegionObjective objective, Bounds bounds,
                                                    ComponentStats stats) {
        TowerStats tower = analyzeTower(world, objective, stats, bounds);

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

        List<String> failures = new ArrayList<>();
        if (!enoughHeight) failures.add("more height");
        if (!enoughPlatform) failures.add("a larger top platform");
        if (!enoughAccess) failures.add("a climbable route");
        if (!enoughSupport) failures.add("stronger support under the tower");
        if (!enoughOpenness) failures.add("clearer top visibility");
        if (!strongBase) failures.add("a thicker base");
        if (total < WATCHTOWER_REQUIRED_SCORE) failures.add("a more convincing tower shape");

        boolean valid = failures.isEmpty();
        String summary = valid ? "Valid watchtower" : "Watchtower needs " + String.join(", ", failures) + ".";

        return result(BuildingType.WATCHTOWER, valid, total, WATCHTOWER_REQUIRED_SCORE,
                structureScore, interiorScore, accessScore, signatureScore, contextScore,
                "Watchtower", summary, stats.structuralBlocks, stats.footprint, bounds);
    }

    private BuildingDetectionResult scoreGarrison(Bounds bounds, ComponentStats stats,
                                                  InteriorStats interior, String team) {
        VariantScore variant = detectGarrisonVariant(stats);
        int countedBeds = team == null || team.isBlank() ? stats.beds : stats.teamBeds;

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

        List<String> failures = new ArrayList<>();
        if (!enoughStructure) failures.add("more structural mass");
        if (!enoughFloor) failures.add("more floor space");
        if (!enoughInterior) failures.add("better enclosed room space");
        if (!roofed) failures.add("better roof coverage");
        if (!enoughBeds) {
            failures.add(team == null || team.isBlank()
                    ? "at least 3 beds"
                    : "at least 3 " + team.toLowerCase() + " beds");
        }
        if (!hasEntrance) failures.add("a proper entrance");
        if (total < GARRISON_REQUIRED_SCORE) failures.add("more barracks detail");

        boolean valid = failures.isEmpty();
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
                for (int y = bounds.minY + 1; y <= bounds.maxY - 1; y++) {
                    if (!isPassable(world.getBlockAt(x, y, z).getType())
                            || !isPassable(world.getBlockAt(x, y + 1, z).getType())) {
                        continue;
                    }

                    Material floor = world.getBlockAt(x, y - 1, z).getType();
                    if (!isConstructionMaterial(floor)) {
                        continue;
                    }

                    usableCells++;

                    int sideWalls = 0;
                    boolean opening = false;
                    BlockPos pos = new BlockPos(x, y, z);
                    for (BlockPos neighbor : pos.horizontalNeighbors()) {
                        Material side = world.getBlockAt(neighbor.x, neighbor.y, neighbor.z).getType();
                        Material sideHead = world.getBlockAt(neighbor.x, neighbor.y + 1, neighbor.z).getType();
                        boolean blocked = isConstructionMaterial(side) || isEntranceMaterial(side)
                                || isConstructionMaterial(sideHead) || isEntranceMaterial(sideHead);
                        if (blocked) {
                            sideWalls++;
                        } else if (isPassable(side) && isPassable(sideHead)) {
                            opening = true;
                        }
                    }

                    boolean roof = hasRoof(world, x, y, z);
                    if (roof) {
                        roofedCells++;
                    }
                    if (sideWalls >= 2) {
                        shelteredCells++;
                    }
                    if (roof && sideWalls >= 2) {
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
        return new InteriorStats(usableCells, interiorCells, floorArea.size(), roofCoverage, enclosureQuality, openings > 0);
    }

    private TowerStats analyzeTower(World world, RegionObjective objective, ComponentStats stats, Bounds bounds) {
        int objectiveBaseY = objective.locationY() == null ? bounds.minY : objective.locationY();
        int highestWalkableY = Integer.MIN_VALUE;
        Map<Integer, Integer> walkableByY = new HashMap<>();
        double exposedSides = 0.0;
        int exposedCount = 0;
        int skyVisibleCount = 0;

        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (!isConstructionMaterial(current)) {
                        continue;
                    }
                    if (!isPassable(world.getBlockAt(x, y + 1, z).getType())) {
                        continue;
                    }
                    if (y > highestWalkableY) {
                        highestWalkableY = y;
                    }
                    walkableByY.merge(y, 1, Integer::sum);
                }
            }
        }

        int platformSize = highestWalkableY == Integer.MIN_VALUE ? 0 : walkableByY.getOrDefault(highestWalkableY, 0);
        if (highestWalkableY != Integer.MIN_VALUE) {
            for (int x = bounds.minX; x <= bounds.maxX; x++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    Material current = world.getBlockAt(x, highestWalkableY, z).getType();
                    if (!isConstructionMaterial(current) || !isPassable(world.getBlockAt(x, highestWalkableY + 1, z).getType())) {
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
                    if (world.getHighestBlockYAt(x, z) <= highestWalkableY + 1) {
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

        return new TowerStats(height, platformSize, openness, accessCoverage, stats.baseFootprint, supportStrength, skyExposure, exposedTerrain);
    }

    private VariantScore detectOutpostVariant(World world, RegionObjective objective) {
        int x = objective.locationX();
        int y = objective.locationY();
        int z = objective.locationZ();
        int radius = 8;
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

        String biomeName = world.getBiome(x, y, z).name();
        if (water >= 35) return new VariantScore("Fishing Outpost", 5.0);
        if (crops >= 18) return new VariantScore("Farm Outpost", 5.0);
        if (sand >= 30 || biomeName.contains("DESERT") || biomeName.contains("BADLANDS")) {
            return new VariantScore("Desert Outpost", 5.0);
        }
        if (objective.locationY() != null && objective.locationY() >= world.getSeaLevel() + 22) {
            return new VariantScore("Mountain Outpost", 5.0);
        }
        if (ore >= 6 || objective.locationY() != null && objective.locationY() <= world.getSeaLevel() - 10) {
            return new VariantScore("Mining Outpost", 5.0);
        }
        if ((logs + leaves) >= 40 || biomeName.contains("FOREST") || biomeName.contains("TAIGA") || biomeName.contains("JUNGLE")) {
            return new VariantScore("Forest Outpost", 5.0);
        }
        return new VariantScore("Standard", 2.0);
    }

    private VariantScore detectGarrisonVariant(ComponentStats stats) {
        if (stats.militaryUtilityBlocks >= 4) {
            return new VariantScore("Armory Barracks", 10.0);
        }
        if (stats.storageBlocks >= 4) {
            return new VariantScore("Logistics Barracks", 9.0);
        }
        if (stats.utilityBlocks >= 3) {
            return new VariantScore("Support Barracks", 8.0);
        }
        return new VariantScore("Barracks", 5.0);
    }

    private boolean hasRoof(World world, int x, int y, int z) {
        for (int dy = 2; dy <= 3; dy++) {
            Material above = world.getBlockAt(x, y + dy, z).getType();
            if (isConstructionMaterial(above) || isEntranceMaterial(above)) {
                return true;
            }
        }
        return false;
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
