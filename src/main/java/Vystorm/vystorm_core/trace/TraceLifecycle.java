package Vystorm.vystorm_core.trace;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Starts/stops the trace tools with Core; a traced player who leaves stops being traced.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class TraceLifecycle implements Listener {
    private TraceLifecycle() {}

    public static void start(Plugin core) {
        MessageSource.configure(core.getConfig().getBoolean("messages.show-source", true));
        core.getServer().getPluginManager().registerEvents(new TraceLifecycle(), core);
    }

    public static void stop() { EventTrace.stopAll(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { EventTrace.forget(event.getPlayer()); }
}
