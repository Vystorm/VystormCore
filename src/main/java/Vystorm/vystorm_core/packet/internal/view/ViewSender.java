package Vystorm.vystorm_core.packet.internal.view;

import Vystorm.vystorm_core.blocks.ChunkCells;
import Vystorm.vystorm_core.packet.internal.wire.Cells;
import Vystorm.vystorm_core.packet.view.ViewChunks;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import org.bukkit.World;

/**
 * Packets the packet view sends itself (server thread; moved from VystormAntiXray's {@code platform.Sender}, without
 * the reveal queue): whole chunks again (through every layer as they decide now), and the true states of cells as
 * section updates, then the block entities of those cells, then one light update with the real light of the touched
 * sections. Chunks that are not loaded are skipped; nothing is loaded.
 *
 * <p>Internal: not part of the public API.
 */
public final class ViewSender {
    private final ViewNms nms;
    private final ViewCounters stats;

    public ViewSender(ViewNms nms, ViewCounters stats) {
        this.nms = nms;
        this.stats = stats;
    }

    /** Sends these chunks again, in the given order; returns how many were loaded and sent. */
    public int resend(ViewerSlot v, World w, Collection<Long> keys) {
        Object level = nms.level(w);
        int n = 0;
        for (long key : keys) {
            Object lc = nms.chunkNow(level, ViewChunks.x(key), ViewChunks.z(key));
            if (lc == null) continue;
            v.sink.accept(nms.chunkPacket(level, lc));
            n++;
        }
        stats.add("resent.chunks", n);
        return n;
    }

    /**
     * Sends the true states of {@code cells} (one chunk, world coordinates): per touched section one section update in
     * ascending section order, then the block entities of those cells, then one light update. {@code unmasked}: the
     * packets pass every layer unchanged. Returns the cells sent (0 when the chunk is not loaded).
     */
    public int sendReal(ViewerSlot v, World w, ChunkCells cells, boolean unmasked) {
        if (cells.size() == 0) return 0;
        Object level = nms.level(w);
        int cx = cells.chunkX(), cz = cells.chunkZ();
        Object lc = nms.chunkNow(level, cx, cz);
        if (lc == null) { stats.inc("sendReal.notLoaded"); return 0; }
        int minY = w.getMinHeight(), sections = (w.getMaxHeight() - minY) >> 4;
        long[][] bySection = new long[sections][];
        for (int i = 0; i < cells.size(); i++) {
            int x = cells.x(i), y = cells.y(i), z = cells.z(i);
            if ((x >> 4) != cx || (z >> 4) != cz) throw new IllegalArgumentException("cell " + x + " " + y + " " + z + " is not in chunk " + cx + " " + cz);
            int s = (y - minY) >> 4;
            if (y < minY || s >= sections) continue;
            if (bySection[s] == null) bySection[s] = new long[Cells.WORDS];
            Cells.set(bySection[s], Cells.index(x, y, z));
        }
        List<Object> out = new ArrayList<>();
        BitSet light = new BitSet();
        int n = 0;
        for (int s = 0; s < sections; s++) {
            long[] m = bySection[s];
            if (m == null) continue;
            int[] ks = new int[Cells.count(m)];
            int j = 0;
            for (int k = 0; k < 4096; k++) if (Cells.bit(m, k)) ks[j++] = k;
            out.add(nms.sectionUpdate(lc, cx, (minY >> 4) + s, cz, s, ks));
            light.set(s + 1);
            n += ks.length;
        }
        out.addAll(nms.blockEntityPackets(lc, pos -> {
            int s = (pos[1] - minY) >> 4;
            return pos[1] >= minY && s < sections && bySection[s] != null && Cells.bit(bySection[s], Cells.index(pos[0], pos[1], pos[2]));
        }));
        if (!light.isEmpty()) out.add(nms.lightUpdate(level, cx, cz, light, light));
        for (Object packet : out) {
            if (unmasked) v.passUnmasked(packet);
            v.sink.accept(packet);
        }
        stats.add("sendReal.cells", n);
        stats.add("sendReal.packets", out.size());
        return n;
    }
}
