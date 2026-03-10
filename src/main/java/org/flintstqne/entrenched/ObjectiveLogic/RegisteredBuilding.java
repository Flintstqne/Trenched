package org.flintstqne.entrenched.ObjectiveLogic;

/**
 * Persisted building instance tied to a settlement objective.
 */
public record RegisteredBuilding(
        int id,
        int objectiveId,
        String regionId,
        int roundId,
        BuildingType type,
        String team,
        RegisteredBuildingStatus status,
        int anchorX,
        int anchorY,
        int anchorZ,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        double totalScore,
        double structureScore,
        double interiorScore,
        double accessScore,
        double signatureScore,
        double contextScore,
        String variant,
        long registeredAt,
        long lastValidatedAt,
        Long invalidatedAt
) {
}
