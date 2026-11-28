package Vystorm.vystorm_core.picker;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.block.BlockFace;

/**
 * Face sets for {@link WorldPick.Builder#faces} (0.71.0). A face pick only accepts the six block faces
 * ({@code NORTH, EAST, SOUTH, WEST, UP, DOWN}).
 *
 * @since 0.71.0
 */
public final class PickFaces {
    /** The four side faces: walls. */
    public static final Set<BlockFace> SIDES = Collections.unmodifiableSet(EnumSet.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST));
    /** The top face: floors. */
    public static final Set<BlockFace> UP = Collections.unmodifiableSet(EnumSet.of(BlockFace.UP));
    /** The bottom face: ceilings. */
    public static final Set<BlockFace> DOWN = Collections.unmodifiableSet(EnumSet.of(BlockFace.DOWN));
    /** All six faces (default). */
    public static final Set<BlockFace> ANY = Collections.unmodifiableSet(EnumSet.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN));

    private PickFaces() {}
}
