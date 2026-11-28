package Vystorm.vystorm_core.blocks;

/**
 * Per-cell answer of a {@link EditVerdict.Partial}: may the cell at world coordinates {@code x, y, z} change?
 * Implementations are pure and thread-safe, so an edit can ask them while it plans on other threads.
 *
 * @since 0.69.0
 */
@FunctionalInterface
public interface CellTest {
    boolean allowed(int x, int y, int z);
}
