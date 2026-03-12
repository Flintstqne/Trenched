package org.flintstqne.entrenched.StatLogic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * REST API server for stats access from external applications (e.g., Discord bot).
 */
public class StatApiServer {

    private final JavaPlugin plugin;
    private final StatService statService;
    private final ConfigManager config;
    private final Logger logger;
    private final Gson gson;

    private HttpServer server;

    // Rate limiting: API key -> list of request timestamps
    private final Map<String, LinkedList<Long>> rateLimitMap = new ConcurrentHashMap<>();

    public StatApiServer(JavaPlugin plugin, StatService statService, ConfigManager config) {
        this.plugin = plugin;
        this.statService = statService;
        this.config = config;
        this.logger = plugin.getLogger();
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();
    }

    /**
     * Starts the API server.
     */
    public void start() {
        int port = config.getStatApiPort();
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

            // Register endpoints
            server.createContext("/api/player", new PlayerHandler());
            server.createContext("/api/leaderboard", new LeaderboardHandler());
            server.createContext("/api/team", new TeamHandler());
            server.createContext("/api/round", new RoundHandler());
            server.createContext("/api/rounds", new RoundsHandler());
            server.createContext("/api/categories", new CategoriesHandler());
            server.createContext("/api/health", new HealthHandler());

            server.start();
            logger.info("[Stats API] Server started on port " + port);
        } catch (IOException e) {
            logger.severe("[Stats API] Failed to start server: " + e.getMessage());
        }
    }

    /**
     * Stops the API server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("[Stats API] Server stopped");
        }
    }

    /**
     * Checks rate limit for an API key.
     */
    private boolean checkRateLimit(String apiKey) {
        int limit = config.getStatApiRateLimit();
        long windowMs = 60_000; // 1 minute

        LinkedList<Long> timestamps = rateLimitMap.computeIfAbsent(apiKey, k -> new LinkedList<>());
        long now = System.currentTimeMillis();

        // Remove old timestamps
        while (!timestamps.isEmpty() && timestamps.peekFirst() < now - windowMs) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= limit) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }

    /**
     * Validates API key from request.
     */
    private boolean validateApiKey(HttpExchange exchange) {
        String providedKey = exchange.getRequestHeaders().getFirst("X-API-Key");
        String configKey = config.getStatApiKey();

        if (configKey == null || configKey.isEmpty() || configKey.equals("change-this-secret-key")) {
            // No key configured, allow all requests (for development)
            return true;
        }

        return configKey.equals(providedKey);
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int code, Object response) throws IOException {
        String json = gson.toJson(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", message);
        error.put("code", code);
        sendResponse(exchange, code, error);
    }

    /**
     * Extracts path parameter after the base path.
     */
    private String getPathParam(HttpExchange exchange, String basePath) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith(basePath + "/")) {
            return path.substring(basePath.length() + 1);
        }
        return null;
    }

    /**
     * Parses query parameters.
     */
    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    params.put(pair[0], pair[1]);
                }
            }
        }
        return params;
    }

    // === HANDLERS ===

    /**
     * GET /api/player/{uuid}
     * GET /api/player/{uuid}/round/{roundId}
     */
    private class PlayerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                sendError(exchange, 429, "Rate limit exceeded");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            if (parts.length < 4) {
                sendError(exchange, 400, "Missing player UUID");
                return;
            }

            String uuidStr = parts[3];
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                sendError(exchange, 400, "Invalid UUID format");
                return;
            }

            // Check if round-specific stats requested
            if (parts.length >= 6 && parts[4].equals("round")) {
                int roundId;
                try {
                    roundId = Integer.parseInt(parts[5]);
                } catch (NumberFormatException e) {
                    sendError(exchange, 400, "Invalid round ID");
                    return;
                }

                Optional<PlayerStats> stats = statService.getPlayerRoundStats(uuid, roundId);
                if (stats.isEmpty()) {
                    sendError(exchange, 404, "Player round stats not found");
                    return;
                }

                sendResponse(exchange, 200, buildPlayerResponse(stats.get(), roundId));
            } else {
                Optional<PlayerStats> stats = statService.getPlayerStats(uuid);
                if (stats.isEmpty()) {
                    sendError(exchange, 404, "Player not found");
                    return;
                }

                sendResponse(exchange, 200, buildPlayerResponse(stats.get(), null));
            }
        }

        private Map<String, Object> buildPlayerResponse(PlayerStats stats, Integer roundId) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("uuid", stats.getUuid().toString());
            response.put("username", stats.getLastKnownName());
            response.put("lastSeen", Instant.ofEpochMilli(stats.getLastLogin()).toString());

            if (roundId != null) {
                response.put("roundId", roundId);
            }

            // Stats by group
            Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
            for (StatCategory.StatGroup group : StatCategory.StatGroup.values()) {
                Map<String, Object> groupStats = new LinkedHashMap<>();
                for (StatCategory cat : StatCategory.getByGroup(group)) {
                    groupStats.put(cat.getKey(), stats.getStat(cat));
                }
                groups.put(group.name().toLowerCase(), groupStats);
            }
            response.put("stats", groups);

            // Computed stats
            Map<String, Object> computed = new LinkedHashMap<>();
            computed.put("kdr", Math.round(stats.getKDR() * 100.0) / 100.0);
            computed.put("kda", Math.round(stats.getKDA() * 100.0) / 100.0);
            computed.put("mvp_score", stats.getMVPScore());
            computed.put("win_rate", Math.round(stats.getWinRate() * 100.0) / 100.0);
            response.put("computed", computed);

            return response;
        }
    }

    /**
     * GET /api/leaderboard/{category}?limit=10
     */
    private class LeaderboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                sendError(exchange, 429, "Rate limit exceeded");
                return;
            }

            String categoryStr = getPathParam(exchange, "/api/leaderboard");
            if (categoryStr == null || categoryStr.isEmpty()) {
                sendError(exchange, 400, "Missing category");
                return;
            }

            StatCategory category = StatCategory.fromKey(categoryStr);
            if (category == null) {
                sendError(exchange, 400, "Invalid category: " + categoryStr);
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            int limit = 10;
            try {
                if (params.containsKey("limit")) {
                    limit = Math.min(100, Math.max(1, Integer.parseInt(params.get("limit"))));
                }
            } catch (NumberFormatException ignored) {}

            List<LeaderboardEntry> entries = statService.getLeaderboard(category, limit);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("category", category.getKey());
            response.put("displayName", category.getDisplayName());
            response.put("period", "lifetime");

            List<Map<String, Object>> entryList = new ArrayList<>();
            for (LeaderboardEntry entry : entries) {
                Map<String, Object> entryMap = new LinkedHashMap<>();
                entryMap.put("rank", entry.rank());
                entryMap.put("uuid", entry.uuid().toString());
                entryMap.put("username", entry.username());
                entryMap.put("value", entry.value());
                entryList.add(entryMap);
            }
            response.put("entries", entryList);

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/team/{team}
     */
    private class TeamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                sendError(exchange, 429, "Rate limit exceeded");
                return;
            }

            String team = getPathParam(exchange, "/api/team");
            if (team == null || (!team.equalsIgnoreCase("red") && !team.equalsIgnoreCase("blue"))) {
                sendError(exchange, 400, "Invalid team. Use 'red' or 'blue'");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            int roundId = -1;
            try {
                if (params.containsKey("round")) {
                    roundId = Integer.parseInt(params.get("round"));
                }
            } catch (NumberFormatException ignored) {}

            if (roundId < 0) {
                // Get current round from service
                List<Integer> rounds = statService.getAllRoundIds();
                if (rounds.isEmpty()) {
                    sendError(exchange, 404, "No rounds found");
                    return;
                }
                roundId = rounds.get(0);
            }

            TeamStats stats = statService.getTeamStats(team.toLowerCase(), roundId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("team", team.toLowerCase());
            response.put("roundId", roundId);
            response.put("playerCount", stats.playerCount());

            Map<String, Object> totals = new LinkedHashMap<>();
            Map<String, Object> averages = new LinkedHashMap<>();
            for (StatCategory cat : StatCategory.values()) {
                totals.put(cat.getKey(), stats.getTotal(cat));
                averages.put(cat.getKey(), Math.round(stats.getAverage(cat) * 100.0) / 100.0);
            }
            response.put("totals", totals);
            response.put("averages", averages);

            if (stats.mvpUuid() != null) {
                Map<String, Object> mvp = new LinkedHashMap<>();
                mvp.put("uuid", stats.mvpUuid().toString());
                mvp.put("username", stats.mvpName());
                mvp.put("score", stats.mvpScore());
                response.put("mvp", mvp);
            }

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/round/{roundId}
     */
    private class RoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                sendError(exchange, 429, "Rate limit exceeded");
                return;
            }

            String roundStr = getPathParam(exchange, "/api/round");
            if (roundStr == null || roundStr.isEmpty()) {
                sendError(exchange, 400, "Missing round ID");
                return;
            }

            int roundId;
            try {
                roundId = Integer.parseInt(roundStr);
            } catch (NumberFormatException e) {
                sendError(exchange, 400, "Invalid round ID");
                return;
            }

            Optional<RoundSummary> summaryOpt = statService.getRoundSummary(roundId);
            if (summaryOpt.isEmpty()) {
                sendError(exchange, 404, "Round not found");
                return;
            }

            RoundSummary summary = summaryOpt.get();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("roundId", summary.roundId());
            response.put("winner", summary.winner());
            response.put("durationMinutes", summary.getDurationMinutes());

            Map<String, Object> red = new LinkedHashMap<>();
            red.put("players", summary.redPlayerCount());
            red.put("kills", summary.redTotalKills());
            red.put("objectives", summary.redTotalObjectives());
            red.put("captures", summary.redTotalCaptures());
            response.put("red", red);

            Map<String, Object> blue = new LinkedHashMap<>();
            blue.put("players", summary.bluePlayerCount());
            blue.put("kills", summary.blueTotalKills());
            blue.put("objectives", summary.blueTotalObjectives());
            blue.put("captures", summary.blueTotalCaptures());
            response.put("blue", blue);

            if (summary.mvpUuid() != null) {
                Map<String, Object> mvp = new LinkedHashMap<>();
                mvp.put("uuid", summary.mvpUuid().toString());
                mvp.put("username", summary.mvpName());
                mvp.put("score", summary.mvpScore());
                response.put("mvp", mvp);
            }

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/rounds
     */
    private class RoundsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                sendError(exchange, 429, "Rate limit exceeded");
                return;
            }

            List<Integer> roundIds = statService.getAllRoundIds();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", roundIds.size());
            response.put("roundIds", roundIds);

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/categories
     */
    private class CategoriesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendError(exchange, 401, "Invalid API key");
                return;
            }

            List<Map<String, Object>> categories = new ArrayList<>();
            for (StatCategory cat : StatCategory.values()) {
                Map<String, Object> catMap = new LinkedHashMap<>();
                catMap.put("key", cat.getKey());
                catMap.put("displayName", cat.getDisplayName());
                catMap.put("group", cat.getGroup().name().toLowerCase());
                catMap.put("isCounter", cat.isCounter());
                catMap.put("isLeaderboardStat", cat.isLeaderboardStat());
                categories.add(catMap);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", categories.size());
            response.put("categories", categories);

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/health
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("timestamp", Instant.now().toString());
            response.put("version", plugin.getDescription().getVersion());

            sendResponse(exchange, 200, response);
        }
    }
}

