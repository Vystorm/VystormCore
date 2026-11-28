package Vystorm.vystorm_core.packet.internal.view;

import java.util.List;
import java.util.function.Function;

/**
 * Finds a player's network connection without touching a non-public field. Paper makes the field that leads from a
 * player's packet listener to its connection public; Spigot keeps it {@code protected}. On Spigot the connection is
 * found instead in the server's public list of all connections ({@code ServerConnectionListener.getConnections()}):
 * the one whose public {@code getPacketListener()} is the very listener object of the player
 * ({@code ServerPlayer.connection}, a public field on both). Only public members are read; nothing is made accessible
 * and nothing is written.
 */
public final class ConnectionLookup {
    private ConnectionLookup() {}

    /**
     * The entry of {@code connections} whose listener is {@code wanted} (by identity, never by equals), or null. The
     * server's list is a synchronized list that the network threads change while players come and go, so it is read
     * while holding its lock.
     */
    public static Object find(List<?> connections, Function<Object, Object> listenerOf, Object wanted) {
        if (connections == null || wanted == null) return null;
        synchronized (connections) {
            for (Object c : connections) if (c != null && listenerOf.apply(c) == wanted) return c;
        }
        return null;
    }
}
