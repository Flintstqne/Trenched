package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Represents an objective in a region.
 */
public record RegionObjective(
        int id,                      // Database ID
        String regionId,             // "A1", "B2", etc.
        int roundId,
        ObjectiveType type,
        ObjectiveStatus status,
        Integer locationX,           // Nullable - some objectives don't have locations
        Integer locationY,
        Integer locationZ,
        double progress,             // 0.0 to 1.0 for multi-step objectives
        String completedBy,          // Team that completed (null if not completed)
        long createdAt,              // Timestamp when created
        Long completedAt             // Timestamp when completed (null if not completed)
) {

    /**
     * Checks if this objective has a specific location.
     */
    public boolean hasLocation() {
        return locationX != null && locationY != null && locationZ != null;
    }

    /**
     * Gets the location as a Bukkit Location.
     * @param world The world to create the location in
     * @return Location or null if no location set
     */
    public Location getLocation(World world) {
        if (!hasLocation() || world == null) {
            return null;
        }
        return new Location(world, locationX + 0.5, locationY, locationZ + 0.5);
    }

    /**
     * Checks if this objective is active.
     */
    public boolean isActive() {
        return status == ObjectiveStatus.ACTIVE;
    }

    /**
     * Checks if this objective is completed.
     */
    public boolean isCompleted() {
        return status == ObjectiveStatus.COMPLETED;
    }

    /**
     * Gets progress as a percentage (0-100).
     */
    public int getProgressPercent() {
        return (int) (progress * 100);
    }

    /**
     * Gets the influence reward for completing this objective.
     */
    public int getInfluenceReward() {
        return type.getInfluenceReward();
    }

    /**
     * Gets a description of the objective with current progress.
     */
    public String getProgressDescription() {
        String base = type.getDescription();

        // Update descriptions with progress for applicable objectives
        return switch (type) {
            case RAID_SABOTAGE_DEFENSES ->
                "Destroy enemy fortifications (" + (int)(progress * 50) + "/50 blocks)";
            case SETTLEMENT_SECURE_PERIMETER ->
                "Build defensive walls (" + (int)(progress * 100) + "/100 blocks)";
            case SETTLEMENT_SUPPLY_ROUTE ->
                "Build a road connecting to friendly territory (" + (int)(progress * 64) + "/64 blocks)";
            case RAID_HOLD_GROUND ->
                "Hold the region center (" + (int)(progress * 60) + "/60 seconds)";
            case RAID_PLANT_EXPLOSIVE -> {
                if (progress == 0) {
                    yield "Plant TNT at the target location";
                } else {
                    int seconds = (int)(progress * 30);
                    yield "Defend the explosive (" + seconds + "/30 seconds)";
                }
            }
            case RAID_CAPTURE_INTEL -> {
                if (progress == 0) {
                    yield "Pick up the intel at the marked location";
                } else if (progress < 1.0) {
                    yield "Return intel to friendly territory!";
                } else {
                    yield "Intel captured!";
                }
            }
            case SETTLEMENT_RESOURCE_DEPOT -> {
                // Progress is qualifyingContainers / requiredContainers
                // Estimate qualifying containers from progress
                int estimatedQualifying = Math.min(4, (int)(progress * 4));
                yield "Stock 4 containers with 500+ items each (" + estimatedQualifying + "/4 done)";
            }
            default -> base;
        };
    }

    /**
     * Creates a copy with updated progress.
     */
    public RegionObjective withProgress(double newProgress) {
        return new RegionObjective(id, regionId, roundId, type, status,
                locationX, locationY, locationZ, newProgress, completedBy, createdAt, completedAt);
    }

    /**
     * Creates a copy marked as completed.
     */
    public RegionObjective asCompleted(String team) {
        return new RegionObjective(id, regionId, roundId, type, ObjectiveStatus.COMPLETED,
                locationX, locationY, locationZ, 1.0, team, createdAt, System.currentTimeMillis());
    }

    /**
     * Creates a copy marked as expired.
     */
    public RegionObjective asExpired() {
        return new RegionObjective(id, regionId, roundId, type, ObjectiveStatus.EXPIRED,
                locationX, locationY, locationZ, progress, completedBy, createdAt, System.currentTimeMillis());
    }
}

