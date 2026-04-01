"""
Guide commands cog for the Entrenched Discord bot.
Provides /objectives and /guides (hub for all informational commands).
"""

import discord
from discord.ext import commands
from discord import app_commands
import logging
from datetime import datetime, timezone

logger = logging.getLogger('entrenched-bot.guides')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


# ── Objective data (mirrors ObjectiveType.java) ──────────────────────────

RAID_OBJECTIVES = [
    {
        'name': 'Destroy Supply Cache',
        'ip': 150,
        'desc': 'Find and destroy a hidden supply cache (chest with supplies).',
    },
    {
        'name': 'Assassinate Commander',
        'ip': 200,
        'desc': 'Kill the designated "commander" — the player with the highest kills in the region.',
    },
    {
        'name': 'Sabotage Defenses',
        'ip': 100,
        'desc': 'Destroy 50+ blocks of enemy walls and fortifications.',
    },
    {
        'name': 'Plant Explosive',
        'ip': 175,
        'desc': 'Place TNT at a marked location and defend it for 30 seconds.',
    },
    {
        'name': 'Capture Intel',
        'ip': 125,
        'desc': 'Retrieve an intel item from enemy base and return to friendly territory.',
    },
    {
        'name': 'Hold Ground',
        'ip': 100,
        'desc': 'Stay in the region center for 60 seconds while contested.',
    },
]

SETTLEMENT_OBJECTIVES = [
    {
        'name': 'Establish Outpost',
        'ip': 200,
        'desc': 'Build a field outpost with shelter, storage, and crafting around the anchor.',
    },
    {
        'name': 'Secure Perimeter',
        'ip': 150,
        'desc': 'Build 100+ blocks of defensive walls.',
    },
    {
        'name': 'Build Supply Route',
        'ip': 125,
        'desc': 'Place 64+ path/road blocks near a border with friendly territory.',
    },
    {
        'name': 'Build Watchtower',
        'ip': 100,
        'desc': 'Construct an observation tower with height, access, and a lookout platform.',
    },
    {
        'name': 'Establish Resource Depot',
        'ip': 150,
        'desc': 'Place 4+ storage containers with 100+ items total.',
    },
    {
        'name': 'Build Garrison Quarters',
        'ip': 175,
        'desc': 'Build an enclosed room with 3+ team beds.',
    },
]


class GuidesCog(commands.Cog):
    """Informational guide commands"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot

    # ── /objectives ──────────────────────────────────────────────────

    @app_commands.command(
        name='objectives',
        description='Guide to all objective types, rewards, and how to complete them',
    )
    async def objectives(self, interaction: discord.Interaction):
        """Show every objective type split by Raid and Settlement."""
        embed = discord.Embed(
            title="🎯 Objective Guide",
            description=(
                "Objectives spawn in regions and award **Influence Points (IP)** "
                "when completed. They come in two categories:\n"
                "• **Raid** objectives appear in **enemy** regions\n"
                "• **Settlement** objectives appear in **neutral** regions"
            ),
            color=discord.Color.orange(),
            timestamp=_ts(),
        )

        # ── Raid ──
        raid_lines = []
        for obj in RAID_OBJECTIVES:
            raid_lines.append(
                f"**{obj['name']}** — `{obj['ip']} IP`\n"
                f"↳ {obj['desc']}"
            )
        embed.add_field(
            name="⚔️ Raid Objectives (Enemy Regions)",
            value='\n'.join(raid_lines),
            inline=False,
        )

        # ── Settlement ──
        settlement_lines = []
        for obj in SETTLEMENT_OBJECTIVES:
            settlement_lines.append(
                f"**{obj['name']}** — `{obj['ip']} IP`\n"
                f"↳ {obj['desc']}"
            )
        embed.add_field(
            name="🏗️ Settlement Objectives (Neutral Regions)",
            value='\n'.join(settlement_lines),
            inline=False,
        )

        embed.add_field(
            name="💡 Tips",
            value=(
                "• Objectives spawn randomly when you enter a valid region\n"
                "• Only one objective is active per region at a time\n"
                "• Completing objectives is the fastest way to earn IP and influence the war"
            ),
            inline=False,
        )

        embed.set_footer(text="Use /guides for a list of all guide commands")
        await interaction.response.send_message(embed=embed)

    # ── /guides ──────────────────────────────────────────────────────

    @app_commands.command(
        name='guides',
        description='List all guide and reference commands',
    )
    async def guides(self, interaction: discord.Interaction):
        """Hub listing every informational / guide command."""
        embed = discord.Embed(
            title="📚 Entrenched Guides",
            description="All guide and reference commands in one place.",
            color=discord.Color.teal(),
            timestamp=_ts(),
        )

        embed.add_field(
            name="🎯 Objectives",
            value="**/objectives** — All objective types, IP rewards & how to complete them",
            inline=False,
        )
        embed.add_field(
            name="🎖️ Ranks & Merits",
            value=(
                "**/merits** — All merit ranks and how to earn them\n"
                "**/achievements** — Every earnable achievement and token reward"
            ),
            inline=False,
        )
        embed.add_field(
            name="🏴 Divisions",
            value=(
                "**/divisions** — List all active divisions\n"
                "**/division** `<name>` — View a division's details & roster"
            ),
            inline=False,
        )
        embed.add_field(
            name="📊 Stats & Leaderboards",
            value=(
                "**/profile** `<player>` — Player card with skin, rank & key stats\n"
                "**/stats** `<player>` — Full detailed statistics\n"
                "**/compare** `<p1>` `<p2>` — Compare two players\n"
                "**/leaderboard** `<category>` — Top players for any stat\n"
                "**/top** — Quick snapshot of current-round leaders"
            ),
            inline=False,
        )
        embed.add_field(
            name="⚔️ War",
            value=(
                "**/warscore** — Live war scoreboard\n"
                "**/online** — Who's playing right now\n"
                "**/map** — Screenshot of the BlueMap war map\n"
                "**/round** `[id]` — Round summary\n"
                "**/history** `<player>` — Round-by-round performance"
            ),
            inline=False,
        )
        embed.add_field(
            name="🔧 Misc",
            value=(
                "**/server** — Check server status\n"
                "**/help** — Quick command list"
            ),
            inline=False,
        )

        embed.set_footer(text="Entrenched — Minecraft War Game")
        await interaction.response.send_message(embed=embed)


async def setup(bot: commands.Bot):
    await bot.add_cog(GuidesCog(bot))

