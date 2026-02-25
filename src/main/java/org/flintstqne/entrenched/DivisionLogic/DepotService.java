package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service interface for Division Depot operations.
 * Handles depot block placement, shared storage, vulnerability, and raiding.
 */
public interface DepotService {

    // ==================== Result Enums ====================

    enum PlaceResult {
        SUCCESS,
        NO_DIVISION,
        LIMIT_REACHED,
        TOO_CLOSE_TO_OTHER_DEPOT,
        INVALID_REGION,
        ENEMY_TERRITORY,
        NOT_ENABLED
    }

    enum RaidResult {
        SUCCESS,              // Loot dropped, depot destroyed
        NOT_VULNERABLE,       // Region still owned by depot team
        NO_TOOL,              // Player doesn't have raid tool
        WRONG_TEAM,           // Can't raid own team's depots
        ALREADY_RAIDING,      // Someone else is raiding this depot
        ON_COOLDOWN,          // Recently raided, on cooldown
        NO_DEPOT,             // No depot at location
        CHANNEL_INTERRUPTED,  // Raider took damage or moved
        NOT_ENABLED
    }

    // ==================== Depot Block Management ====================

    /**
     * Attempts to place a depot block at the given location.
     * Validates division membership, distance rules, and region ownership.
     */
    PlaceResult placeDepot(Player player, Location location);

    /**
     * Breaks a depot at the given location (by owner).
     * Only the owning team can break their own depots.
     * Returns true if depot was removed.
     */
    boolean breakDepot(Player player, Location location);

    /**
     * Gets the depot at a specific world location.
     */
    Optional<DepotLocation> getDepotAt(Location location);

    /**
     * Gets the depot at specific coordinates.
     */
    Optional<DepotLocation> getDepotAt(String world, int x, int y, int z);

    /**
     * Gets all depot locations for a division.
     */
    List<DepotLocation> getDepotsForDivision(int divisionId);

    /**
     * Gets all depot locations in a specific region.
     */
    List<DepotLocation> getDepotsInRegion(String regionId);

    /**
     * Gets all depot locations for a team in the current round.
     */
    List<DepotLocation> getDepotsForTeam(String team);

    /**
     * Gets the number of depots a division has placed.
     */
    int getDepotCount(int divisionId);

    /**
     * Gets the maximum allowed depots per division.
     */
    int getMaxDepotsPerDivision();

    /**
     * Gets the minimum distance required between depots.
     */
    int getMinDistanceBetweenDepots();

    // ==================== Storage Access ====================

    /**
     * Opens the depot storage inventory for a player.
     * Opens the player's own division storage, regardless of which depot they click.
     * Returns null if player has no division.
     */
    Inventory openDepotStorage(Player player);

    /**
     * Gets the raw contents of a division's depot storage.
     */
    ItemStack[] getDepotContents(int divisionId);

    /**
     * Sets the contents of a division's depot storage.
     */
    void setDepotContents(int divisionId, ItemStack[] contents);

    /**
     * Saves a player's depot inventory (called on inventory close).
     */
    void saveDepotInventory(Player player, Inventory inventory);

    /**
     * Checks if an inventory belongs to the depot system.
     */
    boolean isDepotInventory(Inventory inventory);

    // ==================== Vulnerability & Raiding ====================

    /**
     * Checks if a depot is vulnerable (region captured by enemy).
     */
    boolean isDepotVulnerable(DepotLocation depot);

    /**
     * Checks if a depot is vulnerable by location.
     */
    boolean isDepotVulnerable(Location location);

    /**
     * Gets all vulnerable depots in a region.
     */
    List<DepotLocation> getVulnerableDepotsInRegion(String regionId);

    /**
     * Initiates a raid on a depot.
     * The actual raid has a channel time before completion.
     */
    RaidResult startRaid(Player raider, Location depotLocation);

    /**
     * Completes a raid after the channel time.
     * Drops loot and destroys the depot.
     */
    RaidResult completeRaid(Player raider, Location depotLocation);

    /**
     * Cancels an in-progress raid.
     */
    void cancelRaid(Player raider);

    /**
     * Checks if a player is currently raiding.
     */
    boolean isRaiding(Player player);

    /**
     * Gets the raiding player for a depot, if any.
     */
    Optional<UUID> getRaider(Location depotLocation);

    /**
     * Calculates how many items will drop when a depot is raided.
     */
    int calculateLootDropCount(int divisionId);

    /**
     * Gets the raid channel time in seconds.
     */
    int getRaidChannelSeconds();

    /**
     * Gets the loot drop percentage (0.0 - 1.0).
     */
    double getLootDropPercentage();

    // ==================== Cleanup ====================

    /**
     * Clears all depot data for a round (called on round reset).
     */
    void clearDepotsForRound(int roundId);

    /**
     * Removes all depots for a division (called on disband).
     * Storage contents are dropped at depot locations.
     */
    void removeDepotsForDivision(int divisionId, boolean dropContents);

    /**
     * Checks if the depot system is enabled.
     */
    boolean isEnabled();

    // ==================== Item Utilities ====================

    /**
     * Checks if an item is a Division Depot block.
     */
    boolean isDepotItem(ItemStack item);

    /**
     * Checks if an item is a Raid Tool.
     */
    boolean isRaidTool(ItemStack item);

    /**
     * Creates a Division Depot item for a player's division.
     * Returns null if player has no division.
     */
    ItemStack createDepotItem(Player player);

    /**
     * Creates a Raid Tool item.
     */
    ItemStack createRaidTool();
}

