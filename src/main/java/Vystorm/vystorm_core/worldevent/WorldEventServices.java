package Vystorm.vystorm_core.worldevent;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;

import Vystorm.vystorm_core.main;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Zentraler Zugang zu World-Events für alle Vystorm-Plugins, ohne Abhängigkeit auf das verwaltende Plugin.
 * Core verwaltet eigene Event-Domänen; zusätzlich kann sich eine alte externe Quelle registrieren. Ohne Quellen sind Modifikatoren 1.0.
 * Start/Ende werden als {@link WorldEventStartedEvent}/{@link WorldEventEndedEvent} über Bukkit verteilt.
 * <p>Preferred access: {@code api.events().services()}.
 */
public final class WorldEventServices {
    /** Modifikatoren werden auf diesen Bereich begrenzt, damit ein fehlerhafter Katalog nichts sprengt. */
    public static final double MIN_MODIFIER = 0.0, MAX_MODIFIER = 10.0;
    private record Registration(Plugin owner, WorldEventSource source) {}
    private static final AtomicReference<Registration> SOURCE = new AtomicReference<>();
    private static final AtomicBoolean LIFECYCLE = new AtomicBoolean();

    private WorldEventServices() {}

    /** @deprecated Use {@code api.events().domain(owner, id).source(source)}. */
    @Deprecated(since = "0.64.0")
    public static void register(Plugin owner, WorldEventSource source) {
        if (owner == null || source == null) throw new IllegalArgumentException("owner/source missing");
        Registration previous = SOURCE.getAndSet(new Registration(owner, source));
        if (previous != null) ProviderFailures.replaced("WorldEventServices", "Event-Quelle", previous.owner(), owner);
        ensureLifecycle();
    }

    /** @deprecated Use {@code api.events().release(owner)}. */
    @Deprecated(since = "0.64.0")
    public static void unregister(Plugin owner) {
        SOURCE.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current);
    }

    public static boolean available() { return WorldEvents.get().available() || SOURCE.get() != null; }

    public static List<WorldEventView> active() {
        var registration = SOURCE.get();
        if (registration == null) return WorldEvents.get().active();
        try { return combine(WorldEvents.get().active(), registration.source().active()); }
        catch (RuntimeException failure) { failed(registration, "active", failure); return WorldEvents.get().active(); }
    }

    public static List<WorldEventView> activeAt(Location location) {
        if (location == null || location.getWorld() == null) return List.of();
        return activeAt(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static List<WorldEventView> activeAt(UUID worldId, int x, int y, int z) {
        var registration = SOURCE.get();
        if (registration == null) return WorldEvents.get().activeAt(worldId, x, y, z);
        try { return combine(WorldEvents.get().activeAt(worldId, x, y, z), registration.source().activeAt(worldId, x, y, z)); }
        catch (RuntimeException failure) { failed(registration, "activeAt", failure); return WorldEvents.get().activeAt(worldId, x, y, z); }
    }

    /** Ob an der Position ein Event mit dieser ID oder diesem Tag läuft. */
    public static boolean activeAt(Location location, String eventIdOrTag) {
        return activeAt(location).stream().anyMatch(e -> e.eventId().equalsIgnoreCase(eventIdOrTag) || e.hasTag(eventIdOrTag));
    }

    /** Kombinierter Multiplikator an der Position (1.0 ohne Quelle, begrenzt auf 0..10). */
    public static double modifier(String key, Location location) {
        if (location == null || location.getWorld() == null) return 1.0;
        return modifier(key, location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static double modifier(String key, UUID worldId, int x, int y, int z) {
        var registration = SOURCE.get();
        if (key == null) return 1.0;
        double managed = WorldEvents.get().modifier(key, worldId, x, y, z);
        if (registration == null) return managed;
        try { return clamp(managed * registration.source().modifier(key, worldId, x, y, z)); }
        catch (RuntimeException failure) { failed(registration, "modifier", failure); return managed; }
    }

    private static List<WorldEventView> combine(List<WorldEventView> managed, List<WorldEventView> legacy) {
        java.util.Map<UUID,WorldEventView> result = new java.util.LinkedHashMap<>();
        managed.forEach(e -> result.put(e.instanceId(), e)); legacy.forEach(e -> result.putIfAbsent(e.instanceId(), e));
        return List.copyOf(result.values());
    }

    private static void failed(Registration registration, String operation, RuntimeException failure) {
        ProviderFailures.report("WorldEventServices", registration.owner().getName(), operation, failure);
    }

    static double clamp(double value) {
        if (!Double.isFinite(value)) return 1.0;
        return Math.max(MIN_MODIFIER, Math.min(MAX_MODIFIER, value));
    }

    /** Von der Quelle aufzurufen, wenn ein Event beginnt. */
    public static void fireStarted(WorldEventView event) {
        onMainThread(() -> Bukkit.getPluginManager().callEvent(new WorldEventStartedEvent(event)));
    }

    /** Von der Quelle aufzurufen, wenn ein Event endet. */
    public static void fireEnded(WorldEventView event, WorldEventEndedEvent.Reason reason) {
        onMainThread(() -> Bukkit.getPluginManager().callEvent(new WorldEventEndedEvent(event, reason)));
    }

    private static void onMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) { action.run(); return; }
        Plugin core = main.getPlugin();
        if (core != null && core.isEnabled()) Bukkit.getScheduler().runTask(core, action);
    }

    private static void ensureLifecycle() {
        if (LIFECYCLE.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !LIFECYCLE.compareAndSet(false, true)) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDisable(PluginDisableEvent event) { unregister(event.getPlugin()); }
        }, core);
    }
}
