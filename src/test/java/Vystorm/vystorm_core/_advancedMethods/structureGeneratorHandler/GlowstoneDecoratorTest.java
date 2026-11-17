package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class GlowstoneDecoratorTest {
    @Test
    void prefersDarkFoliage() {
        FakeAccess access = new FakeAccess();
        StructurePos bright = new StructurePos(0, 10, 0);
        StructurePos dark = new StructurePos(8, 10, 0);
        access.put(bright, Material.OAK_LEAVES, 15);
        access.put(dark, Material.OAK_LEAVES, 3);

        int placed = GlowstoneDecorator.decorate(access, Set.of(bright, dark), TreeSpecies.OAK,
                new Random(42), 16, 1, 7);

        assertEquals(1, placed);
        assertEquals(Material.OAK_LEAVES, access.getType(bright.x(), bright.y(), bright.z()));
        assertEquals(Material.GLOWSTONE, access.getType(dark.x(), dark.y(), dark.z()));
    }

    @Test
    void scalesWithFoliageAndHonorsMaximum() {
        FakeAccess access = new FakeAccess();
        Set<StructurePos> leaves = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            StructurePos position = new StructurePos(index * 4, 20, 0);
            access.put(position, Material.BIRCH_LEAVES, 15);
            leaves.add(position);
        }

        int placed = GlowstoneDecorator.decorate(access, leaves, TreeSpecies.BIRCH,
                new Random(7), 50, 3, 7);

        assertEquals(3, placed);
    }

    private static final class FakeAccess implements StructureAccess {
        private final Map<StructurePos, Material> blocks = new HashMap<>();
        private final Map<StructurePos, Integer> light = new HashMap<>();

        private void put(StructurePos position, Material material, int lightLevel) {
            blocks.put(position, material);
            light.put(position, lightLevel);
        }

        @Override
        public Material getType(int x, int y, int z) {
            return blocks.getOrDefault(new StructurePos(x, y, z), Material.AIR);
        }

        @Override
        public void setType(int x, int y, int z, Material material) {
            blocks.put(new StructurePos(x, y, z), material);
        }

        @Override
        public void setBlockData(int x, int y, int z, BlockData data) {
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return true;
        }

        @Override
        public int getLightLevel(int x, int y, int z) {
            return light.getOrDefault(new StructurePos(x, y, z), 15);
        }
    }
}
