"""
Division commands cog for the Entrenched Discord bot.
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from datetime import datetime, timezone
from typing import Optional, List

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.divisions')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


class DivisionsCog(commands.Cog):
    """Commands for viewing divisions"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)

    # ── Autocomplete ────────────────────────────────────────────────

    async def _division_autocomplete(
        self, interaction: discord.Interaction, current: str
    ) -> List[app_commands.Choice[str]]:
        """Suggest division names/tags."""
        try:
            data = await self.api.get_divisions()
            divisions = data.get('divisions', [])
            choices = []
            for d in divisions:
                label = f"[{d['tag']}] {d['name']} ({d['team'].capitalize()})"
                if current.lower() in label.lower():
                    choices.append(app_commands.Choice(name=label, value=d['tag']))
            return choices[:25]
        except APIError:
            return []

    # ── /divisions ──────────────────────────────────────────────────

    @app_commands.command(name='divisions', description='List all active divisions')
    @app_commands.describe(team='Filter by team (optional)')
    @app_commands.choices(team=[
        app_commands.Choice(name='🔴 Red', value='red'),
        app_commands.Choice(name='🔵 Blue', value='blue'),
    ])
    async def divisions_list(
        self,
        interaction: discord.Interaction,
        team: Optional[str] = None,
    ):
        """List all active divisions."""
        await interaction.response.defer()

        try:
            data = await self.api.get_divisions(team)
            divisions = data.get('divisions', [])

            if not divisions:
                await interaction.followup.send("No divisions found.", ephemeral=True)
                return

            title = "🏴 All Divisions"
            if team:
                emoji = '🔴' if team == 'red' else '🔵'
                title = f"{emoji} {team.capitalize()} Team Divisions"

            embed = discord.Embed(
                title=title,
                color=discord.Color.dark_gold(),
                timestamp=_ts(),
            )

            red_divs = [d for d in divisions if d['team'] == 'red']
            blue_divs = [d for d in divisions if d['team'] == 'blue']

            if red_divs:
                lines = []
                for d in red_divs:
                    lines.append(
                        f"**[{d['tag']}] {d['name']}** — "
                        f"{d['member_count']} member{'s' if d['member_count'] != 1 else ''}"
                    )
                embed.add_field(
                    name=f"🔴 Red Team ({len(red_divs)})",
                    value='\n'.join(lines) or "None",
                    inline=False,
                )

            if blue_divs:
                lines = []
                for d in blue_divs:
                    lines.append(
                        f"**[{d['tag']}] {d['name']}** — "
                        f"{d['member_count']} member{'s' if d['member_count'] != 1 else ''}"
                    )
                embed.add_field(
                    name=f"🔵 Blue Team ({len(blue_divs)})",
                    value='\n'.join(lines) or "None",
                    inline=False,
                )

            embed.set_footer(text=f"{data.get('count', 0)} division(s) total")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /division <name> ────────────────────────────────────────────

    @app_commands.command(name='division', description='View division details and roster')
    @app_commands.describe(name='Division name or tag')
    @app_commands.autocomplete(name=_division_autocomplete)
    async def division_detail(
        self,
        interaction: discord.Interaction,
        name: str,
    ):
        """View a division's roster and details."""
        await interaction.response.defer()

        try:
            data = await self.api.get_division(name)

            is_red = data['team'] == 'red'
            color = discord.Color.red() if is_red else discord.Color.blue()
            team_emoji = '🔴' if is_red else '🔵'

            embed = discord.Embed(
                title=f"🏴 [{data['tag']}] {data['name']}",
                description=data.get('description') or '*No description set*',
                color=color,
                timestamp=_ts(),
            )

            embed.add_field(name="Team", value=f"{team_emoji} {data['team'].capitalize()}", inline=True)
            embed.add_field(name="Members", value=str(data['member_count']), inline=True)
            embed.add_field(name="Founded by", value=data.get('founder', '?'), inline=True)

            # Build roster grouped by role
            members = data.get('members', [])
            commanders = [m for m in members if m['role'] == 'COMMANDER']
            officers = [m for m in members if m['role'] == 'OFFICER']
            enlisted = [m for m in members if m['role'] == 'MEMBER']

            roster_lines = []
            for m in commanders:
                tag = f"[{m.get('merit_tag', 'RCT')}]" if m.get('merit_tag') else ''
                roster_lines.append(f"★ **{m['username']}** {tag} — Commander")
            for m in officers:
                tag = f"[{m.get('merit_tag', 'RCT')}]" if m.get('merit_tag') else ''
                roster_lines.append(f"• **{m['username']}** {tag} — Officer")
            for m in enlisted:
                tag = f"[{m.get('merit_tag', 'RCT')}]" if m.get('merit_tag') else ''
                roster_lines.append(f"  {m['username']} {tag}")

            if roster_lines:
                embed.add_field(
                    name="Roster",
                    value='\n'.join(roster_lines),
                    inline=False,
                )

            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)


async def setup(bot: commands.Bot):
    await bot.add_cog(DivisionsCog(bot))

