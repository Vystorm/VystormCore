package Vystorm.vystorm_core.worlds;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Rückrufe des Besitzer-Plugins (Server-Thread). Ausnahmen werden geloggt und ändern den Ablauf nicht.
 *
 * <ul>
 *   <li>{@link #entering}: Zustand ist gesichert, Werkzeuge sind gegeben, Teleport hinein startet (eigene Sitzung,
 *       Sidebar, Overlay anlegen).</li>
 *   <li>{@link #arrived}: Spieler ist in der Welt.</li>
 *   <li>{@link #leaving}: The player may still be inside after a failed forced exit (QUIT/SHUTDOWN/REBUILD).
 *       Core restores outside, or neutralizes online visit privileges and retains recovery evidence.
 *       Eigene Darstellung hier abbauen. Wird genau einmal je {@link #entering} gerufen.</li>
 *   <li>{@link #respawn}: Respawn-Ort in der Welt; {@code null} = Weltspawn.</li>
 * </ul>
 *
 * @since 0.26.0
 */
public interface VisitHooks {
    VisitHooks NONE = new VisitHooks() {};

    default void entering(Player player, Visit visit) {}

    default void arrived(Player player, Visit visit) {}

    default void leaving(Player player, Visit visit, ExitReason reason) {}

    default Location respawn(Player player, Visit visit) { return null; }
}
