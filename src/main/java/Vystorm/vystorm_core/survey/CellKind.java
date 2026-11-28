package Vystorm.vystorm_core.survey;

/**
 * What the world survey makes of one block cell.
 *
 * <ul>
 *   <li>{@link #SOLID}: an opaque block (stone, ores, planks ...).</li>
 *   <li>{@link #SURFACE}: an open cell in the sky column (above the highest opaque block of its column) or within the
 *   rule's {@link SurveyRule#reach() reach} of such a cell through open cells.</li>
 *   <li>{@link #ENCLOSED}: every other open cell; enclosed cells form the rooms.</li>
 * </ul>
 *
 * @since 0.68.0
 */
public enum CellKind {
    SOLID,
    SURFACE,
    ENCLOSED
}
