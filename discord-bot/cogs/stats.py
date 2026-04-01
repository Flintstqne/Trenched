"""
Stats commands cog for the Entrenched Discord bot.
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any, Tuple
import uuid
import urllib.parse

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.stats')


# ── Helpers ─────────────────────────────────────────────────────────────

def _ts() -> datetime:
    """Current UTC timestamp for embed footers."""
    return datetime.now(timezone.utc)


def _normalize_identifier(value: Optional[str]) -> Tuple[Optional[str], bool]:
    """Normalize a potential UUID or username.
    Returns a tuple (normalized_value, is_uuid).
    - If value is a valid UUID (dashed or undashed or URL-encoded), returns the canonical
      dashed lowercase UUID and is_uuid=True.
    - Otherwise returns the original (stripped) value and is_uuid=False.
    """
    if not value:
        return None, False
    v = value.strip()
    # Try parsing directly as a UUID
    try:
        u = uuid.UUID(v)
        return str(u), True
    except Exception:
        pass

    # Try URL-decoding then parse
    try:
        v2 = urllib.parse.unquote(v)
        u = uuid.UUID(v2)
        return str(u), True
    except Exception:
        pass

    return v, False


def _format_stat(value: float, category: str) -> str:
    """Format a stat value for display."""
    if category in ('time_played', 'time_in_enemy_territory'):
        hours = int(value) // 60
        minutes = int(value) % 60
        return f"{hours}h {minutes}m"
    elif category in ('damage_dealt', 'damage_taken', 'ip_earned', 'depot_loot_value'):
        return f"{value:,.0f}"
    elif value == int(value):
        return str(int(value))
    else:
        return f"{value:.2f}"


def _bar(current: int, total: int, length: int = 10) -> str:
    """Simple text progress bar."""
    if total == 0:
        return '░' * length
    filled = round(current / total * length)
    return '▓' * filled + '░' * (length - filled)


# ── Cog ─────────────────────────────────────────────────────────────────

class StatsCog(commands.Cog):
    """Commands for viewing player and game statistics"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)

    # ── Autocomplete ────────────────────────────────────────────────────

    async def _player_autocomplete(
        self, interaction: discord.Interaction, current: str
    ) -> List[app_commands.Choice[str]]:
        """Suggest player names the bot has seen before."""
        names = self.api.known_players()
        filtered = [n for n in names if current.lower() in n][:25]
        return [app_commands.Choice(name=n, value=n) for n in filtered]

    # ── /profile ─────────────────────────────────────────────────────────

    @app_commands.command(name='profile', description='Player card with skin, rank, division & key stats')
    @app_commands.describe(username='Minecraft username to look up')
    @app_commands.autocomplete(username=_player_autocomplete)
    async def profile(self, interaction: discord.Interaction, username: str):
        """Compact player card with a skin render thumbnail."""
        await interaction.response.defer()

        try:
            data = await self.api.get_player(username)
            uuid = data['uuid']
            display_name = data['username']

            # ── Merit / rank info ──
            merit = data.get('merit', {})
            rank_tag = merit.get('rank_tag', '')
            rank_name = merit.get('rank_name', '')
            merits_count = merit.get('merits', 0)
            rank_label = f"[{rank_tag}] {rank_name}" if rank_tag else "Unranked"

            # ── Division info ──
            division = data.get('division', {})
            div_tag = division.get('tag', '')
            div_name = division.get('name', '')
            div_label = f"[{div_tag}] {div_name}" if div_tag else "No division"

            # ── Team ──
            team = data.get('team', '')
            team_emoji = {'red': '🔴', 'blue': '🔵'}.get(team.lower(), '⬜') if team else '⬜'

            # ── Key stats ──
            combat = data.get('stats', {}).get('combat', {})
            territory = data.get('stats', {}).get('territory', {})
            objective = data.get('stats', {}).get('objective', {})
            participation = data.get('stats', {}).get('participation', {})
            computed = data.get('computed', {})

            kills = int(combat.get('kills', 0))
            deaths = int(combat.get('deaths', 0))
            assists = int(combat.get('assists', 0))
            kdr = computed.get('kdr', 0)
            captures = int(territory.get('regions_captured', 0))
            objectives_done = int(objective.get('objectives_completed', 0))
            rounds_played = int(participation.get('rounds_played', 0))
            rounds_won = int(participation.get('rounds_won', 0))
            win_rate = computed.get('win_rate', 0)
            mvp_score = computed.get('mvp_score', 0)
            time_played = int(participation.get('time_played', 0))
            hours = time_played // 60
            minutes = time_played % 60

            # ── Build embed ──
            color = {'red': discord.Color.red(), 'blue': discord.Color.blue()}.get(
                team.lower(), discord.Color.greyple()
            ) if team else discord.Color.greyple()

            embed = discord.Embed(
                title=f"{team_emoji} {display_name}",
                color=color,
                timestamp=_ts(),
            )

            skin_url = f"https://mc-heads.net/body/{display_name}/100"
            avatar_url = f"https://mc-heads.net/avatar/{display_name}/50"
            embed.set_thumbnail(url=skin_url)
            embed.set_author(name=display_name, icon_url=avatar_url)

            embed.add_field(
                name="🎖️ Rank",
                value=rank_label,
                inline=True,
            )
            embed.add_field(
                name="🏴 Division",
                value=div_label,
                inline=True,
            )
            embed.add_field(
                name="🪙 Merits",
                value=str(merits_count),
                inline=True,
            )

            embed.add_field(
                name="⚔️ Combat",
                value=(
                    f"**{kills}** / **{deaths}** / **{assists}** (K/D/A)\n"
                    f"K/D: **{kdr:.2f}**  •  Streak: **{int(combat.get('kill_streak_best', 0))}**"
                ),
                inline=True,
            )
            embed.add_field(
                name="🏴 Territory",
                value=(
                    f"**{captures}** captures\n"
                    f"**{objectives_done}** objectives"
                ),
                inline=True,
            )
            embed.add_field(
                name="📊 Record",
                value=(
                    f"**{rounds_won}**W / **{rounds_played}**P ({win_rate:.0f}%)\n"
                    f"⏱ {hours}h {minutes}m played"
                ),
                inline=True,
            )

            embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}  •  /stats {display_name} for full details")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /top ─────────────────────────────────────────────────────────────

    @app_commands.command(name='top', description='Quick top-3 snapshot for key stats')
    async def top(self, interaction: discord.Interaction):
        """Show the top 3 players in kills, objectives, and captures."""
        await interaction.response.defer()

        try:
            categories = [
                ('⚔️ Kills',       'kills'),
                ('🎯 Objectives',  'objectives_completed'),
                ('🏴 Captures',    'regions_captured'),
            ]

            embed = discord.Embed(
                title="🏆 Top Players",
                description="Current leaders across key categories.",
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            for label, cat in categories:
                data = await self.api.get_leaderboard(cat, 3)
                entries = data.get('entries', [])
                if not entries:
                    embed.add_field(name=label, value="No data yet", inline=True)
                    continue

                lines = []
                for entry in entries:
                    rank = entry['rank']
                    medal = {1: '🥇', 2: '🥈', 3: '🥉'}.get(rank, f'#{rank}')
                    value = _format_stat(entry['value'], cat)
                    tag = entry.get('merit_tag', '')
                    tag_str = f" [{tag}]" if tag else ""
                    lines.append(f"{medal} **{entry['username']}**{tag_str} — {value}")

                embed.add_field(name=label, value='\n'.join(lines), inline=True)

            embed.set_footer(text="Lifetime stats  •  /leaderboard <category> for full rankings")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /stats ──────────────────────────────────────────────────────────

    @app_commands.command(name='stats', description='View player statistics')
    @app_commands.describe(username='Minecraft username to look up')
    @app_commands.autocomplete(username=_player_autocomplete)
    async def stats(self, interaction: discord.Interaction, username: str):
        """View player statistics"""
        await interaction.response.defer()

        try:
            data = await self.api.get_player(username)

            # Merit rank tag (appended by the API if merit system is active)
            merit = data.get('merit', {})
            rank_tag = merit.get('rank_tag', '')
            rank_label = f" [{rank_tag}]" if rank_tag else ""

            embed = discord.Embed(
                title=f"📊 {data['username']}{rank_label}'s Stats",
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            # ── Combat ──
            combat = data.get('stats', {}).get('combat', {})
            kills = int(combat.get('kills', 0))
            deaths = int(combat.get('deaths', 0))
            kdr = data.get('computed', {}).get('kdr', 0)
            kda = data.get('computed', {}).get('kda', 0)
            embed.add_field(
                name="⚔️ Combat",
                value=(
                    f"**Kills:** {kills}\n"
                    f"**Deaths:** {deaths}\n"
                    f"**Assists:** {int(combat.get('assists', 0))}\n"
                    f"**K/D:** {kdr:.2f}  •  **KDA:** {kda:.2f}\n"
                    f"**Best Streak:** {int(combat.get('kill_streak_best', 0))}\n"
                    f"**Bullets Shot:** {int(combat.get('bullets_shot', 0)):,}\n"
                    f"**Potions:** {int(combat.get('potions_used', 0))} "
                    f"({int(combat.get('healing_potions_used', 0))} healing)"
                ),
                inline=True
            )

            # ── Territory ──
            territory = data.get('stats', {}).get('territory', {})
            embed.add_field(
                name="🏴 Territory",
                value=(
                    f"**Captured:** {int(territory.get('regions_captured', 0))}\n"
                    f"**Defended:** {int(territory.get('regions_defended', 0))}\n"
                    f"**Contested:** {int(territory.get('regions_contested', 0))}\n"
                    f"**IP Earned:** {territory.get('ip_earned', 0):,.0f}\n"
                    f"**IP Denied:** {territory.get('ip_denied', 0):,.0f}"
                ),
                inline=True
            )

            # ── Objectives ──
            objective = data.get('stats', {}).get('objective', {})
            embed.add_field(
                name="🎯 Objectives",
                value=(
                    f"**Completed:** {int(objective.get('objectives_completed', 0))}\n"
                    f"**Settlement:** {int(objective.get('objectives_settlement', 0))}\n"
                    f"**Raid:** {int(objective.get('objectives_raid', 0))}\n"
                    f"**Intel:** {int(objective.get('intel_captured', 0))} cap / "
                    f"{int(objective.get('intel_recovered', 0))} rec\n"
                    f"**TNT:** {int(objective.get('tnt_planted', 0))} plant / "
                    f"{int(objective.get('tnt_defused', 0))} defuse"
                ),
                inline=True
            )

            # ── Building & Logistics ──
            building = data.get('stats', {}).get('building', {})
            embed.add_field(
                name="🔨 Building",
                value=(
                    f"**Built:** {int(building.get('buildings_constructed', 0))}  •  "
                    f"**Destroyed:** {int(building.get('buildings_destroyed', 0))}\n"
                    f"**Roads:** {int(building.get('roads_built', 0))} built / "
                    f"{int(building.get('roads_damaged', 0))} damaged\n"
                    f"**Depots:** {int(building.get('depots_placed', 0))} placed / "
                    f"{int(building.get('depots_raided', 0))} raided\n"
                    f"**Blocks:** {int(building.get('blocks_placed', 0)):,} placed / "
                    f"{int(building.get('blocks_broken', 0)):,} broken"
                ),
                inline=True
            )

            # ── Participation ──
            participation = data.get('stats', {}).get('participation', {})
            rounds_played = int(participation.get('rounds_played', 0))
            rounds_won = int(participation.get('rounds_won', 0))
            win_rate = data.get('computed', {}).get('win_rate', 0)
            time_played = int(participation.get('time_played', 0))
            hours = time_played // 60
            minutes = time_played % 60

            embed.add_field(
                name="📊 Participation",
                value=(
                    f"**Rounds:** {rounds_won}W / {rounds_played}P "
                    f"({win_rate:.0f}%)\n"
                    f"**Win Rate:** {_bar(rounds_won, rounds_played)}\n"
                    f"**MVPs:** {int(participation.get('rounds_mvp', 0))}  •  "
                    f"**First Bloods:** {int(participation.get('first_blood', 0))}\n"
                    f"**Time Played:** {hours}h {minutes}m\n"
                    f"**Login Streak:** {int(participation.get('login_streak', 0))} "
                    f"(best: {int(participation.get('login_streak_best', 0))})"
                ),
                inline=True
            )

            # ── Footer ──
            mvp_score = data.get('computed', {}).get('mvp_score', 0)
            embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── /leaderboard ────────────────────────────────────────────────────

    @app_commands.command(name='leaderboard', description='View stat leaderboards')
    @app_commands.describe(
        category='Stat category to show',
        limit='Number of entries to show (default: 10)'
    )
    @app_commands.choices(category=[
        # Combat
        app_commands.Choice(name='Kills', value='kills'),
        app_commands.Choice(name='Deaths', value='deaths'),
        app_commands.Choice(name='Assists', value='assists'),
        app_commands.Choice(name='Damage Dealt', value='damage_dealt'),
        app_commands.Choice(name='Best Kill Streak', value='kill_streak_best'),
        app_commands.Choice(name='Revenge Kills', value='revenge_kills'),
        app_commands.Choice(name='Bullets Shot', value='bullets_shot'),
        app_commands.Choice(name='Potions Used', value='potions_used'),
        # Territory
        app_commands.Choice(name='Regions Captured', value='regions_captured'),
        app_commands.Choice(name='Regions Defended', value='regions_defended'),
        app_commands.Choice(name='IP Earned', value='ip_earned'),
        # Objectives
        app_commands.Choice(name='Objectives', value='objectives_completed'),
        app_commands.Choice(name='Intel Captured', value='intel_captured'),
        app_commands.Choice(name='TNT Planted', value='tnt_planted'),
        # Building & Logistics
        app_commands.Choice(name='Buildings Constructed', value='buildings_constructed'),
        app_commands.Choice(name='Buildings Destroyed', value='buildings_destroyed'),
        app_commands.Choice(name='Roads Built', value='roads_built'),
        app_commands.Choice(name='Depots Raided', value='depots_raided'),
        app_commands.Choice(name='Blocks Broken', value='blocks_broken'),
        # Participation
        app_commands.Choice(name='Rounds Played', value='rounds_played'),
        app_commands.Choice(name='Rounds Won', value='rounds_won'),
        app_commands.Choice(name='MVPs', value='rounds_mvp'),
        app_commands.Choice(name='First Bloods', value='first_blood'),
        app_commands.Choice(name='Time Played', value='time_played'),
        app_commands.Choice(name='Login Streak', value='login_streak_best'),
    ])
    async def leaderboard(
        self,
        interaction: discord.Interaction,
        category: str,
        limit: Optional[int] = 10
    ):
        """View stat leaderboards"""
        await interaction.response.defer()

        try:
            data = await self.api.get_leaderboard(category, min(limit, 25))

            embed = discord.Embed(
                title=f"🏆 {data['displayName']} Leaderboard",
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            entries = data.get('entries', [])
            if not entries:
                embed.description = "No data available yet."
            else:
                lines = []
                for entry in entries:
                    rank = entry['rank']
                    medal = {1: '🥇', 2: '🥈', 3: '🥉'}.get(rank, f'`#{rank:>2}`')
                    value = _format_stat(entry['value'], category)
                    tag = entry.get('merit_tag', '')
                    tag_str = f" [{tag}]" if tag else ""
                    lines.append(f"{medal} **{entry['username']}**{tag_str} — {value}")

                embed.description = '\n'.join(lines)

            embed.set_footer(text=f"Lifetime stats • {data.get('period', 'all time')}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── /round ──────────────────────────────────────────────────────────

    @app_commands.command(name='round', description='View round statistics')
    @app_commands.describe(round_id='Round ID (leave empty for latest)')
    async def round_stats(
        self,
        interaction: discord.Interaction,
        round_id: Optional[int] = None
    ):
        """View round statistics"""
        await interaction.response.defer()

        try:
            if round_id is None:
                rounds_data = await self.api.get_rounds()
                round_ids = rounds_data.get('roundIds', [])
                if not round_ids:
                    await interaction.followup.send("No rounds found.")
                    return
                round_id = round_ids[0]

            data = await self.api.get_round(round_id)

            winner = data.get('winner')
            winner_color = {
                'RED': discord.Color.red(),
                'BLUE': discord.Color.blue(),
                'DRAW': discord.Color.greyple()
            }.get(winner, discord.Color.gold())

            embed = discord.Embed(
                title=f"📋 Round {round_id} Summary",
                color=winner_color,
                timestamp=_ts(),
            )

            if winner:
                emoji = {'RED': '🔴', 'BLUE': '🔵', 'DRAW': '🤝'}.get(winner, '')
                embed.description = f"{emoji} **Winner: {winner}**"

            # Red team
            red = data.get('red', {})
            embed.add_field(
                name="🔴 Red Team",
                value=(
                    f"**Players:** {red.get('players', 0)}\n"
                    f"**Kills:** {int(red.get('kills', 0))}\n"
                    f"**Objectives:** {int(red.get('objectives', 0))}\n"
                    f"**Captures:** {int(red.get('captures', 0))}"
                ),
                inline=True
            )

            # Blue team
            blue = data.get('blue', {})
            embed.add_field(
                name="🔵 Blue Team",
                value=(
                    f"**Players:** {blue.get('players', 0)}\n"
                    f"**Kills:** {int(blue.get('kills', 0))}\n"
                    f"**Objectives:** {int(blue.get('objectives', 0))}\n"
                    f"**Captures:** {int(blue.get('captures', 0))}"
                ),
                inline=True
            )

            # MVP
            mvp = data.get('mvp')
            if mvp:
                embed.add_field(
                    name="⭐ Round MVP",
                    value=f"**{mvp['username']}** ({mvp['score']:,.0f} pts)",
                    inline=False
                )

            duration = data.get('durationMinutes', 0)
            if duration > 0:
                hours = duration // 60
                minutes = duration % 60
                embed.set_footer(text=f"Duration: {hours}h {minutes}m")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── /rounds ─────────────────────────────────────────────────────────

    @app_commands.command(name='rounds', description='List all available rounds')
    async def rounds_list(self, interaction: discord.Interaction):
        """List all round IDs with basic info."""
        await interaction.response.defer()

        try:
            rounds_data = await self.api.get_rounds()
            round_ids = rounds_data.get('roundIds', [])

            if not round_ids:
                await interaction.followup.send("No rounds recorded yet.")
                return

            embed = discord.Embed(
                title="📜 All Rounds",
                color=discord.Color.dark_gold(),
                timestamp=_ts(),
            )

            lines = []
            for rid in round_ids[:25]:
                try:
                    rd = await self.api.get_round(rid)
                    winner = rd.get('winner', '?')
                    emoji = {'RED': '🔴', 'BLUE': '🔵', 'DRAW': '🤝'}.get(winner, '❓')
                    dur = rd.get('durationMinutes', 0)
                    dur_str = f"{dur // 60}h {dur % 60}m" if dur else "—"
                    mvp_name = rd.get('mvp', {}).get('username', '—')
                    lines.append(
                        f"**Round {rid}** {emoji} {winner}  •  ⏱ {dur_str}  •  ⭐ {mvp_name}"
                    )
                except APIError:
                    lines.append(f"**Round {rid}** — could not load details")

            embed.description = '\n'.join(lines)
            embed.set_footer(text=f"{len(round_ids)} round(s) total")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── /team ───────────────────────────────────────────────────────────

    @app_commands.command(name='team', description='View team aggregate statistics')
    @app_commands.describe(
        team='Which team to view',
        round_id='Round ID (leave empty for current round)',
    )
    @app_commands.choices(team=[
        app_commands.Choice(name='🔴 Red', value='red'),
        app_commands.Choice(name='🔵 Blue', value='blue'),
    ])
    async def team_stats(
        self,
        interaction: discord.Interaction,
        team: str,
        round_id: Optional[int] = None,
    ):
        """View team aggregate stats."""
        await interaction.response.defer()

        try:
            data = await self.api.get_team(team, round_id)

            is_red = team.lower() == 'red'
            color = discord.Color.red() if is_red else discord.Color.blue()
            emoji = '🔴' if is_red else '🔵'

            embed = discord.Embed(
                title=f"{emoji} {team.capitalize()} Team Stats",
                color=color,
                timestamp=_ts(),
            )

            embed.add_field(name="👥 Players", value=str(data.get('players', 0)), inline=True)
            embed.add_field(name="⚔️ Kills",   value=str(int(data.get('kills', 0))), inline=True)
            embed.add_field(name="💀 Deaths",   value=str(int(data.get('deaths', 0))), inline=True)
            embed.add_field(name="🎯 Objectives", value=str(int(data.get('objectives', 0))), inline=True)
            embed.add_field(name="🏴 Captures", value=str(int(data.get('captures', 0))), inline=True)

            if round_id:
                embed.set_footer(text=f"Round {round_id}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── /server ─────────────────────────────────────────────────────────

    @app_commands.command(name='server', description='Check game server status')
    async def server_status(self, interaction: discord.Interaction):
        """Check if the game server API is reachable."""
        await interaction.response.defer()

        try:
            data = await self.api.health_check()

            online = data.get('onlinePlayers', 0)
            max_players = data.get('maxPlayers', 0)

            embed = discord.Embed(
                title="🟢 Server Online",
                color=discord.Color.green(),
                timestamp=_ts(),
            )
            embed.add_field(name="Status", value=data.get('status', 'ok'), inline=True)
            embed.add_field(name="API Version", value=data.get('version', '?'), inline=True)
            embed.add_field(
                name="Players",
                value=f"**{online}** / {max_players}" if max_players else str(online),
                inline=True,
            )

            try:
                rounds_data = await self.api.get_rounds()
                embed.add_field(
                    name="Rounds Played",
                    value=str(len(rounds_data.get('roundIds', []))),
                    inline=True,
                )
            except APIError:
                pass

            await interaction.followup.send(embed=embed)

        except APIError:
            embed = discord.Embed(
                title="🔴 Server Offline",
                description="Cannot reach the game server API.",
                color=discord.Color.red(),
                timestamp=_ts(),
            )
            await interaction.followup.send(embed=embed)

    # ── /compare ────────────────────────────────────────────────────────

    @app_commands.command(name='compare', description='Compare two players')
    @app_commands.describe(
        player1='First player username',
        player2='Second player username'
    )
    @app_commands.autocomplete(player1=_player_autocomplete, player2=_player_autocomplete)
    async def compare(
        self,
        interaction: discord.Interaction,
        player1: str,
        player2: str
    ):
        """Compare two players side by side"""
        await interaction.response.defer()

        try:
            data1 = await self.api.get_player(player1)
            data2 = await self.api.get_player(player2)

            embed = discord.Embed(
                title=f"⚔️ {data1['username']} vs {data2['username']}",
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            # Stats to compare: (label, group, key, format)
            stats_to_compare = [
                ('Kills',      'combat',    'kills',                None),
                ('Deaths',     'combat',    'deaths',               None),
                ('Assists',    'combat',    'assists',              None),
                ('K/D',        'computed',  'kdr',                  '.2f'),
                ('Best Streak','combat',    'kill_streak_best',     None),
                ('Objectives', 'objective', 'objectives_completed', None),
                ('Captures',   'territory', 'regions_captured',     None),
                ('IP Earned',  'territory', 'ip_earned',            ',.0f'),
                ('Buildings',  'building',  'buildings_constructed', None),
                ('Roads',      'building',  'roads_built',          None),
                ('Win Rate',   'computed',  'win_rate',             '.0f%'),
            ]

            p1_lines = []
            p2_lines = []
            p1_wins = 0
            p2_wins = 0

            for label, group, key, fmt in stats_to_compare:
                if group == 'computed':
                    v1 = data1.get('computed', {}).get(key, 0)
                    v2 = data2.get('computed', {}).get(key, 0)
                else:
                    v1 = data1.get('stats', {}).get(group, {}).get(key, 0)
                    v2 = data2.get('stats', {}).get(group, {}).get(key, 0)

                # Format values
                if fmt == '.2f':
                    v1_str, v2_str = f"{v1:.2f}", f"{v2:.2f}"
                elif fmt == ',.0f':
                    v1_str, v2_str = f"{v1:,.0f}", f"{v2:,.0f}"
                elif fmt == '.0f%':
                    v1_str, v2_str = f"{v1:.0f}%", f"{v2:.0f}%"
                else:
                    v1_str, v2_str = str(int(v1)), str(int(v2))

                # Deaths: lower is better
                better_is_lower = key in ('deaths',)
                if (v1 < v2 if better_is_lower else v1 > v2):
                    p1_lines.append(f"**{label}:** {v1_str} ✅")
                    p2_lines.append(f"**{label}:** {v2_str}")
                    p1_wins += 1
                elif (v2 < v1 if better_is_lower else v2 > v1):
                    p1_lines.append(f"**{label}:** {v1_str}")
                    p2_lines.append(f"**{label}:** {v2_str} ✅")
                    p2_wins += 1
                else:
                    p1_lines.append(f"**{label}:** {v1_str}")
                    p2_lines.append(f"**{label}:** {v2_str}")

            embed.add_field(
                name=f"{data1['username']}  ({p1_wins})",
                value='\n'.join(p1_lines),
                inline=True
            )

            embed.add_field(
                name=f"{data2['username']}  ({p2_wins})",
                value='\n'.join(p2_lines),
                inline=True
            )

            # Overall verdict
            mvp1 = data1.get('computed', {}).get('mvp_score', 0)
            mvp2 = data2.get('computed', {}).get('mvp_score', 0)
            if p1_wins > p2_wins:
                verdict = f"🏆 {data1['username']} wins {p1_wins}–{p2_wins}"
            elif p2_wins > p1_wins:
                verdict = f"🏆 {data2['username']} wins {p2_wins}–{p1_wins}"
            else:
                verdict = "🤝 Tie"

            embed.set_footer(
                text=f"{verdict}  •  MVP Scores: {mvp1:,.0f} vs {mvp2:,.0f}"
            )

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ {e.message}",
                ephemeral=True
            )

    # ── Linked-account helper ────────────────────────────────────────────

    async def _resolve_linked(self, interaction: discord.Interaction) -> Optional[Dict[str, Any]]:
        """Resolve the calling user's linked MC record.
        Returns the full link record dict (as returned by the API), or None
        if the user isn't linked (an error message is sent automatically).
        """
        try:
            data = await self.api.get_linked(str(interaction.user.id))
            # Return the entire record (mc_uuid, mc_username, etc.) so callers
            # can choose the best identifier and perform defensive retries.
            return data
        except APIError as e:
            if e.status == 404:
                await interaction.followup.send(
                    "❌ Your Discord account isn't linked yet. Use `/link <code>` in-game first.",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(f"❌ {e.message}", ephemeral=True)
            return None

    # ── /me ──────────────────────────────────────────────────────────────

    @app_commands.command(name='me', description='Your own player card (requires a linked account)')
    async def me(self, interaction: discord.Interaction):
        """Show the caller's profile card using their linked MC account."""
        await interaction.response.defer()

        linked = await self._resolve_linked(interaction)
        if linked is None:
            return

        # Prefer UUID but be defensive: try UUID first and if the API rejects it
        # with an "Invalid UUID format" message (400) then retry with username.
        mc_uuid = linked.get('mc_uuid')
        mc_username = linked.get('mc_username')

        # Resolve a canonical UUID using the API helper (handles usernames and caching)
        tried_identifier = None
        canonical_uuid = None
        try:
            if mc_uuid:
                tried_identifier = mc_uuid
                canonical_uuid = await self.api.get_uuid(mc_uuid)
            elif mc_username:
                tried_identifier = mc_username
                canonical_uuid = await self.api.get_uuid(mc_username)
            else:
                await interaction.followup.send("❌ Could not resolve your linked Minecraft account.", ephemeral=True)
                return

            logger.info(f"[/me] linked record for %s: mc_uuid=%s mc_username=%s (resolved_uuid=%s)", interaction.user.id, mc_uuid, mc_username, canonical_uuid)
            try:
                data = await self.api.get_player(canonical_uuid)
            except APIError as e:
                # If server reports player not found for the UUID, try the
                # username-based path first (this will resolve via Mojang and
                # call the server with a canonical UUID). If that fails, try
                # the raw endpoint as a last resort. This avoids hitting the
                # server with a non-UUID username which can return 400.
                if e.status == 404 and mc_username:
                    logger.info("[/me] UUID lookup returned 404; attempting username lookup for %s", mc_username)
                    try:
                        data = await self.api.get_player(mc_username)
                        tried_identifier = mc_username
                    except APIError as e2:
                        logger.info("[/me] username-based lookup failed (%s); attempting raw endpoint for %s", e2, mc_username)
                        try:
                            data = await self.api.get_player_raw(mc_username)
                            tried_identifier = mc_username
                        except APIError as e3:
                            # All attempts failed. Log both and raise a clearer
                            # APIError. Prefer a 404 if any attempt produced one.
                            logger.error("[/me] all lookups failed: %s; %s; %s", e, e2, e3)
                            if any(getattr(x, 'status', None) == 404 for x in (e, e2, e3)):
                                raise APIError(404, "Player not found")
                            status = 0
                            for ex in (e, e2, e3):
                                s = getattr(ex, 'status', None)
                                if s and s != 0:
                                    status = s
                                    break
                            combined_msg = "; ".join(getattr(x, 'message', str(x)) for x in (e, e2, e3))
                            raise APIError(status or 0, combined_msg)
                else:
                    raise

        except APIError as e:
            logger.error("[/me] lookup failed for %s (tried %s): %s", interaction.user.id, tried_identifier, e.message)
            if e.status == 404:
                await interaction.followup.send(
                    "📭 No stats found yet for your account. Join the server and play some rounds first!",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(f"❌ {e.message}", ephemeral=True)
            return

        # Successful lookup; extract returned player fields
        uuid = data['uuid']
        display_name = data['username']
        has_stats = data.get('hasStats', True)
        logger.info(f"[/me] lookup succeeded using identifier=%s (user=%s, has_stats=%s)", tried_identifier, interaction.user.id, has_stats)

        # ── Merit / rank info ──
        merit = data.get('merit', {})
        rank_tag = merit.get('rank_tag', '')
        rank_name = merit.get('rank_name', '')
        merits_count = merit.get('merits', 0)
        rank_label = f"[{rank_tag}] {rank_name}" if rank_tag else "Unranked"

        # ── Division info ──
        division = data.get('division', {})
        div_tag = division.get('tag', '')
        div_name = division.get('name', '')
        div_label = f"[{div_tag}] {div_name}" if div_tag else "No division"

        # ── Team ──
        team = data.get('team', '')
        team_emoji = {'red': '🔴', 'blue': '🔵'}.get(team.lower(), '⬜') if team else '⬜'

        # ── Key stats ──
        combat = data.get('stats', {}).get('combat', {})
        territory = data.get('stats', {}).get('territory', {})
        objective = data.get('stats', {}).get('objective', {})
        participation = data.get('stats', {}).get('participation', {})
        computed = data.get('computed', {})

        kills = int(combat.get('kills', 0))
        deaths = int(combat.get('deaths', 0))
        assists = int(combat.get('assists', 0))
        kdr = computed.get('kdr', 0)
        captures = int(territory.get('regions_captured', 0))
        objectives_done = int(objective.get('objectives_completed', 0))
        rounds_played = int(participation.get('rounds_played', 0))
        rounds_won = int(participation.get('rounds_won', 0))
        win_rate = computed.get('win_rate', 0)
        mvp_score = computed.get('mvp_score', 0)
        time_played = int(participation.get('time_played', 0))
        hours = time_played // 60
        minutes = time_played % 60

        # ── Build embed ──
        color = {'red': discord.Color.red(), 'blue': discord.Color.blue()}.get(
            team.lower(), discord.Color.greyple()
        ) if team else discord.Color.greyple()

        embed = discord.Embed(
            title=f"{team_emoji} {display_name}",
            color=color,
            timestamp=_ts(),
        )

        if not has_stats:
            embed.description = "📭 No stats recorded yet — join the server and play some rounds to start tracking your progress!"

        # Use mc_username from the linked record for skin rendering.
        # data['username'] (= display_name) can fall back to an offline UUID string when
        # Bukkit.getOfflinePlayer().getName() returns null on offline-mode servers.
        # mc-heads.net accepts usernames directly and returns a default Steve skin for unknown
        # players instead of a 404, so Discord never shows a broken image icon.
        skin_for_render = mc_username or display_name
        skin_url = f"https://mc-heads.net/body/{skin_for_render}/100"
        avatar_url = f"https://mc-heads.net/avatar/{skin_for_render}/50"
        embed.set_thumbnail(url=skin_url)
        embed.set_author(name=display_name, icon_url=avatar_url)

        team_label = f"{team_emoji} {team.capitalize()}" if team else "⬜ None"
        embed.add_field(name="🏳️ Team", value=team_label, inline=True)
        embed.add_field(name="🎖️ Rank", value=rank_label, inline=True)
        embed.add_field(name="🏴 Division", value=div_label, inline=True)
        embed.add_field(name="🪙 Merits", value=str(merits_count), inline=True)

        embed.add_field(
            name="⚔️ Combat",
            value=(
                f"**{kills}** / **{deaths}** / **{assists}** (K/D/A)\n"
                f"K/D: **{kdr:.2f}**  •  Streak: **{int(combat.get('kill_streak_best', 0))}**"
            ),
            inline=True,
        )
        embed.add_field(
            name="🏴 Territory",
            value=(
                f"**{captures}** captures\n"
                f"**{objectives_done}** objectives"
            ),
            inline=True,
        )
        embed.add_field(
            name="📊 Record",
            value=(
                f"**{rounds_won}**W / **{rounds_played}**P ({win_rate:.0f}%)\n"
                f"⏱ {hours}h {minutes}m played"
            ),
            inline=True,
        )

        embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}  •  /mystats for full details")

        await interaction.followup.send(embed=embed)


    # ── /mystats ─────────────────────────────────────────────────────────

    @app_commands.command(name='mystats', description='Your full statistics (requires a linked account)')
    async def mystats(self, interaction: discord.Interaction):
        """Show the caller's full stats using their linked MC account."""
        await interaction.response.defer()

        linked = await self._resolve_linked(interaction)
        if linked is None:
            return

        mc_uuid = linked.get('mc_uuid')
        mc_username = linked.get('mc_username')

        # Resolve canonical UUID then fetch player data
        tried_identifier = None
        canonical_uuid = None
        try:
            if mc_uuid:
                tried_identifier = mc_uuid
                canonical_uuid = await self.api.get_uuid(mc_uuid)
            elif mc_username:
                tried_identifier = mc_username
                canonical_uuid = await self.api.get_uuid(mc_username)
            else:
                await interaction.followup.send("❌ Could not resolve your linked Minecraft account.", ephemeral=True)
                return

            logger.info(f"[/mystats] linked record for %s: mc_uuid=%s mc_username=%s (resolved_uuid=%s)", interaction.user.id, mc_uuid, mc_username, canonical_uuid)
            try:
                data = await self.api.get_player(canonical_uuid)
            except APIError as e:
                if e.status == 404 and mc_username:
                    logger.info("[/mystats] UUID lookup returned 404; attempting username lookup for %s", mc_username)
                    # First try the high-level username path which will resolve via
                    # Mojang and call the server with a canonical UUID. If that
                    # fails, fall back to the raw endpoint as a last resort.
                    try:
                        data = await self.api.get_player(mc_username)
                        tried_identifier = mc_username
                    except APIError as e2:
                        logger.info("[/mystats] username-based lookup failed (%s); attempting raw endpoint for %s", e2, mc_username)
                        try:
                            data = await self.api.get_player_raw(mc_username)
                            tried_identifier = mc_username
                        except APIError as e3:
                            # All attempts failed. Log and raise a clearer APIError.
                            logger.error("[/mystats] all lookups failed: %s; %s; %s", e, e2, e3)
                            if any(getattr(x, 'status', None) == 404 for x in (e, e2, e3)):
                                raise APIError(404, "Player not found")
                            # Choose a sensible non-zero status if available
                            status = 0
                            for ex in (e, e2, e3):
                                s = getattr(ex, 'status', None)
                                if s and s != 0:
                                    status = s
                                    break
                            combined_msg = "; ".join(getattr(x, 'message', str(x)) for x in (e, e2, e3))
                            raise APIError(status or 0, combined_msg)
                else:
                    raise

        except APIError as e:
            logger.error("[/mystats] lookup failed for %s (tried %s): %s", interaction.user.id, tried_identifier, e.message)
            if e.status == 404:
                await interaction.followup.send(
                    "📭 No stats found yet for your account. Join the server and play some rounds first!",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(f"❌ {e.message}", ephemeral=True)
            return

        logger.info(f"[/mystats] lookup succeeded using identifier=%s (user=%s)", tried_identifier, interaction.user.id)

        has_stats = data.get('hasStats', True)

        # Merit rank tag
        merit = data.get('merit', {})
        rank_tag = merit.get('rank_tag', '')
        rank_label = f" [{rank_tag}]" if rank_tag else ""

        embed = discord.Embed(
            title=f"📊 {data['username']}{rank_label}'s Stats",
            color=discord.Color.gold(),
            timestamp=_ts(),
        )

        if not has_stats:
            embed.description = "📭 No stats recorded yet — join the server and play some rounds to start tracking your progress!"

        # ── Combat ──
        combat = data.get('stats', {}).get('combat', {})
        kills = int(combat.get('kills', 0))
        deaths = int(combat.get('deaths', 0))
        kdr = data.get('computed', {}).get('kdr', 0)
        kda = data.get('computed', {}).get('kda', 0)
        embed.add_field(
            name="⚔️ Combat",
            value=(
                f"**Kills:** {kills}\n"
                f"**Deaths:** {deaths}\n"
                f"**Assists:** {int(combat.get('assists', 0))}\n"
                f"**K/D:** {kdr:.2f}  •  **KDA:** {kda:.2f}\n"
                f"**Best Streak:** {int(combat.get('kill_streak_best', 0))}\n"
                f"**Bullets Shot:** {int(combat.get('bullets_shot', 0)):,}\n"
                f"**Potions:** {int(combat.get('potions_used', 0))} "
                f"({int(combat.get('healing_potions_used', 0))} healing)"
            ),
            inline=True,
        )

        # ── Territory ──
        territory = data.get('stats', {}).get('territory', {})
        embed.add_field(
            name="🏴 Territory",
            value=(
                f"**Captured:** {int(territory.get('regions_captured', 0))}\n"
                f"**Defended:** {int(territory.get('regions_defended', 0))}\n"
                f"**Contested:** {int(territory.get('regions_contested', 0))}\n"
                f"**IP Earned:** {territory.get('ip_earned', 0):,.0f}\n"
                f"**IP Denied:** {territory.get('ip_denied', 0):,.0f}"
            ),
            inline=True,
        )

        # ── Objectives ──
        objective = data.get('stats', {}).get('objective', {})
        embed.add_field(
            name="🎯 Objectives",
            value=(
                f"**Completed:** {int(objective.get('objectives_completed', 0))}\n"
                f"**Settlement:** {int(objective.get('objectives_settlement', 0))}\n"
                f"**Raid:** {int(objective.get('objectives_raid', 0))}\n"
                f"**Intel:** {int(objective.get('intel_captured', 0))} cap / "
                f"{int(objective.get('intel_recovered', 0))} rec\n"
                f"**TNT:** {int(objective.get('tnt_planted', 0))} plant / "
                f"{int(objective.get('tnt_defused', 0))} defuse"
            ),
            inline=True,
        )

        # ── Building & Logistics ──
        building = data.get('stats', {}).get('building', {})
        embed.add_field(
            name="🔨 Building",
            value=(
                f"**Built:** {int(building.get('buildings_constructed', 0))}  •  "
                f"**Destroyed:** {int(building.get('buildings_destroyed', 0))}\n"
                f"**Roads:** {int(building.get('roads_built', 0))} built / "
                f"{int(building.get('roads_damaged', 0))} damaged\n"
                f"**Depots:** {int(building.get('depots_placed', 0))} placed / "
                f"{int(building.get('depots_raided', 0))} raided\n"
                f"**Blocks:** {int(building.get('blocks_placed', 0)):,} placed / "
                f"{int(building.get('blocks_broken', 0)):,} broken"
            ),
            inline=True,
        )

        # ── Participation ──
        participation = data.get('stats', {}).get('participation', {})
        rounds_played = int(participation.get('rounds_played', 0))
        rounds_won = int(participation.get('rounds_won', 0))
        win_rate = data.get('computed', {}).get('win_rate', 0)
        time_played = int(participation.get('time_played', 0))
        hours = time_played // 60
        minutes = time_played % 60

        embed.add_field(
            name="📊 Participation",
            value=(
                f"**Rounds:** {rounds_won}W / {rounds_played}P "
                f"({win_rate:.0f}%)\n"
                f"**Win Rate:** {_bar(rounds_won, rounds_played)}\n"
                f"**MVPs:** {int(participation.get('rounds_mvp', 0))}  •  "
                f"**First Bloods:** {int(participation.get('first_blood', 0))}\n"
                f"**Time Played:** {hours}h {minutes}m\n"
                f"**Login Streak:** {int(participation.get('login_streak', 0))} "
                f"(best: {int(participation.get('login_streak_best', 0))})"
            ),
            inline=True,
        )

        # ── Footer ──
        mvp_score = data.get('computed', {}).get('mvp_score', 0)
        embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}")

        await interaction.followup.send(embed=embed)


    # ── /progress ────────────────────────────────────────────────────────

    CATEGORY_EMOJI = {
        'combat': '⚔️',
        'territory': '🏴',
        'logistics': '🔧',
        'social': '🤝',
        'progression': '📈',
        'time': '⏰',
        'round': '🏆',
    }

    @app_commands.command(name='progress', description='Your achievement progress & rank tracker (requires linked account)')
    async def progress(self, interaction: discord.Interaction):
        """Show personal achievement tracker grouped by category with rank progression."""
        await interaction.response.defer()

        # Resolve linked account
        try:
            linked = await self.api.get_linked(str(interaction.user.id))
            mc_uuid = linked.get('mc_uuid')
            mc_name = linked.get('mc_username', 'Unknown')
        except APIError as e:
            if e.status == 404:
                await interaction.followup.send(
                    "❌ Your Discord account isn't linked yet. Use `/link <code>` in-game first.",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(f"❌ {e.message}", ephemeral=True)
            return

        if not mc_uuid:
            await interaction.followup.send("❌ Could not resolve your Minecraft UUID.", ephemeral=True)
            return

        # Resolve a canonical UUID for merits lookup
        canonical_uuid = None
        try:
            identifier = mc_uuid or linked.get('mc_username')
            if not identifier:
                await interaction.followup.send("❌ Could not resolve your Minecraft account.", ephemeral=True)
                return
            canonical_uuid = await self.api.get_uuid(identifier)
            data = await self.api.get_merits(canonical_uuid)
        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)
            return

        # ── Overall stats ──
        unlocked_count = data.get('achievements_unlocked', 0)
        total_count = data.get('achievements_total', 0)
        rank = data.get('rank', 'Recruit')
        rank_tag = data.get('rank_tag', 'RCT')
        token_balance = data.get('token_balance', 0)
        login_streak = data.get('login_streak', 0)
        received_merits = data.get('received_merits', 0)
        playtime = data.get('playtime_minutes', 0)
        hours = playtime // 60
        minutes = playtime % 60

        # ── Group achievements by category ──
        achievements = data.get('achievements', [])
        categories: dict[str, list] = {}
        for a in achievements:
            cat = a.get('category', 'other')
            categories.setdefault(cat, []).append(a)

        overall_pct = (unlocked_count / total_count * 100) if total_count > 0 else 0

        embed = discord.Embed(
            title=f"📋 {mc_name}'s Progress",
            color=discord.Color.dark_teal(),
            timestamp=_ts(),
        )

        skin_url = f"https://mc-heads.net/avatar/{mc_name}/50"
        embed.set_thumbnail(url=skin_url)

        embed.description = (
            f"**Achievements:** {unlocked_count}/{total_count} ({overall_pct:.0f}%)\n"
            f"{_bar(unlocked_count, total_count, 20)}\n\n"
            f"**Rank:** [{rank_tag}] {rank}  •  **Merits:** {received_merits:,}\n"
            f"**Tokens:** {token_balance:,} 🪙  •  **Streak:** {login_streak} 🔥  •  ⏱ {hours}h {minutes}m"
        )

        # ── Rank progression ──
        next_rank = data.get('next_rank')
        if next_rank:
            nr_name = next_rank.get('name', '?')
            nr_tag = next_rank.get('tag', '?')
            nr_required = next_rank.get('required', 0)
            nr_remaining = next_rank.get('remaining', 0)
            nr_current = nr_required - nr_remaining
            embed.add_field(
                name=f"🎖️ Next Rank: [{nr_tag}] {nr_name}",
                value=f"{_bar(nr_current, nr_required, 15)} {nr_current}/{nr_required} merits",
                inline=False,
            )
        else:
            embed.add_field(
                name="🎖️ Max Rank Achieved!",
                value="You've reached the highest rank. 🎉",
                inline=False,
            )

        # ── Per-category breakdown ──
        for cat_key, cat_achievements in categories.items():
            emoji = self.CATEGORY_EMOJI.get(cat_key, '🎖️')
            cat_unlocked = sum(1 for a in cat_achievements if a.get('unlocked'))
            cat_total = len(cat_achievements)

            lines = [f"{_bar(cat_unlocked, cat_total, 10)} {cat_unlocked}/{cat_total}"]
            for a in cat_achievements:
                status = '✅' if a.get('unlocked') else '🔒'
                lines.append(f"{status} {a['name']}")

            value = '\n'.join(lines)
            if len(value) > 1024:
                value = value[:1020] + '…'

            embed.add_field(
                name=f"{emoji} {cat_key.capitalize()}",
                value=value,
                inline=True,
            )

        embed.set_footer(text="/achievements for full descriptions  •  /mystats for detailed stats")

        await interaction.followup.send(embed=embed)


async def setup(bot: commands.Bot):
    await bot.add_cog(StatsCog(bot))
