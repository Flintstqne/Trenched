package org.flintstqne.entrenched.ObjectiveLogic;

import java.util.Optional;

/**
 * Organic building types tracked for settlement objectives.
 */
public enum BuildingType {
    OUTPOST(ObjectiveType.SETTLEMENT_ESTABLISH_OUTPOST, "Outpost"),
    WATCHTOWER(ObjectiveType.SETTLEMENT_WATCHTOWER, "Watchtower"),
    GARRISON(ObjectiveType.SETTLEMENT_GARRISON_QUARTERS, "Garrison");

    private final ObjectiveType objectiveType;
    private final String displayName;

    BuildingType(ObjectiveType objectiveType, String displayName) {
        this.objectiveType = objectiveType;
        this.displayName = displayName;
    }

    public ObjectiveType getObjectiveType() {
        return objectiveType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Optional<BuildingType> fromObjectiveType(ObjectiveType objectiveType) {
        for (BuildingType value : values()) {
            if (value.objectiveType == objectiveType) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
