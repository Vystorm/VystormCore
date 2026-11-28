package Vystorm.vystorm_core.picker;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A running pick of one player (0.71.0); at most one per player. Server thread.
 *
 * @since 0.71.0
 */
public interface PickFlow {
    /** The plugin that started it. */
    Plugin owner();

    /** The player who is choosing. */
    Player viewer();

    PickKind kind();

    /** When it started, epoch ms. */
    long startedAt();

    /** Whether it still runs. */
    boolean active();

    /** Ends it with {@link PickEnd#CANCELLED_BY_PLUGIN} (closes the picker, no reopen); false if it had ended. */
    boolean cancel();
}
