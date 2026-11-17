package Vystorm.vystorm_core.packet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.packet.internal.BiomePackets;
import Vystorm.vystorm_core.visual.BlockPosition;
import org.bukkit.NamespacedKey;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class ClientBiomesTest {
    @Test
    void projectionComposesOverAnotherOwnersSnowAndRestoresItWithoutLoadingChunks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin winter = fixture.owner(), portals = fixture.owner();
            Player player = fixture.player(); World world = player.getWorld();
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(player.isChunkSent(anyLong())).thenReturn(true);
            when(world.isChunkLoaded(0, 0)).thenReturn(true);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAt(0, 0)).thenReturn(chunk);
            var cells = Map.of(new BlockPosition(0, 64, 0), NamespacedKey.minecraft("jungle"));
            assertTrue(ClientBiomes.snow(winter, player, Set.of()));
            assertTrue(ClientBiomes.project(portals, player, cells));
            assertFalse(ClientBiomes.project(winter, player, cells));
            ProjectedBiomes.tick();
            verify(fixture.adapters.constructed().get(0)).send(eq(player), eq(List.of(chunk)), eq(true), eq(cells));

            ClientBiomes.clearProjection(portals, player);
            verify(fixture.adapters.constructed().get(0)).send(eq(player), eq(List.of(chunk)), eq(true), eq(Map.of()));
            verify(world, never()).getChunkAt(1, 0);
            assertTrue(ProjectedBiomes.cells(player).isEmpty());
        }
    }

    @Test
    void invalidProjectionKeepsPreviousCellsAndWorldChangesNeverRestoreIntoNewWorld() {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner(); Player player = fixture.player();
            when(player.getWorld().getMinHeight()).thenReturn(-64); when(player.getWorld().getMaxHeight()).thenReturn(320);
            var cells = Map.of(new BlockPosition(-4, -64, -4), NamespacedKey.minecraft("plains"));
            assertTrue(ClientBiomes.project(owner, player, cells));
            assertThrows(IllegalArgumentException.class, () -> ClientBiomes.project(owner, player,
                    Map.of(new BlockPosition(-1, -64, -4), NamespacedKey.minecraft("plains"))));
            org.junit.jupiter.api.Assertions.assertEquals(cells, ProjectedBiomes.cells(player));
            World other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(other);
            ClientBiomes.clearProjection(owner, player);
            verify(other, never()).getChunkAt(anyInt(), anyInt());
            assertTrue(ProjectedBiomes.cells(player).isEmpty());
        }
    }

    @Test
    void unchangedProjectionSendsNothingUntilItsClientChunkIsReplaced() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner(); Player player = fixture.player(); World world = player.getWorld();
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(0, 0)).thenReturn(true); when(player.isChunkSent(0L)).thenReturn(true);
            Chunk chunk = mock(Chunk.class); when(world.getChunkAt(0, 0)).thenReturn(chunk);
            var cells = Map.of(new BlockPosition(0, 64, 0), NamespacedKey.minecraft("plains"));
            assertTrue(ClientBiomes.project(owner, player, cells)); ProjectedBiomes.tick();
            var adapter = fixture.adapters.constructed().get(0);
            org.mockito.Mockito.clearInvocations(adapter);
            assertTrue(ClientBiomes.project(owner, player, cells)); ProjectedBiomes.tick();
            org.mockito.Mockito.verifyNoInteractions(adapter);
            ProjectedBiomes.loaded(player, world.getUID(), 0L); ProjectedBiomes.tick();
            verify(adapter).send(eq(player), eq(List.of(chunk)), eq(false), eq(cells));
        }
    }

    @Test
    void retainsSameOwnerViewAndRejectsAnotherOwner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner();
            Plugin other = fixture.owner();
            Player player = fixture.player();
            Set<String> excluded = Set.of("DESERT");

            assertTrue(ClientBiomes.snow(owner, player, excluded));
            assertTrue(ClientBiomes.snow(owner, player, excluded));
            assertFalse(ClientBiomes.snow(other, player, excluded));
            ClientBiomes.release(other);

            assertTrue(fixture.adapters.constructed().size() == 1);
            verify(fixture.adapters.constructed().get(0), never()).send(any(), anyList(), eq(false));
            ClientBiomes.clear(owner, player);
            verify(fixture.adapters.constructed().get(0)).send(eq(player), anyList(), eq(false));
        }
    }

    @Test
    void exclusionChangeRestoresOldViewBeforeCreatingReplacement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner();
            Player player = fixture.player();

            assertTrue(ClientBiomes.snow(owner, player, Set.of("DESERT")));
            assertTrue(ClientBiomes.snow(owner, player, Set.of("BADLANDS")));

            assertTrue(fixture.adapters.constructed().size() == 2);
            verify(fixture.adapters.constructed().get(0)).send(eq(player), anyList(), eq(false));
            verify(fixture.adapters.constructed().get(1), never()).send(any(), anyList(), eq(false));
            ClientBiomes.clear(owner, player);
            verify(fixture.adapters.constructed().get(1)).send(eq(player), anyList(), eq(false));
        }
    }

    @Test
    void releaseClearsOnlyOwnersViewsAndShutdownCancelsSharedTask() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner();
            Plugin other = fixture.owner();
            Player ownedPlayer = fixture.player();
            Player otherPlayer = fixture.player();
            assertTrue(ClientBiomes.snow(owner, ownedPlayer, Set.of()));
            assertTrue(ClientBiomes.snow(other, otherPlayer, Set.of()));

            ClientBiomes.release(owner);
            verify(fixture.adapters.constructed().get(0)).send(eq(ownedPlayer), anyList(), eq(false));
            verify(fixture.adapters.constructed().get(1), never()).send(any(), anyList(), eq(false));

            PluginManager pluginManager = mock(PluginManager.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            BukkitTask task = mock(BukkitTask.class);
            when(scheduler.runTaskTimer(any(), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
            fixture.bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            fixture.bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ClientBiomes.initialize(owner);
            ClientBiomes.shutdown();

            verify(fixture.adapters.constructed().get(1)).send(eq(otherPlayer), anyList(), eq(false));
            verify(task).cancel();
        }
    }

    @Test
    void unsupportedRuntimeReturnsFalseWithoutOpeningView() {
        try (Fixture fixture = new Fixture()) {
            fixture.bukkit.when(Bukkit::getMinecraftVersion).thenReturn("26.1");

            assertFalse(ClientBiomes.snow(fixture.owner(), fixture.player(), Set.of()));
            assertTrue(fixture.adapters.constructed().isEmpty());
        }
    }

    @Test
    void givenVerifiedVersionsWhenCheckingSupportThenOnly262And263AreSupported() {
        try (Fixture fixture = new Fixture()) {
            for (String version : List.of("26.2", "26.3")) {
                fixture.bukkit.when(Bukkit::getMinecraftVersion).thenReturn(version);
                assertTrue(ClientBiomes.supported(), version);
            }
            fixture.features.when(BiomePackets::available).thenReturn(false);
            assertFalse(ClientBiomes.supported(), "A version match alone must not enable an unavailable packet adapter");
            fixture.features.when(BiomePackets::available).thenReturn(true);
            for (String version : List.of("26.1", "26.4", "1.21.8", "1.20.6")) {
                fixture.bukkit.when(Bukkit::getMinecraftVersion).thenReturn(version);
                assertFalse(ClientBiomes.supported(), version);
            }
        }
    }

    @Test
    void givenMinecraft263WhenOpeningSnowViewThenViewOpens() {
        try (Fixture fixture = new Fixture()) {
            fixture.bukkit.when(Bukkit::getMinecraftVersion).thenReturn("26.3");

            assertTrue(ClientBiomes.snow(fixture.owner(), fixture.player(), Set.of()));
            assertTrue(fixture.adapters.constructed().size() == 1);
        }
    }

    @Test
    void rejectsCallsOffTheServerThread() {
        try (Fixture fixture = new Fixture()) {
            fixture.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);

            assertThrows(IllegalStateException.class,
                    () -> ClientBiomes.snow(fixture.owner(), fixture.player(), Set.of()));
            assertTrue(fixture.adapters.constructed().isEmpty());
        }
    }

    @Test
    void rotatingBudgetEventuallySendsToEveryViewer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Plugin owner = fixture.owner();
            World world = mock(World.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            Chunk chunk = mock(Chunk.class);
            when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
            Set<Long> chunkKeys = java.util.stream.LongStream.range(0,64).boxed().collect(java.util.stream.Collectors.toSet());
            List<Player> viewers = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                Player player = mock(Player.class);
                when(player.isOnline()).thenReturn(true);
                when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                when(player.getWorld()).thenReturn(world);
                when(player.getSentChunks()).thenReturn(Set.of());
                when(player.getSentChunkKeys()).thenReturn(chunkKeys);
                when(player.isChunkSent(anyLong())).thenReturn(true);
                viewers.add(player);
            }

            for (Player player : viewers) assertTrue(ClientBiomes.snow(owner, player, Set.of()));

            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            PluginManager pluginManager = mock(PluginManager.class);
            BukkitTask task = mock(BukkitTask.class);
            AtomicReference<Runnable> tick = new AtomicReference<>();
            when(scheduler.runTaskTimer(eq(owner), any(Runnable.class), eq(1L), eq(1L))).thenAnswer(invocation -> {
                tick.set(invocation.getArgument(1));
                return task;
            });
            fixture.bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            fixture.bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            ClientBiomes.initialize(owner);

            tick.get().run();
            tick.get().run();

            assertTrue(fixture.adapters.constructed().size() == viewers.size());
            for (int i = 0; i < viewers.size(); i++)
                verify(fixture.adapters.constructed().get(i), atLeastOnce()).send(eq(viewers.get(i)), anyList(), eq(true));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<BiomePackets> features = mockStatic(BiomePackets.class);
        final MockedConstruction<BiomePackets> adapters = mockConstruction(BiomePackets.class);

        Fixture() {
            features.when(BiomePackets::available).thenReturn(true);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            bukkit.when(Bukkit::getMinecraftVersion).thenReturn("26.2");
        }

        Plugin owner() {
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getName()).thenReturn("test-plugin");
            return plugin;
        }

        Player player() {
            Player player = mock(Player.class);
            World world = mock(World.class);
            when(player.isOnline()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(world);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(player.getSentChunks()).thenReturn(Set.of());
            return player;
        }

        @Override public void close() {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ClientBiomes.shutdown();
            adapters.close();
            features.close();
            bukkit.close();
        }
    }

    @Test
    void givenPlayerBetweenWorlds_whenQueryingSentChunks_thenNothingIsSentAndNothingThrows() {
        Player player = mock(Player.class);
        when(player.getSentChunkKeys()).thenThrow(new NullPointerException("no chunk loader"));
        when(player.isChunkSent(anyLong())).thenThrow(new NullPointerException("no chunk loader"));
        assertTrue(ClientBiomes.sentKeys(player).isEmpty());
        assertFalse(ClientBiomes.sent(player, 5L));
    }
}
