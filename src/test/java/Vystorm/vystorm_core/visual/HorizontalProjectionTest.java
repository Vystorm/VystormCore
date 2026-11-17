package Vystorm.vystorm_core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class HorizontalProjectionTest {
    private static final UUID ENTRANCE_WORLD_ID = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");
    private static final UUID DESTINATION_WORLD_ID = UUID.fromString("8d3be06b-d899-4f65-b230-3e13b8fb1b5a");

    private MockedStatic<Bukkit> bukkit;
    private World entranceWorld;
    private World destinationWorld;

    @BeforeEach
    void givenServerThreadAndMockedWorlds() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        entranceWorld = world();
        destinationWorld = world();
        bukkit.when(() -> Bukkit.getWorld(ENTRANCE_WORLD_ID)).thenReturn(entranceWorld);
        bukkit.when(() -> Bukkit.getWorld(DESTINATION_WORLD_ID)).thenReturn(destinationWorld);
    }

    @AfterEach
    void closeAllViewsOnServerThread() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        Visuals.shutdown();
        bukkit.close();
    }

    @Test
    void givenOpeningMaskWithCentreHoleWhenProjectingThenSamplesOnlyOpenCellsAtTranslatedTargets() {
        HorizontalFrame entrance = entrance();
        HorizontalFrame destination = destination();
        BlockData left = clonedBlockData();
        BlockData right = clonedBlockData();
        stubSample(99, 69, 100, left);
        stubSample(101, 69, 100, right);
        Set<BlockPosition> opening = Set.of(new BlockPosition(-1, 64, 0), new BlockPosition(1, 64, 0));

        Map<BlockPosition, BlockData> projection = Visuals.projection(
                entrance, destination, new Point3(.5, 68, .5), 1, 2, 8, opening);

        assertEquals(2, projection.size());
        assertEquals(left, projection.get(new BlockPosition(-1, 63, 0)));
        assertEquals(right, projection.get(new BlockPosition(1, 63, 0)));
        assertFalse(projection.containsKey(new BlockPosition(0, 63, 0)), "the blocked centre aperture cell stays hidden");
        verify(destinationWorld).getBlockAt(99, 69, 100);
        verify(destinationWorld).getBlockAt(101, 69, 100);
    }

    @Test
    void givenProjectionBudgetOfOneWhenSamplingThenReturnsImmutableSingleCellResult() {
        stubSample(99, 69, 100, clonedBlockData());
        Set<BlockPosition> opening = Set.of(new BlockPosition(-1, 64, 0), new BlockPosition(1, 64, 0));

        Map<BlockPosition, BlockData> projection = Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 2, 1, opening);

        assertEquals(1, projection.size());
        assertEquals(new BlockPosition(-1, 63, 0), projection.keySet().iterator().next());
        assertThrows(UnsupportedOperationException.class,
                () -> projection.put(new BlockPosition(0, 63, 0), mock(BlockData.class)));
    }

    @Test
    void givenUnloadedApertureOrDestinationChunkWhenProjectingThenSkipsReadsWithoutLoadingChunks() {
        Set<BlockPosition> opening = Set.of(new BlockPosition(-1, 64, 0));
        when(entranceWorld.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);

        Map<BlockPosition, BlockData> unloadedEntrance = Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 2, 8, opening);

        assertTrue(unloadedEntrance.isEmpty());
        verify(entranceWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(destinationWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verifyNoChunkLoadCalls(entranceWorld);
        verifyNoChunkLoadCalls(destinationWorld);

        when(entranceWorld.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(destinationWorld.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        Map<BlockPosition, BlockData> unloadedDestination = Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 2, 8, opening);

        assertTrue(unloadedDestination.isEmpty());
        verify(entranceWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(destinationWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verifyNoChunkLoadCalls(entranceWorld);
        verifyNoChunkLoadCalls(destinationWorld);
    }

    @Test
    void givenEyeNotAboveApertureOrInvalidLimitsWhenProjectingThenRejectsWithoutSampling() {
        Set<BlockPosition> opening = Set.of(new BlockPosition(-1, 64, 0));

        assertTrue(Visuals.projection(entrance(), destination(), new Point3(.5, 64, .5), 1, 2, 8, opening).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 0, 2, 8, opening));
        assertThrows(IllegalArgumentException.class, () -> Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 17, 8, opening));
        assertThrows(IllegalArgumentException.class, () -> Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 2, 0, opening));
        assertThrows(IllegalArgumentException.class, () -> Visuals.projection(
                entrance(), destination(), new Point3(.5, 68, .5), 1, 2, 8, Set.of(new BlockPosition(0, 63, 0))));
        verify(entranceWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(destinationWorld, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void givenFractionalPlaneWhenViewingAtAnAngleThenKeepsFrameLayerOutsideMaskIntact() {
        HorizontalFrame source=new HorizontalFrame(ENTRANCE_WORLD_ID,new Point3(.5,64.75,.5),2,2);
        HorizontalFrame target=new HorizontalFrame(DESTINATION_WORLD_ID,new Point3(100.5,70.75,100.5),2,2);
        stubSample(100,70,100,clonedBlockData());
        Map<BlockPosition,BlockData> projection=Visuals.projection(source,target,new Point3(2.5,66,.5),1,1,9,Set.of(new BlockPosition(0,64,0)));
        assertEquals(Set.of(new BlockPosition(0,64,0)),projection.keySet());
        verify(destinationWorld,never()).getBlockAt(99,70,100);
    }

    private static HorizontalFrame entrance() {
        return new HorizontalFrame(ENTRANCE_WORLD_ID, new Point3(.5, 64, .5), 2, 2);
    }

    private static HorizontalFrame destination() {
        return new HorizontalFrame(DESTINATION_WORLD_ID, new Point3(100.5, 70, 100.5), 2, 2);
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    private static BlockData clonedBlockData() {
        BlockData original = mock(BlockData.class);
        BlockData copy = mock(BlockData.class);
        when(original.clone()).thenReturn(copy);
        return copy;
    }

    private void stubSample(int x, int y, int z, BlockData copy) {
        Block block = mock(Block.class);
        BlockData original = mock(BlockData.class);
        when(original.clone()).thenReturn(copy);
        when(block.getBlockData()).thenReturn(original);
        when(destinationWorld.getBlockAt(x, y, z)).thenReturn(block);
    }

    private static void verifyNoChunkLoadCalls(World world) {
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }
}
