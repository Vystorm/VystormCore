package Vystorm.vystorm_core._advancedMethods.worldGeneratorHandler;
import org.bukkit.*;
import org.bukkit.generator.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldGeneratorHandlerTest {
    @Test void givenLayersWhenGeneratingThenClipToWorldHeightAndPreserveLayerOrder() {
        var handler = new WorldGeneratorHandler();
        Material bedrock = mock(Material.class), stone = mock(Material.class);
        when(bedrock.isBlock()).thenReturn(true); when(bedrock.isSolid()).thenReturn(true);
        when(stone.isBlock()).thenReturn(true); when(stone.isSolid()).thenReturn(true);
        var layers = new ArrayList<>(List.of(new WorldGeneratorHandler.Layer(bedrock,1), new WorldGeneratorHandler.Layer(stone,100)));
        var generator = handler.flatGenerator(-65,layers); layers.clear();
        var data = mock(ChunkGenerator.ChunkData.class); when(data.getMinHeight()).thenReturn(-64); when(data.getMaxHeight()).thenReturn(32);
        WorldInfo world = mock(WorldInfo.class); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(32);
        generator.generateNoise(world,new Random(1),-1,0,data);
        verify(data).setRegion(0,-64,0,16,32,16,stone);
        verify(data,never()).setRegion(anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),eq(bedrock));
        assertEquals(32,generator.getBaseHeight(world,new Random(),0,0,HeightMap.WORLD_SURFACE));
        assertFalse(generator.shouldGenerateNoise()); assertFalse(generator.shouldGenerateStructures());
    }
    @Test void givenVoidGeneratorWhenGeneratingThenDoesNotWriteBlocks() {
        var generator = new WorldGeneratorHandler().voidGenerator();
        var data = mock(ChunkGenerator.ChunkData.class);
        generator.generateNoise(mock(WorldInfo.class),new Random(),0,0,data); verifyNoInteractions(data);
        assertThrows(IllegalArgumentException.class,()->new WorldGeneratorHandler.Layer(mock(Material.class),1));
    }
    @Test void givenRegistryWhenRegisteringDuplicateThenPreserveExistingGenerator() {
        var h = new WorldGeneratorHandler(); var g = h.voidGenerator(); h.register("Void",g);
        assertSame(g,h.get("VOID")); assertThrows(IllegalArgumentException.class,()->h.register("void",h.voidGenerator()));
        assertTrue(h.unregister("void")); assertNull(h.get("void"));
    }
    @Test void givenOccupiedWorldWhenUnloadingThenNeverUnloadIt() {
        try(var b = mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World w = mock(World.class); when(w.getPlayers()).thenReturn(List.of(mock(org.bukkit.entity.Player.class)));
            assertFalse(new WorldGeneratorHandler().unload(w,true));
            b.verify(()->Bukkit.unloadWorld(w,true),never());
        }
    }
}
