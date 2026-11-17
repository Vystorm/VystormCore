package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.HashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ScreenMapDemoTest {
    @Test void givenCanvasCentre_whenWorldAt_thenPlayerBlock() {
        int[] at = ScreenMapDemo.worldAt(128, 127, 4, 100, -20);
        assertArrayEquals(new int[] { 100, -20 }, at);
        assertArrayEquals(new int[] { 100 - 128 * 4, -20 - 127 * 4 }, ScreenMapDemo.worldAt(0, 0, 4, 100, -20));
    }

    @Test void givenUnloadedChunks_whenRender_thenTransparentExceptPlayerCross() {
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        byte[] pixels = ScreenMapDemo.render(world, 0, 0, 2, new HashMap<>());
        assertEquals(ScreenMapDemo.GRID.pixelWidth() * ScreenMapDemo.GRID.pixelHeight(), pixels.length);
        assertEquals(0, pixels[0]);
        assertNotEquals(0, pixels[127 * 256 + 128], "player marker");
        verify(world, never()).getHighestBlockYAt(anyInt(), anyInt());
    }

    @Test void givenLoadedFlatGrass_whenRender_thenOneNormalShade() {
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        Block grass = mock(Block.class);
        BlockData data = mock(BlockData.class);
        when(grass.getType()).thenReturn(Material.GRASS_BLOCK);
        when(grass.getBlockData()).thenReturn(data);
        when(data.getMapColor()).thenReturn(org.bukkit.Color.fromRGB(0x7FB238));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(grass);
        byte[] pixels = ScreenMapDemo.render(world, 0, 0, 1, new HashMap<>());
        assertNotEquals(0, pixels[0]);
        assertEquals(pixels[0], pixels[10 * 256 + 40], "flat ground has one colour");
    }

    @Test void givenLayout_thenMapKeepsSquarePixelsAndAllWidgetsInsidePanel() {
        Lang lang = mock(Lang.class, invocation -> Component.text("x"));
        ScreenLayout layout = ScreenMapDemo.layout(lang, mock(Player.class), 2);
        ScreenWidget map = layout.widgets().stream().filter(w -> w.kind() == ScreenWidget.Kind.MAP).findFirst().orElseThrow();
        assertEquals(map.width() / 256, map.height() / 254, 1e-9);
        for (ScreenWidget w : layout.widgets()) {
            assertTrue(w.u() >= 0 && w.u() + w.width() <= layout.width() + 1e-9, w.id());
            assertTrue(w.v() >= 0 && w.v() + w.height() <= layout.height() + 1e-9, w.id());
        }
    }
}
