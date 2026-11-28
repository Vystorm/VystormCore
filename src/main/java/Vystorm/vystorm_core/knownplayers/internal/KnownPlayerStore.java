package Vystorm.vystorm_core.knownplayers.internal;

import Vystorm.vystorm_core.knownplayers.KnownPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The known players in memory: by UUID, a sorted name index (lower case name, then UUID) for prefix searches and a
 * last-seen index for "recent". Reads are lock-free from any thread; writes come from one thread at a time (the server
 * thread, or the loader before the store is published). Readers may see one index a moment before the other; every
 * read checks the entry against the id map. No Bukkit types. Core-internal.
 */
public final class KnownPlayerStore {
    /** One stored player. {@code skin} is null without one; {@code hidden} = the player switched off "findable when offline". */
    public record Entry(UUID id, String name, long firstSeen, long lastSeen, String skin, boolean hidden) {
        public Entry {
            Objects.requireNonNull(id, "id");
            name = KnownPlayerCodec.cleanName(name);
            if (name.isEmpty()) throw new IllegalArgumentException("empty name");
            skin = KnownPlayerCodec.cleanSkin(skin);
            if (firstSeen > lastSeen) firstSeen = lastSeen;
        }

        public KnownPlayer view() { return new KnownPlayer(id, name, firstSeen, lastSeen, Optional.ofNullable(skin)); }
    }

    private static final Comparator<Entry> NEWEST_FIRST = Comparator.comparingLong(Entry::lastSeen).reversed().thenComparing(Entry::id);

    private final Map<UUID, Entry> byId = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<String, UUID> byName = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListSet<Entry> byLastSeen = new ConcurrentSkipListSet<>(NEWEST_FIRST);
    private final AtomicBoolean dirty = new AtomicBoolean();

    static String nameKey(String name, UUID id) { return name.toLowerCase(Locale.ROOT) + '\u0000' + id; }

    public int size() { return byId.size(); }

    public Optional<Entry> get(UUID id) { return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id)); }

    /** Replaces (or adds) one entry and its index rows. */
    public void put(Entry e) {
        Entry old = byId.put(e.id(), e);
        if (old != null) {
            byName.remove(nameKey(old.name(), old.id()));
            byLastSeen.remove(old);
        }
        byName.put(nameKey(e.name(), e.id()), e.id());
        byLastSeen.add(e);
        dirty.set(true);
    }

    /** A join: first seen stays, last seen is {@code now}, the name and (when given) the skin are the new ones. */
    public Entry joined(UUID id, String name, long now, String skin) {
        Entry old = byId.get(id);
        Entry next = old == null
                ? new Entry(id, name, now, now, skin, false)
                : new Entry(id, name, Math.min(old.firstSeen(), now), Math.max(old.lastSeen(), now), skin == null ? old.skin() : skin, old.hidden());
        put(next);
        return next;
    }

    /** A quit: last seen is {@code now}. */
    public void quit(UUID id, long now) {
        Entry old = byId.get(id);
        if (old != null && old.lastSeen() < now) put(new Entry(id, old.name(), old.firstSeen(), now, old.skin(), old.hidden()));
    }

    /** Joins a loaded or seeded entry with what the store already has (a join that came before the file was read). */
    public void merge(Entry loaded) {
        Entry old = byId.get(loaded.id());
        if (old == null) {
            put(loaded);
            return;
        }
        boolean newer = loaded.lastSeen() > old.lastSeen();
        put(new Entry(old.id(), newer ? loaded.name() : old.name(), Math.min(old.firstSeen(), loaded.firstSeen()),
                Math.max(old.lastSeen(), loaded.lastSeen()), old.skin() != null ? old.skin() : loaded.skin(), old.hidden() || loaded.hidden()));
    }

    public boolean forget(UUID id) {
        Entry old = id == null ? null : byId.remove(id);
        if (old == null) return false;
        byName.remove(nameKey(old.name(), old.id()));
        byLastSeen.remove(old);
        dirty.set(true);
        return true;
    }

    public void setHidden(UUID id, boolean hidden) {
        Entry old = byId.get(id);
        if (old != null && old.hidden() != hidden) put(new Entry(id, old.name(), old.firstSeen(), old.lastSeen(), old.skin(), hidden));
    }

    /** Exact current name, case-insensitive; with several (offline-mode servers) the one seen last. */
    public Optional<Entry> byName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String lower = name.strip().toLowerCase(Locale.ROOT);
        Entry best = null;
        for (UUID id : byName.subMap(lower + '\u0000', true, lower + '\u0001', false).values()) {
            Entry e = byId.get(id);
            if (e != null && e.name().equalsIgnoreCase(lower) && (best == null || e.lastSeen() > best.lastSeen())) best = e;
        }
        return Optional.ofNullable(best);
    }

    /** Names starting with {@code prefix} (case-insensitive), in name order; {@code withHidden} for admins. */
    public List<Entry> search(String prefix, int limit, boolean withHidden) {
        List<Entry> out = new ArrayList<>();
        if (prefix == null || limit <= 0) return out;
        String lower = prefix.strip().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return out;
        for (Map.Entry<String, UUID> row : byName.tailMap(lower, true).entrySet()) {
            if (!row.getKey().startsWith(lower)) break;
            Entry e = byId.get(row.getValue());
            if (e == null || (e.hidden() && !withHidden)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Seen at or after {@code since} (epoch ms), newest first. */
    public List<Entry> recent(long since, int limit, boolean withHidden) {
        List<Entry> out = new ArrayList<>();
        if (limit <= 0) return out;
        for (Entry e : byLastSeen) {
            if (e.lastSeen() < since) break;
            if (byId.get(e.id()) != e || (e.hidden() && !withHidden)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Drops every entry last seen before {@code cutoff}; returns how many. */
    public int retain(long cutoff) {
        int dropped = 0;
        for (Entry e : List.copyOf(byLastSeen.descendingSet())) {
            if (e.lastSeen() >= cutoff) break;
            if (forget(e.id())) dropped++;
        }
        return dropped;
    }

    /** All entries, newest first (a copy). */
    public List<Entry> all() { return new ArrayList<>(byLastSeen); }

    /** True once after any change since the last call (the writer's "needs saving"). */
    public boolean takeDirty() { return dirty.getAndSet(false); }

    /** Marks the store as changed again (a write failed). */
    public void markDirty() { dirty.set(true); }
}
