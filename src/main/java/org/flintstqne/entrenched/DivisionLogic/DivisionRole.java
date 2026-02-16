package org.flintstqne.entrenched.DivisionLogic;

/**
 * Roles within a division hierarchy.
 */
public enum DivisionRole {
    COMMANDER,
    OFFICER,
    MEMBER;

    public boolean canManageMembers() {
        return this == COMMANDER || this == OFFICER;
    }

    public boolean canSetWaypoints() {
        return this == COMMANDER || this == OFFICER;
    }

    public boolean canPromote() {
        return this == COMMANDER;
    }

    public boolean canDisband() {
        return this == COMMANDER;
    }

    public boolean canEditDivision() {
        return this == COMMANDER;
    }

    public String getSymbol() {
        return switch (this) {
            case COMMANDER -> "★";
            case OFFICER -> "•";
            case MEMBER -> "";
        };
    }

    public String getDisplayName() {
        return switch (this) {
            case COMMANDER -> "Commander";
            case OFFICER -> "Officer";
            case MEMBER -> "Member";
        };
    }
}

