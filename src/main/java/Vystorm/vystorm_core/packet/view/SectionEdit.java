package Vystorm.vystorm_core.packet.view;

/**
 * Bulk view of one section's 4096 states, index {@code k = y << 8 | z << 4 | x}. Netty thread; valid only inside
 * {@link ChunkLayer#apply}. A section a layer wrote to is encoded again (in its smallest legal form), all others keep
 * their bytes. For a MASK layer every written cell counts as hidden (see {@link ChunkLayer}).
 *
 * @since 0.73.0
 */
public interface SectionEdit {
    /** Index of the section in its chunk (0 = the lowest). */
    int index();

    /** The shown state of a cell (after the earlier layers). */
    int get(int k);

    /** The real state of a cell. */
    int real(int k);

    /** The one real state of this section when the server sent it as a single value, else -1 (no decode needed). */
    int uniform();

    /** Copies the 4096 shown states into {@code out} (length at least 4096). */
    void copyTo(int[] out);

    /** Copies the 4096 real states into {@code out} (length at least 4096). */
    void copyRealTo(int[] out);

    void set(int k, int wireId);

    /** Every cell whose bit in {@code cells} (64 longs) is set gets {@code wireId}; all bits set needs no decode. */
    void fill(long[] cells, int wireId);

    /** Maps states: shown = {@code map[shown]} ({@code map.length} = number of wire states); changed cells count as written. */
    void remap(int[] map);

    /** True while no layer read or wrote this section. */
    boolean untouched();
}
