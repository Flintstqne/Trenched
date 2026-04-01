"""
Discord account linking cog for the Entrenched Discord bot.
Provides /link, /unlink, /whois commands and auto-role assignment on member join.
"""

import discord
from discord.ext import commands, tasks
from discord import app_commands
import logging
from datetime import datetime, timezone

from utils.api import get_api, APIError

logger = logging.getLogger('entrenched-bot.linking')


def _ts() -> datetime:
    return datetime.now(timezone.utc)


class LinkingCog(commands.Cog):
    """Discord ↔ Minecraft account linking and auto-role assignment"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        self.api = get_api(bot.api_url, bot.api_key)
        self.role_config = bot.config.get('roles', {})

    # ── /link <code> ────────────────────────────────────────────────

    @app_commands.command(name='link', description='Link your Discord account to your Minecraft account')
    @app_commands.describe(code='The 6-character code from /link in-game')
    async def link(self, interaction: discord.Interaction, code: str):
        """Verify a link code and create the account link."""
        await interaction.response.defer(ephemeral=True)

        try:
            logger.info(f"[Link] User {interaction.user.id} verifying code '{code.strip().upper()}'")
            data = await self.api.verify_link(code.strip().upper(), str(interaction.user.id))
            logger.info(f"[Link] verify_link returned: {data}")
            result = data.get('result', '')

            if result == 'OK':
                mc_name = data.get('mc_username', 'Unknown')
                embed = discord.Embed(
                    title="✅ Account Linked!",
                    description=(
                        f"Your Discord account is now linked to **{mc_name}**.\n\n"
                        f"Your roles will be assigned automatically."
                    ),
                    color=discord.Color.green(),
                    timestamp=_ts(),
                )
                await interaction.followup.send(embed=embed, ephemeral=True)

                # Assign roles immediately after linking
                await self._sync_member_roles(interaction.user)
            else:
                message = data.get('message', 'Link failed — unknown error.')
                await interaction.followup.send(f"❌ {message}", ephemeral=True)

        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /unlink ─────────────────────────────────────────────────────

    @app_commands.command(name='unlink', description='Remove the link between your Discord and Minecraft accounts')
    async def unlink(self, interaction: discord.Interaction):
        """Remove the Discord ↔ MC link and strip game roles."""
        await interaction.response.defer(ephemeral=True)

        try:
            data = await self.api.unlink(str(interaction.user.id))
            if data.get('unlinked'):
                # Remove all game roles
                await self._strip_game_roles(interaction.user)
                await interaction.followup.send(
                    "✅ Your account has been unlinked and game roles removed.",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(
                    "❌ Your account is not currently linked.",
                    ephemeral=True,
                )
        except APIError as e:
            await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── /whois ──────────────────────────────────────────────────────

    @app_commands.command(name='whois', description='Check who a Discord member is linked to in-game')
    @app_commands.describe(member='The Discord member to look up')
    async def whois(self, interaction: discord.Interaction, member: discord.Member):
        """Look up a member's linked Minecraft account."""
        await interaction.response.defer()

        try:
            data = await self.api.get_linked(str(member.id))
            mc_name = data.get('mc_username', 'Unknown')
            team = data.get('team')
            rank = data.get('rank', 'Recruit')
            rank_tag = data.get('rank_tag', 'RCT')
            division = data.get('division')
            div_tag = data.get('division_tag')

            team_emoji = {'red': '🔴', 'blue': '🔵'}.get(team.lower(), '⬜') if team else '⬜'

            embed = discord.Embed(
                title=f"🔗 {member.display_name}",
                color=discord.Color.dark_teal(),
                timestamp=_ts(),
            )

            skin_url = f"https://mc-heads.net/avatar/{data.get('mc_username', data.get('mc_uuid', ''))}/50"
            embed.set_thumbnail(url=skin_url)

            embed.add_field(name="Minecraft", value=f"**{mc_name}**", inline=True)
            embed.add_field(name="Team", value=f"{team_emoji} {team.capitalize() if team else 'None'}", inline=True)
            embed.add_field(name="Rank", value=f"[{rank_tag}] {rank}", inline=True)

            if division:
                embed.add_field(name="Division", value=f"[{div_tag}] {division}" if div_tag else division, inline=True)

            await interaction.followup.send(embed=embed)

        except APIError as e:
            if e.status == 404:
                await interaction.followup.send(
                    f"❌ **{member.display_name}** hasn't linked their account yet.",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(f"❌ {e.message}", ephemeral=True)

    # ── Auto-role on member join ────────────────────────────────────

    @commands.Cog.listener()
    async def on_member_join(self, member: discord.Member):
        """Assign the auto-join role to every new member, then sync linked roles if applicable."""
        if member.bot:
            return

        # 1) Auto-join role — given to EVERYONE, no linking required
        auto_join_id = self.role_config.get('auto_join')
        if auto_join_id:
            role = member.guild.get_role(int(auto_join_id))
            if role and role not in member.roles:
                try:
                    await member.add_roles(role, reason="Entrenched: auto-join role")
                except Exception:
                    logger.exception(f"Failed to assign auto-join role to {member.id}")

        # 2) Linked-account roles — only if they have a linked MC account
        try:
            await self._sync_member_roles(member)
        except Exception:
            logger.exception(f"Failed to sync linked roles for joining member {member.id}")

    # ── Periodic role sync (every 5 minutes) ────────────────────────

    @tasks.loop(minutes=5)
    async def role_sync(self):
        """Periodically sync roles for all linked members in the guild."""
        guild_id = self.bot.config.get('discord', {}).get('guild_id')
        if not guild_id:
            return

        guild = self.bot.get_guild(int(guild_id))
        if not guild:
            return

        # Only sync if role config exists
        if not self._has_role_config():
            return

        synced = 0
        for member in guild.members:
            if member.bot:
                continue
            try:
                changed = await self._sync_member_roles(member)
                if changed:
                    synced += 1
            except Exception:
                continue

        if synced > 0:
            logger.info(f"Role sync: updated {synced} member(s)")

    @role_sync.before_loop
    async def _before_role_sync(self):
        await self.bot.wait_until_ready()

    def cog_load(self):
        if self._has_role_config():
            self.role_sync.start()

    def cog_unload(self):
        if self.role_sync.is_running():
            self.role_sync.cancel()

    # ── Role sync logic ─────────────────────────────────────────────

    def _has_role_config(self) -> bool:
        """Check if any role mappings are configured."""
        rc = self.role_config
        return bool(
            rc.get('linked')
            or rc.get('team_red')
            or rc.get('team_blue')
        )

    async def _sync_member_roles(self, member: discord.Member) -> bool:
        """Sync a single member's roles based on their linked account. Returns True if roles changed."""
        rc = self.role_config
        if not rc:
            return False

        guild = member.guild

        # Resolve configured role IDs
        linked_role_id = rc.get('linked')
        red_role_id = rc.get('team_red')
        blue_role_id = rc.get('team_blue')

        linked_role = guild.get_role(int(linked_role_id)) if linked_role_id else None
        red_role = guild.get_role(int(red_role_id)) if red_role_id else None
        blue_role = guild.get_role(int(blue_role_id)) if blue_role_id else None

        # All possible game roles we manage
        managed_roles = {r for r in (linked_role, red_role, blue_role) if r is not None}
        if not managed_roles:
            return False

        # Try to look up the linked account
        try:
            data = await self.api.get_linked(str(member.id))
        except APIError as e:
            if e.status == 404:
                # Not linked — strip all game roles
                to_remove = managed_roles & set(member.roles)
                if to_remove:
                    await member.remove_roles(*to_remove, reason="Entrenched: not linked")
                    return True
                return False
            else:
                # API error — fail closed, don't change roles
                return False

        # Determine which roles the member SHOULD have
        desired: set[discord.Role] = set()

        if linked_role:
            desired.add(linked_role)

        team = data.get('team')
        if team:
            if team.lower() == 'red' and red_role:
                desired.add(red_role)
            elif team.lower() == 'blue' and blue_role:
                desired.add(blue_role)

        # Compute diff
        current_managed = managed_roles & set(member.roles)
        to_add = desired - current_managed
        to_remove = current_managed - desired

        changed = False
        if to_remove:
            await member.remove_roles(*to_remove, reason="Entrenched: role sync")
            changed = True
        if to_add:
            await member.add_roles(*to_add, reason="Entrenched: role sync")
            changed = True

        return changed

    async def _strip_game_roles(self, member: discord.Member):
        """Remove all game-managed roles from a member."""
        rc = self.role_config
        if not rc:
            return

        guild = member.guild
        role_ids = [rc.get('linked'), rc.get('team_red'), rc.get('team_blue')]
        roles = [guild.get_role(int(rid)) for rid in role_ids if rid]
        roles = [r for r in roles if r is not None]

        to_remove = [r for r in roles if r in member.roles]
        if to_remove:
            await member.remove_roles(*to_remove, reason="Entrenched: unlinked")


async def setup(bot: commands.Bot):
    await bot.add_cog(LinkingCog(bot))

