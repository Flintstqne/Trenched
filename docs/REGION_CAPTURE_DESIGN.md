# Region Capture & Claiming System Design

## Overview

The region capture system creates a warfare experience focused on **tactical entrenchment**, **supply lines**, and **strategic objectives**. Teams must balance offense, defense, and logistics to control the map.

---

## Core Concepts

### Region Grid
- Map is divided into a 4x4 grid of regions (A1-D4)
- Each region is 512x512 blocks
- Total map size: 2048x2048 blocks
- Each team starts with 1 home region (opposite corners)

```
    1       2       3       4
  +-------+-------+-------+-------+
A | RED   |       |       |       |
  | HOME  |       |       |       |
  +-------+-------+-------+-------+
B |       |       |       |       |
  |       |       |       |       |
  +-------+-------+-------+-------+
C |       |       |       |       |
  |       |       |       |       |
  +-------+-------+-------+-------+
D |       |       |       |  BLUE |
  |       |       |       |  HOME |
  +-------+-------+-------+-------+
```

### Region States
| State | Description |
|-------|-------------|
| `NEUTRAL` | Unclaimed, can be claimed by either team |
| `OWNED` | Belongs to a team, generates supply |
| `CONTESTED` | Being actively attacked by enemy |
| `FORTIFIED` | Recently captured, immune to attack |
| `PROTECTED` | Home region, cannot be captured |

### Strict Adjacency Rules
- Can only capture/claim regions directly adjacent (N/S/E/W) to owned regions
- Creates natural front lines and prevents "island" captures
- **Supply lines matter**: Isolated regions lose benefits

```
Can capture (X) from RED:
  +---+---+---+
  |   | X |   |
  +---+---+---+
  | X |RED| X |
  +---+---+---+
  |   | X |   |
  +---+---+---+
```

---

## Influence Point System

Regions are captured/claimed by accumulating **Influence Points (IP)**. Different actions generate IP at different rates.

### Points Required
| Region Type | Points to Capture |
|-------------|-------------------|
| Neutral Region | 500 IP |
| Enemy Region | 1000 IP |
| Enemy Region (Fortified) | Cannot capture |

---

## Capturing Enemy Regions

Enemy regions require **aggressive actions** to generate influence.

### Action Point Values

| Action | Points | Cooldown/Limit | Notes |
|--------|--------|----------------|-------|
| **Kill Enemy Player** | 50 IP | - | Must be in the region |
| **Complete Raid Objective** | 150 IP | 5 min cooldown | See objectives below |
| **Place Team Banner** | 25 IP | 1 per player per region | Marks territory |
| **Destroy Enemy Structure** | 10-30 IP | - | Based on size detected |
| **Mine Enemy Blocks** | 1 IP | 5/second cap | Player-placed blocks only |

### Enemy Region Objectives (Raids)

These are specific tasks that spawn or can be completed in enemy territory:

| Objective | Points | Description |
|-----------|--------|-------------|
| **Destroy Supply Cache** | 150 IP | Find and destroy hidden chest with supplies |
| **Assassinate Commander** | 200 IP | Kill a designated "commander" player (highest kills) |
| **Sabotage Defenses** | 100 IP | Destroy 50+ blocks of walls/fortifications |
| **Plant Explosive** | 175 IP | Place TNT at marked location, defend for 30s |
| **Capture Intel** | 125 IP | Retrieve item from enemy base, return to your territory |
| **Hold Ground** | 100 IP | Stay in region center for 60 seconds (contested) |

### Anti-Spam Mechanics
- **Kill farming prevention**: Same player kill = 50% reduced points (stacks)
- **Block mining cap**: Max 5 IP per second from mining
- **Objective cooldowns**: 5 minutes between completing same objective type
- **Banner limit**: 1 banner per player per region (removing enemy banner = 15 IP)

---

## Claiming Neutral Regions

Neutral regions reward **building and entrenchment** over pure aggression.

### Action Point Values

| Action | Points | Cooldown/Limit | Notes |
|--------|--------|----------------|-------|
| **Complete Settlement Objective** | 100-200 IP | 3 min cooldown | See objectives below |
| **Build Structure** | 5-50 IP | Evaluated every 30s | Based on structure scoring |
| **Place Defensive Blocks** | 2 IP | 10/second cap | Walls, fences, etc. |
| **Place Team Banner** | 25 IP | 1 per player | Claims intention |
| **Set Up Workstation** | 15 IP | Per unique type | Crafting, furnace, etc. |
| **Light Area (Torches)** | 1 IP | - | Prevents mob spawns |
| **Clear Hostiles** | 5 IP | Per mob | Securing the area |

### Neutral Region Objectives (Settlement)

| Objective | Points | Description |
|-----------|--------|-------------|
| **Establish Outpost** | 200 IP | Build structure with bed, chest, crafting table |
| **Secure Perimeter** | 150 IP | Build 100+ blocks of walls (detected) |
| **Supply Route** | 125 IP | Place 64+ path/road blocks connecting to owned region |
| **Watchtower** | 100 IP | Build structure 15+ blocks tall with line of sight |
| **Resource Depot** | 150 IP | Place 4+ storage containers with 100+ items total |
| **Garrison Quarters** | 175 IP | Build enclosed room with 3+ beds |

### Structure Detection System

The system evaluates builds every 30 seconds and awards points based on:

```
Structure Score = (Blocks Placed × Material Multiplier) + Enclosure Bonus + Height Bonus

Material Multipliers:
- Dirt/Wood: 1.0x
- Stone/Cobble: 1.5x
- Brick/Stone Brick: 2.0x
- Obsidian: 3.0x
- Iron/Metal Blocks: 2.5x

Bonuses:
- Enclosed space (walls + roof): +20 points
- Multi-story (per floor): +15 points
- Defensive features (walls 3+ high): +10 points
```

**Anti-Spam**: 
- Only player-placed blocks count
- Pillar spam detected and ignored (needs horizontal variety)
- Same block place/break = no points
- Minimum 20 blocks for any structure points

---

## Supply Line System

Supply lines are **physical roads** built by players using path blocks. Roads must physically connect regions to your home base to provide supply benefits.

### Road Mechanics

#### Building Roads
- Roads are built using configurable **path blocks** (default: `DIRT_PATH`, `GRAVEL`, `COBBLESTONE`)
- Path blocks must be **adjacent to each other** within a configurable radius (default: 2 blocks)
- This allows for textured/decorated roads while maintaining connectivity
- Roads are automatically detected and tracked by the system

```
Valid Road Patterns (radius 2):
[P] = Path block, [ ] = Any block

Linear:        Textured:           Wide:
[P][P][P][P]   [P][ ][P][ ][P]    [P][P][P]
                                  [P][P][P]
                                  [P][P][P]
```

#### Road Ownership
- The **team that places the majority of path blocks** owns the road segment
- Roads are tracked per-region with ownership data
- Disputed roads (50/50 ownership) provide no supply benefit until resolved

#### Road Connection Requirements
```
HOME ══════ Region A ══════ Region B ══════ Region C
  ↑            ↑               ↑               ↑
  |   [Continuous road]  [Continuous road]  [Road at border]
  |   through entire     through entire     connects to C
  |   region             region
  └── Always supplied (100%)
```

**Key Rules for Continuous Road Supply**:

1. **Border Crossing**: Roads must cross each border (blocks on both sides within adjacency radius)

2. **Continuous Path Through Regions**: For intermediate regions, the road must form a **continuous connected path** from entry border to exit border. Disconnected segments at borders won't work!

3. **Pathfinding Verification**: The system traces road blocks from entry to exit using BFS pathfinding.

4. **Adjacency Radius**: Road blocks must be within 3 blocks of each other (configurable) to count as connected.

**Example**: To supply A3 via A1 → A2 → A3:
- A1 (home): Always supplied
- A2: Must have continuous road from A1/A2 border TO A2/A3 border (spanning the entire region)
- A3: Blocks at the A2/A3 border connect it to the supply chain

**Anti-Cheese**: You cannot place small road segments at borders - the road must actually traverse through each region!

### Supply Benefits

Supply level is determined by road connectivity to home base:

| Supply Status | Respawn Delay | Health Regen | Condition |
|---------------|---------------|--------------|-----------|
| **Supplied** | Normal | Normal | Road connected to home |
| **Partially Supplied** | +5 seconds | Normal | Road damaged but alternative exists |
| **Unsupplied** | +15 seconds | 50% slower | No road connection to home |
| **Isolated** | +30 seconds | 75% slower | No owned adjacent regions |

### Disrupting Supply Lines

#### Destroying Roads
Roads can be disrupted by:
- **Mining path blocks** - Remove the road surface
- **Explosions** - TNT, creepers, etc. destroy road sections
- **Building over** - Placing non-path blocks breaks connectivity

#### Disruption Detection
When a road is disrupted:
1. System detects break in path block adjacency
2. Affected team receives notification:
   ```
   [BlockHole] ⚠ Supply line disrupted in Shadowfen Valley!
   [BlockHole] Downstream regions affected: Iron Hills, Dragon's Rest
   ```
3. System pathfinds to check all downstream regions
4. Any regions that lose ALL road connections become **Unsupplied**

#### Cascade Effect
```
Example - Road destroyed in B2:

Before:                          After:
HOME ═══ A1 ═══ B1 ═══ C1       HOME ═══ A1 ═══ B1    C1 (Unsupplied!)
              ║                               ║
              B2 ═══ C2                      B2 ✖ C2 (Unsupplied!)
              ║                               ║
              B3                              B3 (Unsupplied!)

All regions past the break lose supply!
```

#### Alternative Routes
- Smart players build **redundant roads** through multiple paths
- If one route is destroyed, supply flows through alternatives
- System automatically finds shortest remaining path

```
Redundant Supply Network:
HOME ═══ A1 ═══ B1 ═══ C1
         ║      ║      ║
         A2 ═══ B2 ═══ C2

If B1-C1 is destroyed, C1 still supplied via B1→B2→C2→C1
```

### Road Repair
- Simply place new path blocks to restore connectivity
- Team receives notification when supply is restored:
  ```
  [BlockHole] ✓ Supply line restored to Iron Hills!
  ```

### Road UI/Indicators
- **BlueMap**: Roads shown as colored lines (team color)
- **Action Bar**: Shows supply status when entering region
- **Particles**: Optional particle trail along roads (configurable)

---

## Defense Mechanics

### Fortification Period
- Newly captured regions are **Fortified** for 10 minutes (configurable)
- Cannot be attacked during fortification
- Gives time to establish defenses and build roads
- Visual indicator on BlueMap

### Home Region Protection
- Home regions have permanent protection status
- Can only be attacked when team owns **≤2 regions** (configurable)
- Home is always at 100% supply

---

## Objective System Details

### How Objectives Spawn

**Enemy Regions (Raid Objectives):**
- 1-2 active objectives per enemy region
- Refresh every 10 minutes if not completed
- Marked with particle effects/beacons for attackers
- Defenders see "Region under threat" warnings

**Neutral Regions (Settlement Objectives):**
- All objectives available simultaneously
- Complete in any order
- Progress persists (partial builds count)
- First team to complete gets the points

### Objective UI

**Action Bar Display:**
```
⚔ RAID: Destroy Supply Cache [150 IP] - 45 blocks north
```

**Boss Bar (when near objective):**
```
Sabotage Defenses: 34/50 blocks destroyed
[████████████░░░░░░░░] 68%
```

### Objective Markers
- **Beam of light** at objective location (team-colored)
- **Compass points** to nearest objective
- **BlueMap icons** showing objective types

---

## Visual Feedback

### Region Status on Scoreboard
```
Region: Shadowfen Valley
Owner: Red Team
Status: CONTESTED (Blue attacking)
Defense: 125% (fortified)
Supply: 80%
Your IP: 234/1000
```

### Boss Bar During Capture
```
⚔ Attacking Shadowfen Valley ⚔
Red: [████████████████░░░░] 823 IP
Blue: [██████░░░░░░░░░░░░░░] 312 IP
```

### Notifications

**To Attackers:**
```
[BlockHole] ⚔ Raid started on Shadowfen Valley!
[BlockHole] +50 IP - Enemy player killed!
[BlockHole] +150 IP - Supply Cache destroyed!
[BlockHole] 🎉 Shadowfen Valley captured! (+10 min fortification)
```

**To Defenders:**
```
[BlockHole] ⚠ Shadowfen Valley is under attack!
[BlockHole] ⚠ Enemy IP: 500/1000 - Defend now!
[BlockHole] ❌ Shadowfen Valley has fallen!
```

---

## Win Conditions

### Primary: Territory Control
At the end of Phase 3:
- Team with most regions wins
- Tie-breaker: Total IP earned during round

### Alternative: Total Domination
- Capture ALL enemy regions including home
- Instant win at any phase

### Home Region Rules
- Home can only be attacked when team owns ≤2 regions
- Home has permanent 100% defense bonus
- Losing home = team eliminated

---

## Database Schema

```sql
CREATE TABLE region_status (
  region_id TEXT NOT NULL,           -- "A1", "B2", etc.
  round_id INTEGER NOT NULL,
  owner_team TEXT,                   -- "red", "blue", or NULL
  status TEXT DEFAULT 'NEUTRAL',     -- NEUTRAL, OWNED, CONTESTED, FORTIFIED
  red_influence REAL DEFAULT 0,      -- Red team's IP
  blue_influence REAL DEFAULT 0,     -- Blue team's IP
  fortified_until INTEGER,           -- Timestamp when fortification ends
  owned_since INTEGER,               -- Timestamp when captured
  times_captured INTEGER DEFAULT 0,
  PRIMARY KEY(region_id, round_id),
  FOREIGN KEY(round_id) REFERENCES rounds(round_id)
);

-- Road segments for supply line tracking
CREATE TABLE road_segments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id INTEGER NOT NULL,
  from_region TEXT NOT NULL,         -- Starting region "A1"
  to_region TEXT NOT NULL,           -- Ending region "A2"
  owner_team TEXT,                   -- "red", "blue", or NULL (disputed)
  path_block_count INTEGER DEFAULT 0,
  red_blocks INTEGER DEFAULT 0,      -- Count of red-placed blocks
  blue_blocks INTEGER DEFAULT 0,     -- Count of blue-placed blocks
  is_connected BOOLEAN DEFAULT 0,    -- Whether path is fully connected
  last_checked INTEGER,              -- Timestamp of last connectivity check
  UNIQUE(round_id, from_region, to_region),
  FOREIGN KEY(round_id) REFERENCES rounds(round_id)
);

-- Individual path blocks for detailed tracking
CREATE TABLE road_blocks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id INTEGER NOT NULL,
  region_id TEXT NOT NULL,
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  z INTEGER NOT NULL,
  placed_by TEXT NOT NULL,           -- Player UUID
  team TEXT NOT NULL,                -- "red" or "blue"
  placed_at INTEGER,
  UNIQUE(round_id, x, y, z),
  FOREIGN KEY(round_id) REFERENCES rounds(round_id)
);

-- Supply status cache (updated when roads change)
CREATE TABLE supply_status (
  region_id TEXT NOT NULL,
  round_id INTEGER NOT NULL,
  team TEXT NOT NULL,
  supply_level TEXT DEFAULT 'UNSUPPLIED', -- SUPPLIED, PARTIAL, UNSUPPLIED, ISOLATED
  connected_to_home BOOLEAN DEFAULT 0,
  shortest_path_length INTEGER,      -- Hops to home via roads
  last_updated INTEGER,
  PRIMARY KEY(region_id, round_id, team),
  FOREIGN KEY(round_id) REFERENCES rounds(round_id)
);

CREATE TABLE region_objectives (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  region_id TEXT NOT NULL,
  round_id INTEGER NOT NULL,
  objective_type TEXT NOT NULL,      -- 'raid_cache', 'settlement_outpost', etc.
  status TEXT DEFAULT 'ACTIVE',      -- ACTIVE, COMPLETED, EXPIRED
  location_x INTEGER,
  location_y INTEGER,
  location_z INTEGER,
  progress REAL DEFAULT 0,           -- For multi-step objectives
  completed_by TEXT,                 -- Team that completed
  created_at INTEGER,
  completed_at INTEGER,
  FOREIGN KEY(round_id) REFERENCES rounds(round_id)
);

CREATE TABLE player_region_stats (
  player_uuid TEXT NOT NULL,
  region_id TEXT NOT NULL,
  round_id INTEGER NOT NULL,
  influence_earned INTEGER DEFAULT 0,
  kills INTEGER DEFAULT 0,
  blocks_placed INTEGER DEFAULT 0,
  blocks_mined INTEGER DEFAULT 0,
  objectives_completed INTEGER DEFAULT 0,
  road_blocks_placed INTEGER DEFAULT 0,
  road_blocks_destroyed INTEGER DEFAULT 0,
  PRIMARY KEY(player_uuid, region_id, round_id)
);
```

---

## Config Example

```yaml
regions:
  # Influence thresholds
  influence:
    neutral-capture: 500
    enemy-capture: 1000
    
  # Enemy region actions
  enemy-actions:
    kill-points: 50
    kill-same-player-reduction: 0.5
    banner-place: 25
    banner-remove-enemy: 15
    structure-destroy-per-block: 2
    mine-enemy-blocks: 1
    mine-cap-per-second: 5
    
  # Neutral region actions  
  neutral-actions:
    banner-place: 25
    defensive-block: 2
    defensive-cap-per-second: 10
    workstation: 15
    torch: 1
    mob-kill: 5
    structure-base-points: 5
    structure-max-points: 50
    
  # Objectives
  objectives:
    raid-cooldown-minutes: 5
    settlement-cooldown-minutes: 3
    objectives-per-region: 2
    refresh-minutes: 10
    
  # Defense
  defense:
    fortification-minutes: 10
    
  # Supply lines (physical roads)
  supply:
    # Valid path block types for roads
    path-blocks:
      - DIRT_PATH
      - GRAVEL
      - COBBLESTONE
      - STONE_BRICKS
      - POLISHED_ANDESITE
    # Maximum distance between path blocks to count as connected
    adjacency-radius: 2
    # Respawn delay penalties
    partial-supply-respawn-delay: 5    # +5 seconds
    unsupplied-respawn-delay: 15       # +15 seconds  
    isolated-respawn-delay: 30         # +30 seconds
    # Health regen multipliers (1.0 = normal)
    unsupplied-health-regen: 0.5       # 50% slower
    isolated-health-regen: 0.25        # 75% slower
    
  # Adjacency
  adjacency:
    strict: true                 # Only N/S/E/W
    allow-diagonal: false
    
  # Home regions
  home:
    red: "A1"
    blue: "D4"
    min-regions-to-attack: 2     # Enemy must have ≤2 regions

# Win conditions
win:
  type: "territory"              # territory, domination
  domination-instant-win: true
  
# Notifications
notifications:
  attack-warning: true
  progress-interval: 250         # Notify at 250, 500, 750 IP
  capture-broadcast: true
  objective-hints: true
  supply-disrupted: true         # Notify when supply lines broken
  supply-restored: true          # Notify when supply lines repaired
```

---

## Implementation Priority

### Phase 1: Core Influence System
1. Region status tracking (IP for each team)
2. Basic actions: kills, block place/mine, banners
3. Capture logic with thresholds
4. Adjacency enforcement
5. Basic notifications

### Phase 2: Objectives
1. Objective spawning system
2. Raid objectives (destroy, assassinate, sabotage)
3. Settlement objectives (outpost, perimeter, depot)
4. Objective UI (boss bar, markers)

### Phase 3: Defense & Supply
1. Fortification period
2. Road block tracking system
3. Road connectivity detection (adjacency-based)
4. Supply status calculation (pathfinding to home)
5. Supply line disruption notifications
6. Respawn delay penalties
7. Health regen penalties

### Phase 4: Polish
1. BlueMap integration (contested, supply lines)
2. Statistics tracking
3. Particle effects
4. Sound design
5. Balance tuning

---

## Summary

This system creates warfare that rewards:
- **Strategic thinking**: Supply lines and adjacency matter
- **Team coordination**: Objectives require planning
- **Building skills**: Roads and entrenchment provide real benefits
- **Combat prowess**: Kills and raids are valuable
- **Logistics**: Building and maintaining supply roads is crucial

The anti-spam mechanics prevent grinding while the objective system provides clear goals for both attackers and settlers.

