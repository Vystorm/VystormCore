package Vystorm.vystorm_core.region;

import Vystorm.vystorm_core.main;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Gemeinsames Regionsregister aller Vystorm-Plugins. Plugins melden ihre Regionen (Stadt-Claims, Arenen, Märkte,
 * Dungeons, Fraktionsgebiete …); jedes Plugin fragt sie ohne Abhängigkeit auf das Besitzer-Plugin ab.
 *
 * <p>Registrieren/Entfernen nur auf dem Server-Thread (synchronisiert); Abfragen sind lock-frei und auch aus anderen
 * Threads erlaubt. Kleine Regionen stehen in einem Chunk-Index, sehr große (z. B. Fraktionsgebiete) werden über ihr
 * umschließendes Rechteck geprüft.</p>
 * <p>Preferred access: {@code api.area().regions()}.
 */
public final class RegionService {
    private static final RegionService INSTANCE = new RegionService();
    /** Regionen über dieser Chunk-Zahl werden nicht indiziert, sondern per Rechteck vorgeprüft. */
    static final long MAX_INDEXED_CHUNKS = 4096;
    private static final Comparator<Region> ORDER = Comparator.comparingInt(Region::priority).reversed().thenComparing(Region::key);

    private record ChunkRef(UUID world, int x, int z) {}
    private record Entry(Region region, String ownerPlugin, List<ChunkRef> chunks, int minX, int minZ, int maxX, int maxZ) {
        static Entry of(Region region, String owner, List<ChunkRef> chunks) {
            var shape = region.shape();
            return new Entry(region, owner, chunks, shape.minX(), shape.minZ(), shape.maxX(), shape.maxZ());
        }
    }

    private final Map<String, Entry> byKey = new ConcurrentHashMap<>();
    private final Map<ChunkRef, Region[]> index = new ConcurrentHashMap<>();
    private final List<Entry> large = new CopyOnWriteArrayList<>();
    private final AtomicBoolean lifecycle = new AtomicBoolean();
    private volatile long revision;

    private RegionService() {}

    public static RegionService get() { INSTANCE.ensureLifecycle(); return INSTANCE; }

    /** Zählt bei jeder Änderung hoch (für Caches in Abfragern). */
    public long revision() { return revision; }

    // ---- Registrieren ----

    /** Registriert oder ersetzt eine Region (gleicher Schlüssel {@code art:id}). */
    public synchronized void register(Plugin owner, Region region) {
        Objects.requireNonNull(region, "region");
        String ownerName = owner == null ? "" : owner.getName();
        Region stored = region.owner().isEmpty() ? region.withOwner(ownerName) : region;
        Entry old = byKey.get(stored.key());
        if (old != null) removeIndex(old);
        Entry entry = Entry.of(stored, ownerName, chunksOf(stored.shape()));
        byKey.put(stored.key(), entry);
        addIndex(entry);
        revision++;
    }

    public synchronized boolean unregister(String kind, String id) {
        Entry old = byKey.remove(kind + ":" + id);
        if (old == null) return false;
        removeIndex(old);
        revision++;
        return true;
    }

    /** Entfernt alle Regionen eines Plugins (auch automatisch beim Deaktivieren). */
    public synchronized int unregisterOwner(Plugin owner) {
        if (owner == null) return 0;
        int removed = 0;
        for (Entry entry : List.copyOf(byKey.values())) {
            if (!entry.ownerPlugin().equals(owner.getName())) continue;
            byKey.remove(entry.region().key());
            removeIndex(entry);
            removed++;
        }
        if (removed > 0) revision++;
        return removed;
    }

    /**
     * Gleicht alle Regionen einer Art eines Plugins mit der übergebenen Menge ab: fehlende werden entfernt,
     * geänderte ersetzt, unveränderte bleiben unangetastet.
     */
    public synchronized void sync(Plugin owner, String kind, Collection<Region> regions) {
        String ownerName = owner == null ? "" : owner.getName();
        var wanted = new HashMap<String, Region>();
        for (Region region : regions) {
            if (!region.kind().equals(kind)) throw new IllegalArgumentException("region kind " + region.kind() + " != " + kind);
            wanted.put(region.key(), region.owner().isEmpty() ? region.withOwner(ownerName) : region);
        }
        for (Entry entry : List.copyOf(byKey.values()))
            if (entry.region().kind().equals(kind) && entry.ownerPlugin().equals(ownerName) && !wanted.containsKey(entry.region().key())) {
                byKey.remove(entry.region().key());
                removeIndex(entry);
                revision++;
            }
        for (Region region : wanted.values()) {
            Entry current = byKey.get(region.key());
            if (current == null || !current.region().equals(region)) register(owner, region);
        }
    }

    // ---- Abfragen ----

    /** Alle Regionen an der Position, höchste Priorität zuerst. */
    public List<Region> at(UUID world, int x, int y, int z) {
        var result = new ArrayList<Region>();
        Region[] indexed = index.get(new ChunkRef(world, x >> 4, z >> 4));
        if (indexed != null) for (Region region : indexed) if (region.shape().contains(world, x, y, z)) result.add(region);
        Region[] anyWorld = index.get(new ChunkRef(null, x >> 4, z >> 4));
        if (anyWorld != null) for (Region region : anyWorld) if (region.shape().contains(world, x, y, z)) result.add(region);
        for (Entry entry : large) {
            if (x < entry.minX() || x > entry.maxX() || z < entry.minZ() || z > entry.maxZ()) continue;
            if (entry.region().shape().contains(world, x, y, z)) result.add(entry.region());
        }
        if (result.size() > 1) result.sort(ORDER);
        return result;
    }

    public List<Region> at(Location location) {
        if (location == null || location.getWorld() == null) return List.of();
        return at(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Höchstpriorisierte Region einer Art an der Position. */
    public Optional<Region> top(Location location, String kind) {
        return at(location).stream().filter(region -> region.kind().equals(kind)).findFirst();
    }

    /** Höchstpriorisierte Region überhaupt an der Position. */
    public Optional<Region> top(Location location) { return at(location).stream().findFirst(); }

    /** Wert eines Flags an der Position: die höchstpriorisierte Region, die das Flag setzt, entscheidet. */
    public boolean flag(Location location, String flag, boolean fallback) {
        for (Region region : at(location)) {
            Boolean value = region.flags().get(flag);
            if (value != null) return value;
        }
        return fallback;
    }

    /** Schlüssel {@code art:id} aller Regionen an der Position. */
    public List<String> keys(Location location) { return at(location).stream().map(Region::key).toList(); }

    public Optional<Region> get(String kind, String id) { return Optional.ofNullable(byKey.get(kind + ":" + id)).map(Entry::region); }

    public List<Region> all() { return byKey.values().stream().map(Entry::region).sorted(ORDER).toList(); }

    public List<Region> all(String kind) { return byKey.values().stream().map(Entry::region).filter(r -> r.kind().equals(kind)).sorted(ORDER).toList(); }

    /** Regionen, deren Fläche ein Rechteck schneidet (Spaltenprüfung an Ecken, Mitte und über die Chunks). */
    public List<Region> intersecting(UUID world, int minX, int minZ, int maxX, int maxZ) {
        var result = new LinkedHashSet<Region>();
        for (Entry entry : byKey.values()) {
            RegionShape shape = entry.region().shape();
            if (shape.world() != null && !shape.world().equals(world)) continue;
            if (entry.maxX() < minX || entry.minX() > maxX || entry.maxZ() < minZ || entry.minZ() > maxZ) continue;
            if (overlaps(shape, Math.max(minX, entry.minX()), Math.max(minZ, entry.minZ()), Math.min(maxX, entry.maxX()), Math.min(maxZ, entry.maxZ()))) result.add(entry.region());
        }
        return result.stream().sorted(ORDER).toList();
    }

    private static boolean overlaps(RegionShape shape, int minX, int minZ, int maxX, int maxZ) {
        if (shape instanceof RegionShape.Box) return true; // Rechtecke überschneiden sich bereits
        // Grobe, aber sichere Prüfung in 4er-Schritten; reicht für Platzierungs- und Planungsfragen.
        int step = Math.max(1, Math.min(16, Math.max(maxX - minX, maxZ - minZ) / 32));
        for (int x = minX; x <= maxX; x += step) for (int z = minZ; z <= maxZ; z += step) if (shape.containsColumn(x, z)) return true;
        return shape.containsColumn(maxX, maxZ);
    }

    // ---- Index ----

    private static List<ChunkRef> chunksOf(RegionShape shape) {
        UUID world = shape.world();
        if (shape instanceof RegionShape.Chunks chunks) {
            if (chunks.chunks().size() > MAX_INDEXED_CHUNKS) return null;
            return chunks.chunks().stream().map(p -> new ChunkRef(world, RegionShape.Chunks.chunkX(p), RegionShape.Chunks.chunkZ(p))).toList();
        }
        long firstX = Math.floorDiv(shape.minX(), 16), lastX = Math.floorDiv(shape.maxX(), 16);
        long firstZ = Math.floorDiv(shape.minZ(), 16), lastZ = Math.floorDiv(shape.maxZ(), 16);
        if ((lastX - firstX + 1) * (lastZ - firstZ + 1) > MAX_INDEXED_CHUNKS) return null;
        var result = new ArrayList<ChunkRef>();
        for (long x = firstX; x <= lastX; x++) for (long z = firstZ; z <= lastZ; z++) result.add(new ChunkRef(world, (int) x, (int) z));
        return result;
    }

    private void addIndex(Entry entry) {
        if (entry.chunks() == null) { large.add(entry); return; }
        for (ChunkRef chunk : entry.chunks()) index.merge(chunk, new Region[]{entry.region()}, (a, b) -> {
            Region[] merged = Arrays.copyOf(a, a.length + 1); merged[a.length] = b[0]; return merged;
        });
    }

    private void removeIndex(Entry entry) {
        if (entry.chunks() == null) { large.remove(entry); return; }
        for (ChunkRef chunk : entry.chunks()) index.computeIfPresent(chunk, (key, regions) -> {
            Region[] kept = Arrays.stream(regions).filter(r -> r != entry.region()).toArray(Region[]::new);
            return kept.length == 0 ? null : kept;
        });
    }

    /** Nur für Tests: alles leeren. */
    synchronized void clear() { byKey.clear(); index.clear(); large.clear(); revision++; }

    private void ensureLifecycle() {
        if (lifecycle.get() || Bukkit.getServer() == null || !Bukkit.isPrimaryThread()) return;
        Plugin core = main.getPlugin();
        if (core == null || !core.isEnabled() || !lifecycle.compareAndSet(false, true)) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDisable(PluginDisableEvent event) { if (event.getPlugin() != core) unregisterOwner(event.getPlugin()); }
        }, core);
        Bukkit.getPluginManager().registerEvents(new RegionTracker(this), core);
    }
}
