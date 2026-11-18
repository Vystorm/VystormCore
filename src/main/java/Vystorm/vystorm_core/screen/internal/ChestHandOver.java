package Vystorm.vystorm_core.screen.internal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Menus that are changing from a screen back to the real chest right now (since 0.65.0), by identity.
 * <p>
 * {@link InventoryScreens} opens a plugin's chest menu on the server and shows it as a screen. When the screen cannot be
 * shown (screens switched off, refused) or the player asks for the chest, the same menu is opened as a chest one tick
 * later. On the way the server closes it for a moment: leaving a screen teleports the player back, which closes the
 * open menu, and opening a menu closes the one that is open. That close event is not the player leaving the menu.
 * Core's managed menus ask {@link #active} and keep their session through it, so clicks stay cancelled and the
 * callbacks stay bound. Server thread only.
 */
public final class ChestHandOver {
    private static final Set<Inventory> MENUS = Collections.newSetFromMap(new IdentityHashMap<>());

    private ChestHandOver() {}

    /** Marks {@code top} as changing hands until {@link #show} has opened it as a chest. */
    public static void begin(Inventory top) { if (top != null) MENUS.add(top); }

    /** True while {@code inventory} is on its way from the screen to the real chest. */
    public static boolean active(Inventory inventory) { return inventory != null && MENUS.contains(inventory); }

    /** The last step: opens {@code top} as a chest for the player (if still online) and ends the hand-over. */
    public static void show(Player player, Inventory top) {
        try { if (player.isOnline()) player.openInventory(top); }
        finally { MENUS.remove(top); }
    }
}
