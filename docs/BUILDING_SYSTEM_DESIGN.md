# Building System Design

**Last Updated:** March 11, 2026

This document outlines the purposes and mechanics for settlement buildings in Entrenched.

---

## ✅ Implementation Status

All three core building types are **fully implemented** with score-based detection:

| Building | Status | Required Score | Detection File |
|----------|--------|----------------|----------------|
| **Outpost** | ✅ Complete | 70/100 | `BuildingDetector.java` |
| **Watchtower** | ✅ Complete | 65/100 | `BuildingDetector.java` |
| **Garrison** | ✅ Complete | 75/100 | `BuildingDetector.java` |

### How Detection Works

1. **Periodic Scanning**: `tickStructureObjectives()` runs every 1 second
2. **Component Detection**: Flood-fill algorithm finds connected building blocks
3. **Score Calculation**: 5 categories (structure, interior, access, signature, context)
4. **Progress Feedback**: Boss bar shows plain-language checklist and variant upgrade hints
5. **Integrity Checks**: Registered buildings are re-scanned periodically

### Terrain-Aware Outpost Spawning ✅

Outpost objectives spawn at locations matching the **dominant terrain** in the region:

1. **Region Analysis**: Samples 25 points in a 5×5 grid across the region
2. **Terrain Classification**: Each point classified as WATER, FARM, DESERT, MOUNTAIN, UNDERGROUND, FOREST, or GENERIC
3. **Smart Placement**: Picks spawn location matching the most common terrain type
   - Water regions → shoreline spot (solid ground within 10 blocks of water) → Fishing Outpost
   - Plains/meadow → flat grassland → Farm Outpost
   - Desert/badlands → sand/terracotta → Desert Outpost
   - High elevation → mountain peak → Mountain Outpost
   - Ore-rich/deep → near exposed ore → Mining Outpost
   - Forest/taiga/jungle → among trees → Forest Outpost
4. **Logging**: Console shows terrain analysis per region

### Variant Additional Requirements ✅

Outpost variants require **both** the correct environment **and** specific blocks/items inside the building:
- Building detector scans building bounds for furnaces, ladders, wool, cactus, etc.
- Chest contents scanned for pickaxes, fishing rods, hoes, axes, water buckets, logs
- Missing requirements shown in boss bar and `/obj` checklist
- Buffs only apply when all variant requirements are met

### Ambient Building Sounds ✅

Registered buildings play subtle ambient sounds every ~3 seconds for team members within 16 blocks:

| Building | Sound | Volume |
|----------|-------|--------|
| Outpost | Amethyst chime (`BLOCK_AMETHYST_BLOCK_CHIME`) | 0.15 |
| Watchtower | Amethyst shimmer (`BLOCK_AMETHYST_CLUSTER_STEP`) | 0.15 |
| Garrison | Campfire crackle (`BLOCK_CAMPFIRE_CRACKLE`) | 0.15 |

### Debug Commands
- `/admin objective list <region>` - View objective status with building detection results
- `/admin buildings <red|blue|all>` - List all registered buildings per team
- Building detection logs to console with `[Buildings]` prefix when debug mode is enabled

---

## 🏗️ Building Overview

Buildings are structures players construct to complete settlement objectives and provide strategic benefits. Each building type serves a unique purpose in territory control and team coordination.

**All buildings are standalone** - no upgrade paths required. Each building type can be constructed independently.

---

## 🏕️ Outpost

**Objective:** Build a structure containing required components based on the objective's spawn location.

### Base Construction Requirements
| Component | Quantity | Purpose |
|-----------|----------|---------|
| Crafting Table | 1 | Basic workstation |
| Chest | 1+ | Storage |
| Enclosed Structure | Required | Walls and roof |

### Gameplay Benefits

1. **Territory Establishment**
   - First foothold in a neutral region
   - Marks team presence and intent to settle
   - Required for further development

2. **Resource Collection Point**
   - Central storage for gathered materials
   - Team members can deposit/withdraw supplies

3. **Workstation Hub**
   - Provides crafting capability in the field

### Strategic Value
- **Low investment, quick setup** - Good for establishing initial presence
- **Foundation for region control** - First step in settlement
- **No respawn benefits** - Must travel to outpost manually

### Outpost Variants (Terrain-Aware Spawning + Requirements)

The objective system **analyzes the region's terrain** and spawns the outpost at a location matching the dominant terrain type. The variant is then confirmed by checking both the environment AND additional items inside the building:

| Variant | Spawn Terrain | Additional Requirements | Bonus Effect |
|---------|---------------|------------------------|--------------|
| **Mining Outpost** | Near ore veins / underground | Furnace block + Pickaxe in chest | Fortune I buff for 5 min when leaving |
| **Fishing Outpost** | Near water (ocean/river/shoreline) | Fishing Rod in chest | Luck of the Sea buff for 5 min |
| **Farm Outpost** | Plains/flat terrain | Hoe in chest (16+ crops nearby auto-detected) | Saturation buff for 5 min |
| **Forest Outpost** | Forest/taiga/jungle biome | 10+ logs in chest + Axe in chest | Haste I buff for 5 min (wood cutting) |
| **Mountain Outpost** | High elevation (Y > sea+22) | Ladder/vine block + 3+ wool blocks | Slow Falling buff for 5 min |
| **Desert Outpost** | Desert/badlands biome | 3+ cactus blocks + Water bucket in chest | Fire Resistance buff for 5 min |

**How Variant Detection Works (Two Phases):**

1. **Environment Check** — The region terrain determines which variant is *possible* at the spawn location
2. **Requirement Check** — The building detector scans the structure's bounds for specific blocks and chest contents

**Scoring:**
- **Full requirements met** → 5.0 context score + variant buff on leaving
- **Environment only, missing items** → 3.0 context score + NO buff (displayed as e.g., "Mining Outpost (needs Furnace, Pickaxe in chest)")
- **No environment match** → 2.0 context score, "Standard" variant, no buff

### Boss Bar Variant Guidance ✅

The boss bar guides players through **both** base building requirements and variant-specific upgrades:

| Build Phase | Boss Bar Display |
|-------------|-----------------|
| Early building | `Need: Build bigger walls/floor & Add a roof` |
| Almost done + variant available | `Need: Add a roof | Mining Outpost: Furnace, Pickaxe in chest` |
| Structure complete, variant incomplete | `✓ Structure done! For Mining Outpost: add Furnace, Pickaxe in chest` |
| Fully complete with variant | `✓ Mining Outpost registered!` |
| Fully complete, standard | `✓ Complete! Building registered.` |

The `/obj` checklist also shows variant upgrade requirements:
```
✓ Walls & Structure (24+ blocks)
✓ Floor Size (14+ blocks)
✓ Enclosed Interior Space
✓ Roof Coverage
✓ Storage Chest
✓ Crafting Table
✓ Door/Entrance

⬆ Mining Outpost Upgrade:
  ✗ Furnace
  ✗ Pickaxe in chest
```

### Detection Mechanics (Score-Based System)

The building detector scans around the objective marker and scores structures on 5 categories:

**Required Score: 70/100**

| Category | Max Points | Requirements |
|----------|------------|--------------|
| Structure | 30 | 24+ structural blocks, 14+ footprint, roof coverage |
| Interior | 25 | 5+ interior cells, floor area, enclosure quality |
| Access | 20 | Door/entrance, entrance blocks |
| Signature | 20 | 1+ chest (9 pts), 1+ crafting table (9 pts), utility blocks |
| Context | 5 | Variant bonuses (fishing, mining, farm, etc.) |

**Debug Output Example:**
```
[Buildings] === SCAN START: OUTPOST in region B1 ===
[Buildings] Scanning at -315,72,-220 (radius=16, vertical=12)
[Buildings] Found 48 relevant blocks in scan area
[Buildings] Split into 2 connected components (anchor radius=8)
[Buildings] Component #1 (42 blocks): Anchored - evaluating...
[Buildings]   [OUTPOST] Stats: structural=38 (need 24), footprint=16 (need 14), interior=8 (need 6)
[Buildings]   [OUTPOST] Features: roof=75% (need 60%), chests=2, crafting=1, entrances=1
[Buildings]   [OUTPOST] Scores: structure=28.5/30, interior=22.0/25, access=18.0/20, signature=20.0/20, context=5.0/5
[Buildings]   [OUTPOST] TOTAL: 93.5/70.0 (134%)
[Buildings] Component #1 score: 93.5/70.0 (VALID)
[Buildings] RESULT: 1 anchored components, best score=93.5, valid=true
```

**Terrain-Aware Spawn Log Example:**
```
[Objectives] Terrain analysis for B1: FOREST=12 MOUNTAIN=5 WATER=3 FARM=2 DESERT=0 UNDERGROUND=0
[Objectives] Spawning FOREST outpost in B1 (-715,82,-201)
```

**Variant Requirement Check (when items are missing):**
```
[Buildings]   [VARIANT] Forest environment detected but missing: logsInChest=0/10, axe=false
```

**Enable Debug Logging:**
```yaml
# config.yml
regions:
  objectives:
    building-detection-debug: true
```

### Visual Indicators ✅
- **Particle Effect:** White sparkles (END_ROD) rising from building center, visible to team within 32 blocks
- **Sound:** Subtle amethyst chime every ~3 seconds when within 16 blocks (team only)
- **Map Marker:** Small tent icon on BlueMap

---

## 🗼 Watchtower

**Objective:** Build a structure 15+ blocks tall with clear line of sight.

### Construction Requirements
| Component | Quantity | Purpose |
|-----------|----------|---------|
| Height | 15+ blocks | Elevated vantage point |
| Platform | 3x3 minimum | Standing area at top |
| Ladder/Stairs | Required | Access to top |
| Line of Sight | Required | Clear view of surroundings |

### Gameplay Benefits

1. **Enemy Detection Radar**
   - Players at top of watchtower get enhanced enemy detection
   - Shows enemy players within range on compass/action bar
   - Range scales with height

2. **Map Reveal**
   - Standing on watchtower reveals enemy positions in region
   - Information shared with team members in same region
   - Updates every 3 seconds while occupied

3. **Early Warning System**
   - When enemies enter the region, team gets notification
   - "⚠ Enemy spotted in [Region Name]!" message
   - Only triggers when watchtower is occupied by a player

### Strategic Value
- **Information warfare** - Knowledge of enemy positions is powerful
- **Defensive structure** - Provides overwatch for defenders
- **Requires presence** - Must have player stationed to be effective

### Detection Mechanics (Score-Based System)

**Required Score: 65/100**

| Category | Max Points | Requirements |
|----------|------------|--------------|
| Structure | 35 | Height (14+ blocks), structural blocks (45+), base footprint (3+) |
| Interior | 15 | Support strength (65%+ structural support under tower) |
| Access | 25 | Access coverage (55%+ climbable route via ladders/stairs) |
| Signature | 25 | Platform size (4+ blocks at top), openness (35%+ clear visibility) |
| Context | 15 | Sky exposure, exposed terrain bonus |

**Debug Output Example:**
```
[Buildings] === SCAN START: WATCHTOWER in region A2 ===
[Buildings]   [WATCHTOWER] Tower analysis: height=18 (need 14), platform=6 (need 4), base=4 (need 3)
[Buildings]   [WATCHTOWER] Tower ratios: access=72% (need 55%), support=80% (need 65%), openness=55% (need 35%)
[Buildings]   [WATCHTOWER] Scores: structure=32.0/35, interior=12.0/15, access=19.4/25, signature=22.5/25, context=11.0/15
[Buildings]   [WATCHTOWER] TOTAL: 96.9/65.0 (149%)
```

### Height Bonuses
| Height | Detection Range | Bonus |
|--------|-----------------|-------|
| 15-19 blocks | 64 blocks | Base |
| 20-24 blocks | 96 blocks | +50% |
| 25-29 blocks | 128 blocks | +100% |
| 30+ blocks | 160 blocks | +150% |

### Visual Indicators ✅
- **Particle Effect:** Blue soul fire flame particles at platform level, visible to team within 32 blocks
- **Sound:** Subtle amethyst shimmer every ~3 seconds when within 16 blocks (team only)
- **Glowing Outline:** Enemies within detection range get glowing effect (only visible to watchtower occupant)
- **Map Marker:** Tower icon on BlueMap with detection radius circle

---

## 🏰 Garrison

**Objective:** Build barracks with 3+ beds for team spawning.

### Base Construction Requirements
| Component | Quantity | Purpose |
|-----------|----------|---------|
| Beds | 3+ | Team spawn capacity |
| Roof | Required | Enclosed sleeping area |
| Walls | Required | Protected structure (2+ blocks high) |
| Door | 1+ | Entry point |
| Floor Space | 5x5 minimum | Room for beds |
| Beds Color | Team color | Red beds for red team, blue for blue |

### Gameplay Benefits

1. **Quick Travel Destination**
   - Enables the spawn map teleportation system
   - After respawning at home base, players receive a map item
   - Map shows all friendly garrisons - click to teleport
   - Map disappears after moving X blocks from spawn

2. **Forward Spawn Network**
   - Creates web of teleport destinations across the map
   - Reduces travel time to frontlines
   - Strategic placement = better map control

3. **Spawn Capacity**
   - Bed count determines how many players can teleport per minute
   - Prevents garrison spam by limiting throughput

### Garrison Variants (Based on Interior Contents)

The garrison provides additional bonuses based on what is built INSIDE:

| Variant | Additional Requirements | Bonus Effect |
|---------|------------------------|--------------|
| **Basic Garrison** | 3+ beds, walls, roof | Teleport destination only |
| **Medical Garrison** | + Brewing Stand, + 3 Healing Potions in chest | Regeneration I for 30s on arrival |
| **Armory Garrison** | + Anvil, + 5 Iron Ingots in chest | Resistance I for 30s on arrival |
| **Command Garrison** | + Lectern with written book, + Banner | Strength I for 30s on arrival, Division members get 60s |
| **Supply Garrison** | + 4 Chests (min 64 items total) | Saturation for 60s, +1 spawn capacity |
| **Fortified Garrison** | + 20 defensive blocks (walls/fences) surrounding | Resistance II for 15s on arrival |

**Note:** Variants stack! A garrison can be both Medical AND Armory if it has all requirements.

### Spawn Capacity
| Beds | Teleports/Minute | Max Queue |
|------|------------------|-----------|
| 3 | 3 | 5 |
| 4-5 | 4 | 8 |
| 6-8 | 5 | 10 |
| 9+ | 6 | 12 |

### Detection Mechanics (Score-Based System)

**Required Score: 75/100**

| Category | Max Points | Requirements |
|----------|------------|--------------|
| Structure | 30 | 34+ structural blocks, 18+ footprint, 65%+ roof coverage |
| Interior | 25 | 10+ interior cells, 12+ floor area, enclosure quality |
| Access | 15 | Door/entrance (8 pts), additional entrance blocks |
| Signature | 20 | 3+ team-colored beds (4 pts each), storage, military utility |
| Context | 10 | Variant bonuses (medical, armory, command, etc.) |

**Important:** Beds must match team color! RED team needs RED_BED, BLUE team needs BLUE_BED.

**Debug Output Example:**
```
[Buildings] === SCAN START: GARRISON in region C1 ===
[Buildings]   [GARRISON] Stats: structural=52 (need 34), floor=18 (need 12), interior=14 (need 10)
[Buildings]   [GARRISON] Features: roof=85% (need 65%), beds=4/4 (need 3 team), entrances=2
[Buildings]   [GARRISON] Team='RED', teamBeds=4, allBeds=4
[Buildings]   [GARRISON] Scores: structure=28.0/30, interior=23.0/25, access=15.0/15, signature=20.0/20, context=8.0/10
[Buildings]   [GARRISON] TOTAL: 94.0/75.0 (125%)
```

### Visual Indicators ✅
- **Particle Effect:** Team-colored flames (FLAME for red, SOUL_FIRE_FLAME for blue) rising from center, visible to team within 32 blocks
- **Sound:** Campfire crackle every ~3 seconds when within 16 blocks (team only)
- **Map Marker:** Garrison icon on BlueMap with capacity indicator

---

## 🛡️ Building Protection System

### How Protection Works

Buildings are **NOT** inherently protected. Protection comes from **region ownership**:

| Region State | Your Buildings | Enemy Buildings |
|--------------|----------------|-----------------|
| **Your Team Owns** | Protected - enemies cannot break | N/A (shouldn't exist) |
| **Neutral** | Vulnerable - anyone can break | Vulnerable - anyone can break |
| **Contested** | Vulnerable - enemies can break | Vulnerable - you can break |
| **Enemy Owns** | N/A (shouldn't exist) | Vulnerable - you can break |

### Protection Mechanics

1. **Owned Regions (Your Team)**
   - Enemy players cannot break blocks in your owned regions
   - Your buildings are safe as long as region is owned
   - If region becomes contested, protection is lost

2. **Contested Regions**
   - All blocks are breakable by both teams
   - Buildings are high-priority targets
   - Destroying enemy garrison = removing spawn point

3. **Enemy Regions**
   - You can break blocks (that's how you contest)
   - Enemy buildings are valid targets
   - Destroying buildings is part of conquest

### Building Destruction

| Building | Destroyed When | Effect |
|----------|----------------|--------|
| Outpost | Chest OR Crafting Table broken | Outpost deregistered, must rebuild |
| Watchtower | Platform broken OR height reduced below 15 | Detection lost immediately |
| Garrison | 2+ beds broken OR walls breached | Removed from spawn map, team notified |

### Destruction Notifications ✅
When a building is destroyed, a message is broadcast to all players in the region:
- **Construction:** `"<Variant> <Building Type> has been constructed! (<x>, <y>, <z>)"`
- **Outpost:** `"Outpost destroyed! (x: <x>, z: <z>) Repair it to regain its benefits!"`
- **Watchtower:** `"⚠ Watchtower in [Region] has fallen!"` (team-wide)
- **Garrison:** `"🚨 GARRISON DESTROYED in [Region]! Spawn point lost!"` (team-wide, with sound)

Buildings inside structures are also excluded from road damage notifications.

---

## 🎯 Objective Rewards

| Building | IP Reward | Merit Tokens | Achievement |
|----------|-----------|--------------|-------------|
| Outpost | 75 IP | 2 tokens | "Forward Scout" |
| Watchtower | 100 IP | 3 tokens | "Eyes in the Sky" |
| Garrison | 150 IP | 5 tokens | "Rally Point" |

### Variant Bonuses
- Building a **variant outpost** (not basic): +25 IP bonus
- Building a **variant garrison**: +25 IP per variant type included

---

## 🔧 Building Limits

| Limit Type | Restriction | Reason |
|------------|-------------|--------|
| **Per Region** | 2 outposts, 1 watchtower, 1 garrison | Prevents spam |
| **Per Team (Total)** | 6 garrisons max | Strategic placement matters |
| **Per Player** | 1 outpost registration per region | Personal investment |

---

## ✨ Visual Indicator Summary

| Building | Particle Effect | Map Marker | Sound |
|----------|-----------------|------------|-------|
| Outpost | White sparkles (END_ROD) at center | Tent icon | Amethyst chime (~3s) |
| Watchtower | Blue soul fire at platform | Tower + radius | Amethyst shimmer (~3s) |
| Garrison | Team-colored flames at center | Garrison + capacity | Campfire crackle (~3s) |

### Particle Visibility
- Particles visible from **32 blocks** away
- Particles only visible to **team members** (enemies don't see them)
- Ambient sounds play at **0.15 volume** within **16 blocks** (team only)
- Can be toggled off per-player with `/settings particles`

---

## ❓ Questions to Resolve

1. **How are buildings "registered" as complete?** ✅ RESOLVED
   - **Automatic detection** - `tickStructureObjectives()` runs every 1 second
   - Building must maintain valid score for `building-validation-seconds` (default: 3s)
   - Once registered, integrity checks run every `building-integrity-check-seconds` (default: 5s)

2. **Should watchtower detection work when unoccupied?**
   - Currently requires player presence
   - Could add "automated" upgrade with redstone?

3. **What happens to buildings when region changes hands?** ✅ RESOLVED
   - Buildings remain physically but lose gameplay benefits
   - Enemy buildings become valid raid targets
   - Original team can reclaim by reconquering region
   - Building destruction is broadcast to players in the region

4. **How often should building detection run?** ✅ RESOLVED
   - Detection runs every **1 second** (`structureTask` at 20 ticks)
   - Uses debouncing (`building-detection-debounce-ticks`: 20) to prevent spam
   - Integrity checks run every `building-integrity-check-seconds` (default: 5s)

5. **Should garrison teleport have a cooldown?**
   - Per-player cooldown after teleporting?
   - Prevent rapid repositioning?

6. **How are outpost variants determined?** ✅ RESOLVED
   - **Terrain-aware spawning** analyzes region terrain (25-point grid sample)
   - Objective spawns at location matching dominant terrain type
   - Variant confirmed by scanning building for **both** environment AND specific blocks/items
   - Boss bar and `/obj` checklist guide players through variant requirements
   - Buffs only granted when ALL variant requirements are met

---

## 💡 Future Building Ideas

These are additional building types that could be added to expand the settlement system:

### 🏭 Forge

**Objective:** Build an industrial facility for equipment production.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Furnaces | 3+ | Smelting capacity |
| Blast Furnace | 1+ | Advanced smelting |
| Smoker | 1+ | Food processing |
| Anvil | 1 | Repair station |
| Enclosed | Required | Walls and roof |

**Benefits:**
- **Smelting Speed Boost:** Items smelt 50% faster when placed in forge furnaces
- **Repair Discount:** Anvil repairs cost 1 less level
- **Team Buff:** Players leaving forge get Fire Resistance for 2 min

**Variants:**
| Variant | Requirements | Bonus |
|---------|--------------|-------|
| **Weapons Forge** | + Grindstone, + 10 Iron Ingots | Sharpness I on crafted swords for 10 min |
| **Armor Forge** | + Smithing Table, + Diamond | Protection I on crafted armor for 10 min |

---

### 🏥 Field Hospital

**Objective:** Build a medical facility for healing and recovery.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Beds | 2+ | Patient beds (any color) |
| Brewing Stands | 2+ | Potion production |
| Cauldron | 1+ | Water source |
| Chest with Potions | Required | Medical supplies |
| Enclosed | Required | Walls and roof |

**Benefits:**
- **Passive Regeneration:** Players inside hospital area get Regeneration I
- **Respawn Speed:** Reduces respawn timer by 5 seconds if hospital exists in region
- **Cure Effects:** Removes poison/wither when entering hospital

**Variants:**
| Variant | Requirements | Bonus |
|---------|--------------|-------|
| **Trauma Center** | + 4 beds, + Golden Apples in chest | Absorption II on exit |
| **Plague Ward** | + Milk Buckets in chest | Clears ALL negative effects |

---

### 🌾 Supply Depot

**Objective:** Build a logistics hub for supply management.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Chests | 6+ | Storage capacity |
| Barrels | 4+ | Bulk storage |
| Hay Bales | 8+ | Food storage indicator |
| Item Frames | 4+ | Labeling system |
| Enclosed | Required | Walls and roof |

**Benefits:**
- **Supply Level Boost:** Region supply level +1 tier if depot exists
- **Death Drop Protection:** 25% chance to keep items on death in region
- **Saturation Aura:** Players near depot don't lose hunger

**Variants:**
| Variant | Requirements | Bonus |
|---------|--------------|-------|
| **Armory Depot** | 32+ weapons/armor stored | Spawn with iron sword on teleport |
| **Food Depot** | 128+ food items stored | Saturation II for 5 min on exit |

---

### 🔔 Signal Tower

**Objective:** Build a communication structure for team coordination.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Height | 10+ blocks | Visibility |
| Bell | 1 | Alert mechanism |
| Campfire | 1+ | Smoke signal |
| Platform | Required | Standing area |

**Benefits:**
- **Ring Bell for Alert:** Right-click bell to send region-wide ping to team
- **Smoke Signals:** Campfire smoke visible from 128 blocks (double normal)
- **Communication Range:** Team chat has +50% range when near tower

**Mechanics:**
- Ringing bell sends: "🔔 [Player] is signaling for help in [Region]!"
- Bell has 60-second cooldown to prevent spam
- Smoke color changes based on team (red/blue dyed campfire)

---

### ⚓ Dock

**Objective:** Build a waterside facility for naval operations.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Adjacent to Water | Required | Must touch ocean/river |
| Boat | 1+ | Vessel storage |
| Chest | 2+ | Maritime supplies |
| Fence Posts | 6+ | Dock structure |
| Platform | 3x6 minimum | Landing area |

**Benefits:**
- **Boat Spawn:** Boats respawn at dock every 5 minutes if destroyed
- **Aquatic Buff:** Dolphin's Grace + Aqua Affinity for 3 min when leaving
- **Fishing Boost:** Luck of the Sea II when fishing near dock

**Variants:**
| Variant | Requirements | Bonus |
|---------|--------------|-------|
| **Fishing Dock** | + Fishing Rods in chest | Auto-fish: generates 1 fish/minute |
| **Transport Dock** | + 3 Boats | Fast travel to other team docks |

---

### 🕯️ Shrine

**Objective:** Build a monument structure for team morale.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Gold Blocks | 1+ | Shrine core |
| Candles/Torches | 8+ | Illumination |
| Banner | 1+ | Team symbol |
| Enclosed OR Open | Either | Design flexibility |

**Benefits:**
- **Morale Boost:** Players in region get +10% XP from all sources
- **Death Protection:** First death in region doesn't drop items (once per life)
- **Inspiration:** Merit token earnings +25% in shrine's region

**Variants:**
| Variant | Requirements | Bonus |
|---------|--------------|-------|
| **War Shrine** | + Diamond Block, + Wither Skull | Strength I in region |
| **Peace Shrine** | + Emerald Block, + Flowers | Regeneration I in region |

---

### 🛠️ Workshop

**Objective:** Build a crafting facility for advanced production.

| Component | Quantity | Purpose |
|-----------|----------|---------|
| Crafting Tables | 3+ | Production capacity |
| Stonecutter | 1+ | Precision cutting |
| Loom | 1+ | Banner crafting |
| Cartography Table | 1+ | Map making |
| Chests | 2+ | Material storage |
| Enclosed | Required | Walls and roof |

**Benefits:**
- **Efficiency:** Crafting recipes yield +1 bonus item (where applicable)
- **Repair Station:** Tools repaired by 10% durability when placed in chest overnight
- **Blueprint Unlock:** Access to special "military" recipes only craftable in workshop

**Special Recipes (Workshop Only):**
| Recipe | Ingredients | Result |
|--------|-------------|--------|
| Reinforced Shield | Shield + Iron Block | Shield with Unbreaking III |
| Signal Flare | Firework + Glowstone | Marks location for team |
| Trench Shovel | Diamond Shovel + Stick | Creates DIRT_PATH in 3x3 |

---

## 📊 Building Comparison Summary

| Building | Investment | Primary Benefit | Team Impact |
|----------|------------|-----------------|-------------|
| Outpost | Low | Territory claim + buffs | Low |
| Watchtower | Medium | Enemy detection | Medium |
| Garrison | High | Spawn network | High |
| Forge | Medium | Equipment production | Medium |
| Field Hospital | Medium | Healing + respawn | Medium |
| Supply Depot | High | Supply + protection | High |
| Signal Tower | Low | Communication | Low |
| Dock | Medium | Naval + water control | Medium |
| Shrine | High | Morale + protection | High |
| Workshop | Medium | Crafting + special items | Medium |

