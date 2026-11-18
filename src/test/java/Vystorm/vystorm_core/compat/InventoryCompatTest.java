package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

class InventoryCompatTest {
    @Test void givenCreatedInventoryWhenMenuHolderThenItsHolderIsReturned() {
        Inventory inventory = mock(Inventory.class);
        InventoryHolder holder = mock(InventoryHolder.class);
        when(inventory.getHolder()).thenReturn(holder);

        assertSame(holder, InventoryCompat.menuHolder(inventory));
        assertSame(holder, InventoryCompatInterface.DEFAULT.menuHolder(inventory));
        verify(inventory, times(2)).getHolder();
    }

    @Test void givenInventoryWithLocationWhenMenuHolderThenNullAndHolderIsNeverAsked() {
        Inventory chest = mock(Inventory.class);
        when(chest.getLocation()).thenReturn(mock(Location.class));
        when(chest.getHolder()).thenReturn(mock(InventoryHolder.class));

        assertNull(InventoryCompat.menuHolder(chest));
        verify(chest, never()).getHolder();
    }

    @Test void givenNoInventoryOrNoHolderWhenMenuHolderThenNull() {
        assertNull(InventoryCompat.menuHolder(null));
        assertNull(InventoryCompat.menuHolder(mock(Inventory.class)));
    }
}
