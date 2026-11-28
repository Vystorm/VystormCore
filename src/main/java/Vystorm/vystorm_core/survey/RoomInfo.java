package Vystorm.vystorm_core.survey;

import java.util.Objects;

/**
 * One room of a chunk survey: a connected group (six neighbours) of enclosed open cells inside one chunk.
 *
 * @param id     the room
 * @param volume number of cells
 * @param mouth  a cell of the room touches a surface cell (in this chunk or a neighbour)
 * @param sealed no mouth and no cell on a side face: the room is closed inside this chunk, whatever the neighbours are
 * @param fluid  the room holds water, lava or bubble columns
 * @since 0.68.0
 */
public record RoomInfo(RoomId id, int volume, boolean mouth, boolean sealed, boolean fluid) {
    public RoomInfo {
        Objects.requireNonNull(id, "id");
        if (volume < 1) throw new IllegalArgumentException("volume " + volume);
        if (mouth && sealed) throw new IllegalArgumentException("a room with a mouth is not sealed");
    }
}
