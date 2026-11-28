package Vystorm.vystorm_core.survey;

import java.util.zip.CRC32;

/**
 * The surface rule of one world. A cell is {@link CellKind#SURFACE} if it is in the sky column (above the highest
 * opaque block of its column) or within {@code reach} steps of such a cell through open cells; other open cells are
 * {@link CellKind#ENCLOSED}.
 *
 * @param reach          steps through open cells from the sky column that still count as surface (0 to 16; default 8)
 * @param hallRadius     0 = only the sky counts; r &gt; 0: an open cell whose (2r+1)^3 box is fully open counts like
 *                       sky (worlds without sky such as the Nether: 3, a 7x7x7 box); 0 to 4
 * @param voidBelow      the sky column also starts at the bottom of the world (the End, floating islands)
 * @param doorsStopReach reach does not spread through doors and trapdoors (a house behind a closed door stays
 *                       enclosed); glass, panes and leaves never stop it
 * @since 0.68.0
 */
public record SurveyRule(int reach, int hallRadius, boolean voidBelow, boolean doorsStopReach) {
    /** Largest {@link #reach()}. */
    public static final int MAX_REACH = 16;
    /** Largest {@link #hallRadius()}. */
    public static final int MAX_HALL_RADIUS = 4;
    /**
     * Version of Core's block classification (which block counts as opaque or open). Part of {@link #hash()}, so a
     * changed classification makes every stored record stale.
     */
    public static final int CLASSIFICATION_VERSION = 1;

    /** Worlds with a sky: reach 8, no halls, doors stop reach. */
    public static final SurveyRule OVERWORLD = new SurveyRule(8, 0, false, true);
    /** Worlds without a sky (the Nether): reach 8 from open 7x7x7 halls. */
    public static final SurveyRule NETHER = new SurveyRule(8, 3, false, true);
    /** The End: reach 8, the sky column also from below. */
    public static final SurveyRule END = new SurveyRule(8, 0, true, true);

    public SurveyRule {
        if (reach < 0 || reach > MAX_REACH) throw new IllegalArgumentException("reach must be 0 to " + MAX_REACH + ": " + reach);
        if (hallRadius < 0 || hallRadius > MAX_HALL_RADIUS)
            throw new IllegalArgumentException("hallRadius must be 0 to " + MAX_HALL_RADIUS + ": " + hallRadius);
    }

    public SurveyRule withReach(int value) { return new SurveyRule(value, hallRadius, voidBelow, doorsStopReach); }

    public SurveyRule withHallRadius(int value) { return new SurveyRule(reach, value, voidBelow, doorsStopReach); }

    public SurveyRule withVoidBelow(boolean value) { return new SurveyRule(reach, hallRadius, value, doorsStopReach); }

    public SurveyRule withDoorsStopReach(boolean value) { return new SurveyRule(reach, hallRadius, voidBelow, value); }

    /**
     * Blocks around a chunk that a survey has to see: reach plus hall radius plus one (the neighbours of the chunk's
     * own cells), at most a whole neighbour chunk.
     */
    public int margin() { return Math.min(16, reach + hallRadius + 1); }

    /** Stable hash of the rule and the classification version; stored in every record. */
    public int hash() {
        CRC32 crc = new CRC32();
        crc.update(new byte[] {'S', 'R', (byte) CLASSIFICATION_VERSION, (byte) reach, (byte) hallRadius,
                (byte) (voidBelow ? 1 : 0), (byte) (doorsStopReach ? 1 : 0)});
        return (int) crc.getValue();
    }
}
