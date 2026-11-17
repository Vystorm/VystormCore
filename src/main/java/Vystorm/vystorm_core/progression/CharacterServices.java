package Vystorm.vystorm_core.progression;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Charakterlevel-Bridge: Mobs, Quests und Events vergeben Charakter-XP hierüber, ohne das Skills-Plugin zu kennen.
 * Ohne Quelle: Level 1, keine XP.
 *
 * <p>Preferred access: {@code api.player().character()}.
 *
 * @since 0.16.0
 */
public final class CharacterServices {
    private record Registration(Plugin owner, CharacterSource source) {}

    private static final AtomicReference<Registration> SOURCE = new AtomicReference<>();

    private CharacterServices() {}

    public static void register(Plugin owner, CharacterSource source) {
        if (owner == null || source == null) throw new IllegalArgumentException("owner/source missing");
        Registration previous = SOURCE.getAndSet(new Registration(owner, source));
        if (previous != null) ProviderFailures.replaced("CharacterServices", "Charakter-Quelle", previous.owner(), owner);
    }

    public static void unregister(Plugin owner) { SOURCE.updateAndGet(current -> current != null && current.owner().equals(owner) ? null : current); }

    public static boolean available() { Registration r = SOURCE.get(); return r != null && r.owner().isEnabled(); }

    private static CharacterSource source() { Registration r = SOURCE.get(); return r == null || !r.owner().isEnabled() ? null : r.source(); }

    public static int level(UUID player) {
        CharacterSource s = source();
        try { return s == null ? 1 : Math.max(1, s.level(player)); } catch (RuntimeException failure) { failed("level", failure); return 1; }
    }

    public static int maxLevel() {
        CharacterSource s = source();
        try { return s == null ? 1 : Math.max(1, s.maxLevel()); } catch (RuntimeException failure) { failed("maxLevel", failure); return 1; }
    }

    /** Siehe {@link CharacterSource#awardXp}; nur Server-Thread. Liefert 0 ohne Quelle. */
    public static double awardXp(Player player, double amount, String source, int sourceLevel) {
        CharacterSource s = source();
        if (s == null || player == null || !(amount > 0)) return 0;
        try { return s.awardXp(player, amount, source, sourceLevel); } catch (RuntimeException failure) { failed("awardXp", failure); return 0; }
    }

    private static void failed(String operation, RuntimeException failure) {
        Registration r = SOURCE.get();
        ProviderFailures.report("CharacterServices", r == null ? "?" : r.owner().getName(), operation, failure);
    }
}
