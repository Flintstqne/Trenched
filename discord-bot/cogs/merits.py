"""
Merits & rank commands cog for the Entrenched Discord bot.
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from datetime import datetime, timezone

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.merits')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


# Tier display config
TIER_INFO = {
    'enlisted':  {'emoji': '🪖', 'label': 'Enlisted'},
    'nco':       {'emoji': '⚔️', 'label': 'Non-Commissioned Officers'},
    'warrant':   {'emoji': '🔰', 'label': 'Warrant Officers'},
    'officer':   {'emoji': '⭐', 'label': 'Officers'},
    'general':   {'emoji': '👑', 'label': 'Generals'},
}


class MeritsCog(commands.Cog):
    """Commands for viewing merit ranks and achievements"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)

    # ── /merits ─────────────────────────────────────────────────────────

    @app_commands.command(name='merits', description='View all merit ranks and how to earn them')
    async def merits(self, interaction: discord.Interaction):
        """Show all merit ranks grouped by tier."""
        await interaction.response.defer()

        try:
            data = await self.api.get_ranks()
            ranks = data.get('ranks', [])

            # Group ranks by tier, preserving order
            tiers: dict[str, list] = {}
            for r in ranks:
                tier = r.get('tier', 'enlisted')
                tiers.setdefault(tier, []).append(r)

            embed = discord.Embed(
                title="🎖️ Merit Ranks",
                description=(
                    "Earn merits from other players to climb the ranks.\n"
                    "Ranks are purely cosmetic — no gameplay advantages."
                ),
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            # Render each tier in order
            for tier_key in ('enlisted', 'nco', 'warrant', 'officer', 'general'):
                tier_ranks = tiers.get(tier_key)
                if not tier_ranks:
                    continue
                info = TIER_INFO.get(tier_key, {'emoji': '🎖️', 'label': tier_key.capitalize()})
                lines = []
                for r in tier_ranks:
                    req = r['merits_required']
                    req_text = f"{req:,} merits" if req > 0 else "Starting rank"
                    lines.append(f"**{r['name']}** `[{r['tag']}]` — {req_text}")
                embed.add_field(
                    name=f"{info['emoji']} {info['label']} ({len(tier_ranks)})",
                    value='\n'.join(lines),
                    inline=False,
                )

            embed.set_footer(text=f"{data.get('total', len(ranks))} ranks total  •  /achievements to see earnable tokens")
            await interaction.followup.send(embed=embed)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # Category emoji mapping
    CATEGORY_EMOJI = {
        'combat': '⚔️',
        'territory': '🏴',
        'logistics': '🔧',
        'social': '🤝',
        'progression': '📈',
        'time': '⏰',
        'round': '🏆',
    }

    @app_commands.command(name='achievements', description='View all possible achievements and their rewards')
    async def achievements(self, interaction: discord.Interaction):
        """Show a guide of every achievement, grouped by category."""
        await interaction.response.defer()

        try:
            data = await self.api.get_achievements()
            total = data.get('total', 0)
            categories = data.get('categories', {})

            embeds = []
            embed = discord.Embed(
                title=f"🏅 Achievement Guide — {total} Achievements",
                description="Complete achievements to earn merit tokens!",
                color=discord.Color.gold(),
                timestamp=_ts(),
            )

            field_count = 0
            for cat, achievements in categories.items():
                emoji = self.CATEGORY_EMOJI.get(cat, '🎖️')
                lines = []
                for a in achievements:
                    lines.append(f"**{a['name']}** — {a['description']}  (+{a['reward']}🪙)")

                value = '\n'.join(lines)
                # Discord field value limit is 1024 chars; split if needed
                if len(value) > 1024:
                    value = value[:1020] + '…'

                embed.add_field(
                    name=f"{emoji} {cat.capitalize()} ({len(achievements)})",
                    value=value,
                    inline=False,
                )
                field_count += 1

                # Discord embeds support max 25 fields; start a new embed if close
                if field_count >= 25:
                    embeds.append(embed)
                    embed = discord.Embed(
                        title="🏅 Achievement Guide (cont.)",
                        color=discord.Color.gold(),
                        timestamp=_ts(),
                    )
                    field_count = 0

            embed.set_footer(text="Earn achievements to gain merit tokens and climb the ranks!")
            embeds.append(embed)

            await interaction.followup.send(embeds=embeds)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)


async def setup(bot: commands.Bot):
    await bot.add_cog(MeritsCog(bot))



