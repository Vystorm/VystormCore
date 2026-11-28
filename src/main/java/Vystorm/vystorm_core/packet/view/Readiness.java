package Vystorm.vystorm_core.packet.view;

/**
 * Whether a chunk may go to a viewer now ({@link ChunkLayer#ready}). {@link #HOLD} keeps the chunk, and every packet
 * behind it, for at most the layer's hold time; then it goes out anyway and the layer's {@code apply} decides.
 *
 * @since 0.73.0
 */
public enum Readiness { READY, HOLD }
