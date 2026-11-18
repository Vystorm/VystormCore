package me.clip.placeholderapi;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * The registry of the fake PlaceholderAPI used in tests. Not a class of PlaceholderAPI: nothing in Core's shipped
 * code refers to it.
 */
public final class StubExpansions {
    private static final Map<String, PlaceholderExpansion> BY_IDENTIFIER = new ConcurrentHashMap<>();

    private StubExpansions() {}

    public static boolean register(PlaceholderExpansion expansion) {
        return BY_IDENTIFIER.putIfAbsent(expansion.getIdentifier().toLowerCase(Locale.ROOT), expansion) == null;
    }

    public static boolean unregister(PlaceholderExpansion expansion) {
        return BY_IDENTIFIER.remove(expansion.getIdentifier().toLowerCase(Locale.ROOT), expansion);
    }

    public static boolean registered(PlaceholderExpansion expansion) {
        return BY_IDENTIFIER.get(expansion.getIdentifier().toLowerCase(Locale.ROOT)) == expansion;
    }

    public static PlaceholderExpansion get(String identifier) { return BY_IDENTIFIER.get(identifier.toLowerCase(Locale.ROOT)); }

    /** What {@code /papi reload} does to expansions registered by plugins: only those that persist stay. */
    public static void reload() { BY_IDENTIFIER.values().removeIf(expansion -> !expansion.persist()); }

    /** Empties the registry between tests. */
    public static void clear() { BY_IDENTIFIER.clear(); }
}
