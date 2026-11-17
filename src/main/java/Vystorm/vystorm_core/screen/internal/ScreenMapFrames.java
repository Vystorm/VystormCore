package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.display.internal.DisplayPackets;
import Vystorm.vystorm_core.display.internal.IdPool;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Client-only map frames for PINNED map widgets (since 0.59.0): invisible item frames that exist only as packets for
 * one viewer, each holding a client map from Core's reserved id range. No server entity, no server map.
 */
public interface ScreenMapFrames {
    /** True when frames can be sent on this server (packet adapter available). */
    boolean supported();

    /**
     * Sends the first pixels of every tile (128x128 map colours, row 0 = header) and then one frame per tile at one
     * block, to {@code viewer} only. Pixels go first: a frame whose map has no data yet would show a paper map item at
     * the frame's real place, right in front of the eye. Null when it failed.
     */
    Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles);

    /** The frames of one map widget. */
    interface Handle {
        /** Sends a full tile (128x128 map colours, row 0 = header). */
        void pixels(int tile, byte[] colours);
        /** Removes the frames from the viewer and frees the map ids. Idempotent. */
        void remove();
    }

    /** No map support (tests, Spigot, Paper without the packet adapter). */
    ScreenMapFrames NONE = new ScreenMapFrames() {
        @Override public boolean supported() { return false; }
        @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) { return null; }
    };

    /**
     * Live implementation with Core's reflective {@link DisplayPackets} (Paper/Purpur 1.21.8 to 26.2). Map ids come from
     * the top 64 Ki of Core's reserved client map range; {@code ClientDisplays} hands out ids from the bottom.
     */
    final class Packets implements ScreenMapFrames {
        static final int FIRST_ID = 0x7FFF0000, COUNT = 0xFFFF;
        private final IdPool ids = new IdPool(FIRST_ID, COUNT);
        private DisplayPackets packets;
        private boolean probed;

        @Override public boolean supported() {
            if (!probed) {
                probed = true;
                try { packets = new DisplayPackets(); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { packets = null; }
            }
            return packets != null;
        }

        @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) {
            if (!supported()) return null;
            int count = tiles.size();
            int[] entities = new int[count], maps = new int[count];
            int taken = 0;
            try {
                for (int i = 0; i < count; i++) { maps[i] = ids.take(); taken++; }
                for (int i = 0; i < count; i++) send(viewer, maps[i], tiles.get(i));
                for (int i = 0; i < count; i++) {
                    entities[i] = packets.nextEntityId(viewer.getWorld());
                    packets.send(viewer, packets.spawnFrame(entities[i], UUID.randomUUID(), at.x(), at.y(), at.z(), at.facing(), false));
                    packets.send(viewer, packets.frameData(entities[i], maps[i], true));
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                for (int i = 0; i < taken; i++) ids.give(maps[i]);
                try { packets.send(viewer, packets.remove(entities)); } catch (ReflectiveOperationException | RuntimeException ignored) { }
                return null;
            }
            return new Handle() {
                boolean removed;
                @Override public void pixels(int tile, byte[] colours) {
                    if (removed || !viewer.isOnline()) return;
                    try { send(viewer, maps[tile], colours); } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
                @Override public void remove() {
                    if (removed) return;
                    removed = true;
                    for (int id : maps) ids.give(id);
                    if (!viewer.isOnline()) return;
                    try { packets.send(viewer, packets.remove(entities)); } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            };
        }

        private void send(Player viewer, int mapId, byte[] colours) throws ReflectiveOperationException {
            packets.send(viewer, packets.mapData(mapId, 0, 0, ScreenMapCodec.SIZE, ScreenMapCodec.SIZE, colours));
        }
    }
}
