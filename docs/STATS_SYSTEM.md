# Statistics System

The Entrenched statistics system tracks 35+ player statistics across 5 categories, provides in-game commands, and exposes a REST API for external access (e.g., Discord bots).

## Overview

- **Async Batched Writes**: Stats are queued and flushed every 10 seconds to avoid lag
- **Lifetime + Per-Round Stats**: Track both permanent lifetime stats and per-round progress
- **MVP Calculation**: Weighted formula determines round MVP
- **REST API**: Rate-limited API for Discord bots and web dashboards
- **Login Streaks**: Tracks consecutive login days (resets after 36 hours offline)

---

## Stat Categories

### Combat Stats (9)

| Stat | Description |
|------|-------------|
| `kills` | Total player kills |
| `deaths` | Total deaths |
| `assists` | Kill assists (damage within 10s of kill) |
| `kill_streak_best` | Highest kill streak achieved |
| `kill_streak_current` | Current kill streak (resets on death) |
| `commander_kills` | Officers/commanders killed |
| `revenge_kills` | Killed a player who recently killed you |
| `damage_dealt` | Total damage dealt to players |
| `damage_taken` | Total damage received from players |

### Territory Stats (6)

| Stat | Description |
|------|-------------|
| `regions_captured` | Total regions captured |
| `regions_contested` | Times pushed a region into contested |
| `regions_defended` | Defended a contested region back to owned |
| `ip_earned` | Total influence points earned |
| `ip_denied` | IP removed from enemy (defensive actions) |
| `time_in_enemy_territory` | Minutes spent in enemy regions |

### Objective Stats (11)

| Stat | Description |
|------|-------------|
| `objectives_completed` | Total objectives completed |
| `objectives_settlement` | Settlement objectives completed |
| `objectives_raid` | Raid objectives completed |
| `intel_captured` | Capture Intel objectives completed |
| `intel_recovered` | Recovered dropped intel as defender |
| `tnt_planted` | Plant Explosive objectives completed |
| `tnt_defused` | Defused enemy TNT as defender |
| `supply_caches_destroyed` | Supply caches destroyed |
| `hold_ground_wins` | Hold Ground objectives won |
| `hold_ground_defends` | Defended against Hold Ground |
| `resource_depots_established` | Resource Depots established |

### Building & Logistics Stats (12)

| Stat | Description |
|------|-------------|
| `buildings_constructed` | Total buildings built |
| `buildings_destroyed` | Enemy buildings destroyed |
| `outposts_built` | Outposts built |
| `watchtowers_built` | Watchtowers built |
| `garrisons_built` | Garrisons built |
| `depots_placed` | Division depots placed |
| `depots_raided` | Enemy depots raided |
| `depot_loot_value` | Estimated value of depot loot |
| `roads_built` | Road segments placed |
| `roads_damaged` | Enemy roads damaged |
| `banners_placed` | Banners placed for influence |
| `containers_stocked` | Containers filled for objectives |

### Participation Stats (8)

| Stat | Description |
|------|-------------|
| `rounds_played` | Total rounds participated in |
| `rounds_won` | Rounds won |
| `rounds_mvp` | Times awarded round MVP |
| `time_played` | Total minutes played |
| `login_streak` | Current consecutive day login streak |
| `login_streak_best` | Best login streak achieved |
| `first_blood` | Times getting first kill of a round |
| `last_login` | Timestamp of last login |

---

## Commands

### Player Commands

```
/stats                          - View your own statistics
/stats <player>                 - View another player's statistics
/stats leaderboard <category>   - View top 10 leaderboard
/stats round [roundId]          - View stats for current or specified round
/stats team <red|blue>          - View team aggregate stats
```

**Leaderboard Categories**: `kills`, `deaths`, `assists`, `objectives`, `captures`, `ip`, `time`, `streak`, `mvps`, `wins`, `buildings`, `roads`

### Admin Commands

```
/admin stats purge <roundId>    - Purge all stats for a round (requires confirmation)
/admin stats list               - List all rounds with stats
```

---

## MVP Formula

The MVP is calculated using a weighted formula:

```
MVP Score = (kills × 10) + (objectives × 25) + (captures × 50) + (ip × 0.1)
```

This weights territory control and objective completion higher than pure kills.

---

## Login Streak Mechanics

- Login streak increments when a player logs in after being offline 12+ hours
- Login streak **resets to 1** if offline for more than **36 hours**
- Best login streak is tracked separately and never resets

---

## Configuration

Add to `config.yml`:

```yaml
# Statistics System
stats:
  flush-interval-seconds: 10
  streak-reset-hours: 36
  
  mvp-weights:
    kills: 10
    objectives: 25
    captures: 50
    ip-multiplier: 0.1

# Stats REST API
stats-api:
  enabled: true
  port: 8080
  key: "your-secret-key-here"
  rate-limit: 60  # requests per minute
```

---

## REST API Reference

All endpoints require the `X-API-Key` header with your configured API key.

### `GET /api/health`

Health check endpoint.

**Response:**
```json
{
  "status": "ok",
  "timestamp": "2026-03-10T15:30:00Z",
  "version": "1"
}
```

### `GET /api/player/{uuid}`

Get lifetime stats for a player.

**Response:**
```json
{
  "uuid": "6cd6a079-0846-420e-9d93-39756739eade",
  "username": "flintstqne",
  "lastSeen": "2026-03-10T15:30:00Z",
  "stats": {
    "combat": {
      "kills": 342,
      "deaths": 198,
      "assists": 87
    },
    "territory": {
      "regions_captured": 45,
      "ip_earned": 12500
    }
  },
  "computed": {
    "kdr": 1.73,
    "kda": 2.17,
    "mvp_score": 8925.5,
    "win_rate": 65.0
  }
}
```

### `GET /api/player/{uuid}/round/{roundId}`

Get round-specific stats for a player.

### `GET /api/leaderboard/{category}?limit=10`

Get leaderboard for a stat category.

**Response:**
```json
{
  "category": "kills",
  "displayName": "Kills",
  "period": "lifetime",
  "entries": [
    {"rank": 1, "uuid": "...", "username": "PlayerOne", "value": 523},
    {"rank": 2, "uuid": "...", "username": "PlayerTwo", "value": 498}
  ]
}
```

### `GET /api/team/{team}?round={roundId}`

Get team aggregate stats. Query param `round` is optional (defaults to current round).

### `GET /api/round/{roundId}`

Get round summary including both teams and MVP.

### `GET /api/rounds`

Get list of all round IDs that have stats.

### `GET /api/categories`

Get list of all stat categories with metadata.

---

## Rate Limiting

- Default: 60 requests per minute per API key
- Configurable via `stats-api.rate-limit`
- Returns HTTP 429 when exceeded

---

## Discord Bot

A scaffolded Discord.py bot is included in the `discord-bot/` directory. See `discord-bot/README.md` for setup instructions.

### Bot Commands

- `/stats <username>` - View player statistics
- `/leaderboard <category>` - View stat leaderboards
- `/round [id]` - View round statistics
- `/compare <player1> <player2>` - Compare two players side by side

---

## Implementation Notes

### Async Batched Writes

Stats are queued in memory and flushed to the database every 10 seconds. This prevents lag during intense combat when many stats are being recorded.

### Assist Tracking

When a player dies, the system checks who dealt damage to them within the last 10 seconds (excluding the killer). Those players receive assist credit.

### Revenge Kills

If you kill a player who killed you within the last 60 seconds, it counts as a revenge kill.

### First Blood

The first kill of each round is tracked. Only one player can get first blood per round.

