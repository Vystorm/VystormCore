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

/** Legacy Paper listener API retained for binary compatibility; Bukkit registers the portable listener. */
public final class InventoryEventListener extends BukkitInventoryEventListener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void click(InventoryClickEvent event) { super.click(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void drag(InventoryDragEvent event) { super.drag(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    @Override public void close(InventoryCloseEvent event) { super.close(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void move(InventoryMoveItemEvent event) { super.move(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void pickup(InventoryPickupItemEvent event) { super.pickup(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void playerPickup(org.bukkit.event.entity.EntityPickupItemEvent event) { super.playerPickup(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void drop(PlayerDropItemEvent event) { super.drop(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void swapHands(PlayerSwapHandItemsEvent event) { super.swapHands(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void held(PlayerItemHeldEvent event) { super.held(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    @Override public void quit(PlayerQuitEvent event) { super.quit(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void place(BlockPlaceEvent event) { super.place(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Override public void consume(PlayerItemConsumeEvent event) { super.consume(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    @Override public void boneMealIntent(PlayerInteractEvent event) { super.boneMealIntent(event); }

    @EventHandler(priority = EventPriority.MONITOR)
    @Override public void boneMealStatistic(PlayerStatisticIncrementEvent event) { super.boneMealStatistic(event); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void slotChange(io.papermc.paper.event.player.PlayerInventorySlotChangeEvent event) {
        if (!Objects.equals(event.getOldItemStack(),event.getNewItemStack()))
            main.getApi().inventoryEvents().changed(event.getPlayer());
    }
}
