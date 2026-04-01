package org.flintstqne.entrenched.LinkLogic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * In-game /link command.
 * <p>
 * /link        — generates a one-time code to use in Discord
 * /unlink      — removes the Discord link
 */
public final class LinkCommand implements CommandExecutor {

    private final LinkService linkService;

    public LinkCommand(LinkService linkService) {
        this.linkService = linkService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("unlink")) {
            return handleUnlink(player);
        }

        // /link — generate code
        return handleLink(player);
    }

    private boolean handleLink(Player player) {
        LinkService.GenerateResult result = linkService.generateCode(player.getUniqueId());

        switch (result.status()) {
            case OK -> {
                String code = result.code();

                // The code itself — click copies just the code, hover explains
                Component codeComponent = Component.text(" Your code: ")
                        .color(NamedTextColor.WHITE)
                        .append(
                                Component.text(code)
                                        .color(NamedTextColor.GOLD)
                                        .decorate(TextDecoration.BOLD)
                                        .clickEvent(ClickEvent.copyToClipboard(code))
                                        .hoverEvent(HoverEvent.showText(
                                                Component.text("Click to copy code")
                                                        .color(NamedTextColor.GRAY)))
                        );

                // Discord usage line — click copies the full /link CODE command
                Component usageComponent = Component.text(" Use ")
                        .color(NamedTextColor.GRAY)
                        .append(
                                Component.text("/link " + code)
                                        .color(NamedTextColor.YELLOW)
                                        .clickEvent(ClickEvent.copyToClipboard("/link " + code))
                                        .hoverEvent(HoverEvent.showText(
                                                Component.text("Click to copy /link command")
                                                        .color(NamedTextColor.GRAY)))
                        )
                        .append(Component.text(" in Discord").color(NamedTextColor.GRAY));

                player.sendMessage(Component.empty());
                player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━").color(NamedTextColor.GREEN));
                player.sendMessage(Component.text(" Discord Link Code").color(NamedTextColor.GREEN));
                player.sendMessage(Component.empty());
                player.sendMessage(codeComponent);
                player.sendMessage(Component.empty());
                player.sendMessage(usageComponent);
                player.sendMessage(Component.text(" Expires in 1 minute — single use only!").color(NamedTextColor.RED));
                player.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━").color(NamedTextColor.GREEN));
                player.sendMessage(Component.empty());
            }
            case ALREADY_LINKED -> {
                player.sendMessage(Component.text("Your account is already linked to a Discord account.").color(NamedTextColor.RED));
                player.sendMessage(Component.text("Use ").color(NamedTextColor.GRAY)
                        .append(Component.text("/unlink").color(NamedTextColor.YELLOW))
                        .append(Component.text(" first to remove the existing link.").color(NamedTextColor.GRAY)));
            }
            case COOLDOWN -> {
                player.sendMessage(Component.text("Please wait a few seconds before generating another code.").color(NamedTextColor.RED));
            }
        }

        return true;
    }

    private boolean handleUnlink(Player player) {
        boolean removed = linkService.unlinkByMc(player.getUniqueId());
        if (removed) {
            player.sendMessage(Component.text("Your Discord link has been removed.").color(NamedTextColor.GREEN));
            player.sendMessage(Component.text("Your Discord roles from Entrenched will be removed on next sync.").color(NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("Your account is not linked to any Discord account.").color(NamedTextColor.RED));
        }
        return true;
    }
}
