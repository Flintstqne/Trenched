package org.flintstqne.entrenched.TeamLogic;

/**
 * Represents a team with an ID, display name, and ARGB color.
 * @param color ARGB integer (e.g., 0xFF000033 for semi-transparent red)
 */
public record Team(String id, String displayName, int color) {}