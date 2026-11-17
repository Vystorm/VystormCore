package Vystorm.vystorm_core.schedule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class TaskGroupTest {
    @Test
    void givenTwoGroups_whenOneIsCancelled_thenOnlyItsTasksStopAndClosedGroupRejectsNewTasks() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<BukkitTask> created = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> {
            BukkitTask task = mock(BukkitTask.class);
            created.add(task);
            return task;
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TaskGroup a = TaskGroup.of(plugin, "a"), b = TaskGroup.of(plugin, "b");
            a.now(() -> {});
            a.now(() -> {});
            b.now(() -> {});
            a.cancel();
            verify(created.get(0)).cancel();
            verify(created.get(1)).cancel();
            verify(created.get(2), never()).cancel();
            b.close();
            verify(created.get(2)).cancel();
            assertThrows(IllegalStateException.class, () -> b.now(() -> {}));
        }
    }

    @Test
    void givenConcurrentCancelAndClose_whenRacing_thenEveryTaskCancelledExactlyOnce() throws Exception {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<BukkitTask> created = java.util.Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.atomic.AtomicInteger> cancels = java.util.Collections.synchronizedList(new ArrayList<>());
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(i -> {
            BukkitTask task = mock(BukkitTask.class);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(x -> { count.incrementAndGet(); return null; }).when(task).cancel();
            created.add(task);
            cancels.add(count);
            return task;
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            for (int round = 0; round < 50; round++) {
                TaskGroup g = TaskGroup.of(plugin, "race");
                for (int i = 0; i < 20; i++) g.timer(1, 1, () -> {});
                var start = new java.util.concurrent.CountDownLatch(1);
                Thread a = new Thread(() -> { await(start); g.cancel(); });
                Thread b = new Thread(() -> { await(start); g.close(); });
                a.start();
                b.start();
                start.countDown();
                a.join();
                b.join();
                assertEquals(0, g.size());
            }
            for (var count : cancels) assertEquals(1, count.get(), "cancelled exactly once");
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    void givenFailingTask_whenRunRepeatedly_thenWarningIsThrottled() {
        Plugin plugin = mock(Plugin.class);
        Logger logger = Logger.getLogger("taskgroup-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        List<LogRecord> records = new ArrayList<>();
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() {}
            public void close() {}
        });
        when(plugin.getLogger()).thenReturn(logger);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> runnables = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { runnables.add(i.getArgument(1)); return mock(BukkitTask.class); });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TaskGroup group = TaskGroup.of(plugin, "g");
            group.now(() -> { throw new IllegalStateException("boom"); });
            for (int i = 0; i < 5; i++) runnables.getFirst().run();
            assertEquals(1, records.size());
        }
    }

    @Test
    void givenOneShotTask_whenItRan_thenGroupForgetsItButKeepsTimers() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> runnables = new ArrayList<>();
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(i -> { runnables.add(i.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(i -> mock(BukkitTask.class));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TaskGroup group = TaskGroup.of(plugin, "g");
            BukkitTask timer = group.timer(1, 1, () -> {});
            group.later(5, () -> {});
            assertEquals(2, group.size());
            runnables.getFirst().run();
            assertEquals(1, group.size());
            group.cancel();
            verify(timer).cancel();
        }
    }

    @Test
    void givenOneShotThatRanBeforeItWasTracked_whenScheduled_thenItIsNotTracked() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TaskGroup group = TaskGroup.of(plugin, "g");
            group.async(() -> {});
            assertEquals(0, group.size());
        }
    }

    @Test
    void givenPluginWithOwnServer_whenScheduling_thenItsSchedulerIsUsedAndNullTasksAreTolerated() {
        Plugin plugin = mock(Plugin.class);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        TaskGroup group = TaskGroup.of(plugin, "g");
        assertNull(group.now(() -> {}));
        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
        assertEquals(0, group.size());
    }
}
