package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class ItemInteractorListener implements org.bukkit.event.Listener {

	    @EventHandler(ignoreCancelled = true)
	    public void onItemInteract(InventoryClickEvent event) {
			  if (!main.getApi().gui().isManaged(Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView())))
                main.getApi().inventoryInteractor().handleEvent(event);
	    }
    @EventHandler(ignoreCancelled = true)
    public void onItemDrag(InventoryDragEvent event) {
        if (!main.getApi().gui().isManaged(Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView())))
                main.getApi().inventoryInteractor().handleEvent(event);
    }
}
