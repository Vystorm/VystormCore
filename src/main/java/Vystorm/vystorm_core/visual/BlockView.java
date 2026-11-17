package Vystorm.vystorm_core.visual;

import java.util.Map;
import org.bukkit.block.data.BlockData;

/**
 * A private, non-physical block overlay for one player. All calls must run on the server thread.
 * Never changes world blocks. Close restores current real block states, not old captured states.
 * Only one active view per player is admitted, so plugins cannot overwrite one another's cleanup.
 * @since 0.29.0
 */
public interface BlockView extends AutoCloseable {
    /**
     * Replace desired content, sending at most budget changes (1..4096). Maximum 4096 desired blocks.
     * Repeat with the same desired content until zero is returned. Inputs are defensively cloned.
     * Restorations precede additions. Unloaded chunks are never loaded and are omitted.
     * @return number of desired changes still pending
     */
    int replace(Map<BlockPosition, BlockData> desired, int budget);
    /**
     * Prefer visible changes in map iteration order, reserving a quarter of the budget for
     * restoration so movement cannot starve cleanup. Older implementations use replace.
     * @since 0.36.0
     */
    default int replacePrioritized(Map<BlockPosition, BlockData> desired, int budget) {
        return replace(desired, budget);
    }
    /** Whether a block was actually sent and has not yet been restored. Server thread only. */
    boolean covers(BlockPosition position);
    boolean isClosed();
    @Override void close();
}
