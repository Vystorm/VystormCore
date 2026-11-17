package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClickActionsTest {
    @Test void givenSingleUseCallbackWhenReplayedThenActionRunsOnlyOnceAndCommandIsConsumed() {
        Plugin core=mock(Plugin.class),provider=mock(Plugin.class);when(core.isEnabled()).thenReturn(true);when(provider.isEnabled()).thenReturn(true);
        PluginManager manager=mock(PluginManager.class);BukkitScheduler scheduler=mock(BukkitScheduler.class);
        Player player=mock(Player.class);AtomicInteger calls=new AtomicInteger();
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ClickActions.initialize(core);ArgumentCaptor<Listener> listener=ArgumentCaptor.forClass(Listener.class);
            verify(manager).registerEvents(listener.capture(),eq(core));
            var command=ClickActions.callback(provider,p->calls.incrementAndGet(),1,Duration.ofMinutes(1)).value();
            var first=new PlayerCommandPreprocessEvent(player,command,java.util.Set.of());var second=new PlayerCommandPreprocessEvent(player,command,java.util.Set.of());
            ((ClickActions)listener.getValue()).command(first);((ClickActions)listener.getValue()).command(second);
            assertTrue(first.isCancelled());assertTrue(second.isCancelled());assertEquals(1,calls.get());
        } finally { ClickActions.shutdown(); }
    }
    @Test void givenDisabledOrReleasedOwnerWhenClickedThenCallbackCannotExecute() {
        Plugin core=mock(Plugin.class),provider=mock(Plugin.class);when(core.isEnabled()).thenReturn(true);when(provider.isEnabled()).thenReturn(true);
        PluginManager manager=mock(PluginManager.class);BukkitScheduler scheduler=mock(BukkitScheduler.class);AtomicInteger calls=new AtomicInteger();
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ClickActions.initialize(core);ArgumentCaptor<Listener> listener=ArgumentCaptor.forClass(Listener.class);
            verify(manager).registerEvents(listener.capture(),eq(core));
            for(boolean disabled:new boolean[]{false,true}) {
                when(provider.isEnabled()).thenReturn(true);
                String command=ClickActions.callback(provider,p->calls.incrementAndGet(),-1,Duration.ofMinutes(1)).value();
                if(disabled)when(provider.isEnabled()).thenReturn(false);else ClickActions.release(provider);
                ((ClickActions)listener.getValue()).command(new PlayerCommandPreprocessEvent(mock(Player.class),command,java.util.Set.of()));
            }
            assertEquals(0,calls.get());
        } finally { ClickActions.shutdown(); }
    }
}
