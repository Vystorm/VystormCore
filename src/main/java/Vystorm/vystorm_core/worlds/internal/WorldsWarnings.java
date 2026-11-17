package Vystorm.vystorm_core.worlds.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Rate-limited diagnostics. Player-scoped keys expire with their warning window. */
final class WorldsWarnings {
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final Map<String, Long> next = new HashMap<>();
    WorldsWarnings(Consumer<String> log) { this(log, System::nanoTime); }
    WorldsWarnings(Consumer<String> log, LongSupplier clock) { this.log = log;this.clock = clock; }
    synchronized void warn(String operation, String message) {
        long now = clock.getAsLong();
        next.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
        Long deadline = next.get(operation);
        if (deadline != null && now - deadline < 0) return;
        next.put(operation, now + 60_000_000_000L);
        log.accept(message);
    }
}
