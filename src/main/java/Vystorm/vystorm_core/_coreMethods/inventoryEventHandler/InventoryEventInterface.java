package Vystorm.vystorm_core._coreMethods.inventoryEventHandler;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Coalesced, server-thread inventory change notifications. A subscription receives at most one
 * callback for the same player or inventory in a server tick. Callbacks run on the following tick,
 * after Bukkit has completed the triggering vanilla inventory operation.
 */
public interface InventoryEventInterface {
    void watchPlayers(Plugin owner, Consumer<Player> callback);

    void watchInventories(Plugin owner, Predicate<Inventory> accepts, Consumer<Inventory> callback);

    /** Registers an observation callback for confirmed block placement and item consumption. */
    void watchUses(Plugin owner, Consumer<InventoryUse> callback);

    /** Emits one synchronous observation only; it never moves an item or confirms a later inventory delta. */
    void observeUse(Player player, int slot, org.bukkit.inventory.ItemStack original, BooleanSupplier valid);

    /** Invalidates use observations currently being processed for this player. */
    void invalidateUses(Player player);

    /** Releases use-observation state when a player leaves the server. */
    void forgetUsePlayer(Player player);

    /** Discards only an unconfirmed use candidate for this player. */
    void discardUseIntent(Player player);

    /** Captures a same-tick bone-meal interaction candidate; this does not notify use watchers. */
    void captureUseIntent(Player player, int slot, org.bukkit.inventory.ItemStack original, BooleanSupplier valid);

    /** Confirms the current player's captured bone-meal candidate after its native USE_ITEM statistic. */
    void confirmBoneMealUse(Player player);

    void changed(Player player);

    /** Queues a player notification only when at least one signal remains valid at drain time. */
    void changed(Player player, BooleanSupplier valid);

    void changed(Inventory inventory);

    /** Queues an inventory notification only when at least one signal remains valid at drain time. */
    void changed(Inventory inventory, BooleanSupplier valid);

    /** Removes every subscription owned by the plugin and its pending notifications. */
    void removeAll(Plugin owner);

    /** Removes all subscriptions and pending notifications. */
    void clear();
}
