package Vystorm.vystorm_core.packet.internal.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.packet.ClientBlockInteract;
import Vystorm.vystorm_core.packet.ClientBlockSet;
import Vystorm.vystorm_core.packet.ClientBlocksStats;
import Vystorm.vystorm_core.visual.BlockPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

class ClientBlocksRuntimeTest {
    private static final UUID WORLD_ID = UUID.fromString("b9a890bd-aeef-4bd0-b8b5-78455827019b");
    private MockedStatic<Bukkit> bukkit;
    private World world;
    private Plugin owner;
    private Player alice;
    private ClientBlocksRuntime rt;

    @BeforeEach
    void givenServerThreadWorldAndPlayer() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Portals");
        alice = player("Alice", new Location(world, 0, 64, 0));
        when(world.getPlayers()).thenReturn(List.of(alice));
        rt = ClientBlocksRuntime.get();
    }

    @AfterEach
    void stop() {
        ClientBlocksRuntime.shutdownInstance();
        bukkit.close();
    }

    private Player player(String name, Location at) {
        Player p = mock(Player.class);
        when(p.getName()).thenReturn(name);
        when(p.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(p.isOnline()).thenReturn(true);
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(at);
        when(p.getEyeLocation()).thenReturn(at);
        return p;
    }

    private static BlockData data(String name) {
        BlockData d = mock(BlockData.class);
        when(d.clone()).thenReturn(d);
        when(d.getMaterial()).thenReturn(Material.AIR);
        when(d.toString()).thenReturn(name);
        return d;
    }

    private Block realBlock(int x, int y, int z, BlockData data) {
        Block b = mock(Block.class);
        when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(y);
        when(b.getZ()).thenReturn(z);
        when(b.getWorld()).thenReturn(world);
        when(b.getBlockData()).thenReturn(data);
        when(world.getBlockAt(x, y, z)).thenReturn(b);
        return b;
    }

    @Test
    void givenSetOfTwoSectionsWhenTickingThenOneMultiBlockChangePerSection() {
        ClientBlockSet set = rt.open(owner, alice, "preview", 0);
        BlockData glass = data("glass");
        Map<BlockPosition, BlockData> cells = new HashMap<>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) cells.put(new BlockPosition(x, 64, z), glass);
        for (int x = 0; x < 16; x++) cells.put(new BlockPosition(x, 80, 0), glass);
        set.setAll(cells);
        rt.tick();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<Location, BlockData>> sent = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(alice, times(2)).sendMultiBlockChange(sent.capture());
        assertEquals(List.of(256, 16), sent.getAllValues().stream().map(Map::size).toList());
        verify(alice, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        assertEquals(0, set.pending());
        assertEquals(glass, rt.shown(alice, 3, 64, 3).orElseThrow());
    }

    @Test
    void givenTwoPluginsWhenTheirSetsOverlapThenPriorityDecidesAndCloseFallsThrough() {
        Plugin other = mock(Plugin.class);
        when(other.isEnabled()).thenReturn(true);
        when(other.getName()).thenReturn("Screens");
        BlockData glass = data("glass"), air = data("air");
        ClientBlockSet preview = rt.open(owner, alice, "preview", 0).set(new BlockPosition(1, 64, 1), glass);
        ClientBlockSet hide = rt.open(other, alice, "hide", 10).set(new BlockPosition(1, 64, 1), air);
        rt.tick();
        assertSame(air, rt.shown(alice, 1, 64, 1).orElseThrow());
        assertEquals(List.of(hide, preview), rt.sets(alice));
        clearInvocations(alice);
        hide.close();
        rt.tick();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(glass));
        assertSame(glass, rt.shown(alice, 1, 64, 1).orElseThrow());
        rt.release(owner);
        BlockData real = data("stone");
        realBlock(1, 64, 1, real);
        clearInvocations(alice);
        rt.tick();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(real));
        assertTrue(preview.isClosed());
        assertTrue(rt.shown(alice, 1, 64, 1).isEmpty());
    }

    @Test
    void givenRealBreakInsideTheSetWhenTwoTicksPassThenTheCellIsSentAgain() {
        BlockData glass = data("glass");
        rt.open(owner, alice, "preview", 0).set(new BlockPosition(2, 64, 2), glass);
        rt.tick();
        clearInvocations(alice);
        assertTrue(rt.watched(world, 2, 64, 2));
        rt.changed(world, 2, 64, 2, null);
        rt.tick();
        verify(alice, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        rt.tick();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(glass));
        assertEquals(1, rt.stats().repairs());
    }

    @Test
    void givenInteractListenerWhenThePlayerClicksThenItDecidesAboutTheRepair() {
        BlockData glass = data("glass");
        List<ClientBlockInteract> seen = new ArrayList<>();
        ClientBlockSet set = rt.open(owner, alice, "marks", 0).set(new BlockPosition(3, 64, 3), glass).set(new BlockPosition(4, 64, 3), glass);
        set.onInteract(click -> {
            seen.add(click);
            if (click.cell().x() == 4) click.repair();
        });
        rt.tick();
        Block b3 = realBlock(3, 64, 3, data("air")), b4 = realBlock(4, 64, 3, data("air"));
        assertTrue(rt.interact(alice, b3, ClientBlockInteract.Action.RIGHT_CLICK));
        assertTrue(rt.interact(alice, b4, ClientBlockInteract.Action.BREAK));
        assertEquals(2, seen.size());
        assertSame(set, seen.get(0).set());
        assertEquals(ClientBlockInteract.Action.BREAK, seen.get(1).action());
        assertTrue(rt.shown(alice, 3, 64, 3).isEmpty(), "the listener took over: the real block stays");
        clearInvocations(alice);
        rt.tick();
        rt.tick();
        verify(alice, times(1)).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(glass));
        assertFalse(rt.interact(alice, realBlock(9, 64, 9, data("air")), ClientBlockInteract.Action.LEFT_CLICK));
    }

    @Test
    void givenSharedSetWhenAPlayerComesIntoRangeThenHeGetsItAndLosesItOutside() {
        BlockData lamp = data("lamp");
        ClientBlockSet shared = rt.openShared(owner, new Location(world, 100, 64, 100), 16, "lamps", 0);
        shared.set(new BlockPosition(100, 70, 100), lamp);
        rt.tick();
        verify(alice, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        when(alice.getLocation()).thenReturn(new Location(world, 95, 64, 100));
        for (int i = 0; i < 10; i++) rt.tick();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(lamp));
        BlockData real = data("air");
        realBlock(100, 70, 100, real);
        when(alice.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        for (int i = 0; i < 10; i++) rt.tick();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(real));
        assertFalse(shared.isClosed());
        assertEquals(1, rt.stats().sets());
    }

    @Test
    void givenQuitWhenForgottenThenSetsCloseWithoutPackets() {
        ClientBlockSet set = rt.open(owner, alice, "preview", 0).set(new BlockPosition(1, 64, 1), data("glass"));
        rt.tick();
        clearInvocations(alice);
        rt.forget(alice);
        rt.tick();
        assertTrue(set.isClosed());
        verify(alice, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        verify(alice, never()).sendMultiBlockChange(anyMap());
        set.set(new BlockPosition(2, 64, 2), data("glass"));
        assertEquals(0, set.size(), "changes to a closed set are ignored");
    }

    @Test
    void givenCollisionDeclaredWhenStatsAreReadThenThePluginIsListed() {
        rt.open(owner, alice, "floor", 0).collision(true).set(new BlockPosition(1, 64, 1), data("glass"));
        ClientBlocksStats stats = rt.stats();
        assertEquals(1, stats.owners().size());
        assertEquals("Portals", stats.owners().get(0).plugin());
        assertEquals(1, stats.owners().get(0).collisionSets());
        assertEquals(1, stats.cells());
    }

    @Test
    void givenWrongThreadOrInactiveOwnerWhenOpeningThenRefused() {
        when(owner.isEnabled()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> rt.open(owner, alice, "x", 0));
        when(owner.isEnabled()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> rt.open(owner, alice, "", 0));
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> rt.open(owner, alice, "x", 0));
    }

    @Test
    void givenSetClosedJustBeforeShutdownWhenCoreStopsThenItsRestoresStillGoOut() {
        rt.open(owner, alice, "preview", 0).set(new BlockPosition(1, 64, 1), data("glass"));
        rt.tick();
        BlockData real = data("stone");
        realBlock(1, 64, 1, real);
        rt.release(owner); // plugin disabled first (reload): restore only queued for a tick that never comes
        clearInvocations(alice);
        ClientBlocksRuntime.shutdownInstance();
        verify(alice).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(real));
        rt = ClientBlocksRuntime.get();
    }

    @Test
    void givenListenerWithoutRepairWhenLeftClickedThenTheClientStillCountsAsShowingTheBlock() {
        BlockData glass = data("glass");
        ClientBlockSet set = rt.open(owner, alice, "marks", 0).set(new BlockPosition(3, 64, 3), glass);
        set.onInteract(click -> { });
        rt.tick();
        assertTrue(rt.interact(alice, realBlock(3, 64, 3, data("air")), ClientBlockInteract.Action.LEFT_CLICK));
        assertSame(glass, rt.shown(alice, 3, 64, 3).orElseThrow(), "no block update was sent by the server");
        set.close();
        clearInvocations(alice);
        rt.tick();
        verify(alice).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void givenChunkResentWhenReportedThenTheCellsOfThatChunkGoOutAgain() {
        BlockData glass = data("glass");
        rt.open(owner, alice, "preview", 0).set(new BlockPosition(1, 64, 1), glass).set(new BlockPosition(40, 64, 1), glass);
        rt.tick();
        clearInvocations(alice);
        rt.chunkUnloaded(alice, WORLD_ID, 0, 0);
        rt.chunkLoaded(alice, WORLD_ID, 0, 0);
        rt.tick();
        ArgumentCaptor<Location> at = ArgumentCaptor.forClass(Location.class);
        verify(alice, atLeastOnce()).sendBlockChange(at.capture(), any(BlockData.class));
        assertEquals(List.of(1), at.getAllValues().stream().map(Location::getBlockX).toList());
    }
}
