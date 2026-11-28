package Vystorm.vystorm_core.packet.view;

import org.bukkit.plugin.Plugin;

/**
 * A registered layer (returned by {@code packetView().register(..)}). Closing it removes the layer: chunks it held go
 * out first, judged by it as at the end of their hold time; once no layer is left, Core takes its handlers out of every
 * connection. Server thread.
 *
 * @since 0.73.0
 */
public interface ViewLayer extends AutoCloseable {
    LayerSpec spec();

    Plugin owner();

    /** The hold time in use (starts at {@link LayerSpec#maxHoldMillis()}). */
    long holdMillis();

    /** Changes the hold time (0 to {@link LayerSpec#maxHoldMillis()}), for example after a config reload. */
    void holdMillis(long millis);

    boolean isClosed();

    @Override void close();
}
