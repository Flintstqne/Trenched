# Building System Design

**Last Updated:** February 24, 2026

This document outlines the purposes and mechanics for settlement buildings in Entrenched.

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

### Outpost Variants (Based on Spawn Location)

The objective system spawns outpost objectives at specific locations. The variant depends on WHERE the objective spawns:

| Variant | Spawn Location | Additional Requirements | Bonus Effect |
|---------|----------------|------------------------|--------------|
| **Mining Outpost** | Near ore veins (Y < 50) | Furnace, Stone Pickaxe in chest | Fortune I buff for 5 min when leaving |
| **Fishing Outpost** | Near water (ocean/river) | Fishing Rod in chest, within 10 blocks of water | Luck of the Sea buff for 5 min |
| **Farm Outpost** | Plains/flat terrain | 16+ crops planted nearby, Hoe in chest | Saturation buff for 5 min |
| **Forest Outpost** | Forest biome | 10+ logs in chest, Axe in chest | Haste I buff for 5 min (wood cutting) |
| **Mountain Outpost** | High elevation (Y > 100) | Ladder access, 3+ wool blocks | Slow Falling buff for 5 min |
| **Desert Outpost** | Desert biome | 3+ cactus, Water bucket in chest | Fire Resistance buff for 5 min |

### Detection Mechanics
```
Scan 10x10x10 area for:
- At least 1 crafting table
- At least 1 chest (single or double)
- Enclosed by walls (2+ blocks high on all sides)
- Roof coverage above interior
- Variant-specific requirements based on spawn location
```

### Visual Indicators
- **Particle Effect:** White sparkles rising from crafting table
- **Sound:** Subtle ambient hum when near registered outpost
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

### Detection Mechanics
```
Scan for:
- Solid blocks forming vertical structure
- Minimum 15 blocks from ground to top platform
- Platform at top (3x3 solid blocks)
- Clear sky access (no blocks above platform)
- Climbable access (ladder, stairs, or scaffolding)
```

### Height Bonuses
| Height | Detection Range | Bonus |
|--------|-----------------|-------|
| 15-19 blocks | 64 blocks | Base |
| 20-24 blocks | 96 blocks | +50% |
| 25-29 blocks | 128 blocks | +100% |
| 30+ blocks | 160 blocks | +150% |

### Visual Indicators
- **Particle Effect:** Blue beacon-like particles at platform level
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

### Detection Mechanics
```
Scan for:
- Minimum 3 beds within enclosed structure
- All beds must be team color (RED_BED or BLUE_BED)
- Roof blocks above all beds (within 5 blocks)
- Wall blocks on all 4 sides (at least 2 high)
- At least 1 door for entry
- Minimum 5x5 floor space
- Check for variant requirements
```

### Visual Indicators
- **Particle Effect:** Team-colored flames rising from beds (red/blue)
- **Beacon Beam:** Short colored beam visible from distance (toggleable)
- **Sound:** Military drum beat when garrison is registered
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

### Destruction Notifications
- **Outpost:** "[Region] Outpost destroyed!" (local team message)
- **Watchtower:** "⚠ Watchtower in [Region] has fallen!" (team-wide)
- **Garrison:** "🚨 GARRISON DESTROYED in [Region]! Spawn point lost!" (team-wide, with sound)

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
| Outpost | White sparkles (crafting table) | Tent icon | Ambient hum |
| Watchtower | Blue beacon particles | Tower + radius | Wind whistle |
| Garrison | Team-colored flames | Garrison + capacity | Military drums |

### Particle Visibility
- Particles visible from **32 blocks** away
- Particles only visible to **team members** (enemies don't see them)
- Can be toggled off per-player with `/settings particles`

---

## ❓ Questions to Resolve

1. **How are buildings "registered" as complete?**
   - Automatic detection when requirements met?
   - Player must use command/item to register?

2. **Should watchtower detection work when unoccupied?**
   - Currently requires player presence
   - Could add "automated" upgrade with redstone?

3. **What happens to buildings when region changes hands?**
   - Destroyed automatically?
   - Become "neutral" and can be claimed?
   - Stay as enemy buildings until destroyed?

4. **How often should building detection run?**
   - Every X seconds per region?
   - Only when player places relevant block?

5. **Should garrison teleport have a cooldown?**
   - Per-player cooldown after teleporting?
   - Prevent rapid repositioning?

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

