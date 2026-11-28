package Vystorm.vystorm_core.packet.view;

import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * A packet view layer: what one viewer's client is shown instead of the real world (registered with
 * {@code api.packages().packetView().register(plugin, spec, layer)}).
 *
 * <p><b>Threads.</b> Every method runs on the Netty event loop of the viewer's connection unless its Javadoc says
 * "server thread". There it must not block, take locks the server thread holds, call Bukkit or read the world: read
 * your own prebuilt state (published through a volatile field or a concurrent map), allocate little, no boxing per
 * cell. Calls for different viewers may run at the same time on different event loops; calls for one viewer come in
 * the order of that viewer's packets.
 *
 * <p><b>Failures.</b> Core catches every exception. For a {@link LayerSpec.Kind#MASK MASK} layer the packet is then not
 * sent (fail-closed; an unreadable chunk is asked again from the server, at most twice); one console line per kind and
 * minute names the layer.
 *
 * <p><b>MASK layers</b> (0.73.0) hide real cells. Every cell such a layer writes in {@link #apply} (through
 * {@link ChunkEdit#set} or {@link SectionEdit}) counts as hidden: its block entity is removed from the chunk and its
 * light is sent as 0. A hidden cell is never shown again by a later layer in the same packet. Section and single block
 * updates and light updates of hidden cells are judged with {@link #hides} and {@link #hiddenCells}.
 *
 * @since 0.73.0
 */
public interface ChunkLayer {
    /**
     * Answer of {@link #hiddenCells} for "this layer cannot tell which cells of this section are hidden" (for example
     * the viewer's state belongs to another world): a MASK layer's packet is then not sent.
     */
    long[] UNKNOWN = new long[0];

    /**
     * Cheap: whether this layer changes or judges anything in this chunk for this viewer. When no layer is interested,
     * the packet passes with no copy and no decode. {@code chunkX} and {@code chunkZ} are {@link Integer#MIN_VALUE}
     * when the packet could not be read (answer for the world: an interested MASK layer makes Core drop it).
     */
    boolean interested(ViewerRef viewer, UUID world, int chunkX, int chunkZ);

    /** Whether the chunk may be sent now; {@link Readiness#HOLD} keeps it (and every packet behind it) up to the hold time. */
    default Readiness ready(ViewerRef viewer, UUID world, int chunkX, int chunkZ) { return Readiness.READY; }

    /** Core started to hold this chunk for this layer (once per held chunk), for example to ask for missing data. */
    default void held(ViewerRef viewer, UUID world, int chunkX, int chunkZ) { }

    /** Changes the chunk as this viewer sees it. Sections are decoded on first access. */
    void apply(ViewerRef viewer, ChunkEdit chunk);

    /**
     * MASK layers: whether the client must not learn the state of this cell (single block updates are dropped, section
     * update entries trimmed). World coordinates.
     */
    default boolean hides(ViewerRef viewer, UUID world, int x, int y, int z) { return false; }

    /**
     * MASK layers, the bulk form of {@link #hides} for one section of a chunk (light updates are zeroed and section
     * updates trimmed with it): 64 longs, bit {@code k = y << 8 | z << 4 | x} set = hidden; null = nothing hidden;
     * {@link #UNKNOWN} = cannot tell (the packet is not sent). {@code sectionY} is the absolute section coordinate.
     * The default asks {@link #hides} for every cell.
     */
    default long[] hiddenCells(ViewerRef viewer, UUID world, int chunkX, int sectionY, int chunkZ) {
        long[] out = null;
        int x0 = chunkX << 4, y0 = sectionY << 4, z0 = chunkZ << 4;
        for (int k = 0; k < 4096; k++) {
            if (!hides(viewer, world, x0 + (k & 15), y0 + (k >>> 8), z0 + ((k >>> 4) & 15))) continue;
            if (out == null) out = new long[64];
            out[k >>> 6] |= 1L << (k & 63);
        }
        return out;
    }

    /** Object packets naming a position or a player (only when {@link LayerSpec#objects()}): true drops the packet. */
    default boolean drop(ViewerRef viewer, ObjectPacket packet) { return false; }

    /**
     * The viewer's client entered a world (a login or respawn packet into another world than before passed; the packets
     * behind it belong to that world). {@code minY} and {@code sections} describe its height.
     */
    default void clientWorld(ViewerRef viewer, UUID world, int minY, int sections) { }

    /** The client went back to configuration and dropped every chunk it held (same world). */
    default void clientReset(ViewerRef viewer) { }

    /** The client forgot this chunk (unload packet passed). */
    default void forgotten(ViewerRef viewer, UUID world, int chunkX, int chunkZ) { }

    /** Server thread: the viewer left (quit, or the virtual viewer was removed); drop his state. */
    default void left(ViewerRef viewer) { }

    /**
     * Server thread: Core could not put its handlers into this player's connection (three tries a second apart). A MASK
     * layer decides what that means (for example disconnect a player who must not get the real world).
     */
    default void notAttached(ViewerRef viewer, Player player, String why) { }

    /**
     * Server thread: the packet view switched itself off for this server (its self-test failed again after a packet
     * could not be read): packets pass unchanged from now on. A MASK layer applies its own "off" rule.
     */
    default void stopped(String why) { }
}
