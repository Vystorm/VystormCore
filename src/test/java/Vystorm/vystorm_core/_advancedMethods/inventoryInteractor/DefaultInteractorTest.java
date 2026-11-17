package Vystorm.vystorm_core._advancedMethods.inventoryInteractor;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTInterface;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultInteractorTest {
    private InteractorEntry entry(String key, int priority, Player player) {
        InteractorEntry entry = mock(InteractorEntry.class);
        when(entry.getNBT()).thenReturn(key);
        when(entry.priority()).thenReturn(priority);
        when(entry.canProcess(player)).thenReturn(true);
        return entry;
    }

    @Test
    void shiftClickUsesPriorityFilteringAndBlockingWithoutCallingOrdinaryClick() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            NBTInterface nbt = mock(NBTInterface.class);
            mainMock.when(main::getApi).thenReturn(api);
            when(api.nbt()).thenReturn(nbt);
            Player player = mock(Player.class);
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            when(nbt.getAllNBT(item)).thenReturn(new HashMap<>(Map.of("high", "H", "low", "L")));
            when(nbt.getNBT(item, "High")).thenReturn("H");
            when(nbt.getNBT(item, "low")).thenReturn("L");
            DefaultInteractor handler = new DefaultInteractor();
            InteractorEntry high = entry("High", 20, player);
            InteractorEntry low = entry("low", 10, player);
            handler.register(low);
            handler.register(high);
            assertSame(high, handler.getByName("HIGH"));
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getCurrentItem()).thenReturn(item);
            when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
            when(high.blocksOthers()).thenReturn(true);
            assertTrue(handler.handle(event));
            verify(high).shiftLeftClick(player, event, "H");
            verify(high, never()).leftClick(any(), any(), any());
            verify(low, never()).shiftLeftClick(any(), any(), any());
            when(high.canProcess(player)).thenReturn(false);
            assertTrue(handler.handle(event));
            verify(low).shiftLeftClick(player, event, "L");
            when(event.isCancelled()).thenReturn(true);
            clearInvocations(high, low);
            assertFalse(handler.handle(event));
            verifyNoInteractions(high, low);
        }
    }

    @Test
    void dragDoesNotExecuteSameActionForBothCursorSnapshots() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            NBTInterface nbt = mock(NBTInterface.class);
            mainMock.when(main::getApi).thenReturn(api);
            when(api.nbt()).thenReturn(nbt);
            Player player = mock(Player.class);
            ItemStack item = mock(ItemStack.class);
            when(item.hasItemMeta()).thenReturn(true);
            when(nbt.getAllNBT(item)).thenReturn(new HashMap<>(Map.of("action", "value")));
            DefaultInteractor handler = new DefaultInteractor();
            InteractorEntry action = entry("action", 10, player);
            handler.register(action);
            InventoryDragEvent event = mock(InventoryDragEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getOldCursor()).thenReturn(item);
            when(event.getCursor()).thenReturn(item);
            assertTrue(handler.handle(event));
            verify(action).dragEventOld(event, action);
            verify(action, never()).dragEventNew(event);
        }
    }

    @Test
    void tagAccessUsesCurrentApiOverride() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            mainMock.when(main::getApi).thenReturn(api);
            NBTInterface first = mock(NBTInterface.class);
            NBTInterface replacement = mock(NBTInterface.class);
            when(api.nbt()).thenReturn(first, replacement);
            ItemStack item = mock(ItemStack.class);
            DefaultInteractor handler = new DefaultInteractor();
            handler.setNBTforItem(item, "action", "first");
            handler.setNBTforItem(item, "action", "second");
            verify(first).setNBT(item, "action", "first");
            verify(replacement).setNBT(item, "action", "second");
        }
    }
}
