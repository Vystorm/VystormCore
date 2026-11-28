package Vystorm.vystorm_core.packet.view;

import java.util.List;
import java.util.UUID;

/**
 * An outgoing packet that names a position or a player, as a MASK layer judges it ({@link ChunkLayer#drop}). Nothing
 * here exposes server classes. Valid only inside the call.
 *
 * @since 0.73.0
 */
public final class ObjectPacket {
    /** The packets Core hands to {@link ChunkLayer#drop}. */
    public enum Kind {
        /** An entity spawns (position of the entity; {@link #entityId()}). */
        ADD_ENTITY,
        /** A sound at a position. */
        SOUND,
        /** Particles at a position. */
        PARTICLES,
        /** A block event (chest lid, note block, piston) at a block. */
        BLOCK_EVENT,
        /** Block breaking progress at a block. */
        BLOCK_DESTRUCTION,
        /** A level event at a block (not the global ones, which are heard everywhere). */
        LEVEL_EVENT,
        /** The data of a block entity at a block. */
        BLOCK_ENTITY_DATA,
        /** Players leave the tab list ({@link #profileIds()}; no position). */
        PLAYER_INFO_REMOVE
    }

    private final Kind kind;
    private final double x, y, z;
    private final int entityId;
    private final List<UUID> profileIds;

    public ObjectPacket(Kind kind, double x, double y, double z, int entityId, List<UUID> profileIds) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.entityId = entityId;
        this.profileIds = profileIds == null ? List.of() : profileIds;
    }

    public Kind kind() { return kind; }

    /** The position (block packets: the block's corner). Meaningless for {@link Kind#PLAYER_INFO_REMOVE}. */
    public double x() { return x; }

    public double y() { return y; }

    public double z() { return z; }

    /** The block of the position ({@code floor}). */
    public int blockX() { return (int) Math.floor(x); }

    public int blockY() { return (int) Math.floor(y); }

    public int blockZ() { return (int) Math.floor(z); }

    /** The entity of {@link Kind#ADD_ENTITY}, else 0. */
    public int entityId() { return entityId; }

    /** The players of {@link Kind#PLAYER_INFO_REMOVE}, else empty. */
    public List<UUID> profileIds() { return profileIds; }

    @Override public String toString() { return kind + (kind == Kind.PLAYER_INFO_REMOVE ? " " + profileIds : " " + x + " " + y + " " + z); }
}
