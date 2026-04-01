"""
API client for communicating with the Entrenched stats API.
"""

import asyncio
import aiohttp
import logging
import re
import json
import time
import random
import urllib.parse
from typing import Optional, Dict, Any, List
from pathlib import Path

logger = logging.getLogger('entrenched-bot.api')

# Default timeout for API requests (seconds)
DEFAULT_TIMEOUT = 10


class APIError(Exception):
    """Raised when the API returns an error"""
    def __init__(self, status: int, message: str):
        self.status = status
        self.message = message
        super().__init__(f"API Error {status}: {message}")


class StatsAPI:
    def __init__(self, base_url: str, api_key: str, timeout: int = DEFAULT_TIMEOUT):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.timeout = aiohttp.ClientTimeout(total=timeout)
        self._session: Optional[aiohttp.ClientSession] = None
        self._player_cache: Dict[str, str] = {}  # username -> uuid (for autocomplete)

        # Persistence for the player cache
        self._cache_file = Path(__file__).parent / 'player_cache.json'
        try:
            if self._cache_file.exists():
                with self._cache_file.open('r', encoding='utf-8') as f:
                    data = json.load(f)
                    if isinstance(data, dict):
                        # keys should already be lowercase usernames
                        self._player_cache.update(data)
        except Exception:
            # Don't fail initialization for cache problems
            logger.exception("Failed to load player cache; continuing with empty cache")

    async def _save_cache(self) -> None:
        """Persist the player cache to disk asynchronously."""
        try:
            # Use to_thread to avoid blocking the event loop on file IO
            await asyncio.to_thread(self._cache_file.write_text, json.dumps(self._player_cache, ensure_ascii=False, indent=2), 'utf-8')
        except TypeError:
            # Fallback for Python versions where to_thread/write_text signature may differ
            def _write():
                with self._cache_file.open('w', encoding='utf-8') as f:
                    json.dump(self._player_cache, f, ensure_ascii=False, indent=2)
            await asyncio.to_thread(_write)
        except Exception:
            logger.exception("Failed to persist player cache")

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={
                    'X-API-Key': self.api_key,
                    'Content-Type': 'application/json'
                },
                timeout=self.timeout,
            )
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def _request(self, endpoint: str, params: Optional[Dict] = None) -> Dict[str, Any]:
        """Make a GET request to the API with retries for transient failures.

        Retries on HTTP 429 and 5xx responses, as well as on network errors and
        timeouts, using exponential backoff with jitter.
        """
        session = await self._get_session()
        url = f"{self.base_url}{endpoint}"

        max_retries = 3
        base_backoff = 0.5

        for attempt in range(1, max_retries + 1):
            try:
                async with session.get(url, params=params) as response:
                    # Attempt to parse JSON (may raise)
                    try:
                        data = await response.json()
                    except Exception:
                        text = await response.text()
                        data = {'message': text}

                    # Rate limit
                    if response.status == 429:
                        if attempt == max_retries:
                            raise APIError(429, "Rate limited — try again in a moment.")
                        sleep = base_backoff * (2 ** (attempt - 1)) + random.uniform(0, 0.1)
                        await asyncio.sleep(sleep)
                        continue

                    # Server errors - retry
                    if 500 <= response.status < 600:
                        if attempt == max_retries:
                            raise APIError(response.status, data.get('message', 'Server error'))
                        sleep = base_backoff * (2 ** (attempt - 1)) + random.uniform(0, 0.1)
                        await asyncio.sleep(sleep)
                        continue

                    # Non-OK responses
                    if response.status != 200:
                        raise APIError(
                            response.status,
                            data.get('message', 'Unknown error')
                        )

                    return data

            except asyncio.TimeoutError:
                logger.warning(f"API request timed out (attempt %s): %s", attempt, endpoint)
                if attempt == max_retries:
                    raise APIError(0, "Request timed out — the server may be down.")
                await asyncio.sleep(base_backoff * (2 ** (attempt - 1)))
                continue
            except aiohttp.ClientConnectorError as e:
                # Log the underlying connector error and the full URL for debugging
                logger.error("Cannot connect to API at %s (attempt %s): %s", self.base_url, attempt, e)
                if attempt == max_retries:
                    raise APIError(0, "Cannot connect to the game server. Is it online?")
                await asyncio.sleep(base_backoff * (2 ** (attempt - 1)))
                continue
            except aiohttp.ClientError as e:
                logger.warning(f"API request failed (attempt %s): %s", attempt, e)
                if attempt == max_retries:
                    raise APIError(0, f"Connection failed: {e}")
                await asyncio.sleep(base_backoff * (2 ** (attempt - 1)))
                continue

    async def _post_request(self, endpoint: str, json_body: Dict[str, Any]) -> Dict[str, Any]:
        """Make a POST request to the API"""
        session = await self._get_session()
        url = f"{self.base_url}{endpoint}"

        try:
            async with session.post(url, json=json_body) as response:
                data = await response.json()

                if response.status == 429:
                    raise APIError(429, "Rate limited — try again in a moment.")

                if response.status not in (200, 201):
                    raise APIError(
                        response.status,
                        data.get('message', 'Unknown error')
                    )

                return data
        except asyncio.TimeoutError:
            logger.error("API POST timed out: %s", endpoint)
            raise APIError(0, "Request timed out — the server may be down.")
        except aiohttp.ClientConnectorError as e:
            logger.error("Cannot connect to API at %s for POST %s: %s", self.base_url, endpoint, e)
            raise APIError(0, "Cannot connect to the game server. Is it online?")
        except aiohttp.ClientError as e:
            logger.error("API POST failed for %s: %s", endpoint, e)
            raise APIError(0, f"Connection failed: {e}")

    async def _delete_request(self, endpoint: str) -> Dict[str, Any]:
        """Make a DELETE request to the API"""
        session = await self._get_session()
        url = f"{self.base_url}{endpoint}"

        try:
            async with session.delete(url) as response:
                data = await response.json()

                if response.status == 429:
                    raise APIError(429, "Rate limited — try again in a moment.")

                if response.status != 200:
                    raise APIError(
                        response.status,
                        data.get('message', 'Unknown error')
                    )

                return data
        except asyncio.TimeoutError:
            raise APIError(0, "Request timed out — the server may be down.")
        except aiohttp.ClientConnectorError as e:
            logger.error("Cannot connect to API at %s for DELETE %s: %s", self.base_url, endpoint, e)
            raise APIError(0, "Cannot connect to the game server. Is it online?")
        except aiohttp.ClientError as e:
            logger.error("API DELETE failed for %s: %s", endpoint, e)
            raise APIError(0, f"Connection failed: {e}")

    async def health_check(self) -> Dict[str, Any]:
        """Check if the API is healthy"""
        return await self._request('/api/health')

    async def get_player(self, uuid: str) -> Dict[str, Any]:
        """Get lifetime stats for a player by UUID or username.

        This method is permissive: callers may pass a UUID (hyphenated or not)
        or a Minecraft username. If a username is provided the client will
        attempt to resolve it to a UUID via Mojang's API and then query the
        stat server using the (hyphenated) UUID. Resolved usernames are cached
        for quicker autocomplete/resolution later.
        """
        identifier = uuid

        # If the identifier looks like a username and we have it cached, use
        # the cached UUID immediately.
        if not self._looks_like_uuid(identifier):
            cached = self._player_cache.get(identifier.lower())
            if cached:
                identifier = cached

        # If identifier now looks like a UUID, normalize and call the API.
        if self._looks_like_uuid(identifier):
            norm = self._normalize_uuid(identifier)
            data = await self._request(f'/api/player/{norm}')
            # Cache username -> uuid for autocomplete
            if 'username' in data and 'uuid' in data:
                self._player_cache[data['username'].lower()] = data['uuid']
                try:
                    asyncio.create_task(self._save_cache())
                except Exception:
                    # best-effort persistence; ignore failures here
                    pass
            return data

        # Otherwise treat identifier as a username: resolve via Mojang API.
        username = identifier
        session = await self._get_session()
        mojang_url = f'https://api.mojang.com/users/profiles/minecraft/{username}'
        try:
            async with session.get(mojang_url) as resp:
                if resp.status == 200:
                    mj = await resp.json()
                    raw_id = mj.get('id')  # 32 hex chars without hyphens
                    if not raw_id:
                        raise APIError(404, "Player not found")
                    uuid_hyphen = self._hyphenate_uuid(raw_id)
                    # Cache username -> uuid
                    self._player_cache[username.lower()] = uuid_hyphen
                    data = await self._request(f'/api/player/{uuid_hyphen}')
                    if 'username' in data and 'uuid' in data:
                        self._player_cache[data['username'].lower()] = data['uuid']
                    return data
                elif resp.status == 204 or resp.status == 404:
                    raise APIError(404, "Player not found")
                else:
                    text = await resp.text()
                    raise APIError(resp.status, f"Mojang API error: {text}")
        except asyncio.TimeoutError:
            raise APIError(0, "Username resolution timed out — the Mojang API may be down.")
        except aiohttp.ClientError as e:
            raise APIError(0, f"Failed to resolve username: {e}")

    async def get_player_raw(self, identifier: str) -> Dict[str, Any]:
        """Directly query /api/player/{identifier} without any local normalization

        This is useful when callers deliberately want to send the raw path
        segment (for example to attempt a username-based handler on the server).
        The identifier will be URL-encoded.
        """
        encoded = urllib.parse.quote(identifier, safe='')
        return await self._request(f'/api/player/{encoded}')

    def _looks_like_uuid(self, s: str) -> bool:
        """Return True if the string looks like a UUID (hyphenated or 32 hex)."""
        if not s:
            return False
        s = s.strip()
        # hyphenated form
        if re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", s):
            return True
        # 32 hex chars without hyphens
        if re.fullmatch(r"[0-9a-fA-F]{32}", s):
            return True
        return False

    def _normalize_uuid(self, s: str) -> str:
        """Return a hyphenated lowercase UUID string from various input forms."""
        s = s.strip().lower()
        if '-' in s:
            return s
        return self._hyphenate_uuid(s)

    def _hyphenate_uuid(self, raw: str) -> str:
        """Convert 32-char hex UUID to hyphenated form.

        If input is already hyphenated it is returned unchanged.
        """
        r = raw.replace('-', '').lower()
        if len(r) != 32:
            return raw
        return f"{r[0:8]}-{r[8:12]}-{r[12:16]}-{r[16:20]}-{r[20:32]}"

    async def get_player_round(self, uuid: str, round_id: int) -> Dict[str, Any]:
        """Get round-specific stats for a player"""
        return await self._request(f'/api/player/{uuid}/round/{round_id}')

    async def get_leaderboard(self, category: str, limit: int = 10) -> Dict[str, Any]:
        """Get leaderboard for a stat category"""
        data = await self._request(f'/api/leaderboard/{category}', {'limit': limit})
        # Cache all player names from leaderboard results
        for entry in data.get('entries', []):
            if 'username' in entry and 'uuid' in entry:
                self._player_cache[entry['username'].lower()] = entry['uuid']
        try:
            asyncio.create_task(self._save_cache())
        except Exception:
            pass
        return data

    async def get_team(self, team: str, round_id: Optional[int] = None) -> Dict[str, Any]:
        """Get team stats"""
        params = {'round': round_id} if round_id else None
        return await self._request(f'/api/team/{team}', params)

    async def get_round(self, round_id: int) -> Dict[str, Any]:
        """Get round summary"""
        return await self._request(f'/api/round/{round_id}')

    async def get_rounds(self) -> Dict[str, Any]:
        """Get list of all round IDs"""
        return await self._request('/api/rounds')

    async def get_categories(self) -> Dict[str, Any]:
        """Get list of all stat categories"""
        return await self._request('/api/categories')

    async def get_merits(self, uuid: str) -> Dict[str, Any]:
        """Get merit data for a player by UUID or username"""
        return await self._request(f'/api/merits/{uuid}')

    async def get_divisions(self, team: Optional[str] = None) -> Dict[str, Any]:
        """Get list of all divisions, optionally filtered by team"""
        params = {'team': team} if team else None
        return await self._request('/api/divisions', params)

    async def get_division(self, name_or_tag: str) -> Dict[str, Any]:
        """Get division detail by name or tag"""
        import urllib.parse
        encoded = urllib.parse.quote(name_or_tag, safe='')
        return await self._request(f'/api/division/{encoded}')

    async def get_regions(self) -> Dict[str, Any]:
        """Get all region statuses"""
        return await self._request('/api/regions')

    async def get_achievements(self) -> Dict[str, Any]:
        """Get all possible achievements (guide data, no player needed)"""
        return await self._request('/api/achievements')

    async def get_ranks(self) -> Dict[str, Any]:
        """Get all merit ranks with requirements and tiers"""
        return await self._request('/api/ranks')

    async def get_online(self) -> Dict[str, Any]:
        """Get all currently online players with team/rank/division info"""
        return await self._request('/api/online')

    # ── Discord linking ─────────────────────────────────────────────

    async def verify_link(self, code: str, discord_id: str) -> Dict[str, Any]:
        """Verify a link code and create the Discord ↔ MC link.
        Routes through /api/health to bypass proxy restrictions.
        """
        session = await self._get_session()
        url = f"{self.base_url}/api/health"
        params = {'action': 'verify', 'code': code, 'discord_id': discord_id}

        try:
            async with session.get(url, params=params) as response:
                raw = await response.text()
                logger.info(f"[LinkVerify] status={response.status} body={raw[:300]}")

                if response.status in (200, 400):
                    import json
                    return json.loads(raw)
                if response.status == 429:
                    raise APIError(429, "Rate limited — try again in a moment.")
                try:
                    import json
                    data = json.loads(raw)
                    msg = data.get('message', 'Unknown error')
                except Exception:
                    msg = f"Server returned HTTP {response.status}"
                raise APIError(response.status, msg)
        except APIError:
            raise
        except asyncio.TimeoutError:
            raise APIError(0, "Request timed out — the server may be down.")
        except aiohttp.ClientConnectorError as e:
            logger.error("Cannot connect to the game server for link verification %s: %s", url, e)
            raise APIError(0, "Cannot connect to the game server. Is it online?")
        except aiohttp.ClientError as e:
            logger.error("Link verification request failed for %s: %s", url, e)
            raise APIError(0, f"Connection failed: {e}")

    async def get_linked(self, discord_id: str) -> Dict[str, Any]:
        """Get linked account info for a Discord user"""
        return await self._request('/api/health', params={'action': 'lookup', 'discord_id': discord_id})

    async def unlink(self, discord_id: str) -> Dict[str, Any]:
        """Remove a Discord ↔ MC link"""
        return await self._request('/api/health', params={'action': 'unlink', 'discord_id': discord_id})

    def known_players(self) -> List[str]:
        """Return list of known player usernames (from cache)."""
        return list(self._player_cache.keys())

    async def get_uuid(self, identifier: str) -> str:
        """Resolve an identifier (username or UUID) to a canonical hyphenated UUID.

        - If identifier already looks like a UUID it will be normalized and returned.
        - If a cached mapping exists it will be returned.
        - Otherwise the Mojang API will be queried (with retries/backoff) and the
          resulting UUID cached and persisted.
        Raises APIError if the player cannot be resolved.
        """
        if not identifier:
            raise APIError(404, "Player not found")

        ident = identifier.strip()
        # If it already looks like a UUID, normalize and return
        if self._looks_like_uuid(ident):
            return self._normalize_uuid(ident)

        # Check cache
        cached = self._player_cache.get(ident.lower())
        if cached:
            return self._normalize_uuid(cached)

        # Query Mojang API with exponential backoff
        session = await self._get_session()
        mojang_url = f'https://api.mojang.com/users/profiles/minecraft/{urllib.parse.quote(ident)}'
        max_retries = 4
        backoff_base = 0.5
        for attempt in range(1, max_retries + 1):
            try:
                async with session.get(mojang_url) as resp:
                    if resp.status == 200:
                        mj = await resp.json()
                        raw_id = mj.get('id')
                        if not raw_id:
                            raise APIError(404, "Player not found")
                        uuid_hyphen = self._hyphenate_uuid(raw_id)
                        # Cache and persist
                        self._player_cache[ident.lower()] = uuid_hyphen
                        # Fire-and-forget persist (don't block caller too long)
                        try:
                            asyncio.create_task(self._save_cache())
                        except Exception:
                            # If create_task fails, attempt to persist synchronously
                            try:
                                with self._cache_file.open('w', encoding='utf-8') as f:
                                    json.dump(self._player_cache, f, ensure_ascii=False, indent=2)
                            except Exception:
                                logger.exception("Failed to persist player cache (sync fallback)")
                        return uuid_hyphen
                    elif resp.status in (204, 404):
                        raise APIError(404, "Player not found")
                    elif 500 <= resp.status < 600 or resp.status == 429:
                        if attempt == max_retries:
                            text = await resp.text()
                            raise APIError(resp.status, f"Mojang API error: {text}")
                        sleep = backoff_base * (2 ** (attempt - 1)) + random.uniform(0, 0.1)
                        await asyncio.sleep(sleep)
                        continue
                    else:
                        text = await resp.text()
                        raise APIError(resp.status, f"Mojang API error: {text}")
            except asyncio.TimeoutError:
                if attempt == max_retries:
                    raise APIError(0, "Username resolution timed out — the Mojang API may be down.")
                await asyncio.sleep(backoff_base * (2 ** (attempt - 1)))
                continue
            except aiohttp.ClientError as e:
                if attempt == max_retries:
                    raise APIError(0, f"Failed to resolve username: {e}")
                await asyncio.sleep(backoff_base * (2 ** (attempt - 1)))
                continue


# Singleton instance
_api: Optional[StatsAPI] = None


def get_api(base_url: str, api_key: str) -> StatsAPI:
    """Get or create the API client singleton"""
    global _api
    if _api is None:
        _api = StatsAPI(base_url, api_key)
    return _api

