package Vystorm.vystorm_core._uiMethods.autoGui;

import org.bukkit.event.inventory.*;

/** Menu actions run on the server thread. Override click/drag only for intentional transfers. */
public interface GuiCallbacks {
    default void click(InventoryClickEvent event) { event.setCancelled(true); }
    default void drag(InventoryDragEvent event) { event.setCancelled(true); }
    default void close(InventoryCloseEvent event) {}
    /** Cleanup when an inventory was created but its open was rejected or it was explicitly released. */
    default void discarded(org.bukkit.inventory.Inventory inventory) {}
}
