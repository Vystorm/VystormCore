package Vystorm.vystorm_core.compat;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Handles InventoryView's transition from an abstract class to an interface.
 * <p>Preferred access: {@code api.compat().inventory()}.
 */
public final class InventoryCompat {
    public interface Access {
        Inventory top(InventoryView view);
        Inventory bottom(InventoryView view);
    }
    private static final Access ACCESS = InventoryView.class.isInterface()
            ? new Vystorm.vystorm_core.compat.internal.ModernInventoryAccess() : new LegacyAccess();
    private InventoryCompat() {}
    public static Inventory top(InventoryView view) { return ACCESS.top(view); }
    public static Inventory bottom(InventoryView view) { return ACCESS.bottom(view); }

    /**
     * The holder of a menu that a plugin made with {@code Bukkit.createInventory(holder, ...)}, or null for an inventory
     * that sits in the world (chest, barrel, furnace, minecart, the player's own crafting grid ...).
     *
     * <p>Use it in click, drag, open and close handlers instead of {@code inventory.getHolder()} to find out whether an
     * inventory is your own menu: for a block container {@code getHolder()} copies the whole block state on every call,
     * while this method only asks for the location first. A created inventory has no location on Paper and on Spigot;
     * every inventory with a location belongs to a block or an entity and is never a plugin's menu.
     *
     * @since 0.64.1
     */
    public static InventoryHolder menuHolder(Inventory inventory) {
        return inventory == null || inventory.getLocation() != null ? null : inventory.getHolder();
    }
    private static final class LegacyAccess implements Access {
        public Inventory top(InventoryView view) { return view.getTopInventory(); }
        public Inventory bottom(InventoryView view) { return view.getBottomInventory(); }
    }
}
