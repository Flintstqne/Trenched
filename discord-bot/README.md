# Entrenched Discord Bot

A Discord bot for displaying player statistics from the Entrenched Minecraft server.

## Features

- `/stats <username>` — Full player stat card (combat, territory, objectives, building, participation)
- `/leaderboard <category>` — Top-player leaderboards across 15 stat categories
- `/round [id]` — Round summary with team breakdowns and MVP
- `/rounds` — List all recorded rounds at a glance
- `/compare <player1> <player2>` — Side-by-side player comparison with win/loss tally
- `/team <red|blue> [round]` — Team aggregate statistics
- `/server` — Check if the game server API is online

## Setup

### Prerequisites

- Python 3.10 or higher
- A Discord bot token
- The Entrenched Minecraft server with stats API enabled

### Installation

1. **Install dependencies:**
   ```bash
   pip install -r requirements.txt
   ```

2. **Create configuration:**
   ```bash
   cp config.example.yaml config.yaml
   ```

3. **Edit `config.yaml`:**
   - Add your Discord bot token from [Discord Developer Portal](https://discord.com/developers/applications)
   - Set the API URL to your Minecraft server's stats API
   - Set the API key (from your server's `config.yml` under `stats-api.key`)

4. **Run the bot:**
   ```bash
   python bot.py
   ```

### Server Configuration

Make sure your Minecraft server has the stats API enabled in `config.yml`:

```yaml
stats-api:
  enabled: true
  port: 8080
  key: "your-secret-key-here"  # Use the same key in your bot config
  rate-limit: 60
```

### Creating a Discord Bot

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Click "New Application" and give it a name
3. Go to "Bot" section and click "Add Bot"
4. Copy the token and add it to your `config.yaml`
5. Go to "OAuth2" > "URL Generator"
6. Select scopes: `bot`, `applications.commands`
7. Select permissions: `Send Messages`, `Embed Links`, `Use Slash Commands`
8. Use the generated URL to invite the bot to your server

## Development

### Project Structure

```
discord-bot/
├── bot.py              # Main entry point
├── config.yaml         # Your configuration (not in git)
├── config.example.yaml # Example configuration
├── requirements.txt    # Python dependencies
├── cogs/
│   ├── __init__.py
│   └── stats.py        # Stats commands
└── utils/
    ├── __init__.py
    └── api.py          # API client
```

### Adding New Commands

1. Create a new cog file in `cogs/`
2. Register it in `bot.py`'s `setup_hook` method

## API Endpoints

The bot uses these API endpoints:

| Endpoint | Description |
|----------|-------------|
| `GET /api/player/{uuid}` | Player lifetime stats |
| `GET /api/player/{uuid}/round/{roundId}` | Player round stats |
| `GET /api/leaderboard/{category}?limit=10` | Leaderboard |
| `GET /api/team/{team}` | Team stats |
| `GET /api/round/{roundId}` | Round summary |
| `GET /api/rounds` | List all round IDs |
| `GET /api/categories` | List stat categories |
| `GET /api/health` | Health check |

## Troubleshooting

### Bot doesn't respond to commands

- Check that the bot has proper permissions in the channel
- Wait a few minutes for slash commands to sync (or use guild_id for faster sync)
- Check the console for errors

### API connection errors

- Verify the API URL is correct and accessible
- Ensure the API key matches your server config
- Check if the stats API is enabled on the server

### Rate limiting

The API has a default rate limit of 60 requests per minute. If you're hitting limits, consider caching responses or reducing command usage.
