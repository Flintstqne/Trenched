# Building Detection Plan — Player-Placed Block Tracking

**Created:** March 12, 2026  
**Status:** ✅ Implemented (Phase 1–4), Phase 5 (monitoring/tuning) pending

---

## Overview

This document consolidates the building bugfix plan and the player-placed block tracking system design. The goal is to make structure detection reliable by distinguishing player-built structures from natural terrain (trees, terrain features, etc.).

---

## Part 1: Bugs Fixed

### Bug 1: Natural Tree Logs Counted as Construction Material

**Root Cause:** `BuildingDetector.isConstructionMaterial()` did not exclude `LOG` or `WOOD` blocks. Natural tree trunks provided enough structural mass + footprint to pass the detection threshold with just a chest + crafting table nearby.

**Fix Applied:** Added exclusion for unstripped LOG and WOOD blocks in `isConstructionMaterial()`:
- Unstripped logs (`OAK_LOG`, `SPRUCE_LOG`, etc.) → excluded
- Stripped logs (`STRIPPED_OAK_LOG`, etc.) → still count (stripping requires player action)
- This is the **material-based fallback** used when player-placed tracking is unavailable

**File:** `BuildingDetector.java`

---

### Bug 2: `/obj` Doesn't Show Buildings Section

**Root Cause:** The "YOUR BUILDINGS" section in `ObjectiveCommand.showObjectives()` was placed after multiple early-return paths (region not adjacent, no objectives, defender in owned region). Players with registered buildings in their region could never see them.

**Fix Applied:** Extracted building display into `showRegisteredBuildings(player, regionId, team)` method and call it **before** any early returns. Buildings always display regardless of region state or adjacency.

**File:** `ObjectiveCommand.java`

---

### Bug 3: Outpost Variant Buff Not Applied on Exit

**Root Cause:** `BuildingBenefitManager.pruneTrackedPlayers()` silently removed players from the `playersInBuildings` map when they left the building bounds — **without calling `onPlayerExitBuilding()`**. The exit callback (where `applyOutpostVariantBuff()` lives) never fired.

**Fix Applied:**
1. Added `onPlayerExitBuilding(player, building)` call in `pruneTrackedPlayers()` **before** removing the player
2. Changed variant name matching from exact `switch` to `startsWith()` to handle edge cases where variant name still has `(needs...)` suffix

**File:** `BuildingBenefitManager.java`

---

## Part 2: Player-Placed Block Tracking System

### Problem Statement

Material-based filtering (excluding logs, dirt, stone, etc.) helps but is imperfect:
- **Cobblestone walls** placed by players look the same as cobblestone terrain
- **Planks** from naturally generated structures can't be distinguished
- **Stone bricks** in strongholds look identical to player-placed ones
- Players building near terrain features get inconsistent detection results

### Solution: Track Which Blocks Were Placed by Players

Only blocks that a player physically placed count toward building detection. Natural terrain is invisible to the scanner.

### Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Event Layer (Main Thread)              │
│                                                          │
│  BlockPlaceEvent ──→ isNearBuildingObjective() ──→ track │
│  BlockBreakEvent ──→ untrack                             │
│  EntityExplodeEvent ──→ untrack each block               │
└────────────┬─────────────────────────────────────────────┘
             │ ConcurrentLinkedQueue (lock-free)
             ▼
┌──────────────────────────────────────────────────────────┐
│              PlacedBlockTracker (Cache Layer)             │
│                                                          │
│  regionCache: Map<String, Set<Long>>                     │
│  - Key: regionId                                         │
│  - Value: Set of packed (x,y,z) coordinates              │
│  - O(1) contains() during scan loops                     │
│                                                          │
│  pendingWrites: ConcurrentLinkedQueue<PlacedBlockRecord> │
│  pendingDeletes: ConcurrentLinkedQueue<DeleteRecord>     │
└────────────┬─────────────────────────────────────────────┘
             │ Async flush every 10 seconds
             ▼
┌──────────────────────────────────────────────────────────┐
│              PlacedBlockDb (SQLite Persistence)           │
│                                                          │
│  placed_blocks table:                                    │
│    x, y, z (PK), player_uuid, team, region_id, placed_at│
│                                                          │
│  WAL mode + batch transactions for performance           │
└──────────────────────────────────────────────────────────┘
```

### Coordinate Packing

Coordinates are packed into a single `long` for O(1) `HashSet.contains()` with zero allocation:

```
Bits  0-25: X coordinate (26 bits, ±33M range)
Bits 26-37: Y coordinate (12 bits, ±2048 range — covers MC build height)
Bits 38-63: Z coordinate (26 bits, ±33M range)
```

### Scoped Tracking

**Not every block in the world is tracked.** Only blocks placed within the detection radius of:
- Active building objectives (outpost, watchtower, garrison)
- Registered buildings (for integrity rechecks)

This keeps storage to a few thousand rows instead of millions.

### Inherently Player-Placed Materials

Some blocks **never occur naturally** and don't need tracking:
- Storage: chests, barrels, shulker boxes
- Utility: crafting tables, furnaces, anvils, brewing stands, etc.
- Entrance: doors, trapdoors, fence gates
- Access: ladders, scaffolding
- Decorative: torches, lanterns, beds, banners

These are always included in scans regardless of tracking state.

### Fallback Behavior

When tracking is unavailable (disabled, region not loaded, first boot), the system falls back to material-based filtering — the current behavior with LOG/WOOD exclusion. No builds break on cold start.

### Performance Budget

| Operation | Thread | Cost | Frequency |
|-----------|--------|------|-----------|
| `trackBlock()` | Main | O(1) HashSet.add + queue.offer | Per block placed |
| `untrackBlock()` | Main | O(1) HashSet.remove + queue.offer | Per block broken |
| `isNearBuildingObjective()` | Main | O(objectives + buildings) | Per block placed |
| `packCoord()` during scan | Main | O(1) bitwise math | Per scanned block |
| `Set.contains()` during scan | Main | O(1) | Per scanned block |
| `flush()` | Async | Batch SQL transaction | Every 10 seconds |
| `cleanup()` | Async | Region-level delete | Every 5 minutes |
| `loadRegion()` | Main (once) | DB read → HashSet build | On objective spawn |

---

## Part 3: Implementation Files

### New Files Created
| File | Purpose |
|------|---------|
| `PlacedBlockDb.java` | SQLite persistence layer — batch insert/delete, region load/cleanup |
| `PlacedBlockTracker.java` | In-memory cache with async flush — coordinate packing, scoped tracking |

### Modified Files
| File | Changes |
|------|---------|
| `BuildingDetector.java` | Added tracker reference, scan loop filters by player-placed, `isInherentlyPlayerPlaced()`, excluded unstripped LOG/WOOD |
| `BuildingBenefitManager.java` | Fixed `pruneTrackedPlayers()` exit callback, `startsWith()` variant matching |
| `ObjectiveCommand.java` | Moved buildings section before early returns via `showRegisteredBuildings()` |
| `ObjectiveListener.java` | Added block place/break/explode hooks for tracking |
| `SqlObjectiveService.java` | Added `setPlacedBlockTracker()` passthrough to BuildingDetector |
| `Trenched.java` | Wired PlacedBlockDb, PlacedBlockTracker, startup loading, shutdown flush |
| `ConfigManager.java` | Added `isPlayerPlacedTrackingEnabled()`, flush interval, cleanup interval |
| `config.yml` | Added `player-placed-tracking`, `player-placed-flush-interval`, `player-placed-cleanup-interval` |

---

## Part 4: Configuration

```yaml
regions:
  objectives:
    # Player-placed block tracking for structure detection
    # When enabled, only blocks placed by players count toward buildings
    player-placed-tracking: true
    # How often to flush tracked blocks to database (seconds)
    player-placed-flush-interval: 10
    # How often to clean up orphaned tracking data (minutes)
    player-placed-cleanup-interval: 5
```

### Disabling Tracking

Set `player-placed-tracking: false` to revert to material-based detection only. The LOG/WOOD exclusion still applies as a baseline filter.

---

## Part 5: Testing Checklist

### Bugfix Verification
- [ ] Build outpost near tree → tree logs should NOT count toward structure
- [ ] Run `/obj` in owned region → buildings section still shows
- [ ] Run `/obj` in non-adjacent region → buildings section still shows  
- [ ] Leave completed variant outpost → variant buff applied (check potion effects)

### Player-Placed Tracking
- [ ] Place blocks near building objective → debug logs show "Using player-placed block filtering"
- [ ] Place cobblestone near objective → counts in scan
- [ ] Natural cobblestone near objective → excluded from scan
- [ ] Place chest/crafting table → always counted (inherently player-placed)
- [ ] Break a tracked block → untracked, next scan excludes it
- [ ] TNT explosion near building → exploded blocks untracked
- [ ] Server restart → tracking data persists and reloads
- [ ] Disable tracking in config → fallback to material-based filtering

### Performance
- [ ] No tick lag during normal building (< 1ms per block place event)
- [ ] Flush task runs smoothly on async thread (check console for errors)
- [ ] Cleanup task removes orphaned regions (check console logs)

---

## Part 6: Future Considerations

1. **Region capture reset:** When a region is captured, call `placedBlockTracker.clearRegion(regionId)` to reset tracking for the new team's builds. Not yet wired — needs integration with region capture flow.

2. **Round reset:** Call `placedBlockTracker.clearAll()` when a round ends to clean the slate. Not yet wired — needs integration with round end flow.

3. **Performance monitoring:** If flush times exceed 50ms, consider sharding the DB by region or increasing flush intervals.

4. **Retroactive tracking:** Currently, blocks placed before an objective spawns are not tracked. The inherently-player-placed whitelist and material-based fallback handle most cases. If this becomes a problem, consider a one-time scan that classifies existing blocks by material type.

