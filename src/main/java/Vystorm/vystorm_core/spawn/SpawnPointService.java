package Vystorm.vystorm_core.spawn;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Gemeinsames Register fest gesetzter Spawnpunkte. Das Plugin, das eine Region baut (z. B. WorldForge für
 * {@code dungeon:<id>}), meldet ihre Spawnpunkte; Spawner (SpawnDirector) lesen sie und belegen sie – ohne
 * Abhängigkeit aufeinander.
 *
 * <p>Schreiben synchronisiert (Server-Thread), Lesen lock-frei und aus jedem Thread erlaubt. Ein deaktiviertes Plugin
 * verliert seine Punkte über {@link Vystorm.vystorm_core.lifecycle.CoreLifecycle}.</p>
 *
 * <p>Preferred access: {@code api.area().spawnPoints()}.
 *
 * @since 0.13.0
 */
public final class SpawnPointService {
    private static final SpawnPointService INSTANCE = new SpawnPointService();
    private record Entry(String owner, List<SpawnPoint> points) {}

    private final Map<String, Entry> byRegion = new ConcurrentHashMap<>();
    private volatile long revision;

    private SpawnPointService() {}

    public static SpawnPointService get() { return INSTANCE; }

    /** Zählt bei jeder Änderung hoch (für Caches in Abfragern). */
    public long revision() { return revision; }

    /**
     * Setzt die Spawnpunkte einer Region (ersetzt alle bisherigen). Eine leere Liste entfernt die Region aus dem
     * Register. Punkte einer Region, die ein anderes Plugin gemeldet hat, werden nicht übernommen.
     */
    public synchronized void sync(Plugin owner, String region, Collection<SpawnPoint> points) {
        Objects.requireNonNull(region, "region");
        String name = owner == null ? "" : owner.getName();
        var old = byRegion.get(region);
        if (old != null && !old.owner().equals(name)) throw new IllegalStateException("Spawn points of " + region + " belong to " + old.owner());
        var list = List.copyOf(points);
        for (var p : list) if (!p.region().equals(region)) throw new IllegalArgumentException("Spawn point " + p.id() + " belongs to " + p.region());
        if (list.isEmpty()) { if (byRegion.remove(region) != null) revision++; return; }
        if (old != null && old.points().equals(list)) return;
        byRegion.put(region, new Entry(name, list));
        revision++;
    }

    /** Behält von den Regionen dieses Plugins nur die genannten (für Abgleiche nach Neustart oder Abbau). */
    public synchronized void retain(Plugin owner, Collection<String> regions) {
        String name = owner == null ? "" : owner.getName();var keep = Set.copyOf(regions);
        if (byRegion.entrySet().removeIf(e -> e.getValue().owner().equals(name) && !keep.contains(e.getKey()))) revision++;
    }

    /** Entfernt alle Punkte eines Plugins. */
    public synchronized int unregisterOwner(Plugin owner) {
        String name = owner == null ? "" : owner.getName();int before = byRegion.size();
        byRegion.values().removeIf(e -> e.owner().equals(name));
        int removed = before - byRegion.size();if (removed > 0) revision++;
        return removed;
    }

    public List<SpawnPoint> inRegion(String region) { var e = byRegion.get(region); return e == null ? List.of() : e.points(); }
    public Set<String> regions() { return Set.copyOf(byRegion.keySet()); }
    public List<SpawnPoint> all() { var out = new ArrayList<SpawnPoint>(); byRegion.values().forEach(e -> out.addAll(e.points())); return out; }
    public Optional<String> owner(String region) { return Optional.ofNullable(byRegion.get(region)).map(Entry::owner); }

    /** Nur für Tests: alles leeren. */
    synchronized void clear() { byRegion.clear(); revision++; }
}
