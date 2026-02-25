package org.flintstqne.entrenched.DivisionLogic;

import java.util.UUID;

/**
 * Represents a physical Division Depot block location in the world.
 * All depots for a division share the same virtual storage inventory.
 */
public record DepotLocation(
        int locationId,
        int divisionId,
        int roundId,
        String world,
        int x,
        int y,
        int z,
        UUID placedBy,
        long placedAt,
        String regionId
) {
    /**
     * Gets the formatted location string for display.
     */
    public String formattedLocation() {
        return String.format("(%d, %d, %d)", x, y, z);
    }

    /**
     * Gets the location key for map lookups (world,x,y,z).
     */
    public String locationKey() {
        return world + "," + x + "," + y + "," + z;
    }

    /**
     * Creates a location key from coordinates.
     */
    public static String makeLocationKey(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }
}

