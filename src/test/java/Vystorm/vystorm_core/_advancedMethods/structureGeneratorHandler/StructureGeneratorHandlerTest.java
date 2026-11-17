package Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class StructureGeneratorHandlerTest {
    @Test
    void givenRegisteredCallbackWhenGeneratingThenWritesDeterministicallyOnCallerThread() {
        StructureGeneratorHandler handler = new StructureGeneratorHandler();
        Plugin owner = enabledPlugin();
        NamespacedKey id = new NamespacedKey("test", "marker");
        handler.register(owner, id, (access, origin, random) ->
                access.setType(origin.x() + random.nextInt(1), origin.y(), origin.z(), Material.GLOWSTONE));
        FakeAccess access = new FakeAccess();

        assertTrue(handler.generate(id, access, new StructurePos(4, 70, -2), new Random(17)));
        assertEquals(Material.GLOWSTONE, access.getType(4, 70, -2));
    }

    @Test
    void givenOwnersWhenCollidingOrCleaningUpThenOwnershipIsPreserved() {
        StructureGeneratorHandler handler = new StructureGeneratorHandler();
        Plugin first = enabledPlugin(), second = enabledPlugin();
        NamespacedKey id = new NamespacedKey("test", "owned");
        handler.register(first, id, (access, origin, random) -> { });

        assertThrows(IllegalArgumentException.class,
                () -> handler.register(second, id, (access, origin, random) -> { }));
        assertFalse(handler.unregister(second, id));
        handler.removeAll(first);
        assertFalse(handler.generate(id, new FakeAccess(), new StructurePos(0, 0, 0), new Random(1)));
    }

    @Test
    void givenDisabledOwnerWhenGeneratingThenDoesNotInvokeCallback() {
        StructureGeneratorHandler handler = new StructureGeneratorHandler();
        Plugin owner = enabledPlugin();
        NamespacedKey id = new NamespacedKey("test", "disabled");
        StructureGenerator callback = mock(StructureGenerator.class);
        handler.register(owner, id, callback);
        when(owner.isEnabled()).thenReturn(false);

        assertFalse(handler.generate(id, new FakeAccess(), new StructurePos(0, 0, 0), new Random(1)));
        verifyNoInteractions(callback);
    }

    @Test
    void givenInvalidTreeSettingsWhenBuildingThenDoesNotWrite() {
        FakeAccess access = new FakeAccess();
        BlockData log = mock(BlockData.class), leaves = mock(BlockData.class);
        TreeGenerator tree = new TreeBuilder(access, new Random(1), TreeSpecies.OAK, log, leaves);

        assertThrows(IllegalArgumentException.class,
                () -> tree.buildGiant(new StructurePos(0, 70, 0), 15, 96, 5));
        assertTrue(access.blocks.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new StructureGeneratorHandler()
                .decorateGlowstone(access, java.util.Set.of(new StructurePos(0, 70, 0)),
                        TreeSpecies.OAK, new Random(1), 15, 1, 7));
        assertTrue(access.blocks.isEmpty());
    }

    private static Plugin enabledPlugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static final class FakeAccess implements StructureAccess {
        private final Map<StructurePos, Material> blocks = new HashMap<>();
        @Override public Material getType(int x, int y, int z) {
            return blocks.getOrDefault(new StructurePos(x, y, z), Material.AIR);
        }
        @Override public void setType(int x, int y, int z, Material material) {
            blocks.put(new StructurePos(x, y, z), material);
        }
        @Override public void setBlockData(int x, int y, int z, BlockData data) {
            blocks.put(new StructurePos(x, y, z), data.getMaterial());
        }
        @Override public boolean contains(int x, int y, int z) { return true; }
    }
}
