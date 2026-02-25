package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory class for creating Division Depot items and Raid Tools.
 * Handles NBT tag creation and validation.
 */
public class DepotItem {

    // NBT key names
    public static final String DEPOT_TYPE_KEY = "depot_type";
    public static final String DIVISION_ID_KEY = "division_id";
    public static final String TEAM_KEY = "team";
    public static final String RAID_TOOL_KEY = "raid_tool";

    // NBT values
    public static final String DEPOT_TYPE_VALUE = "division_depot";

    // Default material if config is invalid
    private static final Material DEFAULT_DEPOT_MATERIAL = Material.CHEST;

    private final JavaPlugin plugin;
    private final NamespacedKey depotTypeKey;
    private final NamespacedKey divisionIdKey;
    private final NamespacedKey teamKey;
    private final NamespacedKey raidToolKey;

    public DepotItem(JavaPlugin plugin) {
        this.plugin = plugin;
        this.depotTypeKey = new NamespacedKey(plugin, DEPOT_TYPE_KEY);
        this.divisionIdKey = new NamespacedKey(plugin, DIVISION_ID_KEY);
        this.teamKey = new NamespacedKey(plugin, TEAM_KEY);
        this.raidToolKey = new NamespacedKey(plugin, RAID_TOOL_KEY);
    }

    /**
     * Gets the depot block material from config.
     */
    public Material getDepotMaterial() {
        String materialName = plugin.getConfig().getString("division-depots.block-material", "CHEST");
        try {
            Material material = Material.valueOf(materialName.toUpperCase());
            if (material.isBlock()) {
                return material;
            }
            plugin.getLogger().warning("[Depot] Material " + materialName + " is not a block, using default CHEST");
            return DEFAULT_DEPOT_MATERIAL;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[Depot] Invalid material " + materialName + ", using default CHEST");
            return DEFAULT_DEPOT_MATERIAL;
        }
    }

    // ==================== Depot Block Creation ====================

    /**
     * Creates a Division Depot item for a specific division.
     *
     * @param division The division this depot belongs to
     * @return The depot item, or null if creation fails
     */
    @SuppressWarnings("deprecation")
    public ItemStack createDepotBlock(Division division) {
        if (division == null) {
            return null;
        }

        ItemStack item = new ItemStack(getDepotMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String team = division.team();
        ChatColor teamColor = "red".equalsIgnoreCase(team) ? ChatColor.RED : ChatColor.BLUE;

        // Set display name
        meta.setDisplayName(teamColor + "" + ChatColor.BOLD + "Division Depot " +
                ChatColor.GRAY + division.formattedTag());

        // Set lore
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Shared storage for your division.");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Place to create an access point");
        lore.add(ChatColor.YELLOW + "to your division's shared storage.");
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Division: " + ChatColor.WHITE + division.name());
        lore.add("");
        lore.add(ChatColor.RED + "⚠ Vulnerable when region is captured!");
        meta.setLore(lore);

        // Set NBT data
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(depotTypeKey, PersistentDataType.STRING, DEPOT_TYPE_VALUE);
        pdc.set(divisionIdKey, PersistentDataType.INTEGER, division.divisionId());
        pdc.set(teamKey, PersistentDataType.STRING, team);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates a generic Division Depot item (without specific division info).
     * Used for crafting - the division info is added when placed.
     *
     * @return A generic depot block item
     */
    @SuppressWarnings("deprecation")
    public ItemStack createGenericDepotBlock() {
        ItemStack item = new ItemStack(getDepotMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        // Set display name
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Division Depot");

        // Set lore
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Shared storage for your division.");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Place to create an access point");
        lore.add(ChatColor.YELLOW + "to your division's shared storage.");
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Requires division membership to place.");
        lore.add("");
        lore.add(ChatColor.RED + "⚠ Vulnerable when region is captured!");
        meta.setLore(lore);

        // Set NBT data (no division ID yet - will be set on placement)
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(depotTypeKey, PersistentDataType.STRING, DEPOT_TYPE_VALUE);

        item.setItemMeta(meta);
        return item;
    }

    // ==================== Raid Tool Creation ====================

    /**
     * Creates a Division Raid Tool item.
     *
     * @return The raid tool item
     */
    @SuppressWarnings("deprecation")
    public ItemStack createRaidTool() {
        ItemStack item = new ItemStack(Material.GOLDEN_HOE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        // Set display name
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Division Raid Tool");

        // Set lore
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Use on enemy Division Depots");
        lore.add(ChatColor.GRAY + "in captured territories to raid");
        lore.add(ChatColor.GRAY + "their division's storage.");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Right-click on vulnerable depot");
        lore.add(ChatColor.YELLOW + "to begin raiding process.");
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Raiding takes 5 seconds.");
        lore.add(ChatColor.DARK_GRAY + "Can be interrupted by damage.");
        meta.setLore(lore);

        // Set NBT data
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(raidToolKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    // ==================== Item Validation ====================

    /**
     * Checks if an item is a Division Depot block.
     *
     * @param item The item to check
     * @return true if the item is a depot block
     */
    public boolean isDepotBlock(ItemStack item) {
        if (item == null || item.getType() != getDepotMaterial()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(depotTypeKey, PersistentDataType.STRING)) {
            return false;
        }

        String depotType = pdc.get(depotTypeKey, PersistentDataType.STRING);
        return DEPOT_TYPE_VALUE.equals(depotType);
    }

    /**
     * Checks if an item is a Division Raid Tool.
     *
     * @param item The item to check
     * @return true if the item is a raid tool
     */
    public boolean isRaidTool(ItemStack item) {
        if (item == null || item.getType() != Material.GOLDEN_HOE) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(raidToolKey, PersistentDataType.BYTE)) {
            return false;
        }

        Byte value = pdc.get(raidToolKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    /**
     * Gets the division ID from a depot block item.
     *
     * @param item The depot block item
     * @return The division ID, or -1 if not found
     */
    public int getDivisionIdFromItem(ItemStack item) {
        if (!isDepotBlock(item)) {
            return -1;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return -1;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(divisionIdKey, PersistentDataType.INTEGER)) {
            return -1;
        }

        Integer divisionId = pdc.get(divisionIdKey, PersistentDataType.INTEGER);
        return divisionId != null ? divisionId : -1;
    }

    /**
     * Gets the team from a depot block item.
     *
     * @param item The depot block item
     * @return The team name, or null if not found
     */
    public String getTeamFromItem(ItemStack item) {
        if (!isDepotBlock(item)) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(teamKey, PersistentDataType.STRING);
    }

    // ==================== Getters for Keys ====================

    public NamespacedKey getDepotTypeKey() {
        return depotTypeKey;
    }

    public NamespacedKey getDivisionIdKey() {
        return divisionIdKey;
    }

    public NamespacedKey getTeamKey() {
        return teamKey;
    }

    public NamespacedKey getRaidToolKey() {
        return raidToolKey;
    }
}

