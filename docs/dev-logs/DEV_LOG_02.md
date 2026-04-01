# Dev Log 02 — Outposts

**Date:** March 29, 2026

---

## What Are Outposts?

Outposts are the first building a team can construct when they push into neutral territory. They're the settlement equivalent of planting a flag — a scrappy forward base that says "we're here, and we're staying." When a neutral region spawns an **Establish Outpost** objective, the plugin picks a location that matches the region's dominant terrain and drops an objective marker there. From that point, it's up to the players to build something real around it.

The idea behind outposts was to create a building objective that *feels* organic. We didn't want players slapping down a 3×3 cobblestone box and calling it done. The building detector uses a score-based evaluation system that rewards actual effort — walls, a roof, interior space, a door, storage, and a workstation. Hit 70 out of 100 points and the structure gets registered as a valid outpost. Fall short and the boss bar tells you exactly what's missing in plain language: "needs more structural mass," "needs a chest," "needs better roof coverage." No cryptic error codes — just a checklist you can work through while you build.

---

## How Outposts Are Built

### The Scoring System

The building detector scans a 16-block radius around the objective anchor every second. It runs a flood-fill to find connected structural blocks (stone, wood, bricks — anything you'd actually build walls out of), then scores what it finds across five categories:

| Category | Max Points | What It's Looking For |
|----------|------------|----------------------|
| **Structure** | 30 | 24+ structural blocks, 14+ footprint size, roof coverage |
| **Interior** | 25 | 3+ interior cells (enclosed spaces with floor, roof, and walls) |
| **Access** | 20 | A real entrance — doors, trapdoors, or gap-based entry |
| **Signature** | 20 | At least one chest (9 pts), one crafting table (9 pts), utility blocks |
| **Context** | 5 | Variant bonus (see below) |

You need **70 points** to pass. That's deliberately achievable without being trivial — a genuine small shelter with a door, a chest, a crafting table, and a roof will get you there. A hollow dirt pillar will not.

### What Players See

The boss bar updates in real time as players build. Early on it reads something like `Need: Build bigger walls/floor & Add a roof`. As the structure comes together, it shifts to variant-specific hints: `✓ Structure done! For Mining Outpost: add Furnace, Pickaxe in chest`. When everything checks out, the outpost registers and the team gets notified with coordinates.

The `/obj` command also shows a detailed checklist:
```
✓ Walls & Structure (24+ blocks)
✓ Floor Size (14+ blocks)
✓ Enclosed Interior Space
✓ Roof Coverage
✓ Storage Chest
✓ Crafting Table
✗ Door/Entrance

⬆ Mining Outpost Upgrade:
  ✗ Furnace
  ✗ Pickaxe in chest
```

---

## Outpost Variants

This is where things get interesting. Outposts aren't one-size-fits-all — the plugin analyzes the terrain in the region before the objective even spawns and picks a location that matches the dominant environment. Build near water? You're getting a Fishing Outpost opportunity. Spawned underground near ore veins? Mining Outpost. High on a mountain ridge? Mountain Outpost.

Variant detection runs in two phases:

1. **Environment Check** — The terrain around the objective anchor is sampled. Water blocks, crops, sand, ore, logs, leaves, elevation, and biome data all feed into this.
2. **Requirement Check** — The building detector scans the structure's bounds for specific blocks and chest contents that match the variant.

If the environment matches but the player hasn't placed the required items, the outpost still registers — just as a partial variant with a lower context score (3.0 instead of 5.0) and no buff. The boss bar nudges them: "Fishing Outpost: needs Fishing Rod in chest."

### The Six Variants

| Variant | Where It Spawns | What You Need Inside | Buff on Departure |
|---------|----------------|---------------------|-------------------|
| **Mining Outpost** | Near ore veins or underground (Y ≤ sea level − 10) | Furnace block + Pickaxe in chest | Luck (5 min) |
| **Fishing Outpost** | Near water (35+ water blocks in scan radius) | Fishing Rod in chest | Luck (5 min) |
| **Farm Outpost** | Plains / flat terrain (18+ crop blocks nearby) | Hoe in chest | Saturation (5 min) |
| **Forest Outpost** | Forest, taiga, or jungle biome (40+ logs/leaves) | 10+ Logs in chest + Axe in chest | Haste I (5 min) |
| **Mountain Outpost** | High elevation (Y > sea level + 22) | Ladder or vine block + 3+ Wool blocks | Slow Falling (5 min) |
| **Desert Outpost** | Desert or badlands biome (30+ sand blocks) | 3+ Cactus blocks + Water Bucket in chest | Fire Resistance (5 min) |

If none of the environment checks match, the outpost defaults to **Standard** — still valid, still earns IP, just no variant buff.

### Buffs

Variant buffs activate when a player **leaves** the outpost area, not while they're inside. The idea is that the outpost prepares you for the environment — you stop at your Mining Outpost, gear up, and head into the caves with a Luck buff. You visit the Mountain Outpost and leave with Slow Falling before traversing the peaks.

Buffs last 5 minutes and are applied as standard potion effects. A beacon-activate sound plays and the player gets a green chat message confirming the buff. The system tracks expiry so buffs aren't re-applied if you're already under one.

---

## Terrain-Aware Spawning

Before the outpost objective even appears, the objective spawner runs a terrain analysis across the entire region. It samples 25 points in a 5×5 grid and classifies each as WATER, FARM, DESERT, MOUNTAIN, UNDERGROUND, FOREST, or GENERIC. The most common terrain type wins, and the objective marker is placed at a location that matches:

- **Water regions** → Shoreline spot (solid ground within 10 blocks of water)
- **Plains/meadow** → Flat grassland
- **Desert/badlands** → Sandy terrain
- **High elevation** → Mountain peak
- **Ore-rich/deep** → Near exposed ore
- **Forest/taiga** → Among trees

This means the outpost variant isn't random — it's a direct result of the world generation. Each round produces a new seed, a new map, and a completely different distribution of outpost variants across the 16 regions.

---

## Strategic Value

Outposts are cheap and fast compared to Watchtowers and Garrisons. They're the first thing you build when entering a neutral region, and they serve as:

- **Territory markers** — First foothold in a region, signaling intent to settle
- **Resource stations** — Central chest and crafting table for the team
- **Buff dispensers** — Variant outposts give a meaningful 5-minute edge for the surrounding terrain

Each region supports up to **2 outposts** per team, and each player can only register 1 outpost per region. Building a variant outpost (anything other than Standard) awards a **+25 IP bonus** on top of the base 75 IP and 2 merit tokens.

Outposts are vulnerable, though. If the region is contested or captured, enemies can break in. Destroying the chest or crafting table deregisters the outpost entirely, and the team has to rebuild from scratch. That's the trade-off: outposts are quick to set up but fragile to defend. Strategic placement matters — build deep in your territory for safety, or push forward for terrain buffs at the cost of vulnerability.

---

## Summary

Outposts were designed to make the early settlement phase feel like an actual expedition. You're not just clicking a menu — you're building a shelter in the wilderness, stocking it with the right tools, and earning a buff that matches the land around you. The scoring system keeps it honest without being punishing, the variant system adds depth without adding complexity, and the terrain-aware spawning makes every region feel like it has a purpose.

Next up: we'll talk about how Watchtowers turn information into a weapon.

