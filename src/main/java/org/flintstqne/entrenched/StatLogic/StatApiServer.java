package org.flintstqne.entrenched.StatLogic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.*;
import org.flintstqne.entrenched.LinkLogic.LinkService;
import org.flintstqne.entrenched.MeritLogic.*;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.RegionLogic.RegionStatus;
import org.flintstqne.entrenched.TeamLogic.TeamService;

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
    private final MeritService meritService;
    private final DivisionService divisionService;
    private final RegionService regionService;
    private final TeamService teamService;
    private LinkService linkService;

    private HttpServer server;

    // Rate limiting: API key -> list of request timestamps
    private final Map<String, LinkedList<Long>> rateLimitMap = new ConcurrentHashMap<>();
    private long lastRateLimitPrune = System.currentTimeMillis();
    private static final long PRUNE_INTERVAL_MS = 300_000; // 5 minutes

    public StatApiServer(JavaPlugin plugin, StatService statService, ConfigManager config,
                         MeritService meritService, DivisionService divisionService,
                         RegionService regionService, TeamService teamService) {
        this.plugin = plugin;
        this.statService = statService;
        this.config = config;
        this.meritService = meritService;
        this.divisionService = divisionService;
        this.regionService = regionService;
        this.teamService = teamService;
        this.logger = plugin.getLogger();
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();
    }

    /**
     * Sets the link service (optional, wired after construction).
     */
    public void setLinkService(LinkService linkService) {
        this.linkService = linkService;
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
            server.createContext("/api/merits", new MeritsHandler());
            server.createContext("/api/divisions", new DivisionsHandler());
            server.createContext("/api/division", new DivisionHandler());
            server.createContext("/api/regions", new RegionsHandler());
            server.createContext("/api/achievements", new AchievementsHandler());
            server.createContext("/api/ranks", new RanksHandler());
            server.createContext("/api/online", new OnlineHandler());
            server.createContext("/api/linked", new LinkedLookupHandler());

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

        // Remove old timestamps for this key.
        // Use a single null-safe assignment instead of the two-step isEmpty()+peekFirst()
        // pattern: peekFirst() returns null when the list is empty (or when another thread
        // has concurrently drained it), and auto-unboxing null to long throws NPE.
        Long head;
        while ((head = timestamps.peekFirst()) != null && head < now - windowMs) {
            timestamps.pollFirst();
        }

        // Periodically prune empty entries to prevent memory leak
        if (now - lastRateLimitPrune > PRUNE_INTERVAL_MS) {
            lastRateLimitPrune = now;
            rateLimitMap.entrySet().removeIf(e -> e.getValue().isEmpty());
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
                    // Before returning 404, check whether this is a known player who simply
                    // has no recorded stats yet (e.g. joined but no active round at the time).
                    // If so, return an all-zero profile with hasStats=false so the Discord bot
                    // can display a "no stats yet" card rather than a confusing error.
                    org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                    boolean known = offlinePlayer.hasPlayedBefore()
                            || (linkService != null && linkService.getDiscordIdForMc(uuid).isPresent());
                    if (known) {
                        // Resolution priority:
                        // 1. Username stored in our link DB (captured when player was online — reliable
                        //    even after restarts on offline-mode servers where Bukkit cache can be null)
                        // 2. Bukkit's offline player cache (usercache.json, works for online-mode)
                        // 3. Raw UUID string (last resort — will cause skin services to show a broken image)
                        String knownName = (linkService != null)
                                ? linkService.getMcUsername(uuid)
                                        .orElseGet(() -> {
                                            String n = offlinePlayer.getName();
                                            return n != null ? n : uuid.toString();
                                        })
                                : (offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString());
                        sendResponse(exchange, 200, buildEmptyPlayerResponse(uuid, knownName));
                    } else {
                        sendError(exchange, 404, "Player not found");
                    }
                    return;
                }

                sendResponse(exchange, 200, buildPlayerResponse(stats.get(), null));
            }
        }

        /** Returns a zeroed player profile for a known player who has no stats yet. */
        private Map<String, Object> buildEmptyPlayerResponse(UUID uuid, String username) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("uuid", uuid.toString());
            response.put("username", username);
            response.put("hasStats", false);
            response.put("lastSeen", null);

            // Team (real data even if no stats yet)
            if (teamService != null) {
                Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
                response.put("team", teamOpt.orElse(null));
            }

            // All stat groups initialised to zero
            Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
            for (StatCategory.StatGroup group : StatCategory.StatGroup.values()) {
                Map<String, Object> groupStats = new LinkedHashMap<>();
                for (StatCategory cat : StatCategory.getByGroup(group)) {
                    groupStats.put(cat.getKey(), 0.0);
                }
                groups.put(group.name().toLowerCase(), groupStats);
            }
            response.put("stats", groups);

            // Computed stats (all zero)
            Map<String, Object> computed = new LinkedHashMap<>();
            computed.put("kdr", 0.0);
            computed.put("kda", 0.0);
            computed.put("mvp_score", 0.0);
            computed.put("win_rate", 0.0);
            response.put("computed", computed);

            // Merit rank (real data if available, otherwise default Recruit)
            if (meritService != null) {
                Optional<PlayerMeritData> meritOpt = meritService.getPlayerData(uuid);
                Map<String, Object> merit = new LinkedHashMap<>();
                if (meritOpt.isPresent()) {
                    PlayerMeritData data = meritOpt.get();
                    MeritRank rank = data.getRank();
                    merit.put("rank_name", rank.getDisplayName());
                    merit.put("rank_tag", rank.getTag());
                    merit.put("merits", data.receivedMerits());
                    merit.put("token_balance", data.tokenBalance());
                    MeritRank nextRank = rank.getNextRank();
                    if (nextRank != null) {
                        merit.put("next_rank", nextRank.getDisplayName());
                        merit.put("next_rank_tag", nextRank.getTag());
                        merit.put("merits_to_next", rank.getMeritsToNextRank(data.receivedMerits()));
                        merit.put("next_rank_required", nextRank.getMeritsRequired());
                    }
                } else {
                    merit.put("rank_name", MeritRank.RECRUIT.getDisplayName());
                    merit.put("rank_tag", MeritRank.RECRUIT.getTag());
                    merit.put("merits", 0);
                    merit.put("token_balance", 0);
                }
                response.put("merit", merit);
            }

            // Division (real data if available)
            if (divisionService != null) {
                Optional<Division> divOpt = divisionService.getPlayerDivision(uuid);
                Map<String, Object> division = new LinkedHashMap<>();
                if (divOpt.isPresent()) {
                    division.put("name", divOpt.get().name());
                    division.put("tag", divOpt.get().tag());
                }
                response.put("division", division);
            }

            return response;
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

            // Team
            if (teamService != null) {
                Optional<String> teamOpt = teamService.getPlayerTeam(stats.getUuid());
                response.put("team", teamOpt.orElse(null));
            }

            // Division
            if (divisionService != null) {
                Optional<Division> divOpt = divisionService.getPlayerDivision(stats.getUuid());
                Map<String, Object> division = new LinkedHashMap<>();
                if (divOpt.isPresent()) {
                    division.put("name", divOpt.get().name());
                    division.put("tag", divOpt.get().tag());
                }
                response.put("division", division);
            }

            // Merit rank info
            if (meritService != null) {
                Optional<PlayerMeritData> meritOpt = meritService.getPlayerData(stats.getUuid());
                Map<String, Object> merit = new LinkedHashMap<>();
                if (meritOpt.isPresent()) {
                    PlayerMeritData data = meritOpt.get();
                    MeritRank rank = data.getRank();
                    merit.put("rank_name", rank.getDisplayName());
                    merit.put("rank_tag", rank.getTag());
                    merit.put("merits", data.receivedMerits());
                    merit.put("token_balance", data.tokenBalance());
                    MeritRank nextRank = rank.getNextRank();
                    if (nextRank != null) {
                        merit.put("next_rank", nextRank.getDisplayName());
                        merit.put("next_rank_tag", nextRank.getTag());
                        merit.put("merits_to_next", rank.getMeritsToNextRank(data.receivedMerits()));
                        merit.put("next_rank_required", nextRank.getMeritsRequired());
                    }
                } else {
                    merit.put("rank_name", MeritRank.RECRUIT.getDisplayName());
                    merit.put("rank_tag", MeritRank.RECRUIT.getTag());
                    merit.put("merits", 0);
                    merit.put("token_balance", 0);
                }
                response.put("merit", merit);
            }

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
                // Include merit rank tag
                if (meritService != null) {
                    Optional<PlayerMeritData> md = meritService.getPlayerData(entry.uuid());
                    if (md.isPresent()) {
                        MeritRank mr = md.get().getRank();
                        entryMap.put("merit_rank", mr.getDisplayName());
                        entryMap.put("merit_tag", mr.getTag());
                    } else {
                        entryMap.put("merit_rank", MeritRank.RECRUIT.getDisplayName());
                        entryMap.put("merit_tag", MeritRank.RECRUIT.getTag());
                    }
                }
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
     * GET /api/health                                                      — server health
     * GET /api/health?action=verify&code=ABC123&discord_id=123             — verify link code
     * GET /api/health?action=lookup&discord_id=123                         — look up linked account
     * GET /api/health?action=unlink&discord_id=123                         — unlink account
     */
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!validateApiKey(exchange)) {
                    sendError(exchange, 401, "Invalid API key");
                    return;
                }

                String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
                if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) {
                    sendError(exchange, 429, "Rate limit exceeded");
                    return;
                }

                Map<String, String> params = parseQueryParams(exchange);
                String action = params.get("action");

                // ── Link actions ──
                if (action != null) {
                    if (linkService == null) { sendError(exchange, 503, "Link system not available"); return; }

                    switch (action.toLowerCase()) {
                        case "verify" -> { handleLinkVerify(exchange, params); return; }
                        case "lookup" -> { handleLinkLookup(exchange, params); return; }
                        case "unlink" -> { handleLinkUnlink(exchange, params); return; }
                    }
                }

                // ── Default: health check ──
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "ok");
                response.put("timestamp", Instant.now().toString());
                response.put("version", plugin.getDescription().getVersion());
                response.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
                response.put("maxPlayers", plugin.getServer().getMaxPlayers());

                sendResponse(exchange, 200, response);
            } catch (Exception e) {
                logger.severe("[Stats API] Health handler error: " + e.getMessage());
                e.printStackTrace();
                try { sendError(exchange, 500, "Internal server error"); } catch (Exception ignored) {}
            }
        }

        private void handleLinkVerify(HttpExchange exchange, Map<String, String> params) throws IOException {
            String code = params.get("code");
            String discordId = params.get("discord_id");

            if (code == null || code.isEmpty() || discordId == null || discordId.isEmpty()) {
                sendError(exchange, 400, "Missing 'code' or 'discord_id' query parameters");
                return;
            }

            LinkService.VerifyResult result = linkService.verify(code.trim(), discordId.trim());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result.name());

            if (result == LinkService.VerifyResult.OK) {
                linkService.getMcUuid(discordId.trim()).ifPresent(uuidStr -> {
                    response.put("mc_uuid", uuidStr);
                    UUID uuid = UUID.fromString(uuidStr);

                    // Prefer the online player — they just ran /link in-game so they're connected.
                    // Fall back to the offline cache, then to the raw UUID string.
                    org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
                    String name = (online != null)
                            ? online.getName()
                            : Bukkit.getOfflinePlayer(uuid).getName();
                    if (name == null) name = uuidStr;

                    response.put("mc_username", name);

                    // Persist the resolved name — Bukkit's offline cache can return null on
                    // offline-mode servers after a restart, so we store it ourselves.
                    linkService.updateMcUsername(uuid, name);
                });
                sendResponse(exchange, 200, response);
            } else {
                String message = switch (result) {
                    case INVALID_CODE -> "Invalid or expired link code.";
                    case EXPIRED -> "Code has expired. Generate a new one with /link in-game.";
                    case DISCORD_ALREADY_LINKED -> "This Discord account is already linked. Use /unlink first.";
                    case MC_ALREADY_LINKED -> "This Minecraft account is already linked to another Discord account.";
                    case DB_ERROR -> "Database error — please try again.";
                    default -> "Unknown error.";
                };
                response.put("message", message);
                sendResponse(exchange, 400, response);
            }
        }

        private void handleLinkLookup(HttpExchange exchange, Map<String, String> params) throws IOException {
            String discordId = params.get("discord_id");
            if (discordId == null || discordId.isEmpty()) {
                sendError(exchange, 400, "Missing 'discord_id' parameter");
                return;
            }

            Optional<String> mcUuidOpt = linkService.getMcUuid(discordId);
            if (mcUuidOpt.isEmpty()) {
                sendError(exchange, 404, "No linked account found for this Discord ID");
                return;
            }

            String mcUuid = mcUuidOpt.get();
            UUID uuid = UUID.fromString(mcUuid);

            // Use the username stored in our link DB first; that was captured when the
            // player was online and is reliable across server restarts on offline-mode servers.
            // Fall back to Bukkit's offline cache, then to the UUID string.
            String playerName = linkService.getMcUsername(uuid)
                    .orElseGet(() -> {
                        String n = Bukkit.getOfflinePlayer(uuid).getName();
                        return n != null ? n : mcUuid;
                    });

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("discord_id", discordId);
            response.put("mc_uuid", mcUuid);
            response.put("mc_username", playerName);

            if (teamService != null) {
                Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
                response.put("team", teamOpt.orElse(null));
            }
            if (meritService != null) {
                Optional<PlayerMeritData> md = meritService.getPlayerData(uuid);
                if (md.isPresent()) {
                    MeritRank rank = md.get().getRank();
                    response.put("rank", rank.getDisplayName());
                    response.put("rank_tag", rank.getTag());
                } else {
                    response.put("rank", MeritRank.RECRUIT.getDisplayName());
                    response.put("rank_tag", MeritRank.RECRUIT.getTag());
                }
            }
            if (divisionService != null) {
                Optional<Division> divOpt = divisionService.getPlayerDivision(uuid);
                if (divOpt.isPresent()) {
                    response.put("division", divOpt.get().name());
                    response.put("division_tag", divOpt.get().tag());
                }
            }

            sendResponse(exchange, 200, response);
        }

        private void handleLinkUnlink(HttpExchange exchange, Map<String, String> params) throws IOException {
            String discordId = params.get("discord_id");
            if (discordId == null || discordId.isEmpty()) {
                sendError(exchange, 400, "Missing 'discord_id' parameter");
                return;
            }

            boolean removed = linkService.unlinkByDiscord(discordId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("unlinked", removed);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/merits/{uuid}
     */
    private class MeritsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            String uuidStr = getPathParam(exchange, "/api/merits");
            if (uuidStr == null || uuidStr.isEmpty()) { sendError(exchange, 400, "Missing player UUID"); return; }

            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); }
            catch (IllegalArgumentException e) { sendError(exchange, 400, "Invalid UUID format"); return; }

            if (meritService == null) { sendError(exchange, 503, "Merit system not available"); return; }

            Optional<PlayerMeritData> dataOpt = meritService.getPlayerData(uuid);
            if (dataOpt.isEmpty()) { sendError(exchange, 404, "Player merit data not found"); return; }

            PlayerMeritData data = dataOpt.get();
            MeritRank rank = data.getRank();
            MeritRank nextRank = rank.getNextRank();

            String playerName = Bukkit.getOfflinePlayer(uuid).getName();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("uuid", uuid.toString());
            response.put("username", playerName != null ? playerName : uuid.toString());
            response.put("rank", rank.getDisplayName());
            response.put("rank_tag", rank.getTag());
            response.put("received_merits", data.receivedMerits());
            response.put("token_balance", data.tokenBalance());
            response.put("lifetime_tokens_earned", data.lifetimeTokensEarned());
            response.put("lifetime_merits_given", data.lifetimeMeritsGiven());
            response.put("lifetime_merits_received", data.lifetimeMeritsReceived());
            response.put("login_streak", data.loginStreak());
            response.put("rounds_completed", data.roundsCompleted());
            response.put("playtime_minutes", data.playtimeMinutes());

            if (nextRank != null) {
                Map<String, Object> next = new LinkedHashMap<>();
                next.put("name", nextRank.getDisplayName());
                next.put("tag", nextRank.getTag());
                next.put("required", nextRank.getMeritsRequired());
                next.put("remaining", rank.getMeritsToNextRank(data.receivedMerits()));
                response.put("next_rank", next);
            }

            // Achievements
            Set<Achievement> unlocked = meritService.getUnlockedAchievements(uuid);
            List<Map<String, Object>> achievementList = new ArrayList<>();
            for (Achievement a : Achievement.values()) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", a.name());
                am.put("name", a.getDisplayName());
                am.put("description", a.getDescription());
                am.put("category", a.getCategory());
                am.put("reward", a.getTokenReward());
                am.put("unlocked", unlocked.contains(a));
                achievementList.add(am);
            }
            response.put("achievements_unlocked", unlocked.size());
            response.put("achievements_total", Achievement.values().length);
            response.put("achievements", achievementList);

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/divisions?team=red|blue  (optional filter)
     */
    private class DivisionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            if (divisionService == null) { sendError(exchange, 503, "Division system not available"); return; }

            Map<String, String> params = parseQueryParams(exchange);
            String teamFilter = params.get("team");

            List<Division> allDivisions = new ArrayList<>();
            if (teamFilter != null && (teamFilter.equalsIgnoreCase("red") || teamFilter.equalsIgnoreCase("blue"))) {
                allDivisions.addAll(divisionService.getDivisionsForTeam(teamFilter.toLowerCase()));
            } else {
                allDivisions.addAll(divisionService.getDivisionsForTeam("red"));
                allDivisions.addAll(divisionService.getDivisionsForTeam("blue"));
            }

            List<Map<String, Object>> divList = new ArrayList<>();
            for (Division div : allDivisions) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("id", div.divisionId());
                dm.put("name", div.name());
                dm.put("tag", div.tag());
                dm.put("team", div.team());
                dm.put("description", div.description());
                dm.put("member_count", divisionService.getMembers(div.divisionId()).size());
                divList.add(dm);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", divList.size());
            response.put("divisions", divList);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/division/{nameOrTag}
     */
    private class DivisionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            if (divisionService == null) { sendError(exchange, 503, "Division system not available"); return; }

            String nameOrTag = getPathParam(exchange, "/api/division");
            if (nameOrTag == null || nameOrTag.isEmpty()) { sendError(exchange, 400, "Missing division name or tag"); return; }

            // URL-decode
            nameOrTag = java.net.URLDecoder.decode(nameOrTag, StandardCharsets.UTF_8);

            // Search by ID, name match, or tag match
            Division found = null;

            // Try as numeric ID first
            try {
                int id = Integer.parseInt(nameOrTag);
                Optional<Division> opt = divisionService.getDivision(id);
                if (opt.isPresent()) found = opt.get();
            } catch (NumberFormatException ignored) {}

            // Search across both teams by name or tag
            if (found == null) {
                for (String team : List.of("red", "blue")) {
                    for (Division div : divisionService.getDivisionsForTeam(team)) {
                        if (div.name().equalsIgnoreCase(nameOrTag) || div.tag().equalsIgnoreCase(nameOrTag)) {
                            found = div;
                            break;
                        }
                    }
                    if (found != null) break;
                }
            }

            if (found == null) { sendError(exchange, 404, "Division not found"); return; }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", found.divisionId());
            response.put("name", found.name());
            response.put("tag", found.tag());
            response.put("team", found.team());
            response.put("description", found.description());
            response.put("created_at", Instant.ofEpochMilli(found.createdAt()).toString());

            // Founder name
            String founderName = Bukkit.getOfflinePlayer(UUID.fromString(found.founderUuid())).getName();
            response.put("founder", founderName != null ? founderName : found.founderUuid());

            // Members with roles and merit ranks
            List<DivisionMember> members = divisionService.getMembers(found.divisionId());
            List<Map<String, Object>> memberList = new ArrayList<>();
            for (DivisionMember member : members) {
                Map<String, Object> mm = new LinkedHashMap<>();
                UUID memberUuid = UUID.fromString(member.playerUuid());
                String memberName = Bukkit.getOfflinePlayer(memberUuid).getName();
                mm.put("uuid", member.playerUuid());
                mm.put("username", memberName != null ? memberName : member.playerUuid());
                mm.put("role", member.role().name());
                mm.put("role_symbol", member.role().getSymbol());

                if (meritService != null) {
                    Optional<PlayerMeritData> md = meritService.getPlayerData(memberUuid);
                    if (md.isPresent()) {
                        mm.put("merit_rank", md.get().getRank().getDisplayName());
                        mm.put("merit_tag", md.get().getRank().getTag());
                    } else {
                        mm.put("merit_rank", MeritRank.RECRUIT.getDisplayName());
                        mm.put("merit_tag", MeritRank.RECRUIT.getTag());
                    }
                }
                memberList.add(mm);
            }
            response.put("member_count", memberList.size());
            response.put("members", memberList);

            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/regions
     */
    private class RegionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            if (regionService == null) { sendError(exchange, 503, "Region system not available"); return; }

            List<RegionStatus> statuses = regionService.getAllRegionStatuses();

            int redCount = 0, blueCount = 0, neutralCount = 0, contestedCount = 0;
            List<Map<String, Object>> regionList = new ArrayList<>();
            for (RegionStatus s : statuses) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("id", s.regionId());
                rm.put("owner", s.ownerTeam());
                rm.put("state", s.state().name());
                rm.put("red_influence", s.redInfluence());
                rm.put("blue_influence", s.blueInfluence());
                rm.put("times_captured", s.timesCaptured());
                rm.put("fortified", s.isFortified());
                regionList.add(rm);

                if (s.ownerTeam() != null && s.ownerTeam().equalsIgnoreCase("red")) redCount++;
                else if (s.ownerTeam() != null && s.ownerTeam().equalsIgnoreCase("blue")) blueCount++;
                else neutralCount++;
                if (s.state().name().equals("CONTESTED")) contestedCount++;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", regionList.size());
            response.put("red_owned", redCount);
            response.put("blue_owned", blueCount);
            response.put("neutral", neutralCount);
            response.put("contested", contestedCount);
            response.put("regions", regionList);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/achievements
     * Returns all possible achievements grouped by category (no player required).
     */
    private class AchievementsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            // Group achievements by category
            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            for (Achievement a : Achievement.values()) {
                String cat = a.getCategory();
                grouped.computeIfAbsent(cat, k -> new ArrayList<>());
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", a.name());
                am.put("name", a.getDisplayName());
                am.put("description", a.getDescription());
                am.put("reward", a.getTokenReward());
                grouped.get(cat).add(am);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total", Achievement.values().length);
            response.put("categories", grouped);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/ranks
     * Returns all merit ranks with their requirements, grouped by tier.
     */
    private class RanksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            List<Map<String, Object>> rankList = new ArrayList<>();
            for (MeritRank rank : MeritRank.values()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", rank.getDisplayName());
                rm.put("tag", rank.getTag());
                rm.put("merits_required", rank.getMeritsRequired());

                // Determine tier
                String tier;
                if (rank.isGeneral()) tier = "general";
                else if (rank.isOfficer()) tier = "officer";
                else if (rank.isWarrantOfficer()) tier = "warrant";
                else if (rank.isNCO()) tier = "nco";
                else tier = "enlisted";

                rm.put("tier", tier);
                rankList.add(rm);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total", rankList.size());
            response.put("ranks", rankList);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * GET /api/online
     * Returns all currently online players with team, rank, and division info.
     */
    private class OnlineHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

            List<Map<String, Object>> playerList = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uuid = p.getUniqueId();
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("uuid", uuid.toString());
                pm.put("username", p.getName());

                // Team
                if (teamService != null) {
                    Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
                    pm.put("team", teamOpt.orElse(null));
                }

                // Merit rank
                if (meritService != null) {
                    Optional<PlayerMeritData> md = meritService.getPlayerData(uuid);
                    if (md.isPresent()) {
                        MeritRank rank = md.get().getRank();
                        pm.put("rank", rank.getDisplayName());
                        pm.put("rank_tag", rank.getTag());
                    } else {
                        pm.put("rank", MeritRank.RECRUIT.getDisplayName());
                        pm.put("rank_tag", MeritRank.RECRUIT.getTag());
                    }
                }

                // Division
                if (divisionService != null) {
                    Optional<Division> divOpt = divisionService.getPlayerDivision(uuid);
                    if (divOpt.isPresent()) {
                        pm.put("division", divOpt.get().name());
                        pm.put("division_tag", divOpt.get().tag());
                    }
                }

                playerList.add(pm);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", playerList.size());
            response.put("max", Bukkit.getMaxPlayers());
            response.put("players", playerList);
            sendResponse(exchange, 200, response);
        }
    }

    /**
     * Combined handler for all link endpoints under /api/linked:
     *
     * GET    /api/linked/verify?code=ABC123&discord_id=123   — verify a link code
     * GET    /api/linked/{discord_id}                        — look up linked account info
     * DELETE /api/linked/{discord_id}                        — unlink the account
     */
    private class LinkedLookupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!validateApiKey(exchange)) { sendError(exchange, 401, "Invalid API key"); return; }
                String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
                if (!checkRateLimit(apiKey != null ? apiKey : "anonymous")) { sendError(exchange, 429, "Rate limit exceeded"); return; }

                if (linkService == null) { sendError(exchange, 503, "Link system not available"); return; }

                String pathParam = getPathParam(exchange, "/api/linked");

                // ── Verify sub-route: /api/linked/verify?code=...&discord_id=... ──
                if ("verify".equalsIgnoreCase(pathParam)) {
                    handleVerify(exchange);
                    return;
                }

                // ── Lookup / Unlink: /api/linked/{discord_id} ──
                if (pathParam == null || pathParam.isEmpty()) {
                    sendError(exchange, 400, "Missing Discord ID in path");
                    return;
                }

                String discordId = pathParam;
                String method = exchange.getRequestMethod().toUpperCase();

                if ("DELETE".equals(method)) {
                    boolean removed = linkService.unlinkByDiscord(discordId);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("unlinked", removed);
                    sendResponse(exchange, 200, response);
                    return;
                }

                // Default: GET — lookup
                Optional<String> mcUuidOpt = linkService.getMcUuid(discordId);
                if (mcUuidOpt.isEmpty()) {
                    sendError(exchange, 404, "No linked account found for this Discord ID");
                    return;
                }

                String mcUuid = mcUuidOpt.get();
                UUID uuid = UUID.fromString(mcUuid);
                String playerName = Bukkit.getOfflinePlayer(uuid).getName();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("discord_id", discordId);
                response.put("mc_uuid", mcUuid);
                response.put("mc_username", playerName != null ? playerName : mcUuid);

                if (teamService != null) {
                    Optional<String> teamOpt = teamService.getPlayerTeam(uuid);
                    response.put("team", teamOpt.orElse(null));
                }

                if (meritService != null) {
                    Optional<PlayerMeritData> md = meritService.getPlayerData(uuid);
                    if (md.isPresent()) {
                        MeritRank rank = md.get().getRank();
                        response.put("rank", rank.getDisplayName());
                        response.put("rank_tag", rank.getTag());
                    } else {
                        response.put("rank", MeritRank.RECRUIT.getDisplayName());
                        response.put("rank_tag", MeritRank.RECRUIT.getTag());
                    }
                }

                if (divisionService != null) {
                    Optional<Division> divOpt = divisionService.getPlayerDivision(uuid);
                    if (divOpt.isPresent()) {
                        response.put("division", divOpt.get().name());
                        response.put("division_tag", divOpt.get().tag());
                    }
                }

                sendResponse(exchange, 200, response);

            } catch (Exception e) {
                logger.severe("[Stats API] Linked handler error: " + e.getMessage());
                e.printStackTrace();
                try { sendError(exchange, 500, "Internal server error"); } catch (Exception ignored) {}
            }
        }

        private void handleVerify(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQueryParams(exchange);
            String code = params.get("code");
            String discordId = params.get("discord_id");

            if (code == null || code.isEmpty() || discordId == null || discordId.isEmpty()) {
                sendError(exchange, 400, "Missing 'code' or 'discord_id' query parameters");
                return;
            }

            LinkService.VerifyResult result = linkService.verify(code.trim(), discordId.trim());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result.name());

            if (result == LinkService.VerifyResult.OK) {
                linkService.getMcUuid(discordId.trim()).ifPresent(uuid -> {
                    response.put("mc_uuid", uuid);
                    String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
                    response.put("mc_username", name != null ? name : uuid);
                });
                sendResponse(exchange, 200, response);
            } else {
                String message = switch (result) {
                    case INVALID_CODE -> "Invalid or expired link code.";
                    case EXPIRED -> "Code has expired. Generate a new one with /link in-game.";
                    case DISCORD_ALREADY_LINKED -> "This Discord account is already linked. Use /unlink first.";
                    case MC_ALREADY_LINKED -> "This Minecraft account is already linked to another Discord account.";
                    case DB_ERROR -> "Database error — please try again.";
                    default -> "Unknown error.";
                };
                response.put("message", message);
                sendResponse(exchange, 400, response);
            }
        }
    }
}
