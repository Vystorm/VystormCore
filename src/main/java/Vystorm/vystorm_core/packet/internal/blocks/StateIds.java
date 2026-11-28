package Vystorm.vystorm_core.packet.internal.blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.block.data.BlockData;

/**
 * Block data to small int ids for the store, by {@code BlockData.equals}. Keeps a private copy of every state (the
 * caller's object is never kept). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
final class StateIds {
    private final Map<BlockData, Integer> ids = new HashMap<>();
    private final List<BlockData> states = new ArrayList<>();
    /** 0 unknown, 1 not solid, 2 solid. */
    private byte[] solid = new byte[64];

    int id(BlockData data) {
        Integer id = ids.get(data);
        if (id != null) return id;
        BlockData copy = data.clone();
        if (copy == null) copy = data; // test stand-ins
        int next = states.size();
        states.add(copy);
        ids.put(copy, next);
        return next;
    }

    /** The shared copy; never hand it to code Core does not own without cloning it. */
    BlockData data(int id) { return states.get(id); }

    int size() { return states.size(); }

    boolean solid(int id) {
        if (id >= solid.length) solid = Arrays.copyOf(solid, Math.max(id + 1, solid.length * 2));
        byte known = solid[id];
        if (known == 0) {
            boolean value;
            try {
                // Material.isSolid needs the server registry; without a server (unit tests) a failed registry start
                // would break later tests of the same run, so it is never called there.
                value = org.bukkit.Bukkit.getServer() != null && states.get(id).getMaterial().isSolid();
            } catch (RuntimeException | LinkageError unknown) {
                value = false;
            }
            solid[id] = known = (byte) (value ? 2 : 1);
        }
        return known == 2;
    }
}
