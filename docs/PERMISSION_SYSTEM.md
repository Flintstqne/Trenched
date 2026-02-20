# Permission System Design

## Overview

This document outlines the permission-based access system for chests, containers, and structures in Entrenched. The system allows players to control who can access their placed items while integrating with the objective system and region capture mechanics.

---

## Container Permissions (Chests, Barrels, Shulker Boxes)

### Permission Categories

| Category | Symbol | Color | Who Can Access | Who Can Break |
|----------|--------|-------|----------------|---------------|
| **Public** | 🌐 | Green | Any teammate | Any teammate |
| **Division** | ⚔ | Yellow | Division members only | Division members only |
| **Private** | 🔒 | Red | Owner only | Owner only |

### Default Permissions

- **Player WITH Division**: Defaults to `DIVISION`
- **Player WITHOUT Division**: Defaults to `PUBLIC`

### Special Cases

- **Enemy Players**: 
  - Cannot access ANY friendly containers (regardless of permission)
  - Can only break containers if:
    - There's an active "Destroy Supply Cache" objective in the region
    - The container is tracked as a valid objective target
  
- **Supply Cache Objective**:
  - Only tracks containers placed by the defending team
  - Enemies can break these for the objective regardless of permission
  - Breaking completes the objective and awards IP

---

## UI/UX for Container Permissions

### Visual Display: Action Bar on Look

When a player's crosshair is pointed at a container (within 5 blocks), display permission info in the action bar:

**Format:**
```
📦 [PERMISSION] - Owner: PlayerName | [SHIFT+CLICK to change]
```

**Examples:**
```
📦 🌐 PUBLIC - Owner: Steve | [SHIFT+CLICK to change]
📦 ⚔ DIVISION - [WAR] Warriors | [SHIFT+CLICK to change]
📦 🔒 PRIVATE - Owner: Alex | [SHIFT+CLICK to change]
```

**For non-owners (no change option):**
```
📦 🔒 PRIVATE - Owner: Alex
📦 ⚔ DIVISION - [WAR] Warriors
```

**For enemy containers:**
```
📦 ⚠ ENEMY CONTAINER - Cannot access
```

### Permission Cycling

**Method: Shift + Right-Click (while sneaking)**
- Only works if you're the owner (or commander for DIVISION containers)
- Cycles through: PUBLIC → DIVISION → PRIVATE → PUBLIC
- Plays a click sound on change
- Action bar updates immediately to show new permission

**Note:** If player is not in a division, DIVISION option is skipped:
- PUBLIC → PRIVATE → PUBLIC

### Chat Messages

```
[Entrenched] Container permission changed to DIVISION (⚔)
             Only division members can access this container.

[Entrenched] You cannot access this container!
             This is a PRIVATE container owned by PlayerName.

[Entrenched] You cannot access this container!
             This is a DIVISION container belonging to [TAG] DivisionName.
```

---

## Structure Permissions (Banner-Based Claims)

### What is a "Structure Claim"?

A structure claim is a protected area defined by placing a team banner. The banner acts as the center of a circular claim zone where the placer controls who can build and break blocks.

### How Banner Claims Work

1. **Place a Team Banner** in an owned/contested region (your team must be the original owner)
2. **Claim Zone Created** - Circular area around banner (default 16 block radius)
3. **Permission Applied** - All blocks within radius inherit the banner's permission
4. **Visual Boundary** - Particle border shows claim edges to nearby players

### Permission Categories for Structures

| Category | Symbol | Who Can Build/Break | Visual Color | Radius |
|----------|--------|---------------------|--------------|--------|
| **Team** | 🌐 | Any teammate | Green particles | 16 blocks |
| **Division** | ⚔ | Division members only | Yellow particles | 12 blocks |
| **Private** | 🔒 | Owner only | Red particles | 8 blocks |

**Note:** Smaller radius for PRIVATE claims discourages land hogging while still allowing personal space.

### Claim Configuration

| Setting | Value | Description |
|---------|-------|-------------|
| TEAM Claim Radius | 16 blocks | Full radius for shared claims |
| DIVISION Claim Radius | 12 blocks | Reduced radius for division-only |
| PRIVATE Claim Radius | 8 blocks | Smallest radius to discourage hogging |
| Max Claims per Player | 3 | Personal limit (all permission types) |
| Max PRIVATE per Player | 1 | Only one personal stash allowed |
| Max Claims per Division | 10 | Shared pool for division members |
| Max Claims per Region | 15 | Prevents overclaiming any region |
| Max Claims per Team | 50 | Global team limit |
| Claim Overlap | Not allowed | Original claim has priority |
| Vertical Range | Y ± 32 blocks | From banner Y level |
| Placement Cooldown | 30 seconds | Prevents rapid spam |
| Inactivity Demote | 72 hours | PRIVATE demotes to TEAM if owner offline |

### Claim Overlap Rules

Claims cannot overlap. When placing a new banner:

1. **Check for Existing Claims** - System checks if new claim radius would intersect any existing claim
2. **Original Claim Priority** - If overlap detected, banner placement is **DENIED**
3. **Player Notification** - Message shown: `"Cannot place claim here - overlaps with existing claim by [Owner]"`

**Edge Cases:**
- Claims from the same owner CAN be adjacent (touching) but not overlapping
- Division claims count separately from personal claims for overlap purposes
- If a claim decays and is removed, the area becomes available for new claims

### Visual Display: Particle Borders

When a player approaches a claimed area (within 32 blocks), particle borders become visible:

**Particle Configuration:**
- **Border Style**: Vertical particle columns at claim edge, spaced every 4 blocks
- **Border Height**: 3 blocks tall from ground level
- **Particle Type**: Dust particles matching permission color
- **Refresh Rate**: Every 10 ticks (0.5 seconds)
- **Visibility**: Only visible to players within 32 blocks

**Color Coding:**
| Permission | Particle Color | RGB |
|------------|---------------|-----|
| Team | Lime Green | (0, 255, 0) |
| Division | Gold/Yellow | (255, 215, 0) |
| Private | Red | (255, 0, 0) |

**Center Marker:**
- Vertical beam of particles above the banner
- Same color as permission
- Visible from further away (64 blocks)

### Claim Interaction

**Shift + Right-Click on Claim Banner:**
- Cycles permission: TEAM → DIVISION → PRIVATE → TEAM
- Shows action bar: `🏰 Claim permission changed to DIVISION (⚔)`
- Particles update color immediately

**Breaking the Claim Banner:**
- Removes the claim entirely
- All blocks within former claim become unprotected
- Only owner (or commander for DIVISION claims) can break

### Claim Information Display

**When inside a claim, show boss bar:**
```
🏰 [ClaimName] - DIVISION - [WAR] Warriors
```

**Action bar when looking at claim banner:**
```
🏰 "Forward Base" - ⚔ DIVISION - Owner: Steve | [SHIFT+CLICK to change]
```

---

## Region Ownership & Claim Rules

### Claim Placement Restrictions

Claims can ONLY be placed in regions where your team has ownership rights:

| Region State | Original Owner | Can Claim? |
|--------------|----------------|------------|
| **Owned** | Your Team | ✅ Yes |
| **Contested** | Your Team | ✅ Yes (you're defending) |
| **Contested** | Enemy Team | ❌ No (you're attacking) |
| **Owned** | Enemy Team | ❌ No |
| **Neutral** | None | ❌ No (must capture first) |

**Rationale:** 
- Attackers should be raiding, not building permanent structures
- Defenders need to fortify their positions
- Forces teams to capture regions before establishing infrastructure

### What Happens When a Region Changes Hands?

When Team A loses a region to Team B, claims and permissions are handled simply:

#### Scenario 1: Region Becomes Contested (Enemy Attacking)
- **Claims Remain Active** - Defenders can still use their claims
- **No New Enemy Claims** - Attackers cannot place claims
- **Claim Protection Active** - Enemies cannot break blocks in claims (focus on objectives instead)
- **Visual Change** - Claim particles pulse/flash to indicate contested status

#### Scenario 2: Region Fully Captured by Enemy
When the region flips from Team A ownership to Team B ownership:

**Fresh Slate Approach:**
1. **All Claims Removed** - All banner claims in the region are deleted
2. **Banners Drop** - Physical banners drop as items at their locations
3. **All Container Permissions Wiped** - Containers become unowned/accessible
4. **Capturing Team Has Full Access** - Team B can freely build, break, and access everything

**Rationale:**
- Clean slate for the new owners
- Rewards successful capture
- Punishes defenders who don't evacuate
- Simple to understand and implement
- No complex decay timers

**Notifications:**
```
[Entrenched] ⚠ WARNING: Your region has been captured!
             All claims and container protections have been removed.
             Your banners have dropped at their locations.

[Entrenched] 🏆 Region "Shadowfen Valley" captured!
             All enemy claims have been cleared.
             You have full access to build and loot.
```

### Recapturing Regions

If Team A recaptures a region:
- Team B's claims (if any) are removed
- Team A must place new claims from scratch
- No restoration of old claims - they were fully removed

---

## Container Behavior During Region Changes

### Containers in Contested Regions
- **Owner Team**: Full access based on permission
- **Enemy Team**: Can only break for Supply Cache objective
- **Container Permission**: Unchanged

### Containers in Captured Regions
When a region is fully captured by the enemy team, **all container permissions are wiped**:

- **Fresh Slate**: All prior permissions are removed from the database
- **Enemy Access**: Capturing team can now freely access and break all containers
- **No Ownership**: Containers become "unowned" - anyone on the new owning team can interact
- **Loot Opportunity**: Creates a strategic incentive to capture regions with established infrastructure

**Rationale:**
- Rewards successful capture with access to enemy resources
- Punishes defenders who don't evacuate resources before losing region
- Simplifies the system (no complex decay mechanics for containers)
- Encourages strategic resource management

**What Happens:**
1. Region is captured by Team B
2. All container permissions in that region are **deleted**
3. Containers become accessible to Team B (like unowned PUBLIC containers)
4. Team B can claim containers by shift+clicking (sets new ownership)
5. Original Team A owners lose all access

**Notification to Defenders:**
```
[Entrenched] ⚠ Region "Shadowfen Valley" has been captured!
             All container protections in this region have been removed.
             Enemy team now has full access to your containers.
```

---

## Game Loop Integration

### Typical Gameplay Flow

1. **Team A owns Region X**
   - Players place claims and containers
   - Build fortifications, store resources

2. **Team B attacks Region X** (Contested)
   - Team A claims still work, can reinforce
   - Team B cannot place claims, focuses on objectives
   - Supply Cache objective targets Team A containers

3. **Team B captures Region X**
   - All Team A claims instantly removed (banners drop)
   - All container permissions wiped
   - Team B has full access to everything
   - Team B can now place their own claims

4. **Team A counter-attacks** (Contested again)
   - Team B claims still work (they're now defending)
   - If Team A recaptures, Team B's claims are removed
   - Fresh slate for whoever holds the region

### Strategic Implications

- **Defenders**: Evacuate valuable resources before losing a region
- **Attackers**: Focus on objectives, capture = full access to enemy infrastructure
- **Contested**: High-stakes - losing means losing ALL your claims
- **Resource Management**: Don't store irreplaceable items in contested regions

---

## Implementation Details

### Database Schema

```sql
-- Container permissions table
CREATE TABLE container_permissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    round_id INTEGER NOT NULL,
    world VARCHAR(64) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    owner_uuid VARCHAR(36) NOT NULL,
    team VARCHAR(16) NOT NULL,
    division_id INTEGER,  -- NULL if not division-locked
    permission VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    created_at BIGINT NOT NULL,
    UNIQUE(round_id, world, x, y, z)
);

-- Banner-based structure claims table
CREATE TABLE banner_claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    round_id INTEGER NOT NULL,
    region_id VARCHAR(8) NOT NULL,
    world VARCHAR(64) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    owner_uuid VARCHAR(36) NOT NULL,
    team VARCHAR(16) NOT NULL,
    division_id INTEGER,
    permission VARCHAR(16) NOT NULL DEFAULT 'TEAM',
    radius INTEGER NOT NULL DEFAULT 16,
    name VARCHAR(64),
    created_at BIGINT NOT NULL,
    UNIQUE(round_id, world, x, y, z)
);
```

### Permission Enums

```java
public enum ContainerPermission {
    PUBLIC("🌐", "Public", ChatColor.GREEN, "Any teammate"),
    DIVISION("⚔", "Division", ChatColor.YELLOW, "Division only"),
    PRIVATE("🔒", "Private", ChatColor.RED, "Owner only");
    
    private final String symbol;
    private final String displayName;
    private final ChatColor color;
    private final String description;
    
    // Constructor and getters...
    
    public ContainerPermission next(boolean hasDivision) {
        if (!hasDivision) {
            // Skip DIVISION for players without one
            return this == PUBLIC ? PRIVATE : PUBLIC;
        }
        return values()[(ordinal() + 1) % values().length];
    }
}

public enum ClaimPermission {
    TEAM("🌐", "Team", Color.LIME, "Any teammate"),
    DIVISION("⚔", "Division", Color.GOLD, "Division only"),
    PRIVATE("🔒", "Private", Color.RED, "Owner only");
    
    private final String symbol;
    private final String displayName;
    private final Color particleColor;
    private final String description;
    
    // Constructor and getters...
    
    public ClaimPermission next(boolean hasDivision) {
        if (!hasDivision) {
            return this == TEAM ? PRIVATE : TEAM;
        }
        return values()[(ordinal() + 1) % values().length];
    }
}
```

### Event Listeners

```java
// On container place - set default permission
@EventHandler
public void onContainerPlace(BlockPlaceEvent event) {
    if (isContainer(event.getBlock().getType())) {
        ContainerPermission perm = getDefaultPermission(player);
        saveContainerPermission(location, player, perm);
    }
}

// On container interact - check permission
@EventHandler
public void onContainerInteract(PlayerInteractEvent event) {
    if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
        if (isContainer(block.getType())) {
            if (player.isSneaking()) {
                cyclePermission(block, player);
                event.setCancelled(true);
            } else if (!canAccess(block, player)) {
                denyAccess(player, block);
                event.setCancelled(true);
            }
        }
    }
}

// On container break - check permission
@EventHandler
public void onContainerBreak(BlockBreakEvent event) {
    if (isContainer(block.getType())) {
        if (!canBreak(block, player)) {
            // Check for objective override
            if (!hasObjectiveOverride(block, player)) {
                denyBreak(player, block);
                event.setCancelled(true);
            }
        }
    }
}

// On banner place - create claim if in owned region
@EventHandler
public void onBannerPlace(BlockPlaceEvent event) {
    if (isBanner(event.getBlock().getType())) {
        String regionId = getRegionId(event.getBlock().getLocation());
        if (canPlaceClaim(player, regionId)) {
            createBannerClaim(location, player);
            showClaimCreatedMessage(player);
        }
    }
}

// On banner break - remove claim
@EventHandler
public void onBannerBreak(BlockBreakEvent event) {
    if (isBanner(event.getBlock().getType())) {
        BannerClaim claim = getClaim(location);
        if (claim != null && canBreakClaim(player, claim)) {
            removeClaim(claim);
        } else if (claim != null) {
            event.setCancelled(true);
            denyClaimBreak(player);
        }
    }
}

// On block place in claim - check permission
@EventHandler
public void onBlockPlaceInClaim(BlockPlaceEvent event) {
    BannerClaim claim = getClaimAt(event.getBlock().getLocation());
    if (claim != null && !canBuildInClaim(player, claim)) {
        event.setCancelled(true);
        denyBuild(player, claim);
    }
}

// On block break in claim - check permission
@EventHandler
public void onBlockBreakInClaim(BlockBreakEvent event) {
    BannerClaim claim = getClaimAt(event.getBlock().getLocation());
    if (claim != null && !canBreakInClaim(player, claim)) {
        event.setCancelled(true);
        denyBreak(player, claim);
    }
}
```

---

## Integration with Objective System

### Destroy Supply Cache
1. Objective spawns when enemy has chests in their owned region
2. UI shows direction to nearest enemy chest (regardless of permission)
3. Enemy player can break chest ONLY because of objective
4. Without objective, enemy cannot interact with OR break enemy containers

### Establish Outpost Objective
- Creating a banner claim with bed/chest/crafting table nearby = outpost established
- Claim must contain required blocks within its radius

### Garrison Quarters Objective
- Requires a banner claim with 3+ beds within its radius
- Validates that beds are within the claimed area

---

## Command Reference

```
# Container Commands
/container info                                  - Show info about looked-at container
/container default [public|division|private]     - Set your default for new containers

# Claim Commands (banner-based)
/claim info                 - Show info about claim you're standing in
/claim list                 - List your claims
/claim name <name>          - Name the claim you're standing in
```

**Note:** Most claim management is done through shift+click on banners, not commands.

---

## Phase 1 Implementation (Containers Only)

1. **ContainerPermission enum** - Define permission levels
2. **ContainerPermissionDb** - Database access layer
3. **ContainerPermissionService** - Business logic
4. **ContainerPermissionListener** - Event handling
5. **ContainerLookListener** - Action bar display when looking at containers
6. **Update ObjectiveListener** - Integrate with Supply Cache objective

### Estimated Complexity: Medium
- ~500-800 lines of new code
- New database table
- Integration with existing systems

---

## Phase 2 Implementation (Banner Claims)

1. **ClaimPermission enum** - Define permission levels
2. **BannerClaimDb** - Database access layer
3. **BannerClaimService** - Business logic (claim creation, permission checking)
4. **BannerClaimListener** - Event handling for banner place/break
5. **ClaimProtectionListener** - Block place/break permission in claims
6. **ClaimParticleRenderer** - Visual particle borders
7. **RegionCaptureIntegration** - Clear claims when region is captured
8. **ClaimCommand** - `/claim` command

### Estimated Complexity: High
- ~1000-1500 lines of new code
- Spatial queries for claim detection
- Particle rendering optimization
- Region capture integration

---

## Design Decisions

The following decisions balance anti-abuse measures with interactive, personal gameplay:

### 1. Claim Naming
**Decision: Optional naming, auto-generate if not provided**
- Players can optionally name claims via `/claim name <name>`
- Auto-generated names based on region + number (e.g., "Shadowfen-1", "Shadowfen-2")
- Names displayed in boss bar and action bar UI

### 2. Transfer Ownership
**Decision: Yes, but limited**
- Owner can transfer to another teammate: `/claim transfer <player>`
- Target must be in the same team
- Transfer is immediate, no approval needed
- Prevents "orphaned" claims when players leave

### 3. Division Changes
**Decision: Auto-demote to TEAM permission**
- When owner leaves their division, DIVISION claims become TEAM claims
- Prevents "orphaned" division-locked claims
- Owner retains ownership, just permission level changes
- Notification sent to owner: "Your claim has been demoted to TEAM permission"

### 4. Performance
**Decision: Spatial caching with region-based indexing**
- Cache claims by region ID (already have 16 regions max)
- Each region has at most ~20 claims (see limits below)
- On block event: O(1) region lookup → O(n) claim check where n ≤ 20
- Refresh cache when claims are created/removed
- Use squared distance checks (avoid sqrt) for circular bounds

### 5. Grief Prevention
**Decision: Commander override + proportion limits**
- Division Commanders can break ANY claim in their team (emergency override)
- Maximum 20% of team claims can be PRIVATE (see below)
- This ensures most team infrastructure remains accessible

### 6. Claim Limits
**Decision: Per-player limits with shared division pool**

| Limit Type | Amount | Notes |
|------------|--------|-------|
| Per Player | 3 claims | Personal limit regardless of division |
| Per Division | 10 claims | Shared pool for all division members |
| Per Region | 15 claims | Prevents any region from being overclaimed |
| Per Team | 50 claims | Global team limit across all regions |

**How Division Pool Works:**
- Division members share a pool of 10 "division claim slots"
- Player's personal claims (PRIVATE) count against their 3 personal limit
- Player's TEAM/DIVISION claims count against division pool
- Solo players (no division) can only place 3 TEAM or PRIVATE claims

---

## Anti-Spam System

**Multi-layered approach:**

### Layer 1: Claim Limits (see above)
Prevents raw spam via hard caps.

### Layer 2: Placement Cooldown
- **30-second cooldown** between placing claims
- Prevents rapid claim placement
- Cooldown resets on successful placement only
- Bypassed by commanders/officers for strategic flexibility

### Layer 3: No Resource Cost
- **Decision: No resource cost for claims**
- Rationale: Banners already cost wool + sticks to craft
- Adding more cost creates inventory management burden
- Focus on limits and cooldowns instead

---

## Anti-Land-Hogging System

**The core problem:** Players claiming PRIVATE land and locking teammates out of resources or strategic locations.

### Solution: Tiered Permission Radius

| Permission | Radius | Rationale |
|------------|--------|-----------|
| **TEAM** | 16 blocks | Full radius - encourages sharing |
| **DIVISION** | 12 blocks | Reduced - division is semi-private |
| **PRIVATE** | 8 blocks | Half radius - discourages hogging |

**Effect:** PRIVATE claims cover 1/4 the area of TEAM claims, naturally limiting how much land one player can lock.

### Solution: PRIVATE Claim Limits

| Restriction | Value | Rationale |
|-------------|-------|-----------|
| Max PRIVATE per player | 1 | One personal stash, no more |
| Max % PRIVATE per team | 20% | At most 10 of 50 team claims can be PRIVATE |

**Effect:** Each player gets ONE personal space. The rest must be shared.

### Solution: Inactivity Demotion

| Offline Duration | Action |
|------------------|--------|
| 0-48 hours | No change |
| 48-72 hours | Warning sent to owner (if they log in) |
| 72+ hours | PRIVATE demotes to TEAM |

**Effect:** Inactive players' private claims become accessible without destroying the claim.

**Notifications:**
```
[Entrenched] ⚠ Your PRIVATE claim "My Stash" has been inactive for 48 hours.
             It will demote to TEAM permission in 24 hours if you don't visit it.

[Entrenched] Your PRIVATE claim "My Stash" has been demoted to TEAM due to inactivity.
```

### Solution: Commander Override
- Division Commanders can break ANY friendly claim (including PRIVATE)
- Used for emergencies (player quit, blocking critical area, etc.)
- Logged for accountability: `[Audit] Commander Steve broke PRIVATE claim by Alex at (100, 64, 200)`

---

## Summary: Complete Anti-Abuse Config

```yaml
claims:
  limits:
    per_player: 3
    per_division: 10
    per_region: 15
    per_team: 50
    max_private_per_player: 1
    max_private_percent: 20
  
  radius:
    team: 16
    division: 12
    private: 8
  
  cooldown:
    placement_seconds: 30
    bypass_for_officers: true
  
  inactivity:
    warning_hours: 48
    demote_hours: 72
  
  override:
    commander_can_break_any: true
    log_overrides: true
```

---

## Gameplay Experience Summary

### For Solo Players (No Division)
- 3 personal claims max
- Can use TEAM (16 radius) or PRIVATE (8 radius)
- 1 PRIVATE claim max
- Simple, limited land control

### For Division Members
- 3 personal claims + share 10 division pool
- DIVISION claims (12 radius) for division-only areas
- 1 PRIVATE claim for personal stash
- Collaborative building encouraged

### For Division Commanders
- Same limits as members
- Emergency override to break any claim
- Responsible for managing division land use

### Anti-Abuse Guarantees
- ❌ Cannot spam claims (limits + cooldown)
- ❌ Cannot hog land with PRIVATE (1 max, small radius)
- ❌ Cannot lock team out permanently (inactivity demote)
- ❌ Cannot grief via claim placement (commander override)
- ✅ Still have personal space (1 PRIVATE claim)
- ✅ Still have division privacy (DIVISION permission)
- ✅ Still have autonomy (own your claims)

---

## Future Considerations

### Trusted Players System
- Allow adding specific players to PRIVATE claims/containers
- `/claim trust <player>` when standing in claim
- More flexible access control

### Time-based Auto-Unlock
- Containers auto-unlock after X minutes of owner being offline
- Prevents resource hoarding by inactive players
- Could be configurable per permission level

### Claim Upgrades
- Spend resources to increase claim radius
- Add defensive bonuses to claims
- Visual upgrades (better particles, holograms)

