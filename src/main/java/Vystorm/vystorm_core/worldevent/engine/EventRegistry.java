package Vystorm.vystorm_core.worldevent.engine;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class EventRegistry {
    private final ConcurrentHashMap<String, EventDefinition> definitions = new ConcurrentHashMap<>();

    public void register(EventDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        EventDefinition previous = definitions.putIfAbsent(definition.id(), definition);
        if (previous != null) throw new IllegalArgumentException("Duplicate event id: " + definition.id());
    }

    public void replace(EventDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        definitions.put(definition.id(), definition);
    }

    public Optional<EventDefinition> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(definitions.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    public Collection<EventDefinition> all() {
        return java.util.List.copyOf(definitions.values());
    }

    public Map<String, EventDefinition> snapshot() {
        return Map.copyOf(definitions);
    }

    public int size() {
        return definitions.size();
    }

    /** Übernimmt den Inhalt eines vollständig geladenen Registers, ohne dass Leser ein leeres Register sehen. */
    public void replaceAll(EventRegistry loaded) {
        Objects.requireNonNull(loaded, "loaded");
        Map<String, EventDefinition> next = Map.copyOf(loaded.definitions);
        definitions.putAll(next);
        definitions.keySet().retainAll(next.keySet());
    }

    public void clear() {
        definitions.clear();
    }
}
