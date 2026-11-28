package Vystorm.vystorm_core.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class VisualsTest {
    private static final UUID WORLD_A_ID = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");
    private static final UUID WORLD_B_ID = UUID.fromString("8d3be06b-d899-4f65-b230-3e13b8fb1b5a");

    private MockedStatic<Bukkit> bukkit;
    private World worldA;
    private World worldB;
    private Plugin ownerA;
    private Plugin ownerB;
    private Player playerA;
    private Player playerB;

    @BeforeEach
    void givenServerThreadAndMockedWorlds() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        worldA = world(WORLD_A_ID);
        worldB = world(WORLD_B_ID);
        bukkit.when(() -> Bukkit.getWorld(WORLD_A_ID)).thenReturn(worldA);
        bukkit.when(() -> Bukkit.getWorld(WORLD_B_ID)).thenReturn(worldB);
        ownerA = owner("Owner A");
        ownerB = owner("Owner B");
        playerA = player("Player A", worldA);
        playerB = player("Player B", worldA);
    }

    @AfterEach
    void closeAllViewsOnServerThread() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        Visuals.shutdown();
        Vystorm.vystorm_core.packet.ClientBlocks.shutdown();
        bukkit.close();
    }

    @Test
    void givenNonPrimaryThreadWhenOpeningViewThenRejectsCall() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> Visuals.open(ownerA, playerA));
    }

    @Test
    void givenDisabledOwnerWhenOpeningViewThenRejectsCall() {
        when(ownerA.isEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> Visuals.open(ownerA, playerA));
    }

    @Test
    void givenActiveViewWhenReplacingWithSmallBudgetThenEventuallyConverges() {
        BlockView view = Visuals.open(ownerA, playerA);
        BlockData data = stableBlockData();
        Map<BlockPosition, BlockData> desired = new LinkedHashMap<>();
        desired.put(new BlockPosition(1, 64, 1), data);
        desired.put(new BlockPosition(2, 64, 1), data);
        desired.put(new BlockPosition(3, 64, 1), data);
        stubCurrentBlock(worldA, 1, 64, 1);
        stubCurrentBlock(worldA, 2, 64, 1);
        stubCurrentBlock(worldA, 3, 64, 1);

        assertEquals(2, view.replace(desired, 1));
        assertEquals(1, view.replace(desired, 1));
        assertEquals(0, view.replace(desired, 1));
        verify(playerA, org.mockito.Mockito.times(3)).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void givenMovingViewWhenPrioritizingThenVisibleChangesAndCleanupShareBudget() {
        BlockView view = Visuals.open(ownerA, playerA);
        BlockData data = stableBlockData();
        Map<BlockPosition, BlockData> old = new LinkedHashMap<>(), next = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            BlockPosition p = new BlockPosition(i, 64, 1);
            old.put(p, data); stubCurrentBlock(worldA, i, 64, 1);
            next.put(new BlockPosition(i, 64, 2), data); stubCurrentBlock(worldA, i, 64, 2);
        }
        view.replace(old, 8); clearInvocations(playerA);

        assertEquals(12, view.replacePrioritized(next, 4));
        // 0.72.0: one restore and three additions of one chunk section go out as one multi-block change, restore first.
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<Location, BlockData>> batch = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(playerA).sendMultiBlockChange(batch.capture());
        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        assertEquals(List.of(1, 2, 2, 2), batch.getValue().keySet().stream().map(Location::getBlockZ).toList());
        for (int i = 0; i < 3; i++) view.replacePrioritized(next, 4);
        assertEquals(0, view.replacePrioritized(next, 4));
        assertFalse(view.covers(new BlockPosition(7, 64, 1)));
        assertTrue(view.covers(new BlockPosition(7, 64, 2)));
    }

    @Test
    void givenViewWhenClosingThenRestoresCurrentRealBlockDataAndCloseIsIdempotent() {
        BlockView view = Visuals.open(ownerA, playerA);
        BlockData overlay = stableBlockData();
        BlockData currentReal = mock(BlockData.class);
        Block block = mock(Block.class);
        when(worldA.getBlockAt(4, 70, 9)).thenReturn(block);
        when(block.getBlockData()).thenReturn(currentReal);
        view.replace(Map.of(new BlockPosition(4, 70, 9), overlay), 1);
        clearInvocations(playerA);

        view.close();
        view.close();

        ArgumentCaptor<BlockData> restored = ArgumentCaptor.forClass(BlockData.class);
        verify(playerA).sendBlockChange(any(Location.class), restored.capture());
        assertEquals(currentReal, restored.getValue());
        verify(worldA, org.mockito.Mockito.times(1)).getBlockAt(4, 70, 9);
        assertTrue(view.isClosed());
    }

    @Test
    void givenDesiredDataWhenReplacingThenSendsADefensiveClone() {
        BlockView view = Visuals.open(ownerA, playerA);
        BlockData callerData = mock(BlockData.class);
        BlockData privateCopy = mock(BlockData.class);
        when(callerData.clone()).thenReturn(privateCopy);
        stubCurrentBlock(worldA, 7, 80, -3);
        Map<BlockPosition, BlockData> desired = new LinkedHashMap<>();
        desired.put(new BlockPosition(7, 80, -3), callerData);

        view.replace(desired, 4);
        desired.clear();

        ArgumentCaptor<BlockData> sent = ArgumentCaptor.forClass(BlockData.class);
        verify(playerA).sendBlockChange(any(Location.class), sent.capture());
        assertEquals(privateCopy, sent.getValue());
        assertNotSame(callerData, sent.getValue());
    }

    @Test
    void givenTwoOwnersWhenReleasingOneThenOtherViewRemainsActive() {
        BlockView first = Visuals.open(ownerA, playerA);
        BlockView second = Visuals.open(ownerB, playerB);

        Visuals.release(ownerA);

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
    }

    @Test
    void givenViewerWithActiveViewWhenTheSamePluginOpensSecondThenRejectsConflict() {
        Visuals.open(ownerA, playerA);
        assertThrows(IllegalStateException.class, () -> Visuals.open(ownerA, playerA));
    }

    /** 0.72.0: views of different plugins no longer exclude each other; on a shared cell the newer view is shown. */
    @Test
    void givenViewOfAnotherPluginWhenOpeningThenBothShowAndTheNewerWinsSharedCells() {
        BlockView first = Visuals.open(ownerA, playerA);
        BlockView second = Visuals.open(ownerB, playerA);
        BlockData a = stableBlockData(), b = stableBlockData();
        BlockPosition shared = new BlockPosition(1, 64, 1);
        first.replace(Map.of(shared, a), 4);
        clearInvocations(playerA);
        second.replace(Map.of(shared, b), 4);
        verify(playerA).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(b));
        assertTrue(first.covers(shared));
        assertTrue(second.covers(shared));
        clearInvocations(playerA);
        second.close();
        verify(playerA).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(a));
        assertFalse(first.isClosed());
    }

    @Test
    void givenViewerSwitchingWorldWhenViewClosesThenNeverRestoresIntoNewWorld() {
        BlockView view = Visuals.open(ownerA, playerA);
        view.replace(Map.of(new BlockPosition(4, 70, 9), stableBlockData()), 1);
        clearInvocations(playerA, worldA, worldB);
        when(playerA.getWorld()).thenReturn(worldB);

        view.close();

        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        verify(worldA, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        assertTrue(view.isClosed());
    }

    @Test
    void givenUnloadedChunkWhenReplacingThenDoesNotReadOrLoadItsBlocks() {
        BlockView view = Visuals.open(ownerA, playerA);
        when(worldA.isChunkLoaded(2, 3)).thenReturn(false);

        int pending = view.replace(Map.of(new BlockPosition(33, 70, 49), stableBlockData()), 1);

        assertEquals(0, pending);
        verify(worldA, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(worldA, never()).getChunkAt(anyInt(), anyInt());
        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void givenProjectionIntoUnloadedChunksWhenSamplingThenSkipsBlockReads() {
        when(worldA.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        ViewFrame entrance = new ViewFrame(WORLD_A_ID, new Point3(0, 64, 0), 0, 2, 4);
        ViewFrame destination = new ViewFrame(WORLD_B_ID, new Point3(100, 64, 0), 2, 2, 4);

        Map<BlockPosition, BlockData> projected = Visuals.projection(
                entrance, destination, new Point3(0, 66, 4), 2, 3, 20);

        assertTrue(projected.isEmpty());
        verify(worldA, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(worldB, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(worldA, never()).getChunkAt(anyInt(), anyInt());
        verify(worldB, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void givenProjectionEyeBehindApertureWhenSamplingThenReturnsEmptyWithoutBlockReads() {
        ViewFrame entrance = new ViewFrame(WORLD_A_ID, new Point3(0, 64, 0), 0, 2, 4);
        ViewFrame destination = new ViewFrame(WORLD_B_ID, new Point3(100, 64, 0), 2, 2, 4);

        Map<BlockPosition, BlockData> projected = Visuals.projection(
                entrance, destination, new Point3(0, 66, -4), 2, 3, 20);

        assertTrue(projected.isEmpty());
        verify(worldA, never()).isChunkLoaded(anyInt(), anyInt());
        verify(worldB, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    void givenChunkUpdateWhenReapplyingDesiredViewThenResendsOnlyAffectedViewerChunkCells() {
        BlockView firstView = Visuals.open(ownerA, playerA);
        BlockView secondView = Visuals.open(ownerA, playerB);
        BlockData overlay = stableBlockData();
        BlockPosition inChunkZero = new BlockPosition(1, 64, 1);
        BlockPosition inChunkTwo = new BlockPosition(33, 64, 1);
        Map<BlockPosition, BlockData> desired = Map.of(inChunkZero, overlay, inChunkTwo, overlay);
        stubCurrentBlock(worldA, inChunkZero.x(), inChunkZero.y(), inChunkZero.z());
        stubCurrentBlock(worldA, inChunkTwo.x(), inChunkTwo.y(), inChunkTwo.z());
        firstView.replace(desired, 2);
        secondView.replace(desired, 2);
        clearInvocations(playerA, playerB, worldA);

        assertEquals(0, firstView.replace(desired, 2), "unchanged desired data sends no packets");
        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));

        // 0.72.0: the client block runtime sends the cells of a resent chunk again by itself, without a new replace.
        clearInvocations(playerA, playerB, worldA);
        Visuals.chunkUpdated(playerB, WORLD_A_ID, 0, 0);
        Visuals.chunkUpdated(playerA, WORLD_B_ID, 0, 0);
        assertFalse(firstView.isClosed());
        assertFalse(secondView.isClosed());
        assertTrue(secondView.covers(inChunkZero), "a chunk reload keeps the view's cells");
        Vystorm.vystorm_core.packet.internal.blocks.ClientBlocksRuntime.get().tick();

        ArgumentCaptor<Location> sentLocations = ArgumentCaptor.forClass(Location.class);
        verify(playerB).sendBlockChange(sentLocations.capture(), any(BlockData.class));
        assertEquals(1, sentLocations.getValue().getBlockX());
        assertEquals(1, sentLocations.getValue().getBlockZ());
        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        verify(playerA, never()).sendMultiBlockChange(org.mockito.ArgumentMatchers.anyMap());
        clearInvocations(playerB);
        assertEquals(0, secondView.replace(desired, 2), "nothing left for the caller to do");
        verify(playerB, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        verify(worldA, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(worldA, never()).getChunkAt(anyInt(), anyInt());
        verify(worldA, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void givenViewerQuitWhenTheRuntimeForgetsHimThenTheViewIsClosedWithoutPackets() {
        BlockView view = Visuals.open(ownerA, playerA);
        view.replace(Map.of(new BlockPosition(4, 70, 9), stableBlockData()), 1);
        clearInvocations(playerA);
        Vystorm.vystorm_core.packet.internal.blocks.ClientBlocksRuntime.get().forget(playerA);
        assertTrue(view.isClosed());
        view.close();
        verify(playerA, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        assertEquals(0, Vystorm.vystorm_core.packet.ClientBlocks.stats().sets());
        BlockView again = Visuals.open(ownerA, playerA);
        assertFalse(again.isClosed(), "a new view can be opened after the old one was dropped");
    }

    private static World world(UUID id) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(id);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    private static Plugin owner(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    private static Player player(String name, World world) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private static BlockData stableBlockData() {
        BlockData data = mock(BlockData.class);
        when(data.clone()).thenReturn(data);
        return data;
    }

    private static void stubCurrentBlock(World world, int x, int y, int z) {
        Block block = mock(Block.class);
        when(world.getBlockAt(x, y, z)).thenReturn(block);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
    }
}
