# Dev Log 01 — Objectives System

**Date:** March 14, 2026

---

## What Are Objectives?

Objectives are time-limited tasks that spawn inside regions on the map. They give players a structured goal beyond simply accumulating Influence Points (IP) through kills and block placement. Completing an objective awards a significant IP bonus to the completing team, directly accelerating region capture.

Each objective is tied to a single region and belongs to one of two categories based on that region's ownership state:

- **Raid Objectives** — appear in regions owned by the *enemy* team. They are offensive in nature, focused on disruption, destruction, and extraction.
- **Settlement Objectives** — appear in *neutral* regions. They are constructive, requiring players to build structures or establish a presence to claim the land.

---

## How Objectives Work

### Spawning

When a region becomes eligible (neutral or enemy-owned), the server evaluates which objective types are valid for that region and randomly selects one to spawn. Some objectives have preconditions — for example:

- **Assassinate Commander** only spawns if an enemy commander or officer is currently online in the region.
- **Destroy Supply Cache** only spawns if the enemy team has placed chests in the region.

A maximum of one objective is active per region at a time. The server periodically refreshes objectives, expiring stale ones and spawning replacements as needed.

### Progress

Most objectives track incremental progress from `0.0` to `1.0` (0 – 100%). Players contribute to progress through in-game actions such as:

- Placing or breaking blocks
- Standing in a zone for a duration
- Picking up and delivering an item
- Filling containers with items

The current progress is shown in the objective description (e.g. `"Build defensive walls (12/100 blocks)"`), in the boss bar, and via the scoreboard compass pointing toward the objective location.

### Completion

When progress reaches `1.0`, a player triggers completion by being present and meeting any final requirements. On completion:

1. The objective is marked **COMPLETED** and records the completing team and timestamp.
2. The completing team receives an Influence Point reward (varies by objective, 100 – 200 IP).
3. A cooldown prevents the same player from immediately repeating the same objective type in the same region.
4. Any callbacks (UI updates, notifications to the other team) fire automatically.

### Expiry

Objectives that are not completed within their time window are marked **EXPIRED** and removed. If a region is captured mid-objective, all active objectives in that region are expired immediately.

---

## Objective Types

### 🗡️ Raid Objectives (Enemy Regions)

These objectives spawn in regions owned by the opposing team.

| Objective | IP Reward | How to Complete |
|-----------|-----------|-----------------|
| **Destroy Supply Cache** | 150 IP | Find the hidden enemy supply chest in the region and break it. Only spawns when the enemy has placed chests in the region. |
| **Assassinate Commander** | 200 IP | Kill the designated enemy commander — the enemy player with the most kills in the region. Only spawns when a valid target is online. |
| **Sabotage Defenses** | 100 IP | Destroy 50 blocks of enemy walls or fortifications. Progress updates as blocks are broken. |
| **Plant Explosive** | 175 IP | Place TNT at the marked target location, then defend it for 30 seconds without it being defused by the enemy. |
| **Capture Intel** | 125 IP | Pick up the intel item at the marked location and return it to friendly territory. Intel drops on death and can be recovered by defenders; it respawns at the original location if left on the ground for 60 seconds. |
| **Hold Ground** | 100 IP | Stand in the center of the region for a cumulative 60 seconds while it remains contested. Defenders can slow progress by entering the zone. |

---

### 🏗️ Settlement Objectives (Neutral Regions)

These objectives spawn in neutral regions that neither team currently owns.

| Objective | IP Reward | How to Complete |
|-----------|-----------|-----------------|
| **Establish Outpost** | 200 IP | Build a functional field outpost around the objective anchor — requires shelter, storage, and crafting stations. Evaluated by building quality score. |
| **Secure Perimeter** | 150 IP | Place 100 blocks of walls or defensive fortifications in the region. Progress updates as blocks are placed. |
| **Build Supply Route** | 125 IP | Lay 64 path or road blocks that connect to a friendly-owned adjacent territory border. |
| **Build Watchtower** | 100 IP | Construct a watchtower with sufficient height, an accessible ladder or stairway, and a lookout platform. Evaluated by building quality score. |
| **Establish Resource Depot** | 150 IP | Place 4 or more storage containers (chests, barrels, etc.) each containing at least 500 items. |
| **Build Garrison Quarters** | 175 IP | Build an enclosed room containing 3 or more team-colored beds. Evaluated by building quality score. |

---

## Summary

The objectives system layers structured gameplay on top of the raw IP economy, rewarding coordinated team effort. Raid objectives push teams to aggressively disrupt enemy-held regions, while Settlement objectives reward builders who establish a foothold in neutral territory. Together they create dynamic, objective-driven fights across the 4×4 region grid each round.
