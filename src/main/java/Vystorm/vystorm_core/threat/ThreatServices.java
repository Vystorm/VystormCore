package Vystorm.vystorm_core.threat;

import Vystorm.vystorm_core.main;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Access point for the shared Core threat service.
 * <p>Preferred access: {@code api.runtime().threat()}.
 */
public final class ThreatServices {
    private static final DefaultThreatScoreService SERVICE = new DefaultThreatScoreService();
    private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean();

    private ThreatServices() {}

    public static ThreatScoreService get() {
        ensureLifecycleListener();
        return SERVICE;
    }

    /** Entfernt abgelaufene Modifier; Core ruft das jede Minute asynchron auf. @since 0.11.1 */
    public static int purgeExpired() { return SERVICE.purgeExpired(); }

    private static void ensureLifecycleListener() {
        if (LIFECYCLE_REGISTERED.get() || !Bukkit.isPrimaryThread()) {
            return;
        }
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled()) {
            return;
        }
        if (!LIFECYCLE_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                SERVICE.removeOwner(event.getPlugin());
            }
        }, core);
    }
}
