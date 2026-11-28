package Vystorm.vystorm_core.survey;

/**
 * How a cell or a group of joined rooms relates to the surface.
 *
 * <ul>
 *   <li>{@link #SURFACE}: the cell itself is a surface cell (no room).</li>
 *   <li>{@link #MOUTH}: at least one room of the group touches a surface cell (a cave with an entrance).</li>
 *   <li>{@link #SEALED}: the whole group was followed to its end and touches no surface cell.</li>
 *   <li>{@link #UNKNOWN}: no surface contact found yet, but the group was not followed to its end (chunk limit reached
 *   or a neighbour chunk not surveyed).</li>
 * </ul>
 *
 * @since 0.68.0
 */
public enum SkyContact {
    SURFACE,
    MOUTH,
    SEALED,
    UNKNOWN
}
