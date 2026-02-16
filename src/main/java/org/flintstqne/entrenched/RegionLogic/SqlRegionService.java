package org.flintstqne.entrenched.RegionLogic;

import org.bukkit.Bukkit;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RoadLogic.RoadService;
import org.flintstqne.entrenched.RoadLogic.SupplyLevel;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;

import java.util.*;
import java.util.logging.Logger;

/**
 * SQL-backed implementation of RegionService.
 */
public final class SqlRegionService implements RegionService {

    private static final int GRID_SIZE = 4;
    private static final int REGION_BLOCKS = 512;
    private static final int HALF_SIZE = (GRID_SIZE * REGION_BLOCKS) / 2;

    private final RegionDb db;
    private final RoundService roundService;
    private final ConfigManager configManager;
    private final Logger logger;

    // Road service for supply calculations (optional, set after construction)
    private RoadService roadService;

    // Cache for region statuses (refreshed periodically)
    private final Map<String, RegionStatus> regionCache = new HashMap<>();
    private long lastCacheRefresh = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 seconds

    // Capture callback for notifications
    private CaptureCallback captureCallback;

    /**
     * Callback interface for capture events.
     */
    @FunctionalInterface
    public interface CaptureCallback {
        void onRegionCaptured(String regionId, String newOwner, String previousOwner);
    }

    public SqlRegionService(RegionDb db, RoundService roundService, ConfigManager configManager) {
        this.db = db;
        this.roundService = roundService;
        this.configManager = configManager;
        this.logger = Bukkit.getLogger();
    }

    /**
     * Sets the capture callback for notifications.
     */
    public void setCaptureCallback(CaptureCallback callback) {
        this.captureCallback = callback;
    }

    /**
     * Sets the road service for supply calculations.
     * Called after construction to avoid circular dependency.
     */
    public void setRoadService(RoadService roadService) {
        this.roadService = roadService;
    }

    private Optional<Round> getCurrentRound() {
        return roundService.getCurrentRound();
    }

    private int getCurrentRoundId() {
        return getCurrentRound().map(Round::roundId).orElse(-1);
    }

    private void log(String message) {
        logger.info("[RegionService] " + message);
    }

    // ==================== INITIALIZATION ====================

    @Override
    public void initializeRegionsForRound(int roundId, String redHome, String blueHome) {
        log("Initializing regions for round " + roundId);

        // Initialize all 16 regions (A1-D4)
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                char rowLabel = (char) ('A' + row);
                String regionId = rowLabel + String.valueOf(col + 1);

                String owner = null;
                RegionState state = RegionState.NEUTRAL;

                if (regionId.equals(redHome)) {
                    owner = "red";
                    state = RegionState.PROTECTED;
                    log("  " + regionId + " = RED HOME (PROTECTED)");
                } else if (regionId.equals(blueHome)) {
                    owner = "blue";
                    state = RegionState.PROTECTED;
                    log("  " + regionId + " = BLUE HOME (PROTECTED)");
                } else {
                    log("  " + regionId + " = NEUTRAL");
                }

                db.initializeRegion(regionId, roundId, owner, state);
            }
        }

        refreshCache();
        log("Region initialization complete");
    }

    // ==================== REGION QUERIES ====================

    @Override
    public Optional<RegionStatus> getRegionStatus(String regionId) {
        refreshCacheIfNeeded();
        RegionStatus cached = regionCache.get(regionId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Fallback: query database directly if cache miss
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Optional.empty();

        Optional<RegionStatus> fromDb = db.getRegionStatus(regionId, roundId);
        // Update cache with the result
        fromDb.ifPresent(status -> regionCache.put(regionId, status));
        return fromDb;
    }

    @Override
    public List<RegionStatus> getAllRegionStatuses() {
        refreshCacheIfNeeded();
        return new ArrayList<>(regionCache.values());
    }

    @Override
    public List<RegionStatus> getRegionsByOwner(String team) {
        refreshCacheIfNeeded();
        return regionCache.values().stream()
                .filter(r -> r.isOwnedBy(team))
                .toList();
    }

    @Override
    public String getRegionIdForLocation(int blockX, int blockZ) {
        int gridX = (blockX + HALF_SIZE) / REGION_BLOCKS;
        int gridZ = (blockZ + HALF_SIZE) / REGION_BLOCKS;

        if (gridX < 0 || gridX >= GRID_SIZE || gridZ < 0 || gridZ >= GRID_SIZE) {
            return null;
        }

        char rowLabel = (char) ('A' + gridZ);
        return rowLabel + String.valueOf(gridX + 1);
    }

    @Override
    public int countRegionsOwned(String team) {
        return (int) getRegionsByOwner(team).size();
    }

    // ==================== INFLUENCE OPERATIONS ====================

    @Override
    public InfluenceResult addInfluence(UUID playerUuid, String regionId, String team, InfluenceAction action, double multiplier) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return InfluenceResult.NO_ACTIVE_ROUND;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return InfluenceResult.REGION_NOT_FOUND;

        RegionStatus status = statusOpt.get();

        // Check if region can be influenced
        if (status.isFortified()) return InfluenceResult.REGION_FORTIFIED;
        if (status.state() == RegionState.PROTECTED && status.isOwnedBy(team)) {
            return InfluenceResult.REGION_PROTECTED; // Can't gain influence in own protected region
        }

        // Validate action type for region state
        if (status.state() == RegionState.NEUTRAL && action.isEnemyRegionOnly()) {
            return InfluenceResult.INVALID_ACTION;
        }
        if (status.isOwnedBy(team)) {
            return InfluenceResult.INVALID_ACTION; // Can't gain influence in own region
        }

        // Check adjacency requirement - must have at least one adjacent friendly region
        if (!isAdjacentToTeam(regionId, team)) {
            return InfluenceResult.NOT_ADJACENT;
        }

        // Check rate limiting for certain actions
        if (isRateLimited(playerUuid, regionId, roundId, action)) {
            return InfluenceResult.RATE_LIMITED;
        }

        // Calculate points
        double points = getPointsForAction(action) * multiplier;
        if (points <= 0) return InfluenceResult.SUCCESS; // No points but not an error

        // Add influence
        double currentInfluence = status.getInfluence(team);
        double newInfluence = currentInfluence + points;

        // Update database
        if ("red".equalsIgnoreCase(team)) {
            db.updateInfluence(regionId, roundId, newInfluence, status.blueInfluence());
        } else {
            db.updateInfluence(regionId, roundId, status.redInfluence(), newInfluence);
        }

        // Track player stats
        db.addInfluenceEarned(playerUuid.toString(), regionId, roundId, points);

        // Update rate limit tracking
        updateRateLimit(playerUuid, regionId, roundId, action);

        // Update state if needed
        updateRegionState(regionId, status, team);

        // Check for capture
        checkAndProcessCapture(regionId, team);

        // Invalidate cache
        invalidateCache(regionId);

        return InfluenceResult.SUCCESS;
    }

    private double getPointsForAction(InfluenceAction action) {
        return switch (action) {
            case KILL_ENEMY, KILL_ENEMY_REPEAT -> configManager.getRegionKillPoints();
            case PLACE_BANNER -> configManager.getRegionBannerPlacePoints();
            case REMOVE_ENEMY_BANNER -> configManager.getRegionBannerRemovePoints();
            case MINE_ENEMY_BLOCK -> configManager.getRegionMineEnemyBlocksPoints();
            case PLACE_DEFENSIVE_BLOCK -> configManager.getRegionDefensiveBlockPoints();
            case PLACE_WORKSTATION -> configManager.getRegionWorkstationPoints();
            case PLACE_TORCH -> configManager.getRegionTorchPoints();
            case KILL_MOB -> configManager.getRegionMobKillPoints();
            default -> action.getDefaultPoints();
        };
    }

    private boolean isRateLimited(UUID playerUuid, String regionId, int roundId, InfluenceAction action) {
        // Rate limit block mining actions (per second)
        if (action == InfluenceAction.MINE_ENEMY_BLOCK) {
            int cap = configManager.getRegionMineCapPerSecond();
            int count = db.getActionCount(playerUuid.toString(), regionId, roundId, "mine", 1000);
            return count >= cap;
        }
        // Rate limit defensive blocks (per second)
        if (action == InfluenceAction.PLACE_DEFENSIVE_BLOCK) {
            int cap = configManager.getRegionDefensiveCapPerSecond();
            int count = db.getActionCount(playerUuid.toString(), regionId, roundId, "defensive", 1000);
            return count >= cap;
        }
        // Rate limit workstations (per minute) - prevents crafting table spam
        if (action == InfluenceAction.PLACE_WORKSTATION) {
            int cap = configManager.getRegionWorkstationCapPerMinute();
            int count = db.getActionCount(playerUuid.toString(), regionId, roundId, "workstation", 60000);
            return count >= cap;
        }
        // Rate limit torches (per minute)
        if (action == InfluenceAction.PLACE_TORCH) {
            int cap = configManager.getRegionTorchCapPerMinute();
            int count = db.getActionCount(playerUuid.toString(), regionId, roundId, "torch", 60000);
            return count >= cap;
        }
        return false;
    }

    private void updateRateLimit(UUID playerUuid, String regionId, int roundId, InfluenceAction action) {
        String actionType = switch (action) {
            case MINE_ENEMY_BLOCK -> "mine";
            case PLACE_DEFENSIVE_BLOCK -> "defensive";
            case PLACE_WORKSTATION -> "workstation";
            case PLACE_TORCH -> "torch";
            default -> null;
        };

        if (actionType != null) {
            // Use appropriate time window (seconds vs minutes)
            int windowMs = (action == InfluenceAction.PLACE_WORKSTATION || action == InfluenceAction.PLACE_TORCH)
                    ? 60000 : 1000;
            db.incrementActionCount(playerUuid.toString(), regionId, roundId, actionType, windowMs);
        }
    }

    private void updateRegionState(String regionId, RegionStatus status, String attackingTeam) {
        int roundId = getCurrentRoundId();

        // If neutral region has any influence, it stays neutral until captured
        if (status.state() == RegionState.NEUTRAL) {
            return;
        }

        // If owned region is being attacked, mark as contested
        if (status.state() == RegionState.OWNED && !status.isOwnedBy(attackingTeam)) {
            double attackerInfluence = status.getInfluence(attackingTeam);
            if (attackerInfluence > 0) {
                db.updateRegionState(regionId, roundId, RegionState.CONTESTED);
            }
        }
    }

    @Override
    public double getInfluence(String regionId, String team) {
        return getRegionStatus(regionId)
                .map(s -> s.getInfluence(team))
                .orElse(0.0);
    }

    @Override
    public double getInfluenceRequired(String regionId, String capturingTeam) {
        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return Double.MAX_VALUE;

        RegionStatus status = statusOpt.get();

        double neutralThreshold = configManager.getRegionNeutralCaptureThreshold();
        double enemyThreshold = configManager.getRegionEnemyCaptureThreshold();

        return status.getInfluenceRequired(neutralThreshold, enemyThreshold);
    }

    @Override
    public double getKillMultiplier(UUID killerUuid, UUID victimUuid, String regionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return 1.0;

        int killCount = db.getKillCount(killerUuid.toString(), victimUuid.toString(), regionId, roundId);
        if (killCount == 0) return 1.0;

        // Each subsequent kill of same player reduces points by 50%
        double reduction = configManager.getRegionKillSamePlayerReduction();
        return Math.pow(reduction, killCount);
    }

    // ==================== CAPTURE OPERATIONS ====================

    private void checkAndProcessCapture(String regionId, String team) {
        double influence = getInfluence(regionId, team);
        double required = getInfluenceRequired(regionId, team);

        if (influence >= required) {
            captureRegion(regionId, team);
        }
    }

    @Override
    public CaptureResult captureRegion(String regionId, String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return CaptureResult.REGION_NOT_FOUND;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return CaptureResult.REGION_NOT_FOUND;

        RegionStatus status = statusOpt.get();

        // Validation checks
        if (status.isFortified()) return CaptureResult.REGION_FORTIFIED;
        if (status.state() == RegionState.PROTECTED) return CaptureResult.REGION_PROTECTED;
        if (status.isOwnedBy(team)) return CaptureResult.ALREADY_OWNED;
        if (!canAttackRegion(regionId, team)) return CaptureResult.NOT_ADJACENT;

        // Store previous owner for notification
        String previousOwner = status.ownerTeam();

        // Calculate fortification end time
        long fortificationMinutes = configManager.getRegionFortificationMinutes();
        Long fortifiedUntil = System.currentTimeMillis() + (fortificationMinutes * 60 * 1000);

        // Perform capture
        db.captureRegion(regionId, roundId, team, RegionState.FORTIFIED, fortifiedUntil);
        invalidateCache(regionId);

        log("Region " + regionId + " captured by " + team + "! Fortified for " + fortificationMinutes + " minutes.");

        // Notify via callback
        if (captureCallback != null) {
            captureCallback.onRegionCaptured(regionId, team, previousOwner);
        }

        return CaptureResult.SUCCESS;
    }

    @Override
    public boolean canAttackRegion(String regionId, String team) {
        // Check adjacency rules
        if (!configManager.isRegionStrictAdjacency()) {
            return true; // No adjacency rules
        }

        return isAdjacentToTeam(regionId, team);
    }

    @Override
    public List<String> getAdjacentRegions(String regionId) {
        List<String> adjacent = new ArrayList<>();
        if (regionId == null || regionId.length() < 2) return adjacent;

        char row = regionId.charAt(0);
        int col = Integer.parseInt(regionId.substring(1));

        // North (row - 1)
        if (row > 'A') {
            adjacent.add(String.valueOf((char)(row - 1)) + col);
        }
        // South (row + 1)
        if (row < 'D') {
            adjacent.add(String.valueOf((char)(row + 1)) + col);
        }
        // West (col - 1)
        if (col > 1) {
            adjacent.add(String.valueOf(row) + (col - 1));
        }
        // East (col + 1)
        if (col < 4) {
            adjacent.add(String.valueOf(row) + (col + 1));
        }

        // Diagonal adjacency (if allowed)
        if (configManager.isRegionAllowDiagonal()) {
            if (row > 'A' && col > 1) adjacent.add(String.valueOf((char)(row - 1)) + (col - 1));
            if (row > 'A' && col < 4) adjacent.add(String.valueOf((char)(row - 1)) + (col + 1));
            if (row < 'D' && col > 1) adjacent.add(String.valueOf((char)(row + 1)) + (col - 1));
            if (row < 'D' && col < 4) adjacent.add(String.valueOf((char)(row + 1)) + (col + 1));
        }

        return adjacent;
    }

    @Override
    public boolean isAdjacentToTeam(String regionId, String team) {
        for (String adjacentId : getAdjacentRegions(regionId)) {
            Optional<RegionStatus> adjacentStatus = getRegionStatus(adjacentId);
            if (adjacentStatus.isPresent() && adjacentStatus.get().isOwnedBy(team)) {
                return true;
            }
        }
        return false;
    }

    // ==================== ADMIN OPERATIONS ====================

    @Override
    public void captureRegion(String regionId, String team, long fortifyUntil) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        db.captureRegion(regionId, roundId, team, RegionState.FORTIFIED, fortifyUntil);
        invalidateCache(regionId);
    }

    @Override
    public void resetRegion(String regionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        db.updateRegionOwner(regionId, roundId, null);
        db.updateRegionState(regionId, roundId, RegionState.NEUTRAL);
        db.updateInfluence(regionId, roundId, 0, 0);
        invalidateCache(regionId);
    }

    @Override
    public void setRegionState(String regionId, RegionState state) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        db.updateRegionState(regionId, roundId, state);
        invalidateCache(regionId);
    }

    @Override
    public void addInfluence(String regionId, String team, double amount, UUID playerUuid) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();
        double newRed = status.redInfluence();
        double newBlue = status.blueInfluence();

        if ("red".equalsIgnoreCase(team)) {
            newRed += amount;
        } else if ("blue".equalsIgnoreCase(team)) {
            newBlue += amount;
        }

        db.updateInfluence(regionId, roundId, newRed, newBlue);
        invalidateCache(regionId);

        // Track player stats if provided
        if (playerUuid != null) {
            db.addInfluenceEarned(playerUuid.toString(), regionId, roundId, amount);
        }
    }

    @Override
    public void initializeRegions(String redHome, String blueHome) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;
        initializeRegionsForRound(roundId, redHome, blueHome);
    }

    // ==================== SUPPLY LINE OPERATIONS ====================
    // NOTE: These are temporary implementations. The full physical road system
    // will track actual path blocks placed by players.

    @Override
    public double getSupplyEfficiency(String regionId, String team) {
        // Use RoadService if available for actual road-based supply
        if (roadService != null) {
            SupplyLevel level = roadService.getSupplyLevel(regionId, team);
            return switch (level) {
                case SUPPLIED -> 1.0;
                case PARTIAL -> 0.5;
                case UNSUPPLIED -> 0.25;
                case ISOLATED -> 0.0;
            };
        }

        // Fallback: simple region adjacency as a placeholder
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return 0;
        if (regionId.equals(homeRegion)) return 1.0; // Home is always 100%

        // Check if connected via owned regions (placeholder for road system)
        int distance = findShortestPath(regionId, homeRegion, team);
        if (distance < 0) return 0; // Not connected = Unsupplied

        // Simple: if connected, return 1.0 (Supplied)
        // The physical road system will provide more nuanced supply levels
        return 1.0;
    }

    @Override
    public boolean isConnectedToHome(String regionId, String team) {
        // TODO: Replace with physical road connectivity check
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null) return false;
        if (regionId.equals(homeRegion)) return true;
        return findShortestPath(regionId, homeRegion, team) >= 0;
    }

    private String getHomeRegion(String team) {
        if ("red".equalsIgnoreCase(team)) {
            return configManager.getRegionRedHome();
        } else if ("blue".equalsIgnoreCase(team)) {
            return configManager.getRegionBlueHome();
        }
        return null;
    }

    private int findShortestPath(String from, String to, String team) {
        if (from.equals(to)) return 0;

        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> distances = new HashMap<>();

        queue.add(from);
        distances.put(from, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDist = distances.get(current);

            for (String adjacent : getAdjacentRegions(current)) {
                if (distances.containsKey(adjacent)) continue;

                Optional<RegionStatus> adjacentStatus = getRegionStatus(adjacent);
                if (adjacentStatus.isEmpty()) continue;

                // Can only traverse through owned regions
                if (!adjacentStatus.get().isOwnedBy(team)) continue;

                if (adjacent.equals(to)) {
                    return currentDist + 1;
                }

                distances.put(adjacent, currentDist + 1);
                queue.add(adjacent);
            }
        }

        return -1; // Not connected
    }

    @Override
    public List<String> getRegionsThatWouldBeCutOff(String regionId, String team) {
        // Temporarily remove region from consideration
        List<String> cutOffRegions = new ArrayList<>();
        String homeRegion = getHomeRegion(team);

        for (RegionStatus status : getRegionsByOwner(team)) {
            if (status.regionId().equals(regionId)) continue;
            if (status.regionId().equals(homeRegion)) continue;

            // Check if this region would still be connected without the target region
            if (!wouldBeConnectedWithout(status.regionId(), regionId, team)) {
                cutOffRegions.add(status.regionId());
            }
        }

        return cutOffRegions;
    }

    private boolean wouldBeConnectedWithout(String regionId, String excludeRegion, String team) {
        String homeRegion = getHomeRegion(team);
        if (homeRegion == null || regionId.equals(homeRegion)) return true;

        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        queue.add(regionId);
        visited.add(regionId);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (String adjacent : getAdjacentRegions(current)) {
                if (visited.contains(adjacent)) continue;
                if (adjacent.equals(excludeRegion)) continue;

                Optional<RegionStatus> adjacentStatus = getRegionStatus(adjacent);
                if (adjacentStatus.isEmpty()) continue;
                if (!adjacentStatus.get().isOwnedBy(team)) continue;

                if (adjacent.equals(homeRegion)) {
                    return true;
                }

                visited.add(adjacent);
                queue.add(adjacent);
            }
        }

        return false;
    }

    // ==================== DEFENSE OPERATIONS ====================

    @Override
    public double getDefenseBonus(String regionId) {
        // Defense bonuses removed - only fortification period provides protection
        // This method kept for interface compatibility
        return 0;
    }

    @Override
    public void updateDefenseStructures(String regionId, int count) {
        // Defense structures removed - this method kept for interface compatibility
    }

    // ==================== DECAY ====================

    @Override
    public void applyInfluenceDecay() {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        double decayPerMinute = configManager.getRegionInfluenceDecayPerMinute();

        for (RegionStatus status : getAllRegionStatuses()) {
            if (status.state() == RegionState.CONTESTED) {
                double newRed = Math.max(0, status.redInfluence() - decayPerMinute);
                double newBlue = Math.max(0, status.blueInfluence() - decayPerMinute);
                db.updateInfluence(status.regionId(), roundId, newRed, newBlue);

                // If both influences are 0, return to OWNED state
                if (newRed == 0 && newBlue == 0 && status.ownerTeam() != null) {
                    db.updateRegionState(status.regionId(), roundId, RegionState.OWNED);
                }
            }
        }

        refreshCache();
    }

    @Override
    public void updateFortificationStatus() {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        long now = System.currentTimeMillis();

        for (RegionStatus status : getAllRegionStatuses()) {
            if (status.state() == RegionState.FORTIFIED && status.fortifiedUntil() != null) {
                if (now >= status.fortifiedUntil()) {
                    db.updateRegionState(status.regionId(), roundId, RegionState.OWNED);
                    log("Region " + status.regionId() + " fortification ended.");
                }
            }
        }

        refreshCache();
    }

    // ==================== EVENT HANDLERS ====================

    @Override
    public void onPlayerKill(UUID killerUuid, UUID victimUuid, String killerTeam, String victimTeam, int blockX, int blockZ) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        // Only award points for killing enemies
        if (killerTeam.equalsIgnoreCase(victimTeam)) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // Can only earn kill points in enemy or neutral regions
        if (status.isOwnedBy(killerTeam)) return;

        // Calculate multiplier based on repeat kills
        double multiplier = getKillMultiplier(killerUuid, victimUuid, regionId);

        // Record the kill for future multiplier calculations
        db.recordKill(killerUuid.toString(), victimUuid.toString(), regionId, roundId);
        db.incrementPlayerStat(killerUuid.toString(), regionId, roundId, "kills");
        db.incrementPlayerStat(victimUuid.toString(), regionId, roundId, "deaths");

        // Add influence
        InfluenceAction action = multiplier < 1.0 ? InfluenceAction.KILL_ENEMY_REPEAT : InfluenceAction.KILL_ENEMY;
        addInfluence(killerUuid, regionId, killerTeam, action, multiplier);
    }

    @Override
    public void onBlockPlace(UUID playerUuid, String team, int blockX, int blockY, int blockZ, String blockType) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // Only earn points in neutral or enemy regions
        if (status.isOwnedBy(team)) return;

        // Track stats
        db.incrementPlayerStat(playerUuid.toString(), regionId, roundId, "blocks_placed");

        // Determine action type
        InfluenceAction action = null;
        if (status.state() == RegionState.NEUTRAL) {
            if (isDefensiveBlock(blockType)) {
                action = InfluenceAction.PLACE_DEFENSIVE_BLOCK;
            } else if (isTorch(blockType)) {
                action = InfluenceAction.PLACE_TORCH;
            } else if (isWorkstation(blockType)) {
                action = InfluenceAction.PLACE_WORKSTATION;
            }
        }

        if (action != null) {
            addInfluence(playerUuid, regionId, team, action);
        }
    }

    @Override
    public void onBlockBreak(UUID playerUuid, String team, int blockX, int blockY, int blockZ, boolean wasPlayerPlaced, String placedByTeam) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // Track stats
        db.incrementPlayerStat(playerUuid.toString(), regionId, roundId, "blocks_mined");

        // Only earn points for mining enemy blocks in enemy regions
        if (wasPlayerPlaced && placedByTeam != null && !placedByTeam.equalsIgnoreCase(team)) {
            if (!status.isOwnedBy(team) && status.state() != RegionState.NEUTRAL) {
                addInfluence(playerUuid, regionId, team, InfluenceAction.MINE_ENEMY_BLOCK);
            }
        }
    }

    @Override
    public void onBannerPlace(UUID playerUuid, String team, int blockX, int blockZ) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // Can place banner in neutral or enemy regions
        if (status.isOwnedBy(team)) return;

        // Track stats
        db.incrementPlayerStat(playerUuid.toString(), regionId, roundId, "banners_placed");

        addInfluence(playerUuid, regionId, team, InfluenceAction.PLACE_BANNER);
    }

    @Override
    public void onBannerRemove(UUID playerUuid, String team, int blockX, int blockZ, String bannerTeam) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        // Only award points for removing enemy banners
        if (team.equalsIgnoreCase(bannerTeam)) return;

        addInfluence(playerUuid, regionId, team, InfluenceAction.REMOVE_ENEMY_BANNER);
    }

    @Override
    public void onMobKill(UUID playerUuid, String team, int blockX, int blockZ) {
        String regionId = getRegionIdForLocation(blockX, blockZ);
        if (regionId == null) return;

        Optional<RegionStatus> statusOpt = getRegionStatus(regionId);
        if (statusOpt.isEmpty()) return;

        RegionStatus status = statusOpt.get();

        // Only earn points in neutral regions
        if (status.state() != RegionState.NEUTRAL) return;

        addInfluence(playerUuid, regionId, team, InfluenceAction.KILL_MOB);
    }

    // ==================== HELPER METHODS ====================

    private boolean isDefensiveBlock(String blockType) {
        return blockType.contains("WALL") || blockType.contains("FENCE") ||
               blockType.contains("COBBLESTONE") || blockType.contains("STONE_BRICK") ||
               blockType.contains("BRICK") || blockType.contains("OBSIDIAN");
    }

    private boolean isTorch(String blockType) {
        return blockType.contains("TORCH");
    }

    private boolean isWorkstation(String blockType) {
        return blockType.contains("CRAFTING") || blockType.contains("FURNACE") ||
               blockType.contains("ANVIL") || blockType.contains("SMITHING") ||
               blockType.contains("ENCHANTING") || blockType.contains("BREWING");
    }

    // ==================== CACHE MANAGEMENT ====================

    private void refreshCacheIfNeeded() {
        if (System.currentTimeMillis() - lastCacheRefresh > CACHE_TTL_MS) {
            refreshCache();
        }
    }

    private void refreshCache() {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        regionCache.clear();
        for (RegionStatus status : db.getAllRegionStatuses(roundId)) {
            regionCache.put(status.regionId(), status);
        }
        lastCacheRefresh = System.currentTimeMillis();
    }

    private void invalidateCache(String regionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        db.getRegionStatus(regionId, roundId).ifPresent(status ->
            regionCache.put(regionId, status)
        );
    }
}

