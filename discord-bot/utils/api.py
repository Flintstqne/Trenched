"""
API client for communicating with the Entrenched stats API.
"""

import aiohttp
import logging
from typing import Optional, Dict, Any, List

logger = logging.getLogger('entrenched-bot.api')


class APIError(Exception):
    """Raised when the API returns an error"""
    def __init__(self, status: int, message: str):
        self.status = status
        self.message = message
        super().__init__(f"API Error {status}: {message}")


class StatsAPI:
    def __init__(self, base_url: str, api_key: str):
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self._session: Optional[aiohttp.ClientSession] = None

    async def _get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                headers={
                    'X-API-Key': self.api_key,
                    'Content-Type': 'application/json'
                }
            )
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def _request(self, endpoint: str, params: Optional[Dict] = None) -> Dict[str, Any]:
        """Make a GET request to the API"""
        session = await self._get_session()
        url = f"{self.base_url}{endpoint}"

        try:
            async with session.get(url, params=params) as response:
                data = await response.json()

                if response.status != 200:
                    raise APIError(
                        response.status,
                        data.get('message', 'Unknown error')
                    )

                return data
        except aiohttp.ClientError as e:
            logger.error(f"API request failed: {e}")
            raise APIError(0, f"Connection failed: {e}")

    async def health_check(self) -> Dict[str, Any]:
        """Check if the API is healthy"""
        return await self._request('/api/health')

    async def get_player(self, uuid: str) -> Dict[str, Any]:
        """Get lifetime stats for a player by UUID"""
        return await self._request(f'/api/player/{uuid}')

    async def get_player_round(self, uuid: str, round_id: int) -> Dict[str, Any]:
        """Get round-specific stats for a player"""
        return await self._request(f'/api/player/{uuid}/round/{round_id}')

    async def get_leaderboard(self, category: str, limit: int = 10) -> Dict[str, Any]:
        """Get leaderboard for a stat category"""
        return await self._request(f'/api/leaderboard/{category}', {'limit': limit})

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


# Singleton instance
_api: Optional[StatsAPI] = None


def get_api(base_url: str, api_key: str) -> StatsAPI:
    """Get or create the API client singleton"""
    global _api
    if _api is None:
        _api = StatsAPI(base_url, api_key)
    return _api

