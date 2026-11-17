package Vystorm.vystorm_core._uiMethods.menu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class MenuExplorerTest {
    @Test void givenHistoryAndFavorites_whenCleared_thenOnlyOwnHistoryIsRemovedAndNewVisitsStillWork() {
        Player player = mock(Player.class);
        var data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        var history = NamespacedKey.fromString("vystorm_core:pref.menu.recent");
        var favorites = NamespacedKey.fromString("vystorm_core:pref.menu.favorites");
        var values = new HashMap<NamespacedKey, String>();
        values.put(history, "gallery:album\nsnapshot:photos");
        values.put(favorites, "gallery:album");
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(i -> values.get(i.getArgument(0)));
        doAnswer(i -> { values.remove(i.getArgument(0)); return null; }).when(data).remove(any());
        doAnswer(i -> { values.put(i.getArgument(0), i.getArgument(2)); return null; }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        MenuExplorer.clearRecent(player);
        MenuExplorer.clearRecent(player);
        assertFalse(values.containsKey(history));
        assertEquals("gallery:album", values.get(favorites));
        verify(data, never()).remove(favorites);
        MenuExplorer.visited(player, "snapshot:photos");
        assertEquals("snapshot:photos", values.get(history));
        assertEquals("gallery:album", values.get(favorites));
    }
}
