package Vystorm.vystorm_core.compat;

import org.bukkit.inventory.Inventory;
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
    private static final class LegacyAccess implements Access {
        public Inventory top(InventoryView view) { return view.getTopInventory(); }
        public Inventory bottom(InventoryView view) { return view.getBottomInventory(); }
    }
}
