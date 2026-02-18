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

        // Check kill achievements
        checkKillAchievements(killer);

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

        // Check capture achievements
        checkCaptureAchievements(uuid, isTopContributor);

        return totalTokens;
    }

    @Override
    public int onRegionDefend(UUID uuid, Integer roundId) {
        int tokens = 2;
        db.addTokens(uuid, tokens, MeritTokenSource.DEFEND_REGION, "Region defended", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Region defense");

        // Check defense achievements
        checkDefenseAchievements(uuid);

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

        // Check road achievements
        checkRoadAchievements(uuid);

        return tokensEarned;
    }

    @Override
    public int onSupplyRouteComplete(UUID uuid, Integer roundId) {
        int tokens = 2;
        db.addTokens(uuid, tokens, MeritTokenSource.COMPLETE_SUPPLY_ROUTE, "Supply route completed", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Supply route");

        // Check supply achievements
        checkSupplyAchievements(uuid, false, false);

        return tokens;
    }

    @Override
    public int onRegionSupplied(UUID uuid, Integer roundId) {
        int tokens = 1;
        db.addTokens(uuid, tokens, MeritTokenSource.ESTABLISH_SUPPLY, "Region supplied", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Supply established");

        // Check supply achievements
        checkSupplyAchievements(uuid, true, false);

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

        // Check sabotage achievements
        checkSupplyAchievements(uuid, false, regionsAffected >= 3);
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

            // Check streak achievements
            checkStreakAchievements(uuid, streakCount);
        }

        return tokens;
    }

    @Override
    public int onShutdown(UUID uuid, Integer roundId) {
        int tokens = 1;
        db.addTokens(uuid, tokens, MeritTokenSource.SHUTDOWN, "Shutdown enemy streak", roundId);
        invalidateCache(uuid);
        notifyTokenEarned(uuid, tokens, "Shutdown");

        // Check shutdown achievement
        checkShutdownAchievement(uuid);

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

        // Check login streak achievements
        checkLoginStreakAchievements(uuid);

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

        // Check playtime achievements
        checkPlaytimeAchievements(uuid);

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

        // Check if giver is OP (bypasses all anti-farm checks)
        Player giverPlayer = Bukkit.getPlayer(giver);
        boolean isOp = giverPlayer != null && giverPlayer.isOp();

        // Skip anti-farm checks if debug mode OR if player is OP
        if (!configManager.skipMeritAntiFarm() && !isOp) {
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

        // Check merit giving/receiving achievements
        checkMeritGivingAchievements(giver, receiver);

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

    // ==================== ACHIEVEMENTS ====================

    @Override
    public Set<Achievement> getUnlockedAchievements(UUID uuid) {
        return db.getUnlockedAchievements(uuid);
    }

    @Override
    public boolean hasAchievement(UUID uuid, Achievement achievement) {
        return db.hasAchievement(uuid, achievement);
    }

    @Override
    public int awardAchievement(UUID uuid, Achievement achievement) {
        if (db.hasAchievement(uuid, achievement)) {
            return 0; // Already unlocked
        }

        // Unlock the achievement
        db.unlockAchievement(uuid, achievement);

        // Award tokens
        int tokens = achievement.getTokenReward();
        db.addTokens(uuid, tokens, MeritTokenSource.ACHIEVEMENT, "Achievement: " + achievement.getDisplayName(), null);
        invalidateCache(uuid);

        // Notify player
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "★ " + ChatColor.YELLOW + "Achievement Unlocked! " + ChatColor.GOLD + "★");
            player.sendMessage(achievement.getColor() + achievement.getDisplayName() + ChatColor.GRAY + " - " + achievement.getDescription());
            player.sendMessage(ChatColor.GOLD + "+" + tokens + " Merit Tokens");
            player.sendMessage("");

            // Play sound
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }

        return tokens;
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
        getOrCreatePlayerData(uuid);
        db.setReceivedMerits(uuid, amount);
        invalidateCache(uuid);

        // Notify player if online
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            MeritRank newRank = MeritRank.getRankForMerits(amount);
            player.sendMessage(ChatColor.GOLD + "Your merits have been set to " + amount +
                    " by an admin. Rank: " + newRank.getFormattedTag());
        }
    }

    @Override
    public void adminReset(UUID uuid) {
        db.resetPlayerData(uuid);
        invalidateCache(uuid);

        // Notify player if online
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(ChatColor.YELLOW + "Your merit data has been reset by an admin.");
        }

        logger.info("[MeritService] Reset merit data for " + uuid);
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

                // Award rank achievement
                checkRankAchievements(uuid, r);
                break;
            }
        }
    }

    // ==================== ACHIEVEMENT CHECKING ====================

    private void checkKillAchievements(UUID uuid) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int kills = data.lifetimeKills();

        // First kill
        if (kills >= 1) {
            awardAchievement(uuid, Achievement.FIRST_KILL);
        }
        if (kills >= 10) {
            awardAchievement(uuid, Achievement.KILL_10);
        }
        if (kills >= 50) {
            awardAchievement(uuid, Achievement.KILL_50);
        }
        if (kills >= 100) {
            awardAchievement(uuid, Achievement.KILL_100);
        }
        if (kills >= 500) {
            awardAchievement(uuid, Achievement.KILL_500);
        }
    }

    /**
     * Called when a player gets a kill streak.
     */
    public void checkStreakAchievements(UUID uuid, int streak) {
        if (streak >= 5) {
            awardAchievement(uuid, Achievement.STREAK_5);
        }
        if (streak >= 10) {
            awardAchievement(uuid, Achievement.STREAK_10);
        }
        if (streak >= 15) {
            awardAchievement(uuid, Achievement.STREAK_15);
        }
    }

    /**
     * Called when a player shuts down an enemy streak.
     */
    public void checkShutdownAchievement(UUID uuid) {
        awardAchievement(uuid, Achievement.SHUTDOWN_STREAK);
    }

    private void checkCaptureAchievements(UUID uuid, boolean wasTopContributor) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int captures = data.lifetimeCaptures();

        if (captures >= 1) {
            awardAchievement(uuid, Achievement.FIRST_CAPTURE);
        }
        if (captures >= 5) {
            awardAchievement(uuid, Achievement.CAPTURE_5);
        }
        if (captures >= 10) {
            awardAchievement(uuid, Achievement.CAPTURE_10);
        }
        if (captures >= 25) {
            awardAchievement(uuid, Achievement.CAPTURE_25);
        }
        if (wasTopContributor) {
            awardAchievement(uuid, Achievement.TOP_CONTRIBUTOR);
        }
    }

    private void checkDefenseAchievements(UUID uuid) {
        // For defense, we'd need to track defense count - for now just award first defense
        awardAchievement(uuid, Achievement.DEFEND_REGION);
    }

    private void checkRoadAchievements(UUID uuid) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int roadBlocks = data.lifetimeRoadBlocks();

        if (roadBlocks >= 1) {
            awardAchievement(uuid, Achievement.FIRST_ROAD);
        }
        if (roadBlocks >= 100) {
            awardAchievement(uuid, Achievement.ROAD_100);
        }
        if (roadBlocks >= 500) {
            awardAchievement(uuid, Achievement.ROAD_500);
        }
        if (roadBlocks >= 1000) {
            awardAchievement(uuid, Achievement.ROAD_1000);
        }
    }

    private void checkSupplyAchievements(UUID uuid, boolean regionSupplied, boolean majorSabotage) {
        if (regionSupplied) {
            awardAchievement(uuid, Achievement.SUPPLY_REGION);
        }
        if (majorSabotage) {
            awardAchievement(uuid, Achievement.MAJOR_SABOTAGE);
        }
        // DISRUPT_SUPPLY is awarded on any supply disruption
        awardAchievement(uuid, Achievement.DISRUPT_SUPPLY);
    }

    private void checkPlaytimeAchievements(UUID uuid) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int minutes = data.playtimeMinutes();

        if (minutes >= 60) {
            awardAchievement(uuid, Achievement.PLAY_1_HOUR);
        }
        if (minutes >= 600) {
            awardAchievement(uuid, Achievement.PLAY_10_HOURS);
        }
        if (minutes >= 3000) {
            awardAchievement(uuid, Achievement.PLAY_50_HOURS);
        }
        if (minutes >= 6000) {
            awardAchievement(uuid, Achievement.PLAY_100_HOURS);
        }
    }

    private void checkLoginStreakAchievements(UUID uuid) {
        PlayerMeritData data = getOrCreatePlayerData(uuid);
        int streak = data.loginStreak();

        if (streak >= 7) {
            awardAchievement(uuid, Achievement.LOGIN_STREAK_7);
        }
        if (streak >= 30) {
            awardAchievement(uuid, Achievement.LOGIN_STREAK_30);
        }
    }

    private void checkRankAchievements(UUID uuid, MeritRank rank) {
        switch (rank) {
            case CORPORAL -> awardAchievement(uuid, Achievement.RANK_CORPORAL);
            case SERGEANT -> awardAchievement(uuid, Achievement.RANK_SERGEANT);
            case STAFF_SERGEANT -> awardAchievement(uuid, Achievement.RANK_STAFF_SERGEANT);
            case SECOND_LIEUTENANT, FIRST_LIEUTENANT -> awardAchievement(uuid, Achievement.RANK_LIEUTENANT);
            case CAPTAIN -> awardAchievement(uuid, Achievement.RANK_CAPTAIN);
            case MAJOR -> awardAchievement(uuid, Achievement.RANK_MAJOR);
            case LIEUTENANT_COLONEL, COLONEL -> awardAchievement(uuid, Achievement.RANK_COLONEL);
            case BRIGADIER_GENERAL, MAJOR_GENERAL, LIEUTENANT_GENERAL, GENERAL, GENERAL_OF_THE_ARMY ->
                    awardAchievement(uuid, Achievement.RANK_GENERAL);
            default -> {} // No achievement for RECRUIT, PRIVATE, PFC, MASTER_SERGEANT
        }
    }

    /**
     * Called when a player gives merit to another player.
     */
    public void checkMeritGivingAchievements(UUID giver, UUID receiver) {
        PlayerMeritData giverData = getOrCreatePlayerData(giver);

        // First merit given
        if (giverData.lifetimeMeritsGiven() >= 1) {
            awardAchievement(giver, Achievement.GIVE_MERIT);
        }
        if (giverData.lifetimeMeritsGiven() >= 10) {
            awardAchievement(giver, Achievement.GIVE_10_MERITS);
        }

        PlayerMeritData receiverData = getOrCreatePlayerData(receiver);

        // First merit received
        if (receiverData.lifetimeMeritsReceived() >= 1) {
            awardAchievement(receiver, Achievement.RECEIVE_MERIT);
        }
        if (receiverData.lifetimeMeritsReceived() >= 10) {
            awardAchievement(receiver, Achievement.RECEIVE_10_MERITS);
        }
    }

    /**
     * Called on first player login.
     */
    public void checkFirstLoginAchievement(UUID uuid) {
        awardAchievement(uuid, Achievement.FIRST_LOGIN);
    }
}

