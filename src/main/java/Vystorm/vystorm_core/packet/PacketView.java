package Vystorm.vystorm_core.packet;

import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.packet.internal.view.PacketViewRuntime;
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
 * Static side of {@link PacketViewInterface} (reach it through {@code api.packages().packetView()}).
 *
 * @since 0.73.0
 */
public final class PacketView {
    private PacketView() {}

    /** Core lifecycle hook (enable): registers the listener; nothing is tested or attached until a plugin asks. */
    public static void initialize(Plugin core) { PacketViewRuntime.get().start(core); }

    /** Core lifecycle hook (disable): every handler leaves every connection. */
    public static void shutdown() { PacketViewRuntime.shutdownInstance(); }

    public static boolean supported() { return PacketViewRuntime.get().supported(); }

    public static Optional<String> unsupportedReason() { return PacketViewRuntime.get().unsupportedReason(); }

    public static ViewLayer register(Plugin owner, LayerSpec spec, ChunkLayer layer) { return PacketViewRuntime.get().register(owner, spec, layer); }

    public static int wireId(BlockData data) { return PacketViewRuntime.get().wireId(data); }

    public static WireStates wireStates() { return PacketViewRuntime.get().wireStates(); }

    public static Optional<ViewerRef> viewer(Player player) { return PacketViewRuntime.get().viewer(player); }

    public static Set<Long> heldChunks(Player viewer) { return PacketViewRuntime.get().heldChunks(viewer); }

    public static Collection<Player> holders(World world, int chunkX, int chunkZ) { return PacketViewRuntime.get().holders(world, chunkX, chunkZ); }

    public static int resend(Player viewer, Collection<Long> chunkKeys) { return PacketViewRuntime.get().resend(viewer, chunkKeys); }

    public static int sendReal(Player viewer, World world, ChunkCells cells, boolean unmasked) { return PacketViewRuntime.get().sendReal(viewer, world, cells, unmasked); }

    public static List<ViewLayer> layers() { return PacketViewRuntime.get().layers(); }

    public static ViewStats stats() { return PacketViewRuntime.get().stats(); }

    public static void release(Plugin owner) { PacketViewRuntime.get().release(owner); }
}
