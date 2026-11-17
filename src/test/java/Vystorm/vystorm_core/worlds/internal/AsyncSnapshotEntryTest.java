package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.VisitTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.worlds.*;
import io.papermc.paper.registry.RegistryAccess;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AsyncSnapshotEntryTest {
    @TempDir Path dir;
    Env env; Worlds worlds; FakePlayer player; VisitSnapshotStore store; VisitEngine engine;
    Writes writes; Location arrival; UUID id;
    @BeforeEach void setup() {
        World main = world("world"), target = world("visit");
        env = new Env(main);worlds = new Worlds();
        worlds.add(new ManagedWorldImpl(plugin("Owner"), WorldSpec.builder("p:visit", "visit", mock(ChunkGenerator.class)).build(), target, dir.resolve("world"), false));
        player = new FakePlayer(env, "Async", new Location(main, 1, 64, 1));
        player.contents[0] = item("diamond");
        id = player.player.getUniqueId();arrival = new Location(target, 0, 80, 0);
        store = new VisitSnapshotStore(dir.resolve("visits"));writes = new Writes(false);
        engine = new VisitEngine(env, worlds, store, writes);
    }
    void enter() { assertEquals(VisitResult.OK, engine.enter(player.player, "p:visit", arrival)); }
    void finish() { writes.runAll();env.runTicks();writes.runAll();env.runTicks(); }
    void assertAborted() {
        finish();
        assertFalse(engine.inVisit(id));assertFalse(env.hasMarker(player.player));assertFalse(store.exists(id));
        assertNotNull(player.contents[0]);assertEquals(GameMode.SURVIVAL, player.mode);
        assertFalse(env.events.stream().anyMatch(e -> e.startsWith("teleport")));
    }
    @Test void givenQueuedWrite_whenDoubleEnter_thenAlreadyAndNoMutationBeforeDurability() {
        enter();assertFalse(store.exists(id));assertFalse(env.hasMarker(player.player));assertNotNull(player.contents[0]);
        assertEquals(VisitResult.ALREADY, engine.enter(player.player, "p:visit", arrival));
        finish();assertTrue(store.exists(id));assertTrue(env.hasMarker(player.player));assertTrue(engine.inVisit(id));
    }
    @Test void givenQueuedWrite_whenQuitAndRejoin_thenLateCompletionCannotAdmitOrRestoreStaleState() {
        enter();engine.exit(player.player, ExitReason.QUIT);player.online = false;player.online = true;
        assertEquals(RecoveryDecision.PENDING, engine.recover(player.player));assertAborted();
        enter();finish();assertTrue(engine.inVisit(id));assertTrue(store.exists(id));
    }
    @Test void givenQueuedWrite_whenDead_thenAbort() { enter();when(player.player.isDead()).thenReturn(true);assertAborted(); }
    @Test void givenQueuedWrite_whenDeathAndRespawn_thenAbort() { enter();engine.invalidateEntry(player.player);assertAborted(); }
    @Test void givenQueuedWrite_whenTeleported_thenAbort() { enter();player.moveTo(arrival);assertAborted(); }
    @Test void givenQueuedWrite_whenMoved_thenAbort() { enter();player.location.add(0.1,0,0);assertAborted(); }
    @Test void givenFiniteEffect_whenHealthRegeneratesAndFoodDrainsDuringWrite_thenAdmitWithSubmitTimeScalars() throws Exception {
        PotionEffectType speed;
        // Paper's effect constants resolve registries during class initialization, even for a mock.
        try (var registries = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class, call -> mock(Registry.class, lookup ->
                    lookup.getMethod().getName().equals("getOrThrow") ? mock(PotionEffectType.class) : RETURNS_DEFAULTS.answer(lookup)));
            registries.when(RegistryAccess::registryAccess).thenReturn(access);
            speed = mock(PotionEffectType.class);
        }
        when(speed.getKey()).thenReturn(NamespacedKey.minecraft("speed"));
        PotionEffect effect = mock(PotionEffect.class);
        when(effect.getType()).thenReturn(speed);
        when(effect.getDuration()).thenReturn(1200);
        when(effect.getAmplifier()).thenReturn(1);
        when(player.player.getActivePotionEffects()).thenReturn(List.of(effect));
        when(player.player.getHealth()).thenReturn(12.0);
        when(player.player.getFoodLevel()).thenReturn(20);
        when(player.player.getSaturation()).thenReturn(5f);
        when(player.player.getExhaustion()).thenReturn(1f);
        when(player.player.getRemainingAir()).thenReturn(300);
        when(player.player.getFireTicks()).thenReturn(60);
        when(player.player.getFreezeTicks()).thenReturn(80);
        enter();
        env.now += 2000;
        when(effect.getDuration()).thenReturn(1160);
        when(player.player.getHealth()).thenReturn(13.0);
        when(player.player.getFoodLevel()).thenReturn(19);
        when(player.player.getSaturation()).thenReturn(4f);
        when(player.player.getExhaustion()).thenReturn(2f);
        when(player.player.getRemainingAir()).thenReturn(260);
        when(player.player.getFireTicks()).thenReturn(20);
        when(player.player.getFreezeTicks()).thenReturn(40);
        finish();
        assertTrue(engine.inVisit(id));
        assertTrue(env.hasMarker(player.player));
        VisitSnapshot saved = store.load(id).orElseThrow();
        assertEquals(1200, saved.effects().getFirst().duration());
        assertEquals(12.0, saved.health());assertEquals(20, saved.food());
        assertEquals(5f, saved.saturation());assertEquals(1f, saved.exhaustion());
        assertEquals(300, saved.air());assertEquals(60, saved.fireTicks());assertEquals(80, saved.freezeTicks());
    }
    @ParameterizedTest @ValueSource(ints = {0, 35, 36, 37, 38, 39, 40})
    void givenQueuedWrite_whenInventoryArmorOrOffhandChanges_thenKeepNewItemAndAbort(int slot) {
        enter();var changed = item("changed");player.contents[slot] = changed;
        assertAborted();assertSame(changed, player.contents[slot]);
    }
    @Test void givenQueuedWrite_whenItemRemoved_thenAbortWithoutRestoringIt() {
        player.contents[2] = item("spent");enter();player.contents[2] = null;
        assertAborted();assertNull(player.contents[2]);
    }
    @ParameterizedTest @ValueSource(strings = {"total", "level", "progress"})
    void givenQueuedWrite_whenXpChanges_thenKeepNewXpAndAbort(String component) {
        when(player.player.getTotalExperience()).thenReturn(100);
        when(player.player.getLevel()).thenReturn(7);
        when(player.player.getExp()).thenReturn(0.5f);
        enter();
        switch (component) {
            case "total" -> when(player.player.getTotalExperience()).thenReturn(101);
            case "level" -> when(player.player.getLevel()).thenReturn(8);
            case "progress" -> when(player.player.getExp()).thenReturn(0.6f);
        }
        assertAborted();
        verify(player.player, never()).setTotalExperience(anyInt());
        verify(player.player, never()).setLevel(anyInt());
        verify(player.player, never()).setExp(anyFloat());
    }
    @Test void givenQueuedWrite_whenGameModeChanges_thenKeepNewModeAndAbort() {
        enter();player.mode = GameMode.CREATIVE;finish();
        assertFalse(engine.inVisit(id));assertFalse(env.hasMarker(player.player));assertFalse(store.exists(id));
        assertEquals(GameMode.CREATIVE, player.mode);assertNotNull(player.contents[0]);
        assertFalse(env.events.stream().anyMatch(e -> e.startsWith("teleport")));
    }
    @Test void givenQueuedWrite_whenCraftingGridChanges_thenKeepItemsAndAbort() {
        CraftingInventory crafting = mock(CraftingInventory.class);
        InventoryView view = mock(InventoryView.class);
        when(player.player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(crafting);
        when(crafting.getMatrix()).thenReturn(new ItemStack[4]);
        enter();
        ItemStack[] matrix = {item("ingredient"), null, null, null};
        when(crafting.getMatrix()).thenReturn(matrix);
        assertAborted();assertSame(matrix, crafting.getMatrix());
    }
    @Test void givenQueuedWrite_whenInventoryChanged_thenKeepNewStateAndAbort() { enter();var gift = item("gift");player.contents[2] = gift;assertAborted();assertSame(gift,player.contents[2]); }
    @Test void givenQueuedWrite_whenCursorChanged_thenKeepCursorAndAbort() { enter();var cursor = item("cursor");player.cursor = cursor;assertAborted();assertSame(cursor,player.cursor); }
    @Test void givenQueuedWrite_whenTimeout_thenAbortAndCleanLateWrite() { enter();env.now += 2500;env.deadlines.remove().run();assertAborted(); }
    @Test void givenQueuedWrite_whenCompletionExceedsDeadlineBeforeTimer_thenAbort() { enter();env.now += 2501;assertAborted(); }
    @Test void givenDurableFile_whenCancelledBeforeMainCompletion_thenCleanupOnWriterBeforeAnotherAdmission() {
        enter();
        // Deliver the completed writer to main later, as Bukkit does.
        env.primary = false;writes.tasks.remove().run();env.primary = true;
        assertTrue(store.exists(id));engine.exit(player.player, ExitReason.QUIT);
        assertTrue(store.exists(id), "deletion must also run on the writer");
        assertEquals(VisitResult.ALREADY,engine.enter(player.player,"p:visit",arrival));assertAborted();
        enter();finish();assertTrue(store.exists(id));assertTrue(engine.inVisit(id));
    }
    @Test void givenQueuedWrite_whenShutdown_thenNoAdmissionAndNoWaitForWriter() { enter();engine.shutdown();assertTrue(writes.stopped);assertAborted(); }
    @Test void givenQueuedWrite_whenWorldReleased_thenAbort() { enter();engine.endAll("p:visit",ExitReason.REBUILD);assertAborted(); }
    @Test void givenQueuedWrite_whenAdminRestore_thenInProgress() { enter();assertEquals(RestoreOutcome.IN_PROGRESS,engine.restoreOutcome(player.player));finish();assertTrue(engine.inVisit(id)); }
    @Test void givenWriteFails_whenCompleted_thenNoAdmissionAndTemporaryFileRemoved() throws Exception {
        Files.writeString(dir.resolve("visits"),"not a directory");enter();assertAborted();
    }
    @Test void givenRealBlockedWriter_whenShutdown_thenServerThreadReturnsWithoutWaitingOrMutating() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        store = new VisitSnapshotStore(dir.resolve("visits"), env::log, path -> {
            reached.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        });
        engine = new VisitEngine(env, worlds, store, worker);
        try {
            enter();assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(player.contents[0]);assertFalse(env.hasMarker(player.player));
            assertTimeout(java.time.Duration.ofMillis(250), engine::shutdown);
        } finally { release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)); }
        assertFalse(engine.inVisit(id));assertNotNull(player.contents[0]);assertFalse(env.hasMarker(player.player));
    }

    @Test void givenTwoUndecodablePlayers_whenRestoring_thenEachUuidGetsADiagnostic() throws Exception {
        FakePlayer other = new FakePlayer(env, "Other", player.location.clone());
        for (FakePlayer current : List.of(player, other)) {
            store.save(PlayerStates.capture(current.player, env.codec, "p:visit", "visit", "Owner", current.location, env.now, List.of()));
            env.markers.add(current.player.getUniqueId());
        }
        env.codec.decodeFailure = new IllegalArgumentException("corrupt");
        assertEquals(RecoveryDecision.PENDING, engine.recover(player.player));
        assertEquals(RecoveryDecision.PENDING, engine.recover(other.player));
        assertEquals(2, env.logs.stream().filter(line -> line.contains("Items in the snapshot")).count());
    }
}
