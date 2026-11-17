package Vystorm.vystorm_core.progression;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Disziplin-Bridge (Items §39): Crafting, Schmieden, Reparatur und Salvage fragen Level/Modifikatoren hierüber ab,
 * ohne das Skills-Plugin zu kennen. Ohne Quelle: Level 0, Modifikator 0, keine Unlocks, keine XP.
 * <p>Preferred access: {@code api.player().disciplines()}.
 */
public final class DisciplineServices {
    private record Registration(Plugin owner, DisciplineSource source) {}

    private static final AtomicReference<Registration> SOURCE = new AtomicReference<>();

    private DisciplineServices() {}

    public static void register(Plugin owner, DisciplineSource source) {
        if (owner == null || source == null) throw new IllegalArgumentException("owner/source missing");
        Registration previous = SOURCE.getAndSet(new Registration(owner, source));
        if (previous != null) ProviderFailures.replaced("DisciplineServices", "Disziplin-Quelle", previous.owner(), owner);
    }

    public static void unregister(Plugin owner) { SOURCE.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current); }

    public static boolean available() { Registration r = SOURCE.get(); return r != null && r.owner().isEnabled(); }

    private static DisciplineSource source() { Registration r = SOURCE.get(); return r == null || !r.owner().isEnabled() ? null : r.source(); }

    public static Set<String> disciplines() {
        DisciplineSource s = source();
        try { return s == null ? Set.of() : Set.copyOf(s.disciplines()); } catch (RuntimeException failure) { failed("disciplines", failure); return Set.of(); }
    }

    public static int level(UUID player, String discipline) {
        DisciplineSource s = source();
        try { return s == null ? 0 : s.level(player, discipline); } catch (RuntimeException failure) { failed("level", failure); return 0; }
    }

    public static double modifier(UUID player, String key) {
        DisciplineSource s = source();
        try { return s == null ? 0 : s.modifier(player, key); } catch (RuntimeException failure) { failed("modifier", failure); return 0; }
    }

    public static boolean hasUnlock(UUID player, String unlock) {
        DisciplineSource s = source();
        try { return s != null && s.hasUnlock(player, unlock); } catch (RuntimeException failure) { failed("hasUnlock", failure); return false; }
    }

    public static double awardXp(Player player, String discipline, double amount, String source) {
        DisciplineSource s = source();
        try { return s == null ? 0 : s.awardXp(player, discipline, amount, source); } catch (RuntimeException failure) { failed("awardXp", failure); return 0; }
    }

    private static void failed(String operation, RuntimeException failure) {
        Registration r = SOURCE.get();
        ProviderFailures.report("DisciplineServices", r == null ? "?" : r.owner().getName(), operation, failure);
    }
}
