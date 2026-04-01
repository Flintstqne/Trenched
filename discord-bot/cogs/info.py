"""
General info commands cog for the Entrenched Discord bot.
Provides /warscore, /online, /history, and /help.
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from datetime import datetime, timezone
from typing import Optional, List

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.info')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


def _bar(current: int, total: int, length: int = 12) -> str:
    if total == 0:
        return '░' * length
    filled = round(current / total * length)
    return '▓' * filled + '░' * (length - filled)


class InfoCog(commands.Cog):
    """General info commands"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)

    async def _player_autocomplete(
        self, interaction: discord.Interaction, current: str
    ) -> List[app_commands.Choice[str]]:
        names = self.api.known_players()
        filtered = [n for n in names if current.lower() in n][:25]
        return [app_commands.Choice(name=n, value=n) for n in filtered]

    # ── /warscore ──────────────────────────────────────────────────────

    @app_commands.command(name='warscore', description='Live war scoreboard — who\'s winning?')
    async def warscore(self, interaction: discord.Interaction):
        """Show the current war score overview."""
        await interaction.response.defer()

        try:
            # Fetch regions, teams, and round info in parallel-ish
            regions = await self.api.get_regions()
            red_data = await self.api.get_team('red')
            blue_data = await self.api.get_team('blue')

            rounds_data = await self.api.get_rounds()
            round_ids = rounds_data.get('roundIds', [])
            current_round = None
            if round_ids:
                current_round = await self.api.get_round(round_ids[0])

            # Region counts
            red_regions = regions.get('red_owned', 0)
            blue_regions = regions.get('blue_owned', 0)
            neutral = regions.get('neutral', 0)
            contested = regions.get('contested', 0)
            total_regions = regions.get('count', 16)

            # Team totals
            red_totals = red_data.get('totals', {})
            blue_totals = blue_data.get('totals', {})
            red_kills = int(red_totals.get('kills', 0))
            blue_kills = int(blue_totals.get('kills', 0))
            red_obj = int(red_totals.get('objectives_completed', 0))
            blue_obj = int(blue_totals.get('objectives_completed', 0))
            red_caps = int(red_totals.get('regions_captured', 0))
            blue_caps = int(blue_totals.get('regions_captured', 0))

            # Determine who's leading
            red_score = red_regions * 100 + red_kills + red_obj * 50
            blue_score = blue_regions * 100 + blue_kills + blue_obj * 50
            if red_score > blue_score:
                color = discord.Color.red()
                verdict = "🔴 Red is leading"
            elif blue_score > red_score:
                color = discord.Color.blue()
                verdict = "🔵 Blue is leading"
            else:
                color = discord.Color.greyple()
                verdict = "⚖️ Dead even"

            embed = discord.Embed(
                title="⚔️ War Score",
                description=f"**{verdict}**",
                color=color,
                timestamp=_ts(),
            )

            # Territory control bar
            region_bar = '🔴' * red_regions + '⚡' * contested + '⬜' * neutral + '🔵' * blue_regions
            embed.add_field(
                name=f"🗺️ Territory Control ({total_regions} regions)",
                value=(
                    f"{region_bar}\n"
                    f"🔴 **{red_regions}** owned  •  🔵 **{blue_regions}** owned\n"
                    f"⬜ {neutral} neutral  •  ⚡ {contested} contested"
                ),
                inline=False,
            )

            # Side-by-side stats
            embed.add_field(
                name="🔴 Red Team",
                value=(
                    f"**Kills:** {red_kills:,}\n"
                    f"**Objectives:** {red_obj:,}\n"
                    f"**Captures:** {red_caps:,}\n"
                    f"**Players:** {red_data.get('playerCount', 0)}"
                ),
                inline=True,
            )
            embed.add_field(
                name="🔵 Blue Team",
                value=(
                    f"**Kills:** {blue_kills:,}\n"
                    f"**Objectives:** {blue_obj:,}\n"
                    f"**Captures:** {blue_caps:,}\n"
                    f"**Players:** {blue_data.get('playerCount', 0)}"
                ),
                inline=True,
            )

            # Round info
            if current_round:
                rid = current_round.get('roundId', '?')
                winner = current_round.get('winner')
                dur = current_round.get('durationMinutes', 0)
                dur_str = f"{dur // 60}h {dur % 60}m" if dur else "ongoing"
                round_text = f"**Round {rid}** — "
                if winner:
                    emoji = {'RED': '🔴', 'BLUE': '🔵', 'DRAW': '🤝'}.get(winner, '')
                    round_text += f"{emoji} Winner: {winner}"
                else:
                    round_text += f"⏱ {dur_str}"
                embed.add_field(name="📋 Current Round", value=round_text, inline=False)

            embed.set_footer(text="Live war data")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /online ────────────────────────────────────────────────────────

    @app_commands.command(name='online', description='See who\'s currently online')
    async def online(self, interaction: discord.Interaction):
        """Show all online players grouped by team."""
        await interaction.response.defer()

        try:
            data = await self.api.get_online()
            players = data.get('players', [])
            count = data.get('count', 0)
            max_p = data.get('max', 0)

            embed = discord.Embed(
                title=f"👥 Online Players — {count}/{max_p}",
                color=discord.Color.green() if count > 0 else discord.Color.greyple(),
                timestamp=_ts(),
            )

            if not players:
                embed.description = "No players online right now."
                await interaction.followup.send(embed=embed)
                return

            # Group by team
            red_players = [p for p in players if p.get('team') == 'red']
            blue_players = [p for p in players if p.get('team') == 'blue']
            no_team = [p for p in players if not p.get('team')]

            def format_player(p):
                tag = p.get('rank_tag', '')
                tag_str = f" `[{tag}]`" if tag else ""
                div = p.get('division_tag', '')
                div_str = f" [{div}]" if div else ""
                return f"**{p['username']}**{tag_str}{div_str}"

            if red_players:
                lines = [format_player(p) for p in red_players]
                embed.add_field(
                    name=f"🔴 Red Team ({len(red_players)})",
                    value='\n'.join(lines),
                    inline=True,
                )

            if blue_players:
                lines = [format_player(p) for p in blue_players]
                embed.add_field(
                    name=f"🔵 Blue Team ({len(blue_players)})",
                    value='\n'.join(lines),
                    inline=True,
                )

            if no_team:
                lines = [format_player(p) for p in no_team]
                embed.add_field(
                    name=f"⬜ No Team ({len(no_team)})",
                    value='\n'.join(lines),
                    inline=False,
                )

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /history ───────────────────────────────────────────────────────

    @app_commands.command(name='history', description='View a player\'s round-by-round performance')
    @app_commands.describe(username='Minecraft username to look up')
    @app_commands.autocomplete(username=_player_autocomplete)
    async def history(self, interaction: discord.Interaction, username: str):
        """Show a player's performance across recent rounds."""
        await interaction.response.defer()

        try:
            # Get player data to resolve UUID
            player = await self.api.get_player(username)
            uuid = player['uuid']
            display_name = player['username']

            # Get all rounds
            rounds_data = await self.api.get_rounds()
            round_ids = rounds_data.get('roundIds', [])

            if not round_ids:
                await interaction.followup.send("No rounds recorded yet.", ephemeral=True)
                return

            embed = discord.Embed(
                title=f"📜 {display_name}'s Round History",
                color=discord.Color.dark_gold(),
                timestamp=_ts(),
            )

            lines = []
            rounds_found = 0

            # Check up to 15 most recent rounds
            for rid in round_ids[:15]:
                try:
                    rd = await self.api.get_player_round(uuid, rid)
                    combat = rd.get('stats', {}).get('combat', {})
                    territory = rd.get('stats', {}).get('territory', {})

                    kills = int(combat.get('kills', 0))
                    deaths = int(combat.get('deaths', 0))
                    assists = int(combat.get('assists', 0))
                    caps = int(territory.get('regions_captured', 0))
                    objectives = int(rd.get('stats', {}).get('objective', {}).get('objectives_completed', 0))

                    # Get round summary for winner info
                    try:
                        round_info = await self.api.get_round(rid)
                        winner = round_info.get('winner', '')
                        emoji = {'RED': '🔴', 'BLUE': '🔵', 'DRAW': '🤝'}.get(winner, '❓')
                    except APIError:
                        emoji = '❓'

                    line = (
                        f"{emoji} **Round {rid}** — "
                        f"⚔️ {kills}/{deaths}/{assists} "
                        f"🏴 {caps} cap "
                        f"🎯 {objectives} obj"
                    )
                    lines.append(line)
                    rounds_found += 1
                except APIError:
                    # Player didn't participate in this round
                    continue

            if not lines:
                embed.description = f"**{display_name}** hasn't participated in any recorded rounds."
            else:
                # Overall averages
                embed.description = '\n'.join(lines)

            embed.set_footer(text=f"{rounds_found} round(s) found  •  ⚔️ K/D/A  🏴 Captures  🎯 Objectives")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /predict ───────────────────────────────────────────────────────

    @app_commands.command(name='predict', description='AI war prediction — who\'s going to win?')
    async def predict(self, interaction: discord.Interaction):
        """Analyse territory, combat, and manpower to predict the war winner."""
        await interaction.response.defer()

        try:
            # Gather data from multiple endpoints
            regions = await self.api.get_regions()
            red_team = await self.api.get_team('red')
            blue_team = await self.api.get_team('blue')

            # ── Territory control (40% weight) ──
            red_regions = regions.get('red_owned', 0)
            blue_regions = regions.get('blue_owned', 0)
            total_regions = red_regions + blue_regions + regions.get('neutral', 0)
            contested = regions.get('contested', 0)

            # ── Combat stats (30% weight) ──
            red_totals = red_team.get('totals', {})
            blue_totals = blue_team.get('totals', {})
            red_kills = float(red_totals.get('kills', 0))
            blue_kills = float(blue_totals.get('kills', 0))
            red_objectives = float(red_totals.get('objectives_completed', 0))
            blue_objectives = float(blue_totals.get('objectives_completed', 0))
            red_captures = float(red_totals.get('regions_captured', 0))
            blue_captures = float(blue_totals.get('regions_captured', 0))

            # ── Manpower (15% weight each) ──
            red_players = red_team.get('playerCount', 0)
            blue_players = blue_team.get('playerCount', 0)

            try:
                online = await self.api.get_online()
                online_players = online.get('players', [])
                red_online = sum(1 for p in online_players if (p.get('team') or '').lower() == 'red')
                blue_online = sum(1 for p in online_players if (p.get('team') or '').lower() == 'blue')
            except (APIError, Exception):
                red_online = 0
                blue_online = 0

            # ── Compute weighted score ──
            def safe_ratio(a, b):
                total = a + b
                return a / total if total > 0 else 0.5

            territory_score = safe_ratio(red_regions, blue_regions)
            kills_score = safe_ratio(red_kills, blue_kills)
            obj_score = safe_ratio(red_objectives, blue_objectives)
            cap_score = safe_ratio(red_captures, blue_captures)
            combat_score = (kills_score * 0.5 + obj_score * 0.3 + cap_score * 0.2)
            roster_score = safe_ratio(red_players, blue_players)
            online_score = safe_ratio(red_online, blue_online)

            red_score = (
                territory_score * 0.40 +
                combat_score * 0.30 +
                roster_score * 0.15 +
                online_score * 0.15
            )
            blue_score = 1 - red_score

            # ── Confidence label ──
            diff = abs(red_score - blue_score)
            if diff < 0.05:
                confidence = "Coin Flip 🎲"
            elif diff < 0.15:
                confidence = "Slight Edge 📊"
            elif diff < 0.30:
                confidence = "Strong Advantage 💪"
            else:
                confidence = "Dominant 🏆"

            # ── Winner ──
            if red_score > blue_score:
                winner_emoji, winner_name = '🔴', 'Red'
                color = discord.Color.red()
            elif blue_score > red_score:
                winner_emoji, winner_name = '🔵', 'Blue'
                color = discord.Color.blue()
            else:
                winner_emoji, winner_name = '🤝', 'Tied'
                color = discord.Color.greyple()

            embed = discord.Embed(
                title="🔮 War Prediction",
                description=(
                    f"{winner_emoji} **{winner_name}** is favored to win!\n"
                    f"Confidence: **{confidence}**"
                ),
                color=color,
                timestamp=_ts(),
            )

            # Territory breakdown
            embed.add_field(
                name="🗺️ Territory Control (40%)",
                value=(
                    f"🔴 **{red_regions}** vs 🔵 **{blue_regions}** "
                    f"({total_regions} total, {contested} contested)\n"
                    f"🔴 {_bar(red_regions, total_regions)} 🔵"
                ),
                inline=False,
            )

            # Combat breakdown
            embed.add_field(
                name="⚔️ Combat Performance (30%)",
                value=(
                    f"🔴 {int(red_kills):,} kills / {int(red_objectives)} obj / {int(red_captures)} cap\n"
                    f"🔵 {int(blue_kills):,} kills / {int(blue_objectives)} obj / {int(blue_captures)} cap"
                ),
                inline=True,
            )

            # Manpower breakdown
            embed.add_field(
                name="👥 Manpower (30%)",
                value=(
                    f"🔴 {red_players} roster / {red_online} online\n"
                    f"🔵 {blue_players} roster / {blue_online} online"
                ),
                inline=True,
            )

            # Win probability bar
            embed.add_field(
                name="📊 Win Probability",
                value=(
                    f"🔴 **{red_score * 100:.0f}%** "
                    f"{_bar(int(red_score * 100), 100, 16)} "
                    f"**{blue_score * 100:.0f}%** 🔵"
                ),
                inline=False,
            )

            embed.set_footer(text="Weighted: 40% territory • 30% combat • 15% roster • 15% online")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /help ──────────────────────────────────────────────────────────

    @app_commands.command(name='help', description='List all available bot commands')
    async def help_command(self, interaction: discord.Interaction):
        """Show all available slash commands."""
        embed = discord.Embed(
            title="📖 Entrenched Bot Commands",
            description="All available slash commands for the Entrenched war game.",
            color=discord.Color.dark_teal(),
            timestamp=_ts(),
        )

        embed.add_field(
            name="📊 Player Stats",
            value=(
                "**/profile** `<player>` — Player card with skin, rank & key stats\n"
                "**/stats** `<player>` — View a player's full statistics\n"
                "**/compare** `<player1>` `<player2>` — Compare two players side-by-side\n"
                "**/leaderboard** `<category>` — Top players for a stat\n"
                "**/top** — Quick top-3 leaders for key stats\n"
                "**/history** `<player>` — Round-by-round performance"
            ),
            inline=False,
        )

        embed.add_field(
            name="⚔️ War",
            value=(
                "**/warscore** — Live war scoreboard: who's winning?\n"
                "**/predict** — AI war prediction with win probabilities\n"
                "**/online** — See who's currently online\n"
                "**/round** `[id]` — View round summary\n"
                "**/rounds** — List all rounds\n"
                "**/team** `<red|blue>` — Team aggregate stats"
            ),
            inline=False,
        )

        embed.add_field(
            name="🗺️ Map",
            value=(
                "**/map** — Screenshot the live BlueMap war map"
            ),
            inline=False,
        )

        embed.add_field(
            name="🏴 Divisions",
            value=(
                "**/divisions** `[team]` — List all active divisions\n"
                "**/division** `<name>` — View division details & roster"
            ),
            inline=False,
        )

        embed.add_field(
            name="🎖️ Progression",
            value=(
                "**/merits** — View all merit ranks\n"
                "**/achievements** — Guide to all earnable achievements\n"
                "**/objectives** — Guide to all objective types & IP rewards\n"
                "**/server** — Check server status"
            ),
            inline=False,
        )

        embed.add_field(
            name="📚 Guides",
            value=(
                "**/guides** — Hub listing all guide & reference commands"
            ),
            inline=False,
        )

        embed.add_field(
            name="🔗 Account Linking",
            value=(
                "**/link** `<code>` — Link your Discord to your MC account\n"
                "**/unlink** — Remove your account link\n"
                "**/whois** `<member>` — Check who a Discord member is in-game\n"
                "**/me** — Your own player card (requires linked account)\n"
                "**/mystats** — Your full statistics (requires linked account)\n"
                "**/progress** — Achievement tracker & rank progression"
            ),
            inline=False,
        )

        embed.add_field(
            name="🌟 Community",
            value=(
                "**/spotlight** — Today's Player of the Day"
            ),
            inline=False,
        )

        embed.set_footer(text="Entrenched — Minecraft War Game")
        await interaction.response.send_message(embed=embed)


async def setup(bot: commands.Bot):
    await bot.add_cog(InfoCog(bot))


