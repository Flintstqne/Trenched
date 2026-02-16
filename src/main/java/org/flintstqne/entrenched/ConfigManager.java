package org.flintstqne.entrenched;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Manages plugin configuration with type-safe accessors.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    // ==================== Round Settings ====================

    /**
     * Gets the phase duration in minutes.
     * @return phase duration in minutes, or 0 if auto-advancement is disabled
     */
    public int getPhaseDurationMinutes() {
        return config.getInt("round.phase-duration-minutes", 10080);
    }

    /**
     * Gets the phase duration in milliseconds.
     * @return phase duration in milliseconds
     */
    public long getPhaseDurationMillis() {
        return getPhaseDurationMinutes() * 60L * 1000L;
    }

    /**
     * Gets the phase duration in ticks.
     * @return phase duration in ticks (20 ticks = 1 second)
     */
    public long getPhaseDurationTicks() {
        return getPhaseDurationMinutes() * 60L * 20L;
    }

    /**
     * Checks if auto phase advancement is enabled.
     * @return true if phase duration is greater than 0
     */
    public boolean isAutoPhaseEnabled() {
        return getPhaseDurationMinutes() > 0;
    }

    public int getMaxPhases() {
        return config.getInt("round.max-phases", 3);
    }

    public boolean isAutoStartRoundEnabled() {
        return config.getBoolean("round.auto-start-round", true);
    }

    // ==================== World Settings ====================

    public String getWorldName() {
        return config.getString("world.name", "world");
    }

    public double getBorderSize() {
        return config.getDouble("world.border-size", 1024.0);
    }

    public double getBorderCenterX() {
        return config.getDouble("world.border-center-x", 0.0);
    }

    public double getBorderCenterZ() {
        return config.getDouble("world.border-center-z", 0.0);
    }

    public double getBorderDamageAmount() {
        return config.getDouble("world.border-damage-amount", 0.2);
    }

    public double getBorderDamageBuffer() {
        return config.getDouble("world.border-damage-buffer", 5.0);
    }

    public int getBorderWarningDistance() {
        return config.getInt("world.border-warning-distance", 10);
    }

    // ==================== Pregeneration Settings ====================

    public boolean isPregenEnabled() {
        return config.getBoolean("pregeneration.enabled", true);
    }

    public int getPregenRadiusChunks() {
        return config.getInt("pregeneration.radius-chunks", 2048);
    }

    public int getPregenChunksPerSecond() {
        return config.getInt("pregeneration.chunks-per-second", 250);
    }

    // ==================== Team Settings ====================

    public int getRedSpawnX() {
        return config.getInt("teams.red.spawn-x", -767);
    }

    public int getRedSpawnZ() {
        return config.getInt("teams.red.spawn-z", -767);
    }

    public int getBlueSpawnX() {
        return config.getInt("teams.blue.spawn-x", 767);
    }

    public int getBlueSpawnZ() {
        return config.getInt("teams.blue.spawn-z", 767);
    }

    public int getRegionSize() {
        return config.getInt("teams.region-size", 512);
    }

    // ==================== Player Settings ====================

    public boolean isRespawnAtTeamSpawn() {
        return config.getBoolean("player.respawn-at-team-spawn", true);
    }

    public boolean isNoTeamToWorldSpawn() {
        return config.getBoolean("player.no-team-to-world-spawn", true);
    }

    public boolean isShowTeamGuiOnJoin() {
        return config.getBoolean("player.show-team-gui-on-join", true);
    }

    public int getTeamGuiDelayTicks() {
        return config.getInt("player.team-gui-delay-ticks", 20);
    }

    // ==================== Messages ====================

    public String getPrefix() {
        return translateColors(config.getString("messages.prefix", "&6[BlockHole] "));
    }

    public String getPhaseAdvancedMessage() {
        return translateColors(config.getString("messages.phase-advanced", "&aPhase {phase} has begun!"));
    }

    public String getRoundStartedMessage() {
        return translateColors(config.getString("messages.round-started", "&aRound {round} has started!"));
    }

    public String getRoundEndedMessage() {
        return translateColors(config.getString("messages.round-ended", "&6Round {round} has ended! Winner: {winner}"));
    }

    public String getNewRoundKickMessage() {
        return translateColors(config.getString("messages.new-round-kick",
                "&6&l[BlockHole] &eServer is resetting for a new war!\n&7World is regenerating. Please reconnect in ~20-30 minutes."));
    }

    // ==================== BlueMap Settings ====================

    public boolean isBlueMapEnabled() {
        return config.getBoolean("bluemap.enabled", true);
    }

    public int getMarkerRefreshSeconds() {
        return config.getInt("bluemap.marker-refresh-seconds", 30);
    }

    // ==================== Division Settings ====================

    public boolean isDivisionsEnabled() {
        return config.getBoolean("divisions.enabled", true);
    }

    public int getMaxDivisionsPerTeam() {
        return config.getInt("divisions.max-per-team", 5);
    }

    public int getDivisionFounderCooldownHours() {
        return config.getInt("divisions.founder-cooldown-hours", 48);
    }

    // ==================== Party Settings ====================

    public boolean isPartiesEnabled() {
        return config.getBoolean("parties.enabled", true);
    }

    public int getPartyMaxSize() {
        return config.getInt("parties.max-size", 6);
    }

    public boolean isPartyChatEnabled() {
        return config.getBoolean("parties.features.party-chat", true);
    }

    public boolean isPartyCompassEnabled() {
        return config.getBoolean("parties.features.compass-to-party", true);
    }

    // ==================== Region Capture Settings ====================

    // Influence thresholds
    public double getRegionNeutralCaptureThreshold() {
        return config.getDouble("regions.influence.neutral-capture", 500);
    }

    public double getRegionEnemyCaptureThreshold() {
        return config.getDouble("regions.influence.enemy-capture", 1000);
    }

    public double getRegionInfluenceDecayPerMinute() {
        return config.getDouble("regions.influence.decay-per-minute", 5);
    }

    // Enemy region actions
    public int getRegionKillPoints() {
        return config.getInt("regions.enemy-actions.kill-points", 50);
    }

    public double getRegionKillSamePlayerReduction() {
        return config.getDouble("regions.enemy-actions.kill-same-player-reduction", 0.5);
    }

    public int getRegionBannerPlacePoints() {
        return config.getInt("regions.enemy-actions.banner-place", 25);
    }

    public int getRegionBannerRemovePoints() {
        return config.getInt("regions.enemy-actions.banner-remove-enemy", 15);
    }

    public int getRegionMineEnemyBlocksPoints() {
        return config.getInt("regions.enemy-actions.mine-enemy-blocks", 1);
    }

    public int getRegionMineCapPerSecond() {
        return config.getInt("regions.enemy-actions.mine-cap-per-second", 5);
    }

    // Neutral region actions
    public int getRegionDefensiveBlockPoints() {
        return config.getInt("regions.neutral-actions.defensive-block", 2);
    }

    public int getRegionDefensiveCapPerSecond() {
        return config.getInt("regions.neutral-actions.defensive-cap-per-second", 10);
    }

    public int getRegionWorkstationPoints() {
        return config.getInt("regions.neutral-actions.workstation", 15);
    }

    public int getRegionWorkstationCapPerMinute() {
        return config.getInt("regions.neutral-actions.workstation-cap-per-minute", 3);
    }

    public int getRegionTorchPoints() {
        return config.getInt("regions.neutral-actions.torch", 1);
    }

    public int getRegionTorchCapPerMinute() {
        return config.getInt("regions.neutral-actions.torch-cap-per-minute", 10);
    }

    public int getRegionMobKillPoints() {
        return config.getInt("regions.neutral-actions.mob-kill", 5);
    }

    // Defense settings
    public long getRegionFortificationMinutes() {
        return config.getLong("regions.defense.fortification-minutes", 10);
    }

    // Physical road supply line settings
    public List<String> getSupplyPathBlocks() {
        return config.getStringList("regions.supply.path-blocks");
    }

    public int getSupplyAdjacencyRadius() {
        return config.getInt("regions.supply.adjacency-radius", 3);
    }

    /**
     * Gets the Y-axis tolerance for road pathfinding.
     * This allows roads to go up/down hills with larger vertical gaps.
     * @return Y-axis tolerance (default 32 blocks to handle most terrain)
     */
    public int getSupplyYTolerance() {
        return config.getInt("regions.supply.y-tolerance", 32);
    }

    public int getSupplyBorderWidth() {
        return config.getInt("regions.supply.border-width", 32);
    }

    public int getSupplyPartialRespawnDelay() {
        return config.getInt("regions.supply.partial-supply-respawn-delay", 5);
    }

    public int getSupplyUnsuppliedRespawnDelay() {
        return config.getInt("regions.supply.unsupplied-respawn-delay", 15);
    }

    public int getSupplyIsolatedRespawnDelay() {
        return config.getInt("regions.supply.isolated-respawn-delay", 30);
    }

    public double getSupplyUnsuppliedHealthRegen() {
        return config.getDouble("regions.supply.unsupplied-health-regen", 0.5);
    }

    public double getSupplyIsolatedHealthRegen() {
        return config.getDouble("regions.supply.isolated-health-regen", 0.25);
    }

    // Adjacency settings
    public boolean isRegionStrictAdjacency() {
        return config.getBoolean("regions.adjacency.strict", true);
    }

    public boolean isRegionAllowDiagonal() {
        return config.getBoolean("regions.adjacency.allow-diagonal", false);
    }

    // Home region settings
    public String getRegionRedHome() {
        return config.getString("regions.home.red", "A1");
    }

    public String getRegionBlueHome() {
        return config.getString("regions.home.blue", "D4");
    }

    public int getRegionMinRegionsToAttackHome() {
        return config.getInt("regions.home.min-regions-to-attack", 2);
    }

    // ==================== Debug Settings ====================

    public boolean isVerbose() {
        return config.getBoolean("debug.verbose", false);
    }

    // ==================== Utility Methods ====================

    private String translateColors(String text) {
        if (text == null) return "";
        return text.replace("&", "§");
    }

    /**
     * Formats a message by replacing placeholders.
     */
    public String formatMessage(String message, Object... replacements) {
        String result = message;
        for (int i = 0; i < replacements.length - 1; i += 2) {
            String placeholder = "{" + replacements[i] + "}";
            String value = String.valueOf(replacements[i + 1]);
            result = result.replace(placeholder, value);
        }
        return result;
    }
}

