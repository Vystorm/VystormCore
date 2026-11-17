package Vystorm.vystorm_core._advancedMethods.areaHandler;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.*;

/** In-memory area registry. Use on the server thread; updates rebuild only the affected area's index. */
public class AreaHandler implements AreaInterface {
    private static final long MAX_INDEXED_CHUNKS = 65536;
    private record Chunk(long x, long z) {}
    private record Registered(Area bounds, Set<Chunk> chunks) {}
    private final Map<Area, Registered> areas = new LinkedHashMap<>();
    private final Map<Chunk, Set<Area>> chunks = new HashMap<>();
    private final Set<Area> largeAreas = new LinkedHashSet<>();
    /** Regionsschlüssel je gespiegelter Area; mehrere Areas mit gleichem Tag erhalten eindeutige IDs. */
    private final Map<Area, String[]> mirrored = new IdentityHashMap<>();

    @SuppressWarnings("deprecation")
    @Override public Area getArea() { return new Area(); }

    @Override
    @SuppressWarnings("deprecation")
    public void register(Area area) {
        Objects.requireNonNull(area, "area").validate();
        if (areas.containsKey(area)) { update(area); return; }
        add(area);
        DebugMessages.mid("[AreaHandler] Area registered; type=" + area.getAreaType());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean update(Area area) {
        Objects.requireNonNull(area, "area").validate();
        if (!areas.containsKey(area)) return false;
        removeIndex(area);
        add(area);
        DebugMessages.low("[AreaHandler] Area bounds and chunk index updated");
        return true;
    }

    private void add(Area area) {
        Area bounds = area.copy();
        long minX, maxX, minZ, maxZ;
        if (bounds.getAreaType() == AreaType.Rectangle) {
            minX = Math.min(bounds.getCorner1X(), bounds.getCorner2X());
            maxX = Math.max(bounds.getCorner1X(), bounds.getCorner2X());
            minZ = Math.min(bounds.getCorner1Z(), bounds.getCorner2Z());
            maxZ = Math.max(bounds.getCorner1Z(), bounds.getCorner2Z());
        } else {
            minX = (long) bounds.getCenterX() - bounds.getRadius();
            maxX = (long) bounds.getCenterX() + bounds.getRadius();
            minZ = (long) bounds.getCenterZ() - bounds.getRadius();
            maxZ = (long) bounds.getCenterZ() + bounds.getRadius();
        }
        long firstX = Math.floorDiv(minX, 16), lastX = Math.floorDiv(maxX, 16);
        long firstZ = Math.floorDiv(minZ, 16), lastZ = Math.floorDiv(maxZ, 16);
        Set<Chunk> keys = new HashSet<>();
        if ((lastX - firstX + 1) * (lastZ - firstZ + 1) > MAX_INDEXED_CHUNKS) {
            largeAreas.add(area);
        } else {
            for (long x = firstX; x <= lastX; x++) {
                for (long z = firstZ; z <= lastZ; z++) {
                    Chunk key = new Chunk(x, z);
                    keys.add(key);
                    chunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(area);
                }
            }
        }
        areas.put(area, new Registered(bounds, keys));
        mirror(area, bounds);
    }

    /** Jede Area erscheint zusätzlich im gemeinsamen Regionsdienst (Art = NBT-Präfix, ID = Rest des Tags). */
    private void mirror(Area area, Area bounds) {
        try {
            var key = regionKey(area, bounds);
            String base = key[1];
            for (int n = 2; mirrored.values().stream().anyMatch(k -> k[0].equals(key[0]) && k[1].equals(key[1])); n++) key[1] = base + "#" + n;
            mirrored.put(area, key);
            Vystorm.vystorm_core.region.RegionShape shape = switch (bounds.getAreaType()) {
                case Rectangle -> new Vystorm.vystorm_core.region.RegionShape.Box(bounds.getWorld(), bounds.getCorner1X(), bounds.getY1(), bounds.getCorner1Z(), bounds.getCorner2X(), bounds.getY2(), bounds.getCorner2Z());
                case Cylinder -> new Vystorm.vystorm_core.region.RegionShape.Cylinder(bounds.getWorld(), bounds.getCenterX(), bounds.getCenterZ(), bounds.getRadius(), bounds.getY1(), bounds.getY2());
                case fullCircle -> new Vystorm.vystorm_core.region.RegionShape.Cylinder(bounds.getWorld(), bounds.getCenterX(), bounds.getCenterZ(), bounds.getRadius(), Integer.MIN_VALUE, Integer.MAX_VALUE);
            };
            var region = Vystorm.vystorm_core.region.Region.of(key[0], key[1], shape).withOwner("core-area")
                    .withAttributes(java.util.Map.of("nbt", bounds.getNBT() == null ? "" : bounds.getNBT()));
            Vystorm.vystorm_core.region.RegionService.get().register(null, region);
        } catch (RuntimeException failure) {
            DebugMessages.mid("[AreaHandler] Area not mirrored as region: " + failure.getMessage());
        }
    }

    static String[] regionKey(Area area, Area bounds) {
        String nbt = bounds.getNBT() == null ? "" : bounds.getNBT().trim();
        int colon = nbt.indexOf(':');
        String kind = (colon < 0 ? nbt : nbt.substring(0, colon)).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (kind.isEmpty()) kind = "area";
        if (kind.length() > 40) kind = kind.substring(0, 40);
        String id = colon < 0 || colon == nbt.length() - 1 ? "area-" + Integer.toHexString(System.identityHashCode(area)) : nbt.substring(colon + 1);
        if (id.length() > 128) id = id.substring(0, 128);
        return new String[]{kind, id};
    }

    private void removeIndex(Area area) {
        Registered registered = areas.remove(area);
        if (registered == null) return;
        String[] regionKey = mirrored.remove(area);
        if (regionKey != null) Vystorm.vystorm_core.region.RegionService.get().unregister(regionKey[0], regionKey[1]);
        largeAreas.remove(area);
        for (Chunk key : registered.chunks()) {
            Set<Area> values = chunks.get(key);
            values.remove(area);
            if (values.isEmpty()) chunks.remove(key);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean unregister(Area area) {
        if (!areas.containsKey(area)) return false;
        removeIndex(area);
        DebugMessages.mid("[AreaHandler] Area unregistered");
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<Area> find(Location location) {
        if (location == null || location.getWorld() == null) return List.of();
        Chunk key = new Chunk((long) Math.floor(location.getX() / 16), (long) Math.floor(location.getZ() / 16));
        Set<Area> candidates = new LinkedHashSet<>(chunks.getOrDefault(key, Set.of()));
        candidates.addAll(largeAreas);
        List<Area> matches = new ArrayList<>();
        for (Area area : candidates) {
            if (areas.get(area).bounds().contains(location)) matches.add(area);
        }
        return List.copyOf(matches);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void clear() {
        for (String[] key : mirrored.values()) Vystorm.vystorm_core.region.RegionService.get().unregister(key[0], key[1]);
        mirrored.clear();
        areas.clear(); chunks.clear(); largeAreas.clear();
        DebugMessages.mid("[AreaHandler] Area registry cleared");
    }

    @Override
    @Deprecated
    @SuppressWarnings("unchecked")
    public ArrayList<Area>[] compare(Player player) {
        List<Area> matches = find(Objects.requireNonNull(player, "player").getLocation());
        return matches.isEmpty() ? new ArrayList[0] : new ArrayList[]{new ArrayList<>(matches)};
    }
}
