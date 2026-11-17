package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types.High;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;

class HighTest {
    @Test
    void givenWorkerThreadWhenLoggingHighThenSchedulePlayerAccess() {
        try (var bukkit = mockStatic(Bukkit.class); var pluginMain = mockStatic(main.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            pluginMain.when(main::getPlugin).thenReturn(plugin);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of());
            new High().debug("[Test 1] Background failure", LocalDateTime.now());
            bukkit.verify(Bukkit::getOnlinePlayers, never());
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(plugin), task.capture());
            task.getValue().run();
            bukkit.verify(Bukkit::getOnlinePlayers);
        }
    }

    @Test
    void givenWriterThreadWhenShutdownIsRequestedThenScheduleServerAccess() {
        try (var bukkit = mockStatic(Bukkit.class); var pluginMain = mockStatic(main.class)) {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            pluginMain.when(main::getPlugin).thenReturn(plugin);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of());
            new Vystorm.vystorm_core._parsersDebuggerEtc.debugger.types.Schutdown().debug("[Test 1] Stop", LocalDateTime.now());
            bukkit.verify(Bukkit::shutdown, never());
            bukkit.verify(Bukkit::getOnlinePlayers, never());
            ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(plugin), task.capture());
            task.getValue().run();
            bukkit.verify(Bukkit::shutdown);
        }
    }
}
