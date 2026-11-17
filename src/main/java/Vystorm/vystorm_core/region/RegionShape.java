package Vystorm.vystorm_core.region;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Räumliche Form einer Region. {@code world == null} gilt in jeder Welt. Alle Grenzen sind inklusiv. */
public sealed interface RegionShape {
    UUID world();
    int minY();
    int maxY();
    /** Umschließendes Rechteck in Blockkoordinaten (für den Chunk-Index). */
    int minX(); int minZ(); int maxX(); int maxZ();
    boolean containsColumn(int x, int z);

    default boolean contains(UUID worldId, int x, int y, int z) {
        if (world() != null && !world().equals(worldId)) return false;
        return y >= minY() && y <= maxY() && containsColumn(x, z);
    }

    /** Achsenparalleler Quader. */
    record Box(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements RegionShape {
        public Box {
            int x0 = Math.min(minX, maxX), x1 = Math.max(minX, maxX), y0 = Math.min(minY, maxY), y1 = Math.max(minY, maxY), z0 = Math.min(minZ, maxZ), z1 = Math.max(minZ, maxZ);
            minX = x0; maxX = x1; minY = y0; maxY = y1; minZ = z0; maxZ = z1;
        }
        @Override public boolean containsColumn(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }

    /** Senkrechter Zylinder um einen Mittelpunkt. */
    record Cylinder(UUID world, int centerX, int centerZ, int radius, int minY, int maxY) implements RegionShape {
        public Cylinder {
            if (radius < 0) throw new IllegalArgumentException("radius must be >= 0");
            int y0 = Math.min(minY, maxY), y1 = Math.max(minY, maxY); minY = y0; maxY = y1;
        }
        @Override public int minX() { return (int) Math.max(Integer.MIN_VALUE, (long) centerX - radius); }
        @Override public int maxX() { return (int) Math.min(Integer.MAX_VALUE, (long) centerX + radius); }
        @Override public int minZ() { return (int) Math.max(Integer.MIN_VALUE, (long) centerZ - radius); }
        @Override public int maxZ() { return (int) Math.min(Integer.MAX_VALUE, (long) centerZ + radius); }
        @Override public boolean containsColumn(int x, int z) {
            long dx = (long) x - centerX, dz = (long) z - centerZ;
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    /** Menge ganzer Chunks (z. B. Stadt-Claims). Chunks als {@link #pack(int, int)}. */
    record Chunks(UUID world, Set<Long> chunks, int minY, int maxY) implements RegionShape {
        public Chunks {
            Objects.requireNonNull(world, "world");
            chunks = Set.copyOf(chunks);
            int y0 = Math.min(minY, maxY), y1 = Math.max(minY, maxY); minY = y0; maxY = y1;
        }
        public static long pack(int chunkX, int chunkZ) { return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL); }
        public static int chunkX(long packed) { return (int) (packed >> 32); }
        public static int chunkZ(long packed) { return (int) packed; }
        @Override public boolean containsColumn(int x, int z) { return chunks.contains(pack(x >> 4, z >> 4)); }
        @Override public int minX() { return chunks.stream().mapToInt(Chunks::chunkX).min().orElse(0) << 4; }
        @Override public int maxX() { return (chunks.stream().mapToInt(Chunks::chunkX).max().orElse(0) << 4) + 15; }
        @Override public int minZ() { return chunks.stream().mapToInt(Chunks::chunkZ).min().orElse(0) << 4; }
        @Override public int maxZ() { return (chunks.stream().mapToInt(Chunks::chunkZ).max().orElse(0) << 4) + 15; }
    }

    /** Einfaches Polygon in der X/Z-Ebene (z. B. Fraktionsgebiete). Punkte als {x, z}. */
    record Polygon(UUID world, List<double[]> points, int minY, int maxY) implements RegionShape {
        public Polygon {
            if (points.size() < 3) throw new IllegalArgumentException("polygon needs at least 3 points");
            points = points.stream().map(p -> new double[]{p[0], p[1]}).toList();
            int y0 = Math.min(minY, maxY), y1 = Math.max(minY, maxY); minY = y0; maxY = y1;
        }
        @Override public int minX() { return (int) Math.floor(points.stream().mapToDouble(p -> p[0]).min().orElse(0)); }
        @Override public int maxX() { return (int) Math.ceil(points.stream().mapToDouble(p -> p[0]).max().orElse(0)); }
        @Override public int minZ() { return (int) Math.floor(points.stream().mapToDouble(p -> p[1]).min().orElse(0)); }
        @Override public int maxZ() { return (int) Math.ceil(points.stream().mapToDouble(p -> p[1]).max().orElse(0)); }
        @Override public boolean containsColumn(int x, int z) {
            double px = x + 0.5, pz = z + 0.5;
            boolean inside = false;
            for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
                double[] a = points.get(i), b = points.get(j);
                if ((a[1] > pz) != (b[1] > pz) && px < (b[0] - a[0]) * (pz - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
            }
            return inside;
        }
    }
}
