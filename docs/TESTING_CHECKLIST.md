# Testing Checklist for Recent Features

This checklist covers all recently implemented features that need testing.

---

## 📊 Statistics System

### Basic Stat Tracking
- [x] **Kills/Deaths** - Kill another player and verify stats increment
- [x] **Assists** - Deal damage to a player within 10 seconds of their death (someone else gets kill)
- [x] **Kill Streak** - Get multiple kills without dying, verify `kill_streak_current` and `kill_streak_best`
- [ ] **Commander Kills** - Kill a division commander or officer, verify `commander_kills` stat
- [ ] **Damage Dealt/Taken** - Verify damage stats track PvP damage

### Login & Time Tracking
- [x] **Time Played** - Stay online for 2+ minutes, verify `time_played` increments
- [ ] **Time in Enemy Territory** - Stand in enemy region for 2+ minutes
- [x] **Login Streak** - Log in on consecutive days (or test by manipulating `last_login`)
- [x] **Login Streak Reset** - Verify streak resets after 36+ hours offline

### Objective Stats
- [ ] **Capture Intel** - Complete intel objective, verify `intel_captured` stat
- [ ] **Plant TNT** - Complete TNT plant objective, verify `tnt_planted` stat
- [ ] **Defuse TNT** - As defender, break planted TNT, verify `tnt_defused` stat
- [ ] **Destroy Supply Cache** - Complete cache objective, verify `supply_caches_destroyed`
- [ ] **Hold Ground Win** - Complete hold ground as attacker, verify `hold_ground_wins`
- [ ] **Resource Depot** - Complete resource depot objective, verify `resource_depots_established`
- [ ] **Objectives Completed** - Verify total `objectives_completed` increments
- [ ] **Settlement vs Raid** - Verify `objectives_settlement` and `objectives_raid` categorization

### Territory Stats
- [ ] **IP Earned** - Earn IP through any action, verify `ip_earned` stat tracks
- [ ] **Region Captured** - Capture a region, verify top IP contributor gets `regions_captured`
- [ ] **Region Defended** - Clear enemy IP from contested region via defender kills, verify `regions_defended`
- [ ] **Container Stocked** - Stock a container to 500+ items, verify `containers_stocked` stat
- [ ] **Container Unstocked** - Remove items below 500, verify stat is revoked

### Depot Stats
- [ ] **Depot Placed** - Place a division depot, verify `depots_placed` stat
- [ ] **Depot Raided** - Raid enemy depot, verify `depots_raided` and `depot_loot_value`

### Commands
- [x] `/stats` - View your own stats
- [x] `/stats <player>` - View another player's stats
- [x] `/stats leaderboard kills` - View kills leaderboard
- [x] `/stats leaderboard objectives` - View objectives leaderboard
- [x] `/stats round` - View current round stats
- [x] `/stats round <id>` - View specific round stats
- [x] `/stats team red` - View red team aggregate stats
- [x] `/stats team blue` - View blue team aggregate stats

### Admin Commands
- [x] `/admin stats list` - List all rounds with stats
- [x] `/admin stats purge <roundId>` - Purge stats (run twice to confirm)

### REST API (if enabled)
- [ ] `GET /api/health` - Returns status OK
- [ ] `GET /api/player/{uuid}` - Returns player stats with cached username
- [ ] `GET /api/leaderboard/kills` - Returns leaderboard
- [ ] `GET /api/round/{id}` - Returns round summary
- [ ] Rate limiting - Verify 429 after 60+ requests/minute

---

## 🏗️ Building/Structure System

### Outpost Detection
- [ ] Build a valid outpost structure (24+ structural blocks, 14+ footprint, 6+ interior)
- [ ] Include required elements: chest, crafting table, entrance, 60%+ roof coverage
- [ ] Verify detection at 99%+ and 3-second validation timer
- [ ] Verify building registers as ACTIVE after completion
- [ ] Verify building limit (2 outposts per region) is enforced

### Watchtower Detection
- [ ] Build a valid watchtower (vertical structure, viewing platform)
- [ ] Verify 1 watchtower limit per region

### Garrison Detection
- [ ] Build a valid garrison (larger structure with beds, storage)
- [ ] Verify 1 garrison limit per region

### Building Benefits
- [ ] Outpost provides speed boost in radius
- [ ] Watchtower reveals enemies (glowing effect)
- [ ] Buildings excluded from road damage system

### Building Destruction
- [ ] Break enough blocks to invalidate a building
- [ ] Verify building status changes to DESTROYED
- [ ] Verify team notification "Outpost destroyed!"
- [ ] Verify building can be rebuilt

### Debug Logging
- [ ] Enable verbose logging, verify component analysis logs appear
- [ ] Check logs show: structural blocks, footprint, interior, roof%, scores

---

## 🏪 Division Depot System

### Placement
- [x] Craft depot block using copper chest recipe
- [x] Place depot as officer/commander
- [x] Verify non-officers cannot place
- [x] Verify depot limit per division enforced
- [x] Verify particle effects on placement

### Storage
- [x] Open depot, verify division shared inventory opens
- [x] Add items, close, reopen - verify items persist
- [x] Other division member opens same depot - sees same inventory
- [x] Different division member opens - sees THEIR division's inventory

### Raiding
- [x] Enemy obtains raid tool (`/admin depot givetool <player>`)
- [x] Enemy channels on vulnerable depot (in captured region)
- [x] Verify 5-second channel timer
- [x] Verify 30% of items drop on successful raid
- [x] Verify depot block is removed
- [x] Verify raid stat is recorded

### Vulnerability
- [x] Depot in home region - NOT vulnerable
- [x] Depot in owned region - NOT vulnerable
- [x] Depot in captured-by-enemy region - VULNERABLE

---

## 🎯 Objectives System

### Settlement Objectives
- [x] **Establish Resource Depot** - Place 4+ containers with 500+ items each near objective marker
- [x] Verify compass shows direction to objective
- [x] Verify boss bar shows progress
- [x] Verify container tracking (break container reduces progress)

### Raid Objectives
- [x] **Capture Intel** - Pick up intel item, return to friendly territory
- [x] Verify glowing effect on carrier
- [x] Verify 10-minute timeout
- [x] Verify dropped intel can be recovered by defender
- [x] **Plant Explosive** - Place TNT at location, defend 30 seconds
- [x] Verify TNT explodes after timer
- [x] Verify defender can defuse by breaking TNT
- [] **Hold Ground** - Stand in region center for required time
- [ ] Verify defenders can contest
- [x] **Destroy Supply Cache** - Only spawns if enemy has chests in region
- [x] **Assassinate Commander** - Only spawns if enemy officers online

### Objective UI
- [x] `/obj` shows objectives in current region
- [x] Scoreboard compass updates direction
- [x] Objectives only show for valid regions (adjacent/contested)
- [x] Defenders see alerts when enemies start raid objectives

---

## 🏁 Endgame System

### Regulation
- [ ] Territory counting works correctly (non-home regions)
- [ ] Winner determined at Phase 3 end by territory majority
- [ ] Tie goes to overtime

### Early Win
- [ ] 10/14 regions + 4 lead triggers breakthrough timer
- [ ] 30-minute hold required
- [ ] Timer resets if lead lost

### Overtime
- [ ] 15-minute overtime starts on tie
- [ ] Single target region selected (highest heat)
- [ ] 5-minute hold on target wins
- [ ] Owner at overtime end wins

---

## 🔧 Admin Commands

- [x] `/admin region setowner <region> <team>` - Sets owner
- [x] `/admin region setstate <region> <state>` - Sets state (neutral/contested/owned/fortified)
- [x] `/admin phase advance` - Advances phase
- [ ] `/admin stats purge <roundId>` - Purges round stats

---

## 🐛 Known Issues to Watch For

1. **Ghost Blocks** - After building detection, blocks may appear different client-side. Rejoin to fix.
2. **Double Chest Counting** - Verify resource depot counts double chests as 1 container, not 2
3. **Stat Flush Delay** - Stats batch every 10 seconds, may not appear immediately
4. **Offline Username** - Verify API returns cached username for offline players
5. **Intel Glowing** - Verify glowing effect removed after intel delivered or timeout

---

## 📝 Quick Smoke Test

1. Join server, pick a team
2. Run `/stats` - should show empty stats
3. Kill a player, run `/stats` - should show 1 kill
4. Complete any objective, run `/stats` - should show objectives
5. Run `/stats leaderboard kills` - should show you on leaderboard
6. Place a container near resource depot objective - should update progress
7. Build a small outpost structure - should detect and register

---

## 🔄 Stats Wiring Status

### ✅ Fully Wired Stats
| Method | Wired In | Trigger |
|--------|----------|---------|
| `recordIntelRecovered()` | ObjectiveListener | Defender picks up dropped intel |
| `recordHoldGroundDefend()` | SqlObjectiveService | Defender kills attacker with hold progress |
| `recordBuildingConstructed()` | SqlObjectiveService | Building registered as ACTIVE |
| `recordBuildingDestroyed()` | SqlObjectiveService | Building invalidated after damage - credits all recent damagers (incl. TNT) |
| `recordRoadsBuilt()` | RoadListener | Path block placed or shovel used |
| `recordRoadsDamaged()` | RoadListener | Enemy road block broken |
| `recordBannerPlaced()` | SqlRegionService | Banner placed in neutral/enemy region |
| `recordRegionContested()` | SqlRegionService | Region state changes to CONTESTED |
| `recordIPDenied()` | SqlRegionService | Defender kill in contested region |
| `recordTntDefused()` | ObjectiveListener | Defender breaks planted TNT |
| `recordDepotPlaced()` | DepotListener | Division depot placed |
| `recordDepotRaided()` | DepotListener | Enemy depot raided |
| `recordRegionCaptured()` | SqlRegionService | Top IP contributor gets credit |
| `recordContainerStocked()` | ObjectiveListener | Player stocks container to 500+ items (on inventory close) |
| `recordRegionDefended()` | SqlRegionService | Defender kill clears enemy IP and returns region to OWNED |

### ✅ All Stats Fully Wired
All stat tracking methods have been implemented and wired into the appropriate game events.

