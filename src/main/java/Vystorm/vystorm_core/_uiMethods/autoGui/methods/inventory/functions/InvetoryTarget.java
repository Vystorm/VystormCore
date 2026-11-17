package Vystorm.vystorm_core._uiMethods.autoGui.methods.inventory.functions;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

public interface InvetoryTarget {
/**
 * Get Inventory by name and event
 * events don't have to matter, but they can matter if its player specific
 */
Inventory run(String name);
Inventory run(InventoryEvent event);
Inventory run(Player player);
Inventory run();
Inventory run(UUID uuid);

}
