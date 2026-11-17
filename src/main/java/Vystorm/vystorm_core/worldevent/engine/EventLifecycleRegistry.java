package Vystorm.vystorm_core.worldevent.engine;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Fail-safe integration registry; one broken consumer must not break the director. */
public final class EventLifecycleRegistry {
    private final ConcurrentHashMap<String, EventLifecycleListener> listeners = new ConcurrentHashMap<>();
    private final BiConsumer<String, Throwable> failureSink;

    public EventLifecycleRegistry(BiConsumer<String, Throwable> failureSink) {
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    public void register(EventLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        String id = normalize(listener.id());
        EventLifecycleListener previous = listeners.putIfAbsent(id, listener);
        if (previous != null) throw new IllegalArgumentException("Duplicate lifecycle listener id: " + id);
    }

    public boolean unregister(String id) {
        return listeners.remove(normalize(id)) != null;
    }

    public List<EventLifecycleListener> all() {
        return List.copyOf(listeners.values());
    }

    public void fireStarted(ActiveEvent event) {
        Objects.requireNonNull(event, "event");
        for (EventLifecycleListener listener : listeners.values()) {
            try {
                listener.onStarted(event);
            } catch (RuntimeException failure) {
                failureSink.accept(listener.id(), failure);
            }
        }
    }

    public void fireEnded(ActiveEvent event, EventEndReason reason) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(reason, "reason");
        for (EventLifecycleListener listener : listeners.values()) {
            try {
                listener.onEnded(event, reason);
            } catch (RuntimeException failure) {
                failureSink.accept(listener.id(), failure);
            }
        }
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "id").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        return normalized;
    }
}
