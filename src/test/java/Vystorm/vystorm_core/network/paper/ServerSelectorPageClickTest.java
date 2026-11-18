package Vystorm.vystorm_core.network.paper;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

/** The server selector recognises its own page without asking a chest in the world for its holder. */
class ServerSelectorPageClickTest {
    private final ServerSelectorPage listener = new ServerSelectorPage(List.of());

    private static Inventory ownPage() {
        Inventory inventory = mock(Inventory.class);   // a created inventory: no location
        ServerSelectorPage page = new ServerSelectorPage(List.of());
        when(inventory.getHolder()).thenReturn(page);
        return inventory;
    }
    private static Inventory chestInTheWorld() {
        Inventory chest = mock(Inventory.class);
        when(chest.getLocation()).thenReturn(mock(Location.class));
        return chest;
    }
    private static InventoryClickEvent click(Inventory top) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(top);
        when(event.getClickedInventory()).thenReturn(top);
        when(event.getWhoClicked()).thenReturn(mock(Player.class));
        when(event.getRawSlot()).thenReturn(0);
        return event;
    }
    private static InventoryDragEvent drag(Inventory top) {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getInventory()).thenReturn(top);
        return event;
    }

    @Test void givenOwnPageWhenClickedThenCancelled() {
        InventoryClickEvent event = click(ownPage());

        listener.onClick(event);

        verify(event).setCancelled(true);
    }

    @Test void givenOwnPageWhenDraggedThenCancelled() {
        InventoryDragEvent event = drag(ownPage());

        listener.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test void givenChestInTheWorldWhenClickedOrDraggedThenIgnoredWithoutAskingForItsHolder() {
        Inventory chest = chestInTheWorld();
        InventoryClickEvent click = click(chest);
        InventoryDragEvent drag = drag(chest);

        listener.onClick(click);
        listener.onDrag(drag);

        verify(chest, never()).getHolder();
        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
    }

    @Test void givenMenuOfAnotherPluginWhenClickedOrDraggedThenIgnored() {
        Inventory other = mock(Inventory.class);
        InventoryHolder foreign = mock(InventoryHolder.class);
        when(other.getHolder()).thenReturn(foreign);
        InventoryClickEvent click = click(other);
        InventoryDragEvent drag = drag(other);

        listener.onClick(click);
        listener.onDrag(drag);

        verify(click, never()).setCancelled(anyBoolean());
        verify(drag, never()).setCancelled(anyBoolean());
    }
}
