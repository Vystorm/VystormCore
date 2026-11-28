package Vystorm.vystorm_core.survey.internal;

/**
 * Cell classes of the survey (one byte per cell). The low two bits are the class; bit {@link #DOOR} marks doors and
 * trapdoors, which may stop reach ({@code SurveyRule.doorsStopReach}). Everything that is not {@link #OPAQUE} is open.
 *
 * <p>Plain Java, no Bukkit. Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CellClass {
    public static final byte OPAQUE = 0;
    public static final byte AIR = 1;
    public static final byte FLUID = 2;
    public static final byte SEE_THROUGH = 3;
    /** Flag on {@link #SEE_THROUGH}: a door or trapdoor. */
    public static final byte DOOR = 4;
    /** A door or trapdoor cell. */
    public static final byte DOOR_CELL = SEE_THROUGH | DOOR;

    private CellClass() {}

    public static boolean open(byte c) { return (c & 3) != 0; }

    public static boolean fluid(byte c) { return (c & 3) == FLUID; }

    public static boolean door(byte c) { return (c & DOOR) != 0; }
}
