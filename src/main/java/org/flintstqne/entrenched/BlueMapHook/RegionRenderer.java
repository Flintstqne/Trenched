package org.flintstqne.entrenched.BlueMapHook;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector2f;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.HtmlMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RegionRenderer {

    private static final int REGION_CHUNKS = 32;
    private static final int CHUNK_SIZE = 16;
    private static final int REGION_BLOCKS = REGION_CHUNKS * CHUNK_SIZE;
    private static final int GRID_SIZE = 4;
    private static final int MAP_LAYER_Y = 250;

    private static final String MARKER_SET_ID = "major-regions";
    private static final String MARKER_SET_LABEL = "Major Regions";

    private static final long POLL_PERIOD_TICKS = 20L;
    private static final int MAX_POLLS = 60;
    // How often to refresh markers once BlueMap is available (30 seconds)
    private static final long MAP_UPDATE_PERIOD_TICKS = 20L * 30L;

    // Defaults (gray)
    private static final Color DEFAULT_LINE = new Color("#96969666");
    private static final Color DEFAULT_FILL = new Color("#96969633");

    // Team colors
    private static final Color RED_LINE = new Color("#FF000066");
    private static final Color RED_FILL = new Color("#FF000033");
    private static final Color BLUE_LINE = new Color("#0000FF66");
    private static final Color BLUE_FILL = new Color("#0000FF33");


    private final JavaPlugin plugin;
    private final Logger logger;
    private final Map<String, String> regionNames = new HashMap<>();
    private final RoundService roundService;
    private final RegionService regionService;
    // regionId -> regionName

    // Track which round's names are currently loaded into memory
    private Integer loadedRoundId = null;


    public RegionRenderer(JavaPlugin plugin, RoundService roundService, RegionService regionService) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.roundService = roundService;
        this.regionService = regionService;
    }

    /**
     * Loads region names from the database for the current round.
     * Call this on plugin startup if a round already exists.
     * @return true if names were loaded, false if no round or no names found
     */
    public boolean loadNamesForCurrentRound() {
        Optional<Round> currentRoundOpt = roundService.getCurrentRound();
        if (currentRoundOpt.isEmpty()) return false;

        Round round = currentRoundOpt.get();
        int rid = round.roundId();

        try {
            Map<String, String> persistedNames = roundService.getRegionNames(rid);
            if (persistedNames != null && !persistedNames.isEmpty()) {
                regionNames.clear();
                regionNames.putAll(persistedNames);
                loadedRoundId = rid;
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load region names for round " + rid, e);
            return false;
        }
    }

    public Optional<String> getRegionName(String regionId) {
        // If names not loaded yet, try to load them
        if (regionNames.isEmpty()) {
            loadNamesForCurrentRound();
        }
        return Optional.ofNullable(regionNames.get(regionId));
    }

    /**
     * Gets all region names currently loaded.
     * @return Collection of region names
     */
    public Collection<String> getAllRegionNames() {
        return new ArrayList<>(regionNames.values());
    }

    /**
     * Gets the region ID for a given region name.
     * @param regionName The display name of the region
     * @return The region ID (e.g., "A1") or empty if not found
     */
    public Optional<String> getRegionIdByName(String regionName) {
        for (Map.Entry<String, String> entry : regionNames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(regionName)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public Optional<String> getRegionNameForBlock(int blockX, int blockZ) {
        final int halfSize = (GRID_SIZE * REGION_BLOCKS) / 2;

        // Calculate which grid cell the block falls into
        // Grid cells are numbered 0 to GRID_SIZE-1, starting from -halfSize
        int gridX = (blockX + halfSize) / REGION_BLOCKS;
        int gridZ = (blockZ + halfSize) / REGION_BLOCKS;

        // Clamp to valid grid range
        if (gridX < 0 || gridX >= GRID_SIZE || gridZ < 0 || gridZ >= GRID_SIZE) {
            return Optional.empty();
        }

        char rowLabel = (char) ('A' + gridZ);
        String regionId = rowLabel + String.valueOf(gridX + 1);

        return getRegionName(regionId);
    }

    public void scheduleUpdateForOverworld(World world) {
        final int[] polls = {0};
        final BukkitTask[] taskRef = {null};

        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            polls[0]++;

            Optional<BlueMapAPI> apiOpt = BlueMapAPI.getInstance();
            if (apiOpt.isEmpty()) {
                if (polls[0] >= MAX_POLLS) cancelTask(taskRef[0]);
                return;
            }

            BlueMapAPI api = apiOpt.get();
            Optional<BlueMapMap> mapOpt = findMapForWorld(api, world);

            if (mapOpt.isEmpty()) {
                if (polls[0] >= MAX_POLLS) cancelTask(taskRef[0]);
                return;
            }

            cancelTask(taskRef[0]);

            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> refreshMarkers(world),
                    0L, MAP_UPDATE_PERIOD_TICKS);

            refreshMarkers(world);

        }, 0L, POLL_PERIOD_TICKS);
    }



    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) task.cancel();
    }

    private Optional<BlueMapMap> findMapForWorld(BlueMapAPI api, World world) {
        String worldName = world.getName().toLowerCase(Locale.ROOT);

        // Prefer map with id equal to world name, otherwise fall back to any map
        return api.getMaps().stream()
                .filter(m -> m.getId().equalsIgnoreCase(worldName))
                .findFirst()
                .or(() -> api.getMaps().stream().findFirst());
    }

    // New: deterministic generation + persist helper that can be called from commands
    public void generateAndPersistNamesForCurrentRound(World world) {
        if (world == null) return;

        Optional<Round> currentRoundOpt = roundService.getCurrentRound();

        // Clear in-memory names if round changed
        if (currentRoundOpt.isPresent()) {
            int rid = currentRoundOpt.get().roundId();
            if (loadedRoundId == null || !loadedRoundId.equals(rid)) {
                regionNames.clear();
                loadedRoundId = rid;

                try {
                    Map<String, String> persistedNames = roundService.getRegionNames(rid);
                    if (persistedNames != null && !persistedNames.isEmpty()) {
                        regionNames.putAll(persistedNames);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to load persisted region names for round " + rid, e);
                }
            }
        }

        // Collect already used names (from loaded or in-memory)
        Set<String> usedNames = new HashSet<>(regionNames.values());

        char rowLabel = 'A';
        boolean anyGenerated = false;

        for (int z = 0; z < GRID_SIZE; z++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                String regionId = rowLabel + String.valueOf(x + 1);
                if (regionNames.containsKey(regionId)) continue;

                String regionName;
                do {
                    regionName = RegionNameGenerator.generateRegionName();
                } while (usedNames.contains(regionName));
                usedNames.add(regionName);

                regionNames.put(regionId, regionName);
                anyGenerated = true;
            }

            rowLabel++;
        }

        if (anyGenerated && currentRoundOpt.isPresent()) {
            Round r = currentRoundOpt.get();
            try {
                roundService.setRegionNames(r.roundId(), new HashMap<>(regionNames));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to persist region names for round " + r.roundId(), e);
            }
        }
    }

    /**
     * Force-refresh BlueMap markers for the given world using the currently-loaded region names.
     * Region names must be loaded or generated before calling this method (via
     * {@link #loadNamesForCurrentRound()} or {@link #generateAndPersistNamesForCurrentRound(World)}).
     */
    public void refreshMarkers(World world) {
        if (world == null) return;

        Optional<BlueMapAPI> apiOpt = BlueMapAPI.getInstance();
        if (apiOpt.isEmpty()) return;

        Optional<BlueMapMap> mapOpt = findMapForWorld(apiOpt.get(), world);
        if (mapOpt.isEmpty()) return;

        BlueMapMap map = mapOpt.get();

        MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(
                MARKER_SET_ID,
                id -> MarkerSet.builder()
                        .label(MARKER_SET_LABEL)
                        .toggleable(true)
                        .defaultHidden(false)
                        .build()
        );

        Map<String, Marker> markers = markerSet.getMarkers();
        markers.clear();

        Location redSpawn = MapUtils.hardcodedTeamSpawn(world, MapUtils.TeamId.RED);
        Location blueSpawn = MapUtils.hardcodedTeamSpawn(world, MapUtils.TeamId.BLUE);

        MapUtils.RegionKey redRegion = MapUtils.regionKeyForBlock(redSpawn.getBlockX(), redSpawn.getBlockZ());
        MapUtils.RegionKey blueRegion = MapUtils.regionKeyForBlock(blueSpawn.getBlockX(), blueSpawn.getBlockZ());

        final int totalSize = GRID_SIZE * REGION_BLOCKS;
        final int halfSize = totalSize / 2;

        char rowLabel = 'A';

        for (int z = 0; z < GRID_SIZE; z++) {
            for (int x = 0; x < GRID_SIZE; x++) {
                int x0 = x * REGION_BLOCKS - halfSize;
                int z0 = z * REGION_BLOCKS - halfSize;
                int x1 = x0 + REGION_BLOCKS;
                int z1 = z0 + REGION_BLOCKS;

                int centerX = x0 + REGION_BLOCKS / 2;
                int centerZ = z0 + REGION_BLOCKS / 2;
                int y = MAP_LAYER_Y;

                String regionId = rowLabel + String.valueOf(x + 1);
                String regionName = regionNames.get(regionId);

                if (regionName == null) {
                    continue; // names must be loaded before rendering
                }

                MapUtils.RegionKey cellKey = MapUtils.regionKeyForBlock(centerX, centerZ);

                Color lineColor = DEFAULT_LINE;
                Color fillColor = DEFAULT_FILL;

                if (cellKey.equals(redRegion)) {
                    lineColor = RED_LINE;
                    fillColor = RED_FILL;
                } else if (cellKey.equals(blueRegion)) {
                    lineColor = BLUE_LINE;
                    fillColor = BLUE_FILL;
                } else if (regionService != null) {
                    Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
                    if (statusOpt.isPresent()) {
                        String owner = statusOpt.get().ownerTeam();
                        if ("red".equalsIgnoreCase(owner)) {
                            lineColor = RED_LINE;
                            fillColor = RED_FILL;
                        } else if ("blue".equalsIgnoreCase(owner)) {
                            lineColor = BLUE_LINE;
                            fillColor = BLUE_FILL;
                        }
                    }
                }

                Shape shape = new Shape(List.of(
                        new Vector2d(x0, z0),
                        new Vector2d(x1, z0),
                        new Vector2d(x1, z1),
                        new Vector2d(x0, z1)
                ));

                ShapeMarker areaMarker = ShapeMarker.builder()
                        .label(regionName)
                        .shape(shape, (float) y)
                        .lineColor(lineColor)
                        .lineWidth(3)
                        .fillColor(fillColor)
                        .depthTestEnabled(false)
                        .build();

                String html =
                        "<div style=\""
                                + "transform: translate(-50%, -50%);"
                                + "font-size: 1.0em;"
                                + "font-weight: bold;"
                                + "color: #ffffffcc;"
                                + "text-shadow: 0 0 6px #000000;"
                                + "pointer-events: none;"
                                + "white-space: nowrap;"
                                + "\">"
                                + regionName
                                + "</div>";

                HtmlMarker labelMarker = HtmlMarker.builder()
                        .label(regionName)
                        .position(new Vector3d(centerX, y + 6, centerZ))
                        .html(html)
                        .anchor(new Vector2f(0, 0).toInt())
                        .listed(false)
                        .minDistance(10)
                        .maxDistance(10_000_000)
                        .build();

                markers.put("arena.region.area." + regionId, areaMarker);
                markers.put("arena.region.label." + regionId, labelMarker);
            }

            rowLabel++;
        }
    }
}
