package Vystorm.vystorm_core.visual;

/** Integer position in the world captured by a BlockView. @since 0.29.0 */
public record BlockPosition(int x, int y, int z) {
    public Point3 centre() { return new Point3(x + .5, y + .5, z + .5); }
    public static BlockPosition containing(Point3 point) {
        if (Math.abs(point.x()) > 30_000_000 || Math.abs(point.z()) > 30_000_000
                || point.y() < Integer.MIN_VALUE || point.y() >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Position out of world bounds");
        return new BlockPosition((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }
}
