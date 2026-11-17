package Vystorm.vystorm_core.packet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.packet.internal.ChunkLight;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

class ClientChunksTest {
    static BlockData data(Material m) {
        BlockData d = mock(BlockData.class);
        when(d.getMaterial()).thenReturn(m);
        return d;
    }

    static World world(int min, int max) {
        World w = mock(World.class);
        when(w.getMinHeight()).thenReturn(min);
        when(w.getMaxHeight()).thenReturn(max);
        return w;
    }

    @Test
    void draftKeepsBlocksInWorldCoordinatesAndClipsToTheHeight() {
        BlockData stone = data(Material.STONE), grass = data(Material.GRASS_BLOCK), air = data(Material.AIR);
        ClientChunks.Draft d = new ClientChunks.Draft(world(-64, 320), 3, -2);
        d.fill(1, 2, -100, 70, stone).set(1, 70, 2, grass).set(1, 500, 2, grass).set(1, 69, 2, air);
        d.biome(1, 2, NamespacedKey.minecraft("desert"));
        assertEquals(24, d.sections);
        assertEquals(3, d.palette.size(), "air is never a palette entry: null, stone, grass");
        int c = 2 * 16 + 1;
        assertEquals(1, d.blocks[0 * 256 + c], "fill starts at the world bottom");
        assertEquals(0, d.blocks[(69 + 64) * 256 + c], "air set over stone");
        assertEquals(2, d.blocks[(70 + 64) * 256 + c]);
        assertEquals("minecraft:desert", d.biomes[c]);
        assertTrue(ClientChunks.Draft.air(Material.CAVE_AIR));
        assertFalse(ClientChunks.Draft.air(Material.WATER));
    }

    @Test
    void skyLightIsFullAboveTheSurfaceAndFadesInWater() {
        int sections = 8, minY = 0;
        short[] blocks = new short[sections * 4096];
        int[] opacity = {0, 15, 1};
        // every column: stone up to y 40; column (0,0) has water from 40 to 44 over stone up to 40
        for (int c = 0; c < 256; c++) for (int y = 0; y < 40; y++) blocks[y * 256 + c] = 1;
        for (int y = 40; y < 44; y++) blocks[y * 256] = 2;
        ChunkLight.Sky sky = ChunkLight.sky(blocks, opacity, sections);
        // sections 0 and 1 (y < 32) are fully below the lit blocks: empty; section 2 (32-47) lit, 3 (48-63) above the top + 1
        assertTrue(sky.empty().get(0) && sky.empty().get(1) && sky.empty().get(2));
        assertTrue(sky.mask().get(3) && sky.mask().get(4));
        assertFalse(sky.mask().get(5), "sections above the highest block + 1 get no data (open sky on the client)");
        byte[] s2 = sky.data().get(0);
        assertEquals(15, ChunkLight.at(s2, 5, 40 - 32, 5), "air right above the stone");
        assertEquals(0, ChunkLight.at(s2, 5, 39 - 32, 5), "inside the stone");
        assertEquals(14, ChunkLight.at(s2, 0, 43 - 32, 0), "top water block");
        assertEquals(11, ChunkLight.at(s2, 0, 40 - 32, 0), "bottom water block");
        assertEquals(15, ChunkLight.at(sky.data().get(1), 7, 3, 9));
        assertEquals(sky.mask().cardinality(), sky.data().size());
    }

    @Test
    void emptyChunkHasNoLightData() {
        ChunkLight.Sky sky = ChunkLight.sky(new short[4 * 4096], new int[]{0}, 4);
        assertTrue(sky.mask().isEmpty());
        assertTrue(sky.data().isEmpty());
    }

    @Test
    void clientRangeFollowsTheVanillaStorageRadius() {
        assertEquals(5, ClientChunks.clientRange(0));
        assertEquals(13, ClientChunks.clientRange(10));
        assertEquals(0x0000000500000003L, ClientChunks.key(3, 5));
        assertEquals(-1, (int) ClientChunks.key(-1, 7));
        assertEquals(7, (int) (ClientChunks.key(-1, 7) >> 32));
    }
}
