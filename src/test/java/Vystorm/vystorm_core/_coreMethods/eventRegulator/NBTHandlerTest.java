package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.YmlEntry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NBTHandlerTest {
    @TempDir Path directory;

    private NBTHandler handler(YmlEntry storage) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Vystorm_Core");
        when(plugin.namespace()).thenReturn("vystorm_core");
        YmlEntry settings = new YmlEntry();
        settings.setEntry("NBT_Tag", "[VystormCore]");
        return new NBTHandler(plugin, settings, storage);
    }

    @Test
    void persistsBlockTagsAcrossReloadAndClearsWithoutRestoringOldValues() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(-17);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(2);
        when(block.getLocation()).thenReturn(new Location(world, -17, 64, 2));
        YmlEntry storage = new YmlEntry("blocks.yml", directory);
        NBTHandler first = handler(storage);
        first.setNBT(block, "Action", "value");
        first.setNBT(block, "second", "other");
        YmlEntry reloaded = new YmlEntry("blocks.yml", directory);
        reloaded.loadEntry();
        NBTHandler second = handler(reloaded);
        assertEquals("value", second.getNBT(block, "action"));
        assertEquals(2, second.getAllNBT(block).size());
        second.getAllNBT(block).clear();
        assertEquals("value", second.getNBT(block, "Action"));
        assertEquals("action", second.removeNBT(block, "action"));
        second.clearNBT(block);
        reloaded.loadEntry();
        assertTrue(handler(reloaded).getAllNBT(block).isEmpty());
    }

    @Test
    void readingUntaggedBlocksDoesNotGrowTheCache() throws Exception {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        NBTHandler handler = handler(new YmlEntry("blocks.yml", directory));
        for (int i = 0; i < 100; i++) {
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(i);
            when(block.getLocation()).thenReturn(new Location(world, i, 0, 0));
            assertNull(handler.getNBT(block, "x"));
            assertTrue(handler.getAllNBT(block).isEmpty());
            assertNull(handler.removeNBT(block, "x"));
        }
        var field = NBTHandler.class.getDeclaredField("registeredBlocks");
        field.setAccessible(true);
        assertEquals(0, ((java.util.Map<?, ?>) field.get(handler)).size());
    }

    @Test
    void absentObjectsHaveNoTagsAndLegacyPrefixAcceptsActionNames() {
        NBTHandler handler = handler(new YmlEntry("blocks.yml", directory));
        assertTrue(handler.getAllNBT((Player) null).isEmpty());
        assertNull(handler.getNBT((Player) null, "absent"));
        assertTrue(handler.getAllNBT((Block) null).isEmpty());
        NBTEntity action = mock(NBTEntity.class);
        handler.setEntityNBT(action, "MixedCase");
        assertSame(action, handler.getEntityNBT("mixedcase"));
    }
}
