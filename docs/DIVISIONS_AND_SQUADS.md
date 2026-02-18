# Divisions & Squads System Design

## Overview

Building on the existing **Red vs Blue** team warfare, this document explores adding **organizational structure** through **Divisions** and **Squads** (Parties). These are purely organizational tools—no gameplay bonuses or perks—designed to help large teams coordinate and communicate effectively.

---

## Current Structure

```
Team (Red/Blue)
  └── All Players (flat structure, chaotic)
```

## Proposed Structure

```
Team (Red/Blue)
  └── Division (Large organizational unit, 10-30+ players)
        └── Officers / Leaders
        └── Squads/Parties (Small friend groups, 2-6 players)
              └── Party Leader
              └── Members
```

---

## Divisions

Divisions are **large organizational units** within a team. They exist purely for **coordination, communication, and command structure**—not for gameplay bonuses.

### Purpose of Divisions

- **Organize large player counts** into manageable groups
- **Establish chain of command** for coordinated strategies
- **Separate communication channels** to reduce chat spam
- **Allow leadership** to assign objectives to groups

### Division Hierarchy

```
Division
  ├── Division Commander (1)
  │     └── Full control over division
  ├── Division Officers (2-4)
  │     └── Can invite/kick, manage squads, set waypoints
  └── Division Members
        └── Regular players in the division
```

### Division Features

#### 1. Division Chat
- `/dc <message>` - Division-only chat channel
- Keeps strategic communication separate from team-wide chat
- Officers can pin important messages or announcements

#### 2. Division Roster
- View all members and their current squads
- See who's online/offline

### Division Creation & Management

Divisions are **player-created** organizations. Any player can start a division if they meet the requirements.

#### Creating a Division
- `/division create <name> [TAG]` - Create a new division
- Name: Full name (e.g., "Northern Wolves", "Iron Legion")
- Tag: 2-5 character tag shown in chat (e.g., `[NW]`, `[IRON]`)
- Creator automatically becomes **Division Commander**

#### Division Display
```
Chat example:
[Red] [NW] ★ PlayerName: Let's push B2!
       │   │
       │   └── Commander star
       └── Division tag
```

#### Division Customization
- **Name**: Set at creation, changeable by commander
- **Tag**: 2-5 characters, shown in chat before player name
- **Description**: Optional motto or description
- **Color**: Division accent color (for markers, etc.)

---

## Division Creation Cost

Creating a division should have a **cost** to prevent spam and make divisions feel meaningful. Here are some options:

### Option A: Resource Cost
Require in-game resources to establish a division.

| Tier | Cost | Reasoning |
|------|------|-----------|
| **Cheap** | 32 Iron Ingots | Easy to get, low barrier |
| **Medium** | 1 Diamond Block (9 diamonds) | Moderate investment |
| **Expensive** | 16 Gold Blocks + 1 Diamond Block | Significant commitment |
| **Team Pool** | Withdrawn from team resource pool | Requires team-wide contribution |

**Pros**: Ties into gameplay, resources have meaning
**Cons**: New players may struggle, inflation over time

---

### Option B: Player Requirement
Require a minimum number of players to commit before the division forms.

| Requirement | How it works |
|-------------|--------------|
| **3 Players** | Need 2 others to co-sign before division is created |
| **5 Players** | More substantial group required |
| **Petition System** | Post a "division proposal", others sign up, auto-creates at threshold |

**Pros**: Ensures divisions have actual members, prevents single-player divisions
**Cons**: Harder to start, may discourage leadership

---

### Option C: Region Requirement
Require the team to control a minimum number of regions.

| Requirement | Reasoning |
|-------------|-----------|
| **Team owns 2+ regions** | Can't form divisions until you have territory |
| **1 region per division** | Max divisions = regions owned |
| **Headquarters** | Division must claim a region as "HQ" |

**Pros**: Ties divisions to territorial control, strategic depth
**Cons**: Early-game limitation, losing regions could orphan divisions

---

### Option D: Influence Point Cost
Use the existing IP (Influence Point) system.

| Cost | Notes |
|------|-------|
| **500 IP** | Personal IP spent by creator |
| **1000 IP** | Pooled from founding members |
| **Region capture equivalent** | Same cost as capturing a neutral region |

**Pros**: Uses existing system, rewards active players
**Cons**: New players have no IP, may feel grindy

---

### Option E: Time/Cooldown Based
Limit how often divisions can be created.

| Restriction | Effect |
|-------------|--------|
| **1 per phase** | Only one new division can form per phase |
| **Player cooldown** | Can only create a division once per round |
| **Team limit** | Max 3-5 divisions per team total |

**Pros**: Natural scarcity, prevents spam
**Cons**: First-come-first-served frustration

---

### Option F: Hybrid Approach (Recommended)
Combine multiple factors for balance:

```
Division Creation Requirements:
├── Minimum 3 founding members (including creator)
├── Resource cost: 16 Iron Blocks (144 iron ingots)
├── Team limit: Max 5 divisions per team
└── Cooldown: Creator can't make another for 24 hours (real time)
```

This ensures:
- ✅ Divisions have actual members from the start
- ✅ Moderate resource investment shows commitment
- ✅ Can't spam infinite divisions
- ✅ Leadership matters (cooldown prevents cycling)

---

### Option G: Earned Through Gameplay
Divisions unlock naturally through play:

| Unlock Method | Description |
|---------------|-------------|
| **First blood** | Team's first kill unlocks ability to form 1 division |
| **Region captured** | Each region captured unlocks 1 division slot |
| **Phase progression** | Phase 1: 1 div, Phase 2: 3 divs, Phase 3: 5 divs |
| **Player milestone** | When team reaches 10 players, divisions unlock |

**Pros**: Progression feels natural, rewards success
**Cons**: Losing team may never unlock divisions

---

## My Recommendation

**Option F (Hybrid)** with these specifics:

```yaml
divisions:
  creation:
    # Founding requirements
    min-founding-members: 3          # Need 2 others to co-found
    
    # Resource cost (paid by founder)
    cost:
      enabled: true
      items:
        - material: IRON_BLOCK
          amount: 8                   # 72 iron ingots
    
    # Team limits
    max-per-team: 5
    
    # Personal cooldown
    founder-cooldown-hours: 48        # Can't create another for 2 days
    
    # Phase restriction (optional)
    min-phase: 1                      # Can create from phase 1
```

**Why this works:**
1. **3 founding members** = divisions start with a real group
2. **8 iron blocks** = achievable but meaningful (~15 min of mining)
3. **Max 5 per team** = prevents fragmentation
4. **48hr cooldown** = founder commits to their division

---

## Division Management

## Squads (Parties)

Squads are **small informal groups**—essentially a party system for friends to stick together. No bonuses, just social and communication features.

### Purpose of Squads/Parties

- **Play with friends** - group up with people you know
- **Private communication** - party chat separate from division/team
- **Shared information** - see party members on map, compass pointing
- **Casual grouping** - no commitment, easy to join/leave

### Squad Structure

```
Squad/Party (2-6 players)
  ├── Party Leader (1)
  │     └── Can invite, kick, disband
  └── Party Members
        └── Just along for the ride
```

### Squad Features

#### 1. Party Chat
- `/pc <message>` or `/p <message>` - Party-only chat
- Quick coordination with your small group
- Separate from division and team chat

#### 2. Party Visibility
- See party members' **nameplates through walls** (short range)
- **Compass points** to party leader or nearest party member
- Party members shown on **BlueMap** with special marker

#### 3. Easy Management
- `/party invite <player>` - Invite someone
- `/party leave` - Leave the party
- `/party kick <player>` - Kick someone (leader only)
- `/party disband` - Disband the party (leader only)
- `/party promote <player>` - Transfer leadership

#### 4. No Requirements
- Don't need to be in same division
- Don't need to be doing the same thing
- Can party with anyone on your team

---

## How Divisions and Squads Interact

### Independent Systems
- Squads exist **independently** of divisions
- You can be in Division "Alpha" and partied with someone from Division "Bravo"
- Party is for friends, Division is for organization

### Example Scenario

```
RED TEAM
├── 1st Division (Commander: Steve)
│     ├── Squad: "The Boys" (Jim, Bob, Tim)
│     ├── Squad: "Builders Inc" (Alice, Carol)
│     └── Solo players: Dave, Frank, George
│
├── 2nd Division (Commander: Sarah)
│     ├── Squad: "Night Owls" (Mike, Lisa)
│     └── Solo players: Tom, Jerry
│
└── Unassigned Players
      └── NewPlayer123 (just joined, picking division)
```

### Communication Hierarchy

```
/tc or /team    →  All team members see this
/dc             →  Only your division sees this  
/pc or /party   →  Only your party sees this
```

---

## UI/UX Considerations

### Scoreboard Display

```
═══════════════════════════════
  BlockHole - Red Team
═══════════════════════════════
War: 3 | Phase: 2
Region: Shadowfen Valley
─────────────────────────────
Division: 1st Division
  Commander: SteveTheLeader
  Members: 12 online
─────────────────────────────
Party: The Boys [3/6]
  Leader: JimBob
─────────────────────────────
Team Regions: 8/16
═══════════════════════════════
```

### Commands

```
# Division Commands (All Players)
/division create <name> [TAG]  -- Create a division (requires cost + co-founders)
/division join <name|tag>      -- Request to join a division
/division leave                -- Leave your division
/division info [name]          -- View division details
/division list                 -- List all divisions on your team
/division roster               -- See all members of your division
/dc <message>                  -- Division chat

# Division Officer Commands
/division invite <player>      -- Invite player to division
/division kick <player>        -- Remove player from division
/division waypoint set <name>  -- Set a division waypoint
/division waypoint remove <name>
/division accept <player>      -- Accept join request

# Division Commander Commands
/division promote <player>     -- Promote to officer
/division demote <player>      -- Demote from officer
/division rename <name>        -- Rename division
/division tag <TAG>            -- Change division tag
/division description <text>   -- Set division description
/division transfer <player>    -- Transfer commander role
/division disband              -- Disband division (requires confirmation)

# Party/Squad Commands
/party create               -- Create a new party
/party invite <player>      -- Invite player
/party join <player>        -- Join someone's party (if invited)
/party leave                -- Leave your party
/party kick <player>        -- Kick player (leader only)
/party promote <player>     -- Transfer leadership
/party disband              -- Disband party
/party list                 -- See party members
/pc <message>               -- Party chat
/p <message>                -- Party chat (shorthand)
```

### Tab List Display

```
RED TEAM
─────────────────
[1st Div] ★ SteveTheLeader    -- ★ = Commander
[1st Div] • AliceOfficer       -- • = Officer  
[1st Div]   JimBob
[1st Div]   BobTheBuilder
[2nd Div] ★ SarahCommander
[2nd Div]   MikePlays
[    --  ]   NewPlayer123      -- No division yet
```

---

## Database Schema

```sql
-- Divisions
CREATE TABLE divisions (
  division_id INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id INTEGER NOT NULL,
  team TEXT NOT NULL,              -- 'red' or 'blue'
  division_name TEXT NOT NULL,
  division_tag TEXT NOT NULL,      -- 2-5 char tag like [NW], [IRON]
  description TEXT,                -- Optional motto/description
  founder_uuid TEXT NOT NULL,      -- Who created it
  created_at INTEGER NOT NULL,
  UNIQUE(round_id, team, division_name),
  UNIQUE(round_id, team, division_tag)
);

-- Division Membership
CREATE TABLE division_members (
  player_uuid TEXT NOT NULL,
  division_id INTEGER NOT NULL,
  round_id INTEGER NOT NULL,
  role TEXT DEFAULT 'MEMBER',      -- 'COMMANDER', 'OFFICER', 'MEMBER'
  joined_at INTEGER NOT NULL,
  PRIMARY KEY(player_uuid, round_id),
  FOREIGN KEY(division_id) REFERENCES divisions(division_id)
);

-- Division Join Requests (pending)
CREATE TABLE division_requests (
  request_id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid TEXT NOT NULL,
  division_id INTEGER NOT NULL,
  requested_at INTEGER NOT NULL,
  status TEXT DEFAULT 'PENDING',   -- 'PENDING', 'ACCEPTED', 'DENIED'
  FOREIGN KEY(division_id) REFERENCES divisions(division_id)
);


-- Division Creation Cooldowns (tracks who created divisions recently)
CREATE TABLE division_founder_cooldowns (
  player_uuid TEXT PRIMARY KEY,
  last_created_at INTEGER NOT NULL
);

-- Parties/Squads
CREATE TABLE parties (
  party_id INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id INTEGER NOT NULL,
  team TEXT NOT NULL,
  leader_uuid TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- Party Membership
CREATE TABLE party_members (
  player_uuid TEXT NOT NULL,
  party_id INTEGER NOT NULL,
  joined_at INTEGER NOT NULL,
  PRIMARY KEY(player_uuid, party_id),
  FOREIGN KEY(party_id) REFERENCES parties(party_id)
);
```

---

## Config Example

```yaml
divisions:
  enabled: true
  
  # Max divisions per team (0 = unlimited)
  max-per-team: 5
  
  # Min/max players per division (0 = no limit)
  min-players: 3
  max-players: 0
  
  # Auto-create divisions at round start
  auto-create:
    enabled: true
    names:
      - "1st Division"
      - "2nd Division"
      - "3rd Division"
  
  # Who can create new divisions
  creation:
    allow-player-creation: false   # Only admins/team leads can create
    require-min-players: 3         # Need 3 players to form new division

parties:
  enabled: true
  
  # Party size limits
  min-size: 2
  max-size: 6
  
  # Party features
  features:
    party-chat: true
    see-through-walls: true        # See nameplates through blocks
    see-through-walls-range: 30    # Block range
    compass-to-party: true         # Compass points to party members
    bluemap-markers: true          # Show party on BlueMap

# Chat channel prefixes
chat:
  team-prefix: "[Team]"
  division-prefix: "[Div]"
  party-prefix: "[Party]"
```

---

## Implementation Phases

### Phase 1: Divisions - Basic
1. Division database tables
2. Create/join/leave divisions
3. Division roster view
4. Division chat (`/dc`)

### Phase 2: Division Hierarchy
1. Commander/Officer roles
2. Invite/kick permissions
3. Promote/demote commands
4. Division management GUI

### Phase 3: Parties
1. Party database tables
2. Create/invite/leave parties
3. Party chat (`/pc`)
4. Party list command

### Phase 4: Visual Features
1. Compass pointing to party members
2. See party nameplates through walls
3. Tab list formatting with divisions
4. Scoreboard integration

### Phase 5: Advanced Features
1. Division waypoints
2. Division region assignments
3. BlueMap integration (party markers, division territories)

---

## Open Questions

1. **Should divisions be required?**
   - Option A: Yes, must join a division to play
   - Option B: No, unassigned players can still participate
   - Option C: Soft requirement (prompted but not forced)

2. **How are divisions created?**
   - Option A: Pre-created at round start (admin configured)
   - Option B: Players can create new divisions
   - Option C: Team commanders create divisions

3. **Can players be in multiple parties?**
   - Probably no—one party at a time keeps it simple

4. **What happens when division commander leaves?**
   - Auto-promote senior officer?
   - Division becomes leaderless until someone claims it?
   - Require commander to transfer before leaving?

5. **Should there be a "Team Commander" above divisions?**
   - One player per team who can coordinate all divisions?
   - Or keep it flat with division commanders as equals?

---

## Summary

This system provides **organizational structure without gameplay advantages**:

| Feature | Purpose |
|---------|---------|
| **Divisions** | Organize large groups, establish leadership, coordinate strategy |
| **Parties** | Small friend groups, casual coordination, social play |
| **Division Chat** | Strategic communication for your unit |
| **Party Chat** | Quick comms with your friends |
| **Waypoints** | Share locations within your division |
| **No Bonuses** | Pure organization—skill matters, not which group you're in |

The goal is to help players **self-organize** without creating imbalances. Good leadership and coordination should emerge naturally, not be forced through game mechanics.

---

## Next Steps

1. Decide if divisions are required or optional
2. Determine who can create divisions
3. Design the division selection flow
4. Implement basic party system first (simpler)
5. Add division features incrementally

What aspects would you like to explore further?

