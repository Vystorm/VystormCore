package Vystorm.vystorm_core.progression;

import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Anbieter von Disziplinen/Berufen (Vystorm Skills). Schnell und threadsicher lesbar. */
public interface DisciplineSource {
    Set<String> disciplines();

    int level(UUID player, String discipline);

    /** Freie Modifikatoren, z. B. {@code smithing.quality}, {@code salvage.yield} (0 = neutral). */
    double modifier(UUID player, String key);

    boolean hasUnlock(UUID player, String unlock);

    /** Vergibt XP (Server-Thread); liefert die vergebene Menge. */
    double awardXp(Player player, String discipline, double amount, String source);
}
