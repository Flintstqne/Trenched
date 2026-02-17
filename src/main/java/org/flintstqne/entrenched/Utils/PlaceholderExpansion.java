package org.flintstqne.entrenched.Utils;

import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.DivisionLogic.Division;
import org.flintstqne.entrenched.DivisionLogic.DivisionMember;
import org.flintstqne.entrenched.DivisionLogic.DivisionRole;
import org.flintstqne.entrenched.DivisionLogic.DivisionService;
import org.flintstqne.entrenched.MeritLogic.MeritRank;
import org.flintstqne.entrenched.MeritLogic.MeritService;
import org.flintstqne.entrenched.MeritLogic.PlayerMeritData;
import org.flintstqne.entrenched.TeamLogic.TeamService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * PlaceholderAPI expansion for the merit and division systems.
 *
 * Available placeholders:
 *
 * === RANK PLACEHOLDERS ===
 * - %entrenched_rank% - Full rank name (e.g., "Private First Class")
 * - %entrenched_rank_tag% - Rank tag (e.g., "PFC")
 * - %entrenched_rank_formatted% - Formatted tag with color (e.g., "§7[PFC]§r")
 * - %entrenched_rank_color% - Rank color code (e.g., "§7")
 *
 * === TOKEN/MERIT PLACEHOLDERS ===
 * - %entrenched_tokens% - Current token balance
 * - %entrenched_merits% - Total received merits (determines rank)
 * - %entrenched_merits_today% - Merits received today
 * - %entrenched_next_rank% - Next rank name
 * - %entrenched_merits_to_next% - Merits needed for next rank
 * - %entrenched_progress% - Progress to next rank as percentage
 *
 * === STAT PLACEHOLDERS ===
 * - %entrenched_kills% - Lifetime kills
 * - %entrenched_captures% - Lifetime region captures
 * - %entrenched_playtime% - Playtime formatted
 * - %entrenched_streak% - Login streak in days
 *
 * === DIVISION PLACEHOLDERS ===
 * - %entrenched_division% - Division name (or empty if none)
 * - %entrenched_division_tag% - Division tag (e.g., "1ST")
 * - %entrenched_division_formatted% - Formatted tag with team color (e.g., "§c[1ST]")
 * - %entrenched_division_role% - Player's role in division (COMMANDER/OFFICER/MEMBER)
 * - %entrenched_division_role_short% - Short role (CMD/OFF/MEM)
 * - %entrenched_division_description% - Division description
 * - %entrenched_division_members% - Member count
 * - %entrenched_division_id% - Division ID (internal)
 * - %entrenched_has_division% - true/false if player has a division
 * - %entrenched_is_commander% - true/false if player is division commander
 * - %entrenched_is_officer% - true/false if player is officer+
 *
 * === PREFIX PLACEHOLDERS ===
 * - %entrenched_prefix% - Rank tag only
 * - %entrenched_team_prefix% - Rank + team color
 * - %entrenched_full_prefix% - Division + rank tag with team color: [DIV] [RANK]
 * - %entrenched_chat_prefix% - Full prefix for chat: [DIV] [RANK]
 * - %entrenched_name_prefix% - For tab/nametag: [DIV] [RANK] TEAMCOLOR
 * - %entrenched_chat_format% - Full configurable chat format from config.yml (use with {message})
 */
public class PlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {

    private final MeritService meritService;
    private final TeamService teamService;
    private final DivisionService divisionService;
    private final ConfigManager configManager;

    public PlaceholderExpansion(MeritService meritService, TeamService teamService,
                                DivisionService divisionService, ConfigManager configManager) {
        this.meritService = meritService;
        this.teamService = teamService;
        this.divisionService = divisionService;
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

        // Get division data
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(offlinePlayer.getUniqueId());
        Optional<DivisionMember> membershipOpt = divisionService.getMembership(offlinePlayer.getUniqueId());

        // For players without merit data, use defaults for merit placeholders
        PlayerMeritData data = dataOpt.orElse(null);
        MeritRank rank = data != null ? data.getRank() : MeritRank.RECRUIT;
        MeritRank nextRank = rank.getNextRank();

        return switch (params.toLowerCase()) {
            // ==================== RANK INFO ====================
            case "rank" -> rank.getDisplayName();
            case "rank_tag" -> rank.getTag();
            case "rank_formatted" -> rank.getFormattedTag();
            case "rank_color" -> rank.getColor().toString();

            // ==================== TOKEN/MERIT COUNTS ====================
            case "tokens" -> data != null ? String.valueOf(data.tokenBalance()) : "0";
            case "merits" -> data != null ? String.valueOf(data.receivedMerits()) : "0";
            case "merits_today" -> data != null ? String.valueOf(data.receivedToday()) : "0";

            // ==================== PROGRESSION ====================
            case "next_rank" -> nextRank != null ? nextRank.getDisplayName() : "Max Rank";
            case "merits_to_next" -> data != null ? String.valueOf(data.getMeritsToNextRank()) :
                    String.valueOf(MeritRank.PRIVATE.getMeritsRequired());
            case "progress" -> {
                if (data == null) yield "0";
                if (nextRank == null) yield "100";
                int current = data.receivedMerits();
                int required = nextRank.getMeritsRequired();
                int previous = rank.getMeritsRequired();
                int progress = required > previous ?
                    (int) (((double)(current - previous) / (required - previous)) * 100) : 100;
                yield String.valueOf(Math.min(100, Math.max(0, progress)));
            }

            // ==================== LIFETIME STATS ====================
            case "kills" -> data != null ? String.valueOf(data.lifetimeKills()) : "0";
            case "captures" -> data != null ? String.valueOf(data.lifetimeCaptures()) : "0";
            case "road_blocks" -> data != null ? String.valueOf(data.lifetimeRoadBlocks()) : "0";
            case "rounds" -> data != null ? String.valueOf(data.roundsCompleted()) : "0";
            case "playtime" -> data != null ? formatPlaytime(data.playtimeMinutes()) : "0m";
            case "playtime_hours" -> data != null ? String.valueOf(data.playtimeMinutes() / 60) : "0";
            case "playtime_minutes" -> data != null ? String.valueOf(data.playtimeMinutes()) : "0";
            case "streak" -> data != null ? String.valueOf(data.loginStreak()) : "0";

            // ==================== GIVEN/EARNED STATS ====================
            case "tokens_earned" -> data != null ? String.valueOf(data.lifetimeTokensEarned()) : "0";
            case "merits_given" -> data != null ? String.valueOf(data.lifetimeMeritsGiven()) : "0";
            case "tokens_today" -> data != null ? String.valueOf(data.tokensEarnedToday()) : "0";
            case "given_today" -> data != null ? String.valueOf(data.meritsGivenToday()) : "0";

            // ==================== DIVISION INFO ====================
            case "division" -> divisionOpt.map(Division::name).orElse("");
            case "division_tag" -> divisionOpt.map(Division::tag).orElse("");
            case "division_formatted" -> {
                if (divisionOpt.isEmpty()) yield "";
                Division div = divisionOpt.get();
                String teamColor = getTeamColorCode(div.team());
                yield teamColor + "[" + div.tag() + "]" + ChatColor.RESET;
            }
            case "division_name" -> divisionOpt.map(Division::name).orElse("");
            case "division_description" -> divisionOpt.map(Division::description).orElse("");
            case "division_id" -> divisionOpt.map(d -> String.valueOf(d.divisionId())).orElse("");
            case "division_team" -> divisionOpt.map(Division::team).orElse("");

            // Division role
            case "division_role" -> membershipOpt.map(m -> m.role().name()).orElse("");
            case "division_role_short" -> membershipOpt.map(m -> switch (m.role()) {
                case COMMANDER -> "CMD";
                case OFFICER -> "OFF";
                case MEMBER -> "MEM";
            }).orElse("");
            case "division_role_display" -> membershipOpt.map(m -> switch (m.role()) {
                case COMMANDER -> "Commander";
                case OFFICER -> "Officer";
                case MEMBER -> "Member";
            }).orElse("");

            // Division member count
            case "division_members" -> {
                if (divisionOpt.isEmpty()) yield "0";
                List<DivisionMember> members = divisionService.getMembers(divisionOpt.get().divisionId());
                yield String.valueOf(members.size());
            }

            // Boolean checks for divisions
            case "has_division" -> String.valueOf(divisionOpt.isPresent());
            case "is_commander" -> String.valueOf(membershipOpt.map(m -> m.role() == DivisionRole.COMMANDER).orElse(false));
            case "is_div_officer" -> String.valueOf(membershipOpt.map(m -> m.role().canManageMembers()).orElse(false));

            // ==================== RANK BOOLEAN CHECKS ====================
            case "is_officer" -> String.valueOf(rank.isOfficer());
            case "is_general" -> String.valueOf(rank.isGeneral());
            case "is_nco" -> String.valueOf(rank.isNCO());
            case "rank_level" -> String.valueOf(rank.ordinal());

            // ==================== PREFIXES FOR CHAT/TAB ====================
            case "prefix" -> rank.getFormattedTag();
            case "team_prefix" -> {
                String teamColor = getTeamColor(offlinePlayer);
                yield rank.getFormattedTag() + " " + teamColor;
            }
            case "division_prefix" -> {
                if (divisionOpt.isEmpty()) yield "";
                Division div = divisionOpt.get();
                String teamColor = getTeamColorCode(div.team());
                yield teamColor + "[" + div.tag() + "]" + ChatColor.RESET;
            }
            case "full_prefix" -> {
                String teamColor = getTeamColor(offlinePlayer);
                if (divisionOpt.isPresent()) {
                    Division div = divisionOpt.get();
                    yield teamColor + "[" + div.tag() + "] " + rank.getFormattedTag() + " ";
                }
                yield rank.getFormattedTag() + " " + teamColor;
            }
            case "chat_prefix" -> {
                // Full chat prefix: [DIV] [RANK] with colors
                StringBuilder sb = new StringBuilder();
                if (divisionOpt.isPresent()) {
                    Division div = divisionOpt.get();
                    String teamColor = getTeamColorCode(div.team());
                    sb.append(teamColor).append("[").append(div.tag()).append("]").append(ChatColor.RESET).append(" ");
                }
                sb.append(rank.getFormattedTag());
                sb.append(" ");
                yield sb.toString();
            }
            case "name_prefix" -> {
                // For tab/nametag: [DIV] [RANK] TEAMCOLOR
                String teamColor = getTeamColor(offlinePlayer);
                if (divisionOpt.isPresent()) {
                    Division div = divisionOpt.get();
                    yield getTeamColorCode(div.team()) + "[" + div.tag() + "] " + rank.getFormattedTag() + " " + teamColor;
                }
                yield rank.getFormattedTag() + " " + teamColor;
            }

            // ==================== CONFIGURABLE CHAT FORMAT ====================
            case "chat_format" -> {
                // Returns the appropriate chat format based on player's division/team status
                // This is meant to be used with a message - returns format template
                String teamColorCode = getTeamColor(offlinePlayer);
                String format;

                if (divisionOpt.isPresent()) {
                    format = configManager.getChatFormatWithDivision();
                    Division div = divisionOpt.get();
                    format = format.replace("{division}", getTeamColorCode(div.team()) + "[" + div.tag() + "]" + ChatColor.RESET);
                    format = format.replace("{division_name}", div.name());
                    format = format.replace("{division_tag}", div.tag());
                } else if (teamService.getPlayerTeam(offlinePlayer.getUniqueId()).isPresent()) {
                    format = configManager.getChatFormatWithoutDivision();
                    format = format.replace("{division}", "");
                    format = format.replace("{division_name}", "");
                    format = format.replace("{division_tag}", "");
                } else {
                    format = configManager.getChatFormatNoTeam();
                    format = format.replace("{division}", "");
                    format = format.replace("{division_name}", "");
                    format = format.replace("{division_tag}", "");
                }

                // Replace common placeholders
                format = format.replace("{rank}", rank.getFormattedTag());
                format = format.replace("{rank_name}", rank.getDisplayName());
                format = format.replace("{rank_tag}", rank.getTag());
                format = format.replace("{team_color}", teamColorCode);
                format = format.replace("{player}", offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown");

                // Convert & color codes to §
                format = translateColors(format);

                // Remove {message} placeholder - that will be filled by the chat system
                // Keep it as a marker for where the message goes
                yield format;
            }

            default -> null;
        };
    }

    /**
     * Translates & color codes to § color codes.
     */
    private String translateColors(String text) {
        if (text == null) return "";
        return text.replace("&0", "§0")
                   .replace("&1", "§1")
                   .replace("&2", "§2")
                   .replace("&3", "§3")
                   .replace("&4", "§4")
                   .replace("&5", "§5")
                   .replace("&6", "§6")
                   .replace("&7", "§7")
                   .replace("&8", "§8")
                   .replace("&9", "§9")
                   .replace("&a", "§a")
                   .replace("&b", "§b")
                   .replace("&c", "§c")
                   .replace("&d", "§d")
                   .replace("&e", "§e")
                   .replace("&f", "§f")
                   .replace("&k", "§k")
                   .replace("&l", "§l")
                   .replace("&m", "§m")
                   .replace("&n", "§n")
                   .replace("&o", "§o")
                   .replace("&r", "§r")
                   .replace("&A", "§a")
                   .replace("&B", "§b")
                   .replace("&C", "§c")
                   .replace("&D", "§d")
                   .replace("&E", "§e")
                   .replace("&F", "§f")
                   .replace("&K", "§k")
                   .replace("&L", "§l")
                   .replace("&M", "§m")
                   .replace("&N", "§n")
                   .replace("&O", "§o")
                   .replace("&R", "§r");
    }

    /**
     * Gets the team color code for a player.
     */
    private String getTeamColor(OfflinePlayer player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isPresent()) {
            return getTeamColorCode(teamOpt.get());
        }
        return "§7"; // Gray for no team
    }

    /**
     * Gets the color code for a team name.
     */
    private String getTeamColorCode(String team) {
        if (team == null) return "§7";
        if (team.equalsIgnoreCase("red")) {
            return "§c"; // Red
        } else if (team.equalsIgnoreCase("blue")) {
            return "§9"; // Blue
        }
        return "§7"; // Gray for unknown
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

