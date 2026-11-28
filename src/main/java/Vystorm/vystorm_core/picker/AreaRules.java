package Vystorm.vystorm_core.picker;

import java.util.Objects;

/**
 * The limits of an area pick (0.71.0): smallest and largest size per axis in blocks (both ends counted), the most
 * blocks in all, and how the height is decided ({@link AreaHeight}). Core's own caps (config
 * {@code pickers.world.max-area}, default 512 x 384 x 512 and 50,000,000 blocks) always apply on top, so a plugin that
 * asks for more still gets at most those. Under {@link AreaHeight#FULL} (and after "Full height" in the confirm view of
 * {@link AreaHeight#ASK}) the y limits of these rules are not applied, only Core's cap. Immutable.
 *
 * <pre>{@code
 * AreaRules rules = AreaRules.builder().minSize(3, 3, 3).maxSize(64, 128, 64).maxVolume(200_000)
 *         .height(AreaHeight.ASK).build();
 * }</pre>
 *
 * @since 0.71.0
 */
public final class AreaRules {
    /** No own limits (only Core's caps), height as picked. */
    public static final AreaRules DEFAULT = builder().build();

    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final long maxVolume;
    private final AreaHeight height;
    private final int fixedHeight;

    private AreaRules(Builder b) {
        minX = b.minX; minY = b.minY; minZ = b.minZ;
        maxX = b.maxX; maxY = b.maxY; maxZ = b.maxZ;
        maxVolume = b.maxVolume;
        height = b.height;
        fixedHeight = b.fixedHeight;
    }

    public static Builder builder() { return new Builder(); }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    /** {@link Integer#MAX_VALUE} without an own limit. */
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    /** {@link Long#MAX_VALUE} without an own limit. */
    public long maxVolume() { return maxVolume; }
    public AreaHeight height() { return height; }
    /** The height of {@link AreaHeight#FIXED} in blocks; 0 for every other mode. */
    public int fixedHeight() { return fixedHeight; }

    public static final class Builder {
        private int minX = 1, minY = 1, minZ = 1;
        private int maxX = Integer.MAX_VALUE, maxY = Integer.MAX_VALUE, maxZ = Integer.MAX_VALUE;
        private long maxVolume = Long.MAX_VALUE;
        private AreaHeight height = AreaHeight.PICKED;
        private int fixedHeight;

        private Builder() {}

        /** Smallest size per axis (default 1 x 1 x 1); each at least 1. */
        public Builder minSize(int x, int y, int z) {
            if (x < 1 || y < 1 || z < 1) throw new IllegalArgumentException("minSize must be at least 1 per axis");
            minX = x; minY = y; minZ = z;
            return this;
        }

        /** Largest size per axis (default: only Core's caps); each at least 1. */
        public Builder maxSize(int x, int y, int z) {
            if (x < 1 || y < 1 || z < 1) throw new IllegalArgumentException("maxSize must be at least 1 per axis");
            maxX = x; maxY = y; maxZ = z;
            return this;
        }

        /** The most blocks in all (default: only Core's cap); at least 1. */
        public Builder maxVolume(long blocks) {
            if (blocks < 1) throw new IllegalArgumentException("maxVolume must be at least 1");
            maxVolume = blocks;
            return this;
        }

        /** PICKED (default), FULL or ASK; for a fixed height use {@link #fixedHeight(int)}. */
        public Builder height(AreaHeight mode) {
            Objects.requireNonNull(mode, "mode");
            if (mode == AreaHeight.FIXED) throw new IllegalArgumentException("use fixedHeight(blocks) for AreaHeight.FIXED");
            height = mode;
            fixedHeight = 0;
            return this;
        }

        /** {@link AreaHeight#FIXED}: always this many blocks upwards from the lower corner (at least 1). */
        public Builder fixedHeight(int blocks) {
            if (blocks < 1) throw new IllegalArgumentException("fixedHeight must be at least 1");
            height = AreaHeight.FIXED;
            fixedHeight = blocks;
            return this;
        }

        public AreaRules build() {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalStateException("minSize is larger than maxSize");
            if ((long) minX * minY * minZ > maxVolume) throw new IllegalStateException("minSize has more blocks than maxVolume");
            if (height == AreaHeight.FIXED && (fixedHeight < minY || fixedHeight > maxY))
                throw new IllegalStateException("fixedHeight " + fixedHeight + " is outside the y limits " + minY + ".." + maxY);
            return new AreaRules(this);
        }
    }
}
