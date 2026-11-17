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
 * Manager view of {@link TaskGroup}, reached through {@code api.tasks()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link TaskGroup}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface TaskGroupInterface {
    /** The delegating default. Plugins use {@code api.tasks()}. */
    TaskGroupInterface DEFAULT = new TaskGroupInterface() {};

    /** See {@link TaskGroup#of}. */
    default TaskGroup of(Plugin plugin, String name) { return TaskGroup.of(plugin, name); }
}
