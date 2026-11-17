package Vystorm.vystorm_core.display;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.display.internal.IdPool;
import Vystorm.vystorm_core.display.internal.MapTiles;
import Vystorm.vystorm_core.display.internal.WallGeometry;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ClientDisplaysTest {
    @Test
    void givenTwoChangedPixels_whenDiffingTiles_thenOnlyTheirTileGetsTheBoundingRectangle() {
        int stride = 2 * 128;
        byte[] a = new byte[stride * 128], b = a.clone();
        b[10 * stride + 128 + 5] = 7;   // tile 1, (5, 10)
        b[40 * stride + 128 + 90] = 7;  // tile 1, (90, 40)
        assertNull(MapTiles.changed(a, b, stride, 0, 0));
        assertArrayEquals(new int[]{5, 10, 91, 41}, MapTiles.changed(a, b, stride, 1, 0));
        assertNull(MapTiles.changed(a, a.clone(), stride, 1, 0));
    }

    @Test
    void givenARectangle_whenCopyingRegion_thenRowsOfThatTileAreCopied() {
        int stride = 2 * 128;
        byte[] pixels = new byte[stride * 256];
        pixels[(128 + 3) * stride + 128 + 2] = 9; // tile (1, 1), pixel (2, 3)
        byte[] region = MapTiles.region(pixels, stride, 1, 1, new int[]{2, 3, 4, 5});
        assertEquals(4, region.length);
        assertEquals(9, region[0]);
        assertArrayEquals(new int[]{0, 0, 9, 9}, MapTiles.union(new int[]{0, 0, 1, 1}, new int[]{8, 8, 9, 9}));
    }

    @Test
    void givenReservedRange_whenTakingAndGivingIds_thenIdsAreRecycledInRange() {
        IdPool pool = new IdPool(ClientDisplays.MAP_ID_FIRST, ClientDisplays.MAP_ID_COUNT);
        int a = pool.take(), b = pool.take();
        assertEquals(ClientDisplays.MAP_ID_FIRST, a);
        assertEquals(a + 1, b);
        pool.give(a);
        assertEquals(a, pool.take());
        assertTrue(pool.contains(Integer.MAX_VALUE));
        assertFalse(pool.contains(ClientDisplays.MAP_ID_FIRST - 1));
        assertThrows(IllegalArgumentException.class, () -> pool.give(5));
        IdPool tiny = new IdPool(10, 1);
        tiny.take();
        assertThrows(IllegalStateException.class, tiny::take);
    }

    @Test
    void givenNorthFacingWall_whenRayCasting_thenPixelsFollowBoardsConvention() {
        // frames face north (fx=0, fz=-1): viewer looks south, their right is west (-x)
        assertArrayEquals(new int[]{-1, 0}, WallGeometry.right(0, -1));
        assertArrayEquals(new int[]{8, 63, 0}, WallGeometry.tile(10, 64, 0, 0, -1, 2, 1));
        // looking straight at the middle of the top-left tile from the north
        int[] hit = WallGeometry.pixel(10, 64, 0, 0, -1, 2, 2, new double[]{10.5, 64.5, -3}, new double[]{0, 0, 1});
        assertArrayEquals(new int[]{64, 64}, hit);
        assertNull(WallGeometry.pixel(10, 64, 0, 0, -1, 2, 2, new double[]{10.5, 64.5, -3}, new double[]{0, 0, -1}));
        assertEquals(0, WallGeometry.distanceSquared(10, 64, 0, 0, -1, 2, 2, 9.5, 63.5, 0.5));
        assertEquals(9, WallGeometry.distanceSquared(10, 64, 0, 0, -1, 2, 2, 10.5, 64.5, -3), 1e-9);
    }

    @Test
    void givenInvalidShapes_whenBuildingSpec_thenTheyAreRejected() {
        World world = Mockito.mock(World.class);
        MapWall.Spec spec = MapWall.Spec.of(world, 0, 64, 0, BlockFace.NORTH, 4, 3);
        assertTrue(spec.glow() && spec.invisible());
        assertEquals(48, spec.viewDistance());
        assertEquals(512, spec.pixelWidth());
        assertFalse(spec.glow(false).glow());
        assertThrows(IllegalArgumentException.class, () -> MapWall.Spec.of(world, 0, 0, 0, BlockFace.UP, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> MapWall.Spec.of(world, 0, 0, 0, BlockFace.EAST, 33, 1));
        assertThrows(IllegalArgumentException.class, () -> spec.viewDistance(0));
    }
}
