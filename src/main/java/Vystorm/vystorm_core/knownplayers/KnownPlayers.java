package Vystorm.vystorm_core.knownplayers;

import Vystorm.vystorm_core.knownplayers.internal.KnownPlayerStore;
import Vystorm.vystorm_core.knownplayers.internal.KnownPlayersRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Core's list of players who joined this server (0.71.0): name, UUID, first and last seen, skin address. Fed on every
 * join and quit, seeded once from the server's own {@code usercache.json} when Core starts without the file, kept in
 * {@code plugins/vystorm_core/known-players.dat} and written every 60 s when changed. No Paper-only call, no scan of
 * player data files and never a network lookup, so it works the same on Spigot, Paper and Purpur, also 1.20.6.
 * <p>
 * Read calls are lock-free and may come from any thread; until the file is read at start (a moment after enable) they
 * answer from the players seen so far ({@link #loaded()} says when the file is in). With
 * {@code known-players.enabled: false} in Core's config everything answers empty.
 * <p>
 * Privacy: {@link #search} and {@link #recent} leave out players who switched off "Others can find me by name when I am
 * offline" in their Core settings ({@link #findable}); {@link #get} and {@link #byName} still find them, so commands that
 * take an exact name keep working. Entries unseen for {@code known-players.forget-after-days} (365) are dropped at start;
 * {@link #forget} and {@code /vadmin players forget} remove one on request.
 * <p>
 * Preferred access: {@code api.data().knownPlayers()}. Capability {@code known-players}.
 *
 * @since 0.71.0
 */
public final class KnownPlayers {
    /** Most hits of one {@link #search}. */
    public static final int MAX_SEARCH = 50;
    /** Most entries of one {@link #recent}. */
    public static final int MAX_RECENT = 500;

    private KnownPlayers() {}

    private static KnownPlayerStore store() { return KnownPlayersRuntime.store(); }

    /** Whether the store runs ({@code known-players.enabled}, Core started). */
    public static boolean enabled() { return store() != null; }

    /** Whether the stored file has been read (or there was none). Before that only players seen since the start are known. */
    public static boolean loaded() { return KnownPlayersRuntime.loaded(); }

    /** Number of known players. */
    public static int size() {
        KnownPlayerStore s = store();
        return s == null ? 0 : s.size();
    }

    /** The player with this UUID. */
    public static Optional<KnownPlayer> get(UUID id) {
        KnownPlayerStore s = store();
        return s == null ? Optional.empty() : s.get(id).map(KnownPlayerStore.Entry::view);
    }

    /** The player whose current name is exactly {@code name} (case-insensitive); with several the one seen last. */
    public static Optional<KnownPlayer> byName(String name) {
        KnownPlayerStore s = store();
        return s == null ? Optional.empty() : s.byName(name).map(KnownPlayerStore.Entry::view);
    }

    /**
     * Players whose current name starts with {@code prefix} (case-insensitive), in name order, at most {@code limit}
     * (1 to {@value #MAX_SEARCH}). Players who are not findable when offline are left out, also while they are online:
     * callers that list online players take them from the server.
     */
    public static List<KnownPlayer> search(String prefix, int limit) {
        KnownPlayerStore s = store();
        if (s == null) return List.of();
        return s.search(prefix, Math.max(1, Math.min(MAX_SEARCH, limit)), false).stream().map(KnownPlayerStore.Entry::view).toList();
    }

    /** Players seen within {@code within}, newest first, at most {@code limit} (1 to {@value #MAX_RECENT}); not findable ones left out. */
    public static List<KnownPlayer> recent(Duration within, int limit) {
        KnownPlayerStore s = store();
        if (s == null || within == null || within.isNegative()) return List.of();
        long since = System.currentTimeMillis() - Math.min(within.toMillis(), Long.MAX_VALUE / 2);
        return s.recent(since, Math.max(1, Math.min(MAX_RECENT, limit)), false).stream().map(KnownPlayerStore.Entry::view).toList();
    }

    /** Whether others may find this player by name while offline (the personal setting; true for unknown players). */
    public static boolean findable(UUID id) {
        KnownPlayerStore s = store();
        return s == null || s.get(id).map(e -> !e.hidden()).orElse(true);
    }

    /** Sets the personal "findable when offline" choice of a known player. Server thread. */
    public static void setFindable(UUID id, boolean findable) {
        KnownPlayerStore s = store();
        if (s != null && id != null) s.setHidden(id, !findable);
    }

    /** Removes the player (privacy request); false if unknown. A later join adds the player again. Server thread. */
    public static boolean forget(UUID id) {
        KnownPlayerStore s = store();
        return s != null && s.forget(id);
    }

    /** Core lifecycle: start with Core's config (reads the file off the server thread). Not for plugins. */
    public static void start(Plugin core) { KnownPlayersRuntime.start(core); }

    /** Core lifecycle: writes the file if changed and stops. Not for plugins. */
    public static void shutdown() { KnownPlayersRuntime.shutdown(); }
}
