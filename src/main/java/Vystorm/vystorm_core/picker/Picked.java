package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.block.BlockFace;

/**
 * The target of a world pick (0.71.0), as values only: no live {@code Location}, {@code Block} or {@code Entity}, so a
 * plugin can keep it across ticks. The world may have changed since the pick; re-check business rules when using it.
 * Block types and entity types are their lower-case Bukkit names ({@code oak_planks}, {@code item_frame}).
 *
 * @since 0.71.0
 */
public sealed interface Picked permits Picked.Position, Picked.Block, Picked.Face, Picked.Container, Picked.EntityHit, Picked.Area {
    /** The world's UUID. */
    UUID world();

    /** The block cell the target is in (for a position and an entity: the block that contains its point; for an area its lowest corner). */
    BlockPosition cell();

    /**
     * Where the player stood ({@link PickKind#POSITION}); {@code facing} is the yaw rounded to one of
     * {@code NORTH, EAST, SOUTH, WEST}.
     */
    record Position(UUID world, Point3 at, float yaw, float pitch, BlockFace facing) implements Picked {
        public Position {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(facing, "facing");
        }
        @Override public BlockPosition cell() { return BlockPosition.containing(at); }
    }

    /** A block ({@link PickKind#BLOCK}). */
    record Block(UUID world, BlockPosition block, String blockType) implements Picked {
        public Block {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(blockType, "blockType");
        }
        @Override public BlockPosition cell() { return block; }
    }

    /** One face of a block ({@link PickKind#FACE}); the cell in front of it is {@code block} moved one step to {@code face}. */
    record Face(UUID world, BlockPosition block, BlockFace face, String blockType) implements Picked {
        public Face {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(blockType, "blockType");
        }
        @Override public BlockPosition cell() { return block; }

        /** The cell in front of the face (where a sign, a frame or a board would go). */
        public BlockPosition front() { return new BlockPosition(block.x() + face.getModX(), block.y() + face.getModY(), block.z() + face.getModZ()); }
    }

    /** A block whose state is a container ({@link PickKind#CONTAINER}). */
    record Container(UUID world, BlockPosition block, String blockType) implements Picked {
        public Container {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(blockType, "blockType");
        }
        @Override public BlockPosition cell() { return block; }
    }

    /**
     * An entity ({@link PickKind#ENTITY}): its UUID, type, where it was and its facing (hanging entities: the side they
     * face; others: the yaw rounded to a side).
     */
    record EntityHit(UUID world, UUID entity, String entityType, Point3 at, BlockFace facing) implements Picked {
        public EntityHit {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(facing, "facing");
        }
        @Override public BlockPosition cell() { return BlockPosition.containing(at); }
    }

    /**
     * A box of blocks from two corners ({@link PickKind#AREA}), both ends included: {@code min} is the lowest corner,
     * {@code max} the highest (corners in any order are sorted). Sizes count both ends, so one cell is 1 x 1 x 1.
     *
     * @since 0.71.0
     */
    record Area(UUID world, BlockPosition min, BlockPosition max) implements Picked {
        public Area {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            BlockPosition a = min, b = max;
            min = new BlockPosition(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
            max = new BlockPosition(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        }
        @Override public BlockPosition cell() { return min; }

        public int sizeX() { return max.x() - min.x() + 1; }
        public int sizeY() { return max.y() - min.y() + 1; }
        public int sizeZ() { return max.z() - min.z() + 1; }

        /** Number of blocks in the box. */
        public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

        /** Whether the cell lies in the box (both ends included). */
        public boolean contains(int x, int y, int z) {
            return x >= min.x() && x <= max.x() && y >= min.y() && y <= max.y() && z >= min.z() && z <= max.z();
        }

        /** The same box as a region shape (e.g. to create a Core region from it). */
        public Vystorm.vystorm_core.region.RegionShape.Box shape() {
            return new Vystorm.vystorm_core.region.RegionShape.Box(world, min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        }
    }
}
