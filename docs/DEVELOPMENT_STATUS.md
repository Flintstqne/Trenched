# Entrenched (BlockHole) Development Status

**Last Updated:** February 24, 2026

---

## 🎮 Overview

Entrenched is a Minecraft PvP plugin featuring team-based warfare with:
- **2 Teams**: Red vs Blue
- **4x4 Region Grid**: 16 capturable territories (512x512 blocks each)
- **Supply Line System**: Physical roads connect territories
- **Influence Points (IP)**: Capture regions through actions
- **Objectives System**: Settlement and Raid objectives for capturing regions
- **Divisions & Parties**: Organizational units for coordination
- **Merit System**: Player recognition and ranking

---

## ✅ FULLY IMPLEMENTED

### Core Systems

| Feature | Status | Description |
|---------|--------|-------------|
| **Round System** | ✅ Complete | Start/end rounds, phase advancement, auto-start on server boot |
| **Team System** | ✅ Complete | Red/Blue teams, team selection GUI, team spawns |
| **Region Grid** | ✅ Complete | 4x4 grid (A1-D4), region names generator, boundaries |
| **World Regeneration** | ✅ Complete | New world per round, random seeds, Chunky pregeneration |
| **Scoreboard** | ✅ Complete | War/Phase display, current region, IP status, supply level |
| **BlueMap Integration** | ✅ Complete | Region rendering, team colors, captured region colors |

### Region Capture System

| Feature | Status | Description |
|---------|--------|-------------|
| **Influence Points (IP)** | ✅ Complete | Configurable points for all actions |
| **IP from Kills** | ✅ Complete | Enemy kills award IP, repeat-kill reduction |
| **IP from Blocks** | ✅ Complete | Mining enemy blocks, placing defensive blocks |
| **IP from Banners** | ✅ Complete | Placing team banners, removing enemy banners |
| **IP from Workstations** | ✅ Complete | Placing furnaces, crafting tables, etc. |
| **IP from Torches** | ✅ Complete | Placing light sources |
| **IP from Mob Kills** | ✅ Complete | Killing hostile mobs (in neutral regions) |
| **Capture Logic** | ✅ Complete | Regions change ownership when IP threshold reached |
| **Adjacency Rules** | ✅ Complete | Must own adjacent region to attack (N/S/E/W) |
| **Fortification** | ✅ Complete | 10-min immunity after capture |
| **Rate Limiting** | ✅ Complete | Anti-spam for block mining, workstations, etc. |
| **Anti-Farming (Blocks)** | ✅ Complete | Breaking defensive blocks/torches/workstations removes IP earned |
| **Anti-Farming (Banners)** | ✅ Complete | Breaking own banners deducts IP |
| **Anti-Farming (Kills)** | ✅ Complete | Diminishing returns for repeat kills on same player |

### Supply Line System (Roads)

| Feature | Status | Description |
|---------|--------|-------------|
| **Road Block Detection** | ✅ Complete | DIRT_PATH, STONE_BRICKS, POLISHED_ANDESITE |
| **Road Registration** | ✅ Complete | Auto-tracks placed path blocks per team |
| **Border Connection** | ✅ Complete | Roads must connect at region borders |
| **Continuous Pathfinding** | ✅ Complete | BFS verification of road continuity |
| **Supply Levels** | ✅ Complete | SUPPLIED (100%), PARTIAL (50%), UNSUPPLIED (25%), ISOLATED (0%) |
| **Road Damage Detection** | ✅ Complete | Breaking/explosions update supply |
| **Team Notifications** | ✅ Complete | Alerts when roads damaged, regions affected |
| **Shovel Path Creation** | ✅ Complete | Using shovel on dirt creates DIRT_PATH |
| **Debug Commands** | ✅ Complete | `/admin supply debug`, `roadpath`, `borderinfo`, etc. |
| **Batched Notifications** | ✅ Complete | Groups road damage into single messages |

### Death & Respawn System

| Feature | Status | Description |
|---------|--------|-------------|
| **Death Tracking** | ✅ Complete | Tracks killer, victim, location |
| **Spectator Mode** | ✅ Complete | "Dead" players fly around invisibly |
| **Respawn Timer** | ✅ Complete | Title countdown, supply-based delays |
| **Supply Penalties** | ✅ Complete | Longer respawn in unsupplied regions |
| **Team Spawn Respawn** | ✅ Complete | Respawn at team home spawn |

### Chat System

| Feature | Status | Description |
|---------|--------|-------------|
| **General Chat** | ✅ Complete | `/g` - Global chat (all players) |
| **Team Chat** | ✅ Complete | `/tc` - Team-only chat |
| **Division Chat** | ✅ Complete | `/dc` - Division members only |
| **Party Chat** | ✅ Complete | `/pc` - Party members only |
| **Region Chat** | ✅ Complete | `/rc` - Players in same region (enemies included) |
| **Channel Switching** | ✅ Complete | Commands toggle default channel |
| **Quick Messages** | ✅ Complete | `/dc <msg>` sends without switching |
| **Division Tags** | ✅ Complete | `[TAG]` displayed in chat with team color |
| **Configurable Format** | ✅ Complete | Chat format configurable via config.yml |
| **PlaceholderAPI Support** | ✅ Complete | Custom placeholders for ranks, divisions, etc. |

### Division System

| Feature | Status | Description |
|---------|--------|-------------|
| **Create Division** | ✅ Complete | `/division create <name> [TAG]` |
| **Disband Division** | ✅ Complete | Commander can disband |
| **Invite/Kick** | ✅ Complete | Officers can manage members |
| **Hierarchy** | ✅ Complete | Commander, Officer, Member roles |
| **Promote/Demote** | ✅ Complete | Rank management |
| **Division Info** | ✅ Complete | View roster, online members |
| **Creation Cooldown** | ✅ Complete | 48h cooldown (OP bypasses) |
| **Tab Completion** | ✅ Complete | All subcommands |
| **Division Waypoints** | ✅ Complete | Officers can set, list, and remove shared waypoints |

### Party System

| Feature | Status | Description |
|---------|--------|-------------|
| **Create Party** | ✅ Complete | Automatic on first invite |
| **Invite Players** | ✅ Complete | `/party invite <player>` |
| **Accept/Decline** | ✅ Complete | Invitation system |
| **Leave/Kick** | ✅ Complete | Party management |
| **Transfer Leadership** | ✅ Complete | Change party leader |
| **Party Chat** | ✅ Complete | `/pc` integration |
| **Max Size** | ✅ Complete | Configurable (default: 6) |

### Merit System

| Feature | Status | Description |
|---------|--------|-------------|
| **Merit Tokens** | ✅ Complete | Earned through gameplay actions |
| **Token Earning** | ✅ Complete | Kills, captures, road building, supply, playtime, etc. |
| **Merit Giving** | ✅ Complete | `/merit <player> [reason]` - Give tokens to recognize players |
| **Received Merits** | ✅ Complete | Track merits received from other players |
| **Military Ranks** | ✅ Complete | 18 ranks from Recruit to General of the Army |
| **Rank Display** | ✅ Complete | Rank tags in chat and scoreboards |
| **Anti-Farming** | ✅ Complete | Daily limits, cooldowns, interaction requirements |
| **OP Bypass** | ✅ Complete | OPs skip anti-farm checks for testing |
| **Leaderboards** | ✅ Complete | `/admin merit leaderboard [count]` |
| **Debug Options** | ✅ Complete | Config options for testing (skip limits, self-merit) |

### Achievement System

| Feature | Status | Description |
|---------|--------|-------------|
| **Combat Achievements** | ✅ Complete | First Kill, Kill milestones (10/50/100/500), Kill streaks, Shutdown |
| **Territory Achievements** | ✅ Complete | First Capture, Capture milestones, Defender, Top Contributor |
| **Logistics Achievements** | ✅ Complete | Road building milestones, Supply region, Supply route, Sabotage |
| **Social Achievements** | ✅ Complete | Join division, Create division, Merit giving/receiving |
| **Progression Achievements** | ✅ Complete | Rank promotions (Corporal through General) |
| **Time Achievements** | ✅ Complete | Playtime milestones (1h/10h/50h/100h), Login streaks (7/30 days) |
| **Round Achievements** | ✅ Complete | Win round, Complete round, Round MVP |
| **Achievement Viewer** | ✅ Complete | `/achievements [category]` - View progress |
| **One-time Rewards** | ✅ Complete | Token rewards for each achievement |
| **Achievement Notifications** | ✅ Complete | Sound + message when unlocked |

### Admin Commands

| Feature | Status | Description |
|---------|--------|-------------|
| **Phase Control** | ✅ Complete | `/admin phase <1-3>` |
| **Region Ownership** | ✅ Complete | `/admin region set <region> <team>` |
| **Player Teleport** | ✅ Complete | `/admin tp <player> <region_name>` |
| **Supply Debug** | ✅ Complete | Full suite of debug commands |
| **New Round** | ✅ Complete | `/round new` - Full world reset |
| **Merit Admin** | ✅ Complete | `/admin merit <give\|givetokens\|set\|reset\|info\|leaderboard>` |

---

## 🔧 CONFIGURATION

All features are configurable via `config.yml`:

- **Round Settings**: Phase duration, max phases, auto-start
- **World Settings**: Border size, center, damage
- **Team Settings**: Spawn coordinates, region size
- **Player Settings**: Respawn behavior, team GUI
- **Region Capture**: IP thresholds, action points, rate limits
- **Supply System**: Path blocks, adjacency radius, respawn penalties
- **Divisions/Parties**: Limits, cooldowns, features
- **BlueMap**: Enable/disable, refresh interval
- **Merit System**: Token multiplier, earning rates, anti-farm settings
- **Chat Format**: Configurable chat format with placeholders

---

## 🚧 PARTIALLY IMPLEMENTED

### Region Objectives System

The objectives system provides SETTLEMENT objectives (for neutral regions) and RAID objectives (for enemy-owned regions).

| Objective | Category | Status | Description |
|-----------|----------|--------|-------------|
| **Destroy Supply Cache** | Raid | ✅ Complete | Find/destroy enemy-placed chests |
| **Assassinate Commander** | Raid | ✅ Complete | Kill enemy division commanders/officers (glowing targets, compass tracking) |
| **Sabotage Defenses** | Raid | ✅ Complete | Destroy 50+ wall blocks |
| **Plant Explosive** | Raid | ✅ Complete | Place TNT at target, defend 30s (defenders can defuse) |
| **Capture Intel** | Raid | ✅ Complete | Pick up intel item, return to friendly territory (10 min lifetime) |
| **Hold Ground** | Raid | ✅ Complete | Hold region center 60s (both teams see alerts) |
| **Establish Outpost** | Settlement | ❌ Not Started | Build structure with bed/chest/crafting table |
| **Secure Perimeter** | Settlement | ✅ Complete | Build 100 defensive wall blocks (anti-cheese tracking) |
| **Build Supply Route** | Settlement | ✅ Complete | Build 64 road blocks near friendly territory border |
| **Build Watchtower** | Settlement | ❌ Not Started | Build 15+ block tall structure |
| **Establish Resource Depot** | Settlement | ✅ Complete | 4+ containers with 500+ items each (anti-cheese tracking) |
| **Build Garrison Quarters** | Settlement | ❌ Not Started | Build barracks with 3+ beds (enables quick travel spawn point) |

#### Objectives UI & Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Objective Spawning** | ✅ Complete | Objectives spawn automatically based on region state |
| **Objective Respawn** | ✅ Complete | New objectives spawn after completion (10 min cooldown) |
| **No-Repeat Spawning** | ✅ Complete | Completed objective types excluded until all types used |
| **Boss Bars** | ✅ Complete | Progress bars for nearby objectives |
| **Compass HUD** | ✅ Complete | Scoreboard compass shows direction/distance to nearest objective |
| **Defender Alerts** | ✅ Complete | Defenders see alerts for enemy objectives in progress |
| **Objective Command** | ✅ Complete | `/obj` shows active objectives, coordinates, progress |
| **Objective Particles** | ✅ Complete | Particle effects at objective locations |
| **Adjacency Filtering** | ✅ Complete | Objectives only shown for regions adjacent to team territory |
| **Cleaner Error Messages** | ✅ Complete | Single-line messages for blocked actions (owned regions, non-adjacent) |

---

## ❌ NOT YET IMPLEMENTED

### Remaining Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Establish Outpost** | ❌ Not Started | Settlement objective - Build structure with bed/chest/crafting table |
| **Build Watchtower** | ❌ Not Started | Settlement objective - Build 15+ block tall structure |
| **Build Garrison Quarters** | ❌ Not Started | Settlement objective - Build barracks with 3+ beds, enables quick travel spawn point |
| **Win Condition Detection** | ❌ Not Started | Auto-detect when team wins |
| **Round Statistics** | ❌ Not Started | Post-round stats summary |

---

## 💡 PLANNED FEATURES

### Container Permission System

A team-based container permission system to prevent enemies from accessing friendly storage.

| Feature | Status | Description |
|---------|--------|-------------|
| **Team Chest Protection** | ✅ Complete | Enemies cannot open/break team chests in team-owned regions |
| **Protection Drops on Capture** | ✅ Complete | When region is captured, new owners can access all enemy containers |
| **Objective Bypass** | ✅ Complete | "Destroy Supply Cache" objective allows breaking enemy chests |
| **No Traditional Claims** | ✅ Complete | Protection tied to region ownership, not land claims |

#### Design Philosophy
- **Sector-Based Vulnerability**: Container protection is tied to who controls the region
- **Physical Access Points**: No invisible claim boundaries - if you can reach it, protection depends on region ownership
- **Effort-Based Access**: Enemies must work to access protected storage (capture region or complete objectives)
- **Capture = Full Access**: When a region is captured, ALL enemy containers in that region become accessible to the new owners

#### Container Types Affected
- Chests (single and double)
- Trapped Chests
- Barrels
- Shulker Boxes
- Hoppers, Droppers, Dispensers

> **Note:** Division Depots (planned) will NOT follow this behavior. When a region with enemy Division Depots is captured, the depots remain locked. Accessing a captured Division Depot opens YOUR OWN division's storage, not the enemy's. A special tool is required to raid enemy Division Depots and loot their contents.

### Division Depot System

A shared storage system for divisions, similar to Ender Chests but with physical vulnerability.

> **📄 Full Design Document:** [DIVISION_DEPOT_DESIGN.md](./DIVISION_DEPOT_DESIGN.md)

> **Key Difference from Regular Containers:** Division Depots do NOT follow normal capture rules. When a region is captured, regular containers become accessible, but Division Depots remain protected. Interacting with an enemy Division Depot opens YOUR division's storage, not theirs. A special raid tool is required to loot enemy depot contents.

| Feature | Status | Description |
|---------|--------|-------------|
| **Shared Division Storage** | ✅ Complete | Functions like Ender Chest - all division members access same inventory (54 slots) |
| **Physical Depot Block** | ✅ Complete | Configurable block material (default: CHEST) with custom NBT |
| **Depot Item Factory** | ✅ Complete | DepotItem.java - creates depot blocks and raid tools with NBT |
| **Depot Inventory Holder** | ✅ Complete | DepotInventoryHolder.java - custom holder for storage tracking |
| **Crafting Recipes** | ✅ Complete | DepotRecipes.java - registers shaped recipes for depot and raid tool |
| **Depot Database Layer** | ✅ Complete | SQLite tables for depot locations, storage, and raid history |
| **Depot Service Interface** | ✅ Complete | Full service API for depot operations (DepotService.java) |
| **Depot Service Implementation** | ✅ Complete | SqlDepotService with placement, storage, and raid logic |
| **Storage Persistence** | ✅ Complete | Item serialization/deserialization for database storage |
| **Sector Capture Vulnerability** | ✅ Complete | Depots become raidable when their sector is captured |
| **Division Raid Tool** | ✅ Complete | Special tool item with NBT tags (item factory method) |
| **Raid Channel Mechanic** | ✅ Complete | Raid start/complete/cancel with tracking maps |
| **Partial Loot Drop** | ✅ Complete | 30% of storage items drop on raid (configurable) |
| **Division Notifications** | ✅ Complete | Alerts when depot is raided |
| **Configuration Options** | ✅ Complete | Full config.yml section for depot settings |
| **Depot Listener** | ✅ Complete | DepotListener.java - block place/break/interact events |
| **Container Protection Integration** | ✅ Complete | ContainerProtectionListener excludes depot blocks |
| **Raid Channeling UI** | ✅ Complete | Title countdown during raid with movement check |
| **Main Plugin Integration** | ✅ Complete | DepotService, DepotRecipes, DepotListener wired in Trenched.java |
| **ConfigManager Integration** | ✅ Complete | All depot settings accessible via ConfigManager methods |
| **Particle Effects** | ✅ Complete | DepotParticleManager.java - ambient, vulnerable, placement, raid effects |
| **Ender Chest Behavior** | ✅ Complete | Any depot opens YOUR division storage, regardless of who placed it |
| **Admin Commands** | ✅ Complete | `/admin depot list/info/give/givetool/clear/remove` |
| **Officer/Commander Only** | ✅ Complete | Only officers and commanders can place depots |
| **Depot Limit Display** | ✅ Complete | Shows current/max depot count on placement |
| **Region Capture Alerts** | 📋 Planned | Notify division when their depots become vulnerable |

#### Depot Mechanics

1. **Placement**
   - Division members craft a Division Depot block (copper chest + custom NBT)
   - Place in any region to create access point to shared division storage
   - Multiple depots can exist - all access the same shared inventory

2. **Protection**
   - Depot is protected while region is owned/controlled by depot owner's team
   - Cannot be broken or accessed by enemies in friendly territory

3. **Vulnerability**
   - When region is captured by enemy team, all depots in that region become vulnerable
   - Enemies can use a special tool to "raid" the depot
   - Raiding drops a portion of stored items (configurable %)
   - Depot block is destroyed after raiding

4. **Strategic Considerations**
   - Depots are NOT 100% safe - encourages strategic placement
   - Place in well-defended regions, not frontline territories
   - Creates additional objectives for attackers (capture region → raid depots)
   - Division must choose between convenience (many access points) and security (fewer depots)

### Garrison Quick Travel System

A spawn-based travel system tied to the "Build Garrison Quarters" objective.

| Feature | Status | Description |
|---------|--------|-------------|
| **Spawn Map Item** | 📋 Planned | Players receive a map item when spawning in home region |
| **Region Grid GUI** | 📋 Planned | Map opens inventory grid showing current server state |
| **Garrison Teleport** | 📋 Planned | Can teleport to any garrison in friendly regions |
| **Distance Limit** | 📋 Planned | Map disappears after moving X blocks from spawn point |
| **One-Time Use** | 📋 Planned | Cannot use map again after it disappears |

#### Garrison Mechanics

1. **On Spawn (Home Region)**
   - Player receives a special map item in their inventory
   - Map item has custom NBT data tracking spawn location

2. **Map Usage**
   - Right-click map to open region grid GUI
   - GUI shows all 16 regions with current ownership status
   - Regions with friendly garrisons are highlighted/clickable
   - Click a garrison region to teleport there

3. **Map Expiration**
   - System tracks player's distance from spawn point
   - Once player moves X blocks away (configurable), map is removed
   - Prevents abuse of quick travel mid-game
   - Encourages immediate decision-making on spawn

4. **Strategic Implications**
   - Garrisons provide forward spawn options for team
   - "Build Garrison Quarters" objective now has major gameplay value
   - Teams can create spawn networks across the map
   - Losing a garrison region = losing a spawn point

---

## 📂 Project Structure

```
src/main/java/org/flintstqne/entrenched/
├── Trenched.java              # Main plugin class
├── ConfigManager.java         # Configuration handling
├── AdminLogic/
│   └── AdminCommand.java      # Admin commands
├── BlueMapHook/
│   ├── BlueMapIntegration.java
│   ├── RegionRenderer.java    # Region coloring/names
│   └── RegionNameGenerator.java
├── ChatLogic/
│   ├── ChatChannel.java
│   ├── ChatChannelManager.java
│   └── ChatCommand.java
├── DivisionLogic/
│   ├── Division.java
│   ├── DivisionCommand.java
│   ├── DivisionDb.java
│   ├── DivisionService.java
│   └── SqlDivisionService.java
├── PartyLogic/
│   ├── Party.java
│   ├── PartyCommand.java
│   ├── PartyDb.java
│   ├── PartyService.java
│   └── SqlPartyService.java
├── RegionLogic/
│   ├── RegionCaptureListener.java  # IP from actions
│   ├── ContainerProtectionListener.java  # Team chest protection
│   ├── RegionCommand.java
│   ├── RegionDb.java
│   ├── RegionNotificationManager.java
│   ├── RegionService.java
│   ├── SqlRegionService.java
│   ├── RegionState.java
│   ├── RegionStatus.java
│   └── InfluenceAction.java
├── ObjectiveLogic/
│   ├── ObjectiveCommand.java      # /obj command
│   ├── ObjectiveDb.java
│   ├── ObjectiveListener.java     # Block/inventory events
│   ├── ObjectiveService.java
│   ├── SqlObjectiveService.java   # Objective spawning/completion
│   ├── ObjectiveType.java         # Objective definitions
│   ├── ObjectiveUIManager.java    # Boss bars, compass HUD
│   ├── RegionObjective.java
│   └── ObjectiveCategory.java
├── MeritLogic/
│   ├── MeritService.java
│   ├── SqlMeritService.java
│   ├── MeritDb.java
│   ├── MeritListener.java
│   ├── MeritRank.java
│   ├── Achievement.java
│   └── MeritTokenSource.java
├── RoadLogic/
│   ├── RoadListener.java      # Block place/break detection
│   ├── RoadDb.java
│   ├── RoadService.java
│   ├── SqlRoadService.java    # Pathfinding, supply calc
│   ├── SupplyCommand.java
│   ├── SupplyLevel.java
│   ├── SupplyPenaltyListener.java
│   └── DeathListener.java
├── RoundLogic/
│   ├── RoundCommand.java
│   ├── RoundDb.java
│   ├── RoundService.java
│   ├── SqlRoundService.java
│   ├── NewRoundInitializer.java
│   └── PhaseScheduler.java
├── TeamLogic/
│   ├── TeamCommand.java
│   ├── TeamDb.java
│   ├── TeamService.java
│   ├── TeamListener.java
│   └── TeamGuiCommand.java
└── Utils/
    ├── ScoreboardUtil.java
    └── ChatUtil.java
```

---

## 🎯 Recommended Next Steps

### Priority 1: Complete Remaining Objectives
1. **Establish Outpost** - Build structure with bed/chest/crafting table
2. **Build Watchtower** - Build 15+ block tall structure with line of sight
3. **Build Garrison Quarters** - Build barracks with 3+ beds

### Priority 2: Container & Depot System
1. **Team Chest Protection** - ✅ Complete
2. **Division Depot Block** - Custom craftable block for shared division storage (see [DIVISION_DEPOT_DESIGN.md](./DIVISION_DEPOT_DESIGN.md))
3. **Depot Storage System** - Database tables, save/load inventory
4. **Depot Vulnerability** - Make depots raidable when sector is captured
5. **Raid Tool** - Special tool to raid enemy depots and loot contents

### Priority 3: Win Conditions
1. Finalize automatic territory-control winner logic
2. Implement win detection
3. Add round-end summary


### Priority 4: Quality of Life
1. Player statistics tracking
2. Post-round statistics display
3. Tutorial/onboarding for new players

---

## 🐛 Known Issues

1. **Road scanning can be slow** with very large road networks
2. **BlueMap markers** may need manual refresh occasionally
3. **Phase scheduler** auto-advances immediately at max phase (intended, shows "awaiting conclusion")

---

## 📝 Commands Reference

### Player Commands
- `/team join <red|blue>` - Join a team
- `/teamgui` - Open team selection GUI
- `/division <create|invite|info|leave|...>` - Division management
- `/party <invite|accept|leave|...>` - Party management
- `/region <status|map|info>` - Region information
- `/obj` - View active objectives in current region (with coordinates and progress)
- `/supply status` - View supply levels
- `/g`, `/tc`, `/dc`, `/pc`, `/rc` - Chat channels
- `/merit <player> [reason]` - Give merit tokens to another player
- `/merits [player]` - View merit stats
- `/ranks` - View all military ranks and requirements
- `/achievements [category]` - View your achievements

### Admin Commands
- `/round new` - Start new round (world reset)
- `/round phase <1-3>` - Set phase
- `/admin region set <region> <team>` - Set region ownership
- `/admin region reset <region>` - Reset region to neutral
- `/admin tp <player> <region>` - Teleport player
- `/admin supply debug <region> <team>` - Debug supply
- `/admin supply register <region> <team>` - Scan/register roads
- `/admin merit give <player> <amount> [merits|tokens]` - Give merits or tokens
- `/admin merit givetokens <player> <amount>` - Give tokens directly
- `/admin merit set <player> <amount>` - Set player's merits
- `/admin merit reset <player>` - Reset player's merit data
- `/admin merit info <player>` - View player's merit info
- `/admin merit leaderboard [count]` - View merit leaderboard
- `/admin objective spawn <region> <type>` - Manually spawn objective
- `/admin objective list <region>` - List active objectives

---

## 🔌 PlaceholderAPI Integration

Custom placeholders available (requires PlaceholderAPI):

| Placeholder | Description |
|-------------|-------------|
| `%entrenched_rank%` | Player's military rank name |
| `%entrenched_rank_tag%` | Player's rank tag (e.g., SGT) |
| `%entrenched_rank_formatted%` | Colored rank tag |
| `%entrenched_merits%` | Received merits count |
| `%entrenched_tokens%` | Token balance |
| `%entrenched_division%` | Division name |
| `%entrenched_division_tag%` | Division tag |
| `%entrenched_team%` | Team name (red/blue) |
| `%entrenched_kills%` | Lifetime kills |
| `%entrenched_captures%` | Lifetime captures |
| `%entrenched_chat_prefix%` | Full chat prefix (division + rank) |

