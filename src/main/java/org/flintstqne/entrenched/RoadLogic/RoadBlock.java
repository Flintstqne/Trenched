package org.flintstqne.entrenched.RoadLogic;

/**
 * Represents a tracked road block placed by a player.
 */
public record RoadBlock(
        int x,
        int y,
        int z,
        String regionId,
        String placedByUuid,
        String team,
        long placedAt
) {
    /**
     * Creates a block key for map lookups.
     */
    public String toKey() {
        return x + "," + y + "," + z;
    }

    /**
     * Creates a block key from coordinates.
     */
    public static String toKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}

