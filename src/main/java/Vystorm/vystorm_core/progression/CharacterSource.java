package Vystorm.vystorm_core.progression;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Anbieter des Charakterlevels (Vystorm Skills): Hauptklasse mit Talentpunkten, getrennt von den Disziplinen und von
 * der Vanilla-XP-Leiste. Lesen ist schnell und threadsicher, {@link #awardXp} nur auf dem Server-Thread.
 *
 * @since 0.16.0
 */
public interface CharacterSource {
    /** Charakterlevel (mindestens 1). */
    int level(UUID player);

    /** Höchstes erreichbares Charakterlevel. */
    int maxLevel();

    /**
     * Vergibt Charakter-XP. {@code sourceLevel} ist das Level der Quelle (z. B. Mob-Level) für die Level-Abstands-
     * Dämpfung; {@code 0} = ohne Dämpfung. Liefert die tatsächlich vergebene Menge.
     */
    double awardXp(Player player, double amount, String source, int sourceLevel);
}
