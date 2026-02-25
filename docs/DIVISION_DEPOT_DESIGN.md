# Division Depot System - Implementation Design

**Created:** February 24, 2026  
**Status:** Planning Phase

---

## 🎯 Overview

The Division Depot is a shared storage system for divisions, functioning like an Ender Chest but with physical vulnerability mechanics tied to region control. Unlike regular containers that become fully accessible when a region is captured, Division Depots require a special raid tool to access enemy contents.

---

## 📋 Core Concepts

### Key Differences from Regular Containers

| Feature | Regular Containers | Division Depots |
|---------|-------------------|-----------------|
| Protection | Protected in owned regions | Protected in owned regions |
| On Region Capture | Enemies can fully access | Enemies CANNOT access |
| Enemy Interaction | Open/break freely | Opens YOUR division's storage |
| Raiding | Direct access | Requires special raid tool |
| Contents | Per-container | Shared across all division depots |

### Design Philosophy

1. **Shared Storage**: All Division Depots for a division access the same virtual inventory
2. **Physical Access Points**: Must place depot blocks in the world to use
3. **Not 100% Safe**: Encourages strategic placement, not frontline territories
4. **Effort-Based Raiding**: Enemies must capture region AND use special tool
5. **Partial Loot**: Raiding drops some items, not everything

---

## 🔧 Technical Components

### 1. Division Depot Block

**Block Type:** Custom-textured block using Copper Block with NBT data

```yaml
Material: COPPER_BLOCK (or custom via ItemsAdder/Oraxen if available)
Custom NBT:
  - depot_type: "division_depot"
  - division_id: <int>
  - team: "red" | "blue"
  - placed_by: <UUID>
  - placed_at: <timestamp>
```

**Visual Indicators:**
- Particle effects when placed (team-colored)
- Glowing outline when vulnerable (region captured)
- Custom name visible to players: "[DIVISION] Division Depot"

### 2. Database Schema

```sql
-- Division Depot Storage (virtual inventory)
CREATE TABLE division_depot_storage (
    storage_id INTEGER PRIMARY KEY AUTOINCREMENT,
    division_id INTEGER NOT NULL,
    round_id INTEGER NOT NULL,
    slot INTEGER NOT NULL,          -- 0-53 (6 rows × 9 columns)
    item_data BLOB,                  -- Serialized ItemStack
    UNIQUE(division_id, round_id, slot),
    FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
);

-- Physical Depot Block Locations
CREATE TABLE division_depot_locations (
    location_id INTEGER PRIMARY KEY AUTOINCREMENT,
    division_id INTEGER NOT NULL,
    round_id INTEGER NOT NULL,
    world TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    placed_by TEXT NOT NULL,         -- UUID
    placed_at INTEGER NOT NULL,      -- timestamp
    region_id TEXT NOT NULL,         -- For vulnerability checks
    UNIQUE(world, x, y, z),
    FOREIGN KEY(division_id) REFERENCES divisions(division_id) ON DELETE CASCADE
);

-- Depot Raid History (for analytics/logging)
CREATE TABLE division_depot_raids (
    raid_id INTEGER PRIMARY KEY AUTOINCREMENT,
    depot_location_id INTEGER NOT NULL,
    raider_uuid TEXT NOT NULL,
    raider_division_id INTEGER,
    items_dropped INTEGER NOT NULL,  -- Count of item stacks dropped
    raided_at INTEGER NOT NULL,
    FOREIGN KEY(depot_location_id) REFERENCES division_depot_locations(location_id)
);
```

### 3. Service Layer

**New File:** `src/main/java/org/flintstqne/entrenched/DivisionLogic/DepotService.java`

```java
public interface DepotService {
    
    // Depot Block Management
    boolean placeDepot(Player player, Location location);
    boolean breakDepot(Player player, Location location);
    Optional<DepotLocation> getDepotAt(Location location);
    List<DepotLocation> getDepotsForDivision(int divisionId);
    List<DepotLocation> getDepotsInRegion(String regionId);
    
    // Storage Access
    Inventory openDepotStorage(Player player);  // Opens player's division storage
    ItemStack[] getDepotContents(int divisionId);
    void setDepotContents(int divisionId, ItemStack[] contents);
    
    // Vulnerability & Raiding
    boolean isDepotVulnerable(DepotLocation depot);
    RaidResult raidDepot(Player raider, DepotLocation depot);
    int calculateLootDropCount(int divisionId);  // Based on storage contents
    
    // Cleanup
    void clearDepotsForRound(int roundId);
    void removeDepotsForDivision(int divisionId);
}

public record DepotLocation(
    int locationId,
    int divisionId,
    int roundId,
    String world,
    int x, int y, int z,
    UUID placedBy,
    long placedAt,
    String regionId
) {}

public enum RaidResult {
    SUCCESS,              // Loot dropped, depot destroyed
    NOT_VULNERABLE,       // Region still owned by depot team
    NO_TOOL,              // Player doesn't have raid tool
    WRONG_TEAM,           // Can't raid own team's depots
    ALREADY_RAIDING,      // Someone else is raiding
    COOLDOWN              // Recently raided, on cooldown
}
```

### 4. Raid Tool

**Item:** "Division Raid Tool" - Special multipurpose tool for raiding captured enemy depots

```yaml
Material: GOLDEN_HOE (or custom texture)
Display Name: "§c§lDivision Raid Tool"
Lore:
  - "§7Use on enemy Division Depots"
  - "§7in captured territories to raid"
  - "§7their division's storage."
  - ""
  - "§eRight-click on vulnerable depot"
  - "§eto begin raiding process."
Custom NBT:
  - raid_tool: true
```

**Obtaining the Tool:**
- Option A: Craftable (expensive recipe)
- Option B: Awarded upon region capture
- Option C: Purchasable with merit tokens
- **Recommended:** Option A - Craftable

**Crafting Recipe:**
```
[IRON_BLOCK]  [DIAMOND]     [IRON_BLOCK]
[   STICK  ]  [GOLD_BLOCK]  [   STICK  ]
[   STICK  ]  [  COPPER  ]  [   STICK  ]
```

### 5. Listener Components

**New File:** `src/main/java/org/flintstqne/entrenched/DivisionLogic/DepotListener.java`

```java
public class DepotListener implements Listener {
    
    // Block placement - detect depot block placement
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        // Check if item has depot NBT
        // Validate player has division
        // Validate placement location (not too close to other depots?)
        // Register depot in database
        // Show particles
    }
    
    // Block break - prevent breaking unless owner or raiding
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        // Check if block is depot
        // If owner's team: allow, remove from DB
        // If enemy: block (must use raid tool)
    }
    
    // Interaction - open appropriate inventory
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Check if right-click on depot block
        // If own division: open shared storage
        // If same team, different division: deny
        // If enemy (not vulnerable): open own division storage (if has one)
        // If enemy (vulnerable): prompt to use raid tool
    }
    
    // Inventory close - save storage contents
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // If depot inventory, save contents to database
    }
}
```

### 6. Integration with Existing Systems

#### ContainerProtectionListener Updates
- Add exception handling for Division Depot blocks
- Depot blocks bypass normal container protection logic
- Direct all depot interactions to DepotListener

#### RegionService Integration
- When region is captured, mark depots in that region as "vulnerable"
- Notify division when their depot becomes vulnerable
- Check depot vulnerability status based on region ownership

#### DivisionService Integration
- On division disband: remove all depots, drop storage contents
- On division creation: initialize empty storage
- Track depot count per division (limit?)

---

## 🎮 Gameplay Flow

### Placing a Depot

1. Player crafts "Division Depot" item
2. Player right-clicks to place (like placing a chest)
3. System validates:
   - Player has a division
   - Not too close to other depots (configurable radius, e.g., 32 blocks)
   - In a region owned or contested by player's team (not deep enemy territory)
4. Depot block placed with particles
5. All division members notified: "Division depot placed at [coords] in [region]"

### Accessing Storage

1. Player right-clicks depot block
2. System checks player's division
3. Opens 54-slot inventory (like double chest)
4. Changes saved on inventory close
5. Multiple players can access simultaneously (changes merge)

### Depot Becomes Vulnerable

1. Enemy team captures the region containing depot
2. System marks depot as "vulnerable"
3. Depot emits warning particles (red glow)
4. Division notification: "⚠ Your depot in [region] is now VULNERABLE!"
5. Enemies can now raid it

### Raiding a Depot

1. Enemy player approaches vulnerable depot
2. Player equips Raid Tool
3. Right-click depot with tool
4. 5-second channel (can be interrupted by damage)
5. On completion:
   - Depot block destroyed
   - 30% of stored items drop on ground (configurable)
   - Depot removed from database
   - Division notification: "🚨 Your depot was raided! [X] items lost!"

---

## ⚙️ Configuration

Add to `config.yml`:

```yaml
# Division Depot Settings
division-depots:
  # Enable division depot system
  enabled: true
  
  # Storage size (slots, max 54 for double chest)
  storage-size: 54
  
  # Maximum depots per division
  max-per-division: 5
  
  # Minimum distance between depots (blocks)
  min-distance-between-depots: 32
  
  # Raid settings
  raiding:
    # Time to raid a depot (seconds)
    channel-time-seconds: 5
    
    # Percentage of items dropped on raid (0.0 - 1.0)
    loot-drop-percentage: 0.3
    
    # Minimum items to drop (even if percentage = 0)
    min-items-dropped: 3
    
    # Maximum items dropped per raid
    max-items-dropped: 27
    
    # Cooldown between raids on same division's depots (minutes)
    raid-cooldown-minutes: 30
  
  # Placement restrictions
  placement:
    # Allow placing in contested regions
    allow-in-contested: true
    
    # Allow placing in neutral regions (adjacent to owned)
    allow-in-neutral: false
    
    # Allow placing in enemy regions
    allow-in-enemy: false
  
  # Visual settings
  visuals:
    # Particle type for placed depots
    particle-type: "SOUL_FIRE_FLAME"
    
    # Particle type for vulnerable depots
    vulnerable-particle-type: "DRIP_LAVA"
    
    # Show particles
    show-particles: true
    
    # Particle refresh interval (ticks)
    particle-interval-ticks: 40
```

---

## 📁 File Structure

```
src/main/java/org/flintstqne/entrenched/
├── DivisionLogic/
│   ├── Division.java              # (existing)
│   ├── DivisionService.java       # (existing - add depot methods)
│   ├── DivisionDb.java            # (existing - add depot tables)
│   ├── DepotService.java          # NEW - Interface for depot operations
│   ├── SqlDepotService.java       # NEW - Database implementation
│   ├── DepotLocation.java         # NEW - Data record for depot locations
│   ├── DepotListener.java         # NEW - Event handling
│   ├── DepotInventoryHolder.java  # NEW - Custom inventory holder
│   ├── DepotItem.java             # NEW - Factory for depot items
│   └── RaidTool.java              # NEW - Factory for raid tool items
```

---

## 🔄 Implementation Order

### Phase 1: Database & Data Layer
1. Add depot tables to `DivisionDb.java`
2. Create `DepotLocation.java` record
3. Create `DepotService.java` interface
4. Implement `SqlDepotService.java`

### Phase 2: Depot Block & Storage
5. Create `DepotItem.java` (factory for craftable depot block)
6. Create `DepotInventoryHolder.java` (custom inventory for storage)
7. Add crafting recipe registration
8. Implement storage save/load in SqlDepotService

### Phase 3: Listener & Interaction
9. Create `DepotListener.java`
10. Implement placement validation
11. Implement storage opening/closing
12. Implement same-team vs enemy interaction logic

### Phase 4: Vulnerability & Raiding
13. Create `RaidTool.java` (factory for raid tool item)
14. Add crafting recipe for raid tool
15. Implement vulnerability detection (check region ownership)
16. Implement raid channeling mechanic
17. Implement loot drop on successful raid

### Phase 5: Integration & Polish
18. Update `ContainerProtectionListener.java` to exclude depots
19. Add region capture notifications for vulnerable depots
20. Add division notifications (placement, vulnerability, raids)
21. Add particle effects
22. Add configuration options
23. Update DEVELOPMENT_STATUS.md

---

## ⚠️ Edge Cases to Handle

1. **Player leaves division**: Depot remains, other members can still use
2. **Division disbanded**: All depots destroyed, contents dropped
3. **Server restart**: Depot locations and storage persist in database
4. **Region flip-flops**: Vulnerability updates when ownership changes
5. **Depot in home region**: Never vulnerable (home regions can't be captured)
6. **Multiple raiders**: First to complete channel gets loot
7. **Raider dies during channel**: Channel cancelled
8. **Empty depot raided**: Still destroyed, no loot drops
9. **Depot under placed block**: Prevent placement above if blocked?
10. **Division reaches depot limit**: Clear error message

---

## 🧪 Testing Checklist

- [ ] Can craft depot block with correct recipe
- [ ] Depot places correctly with particles
- [ ] Depot opens shared storage for division members
- [ ] Storage persists after inventory close
- [ ] Storage is shared between all division depots
- [ ] Enemy sees their own storage when clicking depot
- [ ] Depot in owned region cannot be raided
- [ ] Depot becomes vulnerable when region is captured
- [ ] Raid tool works on vulnerable depots
- [ ] Raid channel can be interrupted
- [ ] Correct percentage of items drops
- [ ] Depot is destroyed after raid
- [ ] Division receives notifications
- [ ] Config options work correctly
- [ ] Round reset clears all depot data

---

## 📝 Commands

### Player Commands
```
/depot info                     - Show your division's depot info
/depot list                     - List all your division's depot locations
/depot storage                  - View storage contents (without opening)
```

### Admin Commands
```
/admin depot list <division>    - List depots for a division
/admin depot clear <division>   - Remove all depots for a division
/admin depot give <player>      - Give depot block to player
/admin depot givetool <player>  - Give raid tool to player
```

---

## 🔗 Related Systems

- **Container Protection**: Depots are explicitly excluded from normal protection
- **Region Capture**: Triggers vulnerability checks
- **Division System**: Depots tied to division lifecycle
- **Objectives**: "Destroy Supply Cache" does NOT affect depots
- **Merit System**: Could award merits for successful raids

---

## 💭 Future Enhancements

1. **Depot Upgrades**: Larger storage, better protection
2. **Depot Traps**: Explode when raided? Alert defenders?
3. **Depot Camouflage**: Disguise as normal block
4. **Raid Alerts**: Team notification when raid starts (time to defend)
5. **Recovery Items**: Retrieve some items from raided depot within time limit

