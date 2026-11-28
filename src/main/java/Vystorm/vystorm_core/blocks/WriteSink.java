package Vystorm.vystorm_core.blocks;

/**
 * Receives the outcome of every cell a {@link BlockWriter} handles, in cell order, on the server thread.
 *
 * @since 0.69.0
 */
public interface WriteSink {
    /** Cell {@code i} now holds its {@code after} state. */
    void written(int i);

    /**
     * Cell {@code i} did not hold its {@code before} state, so it was left as it is. {@code currentId} is the state it
     * holds now: re-evaluate the cell with it and write it again if it should still change.
     */
    void mismatch(int i, int currentId);
}
