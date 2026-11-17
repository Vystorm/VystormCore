package Vystorm.vystorm_core.worlds;

import java.util.UUID;
import org.bukkit.Location;

/**
 * Ein laufender Besuch (nur lesen). Server-Thread.
 *
 * @since 0.26.0
 */
public interface Visit {
    enum Phase {
        /** Zustand gesichert, Teleport hinein läuft. */
        ENTERING,
        /** Spieler ist angekommen. */
        ACTIVE,
        /** Teleport hinaus läuft; Wiederherstellung erst danach. */
        LEAVING
    }

    UUID player();

    /** ID der Plugin-Welt ({@link WorldSpec#id()}). */
    String worldId();

    long startedAt();

    Phase phase();

    /** Wohin der Spieler am Ende zurückkehrt. */
    Location returnLocation();
}
