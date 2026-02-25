package org.flintstqne.entrenched.DivisionLogic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles registration of Division Depot crafting recipes.
 */
public class DepotRecipes {

    private final JavaPlugin plugin;
    private final DepotItem depotItem;

    private NamespacedKey depotBlockRecipeKey;
    private NamespacedKey raidToolRecipeKey;

    public DepotRecipes(JavaPlugin plugin, DepotItem depotItem) {
        this.plugin = plugin;
        this.depotItem = depotItem;
    }

    /**
     * Registers all depot-related crafting recipes.
     */
    public void registerRecipes() {
        registerDepotBlockRecipe();
        registerRaidToolRecipe();
        plugin.getLogger().info("[Depot] Crafting recipes registered");
    }

    /**
     * Unregisters all depot-related crafting recipes.
     * Call this on plugin disable.
     */
    public void unregisterRecipes() {
        if (depotBlockRecipeKey != null) {
            Bukkit.removeRecipe(depotBlockRecipeKey);
        }
        if (raidToolRecipeKey != null) {
            Bukkit.removeRecipe(raidToolRecipeKey);
        }
    }

    /**
     * Registers the Division Depot block crafting recipe.
     * The center ingredient uses the configured depot material.
     *
     * Recipe:
     *   [COPPER_BLOCK] [ENDER_CHEST] [COPPER_BLOCK]
     *   [COPPER_INGOT] [DEPOT_MAT]   [COPPER_INGOT]
     *   [COPPER_BLOCK] [IRON_BLOCK]  [COPPER_BLOCK]
     */
    private void registerDepotBlockRecipe() {
        depotBlockRecipeKey = new NamespacedKey(plugin, "division_depot_block");

        // Create the result item (generic depot block)
        ItemStack result = depotItem.createGenericDepotBlock();

        ShapedRecipe recipe = new ShapedRecipe(depotBlockRecipeKey, result);
        recipe.shape("CEC", "XOX", "CIC");

        recipe.setIngredient('C', Material.COPPER_BLOCK);
        recipe.setIngredient('E', Material.ENDER_CHEST);
        recipe.setIngredient('X', Material.COPPER_INGOT);
        recipe.setIngredient('O', depotItem.getDepotMaterial()); // Use configured material
        recipe.setIngredient('I', Material.IRON_BLOCK);

        Bukkit.addRecipe(recipe);
    }

    /**
     * Registers the Division Raid Tool crafting recipe.
     *
     * Recipe:
     *   [IRON_BLOCK]  [DIAMOND]     [IRON_BLOCK]
     *   [   STICK  ]  [GOLD_BLOCK]  [   STICK  ]
     *   [   STICK  ]  [COPPER_INGOT][   STICK  ]
     */
    private void registerRaidToolRecipe() {
        raidToolRecipeKey = new NamespacedKey(plugin, "division_raid_tool");

        // Create the result item
        ItemStack result = depotItem.createRaidTool();

        ShapedRecipe recipe = new ShapedRecipe(raidToolRecipeKey, result);
        recipe.shape("IDI", "SGS", "SCS");

        recipe.setIngredient('I', Material.IRON_BLOCK);
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('S', Material.STICK);
        recipe.setIngredient('G', Material.GOLD_BLOCK);
        recipe.setIngredient('C', Material.COPPER_INGOT);

        Bukkit.addRecipe(recipe);
    }

    /**
     * Gets the recipe key for the depot block.
     */
    public NamespacedKey getDepotBlockRecipeKey() {
        return depotBlockRecipeKey;
    }

    /**
     * Gets the recipe key for the raid tool.
     */
    public NamespacedKey getRaidToolRecipeKey() {
        return raidToolRecipeKey;
    }
}

