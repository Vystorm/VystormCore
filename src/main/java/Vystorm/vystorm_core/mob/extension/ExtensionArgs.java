package Vystorm.vystorm_core.mob.extension;

import java.util.Locale;
import java.util.Map;

/** Konfigurationsargumente einer Mechanic/Condition/eines Targeters; Schlüssel ohne Groß-/Kleinschreibung. */
public record ExtensionArgs(Map<String, String> values) {
    public ExtensionArgs {
        java.util.Map<String, String> normalized = new java.util.HashMap<>();
        if (values != null) values.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        values = Map.copyOf(normalized);
    }

    public String string(String key, String fallback) { return values.getOrDefault(key.toLowerCase(Locale.ROOT), fallback); }

    public boolean has(String key) { return values.containsKey(key.toLowerCase(Locale.ROOT)); }
}
