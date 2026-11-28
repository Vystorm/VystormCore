package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import Vystorm.vystorm_core.visual.Point3;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.block.BlockFace;

/**
 * Turns what the player aims at into a world pick target (design 4.3): pure, no server. The runtime does the ray trace
 * itself on every trigger (so a block click, an air swing and an entity hit follow the same rules) and hands the result
 * over as a {@link Hit}; this class applies the kind's rules (face filter, container, the plugin's block filter result,
 * entity types, allowed world) and names the reason when nothing may be chosen. Reasons are keys under
 * {@code pickers.world.reason.}. Core-internal.
 */
public final class WorldTargets {
    private WorldTargets() {}

    public static final String NOTHING = "nothing", BLOCK = "block", FACE = "face", CONTAINER = "container", ENTITY = "entity", WORLD = "world";

    /** What the ray met. */
    public sealed interface Hit {
        /** Nothing within reach. */
        record None() implements Hit {}

        /**
         * A block and the face the ray entered through; {@code accepted} = the plugin's block filter said yes,
         * {@code container} = its state is a container.
         */
        record OnBlock(BlockPosition block, BlockFace face, String type, boolean accepted, boolean container) implements Hit {
            public OnBlock { Objects.requireNonNull(block); Objects.requireNonNull(face); Objects.requireNonNull(type); }
        }

        /** An entity with its box; {@code accepted} = its type is one the pick accepts. */
        record OnEntity(UUID id, String type, Point3 at, BlockFace facing, Point3 boxMin, Point3 boxMax, boolean accepted) implements Hit {
            public OnEntity { Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(at); Objects.requireNonNull(facing); Objects.requireNonNull(boxMin); Objects.requireNonNull(boxMax); }
        }
    }

    public static final Hit NONE = new Hit.None();

    /** A target ({@code reason} null) or why there is none; {@code hit} stays for the outline (red when not ok). */
    public record Resolution(Picked target, String reason, Hit hit) {
        public boolean ok() { return target != null && reason == null; }
    }

    /** POSITION: where the player stands (feet point), facing = yaw rounded to a side. */
    public static Resolution position(UUID world, Point3 feet, float yaw, float pitch, boolean worldAllowed) {
        Picked.Position p = new Picked.Position(world, feet, yaw, pitch, facing(yaw));
        return worldAllowed ? new Resolution(p, null, NONE) : new Resolution(null, WORLD, NONE);
    }

    /** AREA: a corner given as a cell (typed coordinates, the block below the feet); any cell counts, also air. */
    public static Resolution areaCell(UUID world, BlockPosition cell, boolean worldAllowed) {
        return worldAllowed ? new Resolution(new Picked.Area(world, cell, cell), null, NONE) : new Resolution(null, WORLD, NONE);
    }

    /** BLOCK, FACE, CONTAINER, ENTITY and AREA (the aimed cell as a one-cell area) from a ray result. */
    public static Resolution resolve(PickKind kind, UUID world, Hit hit, Set<BlockFace> faces, boolean worldAllowed) {
        Objects.requireNonNull(kind, "kind");
        if (kind == PickKind.POSITION || kind == PickKind.PLAYERS) throw new IllegalArgumentException(kind + " is not resolved from a ray");
        if (!worldAllowed) return new Resolution(null, WORLD, hit);
        if (kind == PickKind.AREA) {
            // a corner is a cell: the plugin's block filter does not apply (an area may start at any block)
            if (!(hit instanceof Hit.OnBlock b)) return new Resolution(null, NOTHING, NONE);
            return new Resolution(new Picked.Area(world, b.block(), b.block()), null, hit);
        }
        if (kind == PickKind.ENTITY) {
            if (!(hit instanceof Hit.OnEntity e)) return new Resolution(null, NOTHING, NONE);
            if (!e.accepted()) return new Resolution(null, ENTITY, hit);
            return new Resolution(new Picked.EntityHit(world, e.id(), e.type(), e.at(), e.facing()), null, hit);
        }
        if (!(hit instanceof Hit.OnBlock b)) return new Resolution(null, NOTHING, NONE);
        return switch (kind) {
            case BLOCK -> b.accepted() ? new Resolution(new Picked.Block(world, b.block(), b.type()), null, hit) : new Resolution(null, BLOCK, hit);
            case FACE -> !b.accepted() ? new Resolution(null, BLOCK, hit)
                    : !faces.contains(b.face()) ? new Resolution(null, FACE, hit)
                    : new Resolution(new Picked.Face(world, b.block(), b.face(), b.type()), null, hit);
            case CONTAINER -> b.container() && b.accepted() ? new Resolution(new Picked.Container(world, b.block(), b.type()), null, hit)
                    : new Resolution(null, b.container() ? BLOCK : CONTAINER, hit);
            default -> throw new IllegalArgumentException(kind.name());
        };
    }

    /** Minecraft yaw (0 = south, 90 = west, 180 = north, 270 = east) rounded to a side. */
    public static BlockFace facing(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (quarter) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    /** The face of {@code block} that looks towards {@code eye} (the largest axis of eye minus block centre). */
    public static BlockFace faceTowards(BlockPosition block, Point3 eye) {
        Point3 d = eye.subtract(block.centre());
        double ax = Math.abs(d.x()), ay = Math.abs(d.y()), az = Math.abs(d.z());
        if (ay >= ax && ay >= az) return d.y() >= 0 ? BlockFace.UP : BlockFace.DOWN;
        if (ax >= az) return d.x() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return d.z() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** The cell a BUILD protection asks about: the block itself, the cell in front of a face, the cell of a position or entity. */
    public static BlockPosition editCell(Picked p) {
        return p instanceof Picked.Face f ? f.front() : p.cell();
    }

    /** A stable key of what is aimed at: a new key means the check runs again. */
    public static String key(Resolution r) {
        if (r.target() instanceof Picked.Position p) return "pos:" + p.cell() + ":" + p.facing();
        return switch (r.hit()) {
            case Hit.OnBlock b -> "block:" + b.block() + ":" + b.face() + ":" + b.type();
            case Hit.OnEntity e -> "entity:" + e.id() + ":" + BlockPosition.containing(e.at());
            case Hit.None n -> "none:" + r.reason();
        };
    }
}
