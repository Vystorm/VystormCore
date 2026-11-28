package Vystorm.vystorm_core.picker;

/**
 * How the height of an area pick is decided (0.71.0, {@link AreaRules}).
 *
 * @since 0.71.0
 */
public enum AreaHeight {
    /** From the lower to the higher picked corner. Default. */
    PICKED,
    /** Always the whole world height (the world's minimum height to its maximum), whatever the corners' heights. */
    FULL,
    /** As picked; the confirm view offers "Full height" so the player may stretch the area to the whole world height. */
    ASK,
    /**
     * A fixed number of blocks upwards from the lower picked corner ({@link AreaRules.Builder#fixedHeight(int)}); moved
     * down when it would reach above the world.
     */
    FIXED
}
