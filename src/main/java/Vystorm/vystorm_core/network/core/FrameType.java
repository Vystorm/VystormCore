package Vystorm.vystorm_core.network.core;

/**
 * Wire frame types. The ordinal is the wire id, so new types are only ever appended.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public enum FrameType {
    HELLO, PROOF, WELCOME, PAIR_REPLY, STATE_SNAPSHOT, STATE_DELTA, MESSAGE, SEND_PLAYER, SEND_PLAYER_RESULT, HEARTBEAT, ERROR;

    private static final FrameType[] VALUES = values();

    public int id() { return ordinal(); }

    public static FrameType byId(int id) throws FrameException {
        if (id < 0 || id >= VALUES.length) throw new FrameException("Unknown frame type " + id);
        return VALUES[id];
    }
}
