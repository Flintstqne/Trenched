package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.RoundLogic.Round;
import org.flintstqne.entrenched.RoundLogic.RoundService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * SQL-backed implementation of the Division Depot service.
 */
public class SqlDepotService implements DepotService {

    private final JavaPlugin plugin;
    private final DivisionDb db;
    private final DivisionService divisionService;
    private final RegionService regionService;
    private final TeamService teamService;
    private final RoundService roundService;
    private final ConfigManager configManager;
    private final DepotItem depotItem;

    // NBT keys for depot items (also available via DepotItem)
    private final NamespacedKey depotTypeKey;
    private final NamespacedKey divisionIdKey;
    private final NamespacedKey teamKey;
    private final NamespacedKey raidToolKey;

    // Active raids: raider UUID -> depot location key
    private final Map<UUID, String> activeRaids = new ConcurrentHashMap<>();
    // Depot location key -> raider UUID
    private final Map<String, UUID> depotRaiders = new ConcurrentHashMap<>();

    // Cache of open depot inventories: player UUID -> division ID
    private final Map<UUID, Integer> openDepotInventories = new ConcurrentHashMap<>();

    // Track last raid drop count per player (for message display)
    private final Map<UUID, Integer> lastRaidDropCount = new ConcurrentHashMap<>();

    // Storage size (54 = double chest)
    private static final int STORAGE_SIZE = 54;
    private static final String DEPOT_INVENTORY_TITLE = "Division Depot";

    public SqlDepotService(JavaPlugin plugin, DivisionDb db, DivisionService divisionService,
                           RegionService regionService, TeamService teamService,
                           RoundService roundService, ConfigManager configManager) {
        this.plugin = plugin;
        this.db = db;
        this.divisionService = divisionService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.roundService = roundService;
        this.configManager = configManager;

        // Initialize DepotItem factory
        this.depotItem = new DepotItem(plugin);

        // Initialize NBT keys (use same keys as DepotItem for consistency)
        this.depotTypeKey = depotItem.getDepotTypeKey();
        this.divisionIdKey = depotItem.getDivisionIdKey();
        this.teamKey = depotItem.getTeamKey();
        this.raidToolKey = depotItem.getRaidToolKey();
    }

    /**
     * Gets the DepotItem factory for creating depot blocks and raid tools.
     */
    public DepotItem getDepotItemFactory() {
        return depotItem;
    }

    // ==================== Configuration Helpers ====================

    @Override
    public boolean isEnabled() {
        return configManager.isDepotSystemEnabled();
    }

    @Override
    public int getMaxDepotsPerDivision() {
        return configManager.getDepotMaxPerDivision();
    }

    @Override
    public int getMinDistanceBetweenDepots() {
        return configManager.getDepotMinDistance();
    }

    @Override
    public int getRaidChannelSeconds() {
        return configManager.getDepotRaidChannelSeconds();
    }

    @Override
    public double getLootDropPercentage() {
        return configManager.getDepotLootDropPercentage();
    }

    private int getMinItemsDropped() {
        return configManager.getDepotMinItemsDropped();
    }

    private int getMaxItemsDropped() {
        return configManager.getDepotMaxItemsDropped();
    }

    private int getRaidCooldownMinutes() {
        return configManager.getDepotRaidCooldownMinutes();
    }

    private boolean allowInContested() {
        return configManager.isDepotAllowInContested();
    }

    private boolean allowInNeutral() {
        return configManager.isDepotAllowInNeutral();
    }

    private boolean allowInEnemy() {
        return configManager.isDepotAllowInEnemy();
    }

    private int getCurrentRoundId() {
        return roundService.getCurrentRound().map(Round::roundId).orElse(-1);
    }

    // ==================== Depot Block Management ====================

    @Override
    public PlaceResult placeDepot(Player player, Location location) {
        if (!isEnabled()) {
            return PlaceResult.NOT_ENABLED;
        }

        // Check if player has a division
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divisionOpt.isEmpty()) {
            return PlaceResult.NO_DIVISION;
        }

        Division division = divisionOpt.get();

        // Check if player is an officer or commander in their division
        Optional<DivisionMember> memberOpt = divisionService.getMembership(player.getUniqueId());
        if (memberOpt.isEmpty()) {
            return PlaceResult.NO_DIVISION;
        }

        DivisionRole role = memberOpt.get().role();
        if (role != DivisionRole.OFFICER && role != DivisionRole.COMMANDER) {
            return PlaceResult.INSUFFICIENT_RANK;
        }

        int roundId = getCurrentRoundId();
        if (roundId < 0) {
            return PlaceResult.INVALID_REGION;
        }

        // Check depot limit
        int currentCount = db.countDepotsForDivision(division.divisionId());
        if (currentCount >= getMaxDepotsPerDivision()) {
            return PlaceResult.LIMIT_REACHED;
        }

        // Check region validity
        String regionId = regionService.getRegionIdForLocation(location.getBlockX(), location.getBlockZ());
        if (regionId == null) {
            return PlaceResult.INVALID_REGION;
        }

        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(regionId);
        if (statusOpt.isEmpty()) {
            return PlaceResult.INVALID_REGION;
        }

        RegionStatus status = statusOpt.get();
        String playerTeam = division.team();

        // Check region ownership rules
        if (!canPlaceInRegion(status, playerTeam)) {
            return PlaceResult.ENEMY_TERRITORY;
        }

        // Check distance to other depots
        if (isTooCloseToOtherDepot(location, roundId)) {
            return PlaceResult.TOO_CLOSE_TO_OTHER_DEPOT;
        }

        // Create the depot location record
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
        db.createDepotLocation(
                division.divisionId(),
                roundId,
                worldName,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                player.getUniqueId().toString(),
                regionId
        );

        plugin.getLogger().info("[Depot] " + player.getName() + " placed depot for division " +
                division.name() + " at " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());

        return PlaceResult.SUCCESS;
    }

    private boolean canPlaceInRegion(RegionStatus status, String playerTeam) {
        // Check if player's team owns or can access this region
        if (status.isOwnedBy(playerTeam)) {
            return true;
        }

        // Contested region where player's team is the original owner
        if (allowInContested() && status.ownerTeam() != null && status.ownerTeam().equalsIgnoreCase(playerTeam)) {
            return true;
        }

        // Neutral adjacent regions
        if (allowInNeutral() && status.ownerTeam() == null) {
            return true;
        }

        // Enemy territory
        if (allowInEnemy()) {
            return true;
        }

        return false;
    }

    private boolean isTooCloseToOtherDepot(Location location, int roundId) {
        int minDistance = getMinDistanceBetweenDepots();
        int minDistanceSq = minDistance * minDistance;

        // Check all depots in this round
        String regionId = regionService.getRegionIdForLocation(location.getBlockX(), location.getBlockZ());
        if (regionId == null) return false;

        List<DepotLocation> depotsInRegion = db.getDepotsInRegion(regionId, roundId);
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "world";

        for (DepotLocation depot : depotsInRegion) {
            if (!depot.world().equals(worldName)) continue;

            int dx = depot.x() - location.getBlockX();
            int dy = depot.y() - location.getBlockY();
            int dz = depot.z() - location.getBlockZ();
            int distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < minDistanceSq) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean breakDepot(Player player, Location location) {
        Optional<DepotLocation> depotOpt = getDepotAt(location);
        if (depotOpt.isEmpty()) {
            return false;
        }

        DepotLocation depot = depotOpt.get();

        // Get the division that owns this depot
        Optional<Division> depotDivision = divisionService.getDivision(depot.divisionId());
        if (depotDivision.isEmpty()) {
            // Division doesn't exist anymore, just remove the depot
            db.deleteDepotLocation(depot.locationId());
            return true;
        }

        // Check if player is on the same team
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) {
            return false;
        }

        String playerTeam = playerTeamOpt.get();
        String depotTeam = depotDivision.get().team();

        if (!playerTeam.equalsIgnoreCase(depotTeam)) {
            // Enemy trying to break - must use raid tool
            return false;
        }

        // Same team can break their own depots
        db.deleteDepotLocation(depot.locationId());
        plugin.getLogger().info("[Depot] " + player.getName() + " broke depot at " +
                location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
        return true;
    }

    @Override
    public Optional<DepotLocation> getDepotAt(Location location) {
        if (location.getWorld() == null) return Optional.empty();
        return getDepotAt(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public Optional<DepotLocation> getDepotAt(String world, int x, int y, int z) {
        return db.getDepotAt(world, x, y, z);
    }

    @Override
    public List<DepotLocation> getDepotsForDivision(int divisionId) {
        return db.getDepotsForDivision(divisionId);
    }

    @Override
    public List<DepotLocation> getDepotsInRegion(String regionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Collections.emptyList();
        return db.getDepotsInRegion(regionId, roundId);
    }

    @Override
    public List<DepotLocation> getDepotsForTeam(String team) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return Collections.emptyList();
        return db.getDepotsForTeam(team, roundId);
    }

    @Override
    public int getDepotCount(int divisionId) {
        return db.countDepotsForDivision(divisionId);
    }

    // ==================== Storage Access ====================

    @Override
    public Inventory openDepotStorage(Player player) {
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divisionOpt.isEmpty()) {
            return null;
        }

        Division division = divisionOpt.get();
        int roundId = getCurrentRoundId();
        if (roundId < 0) return null;

        // Create inventory with custom holder
        DepotInventoryHolder holder = new DepotInventoryHolder(division.divisionId(), roundId);
        @SuppressWarnings("deprecation")
        Inventory inventory = Bukkit.createInventory(holder, STORAGE_SIZE,
                ChatColor.GOLD + DEPOT_INVENTORY_TITLE + ChatColor.GRAY + " - " + ChatColor.WHITE + division.formattedTag());

        holder.setInventory(inventory);

        // Load contents from database
        ItemStack[] contents = getDepotContents(division.divisionId());
        if (contents != null) {
            inventory.setContents(contents);
        }

        // Track this inventory
        openDepotInventories.put(player.getUniqueId(), division.divisionId());

        return inventory;
    }

    @Override
    public ItemStack[] getDepotContents(int divisionId) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return new ItemStack[STORAGE_SIZE];

        Map<Integer, byte[]> storageData = db.getDepotStorage(divisionId, roundId);
        ItemStack[] contents = new ItemStack[STORAGE_SIZE];

        for (Map.Entry<Integer, byte[]> entry : storageData.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < STORAGE_SIZE) {
                contents[slot] = deserializeItem(entry.getValue());
            }
        }

        return contents;
    }

    @Override
    public void setDepotContents(int divisionId, ItemStack[] contents) {
        int roundId = getCurrentRoundId();
        if (roundId < 0) return;

        // Clear existing storage first
        db.clearDepotStorage(divisionId, roundId);

        // Save each non-null slot
        for (int i = 0; i < contents.length && i < STORAGE_SIZE; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                byte[] data = serializeItem(item);
                if (data != null) {
                    db.saveDepotStorageSlot(divisionId, roundId, i, data);
                }
            }
        }
    }

    @Override
    public void saveDepotInventory(Player player, Inventory inventory) {
        Integer divisionId = openDepotInventories.remove(player.getUniqueId());
        if (divisionId == null) return;

        setDepotContents(divisionId, inventory.getContents());
    }

    @Override
    public boolean isDepotInventory(Inventory inventory) {
        return inventory.getHolder() instanceof DepotInventoryHolder;
    }

    // ==================== Vulnerability & Raiding ====================

    @Override
    public boolean isDepotVulnerable(DepotLocation depot) {
        // Get the division that owns this depot
        Optional<Division> divisionOpt = divisionService.getDivision(depot.divisionId());
        if (divisionOpt.isEmpty()) {
            return true; // No owner = vulnerable
        }

        String depotTeam = divisionOpt.get().team();

        // Check region ownership
        Optional<RegionStatus> statusOpt = regionService.getRegionStatus(depot.regionId());
        if (statusOpt.isEmpty()) {
            return true;
        }

        RegionStatus status = statusOpt.get();

        // Depot is vulnerable if enemy team owns the region
        if (status.ownerTeam() != null && !status.ownerTeam().equalsIgnoreCase(depotTeam)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean isDepotVulnerable(Location location) {
        Optional<DepotLocation> depotOpt = getDepotAt(location);
        return depotOpt.map(this::isDepotVulnerable).orElse(false);
    }

    @Override
    public List<DepotLocation> getVulnerableDepotsInRegion(String regionId) {
        List<DepotLocation> depots = getDepotsInRegion(regionId);
        List<DepotLocation> vulnerable = new ArrayList<>();

        for (DepotLocation depot : depots) {
            if (isDepotVulnerable(depot)) {
                vulnerable.add(depot);
            }
        }

        return vulnerable;
    }

    @Override
    public RaidResult startRaid(Player raider, Location depotLocation) {
        if (!isEnabled()) {
            return RaidResult.NOT_ENABLED;
        }

        Optional<DepotLocation> depotOpt = getDepotAt(depotLocation);
        if (depotOpt.isEmpty()) {
            return RaidResult.NO_DEPOT;
        }

        DepotLocation depot = depotOpt.get();

        // Check if player has raid tool
        if (!isRaidTool(raider.getInventory().getItemInMainHand())) {
            return RaidResult.NO_TOOL;
        }

        // Check team
        Optional<String> raiderTeamOpt = teamService.getPlayerTeam(raider.getUniqueId());
        if (raiderTeamOpt.isEmpty()) {
            return RaidResult.WRONG_TEAM;
        }

        Optional<Division> depotDivisionOpt = divisionService.getDivision(depot.divisionId());
        if (depotDivisionOpt.isEmpty()) {
            return RaidResult.NO_DEPOT;
        }

        String raiderTeam = raiderTeamOpt.get();
        String depotTeam = depotDivisionOpt.get().team();

        if (raiderTeam.equalsIgnoreCase(depotTeam)) {
            return RaidResult.WRONG_TEAM;
        }

        // Check vulnerability
        if (!isDepotVulnerable(depot)) {
            return RaidResult.NOT_VULNERABLE;
        }

        // Check if someone else is raiding
        String locationKey = depot.locationKey();
        if (depotRaiders.containsKey(locationKey)) {
            return RaidResult.ALREADY_RAIDING;
        }

        // Check cooldown on this division's depots
        Optional<Long> lastRaid = db.getLastRaidOnDivision(depot.divisionId());
        if (lastRaid.isPresent()) {
            long cooldownMs = getRaidCooldownMinutes() * 60L * 1000L;
            if (System.currentTimeMillis() - lastRaid.get() < cooldownMs) {
                return RaidResult.ON_COOLDOWN;
            }
        }

        // Start the raid
        activeRaids.put(raider.getUniqueId(), locationKey);
        depotRaiders.put(locationKey, raider.getUniqueId());

        return RaidResult.SUCCESS;
    }

    @Override
    public RaidResult completeRaid(Player raider, Location depotLocation) {
        Optional<DepotLocation> depotOpt = getDepotAt(depotLocation);
        if (depotOpt.isEmpty()) {
            cancelRaid(raider);
            return RaidResult.NO_DEPOT;
        }

        DepotLocation depot = depotOpt.get();
        String locationKey = depot.locationKey();

        // Verify this player was raiding this depot
        UUID activeRaider = depotRaiders.get(locationKey);
        if (activeRaider == null || !activeRaider.equals(raider.getUniqueId())) {
            return RaidResult.CHANNEL_INTERRUPTED;
        }

        // Get raider's division (optional)
        Integer raiderDivisionId = divisionService.getPlayerDivision(raider.getUniqueId())
                .map(Division::divisionId).orElse(null);

        // Calculate and drop loot
        int itemsDropped = dropLoot(depot, depotLocation);

        // Store drop count for the raider (so listener can retrieve it)
        lastRaidDropCount.put(raider.getUniqueId(), itemsDropped);

        // Record the raid
        db.recordDepotRaid(depot.locationId(), depot.divisionId(), raider.getUniqueId().toString(),
                raiderDivisionId, itemsDropped);

        // Remove the depot
        db.deleteDepotLocation(depot.locationId());

        // Clean up tracking
        activeRaids.remove(raider.getUniqueId());
        depotRaiders.remove(locationKey);

        // Notify the victim division
        notifyDivisionOfRaid(depot.divisionId(), raider.getName(), itemsDropped);

        plugin.getLogger().info("[Depot] " + raider.getName() + " raided depot of division " +
                depot.divisionId() + ", dropped " + itemsDropped + " items");

        return RaidResult.SUCCESS;
    }

    private int dropLoot(DepotLocation depot, Location dropLocation) {
        ItemStack[] contents = getDepotContents(depot.divisionId());

        // Count non-null items
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                items.add(item);
            }
        }

        if (items.isEmpty()) {
            return 0;
        }

        // Calculate how many to drop
        int totalItems = items.size();
        int toDrop = (int) Math.ceil(totalItems * getLootDropPercentage());
        toDrop = Math.max(toDrop, Math.min(getMinItemsDropped(), totalItems));
        toDrop = Math.min(toDrop, getMaxItemsDropped());
        toDrop = Math.min(toDrop, totalItems);

        // Shuffle and drop random items
        Collections.shuffle(items);

        int dropped = 0;
        List<ItemStack> toRemove = new ArrayList<>();

        for (int i = 0; i < toDrop && i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (dropLocation.getWorld() != null) {
                dropLocation.getWorld().dropItemNaturally(dropLocation, item.clone());
                toRemove.add(item);
                dropped++;
            }
        }

        // Remove dropped items from storage
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR && !toRemove.contains(item)) {
                remaining.add(item);
            }
        }

        // Update storage with remaining items
        ItemStack[] newContents = new ItemStack[STORAGE_SIZE];
        for (int i = 0; i < remaining.size() && i < STORAGE_SIZE; i++) {
            newContents[i] = remaining.get(i);
        }
        setDepotContents(depot.divisionId(), newContents);

        return dropped;
    }

    private void notifyDivisionOfRaid(int divisionId, String raiderName, int itemsDropped) {
        List<DivisionMember> members = divisionService.getMembers(divisionId);
        for (DivisionMember member : members) {
            Player player = Bukkit.getPlayer(UUID.fromString(member.playerUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "⚠ Your division depot was raided by " +
                        ChatColor.YELLOW + raiderName + ChatColor.RED + "! " + itemsDropped + " items were lost!");
            }
        }
    }

    @Override
    public void cancelRaid(Player raider) {
        String locationKey = activeRaids.remove(raider.getUniqueId());
        if (locationKey != null) {
            depotRaiders.remove(locationKey);
        }
    }

    @Override
    public boolean isRaiding(Player player) {
        return activeRaids.containsKey(player.getUniqueId());
    }

    @Override
    public Optional<UUID> getRaider(Location depotLocation) {
        Optional<DepotLocation> depotOpt = getDepotAt(depotLocation);
        if (depotOpt.isEmpty()) {
            return Optional.empty();
        }

        String locationKey = depotOpt.get().locationKey();
        return Optional.ofNullable(depotRaiders.get(locationKey));
    }

    @Override
    public int calculateLootDropCount(int divisionId) {
        ItemStack[] contents = getDepotContents(divisionId);

        int totalItems = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                totalItems++;
            }
        }

        if (totalItems == 0) return 0;

        int toDrop = (int) Math.ceil(totalItems * getLootDropPercentage());
        toDrop = Math.max(toDrop, Math.min(getMinItemsDropped(), totalItems));
        toDrop = Math.min(toDrop, getMaxItemsDropped());
        return Math.min(toDrop, totalItems);
    }

    @Override
    public int getLastRaidDropCount(UUID raiderUuid) {
        return lastRaidDropCount.getOrDefault(raiderUuid, 0);
    }

    // ==================== Cleanup ====================

    @Override
    public void clearDepotsForRound(int roundId) {
        db.deleteDepotLocationsForRound(roundId);
        db.deleteDepotStorageForRound(roundId);
        activeRaids.clear();
        depotRaiders.clear();
        openDepotInventories.clear();
    }

    @Override
    public void removeDepotsForDivision(int divisionId, boolean dropContents) {
        int roundId = getCurrentRoundId();

        if (dropContents && roundId >= 0) {
            // Drop contents at each depot location
            List<DepotLocation> depots = db.getDepotsForDivision(divisionId);
            ItemStack[] contents = getDepotContents(divisionId);

            if (contents != null && depots.size() > 0) {
                // Drop at first depot location
                DepotLocation firstDepot = depots.get(0);
                org.bukkit.World world = Bukkit.getWorld(firstDepot.world());
                if (world != null) {
                    Location dropLoc = new Location(world, firstDepot.x(), firstDepot.y(), firstDepot.z());
                    for (ItemStack item : contents) {
                        if (item != null && item.getType() != Material.AIR) {
                            world.dropItemNaturally(dropLoc, item);
                        }
                    }
                }
            }
        }

        // Clear storage
        if (roundId >= 0) {
            db.clearDepotStorage(divisionId, roundId);
        }

        // Delete depot locations
        db.deleteDepotsForDivision(divisionId);
    }

    // ==================== Item Utilities ====================

    @Override
    public boolean isDepotItem(ItemStack item) {
        return depotItem.isDepotBlock(item);
    }

    @Override
    public boolean isRaidTool(ItemStack item) {
        return depotItem.isRaidTool(item);
    }

    @Override
    public ItemStack createDepotItem(Player player) {
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divisionOpt.isEmpty()) {
            return null;
        }
        return depotItem.createDepotBlock(divisionOpt.get());
    }

    @Override
    public ItemStack createRaidTool() {
        return depotItem.createRaidTool();
    }

    /**
     * Gets the division ID from a depot block item.
     */
    public int getDivisionIdFromItem(ItemStack item) {
        return depotItem.getDivisionIdFromItem(item);
    }

    /**
     * Gets the team from a depot block item.
     */
    public String getTeamFromItem(ItemStack item) {
        return depotItem.getTeamFromItem(item);
    }

    // ==================== Serialization Helpers ====================

    private byte[] serializeItem(ItemStack item) {
        if (item == null) return null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            return baos.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to serialize item", e);
            return null;
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        if (data == null) return null;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) bois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize item", e);
            return null;
        }
    }
}

