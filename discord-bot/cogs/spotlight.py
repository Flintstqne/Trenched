"""
Spotlight cog for the Entrenched Discord bot.
Provides a daily auto-posted "Player of the Day" and a manual /spotlight command.
"""

import discord
from discord.ext import commands, tasks
from discord import app_commands
import logging
import random
from datetime import datetime, time, timezone

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.spotlight')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


def _bar(current: int, total: int, length: int = 10) -> str:
    if total == 0:
        return '░' * length
    filled = round(current / total * length)
    return '▓' * filled + '░' * (length - filled)


# Titles randomly picked for flair
SPOTLIGHT_TITLES = [
    "🌟 Player of the Day",
    "⭐ Today's MVP",
    "🔥 Soldier Spotlight",
    "💎 Featured Warrior",
    "🏅 Daily Highlight",
]

# Categories to score from (key, weight, display)
SCORING_CATEGORIES = [
    ('kills',               0.30, '⚔️ Kills'),
    ('objectives_completed', 0.25, '🎯 Objectives'),
    ('regions_captured',    0.20, '🏴 Captures'),
    ('rounds_won',          0.15, '🏆 Rounds Won'),
    ('buildings_constructed', 0.10, '🔨 Buildings'),
]


class SpotlightCog(commands.Cog):
    """Daily Player of the Day spotlight"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)
        self._last_spotlight: dict | None = None  # cached for /spotlight command

    # ── Core logic: pick the spotlight player ───────────────────────────

    async def _pick_spotlight(self) -> dict | None:
        """
        Pick the Player of the Day by scoring the #1 player across
        multiple leaderboard categories. Returns a dict with the player's
        full profile data + the reason they were picked, or None.
        """
        # Collect top players from each category
        candidate_scores: dict[str, float] = {}  # uuid -> weighted score
        candidate_names: dict[str, str] = {}      # uuid -> username
        category_leaders: dict[str, tuple] = {}    # category label -> (username, value)

        for cat_key, weight, cat_label in SCORING_CATEGORIES:
            try:
                data = await self.api.get_leaderboard(cat_key, 5)
                entries = data.get('entries', [])
                if not entries:
                    continue

                top_value = entries[0]['value'] if entries[0]['value'] > 0 else 1

                for entry in entries:
                    uuid = entry['uuid']
                    candidate_names[uuid] = entry['username']
                    # Normalise: ratio vs the #1 player, then multiply by weight
                    normalised = (entry['value'] / top_value) * weight
                    candidate_scores[uuid] = candidate_scores.get(uuid, 0) + normalised

                category_leaders[cat_label] = (entries[0]['username'], entries[0]['value'])
            except (APIError, Exception):
                continue

        if not candidate_scores:
            return None

        # Pick the player with the highest composite score
        best_uuid = max(candidate_scores, key=candidate_scores.get)
        best_name = candidate_names[best_uuid]

        # Fetch their full stats + merits
        try:
            player_data = await self.api.get_player(best_name)
        except APIError:
            player_data = None

        try:
            merit_data = await self.api.get_merits(best_uuid)
        except APIError:
            merit_data = None

        return {
            'uuid': best_uuid,
            'username': best_name,
            'score': candidate_scores[best_uuid],
            'player': player_data,
            'merits': merit_data,
            'leaders': category_leaders,
        }

    # ── Build the spotlight embed ───────────────────────────────────────

    def _build_embed(self, spotlight: dict) -> discord.Embed:
        """Build a rich embed for the spotlight player."""
        username = spotlight['username']
        uuid = spotlight['uuid']
        player = spotlight.get('player') or {}
        merits = spotlight.get('merits') or {}
        leaders = spotlight.get('leaders', {})

        # Team color
        team = player.get('team', '')
        team_emoji = {'red': '🔴', 'blue': '🔵'}.get(team.lower(), '⬜') if team else '⬜'
        color = {'red': discord.Color.red(), 'blue': discord.Color.blue()}.get(
            team.lower(), discord.Color.gold()
        ) if team else discord.Color.gold()

        title = random.choice(SPOTLIGHT_TITLES)

        embed = discord.Embed(
            title=title,
            description=f"{team_emoji} **{username}** has earned today's spotlight!",
            color=color,
            timestamp=_ts(),
        )

        # Use mc-heads.net — returns a default Steve skin gracefully for unknown players
        # instead of a hard 404, so Discord never shows a broken image icon.
        skin_url = f"https://mc-heads.net/body/{username}/100"
        avatar_url = f"https://mc-heads.net/avatar/{username}/50"
        embed.set_thumbnail(url=skin_url)
        embed.set_author(name=username, icon_url=avatar_url)

        # Rank & division
        merit_info = player.get('merit', {})
        rank_tag = merit_info.get('rank_tag', '')
        rank_name = merit_info.get('rank_name', '')
        rank_label = f"[{rank_tag}] {rank_name}" if rank_tag else "Recruit"

        division = player.get('division', {})
        div_tag = division.get('tag', '')
        div_name = division.get('name', '')
        div_label = f"[{div_tag}] {div_name}" if div_tag else "—"

        embed.add_field(name="🎖️ Rank", value=rank_label, inline=True)
        embed.add_field(name="🏴 Division", value=div_label, inline=True)
        embed.add_field(name="🏅 Team", value=f"{team_emoji} {team.capitalize()}" if team else "—", inline=True)

        # Key stats
        combat = player.get('stats', {}).get('combat', {})
        territory = player.get('stats', {}).get('territory', {})
        objective = player.get('stats', {}).get('objective', {})
        participation = player.get('stats', {}).get('participation', {})
        computed = player.get('computed', {})

        kills = int(combat.get('kills', 0))
        deaths = int(combat.get('deaths', 0))
        assists = int(combat.get('assists', 0))
        kdr = computed.get('kdr', 0)
        captures = int(territory.get('regions_captured', 0))
        objectives_done = int(objective.get('objectives_completed', 0))
        rounds_won = int(participation.get('rounds_won', 0))
        rounds_played = int(participation.get('rounds_played', 0))
        win_rate = computed.get('win_rate', 0)
        mvp_score = computed.get('mvp_score', 0)

        embed.add_field(
            name="⚔️ Combat",
            value=(
                f"**{kills}** / **{deaths}** / **{assists}** (K/D/A)\n"
                f"K/D: **{kdr:.2f}**"
            ),
            inline=True,
        )
        embed.add_field(
            name="🏴 Territory",
            value=f"**{captures}** captures\n**{objectives_done}** objectives",
            inline=True,
        )
        embed.add_field(
            name="📊 Record",
            value=f"**{rounds_won}**W / **{rounds_played}**P ({win_rate:.0f}%)",
            inline=True,
        )

        # Achievement progress (if available)
        if merits:
            unlocked = merits.get('achievements_unlocked', 0)
            total = merits.get('achievements_total', 0)
            pct = (unlocked / total * 100) if total > 0 else 0
            tokens = merits.get('token_balance', 0)
            streak = merits.get('login_streak', 0)
            embed.add_field(
                name="🏅 Progression",
                value=(
                    f"Achievements: {unlocked}/{total} ({pct:.0f}%)\n"
                    f"Tokens: {tokens:,} 🪙  •  Streak: {streak} 🔥"
                ),
                inline=False,
            )

        # Category leaders (shows where this player leads)
        if leaders:
            leader_lines = []
            for cat_label, (name, value) in leaders.items():
                is_spotlight = name == username
                marker = " ⬅️" if is_spotlight else ""
                leader_lines.append(f"{cat_label}: **{name}** ({int(value):,}){marker}")
            embed.add_field(
                name="📈 Leaderboard Snapshot",
                value='\n'.join(leader_lines),
                inline=False,
            )

        embed.set_footer(text=f"MVP Score: {mvp_score:,.0f}  •  /profile {username} for full card")

        return embed

    # ── /spotlight (manual) ─────────────────────────────────────────────

    @app_commands.command(name='spotlight', description='See today\'s Player of the Day')
    async def spotlight(self, interaction: discord.Interaction):
        """Show the current Player of the Day."""
        await interaction.response.defer()

        # Use cached if from today, otherwise compute fresh
        now = datetime.now(timezone.utc)
        if (
            self._last_spotlight
            and self._last_spotlight.get('date') == now.strftime('%Y-%m-%d')
        ):
            embed = self._build_embed(self._last_spotlight)
            await interaction.followup.send(embed=embed)
            return

        spotlight = await self._pick_spotlight()
        if spotlight is None:
            await interaction.followup.send(
                "❌ Not enough data to pick a Player of the Day yet.",
                ephemeral=True,
            )
            return

        spotlight['date'] = now.strftime('%Y-%m-%d')
        self._last_spotlight = spotlight

        embed = self._build_embed(spotlight)
        await interaction.followup.send(embed=embed)

    # ── Scheduled daily spotlight (12:00 UTC) ───────────────────────────

    @tasks.loop(time=time(hour=12, minute=0, tzinfo=timezone.utc))
    async def daily_spotlight(self):
        """Auto-post the Player of the Day at noon UTC."""
        channel_id = self.bot.config.get('discord', {}).get('spotlight_channel_id')
        if not channel_id:
            return

        channel = self.bot.get_channel(int(channel_id))
        if not channel:
            logger.warning(f"Spotlight: channel {channel_id} not found")
            return

        try:
            spotlight = await self._pick_spotlight()
            if spotlight is None:
                logger.info("Spotlight: not enough data to pick a player")
                return

            now = datetime.now(timezone.utc)
            spotlight['date'] = now.strftime('%Y-%m-%d')
            self._last_spotlight = spotlight

            embed = self._build_embed(spotlight)
            await channel.send(embed=embed)
            logger.info(f"Spotlight: posted {spotlight['username']} as Player of the Day")

        except Exception:
            logger.exception("Failed to post daily spotlight")

    @daily_spotlight.before_loop
    async def _before_daily_spotlight(self):
        await self.bot.wait_until_ready()

    def cog_load(self):
        channel_id = self.bot.config.get('discord', {}).get('spotlight_channel_id')
        if channel_id:
            self.daily_spotlight.start()
            logger.info(f"Scheduled daily spotlight to channel {channel_id}")

    def cog_unload(self):
        if self.daily_spotlight.is_running():
            self.daily_spotlight.cancel()


async def setup(bot: commands.Bot):
    await bot.add_cog(SpotlightCog(bot))

