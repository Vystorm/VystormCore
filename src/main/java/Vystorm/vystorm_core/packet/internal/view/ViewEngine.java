package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.packet.internal.wire.WireTables;
import Vystorm.vystorm_core.packet.view.ViewChunks;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * State shared by the server thread and the Netty threads (moved from VystormAntiXray's {@code Engine}): the block
 * tables, the counters, the worlds by dimension key, the chunks to ask again, and the breaker that switches the view
 * off after a format mismatch.
 *
 * <p>The rule for switching off: only the self-test switches the view off for everyone (when it is run again after a
 * packet could not be read and fails twice: then this server's format really differs). Every other failure belongs to
 * one packet of one viewer and is handled there: a chunk, light or section update a MASK layer is interested in that
 * cannot be rewritten is not sent (a chunk is asked again from the server thread, at most twice), a packet that cannot
 * be judged is dropped; one console line per kind and minute names it.
 *
 * <p>Internal: not part of the public API.
 */
public final class ViewEngine {
    public final WireTables tables;
    public final ViewCounters stats;
    /** How this server writes light bitsets (found by the self-test): 26.3 bytes, 26.2 longs. */
    public final boolean bitsetBytes;
    /** The server's dimension key of every loaded world (as the login and respawn packets name it): its world and height. */
    public final Map<Object, Dim> dimensions = new ConcurrentHashMap<>();
    /** Every loaded world's height. */
    public final Map<UUID, Dim> worlds = new ConcurrentHashMap<>();
    /** Chunks that were not sent because they could not be rewritten: sent again from the server thread. */
    final Set<Retry> retries = ConcurrentHashMap.newKeySet();
    /** Set when a packet could not be read: the server thread runs the self-test again (at most once a minute). */
    final AtomicBoolean recheck = new AtomicBoolean();
    /** Where the rate-limited warnings go (Core's logger). */
    public volatile Consumer<String> warnings = line -> { };
    private final Map<String, long[]> warned = new ConcurrentHashMap<>();
    private volatile String broken;
    private volatile Consumer<String> onBreak = why -> { };

    /** A world as a dimension key names it. */
    public record Dim(UUID world, int minY, int sections) {}

    record Retry(UUID viewer, UUID world, long chunk) {}

    public ViewEngine(WireTables tables, ViewCounters stats, boolean bitsetBytes) {
        this.tables = tables;
        this.stats = stats;
        this.bitsetBytes = bitsetBytes;
    }

    public void onBreak(Consumer<String> handler) { this.onBreak = handler; }

    /** The world of a dimension key, null when Core has not seen it load. */
    public Dim dimension(Object key) { return key == null ? null : dimensions.get(key); }

    public Dim world(UUID id) { return id == null ? null : worlds.get(id); }

    /** A world loaded (server thread). */
    public void addWorld(Object dimensionKey, UUID world, int minY, int sections) {
        Dim d = new Dim(world, minY, sections);
        worlds.put(world, d);
        if (dimensionKey != null) dimensions.put(dimensionKey, d);
    }

    public void removeWorld(UUID world) {
        worlds.remove(world);
        dimensions.values().removeIf(d -> d.world().equals(world));
    }

    /**
     * A packet could not be rewritten and was not sent (any thread): one console line per kind and minute, the self-test
     * runs again on the server thread when the bytes could not be read, and a chunk is sent again (at most twice).
     */
    public boolean unreadable(ViewerSlot v, String what, UUID world, int[] chunk, String why, boolean recheckFormat) {
        stats.inc("unreadable");
        if (recheckFormat) recheck.set(true);
        boolean logged = warn("unreadable " + what, "Packet view: a " + what + " packet for " + v.name + " could not be rewritten (" + why + "); it was not sent"
                + (chunk == null ? "" : " (chunk " + chunk[0] + " " + chunk[1] + ", sent again)") + ". Another plugin that changes packets on the network side can cause this.");
        if (chunk == null || world == null) return logged;
        long key = ViewChunks.key(chunk[0], chunk[1]);
        if (v.retried.merge(key, 1, Integer::sum) <= 2) retries.add(new Retry(v.id, world, key));
        return logged;
    }

    /** One console line per topic and minute (any thread); the next line says how many were left out. True if logged. */
    public boolean warn(String topic, String line) {
        long now = System.currentTimeMillis();
        long[] t = warned.computeIfAbsent(topic, k -> new long[] {0, 0});
        String out;
        synchronized (t) {
            if (now - t[0] < 60_000) { t[1]++; return false; }
            out = t[1] == 0 ? line : line + " (" + t[1] + " more like this in the last minute)";
            t[0] = now;
            t[1] = 0;
        }
        warnings.accept(out);
        return true;
    }

    /** Null while everything works; otherwise why the view switched itself off. */
    public String broken() { return broken; }

    /** Switches the view off once (any thread): packets pass unchanged from now on. Only the self-test calls this. */
    public void breakdown(String why) {
        if (broken != null) return;
        broken = why;
        onBreak.accept(why);
    }
}
