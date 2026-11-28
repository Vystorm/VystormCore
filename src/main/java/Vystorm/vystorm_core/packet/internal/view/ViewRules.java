package Vystorm.vystorm_core.packet.internal.view;

import java.util.UUID;

/**
 * What the connection handlers ask the router ({@link ViewRouter} on a server; a fake in the handler tests). Moved from
 * VystormAntiXray's {@code platform.Rules}. Internal.
 */
public interface ViewRules {
    /** Marker: drop the packet. */
    Object DROP = new Object();

    /** Marker: pass a chunk, light or section packet unchanged (its bytes are not rewritten either). */
    Object UNMASKED = new Object();

    /**
     * How long a packet must wait (nanoseconds; 0 = not at all): only chunk packets a layer is not ready for. Tells the
     * layers that asked for the hold (once per held packet).
     */
    long hold(ViewerSlot v, Object msg, UUID world);

    /** Whether a held packet must still wait (asked again while it is held). */
    boolean mustHold(ViewerSlot v, Object msg, UUID world);

    /** The packet itself (pass), {@link #DROP}, {@link #UNMASKED}, a {@link ViewKind} (pass, rewrite the bytes) or a replacement packet. */
    Object decide(ViewerSlot v, Object msg, UUID world);

    /** Which kind of packet an encoded buffer is, by its packet id; null for every packet that is never rewritten. */
    ViewKind kindOf(int packetId);

    /** Whether this viewer's packets may be rewritten now (layers are registered, the view is on). */
    boolean filtering(ViewerSlot v);

    /**
     * New bytes for an encoded packet (packet id first) of the given world: the same array, new bytes, or null to drop
     * it. Never throws for a packet it cannot read: it decides itself (fail-closed when a MASK layer is interested).
     */
    byte[] rewrite(ViewerSlot v, ViewKind kind, byte[] whole, UUID world);

    /** For a packet that puts the client into a world (login, respawn): that world; null for every other packet. */
    default UUID worldOf(ViewerSlot v, Object msg) { return null; }

    /** Runs right before a packet goes on to the encoder, in the order the client gets them (world switch, leaving play). */
    default void passing(ViewerSlot v, Object msg) { }

    /** Whether the server may have changed the pipeline around this message (a protocol switch, a bundle seen). */
    default boolean pipelineEvent(Object msg) { return false; }

    /** Whether this is a bundle of packets (the server's unbundler takes it apart). */
    default boolean isBundle(Object msg) { return false; }

    /** A chunk packet the byte side let through for the client's current world (the client holds it now). */
    default void delivered(ViewerSlot v, ViewKind kind, byte[] whole, UUID world) { }
}
