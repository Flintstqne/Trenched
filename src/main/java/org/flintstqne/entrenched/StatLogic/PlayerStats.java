package org.flintstqne.entrenched.StatLogic;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Data class holding player statistics.
 */
public class PlayerStats {
    private final UUID uuid;
    private final String lastKnownName;
    private final long lastLogin;
    private final Map<StatCategory, Double> stats;

    public PlayerStats(UUID uuid, String lastKnownName, long lastLogin) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.lastLogin = lastLogin;
        this.stats = new EnumMap<>(StatCategory.class);

        // Initialize all stats to 0
        for (StatCategory cat : StatCategory.values()) {
            stats.put(cat, 0.0);
        }
    }

    public PlayerStats(UUID uuid, String lastKnownName, long lastLogin, Map<StatCategory, Double> stats) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.lastLogin = lastLogin;
        this.stats = new EnumMap<>(StatCategory.class);
        this.stats.putAll(stats);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public double getStat(StatCategory category) {
        return stats.getOrDefault(category, 0.0);
    }

    public void setStat(StatCategory category, double value) {
        stats.put(category, value);
    }

    public void incrementStat(StatCategory category, double delta) {
        stats.put(category, stats.getOrDefault(category, 0.0) + delta);
    }

    public Map<StatCategory, Double> getAllStats() {
        return new EnumMap<>(stats);
    }

    // === COMPUTED STATS ===

    /**
     * Computes Kill/Death Ratio.
     */
    public double getKDR() {
        double kills = getStat(StatCategory.KILLS);
        double deaths = getStat(StatCategory.DEATHS);
        if (deaths == 0) return kills;
        return kills / deaths;
    }

    /**
     * Computes Kill+Assist/Death Ratio (KDA).
     */
    public double getKDA() {
        double kills = getStat(StatCategory.KILLS);
        double assists = getStat(StatCategory.ASSISTS);
        double deaths = getStat(StatCategory.DEATHS);
        if (deaths == 0) return kills + assists;
        return (kills + assists) / deaths;
    }

    /**
     * Computes MVP score using weighted formula:
     * (kills * 10) + (objectives_completed * 25) + (regions_captured * 50) + (ip_earned * 0.1)
     */
    public double getMVPScore() {
        double kills = getStat(StatCategory.KILLS);
        double objectives = getStat(StatCategory.OBJECTIVES_COMPLETED);
        double captures = getStat(StatCategory.REGIONS_CAPTURED);
        double ip = getStat(StatCategory.IP_EARNED);

        return (kills * 10) + (objectives * 25) + (captures * 50) + (ip * 0.1);
    }

    /**
     * Computes win rate as percentage.
     */
    public double getWinRate() {
        double played = getStat(StatCategory.ROUNDS_PLAYED);
        double won = getStat(StatCategory.ROUNDS_WON);
        if (played == 0) return 0;
        return (won / played) * 100;
    }

    /**
     * Gets total objectives (settlement + raid).
     */
    public double getTotalObjectives() {
        return getStat(StatCategory.OBJECTIVES_SETTLEMENT) + getStat(StatCategory.OBJECTIVES_RAID);
    }

    @Override
    public String toString() {
        return "PlayerStats{" +
                "uuid=" + uuid +
                ", name='" + lastKnownName + '\'' +
                ", kills=" + getStat(StatCategory.KILLS) +
                ", deaths=" + getStat(StatCategory.DEATHS) +
                ", kdr=" + String.format("%.2f", getKDR()) +
                '}';
    }
}

