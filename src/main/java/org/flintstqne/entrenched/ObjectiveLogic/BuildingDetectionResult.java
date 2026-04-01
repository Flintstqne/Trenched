package org.flintstqne.entrenched.ObjectiveLogic;

import java.util.ArrayList;
import java.util.List;

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
        int maxZ,
        int bedCount
) {
    public int getProgressPercent() {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, progressRatio)) * 100.0);
    }

    /**
     * Gets a user-friendly progress description showing what's done and what's needed.
     * Uses plain language that any player can understand.
     */
    public String getFriendlyProgress() {
        if (valid) {
            // Show the variant if one was detected
            if (variant != null && !variant.equals("Standard") && !variant.contains("(needs")) {
                return "✓ " + variant + " registered!";
            }
            return "✓ Complete! Building registered.";
        }

        // Parse the summary to find what's missing and create a friendlier message
        List<String> missing = new ArrayList<>();
        String lowerSummary = summary.toLowerCase();

        // Check for common missing items and translate to plain language
        if (lowerSummary.contains("structural mass") || lowerSummary.contains("footprint")) {
            missing.add("Build bigger walls/floor");
        }
        if (lowerSummary.contains("interior") || lowerSummary.contains("room")) {
            missing.add("Add enclosed space inside");
        }
        if (lowerSummary.contains("roof")) {
            missing.add("Add a roof");
        }
        if (lowerSummary.contains("chest")) {
            missing.add("Place a chest");
        }
        if (lowerSummary.contains("crafting")) {
            missing.add("Place a crafting table");
        }
        if (lowerSummary.contains("entrance") || lowerSummary.contains("door")) {
            missing.add("Add a door");
        }
        if (lowerSummary.contains("height")) {
            missing.add("Build taller");
        }
        if (lowerSummary.contains("platform")) {
            missing.add("Add viewing platform at top");
        }
        if (lowerSummary.contains("climbable") || lowerSummary.contains("ladder") || lowerSummary.contains("stair")) {
            missing.add("Add ladder/stairs to climb");
        }
        if (lowerSummary.contains("visibility") || lowerSummary.contains("openness")) {
            missing.add("Open up the top for viewing");
        }
        if (lowerSummary.contains("bed")) {
            missing.add("Place beds (your team color)");
        }
        if (lowerSummary.contains("floor space")) {
            missing.add("More floor space needed");
        }
        if (lowerSummary.contains("build quality") || lowerSummary.contains("tower shape") || lowerSummary.contains("barracks")) {
            missing.add("Improve overall structure");
        }

        // If base building requirements are all met but only variant items missing,
        // show variant upgrade hints
        if (missing.isEmpty() && variant != null && variant.contains("(needs")) {
            // Extract what's needed from variant name like "Mining Outpost (needs Furnace, Pickaxe in chest)"
            String baseName = variant.substring(0, variant.indexOf(" (needs"));
            String needs = variant.substring(variant.indexOf("(needs ") + 7, variant.length() - 1);
            return "✓ Structure done! For " + baseName + ": add " + needs;
        }

        if (missing.isEmpty()) {
            // Fallback to the technical summary if we couldn't parse it
            return summary;
        }

        // If only 1-2 base items missing AND variant has upgrade hints, append variant tip
        String variantHint = "";
        if (missing.size() <= 2 && variant != null && variant.contains("(needs")) {
            String baseName = variant.substring(0, variant.indexOf(" (needs"));
            String needs = variant.substring(variant.indexOf("(needs ") + 7, variant.length() - 1);
            variantHint = " | " + baseName + ": " + needs;
        }

        // Return the first 2 most important missing items
        String result;
        if (missing.size() == 1) {
            result = "Need: " + missing.get(0);
        } else if (missing.size() == 2) {
            result = "Need: " + missing.get(0) + " & " + missing.get(1);
        } else {
            result = "Need: " + missing.get(0) + " +" + (missing.size() - 1) + " more";
        }

        // Only append variant hint if there's room (don't make bossbar too long)
        if (!variantHint.isEmpty() && result.length() + variantHint.length() < 80) {
            result += variantHint;
        }

        return result;
    }

    /**
     * Gets a detailed checklist showing all requirements and their status.
     * Useful for the /obj command or detailed UI.
     */
    public List<String> getChecklist() {
        List<String> checklist = new ArrayList<>();
        String lowerSummary = summary.toLowerCase();

        switch (type) {
            case OUTPOST -> {
                checklist.add((structuralBlocks >= 24 ? "✓" : "✗") + " Walls & Structure (24+ blocks)");
                checklist.add((footprint >= 14 ? "✓" : "✗") + " Floor Size (14+ blocks)");
                checklist.add((!lowerSummary.contains("interior") ? "✓" : "✗") + " Enclosed Interior Space");
                checklist.add((!lowerSummary.contains("roof") ? "✓" : "✗") + " Roof Coverage");
                checklist.add((!lowerSummary.contains("chest") ? "✓" : "✗") + " Storage Chest");
                checklist.add((!lowerSummary.contains("crafting") ? "✓" : "✗") + " Crafting Table");
                checklist.add((!lowerSummary.contains("entrance") ? "✓" : "✗") + " Door/Entrance");

                // Show variant upgrade requirements if a variant environment was detected
                if (variant != null && !variant.equals("Standard")) {
                    String baseName = variant.contains("(needs")
                            ? variant.substring(0, variant.indexOf(" (needs"))
                            : variant;
                    boolean variantComplete = !variant.contains("(needs");
                    checklist.add(""); // blank line separator
                    checklist.add("§e⬆ " + baseName + " Upgrade" + (variantComplete ? " ✓" : ":"));
                    if (variant.contains("(needs")) {
                        String needs = variant.substring(variant.indexOf("(needs ") + 7, variant.length() - 1);
                        for (String item : needs.split(", ")) {
                            checklist.add("  ✗ " + item.trim());
                        }
                    }
                }
            }
            case WATCHTOWER -> {
                checklist.add((!lowerSummary.contains("height") ? "✓" : "✗") + " Height (14+ blocks tall)");
                checklist.add((!lowerSummary.contains("platform") ? "✓" : "✗") + " Viewing Platform at Top");
                checklist.add((!lowerSummary.contains("climbable") ? "✓" : "✗") + " Ladder/Stairs to Climb");
                checklist.add((!lowerSummary.contains("base") ? "✓" : "✗") + " Sturdy Base");
                checklist.add((!lowerSummary.contains("visibility") && !lowerSummary.contains("openness") ? "✓" : "✗") + " Open View at Top");
            }
            case GARRISON -> {
                checklist.add((structuralBlocks >= 34 ? "✓" : "✗") + " Walls & Structure (34+ blocks)");
                checklist.add((!lowerSummary.contains("floor") ? "✓" : "✗") + " Floor Space (12+ blocks)");
                checklist.add((!lowerSummary.contains("interior") ? "✓" : "✗") + " Interior Rooms");
                checklist.add((!lowerSummary.contains("roof") ? "✓" : "✗") + " Roof Coverage");
                checklist.add((!lowerSummary.contains("bed") ? "✓" : "✗") + " Team-colored Beds (3+)");
                checklist.add((!lowerSummary.contains("entrance") ? "✓" : "✗") + " Entrance");
                // "more barracks detail" is added by BuildingDetector when total score < required
                checklist.add((!lowerSummary.contains("barracks") ? "✓" : "✗") + " Barracks Quality (overall score)");

                // Show current variant upgrade progress
                if (variant != null && !variant.equals("Basic Garrison")) {
                    String baseName = variant.contains("(needs")
                            ? variant.substring(0, variant.indexOf(" (needs"))
                            : variant;
                    boolean variantComplete = !variant.contains("(needs");
                    checklist.add(""); // blank line separator
                    checklist.add("§e⬆ " + baseName + (variantComplete ? " ✓" : " (in progress):"));
                    if (variant.contains("(needs")) {
                        String needs = variant.substring(variant.indexOf("(needs ") + 7, variant.length() - 1);
                        for (String item : needs.split(", ")) {
                            checklist.add("  §c✗ " + item.trim());
                        }
                    }
                }

                // Full variant reference catalog (first match wins — variants don't stack)
                checklist.add("");
                checklist.add("§6─── Variant Guide (first match wins) ───");
                checklist.add("§7Basic§f: 3+ team beds (default)");
                checklist.add("§7Medical§f: Brewing Stand + chest + §d3 healing potions§f → §dRegen I§f (30s on arrival)");
                checklist.add("§7Armory§f: Anvil/Smithing + chest + §b5 iron ingots§f → §bResistance I§f (30s on arrival)");
                checklist.add("§7Supply§f: 4+ chests + §664+ items total§f → §6Saturation§f (60s) + §a+1 spawn capacity");
                checklist.add("§7Command§f: 2+ Lecterns or Banners → §cStrength I§f (30s on arrival)");
                checklist.add("§7Fortified§f: 20+ wall/fence blocks → §9Resistance II§f (15s on arrival)");
            }
        }

        return checklist;
    }
}
