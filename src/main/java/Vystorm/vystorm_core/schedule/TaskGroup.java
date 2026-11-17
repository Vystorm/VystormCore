package Vystorm.vystorm_core.schedule;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Gruppe von Scheduler-Tasks eines Plugin-Teils. {@link #cancel()} beendet nur die eigenen Tasks (nie alle Tasks des
 * Plugins). Fehler in einem Task werden abgefangen und höchstens alle 30 Sekunden geloggt; der Task läuft weiter.
 * <p>Preferred access: {@code api.tasks()}.
 */
public final class TaskGroup implements AutoCloseable {
    private final Plugin plugin;private final String name;
    private final Set<BukkitTask> tasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile long warnAfter;private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger tracked = new java.util.concurrent.atomic.AtomicInteger();

    private TaskGroup(Plugin plugin, String name) { this.plugin = plugin;this.name = name; }

    public static TaskGroup of(Plugin plugin, String name) { return new TaskGroup(plugin, name); }

    public BukkitTask timer(long delayTicks, long periodTicks, Runnable action) { return track(scheduler().runTaskTimer(plugin, guard(action), delayTicks, periodTicks), null); }
    public BukkitTask later(long delayTicks, Runnable action) { OneShot shot = new OneShot();return track(scheduler().runTaskLater(plugin, shot.wrap(guard(action)), delayTicks), shot); }
    public BukkitTask now(Runnable action) { OneShot shot = new OneShot();return track(scheduler().runTask(plugin, shot.wrap(guard(action))), shot); }
    public BukkitTask async(Runnable action) { OneShot shot = new OneShot();return track(scheduler().runTaskAsynchronously(plugin, shot.wrap(guard(action))), shot); }
    public BukkitTask asyncTimer(long delayTicks, long periodTicks, Runnable action) { return track(scheduler().runTaskTimerAsynchronously(plugin, guard(action), delayTicks, periodTicks), null); }

    /**
     * Beendet alle Tasks dieser Gruppe. Die Gruppe bleibt danach nutzbar. Jeder Task wird genau einmal abgebrochen – von
     * dem Aufruf, der ihn aus der Gruppe nimmt –, auch wenn {@code cancel}/{@code close} gleichzeitig aus mehreren
     * Threads kommen; ein währenddessen eingetragener Task geht nicht verloren.
     */
    public void cancel() { for (BukkitTask task : Set.copyOf(tasks)) if (tasks.remove(task)) task.cancel(); }

    public int size() { tasks.removeIf(BukkitTask::isCancelled); return tasks.size(); }

    @Override public void close() { closed = true; cancel(); }

    /** Scheduler des Plugin-Servers (auch für Tests mit eigenem {@code Server}), sonst der globale. */
    private BukkitScheduler scheduler() {
        Server server = plugin.getServer();
        BukkitScheduler own = server == null ? null : server.getScheduler();
        return own != null ? own : Bukkit.getScheduler();
    }

    private BukkitTask track(BukkitTask task, OneShot shot) {
        if (task == null) return null;
        if (closed) { task.cancel(); throw new IllegalStateException("Task group " + name + " is closed"); }
        // Abgebrochene Tasks nur gelegentlich aussortieren, damit viele kurze Tasks nicht jedes Mal die ganze Menge prüfen.
        if ((tracked.incrementAndGet() & 63) == 0) tasks.removeIf(BukkitTask::isCancelled);
        // Einmal-Tasks gelten nach dem Lauf nicht als abgebrochen; sie tragen sich deshalb selbst aus.
        if (shot == null || shot.attach(task)) {
            tasks.add(task);
            // close() kann zwischen der Prüfung oben und dem Eintragen gelaufen sein: dann hier abbrechen (genau einmal).
            if (closed) {
                if (tasks.remove(task)) task.cancel();
                throw new IllegalStateException("Task group " + name + " is closed");
            }
        }
        return task;
    }

    /** Entfernt einen Einmal-Task nach seinem Lauf aus der Gruppe, auch wenn er vor dem Eintragen schon lief. */
    private final class OneShot {
        private BukkitTask task;private boolean done;

        Runnable wrap(Runnable action) {
            return () -> {
                try { action.run(); }
                finally { finish(); }
            };
        }

        synchronized boolean attach(BukkitTask scheduled) { task = scheduled;return !done; }

        private void finish() {
            BukkitTask ran;
            synchronized (this) { done = true;ran = task; }
            if (ran != null) tasks.remove(ran);
        }
    }

    private Runnable guard(Runnable action) {
        return () -> {
            try { action.run(); }
            catch (RuntimeException | LinkageError failure) {
                long now = System.currentTimeMillis();
                if (now < warnAfter) return;
                warnAfter = now + 30_000L;
                plugin.getLogger().log(Level.WARNING, "Task in " + name + " failed", failure);
            }
        };
    }
}
