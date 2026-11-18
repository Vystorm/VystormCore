package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** The config menu recognises its own chest pages without asking a chest in the world for its holder. */
class ConfigMenuClickTest {
    private MockedStatic<Bukkit> bukkit;
    private BukkitScheduler scheduler;
    private Plugin core;
    private ConfigMenu menu;
    private Player player;
    private InventoryView view;

    @BeforeEach void up() {
        bukkit = mockStatic(Bukkit.class);
        scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        core = mock(Plugin.class);
        menu = new ConfigMenu(core, mock(ConfigEditorService.class));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        view = mock(InventoryView.class);
    }
    @AfterEach void down() { bukkit.close(); }

    private ConfigMenu.Holder ownMenu() {
        ConfigMenu.Holder holder = new ConfigMenu.Holder(player.getUniqueId(), null);
        holder.inventory = mock(Inventory.class);   // a created inventory: no location
        when(holder.inventory.getHolder()).thenReturn(holder);
        holder.actions.put(3, (p, right) -> {});
        when(view.getTopInventory()).thenReturn(holder.inventory);
        return holder;
    }
    private Inventory chestInTheWorld() {
        Inventory chest = mock(Inventory.class);
        when(chest.getLocation()).thenReturn(mock(Location.class));
        when(view.getTopInventory()).thenReturn(chest);
        return chest;
    }
    private InventoryClickEvent click(Inventory clicked, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getSlot()).thenReturn(slot);
        return event;
    }
    private InventoryDragEvent drag() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        return event;
    }

    @Test void givenOwnMenuWhenClickedThenCancelledAndActionScheduled() {
        ConfigMenu.Holder holder = ownMenu();
        InventoryClickEvent event = click(holder.inventory, 3);

        menu.click(event);

        verify(event).setCancelled(true);
        verify(scheduler).runTask(eq(core), any(Runnable.class));
    }

    @Test void givenOwnMenuWhenDraggedThenCancelled() {
        ownMenu();
        InventoryDragEvent event = drag();

        menu.drag(event);

        verify(event).setCancelled(true);
    }

    @Test void givenChestInTheWorldWhenClickedThenIgnoredWithoutAskingForItsHolder() {
        Inventory chest = chestInTheWorld();
        InventoryClickEvent event = click(chest, 3);

        menu.click(event);

        verify(chest, never()).getHolder();
        verify(event, never()).setCancelled(anyBoolean());
        verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    }

    @Test void givenChestInTheWorldWhenDraggedThenIgnoredWithoutAskingForItsHolder() {
        Inventory chest = chestInTheWorld();
        InventoryDragEvent event = drag();

        menu.drag(event);

        verify(chest, never()).getHolder();
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test void givenMenuOfAnotherPluginWhenClickedOrDraggedThenIgnored() {
        Inventory other = mock(Inventory.class);
        InventoryHolder foreign = mock(InventoryHolder.class);
        when(other.getHolder()).thenReturn(foreign);
        when(view.getTopInventory()).thenReturn(other);
        InventoryClickEvent click = click(other, 3);
        InventoryDragEvent drag = drag();

        menu.click(click);
        menu.drag(drag);

        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
        verify(scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
    }

    @Test void givenInventoryWithoutHolderWhenClickedThenIgnored() {
        Inventory none = mock(Inventory.class);
        when(view.getTopInventory()).thenReturn(none);
        InventoryClickEvent event = click(none, 3);

        menu.click(event);

        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test void givenOwnMenuThenHolderStillAnswersItsInventory() {
        ConfigMenu.Holder holder = ownMenu();
        assertSame(holder.inventory, holder.getInventory());
    }
}
