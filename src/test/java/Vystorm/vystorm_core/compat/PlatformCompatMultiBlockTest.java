package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PlatformCompatMultiBlockTest {
    @Test
    void givenSpigotPlayerWithoutMultiBlockChangeWhenSendingThenSendBlockChangesInBatchesOf256() {
        Player spigot = mock(Player.class);
        doThrow(new NoSuchMethodError("sendMultiBlockChange")).when(spigot).sendMultiBlockChange(anyMap());
        List<Integer> calls = new ArrayList<>();
        doAnswer(inv -> { calls.add(((Collection<?>) inv.getArgument(0)).size()); return null; })
                .when(spigot).sendBlockChanges(anyCollection());
        World world = mock(World.class);
        BlockData glass = mock(BlockData.class);
        BlockState state = mock(BlockState.class);
        when(glass.createBlockState()).thenReturn(state);
        when(state.copy(any(Location.class))).thenReturn(state);
        Map<Location, BlockData> cells = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) cells.put(new Location(world, i % 16, 64 + i / 256, (i / 16) % 16), glass);

        PlatformCompat.sendMultiBlockChange(spigot, cells);

        assertEquals(List.of(256, 256, 88), calls);
        verify(spigot, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void givenPaperPlayerWhenSendingThenOneMultiBlockChangeCall() {
        Player paper = mock(Player.class);
        Map<Location, BlockData> cells = Map.of(new Location(mock(World.class), 1, 2, 3), mock(BlockData.class));
        PlatformCompat.sendMultiBlockChange(paper, cells);
        verify(paper).sendMultiBlockChange(cells);
        verify(paper, never()).sendBlockChanges(anyCollection());
    }
}
