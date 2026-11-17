package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.main;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Zugang zum Mob-Service ohne Abhängigkeit auf Vystorm-Mobs. Genau ein Provider (Vystorm-Mobs) registriert sich;
 * ohne Provider ist {@link #service()} leer und Verbraucher fallen auf Vanilla/Legacy zurück.
 * <p>Preferred access: {@code api.mobs()}.
 */
public final class MobServices {
    private record Registration(Plugin owner, MobService service) {}

    private static final AtomicReference<Registration> PROVIDER = new AtomicReference<>();
    private static final AtomicBoolean LIFECYCLE = new AtomicBoolean();

    private MobServices() {}

    public static void register(Plugin owner, MobService service) {
        if (owner == null || service == null) throw new IllegalArgumentException("owner/service missing");
        Registration previous = PROVIDER.getAndSet(new Registration(owner, service));
        if (previous != null) ProviderFailures.replaced("MobServices", "Mob-Service", previous.owner(), owner);
        ensureLifecycle();
    }

    public static void unregister(Plugin owner) {
        PROVIDER.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current);
    }

    public static boolean available() { return PROVIDER.get() != null; }
    /** Name of the currently enabled provider for diagnostics. @since 0.26.5 */
    public static Optional<String> providerName() {
        Registration r = PROVIDER.get();
        return r == null || !r.owner.isEnabled() ? Optional.empty() : Optional.of(r.owner.getName());
    }

    public static Optional<MobService> service() {
        Registration registration = PROVIDER.get();
        return registration == null ? Optional.empty() : Optional.of(registration.service());
    }

    /**
     * Feuert ein Mob-Event (vom Provider aufzurufen). Auf dem Server-Thread synchron; von anderen Threads wird es auf
     * den nächsten Tick verschoben. Cancellable Requests (z. B. {@code MobSpawnRequestEvent}) müssen daher auf dem
     * Server-Thread gefeuert werden, sonst sieht der Aufrufer weder Cancel noch geänderte Werte (wird geloggt).
     */
    public static void fire(Event event) {
        if (Bukkit.isPrimaryThread() || event.isAsynchronous()) { Bukkit.getPluginManager().callEvent(event);return; }
        if (event instanceof org.bukkit.event.Cancellable)
            ProviderFailures.report("MobServices", event.getEventName(), "fire off-thread",
                    new IllegalStateException("Cancellable event fired off the server thread; cancel/changes are not visible to the caller"));
        Plugin core = main.getPlugin();
        if (core != null && core.isEnabled()) Bukkit.getScheduler().runTask(core, () -> Bukkit.getPluginManager().callEvent(event));
    }

    private static void ensureLifecycle() {
        if (LIFECYCLE.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !LIFECYCLE.compareAndSet(false, true)) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDisable(PluginDisableEvent event) {
                unregister(event.getPlugin());
                RelationServices.unregisterAll(event.getPlugin());
                MobContextServices.unregisterAll(event.getPlugin());
                Vystorm.vystorm_core.mob.extension.MobExtensions.unregisterAll(event.getPlugin());
                Vystorm.vystorm_core.item.ItemRefs.unregisterAll(event.getPlugin());
            }
        }, core);
    }
}
