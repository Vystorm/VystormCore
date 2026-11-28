package Vystorm.vystorm_core.survey;

import java.util.Objects;
import java.util.Set;

/**
 * Rooms of neighbouring chunks joined through their border runs, as far as they were followed. In real worlds one
 * component can span thousands of chunks (study: 53 to 92 percent of all enclosed air in one component), so a search
 * stops at a chunk limit; {@link #complete()} says whether it got to the end.
 *
 * @param rooms    the joined rooms found
 * @param volume   cells of these rooms
 * @param mouth    a room of the component touches a surface cell
 * @param complete every border of every room was followed (no chunk limit hit, every neighbour surveyed)
 * @param chunks   number of chunks visited
 * @since 0.68.0
 */
public record RoomComponent(Set<RoomId> rooms, long volume, boolean mouth, boolean complete, int chunks) {
    public RoomComponent {
        rooms = Set.copyOf(Objects.requireNonNull(rooms, "rooms"));
    }

    /** {@link SkyContact#MOUTH}, {@link SkyContact#SEALED} (complete without mouth) or {@link SkyContact#UNKNOWN}. */
    public SkyContact skyContact() {
        return mouth ? SkyContact.MOUTH : complete ? SkyContact.SEALED : SkyContact.UNKNOWN;
    }

    public boolean contains(RoomId room) { return rooms.contains(room); }
}
