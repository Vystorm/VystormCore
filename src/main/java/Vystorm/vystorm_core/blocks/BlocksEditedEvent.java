package Vystorm.vystorm_core.blocks;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Fired on the server thread after the cells of one chunk were written without per-block events (bulk edits, their
 * undo and redo). One event per chunk; the cells are already written, so the event is not cancellable. Plugins that
 * keep their own record of blocks (snow, crops, protection logs) update it here. Fire it through
 * {@code api.blocks().announce(source, chunk)}.
 *
 * @since 0.69.0
 */
public final class BlocksEditedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin source;
    private final EditedChunk chunk;

    public BlocksEditedEvent(Plugin source, EditedChunk chunk) {
        super(false);
        this.source = Objects.requireNonNull(source, "source");
        this.chunk = Objects.requireNonNull(chunk, "chunk");
    }

    /** The plugin that wrote the cells. */
    public Plugin source() { return source; }

    /** The written cells of the chunk. */
    public EditedChunk chunk() { return chunk; }

    @Override public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
