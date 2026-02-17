package org.flintstqne.entrenched.MeritLogic;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.flintstqne.entrenched.ConfigManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for viewing achievements.
 * Usage:
 *   /achievements - View your achievements
 *   /achievements [category] - View achievements in a category
 *   /achievements list - List all achievements
 *   /achievements progress - Show progress summary
 */
public class AchievementCommand implements CommandExecutor, TabCompleter {

    private final MeritService meritService;
    private final ConfigManager configManager;

    public AchievementCommand(MeritService meritService, ConfigManager configManager) {
        this.meritService = meritService;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (args.length == 0) {
            showProgress(player, uuid);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "list" -> {
                showAllAchievements(player, uuid);
                yield true;
            }
            case "progress" -> {
                showProgress(player, uuid);
                yield true;
            }
            case "combat", "territory", "logistics", "social", "progression", "time", "round" -> {
                showCategory(player, uuid, subCommand);
                yield true;
            }
            default -> {
                // Try to find a matching category
                boolean found = false;
                for (String cat : Achievement.getCategories()) {
                    if (cat.equalsIgnoreCase(subCommand)) {
                        showCategory(player, uuid, cat);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    player.sendMessage(ChatColor.RED + "Unknown category: " + subCommand);
                    player.sendMessage(ChatColor.YELLOW + "Available: list, progress, " +
                            String.join(", ", Achievement.getCategories()));
                }
                yield true;
            }
        };
    }

    private void showProgress(Player player, UUID uuid) {
        Set<Achievement> unlocked = meritService.getUnlockedAchievements(uuid);
        int total = Achievement.values().length;
        int earned = unlocked.size();
        int totalTokens = unlocked.stream().mapToInt(Achievement::getTokenReward).sum();

        player.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.YELLOW + "Your Achievements" + ChatColor.GOLD + " ═══════");
        player.sendMessage(ChatColor.GRAY + "Progress: " + ChatColor.GREEN + earned + ChatColor.GRAY + "/" + ChatColor.WHITE + total +
                ChatColor.GRAY + " (" + ChatColor.YELLOW + String.format("%.1f", (earned * 100.0 / total)) + "%" + ChatColor.GRAY + ")");
        player.sendMessage(ChatColor.GRAY + "Tokens Earned: " + ChatColor.GOLD + totalTokens);
        player.sendMessage("");

        // Show progress by category
        for (String category : Achievement.getCategories()) {
            Achievement[] categoryAchievements = Achievement.getByCategory(category);
            long categoryEarned = Arrays.stream(categoryAchievements).filter(unlocked::contains).count();
            int categoryTotal = categoryAchievements.length;

            ChatColor catColor = categoryAchievements.length > 0 ? categoryAchievements[0].getColor() : ChatColor.WHITE;
            String bar = createProgressBar(categoryEarned, categoryTotal, 10);

            player.sendMessage(catColor + capitalize(category) + ChatColor.GRAY + ": " + bar + " " +
                    ChatColor.WHITE + categoryEarned + "/" + categoryTotal);
        }

        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/achievements <category>" + ChatColor.GRAY + " to view details");
        player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + "/achievements list" + ChatColor.GRAY + " to view all");
    }

    private void showCategory(Player player, UUID uuid, String category) {
        Set<Achievement> unlocked = meritService.getUnlockedAchievements(uuid);
        Achievement[] achievements = Achievement.getByCategory(category);

        if (achievements.length == 0) {
            player.sendMessage(ChatColor.RED + "No achievements found in category: " + category);
            return;
        }

        long earned = Arrays.stream(achievements).filter(unlocked::contains).count();

        player.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.YELLOW + capitalize(category) + " Achievements" +
                ChatColor.GOLD + " ═══════");
        player.sendMessage(ChatColor.GRAY + "Progress: " + ChatColor.GREEN + earned + ChatColor.GRAY + "/" +
                ChatColor.WHITE + achievements.length);
        player.sendMessage("");

        for (Achievement achievement : achievements) {
            boolean isUnlocked = unlocked.contains(achievement);
            String status = isUnlocked ? ChatColor.GREEN + "✓ " : ChatColor.DARK_GRAY + "✗ ";
            ChatColor nameColor = isUnlocked ? achievement.getColor() : ChatColor.GRAY;
            ChatColor descColor = isUnlocked ? ChatColor.WHITE : ChatColor.DARK_GRAY;

            player.sendMessage(status + nameColor + achievement.getDisplayName() +
                    ChatColor.GRAY + " - " + descColor + achievement.getDescription() +
                    ChatColor.GRAY + " [" + ChatColor.GOLD + "+" + achievement.getTokenReward() + " tokens" + ChatColor.GRAY + "]");
        }
    }

    private void showAllAchievements(Player player, UUID uuid) {
        Set<Achievement> unlocked = meritService.getUnlockedAchievements(uuid);
        int total = Achievement.values().length;
        int earned = unlocked.size();

        player.sendMessage(ChatColor.GOLD + "═══════ " + ChatColor.YELLOW + "All Achievements" + ChatColor.GOLD + " ═══════");
        player.sendMessage(ChatColor.GRAY + "Progress: " + ChatColor.GREEN + earned + ChatColor.GRAY + "/" + ChatColor.WHITE + total);
        player.sendMessage("");

        // Group by category
        for (String category : Achievement.getCategories()) {
            Achievement[] categoryAchievements = Achievement.getByCategory(category);
            if (categoryAchievements.length == 0) continue;

            ChatColor catColor = categoryAchievements[0].getColor();
            player.sendMessage(catColor + ChatColor.BOLD.toString() + capitalize(category) + ":");

            for (Achievement achievement : categoryAchievements) {
                boolean isUnlocked = unlocked.contains(achievement);
                String status = isUnlocked ? ChatColor.GREEN + "✓" : ChatColor.DARK_GRAY + "✗";
                ChatColor nameColor = isUnlocked ? achievement.getColor() : ChatColor.GRAY;

                player.sendMessage("  " + status + " " + nameColor + achievement.getDisplayName() +
                        ChatColor.DARK_GRAY + " (" + achievement.getTokenReward() + " tokens)");
            }
        }
    }

    private String createProgressBar(long current, int max, int length) {
        int filled = (int) Math.round((current * length) / (double) max);
        StringBuilder bar = new StringBuilder(ChatColor.DARK_GRAY + "[");
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                bar.append(ChatColor.GREEN).append("█");
            } else {
                bar.append(ChatColor.DARK_GRAY).append("░");
            }
        }
        bar.append(ChatColor.DARK_GRAY).append("]");
        return bar.toString();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("list");
            completions.add("progress");
            completions.addAll(Arrays.asList(Achievement.getCategories()));

            String current = args[0].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(current))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}

