# Building System Bugfix Plan

**Created:** March 11, 2026

---

## Issues Reported

1. **Trees counted as outpost structure** — Outpost objectives spawn near trees, and natural tree logs (`OAK_LOG`, `SPRUCE_LOG`, etc.) are counted as construction material. This allows players to place just a chest and crafting table next to a tree to satisfy the detection requirements.

2. **`/obj` doesn't show Buildings section** — After building and registering a Forest Outpost, the "YOUR BUILDINGS" section (with variant upgrade hints) does not appear in `/objectives`.

3. **Outpost variant buff not applied on exit** — When a player leaves a fully-upgraded outpost (e.g., Forest Outpost with all requirements met), the variant potion effect (e.g., Haste I for 5 min) is never applied.

---

## Issue 1: Natural Tree Logs Counted as Construction Material

### Root Cause

In `BuildingDetector.java` → `isConstructionMaterial()` (~line 971):

- `LEAVES` and `ORE` blocks are explicitly excluded
- **LOG blocks are NOT excluded** — `OAK_LOG`, `SPRUCE_LOG`, `BIRCH_LOG`, etc. are all solid blocks that pass through all filters and default to `true`
- The flood-fill algorithm picks up entire tree trunks as "connected components" with high structural mass and footprint scores
- A player can place a chest + crafting table near a tree, and the tree trunk provides enough structural blocks to pass the 70/100 score threshold

### Proposed Fix

Add LOG and WOOD (bark blocks) to the exclusion check in `isConstructionMaterial()`, but **only unstripped variants**:

```java
// In isConstructionMaterial(), after the LEAVES/ORE check:
if (name.contains("LOG") && !name.contains("STRIPPED")) {
    return false; // Natural tree trunks - not player construction
}
if (name.contains("WOOD") && !name.contains("STRIPPED")) {
    return false; // Natural bark blocks - not player construction  
}
```

**Rationale:**
- Unstripped logs are almost always natural tree trunks
- Stripped logs (`STRIPPED_OAK_LOG`, etc.) require intentional player interaction (right-click with axe), making them reliable player-placed indicators
- Players who want to use logs in their builds can strip them first — this is a minimal gameplay burden

### Impact Assessment

- **Positive:** Prevents tree exploitation for outposts, watchtowers, and garrisons
- **Side effect:** Existing builds using unstripped logs would lose structural block count on next integrity check
- **Mitigation:** The structural block requirement (24 for outpost) is low enough that most real builds have plenty of non-log blocks (cobblestone, planks, etc.)
- **Variant detection unaffected:** The variant scanner at `detectOutpostVariant()` counts logs/wood for environment detection separately (not via `isConstructionMaterial`), so Forest Outpost terrain detection still works

### No Changes Needed (Per User)

User confirmed this approach requires no code changes at this time — just documenting the plan.

---

## Issue 2: `/obj` Doesn't Show Buildings Section

### Root Cause

In `ObjectiveCommand.java` → `showObjectives()`:

The "YOUR BUILDINGS" section (line ~226) is placed **after** the objectives list. But there are multiple early-return paths that prevent reaching it:

1. **Line ~114:** `if (!isRegionValidForCapture(regionId, team, status))` — If the region is already captured/owned and not adjacent, the command returns early with "This region is not adjacent to your territory" and **never reaches the buildings section**.

2. **Line ~161:** `if (relevantCategory == null)` — When a player is a **defender** in their own region (owned state), `getRelevantCategory()` returns null. The command prints "No objectives available" and returns — **never reaching the buildings section**.

3. **Line ~170:** `if (objectives.isEmpty())` — If there are no active objectives, returns with "No active objectives" — **never reaching the buildings section**.

In all three cases, the buildings section is unreachable even though the player has registered buildings in the region.

### Proposed Fix

Move the "YOUR BUILDINGS" section into a **standalone block** that always executes regardless of objective category, adjacency, or region state. It should display whenever:
- The player is in a region
- The player has a team
- There are registered buildings belonging to their team in that region

Specifically:
1. Extract the buildings display code (lines ~226-265) into a separate method like `showRegisteredBuildings(player, regionId, team)`
2. Call it **before** any early returns, or at the very top of the command after team/region validation
3. Show it even in owned/defended regions where no objectives are active

### No Changes Needed (Per User)

User confirmed this approach requires no code changes at this time — just documenting the plan.

---

## Issue 3: Outpost Variant Buff Not Applied on Exit

### Root Cause

**Critical bug in `BuildingBenefitManager.java` → `pruneTrackedPlayers()` (line ~414):**

The `tickBenefits()` method calls `pruneTrackedPlayers()` at line 164 **before** the main player processing loop. The prune method checks if a player is still inside a building's bounding box, and if not, it silently removes them from the `playersInBuildings` tracking map — **without ever calling `onPlayerExitBuilding()`**.

The exit callback (`onPlayerExitBuilding()`) is where `applyOutpostVariantBuff()` lives. Since the prune removes the player first, the main loop's exit logic at line 200 never fires because `previousBuildingId` is already null.

**Sequence of events:**

```
tickBenefits() called every 1 second:

1. pruneTrackedPlayers() runs FIRST
   → Finds player is outside building bounds
   → Removes player from playersInBuildings  ← BUG: no exit callback!
   → Does NOT call onPlayerExitBuilding()

2. Main player loop runs
   → previousBuildingId = playersInBuildings.get(player) → null (was just pruned)
   → insideBuilding = null (player is outside)
   → Goes to: else if (previousBuildingId != null) → FALSE (it's null)
   → Exit callback NEVER fires
   → Buff NEVER applied
```

### Proposed Fix

In `pruneTrackedPlayers()` around lines 437-440, when the player is found to be outside the building bounds, call `onPlayerExitBuilding(player, building)` **before** adding to the stale list:

```java
// Current (broken):
if (!isInsideBuilding(playerX, playerY, playerZ, building)) {
    staleTrackedPlayers.add(entry.getKey());  // Silent removal, no callback
}

// Fixed:
if (!isInsideBuilding(playerX, playerY, playerZ, building)) {
    onPlayerExitBuilding(player, building);   // Fire exit callback FIRST
    staleTrackedPlayers.add(entry.getKey());  // Then mark for removal
}
```

**Why this is safe (no double-fire risk):**
- After the prune removes the player from `playersInBuildings`, the main loop's `previousBuildingId` will be null
- The exit branch (`else if (previousBuildingId != null)`) won't execute
- So `onPlayerExitBuilding` fires exactly once

### Secondary Concern: Variant Name Matching

In `applyOutpostVariantBuff()` (line ~268), the switch statement uses exact string matches:

```java
case "Mining Outpost" -> effectType = PotionEffectType.LUCK;
case "Fishing Outpost" -> effectType = PotionEffectType.LUCK;
case "Farm Outpost" -> effectType = PotionEffectType.SATURATION;
case "Forest Outpost" -> effectType = PotionEffectType.HASTE;
case "Mountain Outpost" -> effectType = PotionEffectType.SLOW_FALLING;
case "Desert Outpost" -> effectType = PotionEffectType.FIRE_RESISTANCE;
```

If the variant stored in the database still has the `(needs...)` suffix (e.g., `"Forest Outpost (needs 10+ Logs in chest, Axe in chest)"`), none of the cases match and no buff is applied.

**Verification needed:** Confirm that `handleRegisteredBuildingIntegrity()` in `SqlObjectiveService.java` (line ~1349) properly updates the variant in the DB to the clean name (e.g., `"Forest Outpost"`) when the player adds the missing items. From code review, this DOES happen at line 1359 via `upsertRegisteredBuilding()` which writes `result.variant()` (the clean name from the latest detection scan).

**Defensive fix:** Change the switch to use `startsWith()` instead of exact matches:

```java
if (variant.startsWith("Mining Outpost")) effectType = PotionEffectType.LUCK;
else if (variant.startsWith("Fishing Outpost")) effectType = PotionEffectType.LUCK;
// etc.
```

This would handle edge cases where the variant name hasn't been updated yet.

### Proposed Fix Summary

1. **Primary fix:** Add `onPlayerExitBuilding(player, building)` call in `pruneTrackedPlayers()` before removing the player
2. **Defensive fix:** Change `applyOutpostVariantBuff()` switch to use `startsWith()` matching instead of exact string equality

---

## Implementation Priority

| Fix | Priority | Risk | Effort |
|-----|----------|------|--------|
| **Issue 3: Exit buff not firing** | 🔴 High | Low — single line change | 5 min |
| **Issue 1: Tree log exploitation** | 🟡 Medium | Low — well-contained change | 10 min |
| **Issue 2: /obj buildings section** | 🟡 Medium | Low — UI rearrangement | 15 min |

---

## Files to Edit

| File | Changes |
|------|---------|
| `BuildingBenefitManager.java` | Fix `pruneTrackedPlayers()` to call exit callback; optionally fix variant name matching |
| `BuildingDetector.java` | Exclude unstripped LOG/WOOD from `isConstructionMaterial()` |
| `ObjectiveCommand.java` | Move "YOUR BUILDINGS" section before early returns |

