package Vystorm.vystorm_core.compat.internal;

import Vystorm.vystorm_core.compat.InventoryCompat;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

public final class ModernInventoryAccess implements InventoryCompat.Access {
    public Inventory top(InventoryView view) { return view.getTopInventory(); }
    public Inventory bottom(InventoryView view) { return view.getBottomInventory(); }
}
