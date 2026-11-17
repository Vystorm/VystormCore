package Vystorm.vystorm_core.curve;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Serverweite Registry benannter Kurven ({@code curve.agility.slow.default}, {@code curve.mastery.post100.v1} …).
 * Plugins registrieren ihre Kurven beim Start (aus Konfiguration oder Code); andere Plugins lesen sie nur über die ID.
 * Eine spätere Registrierung derselben ID ersetzt die frühere (Reload).
 * <p>Preferred access: {@code api.runtime().curves()}.
 */
public final class CurveRegistry {
    private static final Map<String, Curve> CURVES = new ConcurrentHashMap<>();

    private CurveRegistry() {}

    public static void register(String id, Curve curve) {
        if (id == null || id.isBlank() || curve == null) throw new IllegalArgumentException("id/curve missing");
        CURVES.put(id, curve);
    }

    public static void unregister(String id) { if (id != null) CURVES.remove(id); }

    /** Entfernt alle Kurven mit diesem Präfix (z. B. beim Reload eines Plugins). */
    public static void unregisterPrefix(String prefix) { CURVES.keySet().removeIf(key -> key.startsWith(prefix)); }

    public static Optional<Curve> get(String id) { return Optional.ofNullable(id == null ? null : CURVES.get(id)); }

    /** Wertet eine Kurve aus; fehlt sie, gilt {@code fallback}. */
    public static double apply(String id, double x, double fallback) {
        Curve curve = id == null ? null : CURVES.get(id);
        return curve == null ? fallback : curve.apply(x);
    }

    public static Set<String> ids() { return new TreeSet<>(CURVES.keySet()); }

    /**
     * Registriert jede Unter-Sektion als Kurve mit {@code prefix + key}. Fehlerhafte Einträge werden übersprungen und
     * als Meldung zurückgegeben, damit ein Tippfehler nicht das ganze Plugin stoppt.
     */
    public static java.util.List<String> loadSection(String prefix, ConfigurationSection section) {
        var problems = new java.util.ArrayList<String>();
        if (section == null) return problems;
        for (String key : section.getKeys(false)) {
            ConfigurationSection curve = section.getConfigurationSection(key);
            if (curve == null) { problems.add(key + ": not a section"); continue; }
            try { register(prefix + key, Curve.fromMap(curve.getValues(true))); }
            catch (IllegalArgumentException | ClassCastException | NullPointerException invalid) { problems.add(key + ": " + invalid.getClass().getSimpleName() + " " + invalid.getMessage()); }
        }
        return problems;
    }
}
