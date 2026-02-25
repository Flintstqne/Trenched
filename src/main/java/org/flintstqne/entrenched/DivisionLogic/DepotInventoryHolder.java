package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Custom inventory holder for Division Depot storage.
 * Tracks the division ID and round ID for persistence.
 */
public class DepotInventoryHolder implements InventoryHolder {

    private static final int STORAGE_SIZE = 54; // Double chest size

    private final int divisionId;
    private final int roundId;
    private Inventory inventory;

    /**
     * Creates a new depot inventory holder.
     *
     * @param divisionId The division that owns this storage
     * @param roundId    The round this storage belongs to
     */
    public DepotInventoryHolder(int divisionId, int roundId) {
        this.divisionId = divisionId;
        this.roundId = roundId;
    }

    /**
     * Gets the division ID that owns this storage.
     */
    public int getDivisionId() {
        return divisionId;
    }

    /**
     * Gets the round ID this storage belongs to.
     */
    public int getRoundId() {
        return roundId;
    }

    /**
     * Sets the inventory reference.
     * Called after creating the inventory with Bukkit.createInventory().
     */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /**
     * Checks if this holder has a valid inventory set.
     */
    public boolean hasInventory() {
        return inventory != null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Inventory getInventory() {
        if (inventory != null) {
            return inventory;
        }
        // Fallback: create a new empty inventory
        return Bukkit.createInventory(this, STORAGE_SIZE, "Division Depot");
    }

    /**
     * Gets the storage size in slots.
     */
    public static int getStorageSize() {
        return STORAGE_SIZE;
    }
}

