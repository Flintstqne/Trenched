# Entrenched (BlockHole) Development Status

**Last Updated:** February 17, 2026

---

## 🎮 Overview

Entrenched is a Minecraft PvP plugin featuring team-based warfare with:
- **2 Teams**: Red vs Blue
- **4x4 Region Grid**: 16 capturable territories (512x512 blocks each)
- **Supply Line System**: Physical roads connect territories
- **Influence Points (IP)**: Capture regions through actions
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

## 🚧 NOT YET IMPLEMENTED

### Region Objectives System (Partially Implemented)

The design document (`REGION_CAPTURE_DESIGN.md`) outlines objectives. Current status:

| Objective | Status | Description |
|-----------|--------|-------------|
| **Destroy Supply Cache** | ✅ Complete | Find/destroy enemy-placed chests |
| **Assassinate Commander** | ✅ Complete | Kill enemy division commanders/officers (glowing targets) |
| **Sabotage Defenses** | ✅ Complete | Destroy 50+ wall blocks |
| **Plant Explosive** | ✅ Complete | Place TNT at target, defend 30s |
| **Capture Intel** | ❌ Not Started | Retrieve item, return to base |
| **Hold Ground** | ✅ Complete | Hold region center 60s |
| **Establish Outpost** | ❌ Not Started | Build structure with bed/chest/crafting table |
| **Secure Perimeter** | ✅ Complete | Build 100 defensive wall blocks |
| **Build Supply Route** | ✅ Complete | Build 64 road blocks |
| **Build Watchtower** | ❌ Not Started | Build 15+ block tall structure |
| **Establish Resource Depot** | ✅ Complete | 4+ containers with 100+ items |
| **Build Garrison Quarters** | ❌ Not Started | Build barracks with 3+ beds |


### Additional Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Influence Decay** | ✅ Complete | IP decays in contested regions with no activity (configurable rate) |
| **Win Condition Detection** | ❌ Not Started | Auto-detect when team wins |
| **Round Statistics** | ❌ Not Started | Post-round stats summary |

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
│   ├── RegionCommand.java
│   ├── RegionDb.java
│   ├── RegionNotificationManager.java
│   ├── RegionService.java
│   ├── SqlRegionService.java
│   ├── RegionState.java
│   ├── RegionStatus.java
│   └── InfluenceAction.java
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

### Priority 1: Core Gameplay Polish
1. **Test IP earning flow end-to-end** - Verify all actions award correct IP
2. **Test region capture flow** - Confirm ownership changes work
3. **Test supply disruption** - Verify penalties apply correctly

### Priority 2: Objectives System
1. Implement "Hold Ground" objective (simplest)
2. Add objective spawning/tracking
3. Add objective UI (boss bar or scoreboard)

### Priority 3: Win Conditions
1. Define win condition (capture all regions? home region?)
2. Implement win detection
3. Add round-end summary

### Priority 4: Quality of Life
1. Player statistics tracking
2. Post-round statistics display

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
- `/admin tp <player> <region>` - Teleport player
- `/admin supply debug <region> <team>` - Debug supply
- `/admin supply register <region> <team>` - Scan/register roads
- `/admin merit give <player> <amount> [merits|tokens]` - Give merits or tokens
- `/admin merit givetokens <player> <amount>` - Give tokens directly
- `/admin merit set <player> <amount>` - Set player's merits
- `/admin merit reset <player>` - Reset player's merit data
- `/admin merit info <player>` - View player's merit info
- `/admin merit leaderboard [count]` - View merit leaderboard

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

