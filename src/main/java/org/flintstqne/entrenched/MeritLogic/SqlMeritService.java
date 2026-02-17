package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * SQL-based implementation of MeritService.
 */
public class SqlMeritService implements MeritService {

    private final MeritDb db;
    private final ConfigManager configManager;
    private final Logger logger;

    // Cache for player data (short TTL)
    private final Map<UUID, PlayerMeritData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000; // 5 seconds

    // Track first blood per round
    private final Set<Integer> firstBloodClaimed = ConcurrentHashMap.newKeySet();

    // Track cross-team merits given today
    private final Map<UUID, Integer> crossTeamMeritsToday = new ConcurrentHashMap<>();
    private String lastCrossTeamResetDate = "";

    // Anti-farming: track merits given to same player today
    private final Map<String, Integer> samePlayerMeritsToday = new ConcurrentHashMap<>(); // "giver:receiver" -> count

    public SqlMeritService(MeritDb db, ConfigManager configManager) {
        this.db = db;
        this.configManager = configManager;
        this.logger = Bukkit.getLogger();
    }

    // ==================== PLAYER DATA ====================

    @Override
    public Optional<PlayerMeritData> getPlayerData(UUID uuid) {
        // Check cache
        Long cacheTime = cacheTimestamps.get(uuid);
        if (cacheTime != null && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) {
            PlayerMeritData cached = cache.get(uuid);
            if (cached != null) {
                return Optional.of(cached);
            }
        }

        // Fetch from DB
        Optional<PlayerMeritData> data = db.getPlayerData(uuid);
        data.ifPresent(d -> {
            cache.put(uuid, d);
            cacheTimestamps.put(uuid, System.currentTimeMillis());
        });
        return data;
    }

    @Override
    public PlayerMeritData getOrCreatePlayerData(UUID uuid) {
        Optional<PlayerMeritData> existing = getPlayerData(uuid);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new
        db.createPlayerData(uuid);
        PlayerMeritData newData = PlayerMeritData.createNew(uuid);
        cache.put(uuid, newData);
        cacheTimestamps.put(uuid, System.currentTimeMillis());
        return newData;
    }

    @Override
    public MeritRank getPlayerRank(UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerMeritData::getRank)
                .orElse(MeritRank.RECRUIT);
    }

    @Override
    public int getTokenBalance(UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerMeritData::tokenBalance)
                .orElse(0);
    }

    @Override
    public int getReceivedMerits(UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerMeritData::receivedMerits)
                .orElse(0);
    }

    private void invalidateCache(UUID uuid) {
        cache.remove(uuid);
        cacheTimestamps.remove(uuid);
    }

    /**
     * Logs debug message if merit verbose logging is enabled.
     */
    private void logDebug(String message) {
        if (configManager.isMeritVerbose()) {
            logger.info("[MeritDebug] " + message);
        }
    }

    /**
     * Applies token multiplier from config.
     */
    private int applyMultiplier(int tokens) {
        double multiplier = configManager.getMeritTokenMultiplier();
        return (int) Math.ceil(tokens * multiplier);
    }

    // ==================== TOKEN EARNING ====================

    @Override
    public int onPlayerKill(UUID killer, UUID victim, boolean inEnemyTerritory, Integer roundId) {
        // Check cooldown (same victim within configurable minutes = no tokens)
        int cooldownMinutes = configManager.getMeritKillCooldownMinutes();
        if (db.isKillOnCooldown(killer, victim)) {
            logDebug("Kill cooldown active for " + killer + " -> " + victim);
            return 0;
        }

        // Record the kill
        db.recordKill(killer, victim);
        db.incrementKills(killer);

        // Determine source based on territory
        MeritTokenSource source = inEnemyTerritory ?
                MeritTokenSource.KILL_ENEMY_TERRITORY :
                MeritTokenSource.KILL_GENERAL;

        // Add progress (batched: configurable kills needed)
        int tokensEarned = db.addProgress(killer, source, 1);
        tokensEarned = applyMultiplier(tokensEarned);

        if (tokensEarned > 0) {
            db.addTokens(killer, tokensEarned, source, "Enemy kills", roundId);
            invalidateCache(killer);
            notifyTokenEarned(killer, tokensEarned, source.getDisplayName());
            logDebug("Awarded " + tokensEarned + " tokens to " + killer + " for kill");
        }

        return tokensEarned;
    }

    @Override
    public int onRegionCapture(UUID uuid, boolean isNeutral, double ipContributed, boolean isTopContributor, Integer roundId) {
        int totalTokens = 0;

        // Base capture tokens
        MeritTokenSource captureSource = isNeutral ?
                MeritTokenSource.CAPTURE_NEUTRAL :
                MeritTokenSource.CAPTURE_ENEMY;

        int captureTokens = isNeutral ? 3 : 5;
        db.addTokens(uuid, captureTokens, captureSource, "Region captured", roundId);
        totalTokens += captureTokens;

        // Participation bonus (if contributed 100+ IP)
        if (ipContributed >= 100) {
            db.addTokens(uuid, 1, MeritTokenSource.PARTICIPATE_CAPTURE, "Capture participation", roundId);
            totalTokens += 1;
        }

        // Top contributor bonus
        if (isTopContributor) {
            db.addTokens(uuid, 2, MeritTokenSource.MAJOR_CONTRIBUTOR, "Top contributor", roundId);
            totalTokens += 2;
        }

        // Update stats
        db.incrementCaptures(uuid);
        invalidateCache(uuid);

        if (totalTokens > 0) {
            notifyTokenEarned(uuid, totalTokens, "Region capture");
        }

        return totalTokens;
    }

    @Override
    public int onRegionDefend(UUID uuid, Integer roundId) {
        int tokens = 2;
        db.addTokens(uuid, tokens, MeritTokenSource.DEFEND_REGION, "Region defended", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Region defense");
        return tokens;
    }

    @Override
    public int onRoadBlocksPlaced(UUID uuid, int blockCount, Integer roundId) {
        // Track lifetime blocks
        db.addRoadBlocks(uuid, blockCount);

        // Add progress (1 token per 100 blocks)
        int tokensEarned = db.addProgress(uuid, MeritTokenSource.ROAD_MILESTONE, blockCount);

        if (tokensEarned > 0) {
            db.addTokens(uuid, tokensEarned, MeritTokenSource.ROAD_MILESTONE, "Road construction", roundId);
            invalidateCache(uuid);
            notifyTokenEarned(uuid, tokensEarned, "Road milestone");
        }

        return tokensEarned;
    }

    @Override
    public int onSupplyRouteComplete(UUID uuid, Integer roundId) {
        int tokens = 2;
        db.addTokens(uuid, tokens, MeritTokenSource.COMPLETE_SUPPLY_ROUTE, "Supply route completed", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Supply route");
        return tokens;
    }

    @Override
    public int onRegionSupplied(UUID uuid, Integer roundId) {
        int tokens = 1;
        db.addTokens(uuid, tokens, MeritTokenSource.ESTABLISH_SUPPLY, "Region supplied", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Supply established");
        return tokens;
    }

    @Override
    public int onSupplyDisrupted(UUID uuid, int regionsAffected, Integer roundId) {
        int tokens;
        MeritTokenSource source;

        if (regionsAffected >= 3) {
            tokens = 2;
            source = MeritTokenSource.MAJOR_SABOTAGE;
        } else {
            tokens = 1;
            source = MeritTokenSource.DISRUPT_SUPPLY;
        }

        db.addTokens(uuid, tokens, source, "Supply disrupted (" + regionsAffected + " regions)", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Supply disruption");
        return tokens;
    }

    @Override
    public int onFortificationBuilt(UUID uuid, int blockCount, Integer roundId) {
        int tokensEarned = db.addProgress(uuid, MeritTokenSource.MAJOR_FORTIFICATION, blockCount);

        if (tokensEarned > 0) {
            db.addTokens(uuid, tokensEarned, MeritTokenSource.MAJOR_FORTIFICATION, "Fortification built", roundId);
            invalidateCache(uuid);
            notifyTokenEarned(uuid, tokensEarned, "Fortification");
        }

        return tokensEarned;
    }

    @Override
    public int onKillStreak(UUID uuid, int streakCount, Integer roundId) {
        int tokens = 0;
        MeritTokenSource source = null;

        if (streakCount >= 10) {
            tokens = 2;
            source = MeritTokenSource.KILL_STREAK_10;
        } else if (streakCount >= 5) {
            tokens = 1;
            source = MeritTokenSource.KILL_STREAK_5;
        }

        if (tokens > 0 && source != null) {
            db.addTokens(uuid, tokens, source, streakCount + " kill streak", roundId);
            invalidateCache(uuid);
            notifyTokenEarned(uuid, tokens, streakCount + " kill streak");
        }

        return tokens;
    }

    @Override
    public int onShutdown(UUID uuid, Integer roundId) {
        int tokens = 1;
        db.addTokens(uuid, tokens, MeritTokenSource.SHUTDOWN, "Shutdown enemy streak", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Shutdown");
        return tokens;
    }

    @Override
    public int onFirstBlood(UUID uuid, Integer roundId) {
        // Only one first blood per round
        if (roundId != null && !firstBloodClaimed.add(roundId)) {
            return 0;
        }

        int tokens = 2;
        db.addTokens(uuid, tokens, MeritTokenSource.FIRST_BLOOD, "First blood", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "First Blood!");
        return tokens;
    }

    @Override
    public int onDailyLogin(UUID uuid, Integer roundId) {
        // Check if first login today
        if (!db.updateLoginStreak(uuid)) {
            return 0; // Already logged in today
        }

        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int streak = data.loginStreak();
        int tokens = 0;

        // Daily login token
        tokens += 1;
        db.addTokens(uuid, 1, MeritTokenSource.DAILY_LOGIN, "Daily login", roundId);

        // Streak bonuses
        if (streak == 7) {
            tokens += 2;
            db.addTokens(uuid, 2, MeritTokenSource.LOGIN_STREAK_7, "7-day streak", roundId);
        } else if (streak == 30) {
            tokens += 5;
            db.addTokens(uuid, 5, MeritTokenSource.LOGIN_STREAK_30, "30-day streak", roundId);
        }

        if (tokens > 0) {
            invalidateCache(uuid);
            notifyTokenEarned(uuid, tokens, "Daily login" + (streak > 1 ? " (" + streak + "-day streak)" : ""));
        }

        return tokens;
    }

    @Override
    public int onRoundComplete(UUID uuid, double ipEarned, Integer roundId) {
        // Must have earned 100+ IP to get completion bonus
        if (ipEarned < 100) {
            return 0;
        }

        int tokens = 1;
        db.addTokens(uuid, tokens, MeritTokenSource.ROUND_COMPLETION, "Round completed", roundId);
        db.incrementRoundsCompleted(uuid);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Round completion");
        return tokens;
    }

    @Override
    public int onPlaytimeUpdate(UUID uuid, int minutesPlayed, Integer roundId) {
        db.addPlaytimeMinutes(uuid, minutesPlayed);

        // 1 token per 120 minutes (2 hours)
        int tokensEarned = db.addProgress(uuid, MeritTokenSource.ACTIVE_PLAYTIME, minutesPlayed);

        if (tokensEarned > 0) {
            db.addTokens(uuid, tokensEarned, MeritTokenSource.ACTIVE_PLAYTIME, "Active playtime", roundId);
            invalidateCache(uuid);
            notifyTokenEarned(uuid, tokensEarned, "Playtime milestone");
        }

        return tokensEarned;
    }

    // ==================== MERIT GIVING ====================

    @Override
    public GiveResult giveMerit(UUID giver, UUID receiver, int amount, String reason, boolean sameTeam, Integer roundId) {
        // Can't merit yourself (unless debug mode allows it)
        if (giver.equals(receiver) && !configManager.allowSelfMerit()) {
            return GiveResult.SELF_MERIT;
        }

        // Get player data
        PlayerMeritData giverData = getOrCreatePlayerData(giver);
        PlayerMeritData receiverData = getOrCreatePlayerData(receiver);

        // Check token balance
        if (giverData.tokenBalance() < amount) {
            return GiveResult.INSUFFICIENT_TOKENS;
        }

        // Track same-player merit giving (used after transfer)
        String pairKey = giver + ":" + receiver;
        int samePlayerCount = samePlayerMeritsToday.getOrDefault(pairKey, 0);

        // Skip anti-farm checks if debug mode
        if (!configManager.skipMeritAntiFarm()) {
            // Check new player lockout (giver playtime requirement)
            int giverPlaytimeRequired = configManager.skipMeritPlaytimeRequirements() ? 0 : configManager.getMeritGiverPlaytimeRequired();
            if (giverData.playtimeMinutes() < giverPlaytimeRequired) {
                return GiveResult.NEW_PLAYER_LOCKOUT;
            }

            // Check new player lockout (receiver playtime requirement)
            int receiverPlaytimeRequired = configManager.skipMeritPlaytimeRequirements() ? 0 : configManager.getMeritReceiverPlaytimeRequired();
            if (receiverData.playtimeMinutes() < receiverPlaytimeRequired) {
                return GiveResult.NEW_PLAYER_LOCKOUT;
            }

            // Check giver daily limit
            if (giverData.meritsGivenToday() >= configManager.getMeritDailyGiveLimit()) {
                return GiveResult.DAILY_LIMIT_GIVER;
            }

            // Check receiver daily limit
            if (receiverData.receivedToday() >= configManager.getMeritDailyReceiveLimit()) {
                return GiveResult.DAILY_LIMIT_RECEIVER;
            }

            // Check same player cooldown (max per day to same player)
            if (samePlayerCount >= configManager.getMeritSamePlayerDailyLimit()) {
                return GiveResult.SAME_PLAYER_COOLDOWN;
            }

            // Check cross-team limit
            if (!sameTeam) {
                resetCrossTeamIfNeeded();
                int crossTeamCount = crossTeamMeritsToday.getOrDefault(giver, 0);
                if (crossTeamCount >= configManager.getMeritCrossTeamDailyLimit()) {
                    return GiveResult.CROSS_TEAM_LIMIT;
                }
            }

            // Check interaction requirement (must have been in same region within X min)
            int interactionMinutes = configManager.getMeritInteractionRequirementMinutes();
            if (!db.hasRecentInteraction(giver, receiver, interactionMinutes)) {
                return GiveResult.NO_INTERACTION;
            }
        }

        // All checks passed - execute the transfer
        if (!db.removeTokens(giver, amount)) {
            return GiveResult.INSUFFICIENT_TOKENS;
        }

        db.addReceivedMerits(receiver, giver, amount, reason, roundId);
        db.recordMeritGiven(giver, receiver, amount, reason, roundId);

        // Update tracking
        samePlayerMeritsToday.put(pairKey, samePlayerCount + amount);
        if (!sameTeam) {
            crossTeamMeritsToday.merge(giver, amount, Integer::sum);
        }

        // Invalidate caches
        invalidateCache(giver);
        invalidateCache(receiver);

        // Notify players
        notifyMeritGiven(giver, receiver, amount, reason);

        // Check for rank up
        checkRankUp(receiver);

        return GiveResult.SUCCESS;
    }

    private void resetCrossTeamIfNeeded() {
        String today = java.time.LocalDate.now().toString();
        if (!today.equals(lastCrossTeamResetDate)) {
            crossTeamMeritsToday.clear();
            samePlayerMeritsToday.clear();
            lastCrossTeamResetDate = today;
        }
    }

    // ==================== INTERACTION TRACKING ====================

    @Override
    public void recordInteraction(UUID player1, UUID player2, String regionId) {
        db.recordInteraction(player1, player2, regionId, "same_region");
    }

    // ==================== LEADERBOARD ====================

    @Override
    public List<PlayerMeritData> getLeaderboard(int limit) {
        return db.getTopByReceivedMerits(limit);
    }

    // ==================== ADMIN ====================

    @Override
    public void adminGiveTokens(UUID uuid, int amount) {
        getOrCreatePlayerData(uuid); // Ensure player exists
        db.addTokens(uuid, amount, MeritTokenSource.ACHIEVEMENT, "Admin grant", null);
        invalidateCache(uuid);
    }

    @Override
    public void adminGiveMerits(UUID uuid, int amount) {
        getOrCreatePlayerData(uuid); // Ensure player exists
        db.addReceivedMerits(uuid, uuid, amount, "Admin grant", null);
        invalidateCache(uuid);
        checkRankUp(uuid);
    }

    @Override
    public void adminSetMerits(UUID uuid, int amount) {
        // This would need a specific DB method - for now, use the simple approach
        getOrCreatePlayerData(uuid);
        // Would need to add a setReceivedMerits method to DB
        logger.warning("[MeritService] adminSetMerits not fully implemented");
    }

    @Override
    public void adminReset(UUID uuid) {
        // Would need a reset method in DB
        logger.warning("[MeritService] adminReset not fully implemented");
    }

    // ==================== NOTIFICATIONS ====================

    private void notifyTokenEarned(UUID uuid, int amount, String source) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.GOLD + "+" + amount + " Merit Token" + (amount > 1 ? "s" : "") +
                    ChatColor.GRAY + " - " + source);
        }
    }

    private void notifyMeritGiven(UUID giver, UUID receiver, int amount, String reason) {
        Player giverPlayer = Bukkit.getPlayer(giver);
        Player receiverPlayer = Bukkit.getPlayer(receiver);

        String giverName = giverPlayer != null ? giverPlayer.getName() : "Someone";
        String receiverName = receiverPlayer != null ? receiverPlayer.getName() : "Someone";

        // Notify giver
        if (giverPlayer != null && giverPlayer.isOnline()) {
            giverPlayer.sendMessage(ChatColor.GREEN + "You awarded " + amount + " merit" + (amount > 1 ? "s" : "") +
                    " to " + ChatColor.WHITE + receiverName);
        }

        // Notify receiver
        if (receiverPlayer != null && receiverPlayer.isOnline()) {
            String reasonText = reason != null ? " for " + reason : "";
            receiverPlayer.sendMessage(ChatColor.GOLD + giverName + ChatColor.GREEN +
                    " awarded you " + amount + " merit" + (amount > 1 ? "s" : "") + reasonText + "!");
        }

        // Broadcast (configurable)
        if (reason != null) {
            String broadcast = ChatColor.YELLOW + giverName + " recognized " + receiverName + " for " + reason + "!";
            Bukkit.broadcastMessage(broadcast);
        }
    }

    private void checkRankUp(UUID uuid) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        MeritRank rank = data.getRank();

        // Check if rank changed (would need to track previous rank)
        // For now, just announce if they reached certain thresholds
        int merits = data.receivedMerits();

        // Announce promotions at rank thresholds
        for (MeritRank r : MeritRank.values()) {
            if (merits == r.getMeritsRequired() && r != MeritRank.RECRUIT) {
                Player player = Bukkit.getPlayer(uuid);
                String name = player != null ? player.getName() : uuid.toString();
                Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.WHITE + name +
                        ChatColor.GOLD + " has been promoted to " + r.getFormattedTag() + " " + r.getDisplayName() + "!");
                break;
            }
        }
    }
}

