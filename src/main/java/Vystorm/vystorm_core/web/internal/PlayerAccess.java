package Vystorm.vystorm_core.web.internal;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Sicht der Web-Plattform auf Spieler: Online-Status, Berechtigungen und Server-Thread. Aufrufbar von jedem Thread; die
 * Bukkit-Umsetzung ({@link BukkitPlayerAccess}) liest einen auf dem Server-Thread aktualisierten Schnappschuss.
 * Core-intern.
 *
 * @since 0.17.0
 */
public interface PlayerAccess {
    boolean online(UUID player);

    /** Ob der Spieler die Berechtigung hat; bei Unsicherheit {@code false}. */
    boolean has(UUID player, String node);

    /** Führt {@code task} auf dem Server-Thread aus und wartet höchstens {@code timeoutMs}. */
    <T> T sync(Callable<T> task, long timeoutMs) throws Exception;
}
