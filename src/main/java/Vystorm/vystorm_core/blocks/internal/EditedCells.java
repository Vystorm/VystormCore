package Vystorm.vystorm_core.blocks.internal;

import Vystorm.vystorm_core.blocks.EditCause;
import Vystorm.vystorm_core.blocks.EditedChunk;
import Vystorm.vystorm_core.blocks.StateTable;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * The immutable cells {@link EditedChunk.Builder} builds (arrays owned by this object).
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class EditedCells implements EditedChunk {
    private final UUID editId, actor;
    private final EditCause cause;
    private final World world;
    private final int chunkX, chunkZ, minY, maxY;
    private final int[] xs, ys, zs, befores, afters;
    private final StateTable states;

    public EditedCells(UUID editId, UUID actor, EditCause cause, World world, int chunkX, int chunkZ,
                       int[] xs, int[] ys, int[] zs, int[] befores, int[] afters, StateTable states) {
        this.editId = editId; this.actor = actor; this.cause = cause; this.world = world;
        this.chunkX = chunkX; this.chunkZ = chunkZ;
        this.xs = xs; this.ys = ys; this.zs = zs; this.befores = befores; this.afters = afters; this.states = states;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int value : ys) { low = Math.min(low, value); high = Math.max(high, value); }
        minY = low; maxY = high;
    }

    @Override public UUID editId() { return editId; }
    @Override public UUID actor() { return actor; }
    @Override public EditCause cause() { return cause; }
    @Override public World world() { return world; }
    @Override public int chunkX() { return chunkX; }
    @Override public int chunkZ() { return chunkZ; }
    @Override public int size() { return xs.length; }
    @Override public int x(int i) { return xs[i]; }
    @Override public int y(int i) { return ys[i]; }
    @Override public int z(int i) { return zs[i]; }
    @Override public int beforeId(int i) { return befores[i]; }
    @Override public int afterId(int i) { return afters[i]; }
    @Override public BlockData before(int i) { return states.data(befores[i]).clone(); }
    @Override public BlockData after(int i) { return states.data(afters[i]).clone(); }

    @Override public boolean touches(int low, int high) {
        if (xs.length == 0 || high < minY || low > maxY) return false;
        for (int value : ys) if (value >= low && value <= high) return true;
        return false;
    }

    /** The table the ids belong to (Core's survey reads the cell classes from it). */
    public StateTable states() { return states; }

    @Override public String toString() {
        return "EditedChunk[" + cause + " " + editId + " " + world.getName() + " " + chunkX + ", " + chunkZ + ", " + xs.length + " cells]";
    }
}
