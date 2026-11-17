package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.mockito.Mockito.*;

class PriorityHandlerTest {
    @Test
    void ignoresUnknownTagsDeduplicatesActionsAndHonorsBlockingPriority() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            NBTInterface nbt = mock(NBTInterface.class);
            mainMock.when(main::getApi).thenReturn(api);
            when(api.nbt()).thenReturn(nbt);
            Player player = mock(Player.class);
            Block block = mock(Block.class);
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            when(nbt.getAllNBT(player)).thenReturn(new HashMap<>(Map.of("high", "1", "low", "2", "unknown", "3")));
            when(nbt.getAllNBT(block)).thenReturn(new HashMap<>(Map.of("high", "1")));
            NBTEntity high = mock(NBTEntity.class);
            NBTEntity low = mock(NBTEntity.class);
            when(nbt.getEntityNBT("high")).thenReturn(high);
            when(nbt.getEntityNBT("low")).thenReturn(low);
            when(high.priority()).thenReturn(20);
            when(low.priority()).thenReturn(10);
            PriorityHandler handler = new PriorityHandler();
            handler.blockBreakEvent(event);
            var order = inOrder(high, low);
            order.verify(high).blockBreakEvent(event);
            order.verify(low).blockBreakEvent(event);
            verify(high, times(1)).blockBreakEvent(event);
            clearInvocations(high, low);
            when(high.blocksEvent()).thenReturn(true);
            handler.blockBreakEvent(event);
            verify(high).blockBreakEvent(event);
            verify(low, never()).blockBreakEvent(event);
        }
    }

    @Test
    void resolvesNbtOverrideOnEveryDispatch() {
        try (var mainMock = mockStatic(main.class)) {
            API api = mock(API.class);
            mainMock.when(main::getApi).thenReturn(api);
            NBTInterface first = mock(NBTInterface.class);
            NBTInterface second = mock(NBTInterface.class);
            when(api.nbt()).thenReturn(first, second);
            NBTEntity action = mock(NBTEntity.class);
            when(second.getEntityNBT("action")).thenReturn(action);
            Player player = mock(Player.class);
            var keys = new java.util.ArrayList<>(java.util.List.of("action", "action", "unknown"));
            PriorityHandler handler = new PriorityHandler();
            handler.run(keys, player);
            handler.run(keys, player);
            verify(action).runPlayer(player);
        }
    }
}
