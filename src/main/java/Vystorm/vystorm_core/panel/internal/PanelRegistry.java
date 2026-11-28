package Vystorm.vystorm_core.panel.internal;

import Vystorm.vystorm_core.panel.PanelSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.plugin.Plugin;

/** Alle registrierten Panels (Panel-ID → Besitzer + Beschreibung). Server-Thread; gilt auch, wenn der Kanal aus ist. Core-intern. */
public final class PanelRegistry {
    public record Registered(Plugin owner, String id, PanelSpec spec) {}

    private static final Map<String, Registered> PANELS = new LinkedHashMap<>();

    private PanelRegistry() {}

    public static synchronized void register(Plugin owner, String id, PanelSpec spec) {
        if (PANELS.containsKey(id)) throw new IllegalArgumentException("panel already registered: " + id);
        PANELS.put(id, new Registered(owner, id, spec));
    }

    public static synchronized void unregister(Plugin owner, String id) {
        Registered r = PANELS.get(id);
        if (r != null && r.owner() == owner) PANELS.remove(id);
    }

    public static synchronized Optional<Registered> find(String id) { return Optional.ofNullable(PANELS.get(id)); }

    /** 0.71.0: the registration that holds exactly this spec (identity), if any. */
    public static synchronized Optional<Registered> findBySpec(PanelSpec spec) {
        if (spec == null) return Optional.empty();
        for (Registered r : PANELS.values()) if (r.spec() == spec) return Optional.of(r);
        return Optional.empty();
    }

    /** Entfernt alle Panels des Plugins; gibt ihre IDs zurück. */
    public static synchronized List<String> release(Plugin owner) {
        List<String> out = new ArrayList<>();
        PANELS.values().removeIf(r -> {
            if (r.owner() != owner) return false;
            out.add(r.id());
            return true;
        });
        return out;
    }

    public static synchronized void clear() { PANELS.clear(); }
}
