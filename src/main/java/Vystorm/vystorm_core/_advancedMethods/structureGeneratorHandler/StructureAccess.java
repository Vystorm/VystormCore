package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Bounded, caller-owned block access for synchronous structure generation. The caller must
 * select an appropriate thread and make {@link #contains(int, int, int)} enforce every bound
 * that its block methods require. Core never retains a world or this access instance.
 */
public interface StructureAccess {
    Material getType(int x, int y, int z);
    void setType(int x, int y, int z, Material material);
    void setBlockData(int x, int y, int z, BlockData data);
    boolean contains(int x, int y, int z);

    default int getLightLevel(int x, int y, int z) {
        return 15;
    }
}
