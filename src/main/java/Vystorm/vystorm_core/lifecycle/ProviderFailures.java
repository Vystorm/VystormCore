package Vystorm.vystorm_core.lifecycle;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.bukkit.plugin.Plugin;

/**
 * Gedrosseltes Logging für Provider-Fehler und Registrierungskonflikte in Core-Registries: pro Schlüssel höchstens
 * eine Meldung pro Minute, unterdrückte Wiederholungen werden bei der nächsten Meldung mitgezählt. Threadsicher.
 *
 * <p>Preferred access: {@code api.debug().failures()}.
 *
 * @since 0.11.0
 */
public final class ProviderFailures {
    static final long INTERVAL_NANOS = 60_000_000_000L;
    private record Window(AtomicLong next, AtomicLong suppressed) {}

    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();
    static volatile LongSupplier clock = System::nanoTime;

    private ProviderFailures() {}

    /**
     * Meldet einen Fehler eines Providers.
     *
     * @param registry Name der Registry, z. B. {@code ItemServices}
     * @param provider Provider- oder Plugin-Name
     * @param operation aufgerufene Methode
     */
    public static void report(String registry, String provider, String operation, Throwable failure) {
        String key = registry + "|" + provider + "|" + operation + "|" + (failure == null ? "" : failure.getClass().getName());
        long skipped = admit(key);
        if (skipped < 0) return;
        DebugMessages.high("[" + registry + "] Provider " + provider + " failed in " + operation
                + (skipped > 0 ? " (" + skipped + " more since the last report)" : ""), failure);
    }

    /** Warnt, wenn ein anderes Plugin eine bestehende Registrierung ersetzt (last-writer-wins). */
    public static void replaced(String registry, String key, Plugin previousOwner, Plugin newOwner) {
        if (previousOwner == null || newOwner == null || previousOwner == newOwner) return;
        DebugMessages.high("[" + registry + "] " + key + " of " + name(previousOwner) + " is replaced by " + name(newOwner)
                + "; the load order decides which provider applies");
    }

    /** @return Anzahl unterdrückter Meldungen seit der letzten, oder -1 wenn die Meldung gedrosselt wird */
    static long admit(String key) {
        long now = clock.getAsLong();
        Window window = WINDOWS.computeIfAbsent(key, ignored -> new Window(new AtomicLong(Long.MIN_VALUE), new AtomicLong()));
        long next = window.next().get();
        if (next != Long.MIN_VALUE && now - next < 0) { window.suppressed().incrementAndGet(); return -1; }
        if (!window.next().compareAndSet(next, now + INTERVAL_NANOS)) { window.suppressed().incrementAndGet(); return -1; }
        return window.suppressed().getAndSet(0);
    }

    static void reset() { WINDOWS.clear(); }

    private static String name(Plugin plugin) {
        try { return plugin.getName(); } catch (RuntimeException failure) { return String.valueOf(plugin); }
    }
}
