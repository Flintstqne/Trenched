#!/usr/bin/env python3
"""
Entrenched Discord Bot
Provides player statistics, leaderboards, and round information from the game server.
"""

import asyncio
import logging
import discord
from discord.ext import commands
from discord import app_commands
import yaml
import os

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('entrenched-bot')


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
        intents.message_content = True

        super().__init__(
            command_prefix='!',
            intents=intents,
            description='Entrenched Game Statistics Bot'
        )

        self.config = config
        self.api_url = config['api']['url']
        self.api_key = config['api']['key']

    async def setup_hook(self):
        """Called when the bot starts up"""
        # Load cogs
        await self.load_extension('cogs.stats')
        logger.info("Loaded stats cog")

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

        # Set presence
        await self.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.watching,
                name="the battlefield"
            )
        )


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

