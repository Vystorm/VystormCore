package Vystorm.vystorm_core.blocks;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Block states as small int ids for one server run, shared by all plugins ({@code api.blocks().states()}).
 * Ids are dense ({@code 0 .. size() - 1}), id {@link #AIR} is always {@code minecraft:air}, and an id never changes
 * its state while the server runs. Ids are not stable across restarts: files store {@link #text(int)}.
 * <p>
 * Thread-safe: lookups of known states are lock-free, adding an unknown state takes a short lock. All flags are
 * computed once when a state is added, on the thread that adds it.
 *
 * @since 0.69.0
 */
public interface StateTable {
    /** Id of {@code minecraft:air}. */
    int AIR = 0;

    /** Id of the state; adds it when it is unknown. Any thread. */
    int id(BlockData data);

    /**
     * Id of the state written as text ({@code minecraft:oak_stairs[facing=east]}); unknown texts are parsed through
     * {@code Bukkit.createBlockData}. Any thread.
     *
     * @throws IllegalArgumentException when the text is no block state
     */
    int id(String blockData);

    /** The shared instance of the state. Never modify it; clone it before handing it to code you do not own. */
    BlockData data(int id);

    /** {@code BlockData.getAsString()} of the state: the same text in every server run. */
    String text(int id);

    Material material(int id);

    /** Number of known states. */
    int size();

    /** Air, cave air or void air. */
    boolean air(int id);

    /** {@code Material.isSolid()} of the state's material. */
    boolean solid(int id);

    /** The state carries a block entity (chest, sign, furnace, ...): write it through Bukkit only. */
    boolean hasBlockEntity(int id);

    /** The state is a point of interest for villagers or bees (bed, bell, job site, hive, portal, ...). */
    boolean poi(int id);
}
