package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.visual.BlockPosition;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * A player clicked, started to break or broke a cell where Core shows him a client block of a set with an
 * {@link ClientBlockSet#onInteract} listener. Server thread; valid only during the call.
 *
 * @since 0.72.0
 */
public interface ClientBlockInteract {
    enum Action {
        /** Left click on the cell ({@code PlayerInteractEvent} LEFT_CLICK_BLOCK). */
        LEFT_CLICK,
        /** Right click on the cell ({@code PlayerInteractEvent} RIGHT_CLICK_BLOCK). */
        RIGHT_CLICK,
        /** The player started to break it ({@code BlockDamageEvent}). */
        START_BREAK,
        /** The server broke the real block there ({@code BlockBreakEvent}, also when another plugin cancelled it). */
        BREAK
    }

    Player player();

    ClientBlockSet set();

    BlockPosition cell();

    /** The client block the player was shown there (a copy). */
    BlockData shown();

    Action action();

    /** Send the client block again two ticks from now (after the server's answer reached the client). */
    void repair();
}
