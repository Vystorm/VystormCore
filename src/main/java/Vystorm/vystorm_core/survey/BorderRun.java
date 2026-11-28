package Vystorm.vystorm_core.survey;

/**
 * Consecutive cells of one room on a side face of a chunk (see {@link SurveyFace} for the position order). Two rooms
 * of neighbouring chunks join where runs of the touching faces overlap.
 *
 * @param start  first position
 * @param length number of positions (at least 1)
 * @param room   local room id
 * @since 0.68.0
 */
public record BorderRun(int start, int length, int room) {
    public BorderRun {
        if (start < 0 || length < 1 || room < 1)
            throw new IllegalArgumentException("border run " + start + "+" + length + " room " + room);
    }

    /** Position after the last cell. */
    public int end() { return start + length; }

    /** Whether the two runs share at least one position. */
    public boolean overlaps(BorderRun other) { return start < other.end() && other.start < end(); }
}
