package Vystorm.vystorm_core.packet.view;

import java.util.UUID;

/**
 * One chunk packet on its way to one viewer, shared by every layer in turn (decoded once, encoded once). Netty thread;
 * valid only inside {@link ChunkLayer#apply}. States are network ids ({@code packetView().wireId(..)}). Cells are
 * addressed by chunk-local or world x and z (only the low four bits count) and the absolute y.
 *
 * @since 0.73.0
 */
public interface ChunkEdit {
    UUID world();

    int chunkX();

    int chunkZ();

    /** The lowest y of the chunk's world. */
    int minY();

    /** Number of 16-high sections. */
    int sections();

    /** The state shown after the earlier layers. */
    int state(int x, int y, int z);

    /** The real state the server sent. */
    int realState(int x, int y, int z);

    /** Sets the shown state of one cell (a MASK layer hides it). */
    void set(int x, int y, int z, int wireId);

    /** Bulk access to one section (index 0 = the lowest); decoded on first access. */
    SectionEdit section(int index);

    /**
     * Per column ({@code z << 4 | x}) the highest top of the packet's heightmaps as an absolute y (the first cell above
     * the column's top block), {@link #minY()} where the packet has none. A new array per call.
     */
    int[] heightmapTops();

    /** Number of block entities in the packet as the server sent it. */
    int blockEntities();
}
