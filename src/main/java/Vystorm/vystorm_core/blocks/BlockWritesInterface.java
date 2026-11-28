package Vystorm.vystorm_core.blocks;

/**
 * Manager view of {@link BlockWrites}, reached through {@code api.blocks()}: bulk block writes for plugins that change
 * many cells at once (edit tools, generators, restores).
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread rules
 * and failure handling are the ones documented on {@link BlockWrites}. A replacement through
 * {@code api.overrideBlocksHandler(..)} may override single methods (tests, special servers).
 *
 * @since 0.69.0
 */
public interface BlockWritesInterface {
    /** The delegating default. Plugins use {@code api.blocks()}. */
    BlockWritesInterface DEFAULT = new BlockWritesInterface() {};

    /** See {@link BlockWrites#states}. */
    default StateTable states() { return BlockWrites.states(); }

    /** See {@link BlockWrites#writer}. */
    default BlockWriter writer(org.bukkit.World world) { return BlockWrites.writer(world); }

    /** See {@link BlockWrites#bukkitWriter}. */
    default BlockWriter bukkitWriter() { return BlockWrites.bukkitWriter(); }

    /** See {@link BlockWrites#chunks}. */
    default ChunkLoads chunks() { return BlockWrites.chunks(); }

    /** See {@link BlockWrites#announce}. */
    default BlocksEditedEvent announce(org.bukkit.plugin.Plugin source, EditedChunk chunk) { return BlockWrites.announce(source, chunk); }

    /** See {@link BlockWrites#writerReport}. */
    default WriterReport writerReport() { return BlockWrites.writerReport(); }
}
