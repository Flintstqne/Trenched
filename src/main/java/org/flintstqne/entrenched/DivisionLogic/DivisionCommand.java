package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class DivisionCommand implements CommandExecutor, TabCompleter {

    private final DivisionService divisionService;
    private final TeamService teamService;
    private final ConfigManager configManager;

    // Track pending division creations awaiting /confirm
    private final Map<UUID, PendingDivisionCreation> pendingCreations = new ConcurrentHashMap<>();

    // How long a pending creation is valid (30 seconds)
    private static final long PENDING_EXPIRY_MS = 30000;

    public DivisionCommand(DivisionService divisionService, TeamService teamService, ConfigManager configManager) {
        this.divisionService = divisionService;
        this.teamService = teamService;
        this.configManager = configManager;
    }

    /**
     * Record to track pending division creation awaiting confirmation.
     */
    private record PendingDivisionCreation(String name, String tag, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > PENDING_EXPIRY_MS;
        }
    }

    /**
     * Check if a player has a pending division creation.
     */
    public boolean hasPendingCreation(UUID uuid) {
        PendingDivisionCreation pending = pendingCreations.get(uuid);
        if (pending == null) return false;
        if (pending.isExpired()) {
            pendingCreations.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * Process confirmation for pending division creation.
     * Called by ConfirmCommand.
     */
    public boolean confirmCreation(Player player) {
        UUID uuid = player.getUniqueId();
        PendingDivisionCreation pending = pendingCreations.remove(uuid);

        if (pending == null || pending.isExpired()) {
            player.sendMessage(configManager.getPrefix() + ChatColor.RED + "No pending division creation to confirm.");
            return false;
        }

        // Check if resource cost is enabled
        if (configManager.isDivisionCreationCostEnabled() && !player.isOp()) {
            String materialName = configManager.getDivisionCreationMaterial();
            int amount = configManager.getDivisionCreationAmount();

            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "Invalid material configured. Contact an admin.");
                return false;
            }

            // Check if player is holding the required items
            ItemStack heldItem = player.getInventory().getItemInMainHand();
            if (heldItem.getType() != material || heldItem.getAmount() < amount) {
                player.sendMessage(configManager.getPrefix() + ChatColor.RED + "You must be holding " + amount + "x " +
                        formatMaterialName(material) + " to create a division.");
                return false;
            }

            // Remove the items from the player's hand
            if (heldItem.getAmount() == amount) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                heldItem.setAmount(heldItem.getAmount() - amount);
            }

            player.sendMessage(configManager.getPrefix() + ChatColor.YELLOW + "Consumed " + amount + "x " +
                    formatMaterialName(material) + ".");
        }

        // Now actually create the division
        boolean bypassCooldown = player.isOp();
        DivisionService.CreateResult result = divisionService.createDivision(uuid, pending.name(), pending.tag(), bypassCooldown);

        String prefix = configManager.getPrefix();
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.GREEN + "Division '" + pending.name() + "' [" + pending.tag() + "] created!");
                player.sendMessage(prefix + ChatColor.GRAY + "You are now the Division Commander.");
                return true;
            }
            case NAME_TAKEN -> player.sendMessage(prefix + ChatColor.RED + "A division with that name already exists.");
            case TAG_TAKEN -> player.sendMessage(prefix + ChatColor.RED + "A division with that tag already exists.");
            case TEAM_LIMIT_REACHED -> player.sendMessage(prefix + ChatColor.RED + "Your team has reached the maximum number of divisions.");
            case ON_COOLDOWN -> {
                long remaining = divisionService.getCreationCooldownRemaining(uuid);
                player.sendMessage(prefix + ChatColor.RED + "You must wait " + formatDuration(remaining) + " before creating another division.");
            }
            case NO_ACTIVE_ROUND -> player.sendMessage(prefix + ChatColor.RED + "No active round.");
            case PLAYER_NOT_ON_TEAM -> player.sendMessage(prefix + ChatColor.RED + "You must be on a team first.");
            case ALREADY_IN_DIVISION -> player.sendMessage(prefix + ChatColor.RED + "You are already in a division. Leave first with /division leave");
        }

        return false;
    }

    private String formatMaterialName(Material material) {
        return material.name().toLowerCase().replace("_", " ");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "create" -> handleCreate(player, args);
            case "join" -> handleJoin(player, args);
            case "leave" -> handleLeave(player);
            case "info" -> handleInfo(player, args);
            case "list" -> handleList(player);
            case "roster" -> handleRoster(player);
            case "invite" -> handleInvite(player, args);
            case "kick" -> handleKick(player, args);
            case "accept" -> handleAccept(player, args);
            case "deny" -> handleDeny(player, args);
            case "requests" -> handleRequests(player);
            case "promote" -> handlePromote(player, args);
            case "demote" -> handleDemote(player, args);
            case "transfer" -> handleTransfer(player, args);
            case "rename" -> handleRename(player, args);
            case "tag" -> handleTag(player, args);
            case "description", "desc" -> handleDescription(player, args);
            case "disband" -> handleDisband(player, args);
            case "waypoint", "wp" -> handleWaypoint(player, args);
            default -> {
                sendHelp(player);
                yield true;
            }
        };
    }

    private void sendHelp(Player player) {
        String prefix = configManager.getPrefix();
        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Division Commands:");
        player.sendMessage(ChatColor.GRAY + "  /division create <name> [TAG]" + ChatColor.WHITE + " - Create a division");
        player.sendMessage(ChatColor.GRAY + "  /division join <name|tag>" + ChatColor.WHITE + " - Request to join");
        player.sendMessage(ChatColor.GRAY + "  /division leave" + ChatColor.WHITE + " - Leave your division");
        player.sendMessage(ChatColor.GRAY + "  /division info [name]" + ChatColor.WHITE + " - View division info");
        player.sendMessage(ChatColor.GRAY + "  /division list" + ChatColor.WHITE + " - List all divisions");
        player.sendMessage(ChatColor.GRAY + "  /division roster" + ChatColor.WHITE + " - View division members");

        Optional<DivisionMember> memberOpt = divisionService.getMembership(player.getUniqueId());
        if (memberOpt.isPresent() && memberOpt.get().role().canManageMembers()) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "Officer Commands:");
            player.sendMessage(ChatColor.GRAY + "  /division invite <player>" + ChatColor.WHITE + " - Invite a player");
            player.sendMessage(ChatColor.GRAY + "  /division kick <player>" + ChatColor.WHITE + " - Kick a member");
            player.sendMessage(ChatColor.GRAY + "  /division accept <player>" + ChatColor.WHITE + " - Accept join request");
            player.sendMessage(ChatColor.GRAY + "  /division deny <player>" + ChatColor.WHITE + " - Deny join request");
            player.sendMessage(ChatColor.GRAY + "  /division requests" + ChatColor.WHITE + " - View pending requests");
            player.sendMessage(ChatColor.GRAY + "  /division waypoint set <name>" + ChatColor.WHITE + " - Set waypoint");
        }

        if (memberOpt.isPresent() && memberOpt.get().role() == DivisionRole.COMMANDER) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GOLD + "Commander Commands:");
            player.sendMessage(ChatColor.GRAY + "  /division promote <player>" + ChatColor.WHITE + " - Promote to officer");
            player.sendMessage(ChatColor.GRAY + "  /division demote <player>" + ChatColor.WHITE + " - Demote to member");
            player.sendMessage(ChatColor.GRAY + "  /division transfer <player>" + ChatColor.WHITE + " - Transfer command");
            player.sendMessage(ChatColor.GRAY + "  /division rename <name>" + ChatColor.WHITE + " - Rename division");
            player.sendMessage(ChatColor.GRAY + "  /division tag <TAG>" + ChatColor.WHITE + " - Change tag");
            player.sendMessage(ChatColor.GRAY + "  /division disband" + ChatColor.WHITE + " - Disband division");
        }
        player.sendMessage("");
    }

    private boolean handleCreate(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division create <name> [TAG]");
            return true;
        }

        String name = args[1];
        String tag = args.length >= 3 ? args[2].toUpperCase() : generateTag(name);

        if (tag.length() < 2 || tag.length() > 5) {
            player.sendMessage(prefix + ChatColor.RED + "Tag must be 2-5 characters.");
            return true;
        }

        if (!tag.matches("^[A-Z0-9]+$")) {
            player.sendMessage(prefix + ChatColor.RED + "Tag can only contain letters and numbers.");
            return true;
        }

        // Pre-validation checks (don't consume items until confirmed)
        UUID uuid = player.getUniqueId();

        // Check if already in a division
        if (divisionService.getMembership(uuid).isPresent()) {
            player.sendMessage(prefix + ChatColor.RED + "You are already in a division. Leave first with /division leave");
            return true;
        }

        // Check if on a team
        if (teamService.getPlayerTeam(uuid).isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You must be on a team first.");
            return true;
        }

        // Check cooldown (OPs bypass)
        if (!player.isOp()) {
            long remaining = divisionService.getCreationCooldownRemaining(uuid);
            if (remaining > 0) {
                player.sendMessage(prefix + ChatColor.RED + "You must wait " + formatDuration(remaining) + " before creating another division.");
                return true;
            }
        }

        // Check if resource cost is enabled and required
        if (configManager.isDivisionCreationCostEnabled() && !player.isOp()) {
            String materialName = configManager.getDivisionCreationMaterial();
            int amount = configManager.getDivisionCreationAmount();

            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage(prefix + ChatColor.RED + "Invalid material configured. Contact an admin.");
                return true;
            }

            // Store pending creation
            pendingCreations.put(uuid, new PendingDivisionCreation(name, tag, System.currentTimeMillis()));

            // Warn player about the cost
            player.sendMessage("");
            player.sendMessage(prefix + ChatColor.YELLOW + "⚠ Division Creation Cost:");
            player.sendMessage(ChatColor.GRAY + "Creating division '" + ChatColor.WHITE + name +
                    ChatColor.GRAY + "' [" + ChatColor.WHITE + tag + ChatColor.GRAY + "] requires:");
            player.sendMessage(ChatColor.RED + "  ➤ " + amount + "x " + formatMaterialName(material));
            player.sendMessage("");
            player.sendMessage(ChatColor.YELLOW + "Hold the required items and type " +
                    ChatColor.GREEN + "/confirm " + ChatColor.YELLOW + "to proceed.");
            player.sendMessage(ChatColor.GRAY + "(Expires in 30 seconds)");
            player.sendMessage("");

            return true;
        }

        // No cost required (OP or cost disabled) - create directly
        boolean bypassCooldown = player.isOp();
        DivisionService.CreateResult result = divisionService.createDivision(uuid, name, tag, bypassCooldown);

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(prefix + ChatColor.GREEN + "Division '" + name + "' [" + tag + "] created!");
                player.sendMessage(prefix + ChatColor.GRAY + "You are now the Division Commander.");
            }
            case NAME_TAKEN -> player.sendMessage(prefix + ChatColor.RED + "A division with that name already exists.");
            case TAG_TAKEN -> player.sendMessage(prefix + ChatColor.RED + "A division with that tag already exists.");
            case TEAM_LIMIT_REACHED -> player.sendMessage(prefix + ChatColor.RED + "Your team has reached the maximum number of divisions.");
            case ON_COOLDOWN -> {
                long remaining = divisionService.getCreationCooldownRemaining(uuid);
                player.sendMessage(prefix + ChatColor.RED + "You must wait " + formatDuration(remaining) + " before creating another division.");
            }
            case NO_ACTIVE_ROUND -> player.sendMessage(prefix + ChatColor.RED + "No active round.");
            case PLAYER_NOT_ON_TEAM -> player.sendMessage(prefix + ChatColor.RED + "You must be on a team first.");
            case ALREADY_IN_DIVISION -> player.sendMessage(prefix + ChatColor.RED + "You are already in a division. Leave first with /division leave");
        }

        return true;
    }

    private boolean handleJoin(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division join <name|tag>");
            return true;
        }

        DivisionService.JoinResult result = divisionService.requestJoin(player.getUniqueId(), args[1]);

        switch (result) {
            case REQUEST_SENT -> {
                Optional<Division> divOpt = divisionService.findDivision(player.getUniqueId(), args[1]);
                divOpt.ifPresent(div -> player.sendMessage(prefix + ChatColor.GREEN + "Join request sent to " + div.formattedTag() + " " + div.name()));
            }
            case ALREADY_REQUESTED -> player.sendMessage(prefix + ChatColor.YELLOW + "You already have a pending request to this division.");
            case ALREADY_IN_DIVISION -> player.sendMessage(prefix + ChatColor.RED + "You are already in a division.");
            case DIVISION_NOT_FOUND -> player.sendMessage(prefix + ChatColor.RED + "Division not found.");
            case WRONG_TEAM -> player.sendMessage(prefix + ChatColor.RED + "That division belongs to the other team.");
            case NO_ACTIVE_ROUND -> player.sendMessage(prefix + ChatColor.RED + "No active round.");
        }

        return true;
    }

    private boolean handleLeave(Player player) {
        String prefix = configManager.getPrefix();

        Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a division.");
            return true;
        }

        Division div = divOpt.get();
        boolean success = divisionService.leaveDivision(player.getUniqueId());

        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + "You have left " + div.formattedTag() + " " + div.name());
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to leave division.");
        }

        return true;
    }

    private boolean handleInfo(Player player, String[] args) {
        String prefix = configManager.getPrefix();

        Division div;
        if (args.length >= 2) {
            Optional<Division> divOpt = divisionService.findDivision(player.getUniqueId(), args[1]);
            if (divOpt.isEmpty()) {
                player.sendMessage(prefix + ChatColor.RED + "Division not found.");
                return true;
            }
            div = divOpt.get();
        } else {
            Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
            if (divOpt.isEmpty()) {
                player.sendMessage(prefix + ChatColor.RED + "You are not in a division. Specify a name: /division info <name>");
                return true;
            }
            div = divOpt.get();
        }

        List<DivisionMember> members = divisionService.getMembers(div.divisionId());
        Optional<DivisionMember> commander = members.stream().filter(m -> m.role() == DivisionRole.COMMANDER).findFirst();
        long officerCount = members.stream().filter(m -> m.role() == DivisionRole.OFFICER).count();

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "═══ " + div.formattedTag() + " " + div.name() + " ═══");
        player.sendMessage(ChatColor.GRAY + "Team: " + ChatColor.WHITE + div.team().toUpperCase());
        commander.ifPresent(c -> {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(c.playerUuid())).getName();
            player.sendMessage(ChatColor.GRAY + "Commander: " + ChatColor.WHITE + "★ " + name);
        });
        player.sendMessage(ChatColor.GRAY + "Officers: " + ChatColor.WHITE + officerCount);
        player.sendMessage(ChatColor.GRAY + "Members: " + ChatColor.WHITE + members.size());
        if (div.description() != null && !div.description().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Description: " + ChatColor.WHITE + div.description());
        }
        player.sendMessage("");

        return true;
    }

    private boolean handleList(Player player) {
        String prefix = configManager.getPrefix();

        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You must be on a team.");
            return true;
        }

        List<Division> divisions = divisionService.getDivisionsForTeam(teamOpt.get());

        if (divisions.isEmpty()) {
            player.sendMessage(prefix + ChatColor.YELLOW + "No divisions exist for your team yet.");
            return true;
        }

        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Divisions for " + teamOpt.get().toUpperCase() + " Team:");
        for (Division div : divisions) {
            int memberCount = divisionService.getMembers(div.divisionId()).size();
            player.sendMessage(ChatColor.GRAY + "  " + div.formattedTag() + " " + ChatColor.WHITE + div.name() + ChatColor.GRAY + " - " + memberCount + " members");
        }
        player.sendMessage("");

        return true;
    }

    private boolean handleRoster(Player player) {
        String prefix = configManager.getPrefix();

        Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a division.");
            return true;
        }

        Division div = divOpt.get();
        List<DivisionMember> members = divisionService.getMembers(div.divisionId());

        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + div.formattedTag() + " " + div.name() + " Roster:");

        for (DivisionMember member : members) {
            String playerName = Bukkit.getOfflinePlayer(UUID.fromString(member.playerUuid())).getName();
            String symbol = member.role().getSymbol();
            String roleColor = switch (member.role()) {
                case COMMANDER -> ChatColor.GOLD.toString();
                case OFFICER -> ChatColor.YELLOW.toString();
                case MEMBER -> ChatColor.GRAY.toString();
            };
            boolean online = Bukkit.getPlayer(UUID.fromString(member.playerUuid())) != null;
            String onlineStatus = online ? ChatColor.GREEN + "●" : ChatColor.RED + "●";
            player.sendMessage("  " + onlineStatus + " " + roleColor + symbol + " " + playerName);
        }
        player.sendMessage("");

        return true;
    }

    private boolean handleInvite(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division invite <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(prefix + ChatColor.RED + "Player not found or not online.");
            return true;
        }

        boolean success = divisionService.invitePlayer(player.getUniqueId(), target.getUniqueId());
        if (success) {
            Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
            divOpt.ifPresent(div -> {
                player.sendMessage(prefix + ChatColor.GREEN + target.getName() + " has been added to the division.");
                target.sendMessage(prefix + ChatColor.GREEN + "You have been added to " + div.formattedTag() + " " + div.name());
            });
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to invite player.");
        }
        return true;
    }

    private boolean handleKick(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division kick <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore()) {
            player.sendMessage(prefix + ChatColor.RED + "Player not found.");
            return true;
        }

        boolean success = divisionService.kickMember(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + target.getName() + " has been removed from the division.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to kick player.");
        }
        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division accept <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean success = divisionService.acceptRequest(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + target.getName() + "'s request accepted.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to accept request.");
        }
        return true;
    }

    private boolean handleDeny(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division deny <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean success = divisionService.denyRequest(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + target.getName() + "'s request denied.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to deny request.");
        }
        return true;
    }

    private boolean handleRequests(Player player) {
        String prefix = configManager.getPrefix();
        List<JoinRequest> requests = divisionService.getPendingRequests(player.getUniqueId());

        if (requests.isEmpty()) {
            player.sendMessage(prefix + ChatColor.GRAY + "No pending join requests.");
            return true;
        }

        player.sendMessage("");
        player.sendMessage(prefix + ChatColor.GOLD + "Pending Join Requests:");
        for (JoinRequest request : requests) {
            String playerName = Bukkit.getOfflinePlayer(UUID.fromString(request.playerUuid())).getName();
            player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.WHITE + playerName);
        }
        player.sendMessage(ChatColor.GRAY + "Use /division accept <player> or /division deny <player>");
        player.sendMessage("");

        return true;
    }

    private boolean handlePromote(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division promote <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean success = divisionService.promoteToOfficer(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + target.getName() + " has been promoted to Officer.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to promote.");
        }
        return true;
    }

    private boolean handleDemote(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division demote <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean success = divisionService.demoteToMember(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + target.getName() + " has been demoted to Member.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to demote.");
        }
        return true;
    }

    private boolean handleTransfer(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division transfer <player>");
            return true;
        }

        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        boolean success = divisionService.transferCommander(player.getUniqueId(), target.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + "Command transferred to " + target.getName() + ".");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to transfer command.");
        }
        return true;
    }

    private boolean handleRename(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division rename <name>");
            return true;
        }

        boolean success = divisionService.renameDivision(player.getUniqueId(), args[1]);
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + "Division renamed to '" + args[1] + "'.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to rename.");
        }
        return true;
    }

    private boolean handleTag(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division tag <TAG>");
            return true;
        }

        String newTag = args[1].toUpperCase();
        if (newTag.length() < 2 || newTag.length() > 5 || !newTag.matches("^[A-Z0-9]+$")) {
            player.sendMessage(prefix + ChatColor.RED + "Tag must be 2-5 alphanumeric characters.");
            return true;
        }

        boolean success = divisionService.changeTag(player.getUniqueId(), newTag);
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + "Tag changed to [" + newTag + "].");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to change tag.");
        }
        return true;
    }

    private boolean handleDescription(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division desc <text>");
            return true;
        }

        String description = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        boolean success = divisionService.setDescription(player.getUniqueId(), description);
        if (success) {
            player.sendMessage(prefix + ChatColor.GREEN + "Description updated.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to set description.");
        }
        return true;
    }

    private boolean handleDisband(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            player.sendMessage(prefix + ChatColor.RED + "Type /division disband confirm to proceed.");
            return true;
        }

        Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divOpt.isEmpty()) {
            player.sendMessage(prefix + ChatColor.RED + "You are not in a division.");
            return true;
        }

        Division div = divOpt.get();
        boolean success = divisionService.disbandDivision(player.getUniqueId());
        if (success) {
            player.sendMessage(prefix + ChatColor.YELLOW + div.formattedTag() + " " + div.name() + " has been disbanded.");
        } else {
            player.sendMessage(prefix + ChatColor.RED + "Failed to disband.");
        }
        return true;
    }

    private boolean handleWaypoint(Player player, String[] args) {
        String prefix = configManager.getPrefix();
        if (args.length < 2) {
            player.sendMessage(prefix + ChatColor.RED + "Usage: /division waypoint <set|remove|list> [name]");
            return true;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "set" -> {
                if (args.length < 3) {
                    player.sendMessage(prefix + ChatColor.RED + "Usage: /division waypoint set <name>");
                    return true;
                }
                boolean success = divisionService.setWaypoint(player.getUniqueId(), args[2],
                        player.getWorld().getName(), player.getLocation().getBlockX(),
                        player.getLocation().getBlockY(), player.getLocation().getBlockZ());
                if (success) {
                    player.sendMessage(prefix + ChatColor.GREEN + "Waypoint '" + args[2] + "' set.");
                } else {
                    player.sendMessage(prefix + ChatColor.RED + "Failed to set waypoint.");
                }
            }
            case "remove", "delete" -> {
                if (args.length < 3) {
                    player.sendMessage(prefix + ChatColor.RED + "Usage: /division waypoint remove <name>");
                    return true;
                }
                boolean success = divisionService.removeWaypoint(player.getUniqueId(), args[2]);
                if (success) {
                    player.sendMessage(prefix + ChatColor.YELLOW + "Waypoint '" + args[2] + "' removed.");
                } else {
                    player.sendMessage(prefix + ChatColor.RED + "Failed to remove waypoint.");
                }
            }
            case "list" -> {
                List<Waypoint> waypoints = divisionService.getWaypoints(player.getUniqueId());
                if (waypoints.isEmpty()) {
                    player.sendMessage(prefix + ChatColor.GRAY + "No waypoints set.");
                    return true;
                }
                player.sendMessage("");
                player.sendMessage(prefix + ChatColor.GOLD + "Division Waypoints:");
                for (Waypoint wp : waypoints) {
                    player.sendMessage(ChatColor.GRAY + "  • " + ChatColor.WHITE + wp.name() + ChatColor.GRAY + " - " + wp.formattedLocation());
                }
                player.sendMessage("");
            }
            default -> player.sendMessage(prefix + ChatColor.RED + "Usage: /division waypoint <set|remove|list> [name]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("create", "join", "leave", "info", "list", "roster"));
            Optional<DivisionMember> memberOpt = divisionService.getMembership(player.getUniqueId());
            if (memberOpt.isPresent() && memberOpt.get().role().canManageMembers()) {
                options.addAll(Arrays.asList("invite", "kick", "accept", "deny", "requests", "waypoint"));
            }
            if (memberOpt.isPresent() && memberOpt.get().role() == DivisionRole.COMMANDER) {
                options.addAll(Arrays.asList("promote", "demote", "transfer", "rename", "tag", "desc", "disband"));
            }
            return options.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "join", "info" -> getDivisionNames(player);
                case "invite" -> getOnlinePlayerNames(args[1]);
                case "kick", "promote", "demote", "transfer", "accept", "deny" -> getDivisionMemberNames(player, args[1]);
                case "waypoint", "wp" -> Arrays.asList("set", "remove", "list");
                case "disband" -> Collections.singletonList("confirm");
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }

    private List<String> getDivisionNames(Player player) {
        Optional<String> teamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (teamOpt.isEmpty()) return Collections.emptyList();
        return divisionService.getDivisionsForTeam(teamOpt.get()).stream()
                .flatMap(d -> java.util.stream.Stream.of(d.name(), d.tag()))
                .collect(Collectors.toList());
    }

    private List<String> getOnlinePlayerNames(String prefix) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private List<String> getDivisionMemberNames(Player player, String prefix) {
        Optional<Division> divOpt = divisionService.getPlayerDivision(player.getUniqueId());
        if (divOpt.isEmpty()) return Collections.emptyList();
        return divisionService.getMembers(divOpt.get().divisionId()).stream()
                .map(m -> Bukkit.getOfflinePlayer(UUID.fromString(m.playerUuid())).getName())
                .filter(Objects::nonNull)
                .filter(name -> name.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private String generateTag(String name) {
        String[] words = name.split("\\s+");
        if (words.length > 1) {
            StringBuilder tag = new StringBuilder();
            for (String word : words) {
                if (!word.isEmpty()) tag.append(word.charAt(0));
            }
            return tag.toString().toUpperCase();
        }
        return name.substring(0, Math.min(4, name.length())).toUpperCase();
    }

    private String formatDuration(long millis) {
        if (millis < 60000) return (millis / 1000) + "s";
        if (millis < 3600000) return (millis / 60000) + "m";
        return (millis / 3600000) + "h " + ((millis % 3600000) / 60000) + "m";
    }
}

