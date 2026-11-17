package Vystorm.vystorm_core.bundleclasses.baseListener;

import Vystorm.vystorm_core.main;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.event.block.Action;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

/** Observes completed inventory changes only; it never moves items, cancels events, or runs plugin business logic. */
public class BukkitInventoryEventListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        var events = main.getApi().inventoryEvents();
        if (event.getWhoClicked() instanceof Player player) events.invalidateUses(player);
        if (event.getWhoClicked() instanceof Player player) events.changed(player, () -> !event.isCancelled());
        changed(events, Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView()), event);
        changed(events, event.getClickedInventory(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        var events = main.getApi().inventoryEvents();
        if (event.getWhoClicked() instanceof Player player) events.invalidateUses(player);
        if (event.getWhoClicked() instanceof Player player) events.changed(player, () -> !event.isCancelled());
        changed(events, Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView()), event);
        changed(events, Vystorm.vystorm_core.compat.InventoryCompat.bottom(event.getView()), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        var events = main.getApi().inventoryEvents();
        if (event.getPlayer() instanceof Player player) events.changed(player);
        events.changed(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent event) {
        var events = main.getApi().inventoryEvents();
        events.changed(event.getSource(), () -> !event.isCancelled());
        changed(events, event.getDestination(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(InventoryPickupItemEvent event) {
        changed(main.getApi().inventoryEvents(), event.getInventory(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) main.getApi().inventoryEvents().changed(player, () -> !event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { main.getApi().inventoryEvents().invalidateUses(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swapHands(PlayerSwapHandItemsEvent event) { main.getApi().inventoryEvents().invalidateUses(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { main.getApi().inventoryEvents().invalidateUses(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { main.getApi().inventoryEvents().forgetUsePlayer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        observe(event.getPlayer(), event.getHand(), event.getItemInHand(), () -> !event.isCancelled() && event.canBuild());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        org.bukkit.inventory.ItemStack original = event.getItem();
        observe(event.getPlayer(), event.getHand(), original,
                () -> !event.isCancelled() && event.getItem().isSimilar(original));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void boneMealIntent(PlayerInteractEvent event) {
        main.getApi().inventoryEvents().discardUseIntent(event.getPlayer());
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getMaterial() != Material.BONE_MEAL
                || event.getHand() == null || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        org.bukkit.inventory.ItemStack original = event.getItem();
        if (original == null || original.getType() != Material.BONE_MEAL) return;
        observeIntent(event.getPlayer(), event.getHand(), original,
                () -> event.useItemInHand() != org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void boneMealStatistic(PlayerStatisticIncrementEvent event) {
        if (event.getStatistic() == Statistic.USE_ITEM && event.getMaterial() == Material.BONE_MEAL) {
            main.getApi().inventoryEvents().confirmBoneMealUse(event.getPlayer());
        }
    }

    private static void observe(Player player, EquipmentSlot hand, org.bukkit.inventory.ItemStack original,
                                java.util.function.BooleanSupplier valid) {
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        main.getApi().inventoryEvents().observeUse(player, slot, original.clone(), valid);
    }

    private static void observeIntent(Player player, EquipmentSlot hand, org.bukkit.inventory.ItemStack original,
                                      java.util.function.BooleanSupplier valid) {
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        main.getApi().inventoryEvents().captureUseIntent(player, slot, original.clone(), valid);
    }

    private static void changed(Vystorm.vystorm_core._coreMethods.inventoryEventHandler.InventoryEventInterface events,
                                Inventory inventory, org.bukkit.event.Cancellable event) {
        if (inventory != null) events.changed(inventory, () -> !event.isCancelled());
    }
}
