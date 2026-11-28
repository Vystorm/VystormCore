package Vystorm.vystorm_core.survey.internal;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The block classes of recently snapshotted chunks of one world, so a chunk classified as a neighbour is not
 * snapshotted and classified again as the centre of the next survey (in ring order every chunk is a neighbour of
 * eight others). Least recently used entries go first once the entry limit or the byte budget is exceeded; an entry
 * expires after {@code ttlMillis} (a running job raises both the budget and the time, because the chunks of the
 * previous ring are needed again one ring later). Entries are kept while their chunk is unloaded: an unloaded chunk
 * does not change, and a loaded one is kept up to date from the block watcher. Thread safe.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ClassCache {
    private record Entry(ChunkClasses classes, long since, int bytes) {}

    private final LinkedHashMap<Long, Entry> map = new LinkedHashMap<>(64, 0.75f, true);
    private final LongSupplier clock;
    private long bytes;
    private int maxEntries = 512;
    private long maxBytes = 32L << 20;
    private long ttlMillis = 30_000;
    private long hits, misses;

    public ClassCache(LongSupplier clock) { this.clock = clock; }

    public ClassCache() { this(System::currentTimeMillis); }

    /** Sets the limits; the cache shrinks at once if needed. */
    public synchronized void limits(int entries, long maxBytes, long ttlMillis) {
        this.maxEntries = Math.max(1, entries);
        this.maxBytes = Math.max(1, maxBytes);
        this.ttlMillis = Math.max(1, ttlMillis);
        trim();
    }

    public synchronized ChunkClasses get(long key) {
        Entry e = map.get(key);
        if (e == null) { misses++; return null; }
        if (clock.getAsLong() - e.since > ttlMillis) {
            remove(key);
            misses++;
            return null;
        }
        hits++;
        return e.classes;
    }

    /** Looks without counting a hit or a miss and without expiring (the block watcher's filter). */
    public synchronized boolean contains(long key) { return map.containsKey(key); }

    /** Stores classes; an update of an existing entry (a block change) keeps its age. */
    public synchronized void put(long key, ChunkClasses classes) {
        Entry old = map.get(key);
        int size = classes.footprint();
        if (old != null) bytes -= old.bytes;
        map.put(key, new Entry(classes, old == null ? clock.getAsLong() : old.since, size));
        bytes += size;
        trim();
    }

    /** Replaces an entry only if there is one (block changes in chunks the cache does not hold are ignored). */
    public synchronized void update(long key, ChunkClasses classes) { if (map.containsKey(key)) put(key, classes); }

    public synchronized void remove(long key) {
        Entry e = map.remove(key);
        if (e != null) bytes -= e.bytes;
    }

    private void trim() {
        Iterator<Map.Entry<Long, Entry>> it = map.entrySet().iterator();
        while ((map.size() > maxEntries || bytes > maxBytes) && it.hasNext()) {
            bytes -= it.next().getValue().bytes;
            it.remove();
        }
    }

    public synchronized int size() { return map.size(); }

    public synchronized long bytes() { return bytes; }

    public synchronized long hits() { return hits; }

    public synchronized long misses() { return misses; }

    public synchronized void resetCounts() { hits = 0; misses = 0; }
}
