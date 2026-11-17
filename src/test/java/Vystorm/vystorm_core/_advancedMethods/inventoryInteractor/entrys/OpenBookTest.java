package Vystorm.vystorm_core._advancedMethods.inventoryInteractor.entrys;
import Vystorm.vystorm_core.main;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class OpenBookTest {
    @Test void givenWrittenBookWhenClickedThenCancelAndOpenSnapshotOnNextTick() {
        try(var b=mockStatic(Bukkit.class); var m=mockStatic(main.class)) {
            Plugin plugin=mock(Plugin.class);m.when(main::getPlugin).thenReturn(plugin);
            BukkitScheduler scheduler=mock(BukkitScheduler.class);b.when(Bukkit::getScheduler).thenReturn(scheduler);
            Player player=mock(Player.class);when(player.isOnline()).thenReturn(true);when(player.hasPermission("vcore.bookgui.help")).thenReturn(true);
            ItemStack book=mock(ItemStack.class),snapshot=mock(ItemStack.class);
            when(book.getType()).thenReturn(Material.WRITTEN_BOOK);when(book.hasItemMeta()).thenReturn(true);when(book.clone()).thenReturn(snapshot);
            InventoryClickEvent event=mock(InventoryClickEvent.class);when(event.getCurrentItem()).thenReturn(book);
            new openBook().rightClick(player,event,"help");
            verify(event).setCancelled(true);verify(player,never()).openBook(any(ItemStack.class));
            var task=org.mockito.ArgumentCaptor.forClass(Runnable.class);verify(scheduler).runTask(eq(plugin),task.capture());
            task.getValue().run();verify(player).openBook(snapshot);
        }
    }
    @Test void givenMissingPermissionWhenScheduledThenDoNotOpenBook() {
        try(var b=mockStatic(Bukkit.class);var m=mockStatic(main.class)) {
            Plugin plugin=mock(Plugin.class);m.when(main::getPlugin).thenReturn(plugin);
            BukkitScheduler scheduler=mock(BukkitScheduler.class);b.when(Bukkit::getScheduler).thenReturn(scheduler);
            Player player=mock(Player.class);when(player.isOnline()).thenReturn(true);
            ItemStack book=mock(ItemStack.class);when(book.getType()).thenReturn(Material.WRITTEN_BOOK);when(book.hasItemMeta()).thenReturn(true);when(book.clone()).thenReturn(book);
            InventoryClickEvent event=mock(InventoryClickEvent.class);
            new openBook(name->book).leftClick(player,event,"private");
            var task=org.mockito.ArgumentCaptor.forClass(Runnable.class);verify(scheduler).runTask(eq(plugin),task.capture());
            task.getValue().run();verify(player,never()).openBook(any(ItemStack.class));
        }
    }
}
