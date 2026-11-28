package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.blocks.EditAsk;
import Vystorm.vystorm_core.blocks.EditGuard;
import Vystorm.vystorm_core.blocks.EditVerdict;
import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionService;
import Vystorm.vystorm_core.region.RegionShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Core's own answer from the regions: no region in the chunk sets the flag {@code build}: allow; a region that denies
 * {@code build} covers the whole asked box and no region there allows it: deny; otherwise a per-cell test that reads
 * the regions the way {@code RegionService.flag} does (the highest priority region that sets the flag decides, no
 * flag means allowed). Regions whose {@code owner()} is in the skipped set (plugins with an own-regions guard) are
 * left out: their guard answers for them.
 *
 * <p>The cell test holds the shapes and values of the chunk's flagged regions as they were when asked (immutable,
 * highest priority first) and allocates nothing per cell, so it may run on any thread; a cell outside the asked chunk
 * falls back to a live lookup.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class RegionEditGuard implements EditGuard {
    static final String FLAG = "build";
    private final RegionService regions;

    public RegionEditGuard(RegionService regions) { this.regions = Objects.requireNonNull(regions, "regions"); }

    @Override
    public EditVerdict ask(EditAsk ask) { return ask(ask, Set.of()); }

    /** As {@link #ask(EditAsk)}, without the {@code build} flag of the regions whose owner is in {@code skipped}. */
    public EditVerdict ask(EditAsk ask, Set<String> skipped) {
        UUID world = ask.world().getUID();
        int chunkX = ask.chunkX(), chunkZ = ask.chunkZ();
        int minX = chunkX << 4, minZ = chunkZ << 4, maxX = minX + 15, maxZ = minZ + 15;
        List<Region> flagged = new ArrayList<>();   // in the chunk's columns at any height, highest priority first
        boolean anyInRange = false, anyAllows = false, anyDenies = false, deniedEverywhere = false;
        for (Region region : regions.intersecting(world, minX, minZ, maxX, maxZ)) {
            if (!region.flags().containsKey(FLAG) || skipped.contains(region.owner())) continue;
            flagged.add(region);
            RegionShape shape = region.shape();
            if (shape.maxY() < ask.minY() || shape.minY() > ask.maxY()) continue;
            anyInRange = true;
            if (region.flags().get(FLAG)) { anyAllows = true; continue; }
            anyDenies = true;
            if (!deniedEverywhere && covers(shape, minX, minZ, ask.minY(), ask.maxY())) deniedEverywhere = true;
        }
        if (!anyInRange || !anyDenies) return new EditVerdict.Allow();
        if (deniedEverywhere && !anyAllows) return new EditVerdict.Deny("regions");
        // shapes and values in decision order; a chunk set becomes a box of this chunk (no boxed lookup per cell)
        List<RegionShape> shapes = new ArrayList<>(flagged.size());
        List<Boolean> values = new ArrayList<>(flagged.size());
        for (Region region : flagged) {
            RegionShape shape = region.shape();
            if (shape instanceof RegionShape.Chunks) {
                if (!shape.containsColumn(minX, minZ)) continue;
                shape = new RegionShape.Box(world, minX, shape.minY(), minZ, maxX, shape.maxY(), maxZ);
            }
            shapes.add(shape);
            values.add(region.flags().get(FLAG));
        }
        RegionShape[] order = shapes.toArray(new RegionShape[0]);
        boolean[] allowed = new boolean[order.length];
        for (int i = 0; i < allowed.length; i++) allowed[i] = values.get(i);
        Set<String> skip = Set.copyOf(skipped);
        return new EditVerdict.Partial((x, y, z) -> {
            if (x >> 4 != chunkX || z >> 4 != chunkZ) return live(world, x, y, z, skip);
            for (int i = 0; i < order.length; i++) if (order[i].contains(world, x, y, z)) return allowed[i];
            return true;
        });
    }

    /** A cell outside the asked chunk: the regions now, without the skipped owners. */
    private boolean live(UUID world, int x, int y, int z, Set<String> skipped) {
        for (Region region : regions.at(world, x, y, z)) {
            if (skipped.contains(region.owner())) continue;
            Boolean value = region.flags().get(FLAG);
            if (value != null) return value;
        }
        return true;
    }

    private static boolean covers(RegionShape shape, int minX, int minZ, int minY, int maxY) {
        if (shape.minY() > minY || shape.maxY() < maxY) return false;
        if (shape instanceof RegionShape.Box box)
            return box.minX() <= minX && box.maxX() >= minX + 15 && box.minZ() <= minZ && box.maxZ() >= minZ + 15;
        for (int x = minX; x <= minX + 15; x++)
            for (int z = minZ; z <= minZ + 15; z++) if (!shape.containsColumn(x, z)) return false;
        return true;
    }
}
