# Merit System Design

## Overview

The Merit System is a **two-stage recognition and progression system**:

1. **Earn Merit Tokens** - Complete objectives and tasks to earn merit tokens
2. **Award Merit Tokens** - Give your earned tokens to other players to recognize them

This creates a system where **players must contribute to earn recognition currency**, then use that currency to acknowledge others. Accumulated received merits unlock **military-style ranks** that display as prefixes in chat.

---

## Core Concepts

### How Merits Work

```
┌─────────────────────────────────────────────────────────────────┐
│                        MERIT FLOW                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   PLAYER A                              PLAYER B                │
│   ─────────                             ─────────               │
│                                                                 │
│   [Completes Task]                                              │
│         │                                                       │
│         ▼                                                       │
│   [+1 Merit Token]  ───► /merit PlayerB ───►  [+1 Received]     │
│   (Spendable)                                  (Rank Points)    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Two Merit Currencies

| Currency | How Obtained | How Used | Persistence |
|----------|--------------|----------|-------------|
| **Merit Tokens** | Complete tasks/objectives | Give to other players | Permanent until spent |
| **Received Merits** | Other players give you tokens | Determines your rank | Permanent |

### Key Principle

> **You cannot increase your own rank.** You must contribute to earn tokens, then give those tokens to others who deserve recognition. Your rank depends entirely on how others recognize YOU.

### Merit Persistence

| Scope | Behavior |
|-------|----------|
| **Merit Tokens** | Persist across rounds until spent |
| **Received Merits** | Permanent, never decrease |
| **Rank** | Based on total received merits |

---

## Rank Structure

Military-themed ranks that reflect the warfare setting. Ranks are purely cosmetic and organizational - no gameplay advantages.

### Enlisted Ranks

| Rank | Tag | Merits Required | Description |
|------|-----|-----------------|-------------|
| **Recruit** | `[RCT]` | 0 | New players, fresh to the war |
| **Private** | `[PVT]` | 10 | Shown some commitment |
| **Private First Class** | `[PFC]` | 25 | Reliable soldier |
| **Corporal** | `[CPL]` | 50 | Experienced fighter |
| **Sergeant** | `[SGT]` | 100 | Proven leader potential |
| **Staff Sergeant** | `[SSG]` | 175 | Respected veteran |
| **Master Sergeant** | `[MSG]` | 275 | Elite enlisted |

### Officer Ranks

| Rank | Tag | Merits Required | Description |
|------|-----|-----------------|-------------|
| **Second Lieutenant** | `[2LT]` | 400 | Junior officer |
| **First Lieutenant** | `[1LT]` | 600 | Field leader |
| **Captain** | `[CPT]` | 850 | Company commander |
| **Major** | `[MAJ]` | 1,200 | Battalion level |
| **Lieutenant Colonel** | `[LTC]` | 1,750 | Senior officer |
| **Colonel** | `[COL]` | 2,500 | Regimental commander |

### General Ranks

| Rank | Tag | Merits Required | Description |
|------|-----|-----------------|-------------|
| **Brigadier General** | `[BG]` | 3,500 | One-star general |
| **Major General** | `[MG]` | 5,000 | Two-star general |
| **Lieutenant General** | `[LTG]` | 7,500 | Three-star general |
| **General** | `[GEN]` | 10,000 | Four-star general |
| **General of the Army** | `[GOA]` | 15,000 | Five-star, legendary |

### Chat Display Format

```
[Red] [SGT] [NW] PlayerName: Message here
  │     │    │
  │     │    └── Division tag (optional)
  │     └── Merit rank tag
  └── Team color
```

---

## Earning Merit Tokens

Players earn **Merit Tokens** by completing tasks and objectives. These tokens are then given to other players as recognition.

> **Balance Philosophy**: Tokens should be meaningful and relatively scarce. Earning a token should feel like an accomplishment, not a constant stream.

### 1. Combat Tokens

Actions in enemy or contested territory.

| Action | Tokens | Requirement | Notes |
|--------|--------|-------------|-------|
| **Kill Enemies in Their Territory** | 1 | 5 kills | Batched - earn 1 token per 5 enemy kills in enemy territory |
| **Kill Enemies (General)** | 1 | 10 kills | Batched - earn 1 token per 10 enemy kills anywhere |
| **Kill Streak (5)** | 1 | Per streak | Bonus for 5 kills without dying |
| **Kill Streak (10)** | 2 | Per streak | Unstoppable bonus |
| **First Blood (Round)** | 2 | Once per round | First kill of the round |
| **Shutdown** | 1 | Per event | End an enemy's 5+ kill streak |

### 2. Territory Tokens

Actions related to region capture and defense.

| Action | Tokens | Requirement | Notes |
|--------|--------|-------------|-------|
| **Capture Neutral Region** | 3 | Per capture | Claiming new land |
| **Capture Enemy Region** | 5 | Per capture | Taking enemy territory |
| **Participate in Capture** | 1 | Per capture | Earned 100+ IP in captured region |
| **Successfully Defend Region** | 2 | Per defense | Region stays owned after being contested |
| **Major Contributor** | 2 | Per capture | Top 3 IP contributors in a capture |

### 3. Logistics Tokens

Supply line and infrastructure contributions.

| Action | Tokens | Requirement | Notes |
|--------|--------|-------------|-------|
| **Complete Supply Route** | 2 | Per route | First road connecting two regions |
| **Establish Supply to New Region** | 1 | Per region | First time region reaches 100% supply |
| **Road Milestone** | 1 | Per 100 blocks | Every 100 road blocks placed |

### 4. Construction Tokens

Building and fortification efforts.

| Action | Tokens | Requirement | Notes |
|--------|--------|-------------|-------|
| **Major Fortification** | 1 | Per 100 blocks | Substantial defensive structure |
| **Establish Outpost** | 1 | 1 hour cooldown | Place workstation in neutral region |

### 5. Sabotage Tokens

Disrupting enemy operations.

| Action | Tokens | Requirement | Notes |
|--------|--------|-------------|-------|
| **Disrupt Enemy Supply Line** | 1 | Per disruption | Break enemy supply to a region |
| **Major Sabotage** | 2 | Per event | Disrupt supply to 3+ enemy regions at once |

### 6. Time-Based Tokens

Rewarding dedication and presence.

| Milestone | Tokens | Notes |
|-----------|--------|-------|
| **Daily Login** | 1 | First login of the day (must play 15+ min) |
| **Login Streak (7 days)** | 2 | Week of dedication |
| **Login Streak (30 days)** | 5 | Month of commitment |
| **Round Completion** | 1 | Online when round ends + earned 100+ IP |
| **Active Hour** | 1 | Per 2 hours active playtime (not AFK) |

### 7. Achievement Tokens (One-Time)

Special accomplishments that can only be earned once. Organized by category with beginner-friendly options.

#### 🎮 Getting Started (New Player Friendly)

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **First Steps** | 1 | Join a team for the first time |
| **Battle Ready** | 1 | Equip a full set of iron armor |
| **Armed and Dangerous** | 1 | Craft or obtain a diamond sword |
| **First Kill** | 1 | Get your first enemy kill |
| **Territorial** | 1 | Enter an enemy region for the first time |
| **Supply Runner** | 1 | Place your first road block |
| **Team Player** | 1 | Join a party |
| **Enlisted** | 1 | Join a division |
| **Fortifier** | 1 | Place 10 defensive blocks (walls, fences) |
| **Explorer** | 1 | Visit 4 different regions in one session |

#### ⚔️ Combat Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Warrior** | 2 | Get 25 enemy kills (lifetime) |
| **Veteran Fighter** | 3 | Get 100 enemy kills (lifetime) |
| **Battle Hardened** | 5 | Get 500 enemy kills (lifetime) |
| **Legend of War** | 8 | Get 1,000 enemy kills (lifetime) |
| **Streak Starter** | 1 | Get a 3 kill streak |
| **Rampage** | 2 | Get a 5 kill streak |
| **Unstoppable** | 3 | Get a 10 kill streak |
| **Untouchable** | 5 | Get a 15 kill streak |
| **Avenger** | 1 | Get 5 revenge kills (lifetime) |
| **Shutdown Artist** | 2 | End 10 enemy kill streaks (lifetime) |
| **Survivalist** | 2 | Survive 30 minutes in enemy territory |

#### 🏰 Territory Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Conqueror** | 2 | Capture your first region |
| **Empire Builder** | 3 | Be part of capturing 5 regions (lifetime) |
| **Warlord** | 5 | Be part of capturing 15 regions (lifetime) |
| **Dominator** | 8 | Be part of capturing 30 regions (lifetime) |
| **Defender** | 2 | Successfully defend a region from capture |
| **Iron Wall** | 4 | Successfully defend 10 regions (lifetime) |
| **Home Guard** | 2 | Kill 10 enemies in your home region |
| **Deep Strike** | 2 | Kill an enemy in their home region |
| **Frontline Fighter** | 2 | Earn 500 IP in contested regions (lifetime) |
| **Key Contributor** | 3 | Be top contributor in 3 region captures |

#### 🛤️ Logistics Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Road Builder** | 2 | Place 100 road blocks (lifetime) |
| **Highway Engineer** | 3 | Place 500 road blocks (lifetime) |
| **Infrastructure Master** | 5 | Place 2,000 road blocks (lifetime) |
| **Supply Chain** | 2 | Connect 3 regions with roads |
| **Logistics Expert** | 4 | Have 5+ regions at 100% supply |
| **Road Warrior** | 2 | Repair 5 damaged supply routes |
| **Saboteur** | 2 | Disrupt enemy supply lines 5 times |
| **Master Saboteur** | 4 | Disrupt enemy supply to 10+ regions total |

#### 🏗️ Construction Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Builder** | 1 | Place 100 blocks in friendly territory |
| **Architect** | 2 | Place 500 blocks in friendly territory |
| **Master Builder** | 4 | Place 2,000 blocks in friendly territory |
| **Fortification Expert** | 2 | Place 200 defensive blocks (walls, fences) |
| **Outpost Establisher** | 2 | Establish 3 outposts in neutral regions |
| **Light Bringer** | 1 | Place 50 torches/lanterns |

#### ⏱️ Dedication Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Newcomer** | 1 | Play for 1 hour total |
| **Regular** | 2 | Play for 10 hours total |
| **Dedicated** | 3 | Play for 50 hours total |
| **Veteran** | 5 | Play for 100 hours total |
| **No-Lifer** | 8 | Play for 500 hours total |
| **Consistent** | 2 | Login 7 days in a row |
| **Devoted** | 4 | Login 30 days in a row |
| **Round Veteran** | 2 | Complete 3 full rounds |
| **War Veteran** | 3 | Complete 10 full rounds |
| **War Hero** | 5 | Complete 25 full rounds |
| **Living Legend** | 8 | Complete 50 full rounds |

#### 🤝 Social Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Sociable** | 1 | Give your first merit to someone |
| **Generous** | 2 | Give 25 merits to others (lifetime) |
| **Appreciative** | 3 | Give 100 merits to others (lifetime) |
| **Philanthropist** | 5 | Give 500 merits to others (lifetime) |
| **Respected** | 2 | Receive 10 merits from others |
| **Admired** | 3 | Receive 50 merits from others |
| **Beloved** | 5 | Receive 200 merits from others |
| **Icon** | 8 | Receive 500 merits from others |
| **Diverse Recognition** | 2 | Receive merits from 10 different players |
| **Well Known** | 4 | Receive merits from 25 different players |
| **Division Founder** | 2 | Create a division |
| **Party Host** | 1 | Invite someone to a party |

#### 🏆 Special Achievements

| Achievement | Tokens | Description |
|-------------|--------|-------------|
| **Promoted** | 1 | Reach Private rank |
| **NCO** | 2 | Reach Sergeant rank |
| **Commissioned** | 3 | Reach Second Lieutenant rank |
| **Senior Officer** | 5 | Reach Major rank |
| **Flag Officer** | 8 | Reach Brigadier General rank |
| **Sportsmanship** | 1 | Give a merit to an enemy player |
| **Cross-Team Respect** | 2 | Receive a merit from an enemy player |
| **First Day Hero** | 3 | Earn 5+ tokens on your first day |
| **Round MVP** | 3 | Earn the most merits in a round |

---

## Giving Merit Tokens

### The `/merit` Command

Players spend their earned tokens to recognize others.

```
/merit <player>           - Give 1 token to a player
/merit <player> <amount>  - Give multiple tokens (if you have them)
/merit <player> <reason>  - Give 1 token with a public reason
```

### Basic Mechanics

| Aspect | Rule |
|--------|------|
| **Cost** | 1 merit token from YOUR balance per merit given |
| **Self-Merit** | Cannot merit yourself |
| **Notification** | Target sees: "PlayerName awarded you a merit! (+1 Received)" |
| **Announcement** | Server broadcasts recognition (configurable) |

### Recognition Categories (Optional Reason Tags)

Players can include a reason that gets broadcast:

```
/merit PlayerName combat     → "PlayerName recognized PlayerName for combat prowess!"
/merit PlayerName leadership → "PlayerName recognized PlayerName for leadership!"
/merit PlayerName teamwork   → "PlayerName recognized PlayerName for teamwork!"
/merit PlayerName building   → "PlayerName recognized PlayerName for construction!"
/merit PlayerName supply     → "PlayerName recognized PlayerName for logistics!"
/merit PlayerName bravery    → "PlayerName recognized PlayerName for bravery!"
```

---

## Anti-Farming Measures

Preventing abuse of the merit system through collusion or alt accounts.

### 1. Giving Restrictions

| Rule | Effect | Reasoning |
|------|--------|-----------|
| **Same Player Cooldown** | Can't give to same player more than 3x per day | Prevents targeted boosting |
| **Interaction Requirement** | Must have been in same region within last 30 min | Prevents blind merit trading |
| **Cross-Team Limit** | Max 2 merits to enemy team per day | Allows sportsmanship, limits abuse |
| **New Player Lockout** | Can't give merits until 2 hours playtime | Prevents alt account farming |

### 2. Receiving Restrictions

| Rule | Effect | Reasoning |
|------|--------|-----------|
| **Daily Cap** | Max 10 received merits per day | Limits boosting effectiveness |
| **Same Giver Cap** | Max 3 from same player per week | Prevents pair-farming |
| **New Account Lockout** | Can't receive merits until 1 hour playtime | Discourages alts |

### 3. Detection Systems

| Detection | Action |
|-----------|--------|
| **Rapid Exchange** | If A→B and B→A within 5 minutes, flag both accounts |
| **Pattern Detection** | Same group always meriting each other? Flag for review |
| **IP Correlation** | Same IP giving/receiving merits? Auto-block |
| **Unusual Ratios** | 90%+ of merits from one person? Flag account |

### 4. Flagged Account Consequences

| Severity | Action |
|----------|--------|
| **Warning** | Private message to player about suspicious activity |
| **Soft Ban** | Can't give/receive merits for 24 hours |
| **Merit Void** | Suspicious merits removed from both parties |
| **Hard Ban** | Permanent merit system ban (admin decision) |

### 5. Earning Restrictions

| Rule | Effect | Reasoning |
|------|--------|-----------|
| **Kill Same Player Cooldown** | No tokens for killing same player within 5 min | Prevents spawn-kill farming |
| **Party/Division Kill Exclusion** | No tokens for kills in same party/division (if friendly fire enabled) | Prevents arranged kills |
| **AFK Detection** | No time-based tokens if AFK > 5 minutes | Prevents idle farming |
| **Round Participation** | Must have earned 50+ IP in round for completion bonus | Must actually play |

### 6. Economy Balancing

| Mechanism | Effect |
|-----------|--------|
| **Token Scarcity** | Tokens are harder to earn than receive | Creates genuine scarcity |
| **No Hoarding Benefit** | Tokens don't compound - only received merits matter | Encourages spending |
| **Decay on Inactivity** | Unspent tokens decay 10% per month (optional) | Keeps economy active |

---


## Scoreboard Display

### Merit Section on Scoreboard

```
═══════════════════════════
  §6§lWar 3 §7| §fPhase 2
═══════════════════════════
  §fRank: §e[SGT] Sergeant
  §fReceived: §a1,247 §7(+5 today)
  §fTokens: §b23 §7available
  §fNext: §71,500 §8(Staff Sergeant)
───────────────────────────
  §fRegion: §bShadowfen Valley
  §fOwner: §cRed §7| §aContested
  §fYour IP: §e127§7/§f500
  §fSupply: §a100%
═══════════════════════════
```

### Key Elements

- **Current Rank** with tag and full name
- **Received Merits** - Determines rank, shows daily gain
- **Token Balance** - How many you can give away
- **Progress to Next Rank** showing threshold

---

## Commands

### Player Commands

| Command | Description |
|---------|-------------|
| `/merit <player>` | Award 1 merit to a player |
| `/merit <player> <reason>` | Award merit with category reason |
| `/merits` | View your merit stats |
| `/merits <player>` | View another player's merit stats |
| `/ranks` | View all ranks and requirements |
| `/leaderboard merits` | View top merit earners |

### Admin Commands

| Command | Description |
|---------|-------------|
| `/admin merit give <player> <amount>` | Give merits |
| `/admin merit remove <player> <amount>` | Remove merits |
| `/admin merit set <player> <amount>` | Set exact merit count |
| `/admin merit reset <player>` | Reset to 0 merits |

---

## Configuration

```yaml
# Merit System Settings
merits:
  # Enable merit system
  enabled: true
  
  # Peer recognition settings
  peer-recognition:
    enabled: true
    merits-per-recognition: 1
    same-player-cooldown-hours: 1
    daily-limit: 5
    allow-cross-team: true
    broadcast-recognition: true
  
  # Time-based merits
  playtime:
    enabled: true
    first-hour-merits: 5
    hourly-merits: 2
    daily-login-merits: 5
    weekly-streak-merits: 25
    monthly-streak-merits: 100
  
  # Combat merits
  combat:
    kill-enemy: 3
    kill-in-enemy-territory: 5
    defend-kill: 4
    revenge-kill: 2
    first-blood: 15
    kill-streak-3: 5
    kill-streak-5: 10
    kill-streak-10: 25
  
  # Territory merits
  territory:
    capture-neutral: 25
    capture-enemy: 50
    participate-capture: 10
    defend-region: 15
  
  # Logistics merits
  logistics:
    road-blocks-per-merit: 10
    complete-supply-route: 20
    repair-road: 5
    establish-supply: 15
  
  # Display settings
  display:
    show-rank-in-chat: true
    show-rank-in-tab: true
    show-on-scoreboard: true
    rank-color: GOLD
```

---

## Database Schema

```sql
-- Player merit tracking
CREATE TABLE player_merits (
    uuid TEXT PRIMARY KEY,
    -- Token balance (earned, can be given away)
    token_balance INTEGER DEFAULT 0,
    tokens_earned_today INTEGER DEFAULT 0,
    last_token_date TEXT,
    -- Received merits (determines rank)
    received_merits INTEGER DEFAULT 0,
    received_today INTEGER DEFAULT 0,
    last_received_date TEXT,
    -- Giving tracking
    merits_given_today INTEGER DEFAULT 0,
    last_given_date TEXT,
    -- Lifetime stats
    lifetime_tokens_earned INTEGER DEFAULT 0,
    lifetime_merits_given INTEGER DEFAULT 0,
    lifetime_merits_received INTEGER DEFAULT 0,
    lifetime_kills INTEGER DEFAULT 0,
    lifetime_captures INTEGER DEFAULT 0,
    lifetime_road_blocks INTEGER DEFAULT 0,
    rounds_completed INTEGER DEFAULT 0,
    playtime_minutes INTEGER DEFAULT 0,
    login_streak INTEGER DEFAULT 0,
    last_login_date TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Merit transaction log (both earning tokens and giving/receiving)
CREATE TABLE merit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL,
    transaction_type TEXT NOT NULL,  -- 'EARN_TOKEN', 'GIVE_MERIT', 'RECEIVE_MERIT'
    amount INTEGER NOT NULL,
    source TEXT NOT NULL,  -- 'combat', 'territory', 'peer', 'achievement', etc.
    reason TEXT,
    other_player TEXT,  -- UUID of other party (giver/receiver)
    round_id INTEGER,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Achievement tracking
CREATE TABLE player_achievements (
    uuid TEXT NOT NULL,
    achievement_id TEXT NOT NULL,
    unlocked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid, achievement_id)
);

-- Peer recognition cooldowns (giving restrictions)
CREATE TABLE merit_cooldowns (
    giver_uuid TEXT NOT NULL,
    receiver_uuid TEXT NOT NULL,
    times_given_today INTEGER DEFAULT 0,
    times_given_this_week INTEGER DEFAULT 0,
    last_given TIMESTAMP,
    PRIMARY KEY (giver_uuid, receiver_uuid)
);

-- Player interaction tracking (for anti-farming)
CREATE TABLE player_interactions (
    player1_uuid TEXT NOT NULL,
    player2_uuid TEXT NOT NULL,
    region_id TEXT,
    interaction_type TEXT,  -- 'same_region', 'combat', 'trade'
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Flagged accounts for suspicious activity
CREATE TABLE merit_flags (
    uuid TEXT NOT NULL,
    flag_type TEXT NOT NULL,  -- 'rapid_exchange', 'pattern', 'ip_correlation', 'ratio'
    severity TEXT NOT NULL,   -- 'warning', 'soft_ban', 'hard_ban'
    details TEXT,
    flagged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    resolved BOOLEAN DEFAULT FALSE
);
```

---

## Extended Ideas

### 1. Merit Decay (Optional)

To prevent "retired" players from holding high ranks forever:

| Scenario | Effect |
|----------|--------|
| **Inactive 30 days** | -5% merits per week |
| **Inactive 90 days** | Rank frozen, stops showing |
| **Returns** | Decay stops, regains rank |

*Could be controversial - make this configurable or optional.*

### 2. Seasonal Ranks

Separate ranking for each "season" (set of rounds):

- **Lifetime Rank**: Total all-time merits
- **Season Rank**: Merits this season only
- **Round MVP**: Most merits in current round

### 3. Rank Insignias

Visual indicators of rank:

| Rank Tier | Insignia |
|-----------|----------|
| Enlisted | No special indicator |
| NCO (SGT-MSG) | ⚔ symbol |
| Officers | ★ symbol |
| Generals | ★★ to ★★★★★ |

### 4. Merit Milestones

Special announcements for crossing thresholds:

```
[Server] PlayerName has been promoted to Sergeant! [SGT]
[Server] PlayerName earned their 1,000th merit!
```

### 5. Division Merit Leaderboard

Track division collective merits:

```
/division merits  - View division's total merits
                  - Average merits per member
                  - Top contributors
```

### 6. Merit Rewards (Optional)

Physical rewards for reaching ranks (if desired):

| Rank | Reward |
|------|--------|
| Sergeant | Recipe unlock: Special banner pattern |
| Captain | Access to /hat command |
| Colonel | Custom particle effect |
| General | Unique chat color option |

*Keep rewards cosmetic only to avoid pay-to-win concerns.*

### 7. Commendation System

Officers (Captain+) can give special commendations:

```
/commend <player> <reason>
```

- Worth 5 merits instead of 1
- Limited to 1 per day per officer
- Displayed prominently

---

## Implementation Priority

### Phase 1: Core System
1. Database tables and MeritService
2. Basic merit tracking (kills, captures)
3. Rank calculation and display
4. Scoreboard integration

### Phase 2: Peer Recognition
1. `/merit` command
2. Cooldown system
3. Daily limits
4. Recognition broadcasts

### Phase 3: Time & Achievements
1. Playtime tracking
2. Login streaks
3. Achievement system
4. One-time unlocks

### Phase 4: Polish
1. Leaderboards
2. Admin commands
3. Configuration options
4. Promotion announcements

---

## Summary

The Merit System provides:

✅ **Progression** - Clear path from Recruit to General  
✅ **Recognition** - Players can appreciate each other  
✅ **Visibility** - Ranks show in chat, tab, scoreboard  
✅ **Motivation** - Rewards all play styles (combat, building, logistics)  
✅ **Persistence** - Merits survive across rounds  
✅ **Configurability** - Admins can tune all values  

This creates a long-term progression system that complements the round-based warfare gameplay!

---

## PlaceholderAPI Integration

The merit system integrates with **PlaceholderAPI** to provide placeholders for use with any plugin that supports PAPI (tab plugins, chat formatters, scoreboards, etc.).

### Available Placeholders

#### Rank Information
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_rank%` | Full rank name | `Private First Class` |
| `%entrenched_rank_tag%` | Rank abbreviation | `PFC` |
| `%entrenched_rank_formatted%` | Colored tag with brackets | `§7[PFC]§r` |
| `%entrenched_rank_color%` | Rank color code | `§7` |

#### Token & Merit Counts
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_tokens%` | Current token balance | `15` |
| `%entrenched_merits%` | Total received merits | `127` |
| `%entrenched_merits_today%` | Merits received today | `3` |
| `%entrenched_tokens_earned%` | Lifetime tokens earned | `256` |
| `%entrenched_merits_given%` | Lifetime merits given | `89` |

#### Progression
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_next_rank%` | Next rank name | `Corporal` |
| `%entrenched_merits_to_next%` | Merits needed for next rank | `23` |
| `%entrenched_progress%` | Progress to next rank (%) | `54` |

#### Lifetime Stats
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_kills%` | Total kills | `432` |
| `%entrenched_captures%` | Regions captured | `15` |
| `%entrenched_road_blocks%` | Road blocks placed | `1250` |
| `%entrenched_rounds%` | Rounds completed | `12` |
| `%entrenched_playtime%` | Formatted playtime | `5d 12h` |
| `%entrenched_playtime_hours%` | Playtime in hours | `132` |
| `%entrenched_streak%` | Login streak in days | `7` |

#### Division Information
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_division%` | Division name | `1st Infantry` |
| `%entrenched_division_tag%` | Division tag | `1ST` |
| `%entrenched_division_formatted%` | Colored tag with brackets | `§c[1ST]§r` |
| `%entrenched_division_name%` | Division name (same as division) | `1st Infantry` |
| `%entrenched_division_description%` | Division description | `Elite combat unit` |
| `%entrenched_division_team%` | Division's team | `red` |
| `%entrenched_division_id%` | Division ID (internal) | `5` |
| `%entrenched_division_role%` | Player's role | `COMMANDER` |
| `%entrenched_division_role_short%` | Short role | `CMD` |
| `%entrenched_division_role_display%` | Display role | `Commander` |
| `%entrenched_division_members%` | Member count | `12` |
| `%entrenched_has_division%` | Has a division? | `true/false` |
| `%entrenched_is_commander%` | Is division commander? | `true/false` |
| `%entrenched_is_div_officer%` | Is division officer+? | `true/false` |

#### Prefixes (for chat/tab plugins)
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_prefix%` | Rank tag only | `§7[PFC]§r` |
| `%entrenched_team_prefix%` | Rank + team color | `§7[PFC]§r §c` |
| `%entrenched_division_prefix%` | Division tag with team color | `§c[1ST]§r` |
| `%entrenched_full_prefix%` | [DIV] [RANK] format | `§c[1ST] §7[PFC]§r ` |
| `%entrenched_chat_prefix%` | Full chat prefix: [DIV] [RANK] | `§c[1ST]§r §7[PFC]§r ` |
| `%entrenched_name_prefix%` | For tab/nametag: [DIV] [RANK] TEAMCOLOR | `§c[1ST] §7[PFC]§r §c` |
| `%entrenched_chat_format%` | Dynamic format from config with {message} | See below |

### Configurable Chat Format

The `%entrenched_chat_format%` placeholder uses formats defined in `config.yml` under `merit.chat`:

```yaml
merit:
  chat:
    # Format for players with a division
    with-division: "{division} {rank} {team_color}{player}&7: &f{message}"
    
    # Format for players without a division  
    without-division: "{rank} {team_color}{player}&7: &f{message}"
    
    # Format for players with no team
    no-team: "{rank} &7{player}&7: &f{message}"
```

#### Available Format Placeholders:
| Placeholder | Description |
|-------------|-------------|
| `{rank}` | Formatted rank tag with color (e.g., `§7[PFC]§r`) |
| `{rank_name}` | Full rank name (e.g., `Private First Class`) |
| `{rank_tag}` | Rank abbreviation only (e.g., `PFC`) |
| `{division}` | Formatted division tag with team color (e.g., `§c[1ST]§r`) |
| `{division_name}` | Division name (e.g., `1st Infantry`) |
| `{division_tag}` | Division tag only (e.g., `1ST`) |
| `{team_color}` | Team color code (`§c` for red, `§9` for blue) |
| `{player}` | Player name |
| `{message}` | Chat message |

Color codes use `&` prefix (e.g., `&c` = red, `&f` = white, `&7` = gray).

#### Boolean Checks
| Placeholder | Description | Example |
|-------------|-------------|---------|
| `%entrenched_is_officer%` | Is player an officer? | `true/false` |
| `%entrenched_is_general%` | Is player a general? | `true/false` |
| `%entrenched_is_nco%` | Is player an NCO? | `true/false` |
| `%entrenched_rank_level%` | Rank ordinal (0-18) | `5` |

### Example: TAB Plugin Format

```yaml
# TAB plugin config example
tablist-name-format: "%entrenched_rank_formatted% %player%"
chat-format: "%entrenched_team_prefix%%player%&7: &f%message%"
```

### Example: LuckPerms Chat Meta

```
/lp group default meta addprefix 1 "%entrenched_prefix% "
```


