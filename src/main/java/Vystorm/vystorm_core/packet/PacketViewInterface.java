package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.packet.view.ChunkLayer;
import Vystorm.vystorm_core.packet.view.LayerSpec;
import Vystorm.vystorm_core.packet.view.ViewLayer;
import Vystorm.vystorm_core.packet.view.ViewStats;
import Vystorm.vystorm_core.packet.view.ViewerRef;
import Vystorm.vystorm_core.packet.view.WireStates;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The packet view ({@code api.packages().packetView()}): shared chunk rewriting in each player's connection. A plugin
 * registers a {@link ChunkLayer}; Core applies every layer to the chunk, light, section and block packets on their way
 * to the client inside one pair of handlers per connection (each chunk is decoded and encoded once per viewer, however
 * many layers there are). Handlers are in a connection only while a layer is registered.
 *
 * <p>Runs on Paper, Purpur and Spigot 26.2 and 26.3 after a self-test with a real chunk; elsewhere {@link #supported()}
 * is false, {@link #register} refuses and nothing is attached. 0.73.0 runs MASK layers (hiding real cells, as
 * VystormAntiXray does); CONTENT layers come with capability {@code packet-view-content}.
 *
 * @since 0.73.0
 */
public interface PacketViewInterface {
    PacketViewInterface DEFAULT = new PacketViewInterface() {};

    /**
     * True when the view runs on this server. The first call on the server thread runs the self-test with a real loaded
     * chunk (no handler is attached by it); other threads get false until then.
     */
    default boolean supported() { return PacketView.supported(); }

    /** Why the view does not run here (empty when it does). The first call on the server thread runs the self-test. */
    default Optional<String> unsupportedReason() { return PacketView.unsupportedReason(); }

    /**
     * Registers a layer (server thread). The first layer puts Core's handlers into every connection; the layer is
     * released when its plugin disables or the handle is closed.
     *
     * @throws IllegalStateException         when the view does not run here, the id is taken or the owner is disabled
     * @throws UnsupportedOperationException for a CONTENT layer on this version
     */
    default ViewLayer register(Plugin owner, LayerSpec spec, ChunkLayer layer) { return PacketView.register(owner, spec, layer); }

    /** The network id of a block state (the id chunk data uses). Server thread; the returned int is usable anywhere. */
    default int wireId(BlockData data) { return PacketView.wireId(data); }

    /** The table of network ids: count, air, fluid (immutable, any thread once the view runs). */
    default WireStates wireStates() { return PacketView.wireStates(); }

    /** The handle of an online player that Core follows (empty when no layer is registered or he is not followed). */
    default Optional<ViewerRef> viewer(Player player) { return PacketView.viewer(player); }

    /** Chunk keys ({@code ViewChunks.key}) the client of this player holds now, as far as Core saw them (snapshot; any thread). */
    default Set<Long> heldChunks(Player viewer) { return PacketView.heldChunks(viewer); }

    /** Players whose client holds this chunk (snapshot; any thread). */
    default Collection<Player> holders(World world, int chunkX, int chunkZ) { return PacketView.holders(world, chunkX, chunkZ); }

    /** Sends these chunks again to one viewer, through every layer as it decides now (server thread). Returns the chunks sent. */
    default int resend(Player viewer, Collection<Long> chunkKeys) { return PacketView.resend(viewer, chunkKeys); }

    /**
     * Sends the true states of cells of one chunk (section updates, their block entities, one light update) to one
     * viewer (server thread). MASK layers still judge them unless {@code unmasked}: a layer that reveals marks the
     * cells shown first, or passes {@code unmasked = true}; then the section updates and block entity data pass every
     * layer (also other plugins' masks: only for cells the caller may show), while the light update is still judged, so
     * hidden cells next to the sent ones stay dark. Returns the cells sent.
     */
    default int sendReal(Player viewer, World world, ChunkCells cells, boolean unmasked) { return PacketView.sendReal(viewer, world, cells, unmasked); }

    /** The registered layers in the order they run. */
    default List<ViewLayer> layers() { return PacketView.layers(); }

    default ViewStats stats() { return PacketView.stats(); }

    /** Closes every layer of the owner (also done when it disables). */
    default void release(Plugin owner) { PacketView.release(owner); }
}
