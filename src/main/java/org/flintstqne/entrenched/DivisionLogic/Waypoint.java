package org.flintstqne.entrenched.DivisionLogic;

/**
 * Represents a waypoint set by a division.
 */
public record Waypoint(
        int waypointId,
        int divisionId,
        String name,
        String world,
        int x,
        int y,
        int z,
        String createdBy,
        long createdAt
) {
    public String formattedLocation() {
        return String.format("(%d, %d, %d)", x, y, z);
    }
}

