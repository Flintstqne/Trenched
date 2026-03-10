package org.flintstqne.entrenched.ObjectiveLogic;

/**
 * Debug and scoring snapshot from an organic building scan.
 */
public record BuildingDetectionResult(
        BuildingType type,
        boolean valid,
        double progressRatio,
        double totalScore,
        double requiredScore,
        double structureScore,
        double interiorScore,
        double accessScore,
        double signatureScore,
        double contextScore,
        String variant,
        String summary,
        int structuralBlocks,
        int footprint,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {
    public int getProgressPercent() {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, progressRatio)) * 100.0);
    }
}
