# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Entrenched (internal codename "BlockHole") is a Paper/Bukkit Minecraft PvP plugin: two teams (Red/Blue) fight over a
4x4 grid of capturable regions using an influence-points capture system, physical supply-line roads, objectives,
divisions/parties, and a merit/rank system. A companion Discord bot (`discord-bot/`, Python) reads player/round stats
from the plugin's built-in HTTP stats API.

## Build & run

```bash
mvn clean package                 # builds target/Trenched-1.jar (shaded); default Maven goal
```

- Java 21, Maven, packaged with `maven-shade-plugin` (sqlite-jdbc is shaded in; PlaceholderAPI is excluded/provided).
- No test suite exists in this repo — there is nothing to run via `mvn test`. `docs/TESTING_CHECKLIST.md` is a manual
  in-game QA checklist, not automated tests. Don't invent a test framework unless asked.
- Deploy by dropping the shaded jar into a Paper 1.21.x server's `plugins/` folder. Hard dependency: `InventoryFramework`
  plugin installed separately (shaded relocation only covers the package, not the plugin jar). Soft deps: PlaceholderAPI, BlueMap.
- Runtime config is `config.yml` (copied via Maven resource filtering from `src/main/resources/config.yml`); `plugin.yml`
  declares every command/permission and must stay in sync with commands registered in `Trenched.java`.

### Discord bot

```bash
cd discord-bot
pip install -r requirements.txt
cp config.example.yaml config.yaml   # then fill in bot token + stats API URL/key
python bot.py
```

The bot only *consumes* the plugin's stats API (`StatApiServer`, enabled via `stats-api.enabled` in `config.yml`); it has
no direct DB access. See `discord-bot/README.md` for the endpoint list and command set.

## Architecture

### Plugin bootstrap

`Trenched.java` (`onEnable`) is the composition root: every subsystem is constructed and wired here, in dependency
order, then commands/listeners are registered. There is no DI framework — when adding a subsystem, follow the existing
pattern (construct `XDb` → `XService` → listeners/commands, wire cross-system callbacks, register commands from
`plugin.yml`, register events). Read `onEnable` top-to-bottom before touching startup order; many systems have
implicit ordering dependencies (e.g. `regionService` must exist before `roadService`'s capture callback is wired,
`objectiveService` needs `divisionService`/`teamService` injected post-construction to avoid a circular dependency).

### Package-per-subsystem, `Db`/`Service` split

Code is organized as one package per feature area under `org.flintstqne.entrenched.*Logic` (or `*Hook`/`Utils`):
`RegionLogic`, `RoadLogic`, `RoundLogic`, `TeamLogic`, `DivisionLogic`, `PartyLogic`, `MeritLogic`, `ObjectiveLogic`,
`ChatLogic`, `LinkLogic`, `StatLogic`, `AdminLogic`, `BlueMapHook`.

Within each, the consistent pattern is:
- `XDb` — raw SQLite access (own connection/schema setup) for that subsystem.
- `XService` (interface) + `SqlXService` (implementation) — business logic on top of the `Db`. Depend on the
  `XService` interface elsewhere, not the `Sql` impl, except where cross-system wiring requires an explicit cast
  (this happens throughout `Trenched.java`, e.g. `((SqlObjectiveService) objectiveService).setDivisionService(...)`).
- `XCommand` — `CommandExecutor`/`TabCompleter`, thin, delegates to the service.
- `XListener` — Bukkit event handlers, delegates to the service.

Each subsystem gets its own SQLite database/file via its `Db` class — there is no shared central DAO layer.

### Cross-system wiring is callback-based

Systems avoid hard compile-time coupling by exchanging callbacks/listeners set after construction (e.g.
`sqlRegionService.setCaptureCallback(...)`, `roadListener.setDisruptionCallback(...)`,
`objectiveService.setBuildingDestroyedCallback(...)`). When adding a new interaction between two subsystems, prefer
adding a callback setter over introducing a constructor dependency, matching existing style.

### Round/world lifecycle

A "round" (`RoundLogic`) owns the active game world (often a timestamped world name like `world_<epoch>` created per
round) and the current phase. Because Bukkit won't auto-load a non-default world folder, `Trenched.ensureGameWorldLoaded()`
manually loads it from disk on startup and patches `server.properties` `level-name` so the next restart boots directly
into it; it also strips BlueMap's stale auto-generated map configs for the old default `world`. `NewRoundInitializer`
handles starting a fresh round (new world, reset regions/objectives/roads). Most services fetch the game world
dynamically via `roundService.getGameWorld()` rather than caching a `World` reference, since the world changes across
rounds.

### Config

`ConfigManager` wraps `config.yml` with typed getters — add new settings there rather than reading `getConfig()` ad hoc
elsewhere. Feature flags like `depot-system.enabled`, `player-placed-tracking.enabled`, `stats-api.enabled` gate whole
subsystems on/off in `onEnable`.

### Region/capture/supply relationship

Regions (`RegionLogic`) are a 4x4 grid, captured via influence points from kills/blocks/banners/workstations. Capturing
a region triggers a cascade (all wired via the capture callback in `Trenched.java`): notify players, expire objectives
in that region, clear player-placed-block tracking, record endgame "heat", flag division depots there as vulnerable,
and re-scan for roads. Supply (`RoadLogic`) is physical: teams must build path-block roads connecting regions back to
their home; `RegionService` and `RoadService` reference each other (wired post-construction) to compute
supply-adjusted capture rates.

### Objectives & building detection

`ObjectiveLogic` tracks settlement/raid objectives per region and structural "buildings" (outposts, watchtowers,
garrison quarters) detected via `BuildingDetector` against block patterns. `PlacedBlockTracker` (optional, config-gated)
records player-placed blocks per region so building detection can distinguish player-built structures from
world-generated terrain. See `docs/BUILDING_DETECTION_PLAN.md` and `docs/BUILDING_SYSTEM_DESIGN.md` for the design
rationale behind this system.

### Discord linking & stats API

`LinkLogic` provides one-time-code account linking between Discord and Minecraft (`/link`, `/unlink`), always enabled.
`StatLogic.StatApiServer` is an embedded HTTP server (own lightweight router, no framework) exposing read-only JSON
endpoints the Discord bot polls; it's independent of the linking system except for resolving Discord IDs to
usernames/UUIDs.

## Docs worth reading before large changes

- `docs/DEVELOPMENT_STATUS.md` — current feature status/roadmap.
- `docs/REGION_CAPTURE_DESIGN.md`, `docs/DIVISION_DEPOT_DESIGN.md`, `docs/DIVISIONS_AND_SQUADS.md`,
  `docs/MERIT_SYSTEM.md`, `docs/STATS_SYSTEM.md`, `docs/BUILDING_SYSTEM_DESIGN.md`/`BUILDING_DETECTION_PLAN.md` —
  per-system design docs.
- `docs/dev-logs/` — chronological dev log entries.
