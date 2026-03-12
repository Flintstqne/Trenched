"""
Stats commands cog for the Entrenched Discord bot.
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from typing import Optional

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.stats')


class StatsCog(commands.Cog):
    """Commands for viewing player and game statistics"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)

    def _format_stat_value(self, value: float, category: str) -> str:
        """Format a stat value for display"""
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

    @app_commands.command(name='stats', description='View player statistics')
    @app_commands.describe(username='Minecraft username to look up')
    async def stats(self, interaction: discord.Interaction, username: str):
        """View player statistics"""
        await interaction.response.defer()

        try:
            # Note: In production, you'd need to resolve username to UUID
            # For now, we'll assume the API can handle usernames or show an error
            data = await self.api.get_player(username)

            embed = discord.Embed(
                title=f"📊 {data['username']}'s Stats",
                color=discord.Color.gold()
            )

            # Combat stats
            combat = data.get('stats', {}).get('combat', {})
            embed.add_field(
                name="⚔️ Combat",
                value=(
                    f"**Kills:** {int(combat.get('kills', 0))}\n"
                    f"**Deaths:** {int(combat.get('deaths', 0))}\n"
                    f"**K/D:** {data.get('computed', {}).get('kdr', 0):.2f}\n"
                    f"**Assists:** {int(combat.get('assists', 0))}"
                ),
                inline=True
            )

            # Territory stats
            territory = data.get('stats', {}).get('territory', {})
            embed.add_field(
                name="🏴 Territory",
                value=(
                    f"**Captured:** {int(territory.get('regions_captured', 0))}\n"
                    f"**Defended:** {int(territory.get('regions_defended', 0))}\n"
                    f"**IP Earned:** {territory.get('ip_earned', 0):,.0f}"
                ),
                inline=True
            )

            # Objective stats
            objective = data.get('stats', {}).get('objective', {})
            embed.add_field(
                name="🎯 Objectives",
                value=(
                    f"**Completed:** {int(objective.get('objectives_completed', 0))}\n"
                    f"**Settlement:** {int(objective.get('objectives_settlement', 0))}\n"
                    f"**Raid:** {int(objective.get('objectives_raid', 0))}"
                ),
                inline=True
            )

            # Participation stats
            participation = data.get('stats', {}).get('participation', {})
            time_played = int(participation.get('time_played', 0))
            hours = time_played // 60
            minutes = time_played % 60

            embed.add_field(
                name="📊 Participation",
                value=(
                    f"**Rounds Played:** {int(participation.get('rounds_played', 0))}\n"
                    f"**Rounds Won:** {int(participation.get('rounds_won', 0))}\n"
                    f"**MVPs:** {int(participation.get('rounds_mvp', 0))}\n"
                    f"**Time Played:** {hours}h {minutes}m"
                ),
                inline=True
            )

            # MVP Score
            mvp_score = data.get('computed', {}).get('mvp_score', 0)
            embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ Error: {e.message}",
                ephemeral=True
            )

    @app_commands.command(name='leaderboard', description='View stat leaderboards')
    @app_commands.describe(
        category='Stat category to show',
        limit='Number of entries to show (default: 10)'
    )
    @app_commands.choices(category=[
        app_commands.Choice(name='Kills', value='kills'),
        app_commands.Choice(name='Deaths', value='deaths'),
        app_commands.Choice(name='Objectives', value='objectives_completed'),
        app_commands.Choice(name='Regions Captured', value='regions_captured'),
        app_commands.Choice(name='IP Earned', value='ip_earned'),
        app_commands.Choice(name='Time Played', value='time_played'),
        app_commands.Choice(name='Kill Streak', value='kill_streak_best'),
        app_commands.Choice(name='MVPs', value='rounds_mvp'),
        app_commands.Choice(name='Rounds Won', value='rounds_won'),
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
                color=discord.Color.gold()
            )

            entries = data.get('entries', [])
            if not entries:
                embed.description = "No data available"
            else:
                lines = []
                for entry in entries:
                    rank = entry['rank']
                    medal = {1: '🥇', 2: '🥈', 3: '🥉'}.get(rank, f'#{rank}')
                    value = self._format_stat_value(entry['value'], category)
                    lines.append(f"{medal} **{entry['username']}** - {value}")

                embed.description = '\n'.join(lines)

            embed.set_footer(text=f"Lifetime stats • {data.get('period', 'all time')}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ Error: {e.message}",
                ephemeral=True
            )

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
            # Get round ID if not specified
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
                color=winner_color
            )

            if winner:
                embed.description = f"**Winner:** {winner}"

            # Red team stats
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

            # Blue team stats
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
                    value=f"**{mvp['username']}** ({mvp['score']:,.0f} points)",
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
                f"❌ Error: {e.message}",
                ephemeral=True
            )

    @app_commands.command(name='compare', description='Compare two players')
    @app_commands.describe(
        player1='First player username',
        player2='Second player username'
    )
    async def compare(
        self,
        interaction: discord.Interaction,
        player1: str,
        player2: str
    ):
        """Compare two players side by side"""
        await interaction.response.defer()

        try:
            # Fetch both players
            data1 = await self.api.get_player(player1)
            data2 = await self.api.get_player(player2)

            embed = discord.Embed(
                title=f"⚔️ {data1['username']} vs {data2['username']}",
                color=discord.Color.gold()
            )

            # Compare key stats
            stats_to_compare = [
                ('Kills', 'combat', 'kills'),
                ('Deaths', 'combat', 'deaths'),
                ('K/D', 'computed', 'kdr'),
                ('Objectives', 'objective', 'objectives_completed'),
                ('Captures', 'territory', 'regions_captured'),
                ('IP Earned', 'territory', 'ip_earned'),
            ]

            p1_lines = []
            p2_lines = []

            for label, group, key in stats_to_compare:
                if group == 'computed':
                    v1 = data1.get('computed', {}).get(key, 0)
                    v2 = data2.get('computed', {}).get(key, 0)
                else:
                    v1 = data1.get('stats', {}).get(group, {}).get(key, 0)
                    v2 = data2.get('stats', {}).get(group, {}).get(key, 0)

                # Format values
                if key == 'kdr':
                    v1_str = f"{v1:.2f}"
                    v2_str = f"{v2:.2f}"
                elif key == 'ip_earned':
                    v1_str = f"{v1:,.0f}"
                    v2_str = f"{v2:,.0f}"
                else:
                    v1_str = str(int(v1))
                    v2_str = str(int(v2))

                # Add indicator for winner
                if v1 > v2:
                    p1_lines.append(f"**{label}:** {v1_str} ✅")
                    p2_lines.append(f"**{label}:** {v2_str}")
                elif v2 > v1:
                    p1_lines.append(f"**{label}:** {v1_str}")
                    p2_lines.append(f"**{label}:** {v2_str} ✅")
                else:
                    p1_lines.append(f"**{label}:** {v1_str}")
                    p2_lines.append(f"**{label}:** {v2_str}")

            embed.add_field(
                name=data1['username'],
                value='\n'.join(p1_lines),
                inline=True
            )

            embed.add_field(
                name=data2['username'],
                value='\n'.join(p2_lines),
                inline=True
            )

            # MVP scores
            mvp1 = data1.get('computed', {}).get('mvp_score', 0)
            mvp2 = data2.get('computed', {}).get('mvp_score', 0)
            winner = data1['username'] if mvp1 > mvp2 else data2['username'] if mvp2 > mvp1 else "Tie"

            embed.set_footer(text=f"MVP Scores: {mvp1:,.0f} vs {mvp2:,.0f} • Winner: {winner}")

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(
                f"❌ Error: {e.message}",
                ephemeral=True
            )


async def setup(bot: commands.Bot):
    await bot.add_cog(StatsCog(bot))

