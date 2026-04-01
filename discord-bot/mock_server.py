#!/usr/bin/env python3
"""
Mock stats API server for the Entrenched Discord bot.

Generates fresh random data every time it starts, exposing the same REST
endpoints as the real Minecraft plugin so the bot can be tested without
actual players.

Usage:
    python mock_server.py              # defaults to port 8080
    python mock_server.py --port 9090  # custom port
    python mock_server.py --seed 42    # reproducible data
"""

import argparse
import json
import random
import uuid
from datetime import datetime, timedelta, timezone
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

# ── Configurable constants ──────────────────────────────────────────────

NUM_PLAYERS = 30
NUM_ROUNDS = 5
NUM_REGIONS = 25
LEADERBOARD_DEFAULT_LIMIT = 10

# Merit rank table (tag, displayName, meritsRequired)
MERIT_RANKS = [
    ("RCT", "Recruit", 0),
    ("PVT", "Private", 10),
    ("PFC", "Private First Class", 25),
    ("SPC", "Specialist", 45),
    ("CPL", "Corporal", 70),
    ("SGT", "Sergeant", 100),
    ("SSG", "Staff Sergeant", 150),
    ("SFC", "Sergeant First Class", 210),
    ("MSG", "Master Sergeant", 280),
    ("1SG", "First Sergeant", 350),
    ("SGM", "Sergeant Major", 430),
    ("CSM", "Command Sergeant Major", 520),
    ("WO1", "Warrant Officer 1", 625),
    ("CW2", "Chief Warrant Officer 2", 750),
    ("CW3", "Chief Warrant Officer 3", 900),
    ("CW4", "Chief Warrant Officer 4", 1075),
    ("CW5", "Chief Warrant Officer 5", 1275),
    ("CDT", "Cadet", 1500),
    ("2LT", "Second Lieutenant", 1750),
    ("1LT", "First Lieutenant", 2050),
    ("CPT", "Captain", 2400),
    ("MAJ", "Major", 2850),
    ("LTC", "Lieutenant Colonel", 3400),
    ("COL", "Colonel", 4100),
    ("BG", "Brigadier General", 5000),
    ("MG", "Major General", 6500),
    ("LTG", "Lieutenant General", 8500),
    ("GEN", "General", 11000),
    ("GOA", "General of the Army", 15000),
]

DIVISION_NAMES = [
    "Night Wolves", "Iron Fist", "Steel Rain", "Shadow Company",
    "Ghost Recon", "Vanguard", "Crimson Tide", "Blue Storm",
    "Frost Legion", "Thunder Hawks",
]

DIVISION_TAGS = [
    "NW", "IF", "SR", "SC", "GR", "VG", "CT", "BS", "FL", "TH",
]

REGION_NAMES = [f"{chr(65 + r)}{c}" for r in range(5) for c in range(1, 6)]

MINECRAFT_NAME_PARTS = [
    "Wolf", "Shadow", "Blaze", "Ender", "Creep", "Iron", "Storm",
    "Frost", "Redstone", "Obsidian", "Diamond", "Nether", "Wither",
    "Phantom", "Torch", "Anvil", "Beacon", "Golem", "Shulk", "Piston",
    "Axe", "Sword", "Bow", "Shield", "Helm", "Craft", "Mine", "Block",
    "Pixel", "Vex", "Lava", "Cobble", "Flint", "Steel", "Breeze",
]

STAT_CATEGORIES_META = {
    "kills":                  {"display": "Kills",                 "group": "combat"},
    "deaths":                 {"display": "Deaths",                "group": "combat"},
    "assists":                {"display": "Assists",               "group": "combat"},
    "kill_streak_best":       {"display": "Best Kill Streak",      "group": "combat"},
    "kill_streak_current":    {"display": "Current Kill Streak",   "group": "combat"},
    "commander_kills":        {"display": "Commander Kills",       "group": "combat"},
    "revenge_kills":          {"display": "Revenge Kills",         "group": "combat"},
    "damage_dealt":           {"display": "Damage Dealt",          "group": "combat"},
    "damage_taken":           {"display": "Damage Taken",          "group": "combat"},
    "bullets_shot":            {"display": "Bullets Shot",           "group": "combat"},
    "potions_used":           {"display": "Potions Used",          "group": "combat"},
    "healing_potions_used":   {"display": "Healing Potions Used",  "group": "combat"},
    "regions_captured":       {"display": "Regions Captured",      "group": "territory"},
    "regions_contested":      {"display": "Regions Contested",     "group": "territory"},
    "regions_defended":       {"display": "Regions Defended",      "group": "territory"},
    "ip_earned":              {"display": "IP Earned",             "group": "territory"},
    "ip_denied":              {"display": "IP Denied",             "group": "territory"},
    "time_in_enemy_territory":{"display": "Time in Enemy Territory","group": "territory"},
    "objectives_completed":   {"display": "Objectives Completed",  "group": "objective"},
    "objectives_settlement":  {"display": "Settlement Objectives", "group": "objective"},
    "objectives_raid":        {"display": "Raid Objectives",       "group": "objective"},
    "intel_captured":         {"display": "Intel Captured",        "group": "objective"},
    "intel_recovered":        {"display": "Intel Recovered",       "group": "objective"},
    "tnt_planted":            {"display": "TNT Planted",           "group": "objective"},
    "tnt_defused":            {"display": "TNT Defused",           "group": "objective"},
    "supply_caches_destroyed":{"display": "Supply Caches Destroyed","group": "objective"},
    "hold_ground_wins":       {"display": "Hold Ground Wins",      "group": "objective"},
    "hold_ground_defends":    {"display": "Hold Ground Defends",   "group": "objective"},
    "resource_depots_established":{"display": "Depots Established","group": "objective"},
    "buildings_constructed":  {"display": "Buildings Constructed",  "group": "building"},
    "buildings_destroyed":    {"display": "Buildings Destroyed",    "group": "building"},
    "outposts_built":         {"display": "Outposts Built",        "group": "building"},
    "watchtowers_built":      {"display": "Watchtowers Built",     "group": "building"},
    "garrisons_built":        {"display": "Garrisons Built",       "group": "building"},
    "depots_placed":          {"display": "Depots Placed",         "group": "building"},
    "depots_raided":          {"display": "Depots Raided",         "group": "building"},
    "depot_loot_value":       {"display": "Depot Loot Value",      "group": "building"},
    "roads_built":            {"display": "Roads Built",           "group": "building"},
    "roads_damaged":          {"display": "Roads Damaged",         "group": "building"},
    "banners_placed":         {"display": "Banners Placed",        "group": "building"},
    "containers_stocked":     {"display": "Containers Stocked",    "group": "building"},
    "blocks_broken":          {"display": "Blocks Broken",         "group": "building"},
    "blocks_placed":          {"display": "Blocks Placed",         "group": "building"},
    "rounds_played":          {"display": "Rounds Played",         "group": "participation"},
    "rounds_won":             {"display": "Rounds Won",            "group": "participation"},
    "rounds_mvp":             {"display": "Round MVPs",            "group": "participation"},
    "time_played":            {"display": "Time Played",           "group": "participation"},
    "login_streak":           {"display": "Login Streak",          "group": "participation"},
    "login_streak_best":      {"display": "Best Login Streak",     "group": "participation"},
    "first_blood":            {"display": "First Bloods",          "group": "participation"},
}

# ── Data generation helpers ─────────────────────────────────────────────

def _random_username(rng: random.Random) -> str:
    """Generate a Minecraft-style username (3-16 chars, alphanumeric + underscore)."""
    part1 = rng.choice(MINECRAFT_NAME_PARTS)
    part2 = rng.choice(MINECRAFT_NAME_PARTS)
    suffix = str(rng.randint(0, 999)) if rng.random() < 0.5 else ""
    sep = rng.choice(["", "_"]) if suffix or part1 != part2 else "_"
    name = f"{part1}{sep}{part2}{suffix}"
    return name[:16]


def _random_player_stats(rng: random.Random) -> dict:
    """Generate a plausible stat block for one player."""
    kills = rng.randint(20, 600)
    deaths = rng.randint(30, 500)
    assists = rng.randint(5, int(kills * 0.4))
    return {
        "combat": {
            "kills": kills,
            "deaths": deaths,
            "assists": assists,
            "kill_streak_best": rng.randint(3, 20),
            "kill_streak_current": rng.randint(0, 8),
            "commander_kills": rng.randint(0, 15),
            "revenge_kills": rng.randint(0, int(kills * 0.15)),
            "damage_dealt": round(rng.uniform(5000, 120000), 1),
            "damage_taken": round(rng.uniform(4000, 100000), 1),
            "bullets_shot": rng.randint(50, 2000),
            "potions_used": rng.randint(5, 200),
            "healing_potions_used": rng.randint(2, 80),
        },
        "territory": {
            "regions_captured": rng.randint(0, 60),
            "regions_contested": rng.randint(0, 40),
            "regions_defended": rng.randint(0, 35),
            "ip_earned": rng.randint(500, 20000),
            "ip_denied": rng.randint(0, 5000),
            "time_in_enemy_territory": rng.randint(5, 300),
        },
        "objective": {
            "objectives_completed": rng.randint(5, 80),
            "objectives_settlement": rng.randint(0, 25),
            "objectives_raid": rng.randint(0, 25),
            "intel_captured": rng.randint(0, 10),
            "intel_recovered": rng.randint(0, 8),
            "tnt_planted": rng.randint(0, 12),
            "tnt_defused": rng.randint(0, 10),
            "supply_caches_destroyed": rng.randint(0, 15),
            "hold_ground_wins": rng.randint(0, 10),
            "hold_ground_defends": rng.randint(0, 10),
            "resource_depots_established": rng.randint(0, 5),
        },
        "building": {
            "buildings_constructed": rng.randint(0, 50),
            "buildings_destroyed": rng.randint(0, 30),
            "outposts_built": rng.randint(0, 10),
            "watchtowers_built": rng.randint(0, 8),
            "garrisons_built": rng.randint(0, 6),
            "depots_placed": rng.randint(0, 8),
            "depots_raided": rng.randint(0, 5),
            "depot_loot_value": rng.randint(0, 25000),
            "roads_built": rng.randint(0, 40),
            "roads_damaged": rng.randint(0, 20),
            "banners_placed": rng.randint(0, 25),
            "containers_stocked": rng.randint(0, 20),
            "blocks_broken": rng.randint(100, 5000),
            "blocks_placed": rng.randint(100, 4000),
        },
        "participation": {
            "rounds_played": rng.randint(1, NUM_ROUNDS),
            "rounds_won": 0,  # filled in later
            "rounds_mvp": rng.randint(0, 3),
            "time_played": rng.randint(60, 6000),
            "login_streak": rng.randint(1, 15),
            "login_streak_best": rng.randint(1, 30),
            "first_blood": rng.randint(0, 4),
        },
    }


def _computed(stats: dict) -> dict:
    combat = stats["combat"]
    territory = stats["territory"]
    objective = stats["objective"]
    participation = stats["participation"]
    kills = combat["kills"]
    deaths = combat["deaths"]
    assists = combat["assists"]
    kdr = round(kills / max(deaths, 1), 2)
    kda = round((kills + assists) / max(deaths, 1), 2)
    mvp_score = round(
        kills * 10
        + objective["objectives_completed"] * 25
        + territory["regions_captured"] * 50
        + territory["ip_earned"] * 0.1,
        1,
    )
    rounds_played = max(participation["rounds_played"], 1)
    rounds_won = participation["rounds_won"]
    win_rate = round(rounds_won / rounds_played * 100, 1)
    return {"kdr": kdr, "kda": kda, "mvp_score": mvp_score, "win_rate": win_rate}


# ── World builder ───────────────────────────────────────────────────────

def _rank_for_merits(merits: int):
    """Return the (tag, displayName, required) tuple for given merit count."""
    result = MERIT_RANKS[0]
    for rank in MERIT_RANKS:
        if merits >= rank[2]:
            result = rank
        else:
            break
    return result


def _next_rank(current_rank):
    """Return the next rank tuple or None if at max."""
    for i, rank in enumerate(MERIT_RANKS):
        if rank[0] == current_rank[0]:
            if i + 1 < len(MERIT_RANKS):
                return MERIT_RANKS[i + 1]
            return None
    return None


class MockWorld:
    """Holds all generated data for one server run."""

    def __init__(self, seed=None):
        self.rng = random.Random(seed)
        self.players = []        # [{uuid, username, lastSeen, stats, computed}]
        self.rounds = []         # [{roundId, winner, durationMinutes, red, blue, mvp, ...}]
        self.merits = {}         # uuid -> merit data dict
        self.divisions = []      # [{id, name, tag, team, description, founder, members}]
        self.regions = []        # [{id, owner, state, red_influence, blue_influence, ...}]
        self._by_uuid = {}
        self._by_name = {}
        self._generate()

    def _generate(self):
        now = datetime.now(timezone.utc)
        used_names: set[str] = set()

        # Players
        for _ in range(NUM_PLAYERS):
            name = _random_username(self.rng)
            while name.lower() in used_names:
                name = _random_username(self.rng)
            used_names.add(name.lower())

            uid = str(uuid.UUID(int=self.rng.getrandbits(128), version=4))
            stats = _random_player_stats(self.rng)
            last_seen = (now - timedelta(minutes=self.rng.randint(0, 10080))).isoformat()
            player = {
                "uuid": uid,
                "username": name,
                "lastSeen": last_seen,
                "stats": stats,
            }
            self.players.append(player)

        # Assign teams per round so we can aggregate
        player_teams: dict[int, dict] = {}  # roundId -> {uuid: "red"|"blue"}
        for rid in range(1, NUM_ROUNDS + 1):
            assignments = {}
            for p in self.players:
                if self.rng.random() < 0.7:  # 70 % chance they participated
                    assignments[p["uuid"]] = self.rng.choice(["red", "blue"])
            player_teams[rid] = assignments

        # Rounds
        for rid in range(1, NUM_ROUNDS + 1):
            winner = self.rng.choice(["RED", "BLUE", "DRAW"])
            duration = self.rng.randint(30, 360)
            assignments = player_teams[rid]

            red_uuids = [u for u, t in assignments.items() if t == "red"]
            blue_uuids = [u for u, t in assignments.items() if t == "blue"]

            red_kills = sum(self._by_uuid_raw(u)["stats"]["combat"]["kills"] // NUM_ROUNDS for u in red_uuids)
            blue_kills = sum(self._by_uuid_raw(u)["stats"]["combat"]["kills"] // NUM_ROUNDS for u in blue_uuids)
            red_obj = sum(self._by_uuid_raw(u)["stats"]["objective"]["objectives_completed"] // NUM_ROUNDS for u in red_uuids)
            blue_obj = sum(self._by_uuid_raw(u)["stats"]["objective"]["objectives_completed"] // NUM_ROUNDS for u in blue_uuids)
            red_cap = sum(self._by_uuid_raw(u)["stats"]["territory"]["regions_captured"] // NUM_ROUNDS for u in red_uuids)
            blue_cap = sum(self._by_uuid_raw(u)["stats"]["territory"]["regions_captured"] // NUM_ROUNDS for u in blue_uuids)

            # Update rounds_won for players on winning team
            if winner in ("RED", "BLUE"):
                win_uuids = red_uuids if winner == "RED" else blue_uuids
                for uid in win_uuids:
                    p = self._by_uuid_raw(uid)
                    p["stats"]["participation"]["rounds_won"] = min(
                        p["stats"]["participation"]["rounds_won"] + 1,
                        p["stats"]["participation"]["rounds_played"],
                    )

            # Pick MVP from the winning side (or random)
            mvp_pool = red_uuids if winner == "RED" else blue_uuids if winner == "BLUE" else list(assignments.keys())
            mvp_uuid = self.rng.choice(mvp_pool) if mvp_pool else self.players[0]["uuid"]
            mvp_player = self._by_uuid_raw(mvp_uuid)

            self.rounds.append({
                "roundId": rid,
                "winner": winner,
                "durationMinutes": duration,
                "red": {
                    "players": len(red_uuids),
                    "kills": red_kills,
                    "objectives": red_obj,
                    "captures": red_cap,
                },
                "blue": {
                    "players": len(blue_uuids),
                    "kills": blue_kills,
                    "objectives": blue_obj,
                    "captures": blue_cap,
                },
                "mvp": {
                    "uuid": mvp_uuid,
                    "username": mvp_player["username"],
                    "score": round(
                        mvp_player["stats"]["combat"]["kills"] * 10
                        + mvp_player["stats"]["objective"]["objectives_completed"] * 25
                        + mvp_player["stats"]["territory"]["regions_captured"] * 50
                        + mvp_player["stats"]["territory"]["ip_earned"] * 0.1,
                        1,
                    ),
                },
            })

        # Pre-compute derived stats & build indexes
        for p in self.players:
            p["computed"] = _computed(p["stats"])
            self._by_uuid[p["uuid"]] = p
            self._by_name[p["username"].lower()] = p

        # Generate merit data for each player
        for p in self.players:
            received = self.rng.randint(0, 800)
            rank = _rank_for_merits(received)
            next_rank = _next_rank(rank)
            merit = {
                "uuid": p["uuid"],
                "username": p["username"],
                "rank": rank[1],
                "rank_tag": rank[0],
                "received_merits": received,
                "token_balance": self.rng.randint(0, 100),
                "lifetime_tokens_earned": self.rng.randint(50, 500),
                "lifetime_merits_given": self.rng.randint(0, 50),
                "lifetime_merits_received": received,
                "login_streak": self.rng.randint(0, 30),
                "rounds_completed": self.rng.randint(0, NUM_ROUNDS),
                "playtime_minutes": self.rng.randint(60, 6000),
                "achievements_unlocked": self.rng.randint(2, 25),
                "achievements_total": 40,
                "achievements": [],
            }
            if next_rank:
                merit["next_rank"] = {
                    "name": next_rank[1],
                    "tag": next_rank[0],
                    "required": next_rank[2],
                    "remaining": max(0, next_rank[2] - received),
                }
            # Attach merit summary to player response
            p["merit"] = {
                "rank": rank[1],
                "rank_tag": rank[0],
                "received_merits": received,
                "token_balance": merit["token_balance"],
            }
            if next_rank:
                p["merit"]["next_rank"] = next_rank[1]
                p["merit"]["next_rank_tag"] = next_rank[0]
                p["merit"]["merits_to_next"] = max(0, next_rank[2] - received)
                p["merit"]["next_rank_required"] = next_rank[2]
            self.merits[p["uuid"]] = merit

        # Generate divisions
        num_divs = min(len(DIVISION_NAMES), 8)
        for i in range(num_divs):
            team = "red" if i % 2 == 0 else "blue"
            team_players = [p for j, p in enumerate(self.players) if ("red" if j % 2 == 0 else "blue") == team]
            founder = self.rng.choice(team_players)
            member_pool = [p for p in team_players if p != founder]
            members_chosen = self.rng.sample(member_pool, min(self.rng.randint(2, 6), len(member_pool)))
            member_list = [{
                "uuid": founder["uuid"],
                "username": founder["username"],
                "role": "COMMANDER",
                "role_symbol": "★",
                "merit_rank": self.merits[founder["uuid"]]["rank"],
                "merit_tag": self.merits[founder["uuid"]]["rank_tag"],
            }]
            for j, m in enumerate(members_chosen):
                role = "OFFICER" if j == 0 else "MEMBER"
                member_list.append({
                    "uuid": m["uuid"],
                    "username": m["username"],
                    "role": role,
                    "role_symbol": "•" if role == "OFFICER" else "",
                    "merit_rank": self.merits[m["uuid"]]["rank"],
                    "merit_tag": self.merits[m["uuid"]]["rank_tag"],
                })
            self.divisions.append({
                "id": i + 1,
                "name": DIVISION_NAMES[i],
                "tag": DIVISION_TAGS[i],
                "team": team,
                "description": f"The mighty {DIVISION_NAMES[i]}!",
                "founder": founder["username"],
                "founder_uuid": founder["uuid"],
                "created_at": datetime.now(timezone.utc).isoformat(),
                "member_count": len(member_list),
                "members": member_list,
            })

        # Generate regions
        for rname in REGION_NAMES[:NUM_REGIONS]:
            owner = self.rng.choice([None, "red", "blue"])
            states = ["NEUTRAL", "OWNED", "CONTESTED", "FORTIFIED"]
            if owner is None:
                state = "NEUTRAL"
            else:
                state = self.rng.choice(["OWNED", "CONTESTED", "FORTIFIED"])
            self.regions.append({
                "id": rname,
                "owner": owner,
                "state": state,
                "red_influence": round(self.rng.uniform(0, 1000), 1) if owner != "blue" else 0,
                "blue_influence": round(self.rng.uniform(0, 1000), 1) if owner != "red" else 0,
                "times_captured": self.rng.randint(0, 10),
                "fortified": state == "FORTIFIED",
            })

    # helpers used during generation before indexes are ready
    def _by_uuid_raw(self, uid):
        for p in self.players:
            if p["uuid"] == uid:
                return p
        return self.players[0]

    # ── public lookups ──

    def player(self, identifier: str):
        """Look up by UUID or username (case-insensitive)."""
        return self._by_uuid.get(identifier) or self._by_name.get(identifier.lower())

    def round(self, rid: int):
        for r in self.rounds:
            if r["roundId"] == rid:
                return r
        return None

    def leaderboard(self, category: str, limit: int):
        meta = STAT_CATEGORIES_META.get(category)
        if meta is None:
            return None
        group = meta["group"]

        entries = []
        for p in self.players:
            val = p["stats"].get(group, {}).get(category, 0)
            merit = self.merits.get(p["uuid"], {})
            entries.append({
                "uuid": p["uuid"],
                "username": p["username"],
                "value": val,
                "merit_rank": merit.get("rank", "Recruit"),
                "merit_tag": merit.get("rank_tag", "RCT"),
            })

        entries.sort(key=lambda e: e["value"], reverse=True)
        entries = entries[:limit]
        for i, e in enumerate(entries, start=1):
            e["rank"] = i

        return {
            "category": category,
            "displayName": meta["display"],
            "period": "lifetime",
            "entries": entries,
        }

    def team_stats(self, team: str, round_id=None):
        team_lower = team.lower()
        # Just aggregate from all players (mock approximation)
        total_kills = 0
        total_deaths = 0
        total_obj = 0
        total_cap = 0
        count = 0
        for i, p in enumerate(self.players):
            # Deterministically assign ~half to each team
            assigned = "red" if i % 2 == 0 else "blue"
            if assigned != team_lower:
                continue
            count += 1
            total_kills += p["stats"]["combat"]["kills"]
            total_deaths += p["stats"]["combat"]["deaths"]
            total_obj += p["stats"]["objective"]["objectives_completed"]
            total_cap += p["stats"]["territory"]["regions_captured"]

        return {
            "team": team_lower,
            "players": count,
            "kills": total_kills,
            "deaths": total_deaths,
            "objectives": total_obj,
            "captures": total_cap,
        }


# ── HTTP handler ────────────────────────────────────────────────────────

class MockHandler(BaseHTTPRequestHandler):
    world: MockWorld  # set on the class before serving

    def _json(self, obj, status=200):
        body = json.dumps(obj, indent=2).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _not_found(self, msg="Not found"):
        self._json({"message": msg}, 404)

    # suppress default logging for cleaner output; we print our own
    def log_message(self, fmt, *args):
        print(f"  {self.command} {self.path} -> {args[1] if len(args) > 1 else ''}")

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        qs = parse_qs(parsed.query)

        # /api/health (also handles link actions via ?action= query param)
        if path == "/api/health":
            action = qs.get("action", [None])[0]

            if action == "verify":
                code = qs.get("code", [""])[0]
                discord_id = qs.get("discord_id", [""])[0]
                if not code or not discord_id:
                    return self._json({"result": "INVALID_CODE", "message": "Missing code or discord_id"}, 400)
                # Mock: accept any code and "link" to a random player
                p = random.choice(self.world.players)
                return self._json({
                    "result": "OK",
                    "mc_uuid": p["uuid"],
                    "mc_username": p["username"],
                })

            if action == "lookup":
                discord_id = qs.get("discord_id", [""])[0]
                if not discord_id:
                    return self._json({"error": True, "message": "Missing discord_id"}, 400)
                # Mock: return a random player as the linked account
                p = random.choice(self.world.players)
                team = p.get("team", "red")
                merit = self.world.merits.get(p["uuid"], {})
                return self._json({
                    "discord_id": discord_id,
                    "mc_uuid": p["uuid"],
                    "mc_username": p["username"],
                    "team": team,
                    "rank": merit.get("rank", "Recruit"),
                    "rank_tag": merit.get("rank_tag", "RCT"),
                })

            if action == "unlink":
                return self._json({"unlinked": True})

            # Default: health check
            online = sum(1 for _ in self.world.players if hash(_.get('uuid', '')) % 3 != 0)
            return self._json({
                "status": "ok",
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "version": "mock-1",
                "onlinePlayers": online,
                "maxPlayers": 50,
            })

        # /api/categories
        if path == "/api/categories":
            cats = [
                {"key": k, "displayName": v["display"], "group": v["group"]}
                for k, v in STAT_CATEGORIES_META.items()
            ]
            return self._json({"categories": cats})

        # /api/rounds
        if path == "/api/rounds":
            ids = sorted((r["roundId"] for r in self.world.rounds), reverse=True)
            return self._json({"roundIds": ids})

        # /api/round/{id}
        if path.startswith("/api/round/"):
            try:
                rid = int(path.split("/")[-1])
            except ValueError:
                return self._not_found("Invalid round id")
            r = self.world.round(rid)
            if r is None:
                return self._not_found("Round not found")
            return self._json(r)

        # /api/leaderboard/{category}
        if path.startswith("/api/leaderboard/"):
            cat = path.split("/")[-1]
            limit = int(qs.get("limit", [LEADERBOARD_DEFAULT_LIMIT])[0])
            lb = self.world.leaderboard(cat, limit)
            if lb is None:
                return self._not_found("Unknown category")
            return self._json(lb)

        # /api/team/{team}
        if path.startswith("/api/team/"):
            team = path.split("/")[-1]
            round_id = qs.get("round", [None])[0]
            if round_id:
                round_id = int(round_id)
            return self._json(self.world.team_stats(team, round_id))

        # /api/merits/{uuid_or_name}
        if path.startswith("/api/merits/"):
            identifier = path.split("/")[-1]
            p = self.world.player(identifier)
            if p is None:
                return self._not_found("Player merit data not found")
            merit = self.world.merits.get(p["uuid"])
            if merit is None:
                return self._not_found("Player merit data not found")
            return self._json(merit)

        # /api/divisions
        if path == "/api/divisions":
            team_filter = qs.get("team", [None])[0]
            divs = self.world.divisions
            if team_filter:
                divs = [d for d in divs if d["team"] == team_filter.lower()]
            # Return summary (without full members list)
            summary = []
            for d in divs:
                summary.append({
                    "id": d["id"],
                    "name": d["name"],
                    "tag": d["tag"],
                    "team": d["team"],
                    "description": d["description"],
                    "member_count": d["member_count"],
                })
            return self._json({"count": len(summary), "divisions": summary})

        # /api/division/{nameOrTag}
        if path.startswith("/api/division/"):
            name_or_tag = path.split("/")[-1]
            found = None
            for d in self.world.divisions:
                if (d["name"].lower() == name_or_tag.lower() or
                        d["tag"].lower() == name_or_tag.lower() or
                        str(d["id"]) == name_or_tag):
                    found = d
                    break
            if found is None:
                return self._not_found("Division not found")
            return self._json(found)

        # /api/regions
        if path == "/api/regions":
            regions = self.world.regions
            red = sum(1 for r in regions if r["owner"] == "red")
            blue = sum(1 for r in regions if r["owner"] == "blue")
            neutral = sum(1 for r in regions if r["owner"] is None)
            contested = sum(1 for r in regions if r["state"] == "CONTESTED")
            return self._json({
                "count": len(regions),
                "red_owned": red,
                "blue_owned": blue,
                "neutral": neutral,
                "contested": contested,
                "regions": regions,
            })

        # /api/player/{id}/round/{rid}
        parts = path.split("/")
        if len(parts) == 6 and parts[1] == "api" and parts[2] == "player" and parts[4] == "round":
            identifier = parts[3]
            try:
                rid = int(parts[5])
            except ValueError:
                return self._not_found("Invalid round id")
            p = self.world.player(identifier)
            if p is None:
                return self._not_found("Player not found")
            # Return the same stats scaled down (mock per-round approximation)
            round_stats = {}
            for group, vals in p["stats"].items():
                round_stats[group] = {k: max(0, v // NUM_ROUNDS) for k, v in vals.items()}
            return self._json({
                "uuid": p["uuid"],
                "username": p["username"],
                "roundId": rid,
                "stats": round_stats,
            })

        # /api/player/{id}
        if path.startswith("/api/player/"):
            identifier = path.split("/")[-1]
            p = self.world.player(identifier)
            if p is None:
                return self._not_found("Player not found")
            return self._json(p)

        self._not_found()


# ── Entry point ─────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Mock Entrenched stats API server")
    parser.add_argument("--port", type=int, default=8080, help="Port to listen on (default 8080)")
    parser.add_argument("--seed", type=int, default=None, help="RNG seed for reproducible data")
    args = parser.parse_args()

    world = MockWorld(seed=args.seed)
    MockHandler.world = world

    print(f"\n{'=' * 60}")
    print(f"  Entrenched Mock Stats API")
    print(f"  Listening on http://localhost:{args.port}")
    print(f"  Seed: {args.seed or '(random)'}")
    print(f"  Players: {len(world.players)}")
    print(f"  Rounds:  {len(world.rounds)}")
    print(f"{'=' * 60}")
    print(f"\n  Sample players you can query:")
    for p in world.players[:5]:
        print(f"    - {p['username']:<20}  uuid={p['uuid']}")
    print(f"    ... and {len(world.players) - 5} more\n")
    print(f"  Try:  curl http://localhost:{args.port}/api/health")
    print(f"        curl http://localhost:{args.port}/api/leaderboard/kills")
    print(f"        curl http://localhost:{args.port}/api/player/{world.players[0]['username']}")
    print(f"        curl http://localhost:{args.port}/api/round/1")
    print()

    server = HTTPServer(("", args.port), MockHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()


if __name__ == "__main__":
    main()


