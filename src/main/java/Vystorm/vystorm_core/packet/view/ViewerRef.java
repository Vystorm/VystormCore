package Vystorm.vystorm_core.packet.view;

import java.util.UUID;

/**
 * A lightweight handle of one viewer (a player's client, or a virtual viewer of a proof) for code on the Netty threads:
 * no {@code Player}, immutable, valid until {@link ChunkLayer#left}. {@code slot} is a small dense number (reused after
 * a viewer left) for per-viewer arrays in layers.
 *
 * @since 0.73.0
 */
public record ViewerRef(UUID id, String name, int slot) {}
