package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.picker.AreaHeight;
import Vystorm.vystorm_core.picker.AreaRules;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The box of an area pick and its limits (design 4.6): the height mode turns two corners into the box, then the box is
 * checked against the plugin's {@link AreaRules} under Core's {@link AreaCaps}, axis by axis, then the number of blocks.
 * Reasons are keys under {@code pickers.world.area.reason.} with their numbers as arguments. Pure. Core-internal.
 */
public final class AreaLimits {
    private AreaLimits() {}

    public static final String OTHER_WORLD = "other-world", TOO_BIG = "too-big", TOO_SMALL = "too-small", TOO_MANY = "too-many";

    /** Ok ({@code reason} null) or a reason key with its arguments ({@code size}, {@code max}, {@code min}, {@code blocks}). */
    public record Verdict(String reason, Map<String, String> args) {
        static final Verdict OK = new Verdict(null, Map.of());
        public boolean ok() { return reason == null; }
        public String arg(String name) { return args.get(name); }
        /** The arguments as name, value pairs for a language call. */
        public Object[] pairs() {
            Object[] out = new Object[args.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(args).entrySet()) { out[i++] = e.getKey(); out[i++] = e.getValue(); }
            return out;
        }
    }

    /**
     * The box from two corners after the height mode. {@code worldMin} is the lowest block height of the world,
     * {@code worldMax} the first height above it (Bukkit {@code getMinHeight}/{@code getMaxHeight}). FULL (and ASK
     * when {@code expanded}) spans the world height; FIXED goes up from the lower corner and is moved down at the top;
     * the corners' heights are kept inside the world otherwise.
     */
    public static Picked.Area box(UUID world, BlockPosition a, BlockPosition b, AreaRules rules, boolean expanded, int worldMin, int worldMax) {
        int top = worldMax - 1;
        int lo = Math.min(a.y(), b.y()), hi = Math.max(a.y(), b.y());
        AreaHeight mode = rules.height();
        if (mode == AreaHeight.FULL || (mode == AreaHeight.ASK && expanded)) {
            lo = worldMin;
            hi = top;
        } else if (mode == AreaHeight.FIXED) {
            lo = clamp(lo, worldMin, top);
            // in long: a fixed height near Integer.MAX_VALUE must not wrap around
            long want = (long) lo + rules.fixedHeight() - 1;
            if (want > top) {
                hi = top;
                lo = (int) Math.max(worldMin, (long) top - rules.fixedHeight() + 1);
            } else {
                hi = (int) want;
            }
        } else {
            lo = clamp(lo, worldMin, top);
            hi = clamp(hi, worldMin, top);
        }
        return new Picked.Area(world, new BlockPosition(a.x(), lo, a.z()), new BlockPosition(b.x(), hi, b.z()));
    }

    /**
     * The limits of a finished box: both corners in one world, each axis within the plugin's and Core's maximum, at
     * least the plugin's minimum, and at most the smaller of both block counts. With {@code fullHeight} the plugin's y
     * limits are not applied (it asked for or allowed the whole height), Core's cap still is.
     */
    public static Verdict check(Picked.Area box, boolean sameWorld, AreaRules rules, AreaCaps caps, boolean fullHeight) {
        if (!sameWorld) return new Verdict(OTHER_WORLD, Map.of());
        int maxX = Math.min(rules.maxX(), caps.x()), maxZ = Math.min(rules.maxZ(), caps.z());
        int maxY = fullHeight ? caps.y() : Math.min(rules.maxY(), caps.y());
        String size = size(box);
        if (box.sizeX() > maxX || box.sizeY() > maxY || box.sizeZ() > maxZ)
            return new Verdict(TOO_BIG, Map.of("size", size, "max", maxX + " x " + maxY + " x " + maxZ));
        int minY = fullHeight ? 1 : rules.minY();
        if (box.sizeX() < rules.minX() || box.sizeY() < minY || box.sizeZ() < rules.minZ())
            return new Verdict(TOO_SMALL, Map.of("size", size, "min", rules.minX() + " x " + minY + " x " + rules.minZ()));
        long maxVolume = Math.min(rules.maxVolume(), caps.volume());
        if (box.volume() > maxVolume) return new Verdict(TOO_MANY, Map.of("size", size, "blocks", count(box.volume()), "max", count(maxVolume)));
        return Verdict.OK;
    }

    /** "12 x 5 x 9". */
    public static String size(Picked.Area box) { return box.sizeX() + " x " + box.sizeY() + " x " + box.sizeZ(); }

    /** A block count with thousands grouped by commas ("7,497"). */
    public static String count(long n) { return String.format(Locale.ROOT, "%,d", n); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
