#!/usr/bin/env python3
"""
Entrenched Discord Bot
Provides player statistics, leaderboards, and round information from the game server.
"""

import asyncio
import io
import logging
import sys
import discord
from discord.ext import commands, tasks
from discord import app_commands
from datetime import datetime, time, timezone
import yaml
import os

from utils.api import get_api, APIError

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('entrenched-bot')

# ── Suppress harmless Windows ProactorEventLoop socket-shutdown noise ──
if sys.platform == 'win32':
    _orig_call_connection_lost = asyncio.proactor_events._ProactorBasePipeTransport._call_connection_lost

    def _silent_connection_lost(self, exc):
        try:
            _orig_call_connection_lost(self, exc)
        except (ConnectionResetError, OSError):
            pass

    asyncio.proactor_events._ProactorBasePipeTransport._call_connection_lost = _silent_connection_lost


def load_config():
    """Load configuration from config.yaml"""
    config_path = os.path.join(os.path.dirname(__file__), 'config.yaml')

    if not os.path.exists(config_path):
        logger.error("config.yaml not found! Copy config.example.yaml to config.yaml and fill in your values.")
        raise FileNotFoundError("config.yaml not found")

    with open(config_path, 'r') as f:
        return yaml.safe_load(f)


class EntrenchedBot(commands.Bot):
    def __init__(self, config: dict):
        intents = discord.Intents.default()
        intents.members = True  # Required for on_member_join and role sync

        super().__init__(
            command_prefix='!',
            intents=intents,
            description='Entrenched Game Statistics Bot'
        )

        self.config = config
        self.api_url = config['api']['url']
        self.api_key = config['api']['key']
        self._status_index = 0

    async def setup_hook(self):
        """Called when the bot starts up"""
        # Load cogs
        await self.load_extension('cogs.stats')
        logger.info("Loaded stats cog")

        await self.load_extension('cogs.map')
        logger.info("Loaded map cog")

        await self.load_extension('cogs.merits')
        logger.info("Loaded merits cog")

        await self.load_extension('cogs.divisions')
        logger.info("Loaded divisions cog")

        await self.load_extension('cogs.info')
        logger.info("Loaded info cog")

        await self.load_extension('cogs.guides')
        logger.info("Loaded guides cog")

        await self.load_extension('cogs.linking')
        logger.info("Loaded linking cog")

        await self.load_extension('cogs.spotlight')
        logger.info("Loaded spotlight cog")

        # Sync slash commands
        guild_id = self.config.get('discord', {}).get('guild_id')
        if guild_id:
            guild = discord.Object(id=guild_id)
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)
            logger.info(f"Synced commands to guild {guild_id}")
        else:
            await self.tree.sync()
            logger.info("Synced commands globally")

    async def on_ready(self):
        logger.info(f'Logged in as {self.user} (ID: {self.user.id})')
        logger.info(f'Connected to {len(self.guilds)} guilds')

        # Start the rotating status
        if not self.rotate_status.is_running():
            self.rotate_status.start()

        # Start scheduled daily map post
        map_channel = self.config.get('discord', {}).get('map_channel_id')
        if map_channel and not self.daily_map_post.is_running():
            self.daily_map_post.start()
            logger.info(f"Scheduled daily map post to channel {map_channel}")

    @tasks.loop(seconds=30)
    async def rotate_status(self):
        """Cycle the bot's presence through live game info."""
        api = get_api(self.api_url, self.api_key)

        status_generators = [
            self._status_players,
            self._status_round,
            self._status_regions,
            self._status_teams,
        ]

        # Try each generator starting from current index; skip on API failure
        for _ in range(len(status_generators)):
            idx = self._status_index % len(status_generators)
            self._status_index += 1
            try:
                text = await status_generators[idx](api)
                if text:
                    await self.change_presence(
                        activity=discord.Activity(
                            type=discord.ActivityType.watching,
                            name=text,
                        )
                    )
                    return
            except (APIError, Exception):
                continue

        # Fallback if all generators fail
        await self.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.watching,
                name="the battlefield",
            )
        )

    @rotate_status.before_loop
    async def _before_rotate(self):
        await self.wait_until_ready()

    @staticmethod
    async def _status_players(api) -> str:
        data = await api.health_check()
        online = data.get('onlinePlayers', 0)
        max_p = data.get('maxPlayers', 0)
        return f"👥 {online}/{max_p} players online"

    @staticmethod
    async def _status_round(api) -> str:
        rounds_data = await api.get_rounds()
        round_ids = rounds_data.get('roundIds', [])
        if not round_ids:
            return None
        rid = round_ids[0]
        rd = await api.get_round(rid)
        winner = rd.get('winner')
        if winner:
            return f"⚔ Round {rid} — Winner: {winner}"
        return f"⚔ Round {rid} in progress"

    @staticmethod
    async def _status_regions(api) -> str:
        data = await api.get_regions()
        red = data.get('red_owned', 0)
        blue = data.get('blue_owned', 0)
        contested = data.get('contested', 0)
        text = f"🔴 {red} regions vs 🔵 {blue} regions"
        if contested:
            text += f" • {contested} contested"
        return text

    @staticmethod
    async def _status_teams(api) -> str:
        red = await api.get_team('red')
        blue = await api.get_team('blue')
        red_k = int(red.get('totals', {}).get('kills', 0))
        blue_k = int(blue.get('totals', {}).get('kills', 0))
        return f"🔴 {red_k:,} kills vs 🔵 {blue_k:,} kills"

    # ── Daily map screenshot ────────────────────────────────────────────

    @tasks.loop(time=time(hour=0, minute=0, tzinfo=timezone.utc))
    async def daily_map_post(self):
        """Post a map screenshot to the configured channel at midnight UTC."""
        channel_id = self.config.get('discord', {}).get('map_channel_id')
        if not channel_id:
            return

        channel = self.get_channel(int(channel_id))
        if not channel:
            logger.warning(f"Daily map: channel {channel_id} not found")
            return

        # Use the MapCog's screenshot logic
        map_cog = self.cogs.get('MapCog')
        if not map_cog:
            logger.warning("Daily map: MapCog not loaded")
            return

        try:
            from cogs.map import _ephemeral_browser
            from PIL import Image

            bm_cfg = self.config.get('bluemap', {})
            bluemap_url = bm_cfg.get('url', 'http://localhost:8100').rstrip('/')
            map_id = bm_cfg.get('map_id', 'world')
            width = bm_cfg.get('width', 2100)
            height = bm_cfg.get('height', 2100)
            render_wait = bm_cfg.get('render_wait', 3)
            default_distance = bm_cfg.get('default_distance', 1200)

            url = f"{bluemap_url}/#{map_id}:0:100:0:{default_distance}:0:-90:0"

            async with _ephemeral_browser() as browser:
                page = await browser.new_page(
                    viewport={'width': width, 'height': height}
                )
                try:
                    await page.goto(url, wait_until='domcontentloaded', timeout=15_000)
                    try:
                        await page.wait_for_selector('canvas', timeout=10_000)
                    except Exception:
                        pass
                    await page.wait_for_timeout(render_wait * 1000)

                    await page.evaluate("""() => {
                        for (const sel of [
                            '.side-menu', '.menu-button', '.marker-menu',
                            '.control-bar', '.info-bubble', '.bluemap-container > .controls',
                            '#app > aside', '.leaflet-control-container'
                        ]) {
                            document.querySelectorAll(sel).forEach(el => el.style.display = 'none');
                        }
                        document.querySelectorAll('div').forEach(el => {
                            if (el.style.fontWeight === 'bold' && el.style.whiteSpace === 'nowrap') {
                                el.style.fontSize = '48px';
                                el.style.textShadow = '0 0 12px #000, 0 0 24px #000';
                            }
                        });
                    }""")

                    screenshot_bytes = await page.screenshot(type='png')
                finally:
                    await page.close()

            # Strip PNG metadata
            img = Image.open(io.BytesIO(screenshot_bytes))
            clean_buf = io.BytesIO()
            img.save(clean_buf, format='PNG')
            clean_buf.seek(0)

            file = discord.File(clean_buf, filename='map.png')

            # Build embed with war score summary
            api = get_api(self.api_url, self.api_key)
            try:
                regions = await api.get_regions()
                red_r = regions.get('red_owned', 0)
                blue_r = regions.get('blue_owned', 0)
                contested = regions.get('contested', 0)
                desc = f"🔴 **{red_r}** regions  •  🔵 **{blue_r}** regions"
                if contested:
                    desc += f"  •  ⚡ {contested} contested"
            except (APIError, Exception):
                desc = ""

            embed = discord.Embed(
                title="🗺️ Daily War Map",
                description=desc,
                color=discord.Color.dark_green(),
                timestamp=datetime.now(timezone.utc),
            )
            embed.set_image(url="attachment://map.png")
            embed.set_footer(text="Posted daily at midnight UTC")

            await channel.send(embed=embed, file=file)
            logger.info("Daily map screenshot posted successfully")

        except Exception:
            logger.exception("Failed to post daily map screenshot")

    @daily_map_post.before_loop
    async def _before_daily_map(self):
        await self.wait_until_ready()

    async def close(self):
        """Clean up resources before shutting down."""
        if self.rotate_status.is_running():
            self.rotate_status.cancel()
        if self.daily_map_post.is_running():
            self.daily_map_post.cancel()
        api = get_api(self.api_url, self.api_key)
        await api.close()
        logger.info("Closed API session")
        await super().close()


async def main():
    config = load_config()

    bot = EntrenchedBot(config)

    async with bot:
        token = config['discord']['token']
        if not token or token == 'YOUR_DISCORD_BOT_TOKEN':
            logger.error("Please set your Discord bot token in config.yaml")
            return

        await bot.start(token)


if __name__ == '__main__':
    asyncio.run(main())
