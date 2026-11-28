package Vystorm.vystorm_core.picker;

/**
 * What a pick flow chooses (0.71.0): players ({@link PlayerPick}) or one target in the world ({@link WorldPick}).
 *
 * @since 0.71.0
 */
public enum PickKind {
    /** The player picker ({@link PlayerPick}). */
    PLAYERS,
    /** World pick: where the player stands, with the facing ({@link Picked.Position}). */
    POSITION,
    /** World pick: the block under the crosshair ({@link Picked.Block}). */
    BLOCK,
    /** World pick: one face of the block under the crosshair, for example a wall ({@link Picked.Face}). */
    FACE,
    /** World pick: a block whose state is a container (chest, barrel, furnace ...) ({@link Picked.Container}). */
    CONTAINER,
    /** World pick: an entity under the crosshair (painting, item frame, armor stand, mob ...) ({@link Picked.EntityHit}). */
    ENTITY,
    /** World pick: a box from two corners, with a confirm view ({@link Picked.Area}, {@link AreaRules}). @since 0.71.0 */
    AREA;

    /** Whether this kind is a world pick. */
    public boolean world() { return this != PLAYERS; }
}
