package Vystorm.vystorm_core.picker;

import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.visual.Point3;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Description of one world pick (0.71.0): a single target or an area from two corners; what may be chosen, how far, which rules apply and what
 * happens with the choice. Built once per click with {@link #builder(Object)}; immutable. Texts are String,
 * {@code Lang.ref} or Adventure {@code Component}.
 *
 * <pre>{@code
 * WorldPick pick = WorldPick.builder(lang.ref("pick.board.title"))
 *         .target(PickKind.FACE)                    // POSITION, BLOCK, FACE, CONTAINER, ENTITY, AREA
 *         .reach(10)                                // default 6, at most pickers.world.max-reach (64)
 *         .faces(PickFaces.SIDES)                   // FACE only
 *         .blocks(b -> b.getType().isOccluding())   // BLOCK, FACE, CONTAINER
 *         .protection(PickProtection.BUILD)         // NONE (default) or BUILD (api.area().mayEdit)
 *         .check((viewer, c) -> fits(c) ? PickVerdict.ok() : PickVerdict.deny(lang.ref("pick.board.no-room")))
 *         .outline((viewer, c) -> List.of(boardBox(c)))   // extra boxes the preview draws
 *         .onPick((viewer, c) -> createBoard(viewer, (Picked.Face) c.target()))   // returns a panel Result
 *         .build();
 * return core.gui().pickers().world(action, pick);
 * }</pre>
 *
 * @since 0.71.0
 */
public final class WorldPick {
    /** Default and largest timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120), MAX_TIMEOUT = Duration.ofSeconds(600);
    /** Default reach and the hard cap of any reach (the config {@code pickers.world.max-reach} may lower it). */
    public static final int DEFAULT_REACH = 6, MAX_REACH = 64;

    /**
     * Whether a target may be chosen. Server thread; runs whenever the aimed target changes (at most five times a second,
     * cached per target) and again right before {@link Handler}: keep it cheap. The reason shows in the action bar.
     * An area corner or box may lie in chunks that are not loaded (typed coordinates): do not read blocks there
     * without {@code World.isChunkLoaded}, or the check loads chunks on the server thread.
     */
    @FunctionalInterface
    public interface Check { PickVerdict check(Player viewer, PickCandidate candidate); }

    /** Extra boxes the preview draws around a target, e.g. the footprint of a board on a wall. Server thread; keep it cheap. */
    @FunctionalInterface
    public interface Outline { List<Box> boxes(Player viewer, PickCandidate candidate); }

    /** What happens with the choice. Server thread; return {@code Result.error(..)} to stay in pick mode with the message. */
    @FunctionalInterface
    public interface Handler { Result picked(Player viewer, PickCandidate picked); }

    /** Told how the flow ended (also after {@link PickEnd#DONE}). Server thread. */
    @FunctionalInterface
    public interface EndHandler { void ended(Player viewer, PickEnd end); }

    /** A box in world coordinates; corners in any order. */
    public record Box(Point3 min, Point3 max) {
        public Box {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            Point3 a = min, b = max;
            min = new Point3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
            max = new Point3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        }
    }

    private final Object title;
    private final Object subtitle;
    private final PickKind kind;
    private final int reach;
    private final Set<BlockFace> faces;
    private final Predicate<org.bukkit.block.Block> blocks;
    private final Set<EntityType> entities;
    private final Predicate<World> worlds;
    private final PickProtection protection;
    private final AreaRules area;
    private final String bypass;
    private final Check check;
    private final Outline outline;
    private final Duration timeout;
    private final Handler onPick;
    private final EndHandler onEnd;

    private WorldPick(Builder b) {
        title = b.title;
        subtitle = b.subtitle;
        kind = b.kind;
        reach = b.reach;
        faces = Collections.unmodifiableSet(EnumSet.copyOf(b.faces));
        blocks = b.blocks;
        entities = b.entities.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(b.entities));
        worlds = b.worlds;
        protection = b.protection;
        area = b.area;
        bypass = b.bypass;
        check = b.check;
        outline = b.outline;
        timeout = b.timeout;
        onPick = b.onPick;
        onEnd = b.onEnd;
    }

    public static Builder builder(Object title) { return new Builder(title); }

    public Object title() { return title; }
    /** Null without one; shown in the chat line at the start. */
    public Object subtitle() { return subtitle; }
    public PickKind kind() { return kind; }
    /** Ray length in blocks, 1 to {@link #MAX_REACH}; the config's {@code pickers.world.max-reach} caps it further. */
    public int reach() { return reach; }
    /** Faces a {@link PickKind#FACE} pick accepts. */
    public Set<BlockFace> faces() { return faces; }
    /** Which blocks count for BLOCK, FACE and CONTAINER (the live block under the crosshair). */
    public Predicate<org.bukkit.block.Block> blocks() { return blocks; }
    /** Entity types an ENTITY pick accepts; empty = every entity except players. */
    public Set<EntityType> entities() { return entities; }
    public Predicate<World> worlds() { return worlds; }
    public PickProtection protection() { return protection; }
    /** The limits of an {@link PickKind#AREA} pick ({@link AreaRules#DEFAULT} unless set); ignored by single targets. @since 0.71.0 */
    public AreaRules area() { return area; }
    /** Permission that skips {@link PickProtection#BUILD}; null without one. */
    public String bypass() { return bypass; }
    public Check check() { return check; }
    /** Null without one. */
    public Outline outline() { return outline; }
    public Duration timeout() { return timeout; }
    public Handler onPick() { return onPick; }
    /** Null without one. */
    public EndHandler onEnd() { return onEnd; }

    public static final class Builder {
        private final Object title;
        private Object subtitle;
        private PickKind kind = PickKind.BLOCK;
        private int reach = DEFAULT_REACH;
        private Set<BlockFace> faces = PickFaces.ANY;
        private Predicate<org.bukkit.block.Block> blocks = b -> true;
        private Set<EntityType> entities = Set.of();
        private Predicate<World> worlds = w -> true;
        private PickProtection protection = PickProtection.NONE;
        private AreaRules area = AreaRules.DEFAULT;
        private String bypass;
        private Check check = (v, c) -> PickVerdict.ok();
        private Outline outline;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Handler onPick;
        private EndHandler onEnd;

        private Builder(Object title) { this.title = Objects.requireNonNull(title, "title"); }

        public Builder subtitle(Object subtitle) { this.subtitle = subtitle; return this; }

        /** What is chosen (default {@link PickKind#BLOCK}); {@link PickKind#PLAYERS} is not a world pick. */
        public Builder target(PickKind kind) {
            Objects.requireNonNull(kind, "kind");
            if (!kind.world()) throw new IllegalArgumentException("target(" + kind + ") is not a world pick: use PlayerPick");
            this.kind = kind;
            return this;
        }

        /**
         * Ray length in blocks (default 6); values above {@link #MAX_REACH} are capped, below 1 refused. A pick that keeps
         * 6 (also when set explicitly) gets the server's {@code pickers.world.default-reach} instead.
         */
        public Builder reach(int blocks) {
            if (blocks < 1) throw new IllegalArgumentException("reach must be at least 1");
            this.reach = Math.min(MAX_REACH, blocks);
            return this;
        }

        /** Faces a FACE pick accepts (default {@link PickFaces#ANY}); only the six block faces. */
        public Builder faces(Set<BlockFace> faces) {
            Objects.requireNonNull(faces, "faces");
            if (faces.isEmpty()) throw new IllegalArgumentException("faces must not be empty");
            for (BlockFace f : faces) if (!PickFaces.ANY.contains(f)) throw new IllegalArgumentException("not a block face: " + f);
            this.faces = faces;
            return this;
        }

        /** Which blocks count for BLOCK, FACE and CONTAINER (default all; CONTAINER also needs a container state). */
        public Builder blocks(Predicate<org.bukkit.block.Block> filter) { this.blocks = Objects.requireNonNull(filter); return this; }

        /** Entity types an ENTITY pick accepts (default: every entity except players). */
        public Builder entities(EntityType... types) { return entities(Set.of(types)); }

        public Builder entities(Set<EntityType> types) {
            Objects.requireNonNull(types, "types");
            for (EntityType t : types) Objects.requireNonNull(t);
            this.entities = Set.copyOf(types);
            return this;
        }

        /** Worlds in which a target may be chosen (default all); elsewhere the preview is red with a reason. */
        public Builder worlds(Predicate<World> filter) { this.worlds = Objects.requireNonNull(filter); return this; }

        public Builder protection(PickProtection protection) { this.protection = Objects.requireNonNull(protection); return this; }

        /** Limits of an {@link PickKind#AREA} pick (default {@link AreaRules#DEFAULT}: only Core's caps, height as picked). @since 0.71.0 */
        public Builder area(AreaRules rules) { this.area = Objects.requireNonNull(rules, "rules"); return this; }

        /** Permission that skips {@link PickProtection#BUILD} (e.g. the plugin's admin bypass). */
        public Builder bypass(String permission) { this.bypass = permission == null || permission.isBlank() ? null : permission; return this; }

        public Builder check(Check check) { this.check = Objects.requireNonNull(check); return this; }

        public Builder outline(Outline outline) { this.outline = outline; return this; }

        /** Idle time until pick mode ends (from the last click or target change; 10 s to 600 s, default 120 s). */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            long s = timeout.toSeconds();
            this.timeout = Duration.ofSeconds(Math.max(10, Math.min(MAX_TIMEOUT.toSeconds(), s)));
            return this;
        }

        public Builder onPick(Handler handler) { this.onPick = Objects.requireNonNull(handler); return this; }

        public Builder onEnd(EndHandler handler) { this.onEnd = handler; return this; }

        public WorldPick build() {
            if (onPick == null) throw new IllegalStateException("WorldPick needs onPick(...)");
            return new WorldPick(this);
        }
    }
}
