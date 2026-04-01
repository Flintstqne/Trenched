"""
Map screenshot cog for the Entrenched Discord bot.
Uses Playwright to take a headless-browser screenshot of the BlueMap web UI.
"""

import io
import logging
from contextlib import asynccontextmanager

import discord
from discord import app_commands
from discord.ext import commands
from datetime import datetime, timezone
from PIL import Image
from playwright.async_api import async_playwright, Error as PlaywrightError

logger = logging.getLogger('entrenched-bot.map')


@asynccontextmanager
async def _ephemeral_browser():
    """Launch a Chromium instance, yield it, then tear everything down.

    Using a fresh browser per screenshot avoids EPIPE / broken-pipe crashes
    caused by a long-lived Chromium process dying between invocations.
    """
    pw = await async_playwright().start()
    browser = None
    try:
        browser = await pw.chromium.launch(headless=True)
        yield browser
    finally:
        if browser:
            try:
                await browser.close()
            except Exception:
                pass
        try:
            await pw.stop()
        except Exception:
            pass


class MapCog(commands.Cog):
    """Commands for viewing the live BlueMap"""

    def __init__(self, bot: commands.Bot):
        self.bot = bot
        bm_cfg = bot.config.get('bluemap', {})
        self.bluemap_url = bm_cfg.get('url', 'http://localhost:8100').rstrip('/')
        self.map_id = bm_cfg.get('map_id', 'world')
        self.width = bm_cfg.get('width', 2100)
        self.height = bm_cfg.get('height', 2100)
        self.render_wait = bm_cfg.get('render_wait', 3)
        # Default camera distance to fit the full map in the viewport.
        # For BlueMap flat/ortho mode this roughly equals half the map diameter.
        self.default_distance = bm_cfg.get('default_distance', 1200)

    @app_commands.command(name='map', description='Screenshot the live BlueMap and post it here')
    async def map_screenshot(
        self,
        interaction: discord.Interaction,
    ):
        """Take a screenshot of the BlueMap and send it as an embed."""
        await interaction.response.defer()

        # Build the BlueMap URL hash.
        # Format: #<map>:<x>:<y>:<z>:<distance>:<yaw>:<pitch>:<perspective>
        # Defaults: centered on (0, 0), top-down flat view, distance sized to
        # fit the full 2048×2048 block map in the viewport.
        distance = self.default_distance
        # yaw=0 (north-up), pitch=-90 (top-down), perspective=0 (flat/ortho)
        url = f"{self.bluemap_url}/#{self.map_id}:0:100:0:{distance}:0:-90:0"

        try:
            async with _ephemeral_browser() as browser:
                page = await browser.new_page(
                    viewport={'width': self.width, 'height': self.height}
                )

                try:
                    # Use 'domcontentloaded' instead of 'networkidle' because BlueMap's
                    # WebGL renderer continuously streams map tiles, so networkidle never fires.
                    await page.goto(url, wait_until='domcontentloaded', timeout=15_000)

                    # Wait for the WebGL canvas to appear (BlueMap renders into a canvas)
                    try:
                        await page.wait_for_selector('canvas', timeout=10_000)
                    except PlaywrightError:
                        logger.warning("BlueMap canvas element not found within timeout, proceeding anyway")

                    # Give the WebGL renderer extra time to paint tiles
                    await page.wait_for_timeout(self.render_wait * 1000)

                    # Hide the BlueMap UI panels and scale up region labels for readability
                    await page.evaluate("""() => {
                        // BlueMap v3/v5 sidebar & controls
                        for (const sel of [
                            '.side-menu', '.menu-button', '.marker-menu',
                            '.control-bar', '.info-bubble', '.bluemap-container > .controls',
                            '#app > aside', '.leaflet-control-container'
                        ]) {
                            document.querySelectorAll(sel).forEach(el => el.style.display = 'none');
                        }

                        // Scale up HTML marker labels so region names are readable at
                        // the full-map zoom level.  We identify the labels by the
                        // inline styles set in RegionRenderer.java (bold + nowrap).
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

            # Strip PNG metadata — Playwright can embed the source URL in
            # tEXt chunks, which would leak the server IP to anyone who
            # inspects the image file.
            img = Image.open(io.BytesIO(screenshot_bytes))
            clean_buf = io.BytesIO()
            img.save(clean_buf, format='PNG')
            clean_buf.seek(0)

            file = discord.File(clean_buf, filename='map.png')

            embed = discord.Embed(
                title="🗺️ Live War Map",
                color=discord.Color.dark_green(),
                timestamp=datetime.now(timezone.utc),
            )
            embed.set_image(url="attachment://map.png")
            embed.set_footer(text="BlueMap screenshot")

            await interaction.followup.send(embed=embed, file=file)

        except PlaywrightError as e:
            error_msg = str(e).lower()
            logger.exception("Playwright error capturing map screenshot")

            if any(kw in error_msg for kw in ("net::err_connection_refused", "econnrefused",
                                                "net::err_connection_timed_out", "etimedout",
                                                "net::err_name_not_resolved", "enotfound",
                                                "net::err_connection_reset", "econnreset")):
                await interaction.followup.send(
                    "❌ The BlueMap web server appears to be offline or unreachable. "
                    "Please try again later or contact an admin.",
                    ephemeral=True,
                )
            elif "timeout" in error_msg:
                await interaction.followup.send(
                    "❌ The BlueMap page took too long to load. "
                    "The map server may be under heavy load — please try again later.",
                    ephemeral=True,
                )
            else:
                await interaction.followup.send(
                    "❌ Something went wrong while capturing the map screenshot. "
                    "Please try again later.",
                    ephemeral=True,
                )

        except Exception:
            logger.exception("Unexpected error capturing map screenshot")
            await interaction.followup.send(
                "❌ An unexpected error occurred while capturing the map. "
                "Please try again later.",
                ephemeral=True,
            )


async def setup(bot: commands.Bot):
    await bot.add_cog(MapCog(bot))
