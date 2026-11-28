package Vystorm.vystorm_core.blocks;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * A mocked {@link World} whose blocks live in a map of state ids (missing cells are air). Counts block reads, writes
 * and whether every write skipped physics.
 */
public final class TestWorld {
    private final StateTable states;
    private final Map<Long, Integer> cells = new HashMap<>();
    private final World world = mock(World.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
    private final AtomicBoolean physics = new AtomicBoolean();

    public TestWorld(StateTable states) {
        this.states = states;
        when(world.getUID()).thenReturn(id);
        when(world.getName()).thenReturn("test-world");
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
    }

    private Block block(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getWorld()).thenReturn(world);
        when(block.getBlockData()).thenAnswer(call -> { reads.incrementAndGet(); return states.data(get(x, y, z)).clone(); });
        doAnswer(call -> {
            writes.incrementAndGet();
            if ((boolean) call.getArgument(1)) physics.set(true);
            set(x, y, z, states.id((BlockData) call.getArgument(0)));
            return null;
        }).when(block).setBlockData(org.mockito.ArgumentMatchers.any(BlockData.class), anyBoolean());
        return block;
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }

    public World world() { return world; }
    public int get(int x, int y, int z) { return cells.getOrDefault(key(x, y, z), StateTable.AIR); }
    public void set(int x, int y, int z, int state) { cells.put(key(x, y, z), state); }
    public int reads() { return reads.get(); }
    public int writes() { return writes.get(); }
    public boolean writesWithoutPhysics() { return !physics.get(); }
}
