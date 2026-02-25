package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.flintstqne.entrenched.ConfigManager;
import org.flintstqne.entrenched.RegionLogic.RegionService;
import org.flintstqne.entrenched.TeamLogic.TeamService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all Division Depot events:
 * - Block placement (validate and register depot)
 * - Block breaking (owner vs enemy logic)
 * - Player interaction (open storage or handle enemy interaction)
 * - Inventory close (save storage contents)
 */
public class DepotListener implements Listener {

    private final JavaPlugin plugin;
    private final DepotService depotService;
    private final DivisionService divisionService;
    private final TeamService teamService;
    private final RegionService regionService;
    private final ConfigManager configManager;
    private final DepotItem depotItem;
    private DepotParticleManager particleManager;

    // Track players who are currently viewing depot storage (for save on close)
    private final Map<UUID, Integer> playersViewingDepot = new ConcurrentHashMap<>();

    // Cooldown for interaction messages to prevent spam
    private final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 2000; // 2 seconds

    public DepotListener(JavaPlugin plugin, DepotService depotService, DivisionService divisionService,
                         TeamService teamService, RegionService regionService, ConfigManager configManager,
                         DepotItem depotItem) {
        this.plugin = plugin;
        this.depotService = depotService;
        this.divisionService = divisionService;
        this.teamService = teamService;
        this.regionService = regionService;
        this.configManager = configManager;
        this.depotItem = depotItem;
    }

    /**
     * Sets the particle manager for visual effects.
     */
    public void setParticleManager(DepotParticleManager particleManager) {
        this.particleManager = particleManager;
    }

    // ==================== BLOCK PLACEMENT ====================

    /**
     * Handles Division Depot block placement.
     * Validates the placement and registers the depot in the database.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();

        // Check if this is a depot block
        if (!depotItem.isDepotBlock(item)) {
            return;
        }

        Player player = event.getPlayer();
        Location location = event.getBlock().getLocation();

        // Attempt to place the depot
        DepotService.PlaceResult result = depotService.placeDepot(player, location);

        switch (result) {
            case SUCCESS:
                // Get current depot count for the division
                int currentCount = depotService.getDepotsForDivision(
                        divisionService.getPlayerDivision(player.getUniqueId())
                                .map(Division::divisionId).orElse(-1)).size();
                int maxDepots = depotService.getMaxDepotsPerDivision();

                // Depot placed successfully with limit info
                sendMessage(player, ChatColor.GREEN + "Division Depot placed! " +
                        ChatColor.GRAY + "(" + currentCount + "/" + maxDepots + " depots)");
                sendMessage(player, ChatColor.GRAY + "All division members can access shared storage here.");

                // Show placement particle effect
                if (particleManager != null) {
                    String team = teamService.getPlayerTeam(player.getUniqueId()).orElse("red");
                    particleManager.showPlacementEffect(location, team);
                }

                // Notify division members
                notifyDivisionOfPlacement(player, location);

                plugin.getLogger().info("[Depot] " + player.getName() + " placed depot at " +
                        location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
                break;

            case NO_DIVISION:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "You must be in a division to place a depot!");
                break;

            case INSUFFICIENT_RANK:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "Only Officers and Commanders can place division depots!");
                break;

            case LIMIT_REACHED:
                event.setCancelled(true);
                int max = depotService.getMaxDepotsPerDivision();
                sendMessage(player, ChatColor.RED + "Your division has reached the maximum depot limit! " +
                        ChatColor.GRAY + "(" + max + "/" + max + ")");
                sendMessage(player, ChatColor.GRAY + "Remove an existing depot to place a new one.");
                break;

            case TOO_CLOSE_TO_OTHER_DEPOT:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "Too close to another depot! " +
                        ChatColor.GRAY + "Minimum distance: " + depotService.getMinDistanceBetweenDepots() + " blocks.");
                break;

            case INVALID_REGION:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "You cannot place a depot here!");
                break;

            case ENEMY_TERRITORY:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "You cannot place a depot in enemy territory!");
                break;

            case NOT_ENABLED:
                event.setCancelled(true);
                sendMessage(player, ChatColor.RED + "Division depots are not enabled on this server.");
                break;
        }
    }

    // ==================== BLOCK BREAKING ====================

    /**
     * Handles Division Depot block breaking.
     * - Same team can break their own depots
     * - Enemies must use raid tool on vulnerable depots
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Check if this is a depot location
        Optional<DepotLocation> depotOpt = depotService.getDepotAt(block.getLocation());
        if (depotOpt.isEmpty()) {
            return;
        }

        DepotLocation depot = depotOpt.get();
        Player player = event.getPlayer();

        // Get player's team
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) {
            event.setCancelled(true);
            sendMessage(player, ChatColor.RED + "You must be on a team to interact with depots.");
            return;
        }

        String playerTeam = playerTeamOpt.get();

        // Get depot's team
        Optional<Division> depotDivisionOpt = divisionService.getDivision(depot.divisionId());
        if (depotDivisionOpt.isEmpty()) {
            // Division no longer exists - allow breaking and clean up
            depotService.breakDepot(player, block.getLocation());
            return;
        }

        String depotTeam = depotDivisionOpt.get().team();

        // Same team - allow breaking
        if (playerTeam.equalsIgnoreCase(depotTeam)) {
            boolean success = depotService.breakDepot(player, block.getLocation());
            if (success) {
                sendMessage(player, ChatColor.YELLOW + "Division Depot removed.");
            }
            return;
        }

        // Enemy team - must use raid tool on vulnerable depot
        event.setCancelled(true);

        if (depotService.isDepotVulnerable(depot)) {
            sendMessage(player, ChatColor.RED + "This depot is vulnerable! " +
                    ChatColor.YELLOW + "Use a Raid Tool to loot it.");
        } else {
            sendMessage(player, ChatColor.RED + "You cannot break enemy depots in their territory!");
        }
    }

    // ==================== PLAYER INTERACTION ====================

    /**
     * Handles player interaction with depot blocks.
     * - Own division: Open shared storage
     * - Same team, different division: Deny access
     * - Enemy (not vulnerable): Open own division storage
     * - Enemy (vulnerable): Prompt to use raid tool
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click on block
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Ignore off-hand interactions to prevent double-firing
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != depotItem.getDepotMaterial()) {
            return;
        }

        // Check if this is a depot location
        Optional<DepotLocation> depotOpt = depotService.getDepotAt(block.getLocation());
        if (depotOpt.isEmpty()) {
            return;
        }

        // This is a depot - cancel normal interaction
        event.setCancelled(true);

        DepotLocation depot = depotOpt.get();
        Player player = event.getPlayer();

        // Check if player is holding raid tool
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (depotItem.isRaidTool(heldItem)) {
            handleRaidToolInteraction(player, depot, block.getLocation());
            return;
        }

        // Handle normal interaction
        handleDepotInteraction(player, depot);
    }

    /**
     * Handles normal depot interaction (opening storage).
     *
     * Division Depots work like Ender Chests:
     * - ANY player with a division can use ANY depot block
     * - The depot opens THEIR OWN division's inventory
     * - It doesn't matter who placed the depot or which team it belongs to
     * - Exception: Vulnerable enemy depots can be raided with a Raid Tool
     */
    private void handleDepotInteraction(Player player, DepotLocation depot) {
        // Get player's division
        Optional<Division> playerDivisionOpt = divisionService.getPlayerDivision(player.getUniqueId());

        // Player must have a division to use any depot
        if (playerDivisionOpt.isEmpty()) {
            sendMessage(player, ChatColor.RED + "You must be in a division to use depots.");
            return;
        }

        // Get depot's division (for vulnerability check)
        Optional<Division> depotDivisionOpt = divisionService.getDivision(depot.divisionId());
        if (depotDivisionOpt.isEmpty()) {
            // Depot's division no longer exists - still allow access to own storage
            openDepotStorage(player);
            return;
        }

        Division depotDivision = depotDivisionOpt.get();
        Division playerDivision = playerDivisionOpt.get();

        // Check if this is an enemy depot
        boolean isEnemyDepot = !depotDivision.team().equalsIgnoreCase(playerDivision.team());

        // If enemy depot is vulnerable, remind player to use raid tool
        if (isEnemyDepot && depotService.isDepotVulnerable(depot)) {
            sendMessage(player, ChatColor.YELLOW + "This enemy depot is vulnerable! " +
                    ChatColor.GRAY + "Use a Raid Tool to loot their storage, or access your own storage now.");
        }

        // Open the player's own division storage (regardless of whose depot this is)
        openDepotStorage(player);
    }

    /**
     * Handles raid tool interaction with depot.
     */
    private void handleRaidToolInteraction(Player player, DepotLocation depot, Location depotLocation) {
        // Get player's team
        Optional<String> playerTeamOpt = teamService.getPlayerTeam(player.getUniqueId());
        if (playerTeamOpt.isEmpty()) {
            sendMessage(player, ChatColor.RED + "You must be on a team to raid depots.");
            return;
        }

        String playerTeam = playerTeamOpt.get();

        // Get depot's team
        Optional<Division> depotDivisionOpt = divisionService.getDivision(depot.divisionId());
        if (depotDivisionOpt.isEmpty()) {
            sendMessage(player, ChatColor.RED + "This depot's division no longer exists.");
            return;
        }

        String depotTeam = depotDivisionOpt.get().team();

        // Can't raid own team's depots
        if (playerTeam.equalsIgnoreCase(depotTeam)) {
            sendMessage(player, ChatColor.RED + "You cannot raid your own team's depots!");
            return;
        }

        // Start raid
        DepotService.RaidResult result = depotService.startRaid(player, depotLocation);

        switch (result) {
            case SUCCESS:
                // Raid started - begin channeling
                startRaidChannel(player, depot, depotLocation);
                break;

            case NOT_VULNERABLE:
                sendMessage(player, ChatColor.RED + "This depot is not vulnerable! " +
                        ChatColor.GRAY + "Capture this region first.");
                break;

            case WRONG_TEAM:
                sendMessage(player, ChatColor.RED + "You cannot raid your own team's depots!");
                break;

            case ALREADY_RAIDING:
                sendMessage(player, ChatColor.RED + "Someone else is already raiding this depot!");
                break;

            case ON_COOLDOWN:
                sendMessage(player, ChatColor.RED + "This division's depots were recently raided. " +
                        "Please wait before raiding again.");
                break;

            case NO_DEPOT:
                sendMessage(player, ChatColor.RED + "No depot found at this location.");
                break;

            case NO_TOOL:
                sendMessage(player, ChatColor.RED + "You need a Raid Tool to raid depots!");
                break;

            case NOT_ENABLED:
                sendMessage(player, ChatColor.RED + "Division depots are not enabled.");
                break;

            default:
                sendMessage(player, ChatColor.RED + "Failed to start raid.");
                break;
        }
    }

    /**
     * Starts the raid channeling process.
     */
    private void startRaidChannel(Player player, DepotLocation depot, Location depotLocation) {
        int channelSeconds = depotService.getRaidChannelSeconds();

        sendMessage(player, ChatColor.YELLOW + "⚔ Raiding depot... " +
                ChatColor.GRAY + "(" + channelSeconds + " seconds, don't move!)");

        // Store starting location for movement check
        Location startLocation = player.getLocation().clone();

        // Schedule the raid completion
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Check if player moved too far
            if (player.getLocation().distance(startLocation) > 2.0) {
                depotService.cancelRaid(player);
                sendMessage(player, ChatColor.RED + "Raid cancelled - you moved!");
                return;
            }

            // Check if player is still online
            if (!player.isOnline()) {
                depotService.cancelRaid(player);
                return;
            }

            // Check if player still has raid tool
            if (!depotItem.isRaidTool(player.getInventory().getItemInMainHand())) {
                depotService.cancelRaid(player);
                sendMessage(player, ChatColor.RED + "Raid cancelled - raid tool no longer equipped!");
                return;
            }

            // Complete the raid
            DepotService.RaidResult result = depotService.completeRaid(player, depotLocation);

            if (result == DepotService.RaidResult.SUCCESS) {
                int lootCount = depotService.getLastRaidDropCount(player.getUniqueId());
                sendMessage(player, ChatColor.GREEN + "⚔ Raid successful! " +
                        ChatColor.YELLOW + lootCount + " items dropped!");

                // Show raid particle effect
                if (particleManager != null) {
                    particleManager.showRaidEffect(depotLocation);
                }

                // Remove the depot block
                depotLocation.getBlock().setType(Material.AIR);
            } else {
                sendMessage(player, ChatColor.RED + "Raid failed: " + result.name());
            }
        }, channelSeconds * 20L); // Convert seconds to ticks

        // Send countdown titles
        for (int i = channelSeconds; i > 0; i--) {
            final int secondsLeft = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && depotService.isRaiding(player)) {
                    player.sendTitle(
                            ChatColor.RED + "⚔ RAIDING ⚔",
                            ChatColor.YELLOW + String.valueOf(secondsLeft) + " seconds remaining",
                            0, 25, 5
                    );
                }
            }, (channelSeconds - i) * 20L);
        }
    }

    /**
     * Opens the depot storage for a player.
     */
    private void openDepotStorage(Player player) {
        Inventory inventory = depotService.openDepotStorage(player);

        if (inventory == null) {
            sendMessage(player, ChatColor.RED + "You don't have a division!");
            return;
        }

        // Track that this player is viewing depot storage
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(player.getUniqueId());
        divisionOpt.ifPresent(division -> playersViewingDepot.put(player.getUniqueId(), division.divisionId()));

        player.openInventory(inventory);
    }

    // ==================== INVENTORY CLOSE ====================

    /**
     * Handles inventory close to save depot storage contents.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        // Check if this was a depot inventory
        if (!depotService.isDepotInventory(event.getInventory())) {
            return;
        }

        // Save the inventory contents
        depotService.saveDepotInventory(player, event.getInventory());

        // Remove from tracking
        playersViewingDepot.remove(player.getUniqueId());

        plugin.getLogger().fine("[Depot] Saved depot storage for " + player.getName());
    }

    // ==================== HELPER METHODS ====================

    /**
     * Notifies division members when a depot is placed.
     */
    private void notifyDivisionOfPlacement(Player placer, Location location) {
        Optional<Division> divisionOpt = divisionService.getPlayerDivision(placer.getUniqueId());
        if (divisionOpt.isEmpty()) return;

        Division division = divisionOpt.get();
        String regionId = regionService.getRegionIdForLocation(location.getBlockX(), location.getBlockZ());

        for (DivisionMember member : divisionService.getMembers(division.divisionId())) {
            if (member.playerUuid().equals(placer.getUniqueId().toString())) continue;

            Player memberPlayer = plugin.getServer().getPlayer(UUID.fromString(member.playerUuid()));
            if (memberPlayer != null && memberPlayer.isOnline()) {
                memberPlayer.sendMessage(configManager.getPrefix() + ChatColor.GREEN +
                        placer.getName() + " placed a Division Depot in " + regionId + "!");
            }
        }
    }

    /**
     * Sends a message to a player with cooldown to prevent spam.
     */
    private void sendMessage(Player player, String message) {
        long now = System.currentTimeMillis();
        Long lastMessage = messageCooldowns.get(player.getUniqueId());

        // Always send important messages (success, raid, etc.)
        if (message.contains("successful") || message.contains("Raiding") || message.contains("placed")) {
            player.sendMessage(configManager.getPrefix() + message);
            messageCooldowns.put(player.getUniqueId(), now);
            return;
        }

        // Apply cooldown for repeated messages
        if (lastMessage == null || (now - lastMessage) > MESSAGE_COOLDOWN_MS) {
            player.sendMessage(configManager.getPrefix() + message);
            messageCooldowns.put(player.getUniqueId(), now);
        }
    }

    /**
     * Clears cached data for a player (call on quit).
     */
    public void clearPlayerData(UUID playerId) {
        playersViewingDepot.remove(playerId);
        messageCooldowns.remove(playerId);
    }
}

