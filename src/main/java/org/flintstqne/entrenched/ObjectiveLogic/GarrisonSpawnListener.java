package org.flintstqne.entrenched.ObjectiveLogic;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public class GarrisonSpawnListener implements Listener {
    private final GarrisonSpawnService garrisonSpawnService;

    public GarrisonSpawnListener(GarrisonSpawnService garrisonSpawnService) {
        this.garrisonSpawnService = garrisonSpawnService;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !garrisonSpawnService.isSpawnMap(item)) {
            return;
        }

        event.setCancelled(true);
        garrisonSpawnService.openGarrisonMenu(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!GarrisonSpawnService.GARRISON_GUI_TITLE.equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType().isAir()) {
            return;
        }

        garrisonSpawnService.handleGarrisonMenuClick(player, clickedItem);
    }
}
