package Vystorm.vystorm_core.compat;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import Vystorm.vystorm_core.compat.InventoryCompat.*;

/**
 * Manager view of {@link InventoryCompat}, reached through {@code api.compat().inventory()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link InventoryCompat}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface InventoryCompatInterface {
    /** The delegating default. Plugins use {@code api.compat().inventory()}. */
    InventoryCompatInterface DEFAULT = new InventoryCompatInterface() {};

    /** See {@link InventoryCompat#top}. */
    default Inventory top(InventoryView view) { return InventoryCompat.top(view); }

    /** See {@link InventoryCompat#bottom}. */
    default Inventory bottom(InventoryView view) { return InventoryCompat.bottom(view); }
}
