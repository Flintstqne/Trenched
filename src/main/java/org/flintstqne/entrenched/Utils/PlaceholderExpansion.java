package org.flintstqne.entrenched.MeritLogic;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * PlaceholderAPI expansion for the merit system.
 *
 * Available placeholders:
 * - %entrenched_rank% - Full rank name (e.g., "Private First Class")
 * - %entrenched_rank_tag% - Rank tag (e.g., "PFC")
 * - %entrenched_rank_formatted% - Formatted tag with color (e.g., "§7[PFC]§r")
 * - %entrenched_rank_color% - Rank color code (e.g., "§7")
 * - %entrenched_tokens% - Current token balance
 * - %entrenched_merits% - Total received merits (determines rank)
 * - %entrenched_merits_today% - Merits received today
 * - %entrenched_next_rank% - Next rank name
 * - %entrenched_merits_to_next% - Merits needed for next rank
 * - %entrenched_progress% - Progress to next rank as percentage
 * - %entrenched_kills% - Lifetime kills
 * - %entrenched_captures% - Lifetime region captures
 * - %entrenched_playtime% - Playtime in hours
 * - %entrenched_streak% - Login streak in days
 * - %entrenched_prefix% - Full prefix for chat/tab: [RANK] in rank color
 * - %entrenched_team_prefix% - Team-colored prefix: [RANK] PlayerName style
 */
public class MeritPlaceholderExpansion extends PlaceholderExpansion {

    private final MeritService meritService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    public MeritPlaceholderExpansion(MeritService meritService, TeamService teamService, ConfigManager configManager) {
        this.meritService = meritService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "entrenched";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Flintstqne";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        // This expansion should persist through reloads
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) {
            return "";
        }

        // Get player merit data
        Optional<PlayerMeritData> dataOpt = meritService.getPlayerData(offlinePlayer.getUniqueId());

        // For players without data, return defaults
        if (dataOpt.isEmpty()) {
            return getDefaultValue(params);
        }

        PlayerMeritData data = dataOpt.get();
        MeritRank rank = data.getRank();
        MeritRank nextRank = rank.getNextRank();

        return switch (params.toLowerCase()) {
            // Rank info
            case "rank" -> rank.getDisplayName();
            case "rank_tag" -> rank.getTag();
            case "rank_formatted" -> rank.getFormattedTag();
            case "rank_color" -> rank.getColor().toString();

            // Token/Merit counts
            case "tokens" -> String.valueOf(data.tokenBalance());
            case "merits" -> String.valueOf(data.receivedMerits());
            case "merits_today" -> String.valueOf(data.receivedToday());

            // Progression
            case "next_rank" -> nextRank != null ? nextRank.getDisplayName() : "Max Rank";
            case "merits_to_next" -> String.valueOf(data.getMeritsToNextRank());
            case "progress" -> {
                if (nextRank == null) {
                    yield "100";
                }
                int current = data.receivedMerits();
                int required = nextRank.getMeritsRequired();
                int previous = rank.getMeritsRequired();
                int progress = required > previous ?
                    (int) (((double)(current - previous) / (required - previous)) * 100) : 100;
                yield String.valueOf(Math.min(100, Math.max(0, progress)));
            }

            // Lifetime stats
            case "kills" -> String.valueOf(data.lifetimeKills());
            case "captures" -> String.valueOf(data.lifetimeCaptures());
            case "road_blocks" -> String.valueOf(data.lifetimeRoadBlocks());
            case "rounds" -> String.valueOf(data.roundsCompleted());
            case "playtime" -> formatPlaytime(data.playtimeMinutes());
            case "playtime_hours" -> String.valueOf(data.playtimeMinutes() / 60);
            case "playtime_minutes" -> String.valueOf(data.playtimeMinutes());
            case "streak" -> String.valueOf(data.loginStreak());

            // Given/earned stats
            case "tokens_earned" -> String.valueOf(data.lifetimeTokensEarned());
            case "merits_given" -> String.valueOf(data.lifetimeMeritsGiven());
            case "tokens_today" -> String.valueOf(data.tokensEarnedToday());
            case "given_today" -> String.valueOf(data.meritsGivenToday());

            // Prefixes for chat/tab plugins
            case "prefix" -> rank.getFormattedTag();
            case "team_prefix" -> {
                String teamColor = getTeamColor(offlinePlayer);
                yield rank.getFormattedTag() + " " + teamColor;
            }
            case "full_prefix" -> {
                String teamColor = getTeamColor(offlinePlayer);
                String divisionTag = getDivisionTag(offlinePlayer);
                if (divisionTag != null && !divisionTag.isEmpty()) {
                    yield rank.getFormattedTag() + " " + teamColor + divisionTag + " ";
                }
                yield rank.getFormattedTag() + " " + teamColor;
            }

            // Rank checks (for permission/condition plugins)
            case "is_officer" -> String.valueOf(rank.isOfficer());
            case "is_general" -> String.valueOf(rank.isGeneral());
            case "is_nco" -> String.valueOf(rank.isNCO());
            case "rank_level" -> String.valueOf(rank.ordinal());

            default -> null;
        };
    }

    /**
     * Returns default values for players without merit data.
     */
    private String getDefaultValue(String params) {
        return switch (params.toLowerCase()) {
            case "rank" -> MeritRank.RECRUIT.getDisplayName();
            case "rank_tag" -> MeritRank.RECRUIT.getTag();
            case "rank_formatted" -> MeritRank.RECRUIT.getFormattedTag();
            case "rank_color" -> MeritRank.RECRUIT.getColor().toString();
            case "tokens", "merits", "merits_today", "kills", "captures",
                 "road_blocks", "rounds", "streak", "tokens_earned",
                 "merits_given", "tokens_today", "given_today", "playtime_hours",
                 "playtime_minutes", "rank_level" -> "0";
            case "next_rank" -> MeritRank.PRIVATE.getDisplayName();
            case "merits_to_next" -> String.valueOf(MeritRank.PRIVATE.getMeritsRequired());
            case "progress" -> "0";
            case "playtime" -> "0m";
            case "prefix" -> MeritRank.RECRUIT.getFormattedTag();
            case "team_prefix", "full_prefix" -> MeritRank.RECRUIT.getFormattedTag() + " §7";
            case "is_officer", "is_general", "is_nco" -> "false";
            default -> "";
        };
    }

    /**
     * Gets the team color code for a player.
     */
    private String getTeamColor(OfflinePlayer player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isPresent()) {
            String team = teamOpt.get();
            if (team.equalsIgnoreCase("red")) {
                return "§c"; // Red
            } else if (team.equalsIgnoreCase("blue")) {
                return "§9"; // Blue
            }
        }
        return "§7"; // Gray for no team
    }

    /**
     * Gets the division tag for a player (if they have one).
     * Returns empty string if no division.
     */
    private String getDivisionTag(OfflinePlayer player) {
        // This would need DivisionService integration
        // For now, return empty - can be extended later
        return "";
    }

    /**
     * Formats playtime nicely.
     */
    private String formatPlaytime(int minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        int hours = minutes / 60;
        int mins = minutes % 60;
        if (hours < 24) {
            return hours + "h " + mins + "m";
        }
        int days = hours / 24;
        hours = hours % 24;
        return days + "d " + hours + "h";
    }
}

