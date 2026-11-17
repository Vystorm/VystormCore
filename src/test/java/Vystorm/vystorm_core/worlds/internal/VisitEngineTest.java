package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.VisitTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.region.Region;
import Vystorm.vystorm_core.region.RegionShape;
import Vystorm.vystorm_core.worlds.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Besuche: Reihenfolge, Dupe-Versuche (Review C1/C2), Items aus dem Besuch (I1), Erholung, Sichtbarkeit. */
class VisitEngineTest {
    static final String ID = "vystormskills:talentwelt";
    @TempDir Path dir;
    World main, talent;
    Env env;
    Worlds worlds;
    VisitSnapshotStore store;
    VisitEngine engine;
    FakePlayer esoren;
    ItemStack diamond, helmet, shield, cursorItem;
    Location arrival;
    final List<String> hooks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        main = world("world");
        talent = world("vystorm_talentwelt");
        env = new Env(main);
        env.worlds.put(talent.getName(), talent);
        env.commands.put("talents", "talente");
        worlds = new Worlds();
        worlds.add(new ManagedWorldImpl(plugin("VystormSkills"), spec(Visibility.PARTY), talent, dir.resolve("talentwelt"), false));
        store = new VisitSnapshotStore(dir.resolve("visits"));
        engine = new VisitEngine(env, worlds, store, new Writes(true));
        esoren = new FakePlayer(env, "Esoren", new Location(main, 10, 64, 10));
        diamond = item("diamond");helmet = item("helmet");shield = item("shield");cursorItem = item("cursor-item");
        esoren.contents[0] = diamond;esoren.contents[39] = helmet;esoren.contents[40] = shield;esoren.cursor = cursorItem;
        arrival = new Location(talent, 0, 100, 0);
    }

    WorldSpec spec(Visibility visibility) {
        VisitHooks recording = new VisitHooks() {
            @Override public void entering(org.bukkit.entity.Player p, Visit v) { hooks.add("entering " + p.getName()); }
            @Override public void arrived(org.bukkit.entity.Player p, Visit v) { hooks.add("arrived " + p.getName()); }
            @Override public void leaving(org.bukkit.entity.Player p, Visit v, ExitReason r) { hooks.add("leaving " + p.getName() + " " + r); }
            @Override public Location respawn(org.bukkit.entity.Player p, Visit v) { return new Location(talent, 5, 101, 5); }
        };
        return WorldSpec.builder(ID, "vystorm_talentwelt", mock(ChunkGenerator.class)).visibility(visibility).hooks(recording)
                .policy(VisitPolicy.builder().allowCommands("talente", "msg").cooldownSeconds(5).blockRegionKinds(List.of("arena"))
                        .toolKit(p -> Map.of(0, item("focus"), 8, item("exit"))).build())
                .build();
    }

    private void enterAndArrive(FakePlayer player) {
        assertEquals(VisitResult.OK, engine.enter(player.player, ID, arrival));
        env.runTicks();
        assertEquals(Visit.Phase.ACTIVE, engine.visit(player.player.getUniqueId()).orElseThrow().phase());
    }

    private long count(FakePlayer player, ItemStack stack) { return player.items().stream().filter(s -> s == stack).count(); }

    private boolean hasTools(FakePlayer player) { return player.items().stream().anyMatch(env.tools::contains) || env.tools.contains(player.cursor); }

    @Test
    void givenOptInExperienceIsolation_whenVisitEnds_thenOriginalXpRestored() {
        WorldSpec isolated = WorldSpec.builder(ID, "vystorm_talentwelt", mock(ChunkGenerator.class))
                .policy(VisitPolicy.builder().isolateExperience(true).build()).build();
        worlds.add(new ManagedWorldImpl(plugin("VystormSkills"), isolated, talent, dir.resolve("talentwelt"), false));
        when(esoren.player.getLevel()).thenReturn(31);
        when(esoren.player.getTotalExperience()).thenReturn(1543);
        when(esoren.player.getExp()).thenReturn(.42f);
        enterAndArrive(esoren);
        VisitSnapshot saved = store.load(esoren.player.getUniqueId()).orElseThrow();
        assertEquals(31, saved.experienceLevel());
        verify(esoren.player).setLevel(0);
        verify(esoren.player).setTotalExperience(0);
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        verify(esoren.player).setLevel(31);
        verify(esoren.player).setTotalExperience(1543);
        verify(esoren.player).setExp(.42f);
    }

    @Test
    void givenUndecodableSnapshot_whenCommandExit_thenActiveWithoutTeleportOrLeavingHook() {
        enterAndArrive(esoren);
        env.events.clear();
        env.codec.decodeFailure = new IllegalArgumentException("broken items");
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals(talent, esoren.location.getWorld());
        assertEquals(Visit.Phase.ACTIVE, engine.visit(esoren.player.getUniqueId()).orElseThrow().phase());
        assertTrue(env.events.stream().noneMatch(e -> e.startsWith("teleport")));
        assertFalse(hooks.stream().anyMatch(h -> h.startsWith("leaving")));
        assertTrue(env.messages.contains("worlds.visit.restore-failed"));
        assertFalse(engine.commandAllowed(esoren.player, "/market sell"));
    }

    @Test
    void givenBothTeleportsFail_whenShutdown_thenNeutralizeOnlinePlayerAndRetainRecovery() {
        assertForcedFailureSafe(ExitReason.SHUTDOWN);
    }

    @Test
    void givenBothTeleportsFail_whenRebuild_thenNeutralizeOnlinePlayerAndRetainRecovery() {
        assertForcedFailureSafe(ExitReason.REBUILD);
    }

    private void assertForcedFailureSafe(ExitReason reason) {
        enterAndArrive(esoren);
        ItemStack gift = item("gift");esoren.contents[20] = gift;
        env.syncTeleport = false;
        clearInvocations(esoren.player);
        engine.exit(esoren.player, reason);
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertFalse(hasTools(esoren));
        assertSame(gift, esoren.contents[20]);
        assertEquals(GameMode.SURVIVAL, esoren.mode);
        verify(esoren.player).setInvulnerable(false);
        verify(esoren.player).setCollidable(true);
        verify(esoren.player).setFlying(false);
        verify(esoren.player).setAllowFlight(false);
        assertTrue(env.hasMarker(esoren.player));
        assertTrue(store.exists(esoren.player.getUniqueId()));
        assertEquals(1, hooks.stream().filter(h -> h.startsWith("leaving")).count());
        env.syncTeleport = true;
        assertEquals("restored", engine.restore(esoren.player));
        assertEquals(1, count(esoren, gift));
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenLeavingVisit_whenAdminRestores_thenReportsInProgress() {
        enterAndArrive(esoren);
        env.asyncTeleport = null;
        engine.exit(esoren.player, ExitReason.COMMAND);
        assertEquals("in-progress", engine.restore(esoren.player));
        assertEquals(1, env.pendings.size());
    }

    @Test
    void givenExpiredCooldowns_whenAnotherPlayerEnters_thenOldEntriesPruned() throws Exception {
        enterAndArrive(esoren);
        engine.exit(esoren.player, ExitReason.QUIT);
        env.now += 6_000;
        FakePlayer alex = new FakePlayer(env, "Alex", new Location(main, 20, 64, 20));
        enterAndArrive(alex);
        var field = VisitEngine.class.getDeclaredField("lastEntry");
        field.setAccessible(true);
        assertEquals(Set.of(alex.player.getUniqueId()), ((Map<?, ?>) field.get(engine)).keySet());
    }

    @Test
    void givenDifferentWorldCooldowns_whenChangingWorld_thenTargetPolicyDeterminesCooldown() {
        enterAndArrive(esoren);
        engine.exit(esoren.player, ExitReason.QUIT);
        World other = world("other");env.worlds.put("other", other);
        WorldSpec spec = WorldSpec.builder("p:other", "other", mock(ChunkGenerator.class))
                .policy(VisitPolicy.builder().cooldownSeconds(0).build()).build();
        worlds.add(new ManagedWorldImpl(plugin("P"), spec, other, dir.resolve("other"), false));
        assertEquals(VisitResult.OK, engine.enter(esoren.player, "p:other", new Location(other, 0, 64, 0)));
    }

    @Test
    void givenEvictionSaveFails_whenRecovering_thenFailureLoggedOncePerWindow() {
        env.setMarker(esoren.player, ID);
        esoren.saveFailure = new IllegalStateException("disk full");
        engine.recover(esoren.player);
        assertTrue(env.logs.stream().anyMatch(s -> s.contains("disk full")));
    }

    @Test
    void givenReviewExtraction_whenLoadingHelpers_thenRecoveryAndVisibilityHaveOwnClasses() throws Exception {
        assertNotNull(Class.forName("Vystorm.vystorm_core.worlds.internal.VisitRecovery"));
        assertNotNull(Class.forName("Vystorm.vystorm_core.worlds.internal.VisitVisibility"));
    }

    @Test
    void givenSnapshotWriteRequested_whenEntering_thenInventoryAndMarkerWaitForDurability() {
        Writes writes = new Writes(false);
        engine = new VisitEngine(env, worlds, store, writes);
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
        assertSame(diamond, esoren.contents[0]);
        assertFalse(env.hasMarker(esoren.player));
        assertEquals(VisitResult.ALREADY, engine.enter(esoren.player, ID, arrival));
    }

    @Test
    void givenToolOnlyEvidence_whenRecovering_thenCreativeFlagsStayUntouched() {
        esoren.mode = GameMode.CREATIVE;
        esoren.contents[4] = item("stray-tool");
        env.tools.add(esoren.contents[4]);
        assertEquals(RecoveryDecision.EVICT, engine.recover(esoren.player));
        assertNull(esoren.contents[4]);
        assertSame(diamond, esoren.contents[0]);
        assertEquals(GameMode.CREATIVE, esoren.mode);
        verify(esoren.player, never()).setAllowFlight(anyBoolean());
        verify(esoren.player, never()).setFlying(anyBoolean());
        verify(esoren.player, never()).setInvulnerable(anyBoolean());
    }

    @Test
    void givenTwoPlayersWithSaveFailure_whenEvicted_thenBothFailuresLogged() {
        FakePlayer other = new FakePlayer(env, "Other", new Location(main, 0, 64, 0));
        for (FakePlayer p : List.of(esoren, other)) {
            env.markers.add(p.player.getUniqueId());
            p.saveFailure = new IllegalStateException("disk failure");
            engine.recover(p.player);
        }
        assertEquals(2, env.logs.stream().filter(l -> l.contains("not saved:")).count());
    }

    @Test
    void givenEnteringVisitWithBrokenSnapshot_whenAdminRestores_thenInProgressAndFailedTeleportCleansUp() {
        env.asyncTeleport = null;
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
        env.codec.decodeFailure = new IllegalArgumentException("broken");
        assertEquals(RestoreOutcome.IN_PROGRESS, engine.restoreOutcome(esoren.player));
        env.complete(0, false);
        env.runTicks();
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertTrue(env.hasMarker(esoren.player));
        assertEquals(main, esoren.location.getWorld());
    }

    // ------------------------------------------------------------------ Betreten

    @Test
    void givenPlayer_whenEntering_thenSnapshotOnDiskBeforeAnythingChanges() {
        UUID id = esoren.player.getUniqueId();
        doAnswer(i -> { assertTrue(store.exists(id), "snapshot written before the inventory is cleared");Arrays.fill(esoren.contents, null);return null; })
                .when(esoren.inventory).clear();
        enterAndArrive(esoren);
        assertEquals(talent, esoren.location.getWorld());
        assertTrue(env.markers.contains(id));
        assertTrue(esoren.items().stream().allMatch(env.tools::contains), "only tools while visiting");
        assertNull(esoren.cursor, "cursor captured into the snapshot, not dropped");
        VisitSnapshot snapshot = store.load(id).orElseThrow();
        assertEquals("world", snapshot.returnWorld());
        assertEquals(List.of(), PlayerStates.extras(snapshot, env.codec));
        assertTrue(Arrays.asList(env.codec.decode(snapshot.items())).contains(cursorItem));
        assertEquals(List.of("entering Esoren", "arrived Esoren"), hooks);
        assertEquals(GameMode.ADVENTURE, esoren.mode);
    }

    @Test
    void givenOpenCraftingGridAirAndFreeze_whenEntering_thenCapturedAndGridCleared() {
        ItemStack wood = item("wood");
        CraftingInventory crafting = mock(CraftingInventory.class);
        when(crafting.getMatrix()).thenReturn(new ItemStack[]{wood, null, null, null});
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(crafting);
        when(esoren.player.getOpenInventory()).thenReturn(view);
        when(esoren.player.getRemainingAir()).thenReturn(120);
        when(esoren.player.getFreezeTicks()).thenReturn(33);
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
        VisitSnapshot snapshot = store.load(esoren.player.getUniqueId()).orElseThrow();
        assertTrue(Arrays.asList(env.codec.decode(snapshot.items())).containsAll(List.of(cursorItem, wood)));
        assertEquals(120, snapshot.air());
        assertEquals(33, snapshot.freezeTicks());
        verify(crafting).setMatrix(any(ItemStack[].class));
    }

    @Test
    void givenRegionCheckFailsOrBlockedKind_whenEntering_thenRefusedFailClosedAndNothingChanged() {
        env.regionFailure = new IllegalStateException("region service down");
        assertEquals(VisitResult.BLOCKED_REGION, engine.enter(esoren.player, ID, arrival));
        env.regionFailure = null;
        env.regions = List.of(Region.of("arena", "a1", new RegionShape.Box(null, 0, 0, 0, 20, 100, 20)));
        assertEquals(VisitResult.BLOCKED_REGION, engine.enter(esoren.player, ID, arrival));
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertSame(diamond, esoren.contents[0]);
        assertTrue(env.markers.isEmpty());
    }

    @Test
    void givenOpenSnapshotOrMarker_whenEntering_thenPendingRecovery() throws Exception {
        store.save(VisitSnapshotStoreTest.sample(esoren.player.getUniqueId()));
        assertEquals(VisitResult.PENDING_RECOVERY, engine.enter(esoren.player, ID, arrival));
        store.delete(esoren.player.getUniqueId());
        env.markers.add(esoren.player.getUniqueId());
        assertEquals(VisitResult.PENDING_RECOVERY, engine.enter(esoren.player, ID, arrival));
    }

    @Test
    void givenRecentEntry_whenEnteringAgain_thenCooldownUntilItExpires() {
        enterAndArrive(esoren);
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals(VisitResult.COOLDOWN, engine.enter(esoren.player, ID, arrival));
        env.now += 5_000;
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
    }

    @Test
    void givenEntryTeleportFails_whenCallbackRuns_thenStateBackAndPluginCleanedUp() {
        env.asyncTeleport = false;
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
        env.runTicks();
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertSame(diamond, esoren.contents[0]);
        assertFalse(hasTools(esoren));
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertTrue(env.messages.contains("worlds.visit.enter-failed"));
        assertEquals(List.of("entering Esoren", "leaving Esoren FAILED_ENTRY"), hooks);
    }

    // ------------------------------------------------------------------ Verlassen (Review C2, I1)

    @Test
    void givenVisit_whenLeaving_thenTeleportFirstThenRestoreThenSaveThenDelete() {
        enterAndArrive(esoren);
        env.events.clear();
        assertTrue(engine.exit(esoren.player, ExitReason.COMMAND));
        assertTrue(store.exists(esoren.player.getUniqueId()), "nothing restored before the teleport has finished");
        env.runTicks();
        List<String> e = env.events;
        assertTrue(e.indexOf("teleport-async world") < e.indexOf("set-contents"), e.toString());
        assertTrue(e.indexOf("set-contents") < e.indexOf("save"), e.toString());
        assertEquals(main, esoren.location.getWorld());
        assertSame(diamond, esoren.contents[0]);
        assertSame(helmet, esoren.contents[39]);
        assertSame(shield, esoren.contents[40]);
        assertEquals(1, count(esoren, cursorItem), "cursor item comes back into the inventory");
        assertFalse(hasTools(esoren));
        assertFalse(env.markers.contains(esoren.player.getUniqueId()));
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertEquals(1, esoren.saves);
    }

    @Test
    void givenReturnTeleportFails_whenLeaving_thenVisitKeptAndLaterRestoredExactlyOnce() {
        enterAndArrive(esoren);
        env.asyncTeleport = false;env.syncTeleport = false;
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        UUID id = esoren.player.getUniqueId();
        assertTrue(engine.inVisit(id), "visit stays when the return teleport fails");
        assertEquals(Visit.Phase.ACTIVE, engine.visit(id).orElseThrow().phase());
        assertTrue(env.messages.contains("worlds.visit.return-failed"));
        assertEquals(0, count(esoren, diamond), "nothing restored inside the world");
        assertTrue(store.exists(id));
        // Dupe-Versuch: erneut verlassen, dann erneut „joinen“ – nur ein Diamant.
        env.asyncTeleport = true;env.syncTeleport = true;
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals(RecoveryDecision.NOTHING, engine.recover(esoren.player));
        assertEquals(1, count(esoren, diamond));
        assertEquals(1, esoren.saves);
    }

    @Test
    void givenQuitWhileReturnTeleportPending_whenLateCallbackRuns_thenRestoredOnlyOnce() {
        enterAndArrive(esoren);
        env.asyncTeleport = null;
        engine.exit(esoren.player, ExitReason.COMMAND);
        assertEquals(Visit.Phase.LEAVING, engine.visit(esoren.player.getUniqueId()).orElseThrow().phase());
        engine.exit(esoren.player, ExitReason.QUIT);   // PlayerQuitEvent: synchron hinaus und zurück
        esoren.online = false;
        env.pending.complete(true);
        env.runTicks();
        assertEquals(1, count(esoren, diamond));
        assertEquals(1, esoren.saves);
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertEquals(1, hooks.stream().filter(h -> h.startsWith("leaving")).count());
    }

    @Test
    void givenLeaveDuringEntryTeleport_whenEntryFinishesFirst_thenNoArrivalAndRestoredOnce() {
        env.asyncTeleport = null;
        assertEquals(VisitResult.OK, engine.enter(esoren.player, ID, arrival));
        engine.exit(esoren.player, ExitReason.COMMAND);              // noch während des Ladens
        env.complete(0, true);                                         // Eintritt fertig
        env.runTicks();
        assertEquals(Visit.Phase.LEAVING, engine.visit(esoren.player.getUniqueId()).orElseThrow().phase());
        assertFalse(hooks.contains("arrived Esoren"));
        env.complete(1, true);                                         // Rückkehr fertig
        env.runTicks();
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertEquals(main, esoren.location.getWorld());
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenLeaveDuringEntryTeleport_whenEntryFinishesLast_thenPlayerSentOutAgain() {
        env.asyncTeleport = null;
        engine.enter(esoren.player, ID, arrival);
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.complete(1, true);                                         // Rückkehr zuerst
        env.runTicks();
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        env.complete(0, true);                                         // verspäteter Eintritt landet in der Welt
        env.runTicks();
        assertEquals(main, esoren.location.getWorld(), "nie ohne Besuch in der Welt zurücklassen");
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenItemsReceivedDuringVisit_whenLeaving_thenKeptAndOverflowDropped() {
        enterAndArrive(esoren);
        ItemStack gift = item("gift");
        esoren.contents[20] = gift;                         // Admin-/give während des Besuchs
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals(1, count(esoren, gift));
        assertEquals(1, count(esoren, diamond));
        assertFalse(hasTools(esoren));
    }

    @Test
    void givenFullInventory_whenLeaving_thenExtrasDroppedNotLost() {
        for (int slot = 1; slot < 36; slot++) esoren.contents[slot] = item("filler" + slot);
        enterAndArrive(esoren);
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals(List.of(cursorItem), env.drops);
    }

    @Test
    void givenSaveDataFails_whenLeaving_thenSnapshotKept() {
        enterAndArrive(esoren);
        esoren.saveFailure = new IllegalStateException("disk full");
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertTrue(store.exists(esoren.player.getUniqueId()));
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("snapshot kept")));
    }

    @Test
    void givenForeignTeleportOut_whenWorldChanged_thenRestoredInPlaceWithoutTeleport() {
        enterAndArrive(esoren);
        env.events.clear();
        esoren.moveTo(new Location(main, 500, 70, 500));     // z. B. /spawn eines anderen Plugins
        engine.changedWorld(esoren.player, talent);
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertSame(diamond, esoren.contents[0]);
        assertEquals(500, esoren.location.getX());
        assertTrue(env.events.stream().noneMatch(e -> e.startsWith("teleport")), env.events.toString());
    }

    // ------------------------------------------------------------------ Absturz-Erholung (Review C2, I2)

    @Test
    void givenCrashInsideVisit_whenJoiningWithFreshEngine_thenTeleportOutThenRestore() {
        enterAndArrive(esoren);
        ItemStack gift = item("gift");
        esoren.contents[20] = gift;
        VisitEngine afterRestart = new VisitEngine(env, worlds, store, new Writes(true));
        env.events.clear();
        assertEquals(RecoveryDecision.RESTORE, afterRestart.recover(esoren.player));
        assertTrue(env.events.indexOf("teleport world") < env.events.indexOf("set-contents"), env.events.toString());
        assertEquals(main, esoren.location.getWorld());
        assertEquals(1, count(esoren, diamond));
        assertEquals(1, count(esoren, gift), "items received during the visit survive the crash recovery");
        assertFalse(hasTools(esoren));
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertTrue(env.messages.contains("worlds.visit.restored"));
    }

    @Test
    void givenSnapshotButPlayerDataAlreadyOutside_whenJoining_thenDiscardedWithoutTouchingInventory() throws Exception {
        store.save(VisitSnapshotStoreTest.sample(esoren.player.getUniqueId()));
        env.events.clear();
        assertEquals(RecoveryDecision.DISCARD, engine.recover(esoren.player));
        assertFalse(env.events.contains("set-contents"));
        assertSame(diamond, esoren.contents[0]);
        assertFalse(store.exists(esoren.player.getUniqueId()));
    }

    @Test
    void givenVisitStateWithoutReadableSnapshot_whenJoining_thenEvicted() {
        enterAndArrive(esoren);
        VisitEngine afterRestart = new VisitEngine(env, worlds, new VisitSnapshotStore(dir.resolve("empty")));
        assertEquals(RecoveryDecision.EVICT, afterRestart.recover(esoren.player));
        assertFalse(hasTools(esoren));
        assertFalse(env.markers.contains(esoren.player.getUniqueId()));
        assertEquals(GameMode.SURVIVAL, esoren.mode);
        assertEquals(main, esoren.location.getWorld());
    }

    @Test
    void givenLoginInsideManagedWorld_whenSpawnLocationAsked_thenReturnLocationAndRestoreOnJoin() {
        enterAndArrive(esoren);
        VisitEngine afterRestart = new VisitEngine(env, worlds, store, new Writes(true));
        env.markers.clear();                                   // ohne Marker und Werkzeug: die Umleitung zählt als Besuchsstand
        Arrays.fill(esoren.contents, null);
        Location spawn = afterRestart.spawnLocation(esoren.player.getUniqueId(), new Location(talent, 0, 100, 0));
        assertEquals(main, spawn.getWorld());
        assertEquals(10, spawn.getX());
        esoren.moveTo(spawn);
        assertEquals(RecoveryDecision.RESTORE, afterRestart.recover(esoren.player));
        assertEquals(1, count(esoren, diamond));
        assertNull(afterRestart.spawnLocation(UUID.randomUUID(), new Location(main, 0, 64, 0)));
    }

    @Test
    void givenLegacySkillsTools_whenJoiningWithFormat1Snapshot_thenRestored() throws Exception {
        ItemStack legacyTool = item("legacy-door");
        env.legacyTools.add(legacyTool);
        Arrays.fill(esoren.contents, null);
        esoren.contents[8] = legacyTool;
        esoren.cursor = null;
        String items = env.codec.encode(new ItemStack[]{diamond});
        store.save(new VisitSnapshot(1, esoren.player.getUniqueId(), ID, "vystorm_talentwelt", "VystormSkills", 1, "world", 10, 64, 10, 0, 0,
                "SURVIVAL", items, "", 0, List.of(), 20, 20, 5, 0, 0, 0, -1, 0, false, false, 0.1f, -1, true, false, "vystormskills:tw_item"));
        assertEquals(RecoveryDecision.RESTORE, engine.recover(esoren.player));
        assertSame(diamond, esoren.contents[0]);
        assertNull(esoren.contents[8]);
    }

    // ------------------------------------------------------------------ Befehle, Teleports, Respawn (Review C1)

    @Test
    void givenVisit_whenCommandsTyped_thenOnlyAllowListPasses() {
        assertTrue(engine.commandAllowed(esoren.player, "/market verkaufen 100"), "no visit, no restriction");
        enterAndArrive(esoren);
        assertFalse(engine.commandAllowed(esoren.player, "/market verkaufen 100"));
        assertFalse(engine.commandAllowed(esoren.player, "/give Esoren diamond 64"));
        assertTrue(engine.commandAllowed(esoren.player, "/talente welt verlassen"));
        assertTrue(engine.commandAllowed(esoren.player, "/talents welt"));
    }

    @Test
    void givenForeignTeleportIntoWorld_whenNoVisit_thenOnlyAdminsInCreativeMayEnter() {
        Location from = new Location(main, 0, 64, 0), into = new Location(talent, 0, 100, 0);
        assertFalse(engine.teleportAllowed(esoren.player, from, into));
        esoren.mode = GameMode.CREATIVE;
        when(esoren.player.hasPermission("vystorm_core.worlds.bypass")).thenReturn(true);
        assertTrue(engine.teleportAllowed(esoren.player, from, into));
        esoren.mode = GameMode.SURVIVAL;
        enterAndArrive(esoren);
        assertTrue(engine.teleportAllowed(esoren.player, from, into), "own visit");
        assertTrue(engine.teleportAllowed(esoren.player, into, new Location(main, 1, 64, 1)), "out is allowed; changedWorld ends the visit");
    }

    @Test
    void givenDeathInVisit_whenRespawning_thenInWorldAndToolsGivenAgain() {
        enterAndArrive(esoren);
        Arrays.fill(esoren.contents, null);
        Location at = engine.respawn(esoren.player);
        assertEquals(talent, at.getWorld());
        env.runTicks();
        assertTrue(hasTools(esoren));
        assertNull(engine.respawn(new FakePlayer(env, "Other", new Location(main, 0, 64, 0)).player));
    }

    // ------------------------------------------------------------------ Sichtbarkeit und Admin

    @Test
    void givenPartyVisibility_whenStrangersVisit_thenHiddenAndShownAgainOnExit() {
        FakePlayer alex = new FakePlayer(env, "Alex", new Location(main, 20, 64, 20));
        enterAndArrive(esoren);
        enterAndArrive(alex);
        assertTrue(env.events.contains("hide Esoren Alex"));
        assertTrue(env.events.contains("hide Alex Esoren"));
        engine.exit(alex.player, ExitReason.COMMAND);
        env.runTicks();
        assertTrue(env.events.contains("show Esoren Alex"));
        assertTrue(env.events.contains("show Alex Esoren"));
    }

    @Test
    void givenAdminRestore_whenSnapshotAlreadyApplied_thenReportsDiscardNotRestore() throws Exception {
        assertEquals(VisitEngine.RESTORE_NOTHING, engine.restore(esoren.player));
        store.save(VisitSnapshotStoreTest.sample(esoren.player.getUniqueId()));
        assertEquals(VisitEngine.RESTORE_DISCARDED, engine.restore(esoren.player));
        enterAndArrive(esoren);
        assertEquals(VisitEngine.RESTORE_VISIT_ENDED, engine.restore(esoren.player));
    }

    @Test
    void givenShutdown_whenPlayersVisiting_thenAllRestoredSynchronously() {
        enterAndArrive(esoren);
        engine.shutdown();
        assertFalse(engine.inVisit(esoren.player.getUniqueId()));
        assertSame(diamond, esoren.contents[0]);
        assertFalse(store.exists(esoren.player.getUniqueId()));
    }

    @Test
    void givenFailedSave_whenAdminRetries_thenSnapshotKeptUntilOutsideStateSaved() {
        enterAndArrive(esoren);
        esoren.saveFailure = new IllegalStateException("disk full");
        engine.exit(esoren.player, ExitReason.COMMAND);
        env.runTicks();
        assertEquals("pending", engine.restore(esoren.player));
        assertTrue(store.exists(esoren.player.getUniqueId()), "disk may still contain visit inventory");
        assertEquals(1, count(esoren, diamond));
        esoren.saveFailure = null;
        assertEquals(VisitEngine.RESTORE_DISCARDED, engine.restore(esoren.player));
        assertEquals(1, esoren.saves);
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenGiftInToolSlot_whenToolsRegiven_thenGiftSurvivesReturn() {
        enterAndArrive(esoren);
        ItemStack gift = item("gift-in-tool-slot");
        esoren.contents[0] = gift;
        engine.regiveTools(esoren.player);
        assertEquals(1, count(esoren, gift));
        engine.exit(esoren.player, ExitReason.QUIT);
        assertEquals(1, count(esoren, gift));
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenFullGiftInventory_whenToolsRegiven_thenNoGiftDroppedOrOverwritten() {
        enterAndArrive(esoren);
        List<ItemStack> gifts = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) { ItemStack gift = item("gift" + slot);gifts.add(gift);esoren.contents[slot] = gift; }
        engine.regiveTools(esoren.player);
        for (ItemStack gift : gifts) assertEquals(1, count(esoren, gift));
        assertTrue(env.drops.isEmpty(), "gifts must not be stranded in the protected world");
    }

    @Test
    void givenEncodingFailure_whenEntering_thenTransientItemsReturnedAndNoMarker() {
        env.codec.encodeFailure = new IllegalArgumentException("invalid item");
        assertEquals(VisitResult.SNAPSHOT_FAILED, engine.enter(esoren.player, ID, arrival));
        assertEquals(1, count(esoren, diamond));
        assertEquals(1, count(esoren, cursorItem));
        assertFalse(env.hasMarker(esoren.player));
        assertFalse(store.exists(esoren.player.getUniqueId()));
    }

    @Test
    void givenDecodingFailure_whenReturning_thenVisitItemsAndRecoveryEvidenceUntouched() {
        enterAndArrive(esoren);
        ItemStack gift = item("gift");esoren.contents[20] = gift;
        env.codec.decodeFailure = new IllegalArgumentException("invalid snapshot item");
        assertDoesNotThrow(() -> engine.exit(esoren.player, ExitReason.QUIT));
        assertEquals(1, count(esoren, gift));
        assertTrue(env.hasMarker(esoren.player));
        assertTrue(store.exists(esoren.player.getUniqueId()));
        assertEquals("pending", engine.restore(esoren.player));
        env.codec.decodeFailure = null;
        assertEquals(VisitEngine.RESTORE_RESTORED, engine.restore(esoren.player));
        assertEquals(1, count(esoren, gift));
        assertEquals(1, count(esoren, diamond));
    }

    @Test
    void givenNewerSnapshot_whenRecovering_thenEvidenceKeptAndOnlinePrivilegesNeutralized() throws Exception {
        enterAndArrive(esoren);
        Path file = store.dir().resolve(esoren.player.getUniqueId() + ".yml");
        String future = java.nio.file.Files.readString(file).replace("format: " + VisitSnapshot.FORMAT, "format: " + (VisitSnapshot.FORMAT + 1));
        java.nio.file.Files.writeString(file, future);
        VisitEngine restarted = new VisitEngine(env, worlds, store, new Writes(true));
        assertEquals("pending", restarted.restore(esoren.player));
        assertTrue(env.hasMarker(esoren.player));
        assertFalse(hasTools(esoren));
        assertEquals(GameMode.SURVIVAL, esoren.mode);
        assertEquals(future, java.nio.file.Files.readString(file));
    }

    @Test
    void givenFailedMigrationWithoutVisitEvidence_whenJoining_thenNeverInventRestoreEvidence() {
        env.pendingMigration = true;
        assertEquals(RecoveryDecision.PENDING, engine.recover(esoren.player));
        assertFalse(env.hasMarker(esoren.player), "normal saved inventory must remain eligible for DISCARD after migration");
        assertSame(diamond, esoren.contents[0]);
    }

    @Test
    void givenToolEvidenceWithoutMarkerAndBrokenItems_whenRecovering_thenStripToolsAndPreserveEvidence() {
        enterAndArrive(esoren);
        env.clearMarker(esoren.player);
        env.codec.decodeFailure = new IllegalArgumentException("unreadable legacy items");
        VisitEngine restarted = new VisitEngine(env, worlds, store, new Writes(true));
        assertEquals(RecoveryDecision.PENDING, restarted.recover(esoren.player));
        assertFalse(hasTools(esoren));
        assertEquals(GameMode.SURVIVAL, esoren.mode);
        assertTrue(env.hasMarker(esoren.player), "retain proven visit state after tools are stripped");
        assertTrue(store.exists(esoren.player.getUniqueId()));
    }

    @Test
    void givenFutureSnapshotAndNormalPlayerdata_whenJoining_thenNeverInventRestoreEvidence() throws Exception {
        java.nio.file.Files.createDirectories(store.dir());
        java.nio.file.Files.writeString(store.dir().resolve(esoren.player.getUniqueId() + ".yml"), "format: " + (VisitSnapshot.FORMAT + 1) + "\nitems: x\nreturn: {world: world}\n");
        assertEquals(RecoveryDecision.PENDING, engine.recover(esoren.player));
        assertFalse(env.hasMarker(esoren.player));
        assertSame(diamond, esoren.contents[0]);
    }

    @Test
    void givenFailedLegacyMigration_whenEnteringAnyCoreVisit_thenBlockedUntilMigrationSucceeds() {
        env.pendingMigration = true;
        assertEquals(VisitResult.PENDING_RECOVERY, engine.enter(esoren.player, ID, arrival));
        assertSame(diamond, esoren.contents[0]);
        assertFalse(store.exists(esoren.player.getUniqueId()));
        env.pendingMigration = false;
        enterAndArrive(esoren);
    }

    @Test
    void givenRecoveryTeleportFails_whenAdminRestores_thenReportsPendingAndKeepsSnapshot() {
        enterAndArrive(esoren);
        env.syncTeleport = false;
        VisitEngine restarted = new VisitEngine(env, worlds, store, new Writes(true));
        assertEquals("pending", restarted.restore(esoren.player));
        assertTrue(store.exists(esoren.player.getUniqueId()));
        assertTrue(env.hasMarker(esoren.player));
        assertFalse(env.messages.contains("worlds.visit.restored"));
    }

    @Test
    void givenOwnerPluginAbsent_whenRecovering_thenFullStateRestoredOnce() {
        enterAndArrive(esoren);
        worlds.byId.clear();
        VisitEngine restarted = new VisitEngine(env, worlds, store, new Writes(true));
        assertEquals(VisitEngine.RESTORE_RESTORED, restarted.restore(esoren.player));
        assertEquals(1, count(esoren, diamond));
        assertEquals(VisitEngine.RESTORE_NOTHING, restarted.restore(esoren.player));
        assertEquals(1, count(esoren, diamond));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void givenNondefaultScalarState_whenLeavingOrRecovering_thenEverySavedFieldRestored(boolean recovery) {
        esoren.mode = GameMode.CREATIVE;
        when(esoren.inventory.getHeldItemSlot()).thenReturn(4);
        when(esoren.player.getHealth()).thenReturn(12.5);
        when(esoren.player.getFoodLevel()).thenReturn(8);
        when(esoren.player.getSaturation()).thenReturn(2.5f);
        when(esoren.player.getExhaustion()).thenReturn(1.25f);
        when(esoren.player.getFireTicks()).thenReturn(6);
        when(esoren.player.getFreezeTicks()).thenReturn(33);
        when(esoren.player.getRemainingAir()).thenReturn(120);
        when(esoren.player.getMaximumAir()).thenReturn(300);
        when(esoren.player.getFallDistance()).thenReturn(4.5f);
        when(esoren.player.getAllowFlight()).thenReturn(true);
        when(esoren.player.isFlying()).thenReturn(true);
        when(esoren.player.getFlySpeed()).thenReturn(0.25f);
        when(esoren.player.getWalkSpeed()).thenReturn(0.3f);
        when(esoren.player.isCollidable()).thenReturn(true);
        when(esoren.player.isInvulnerable()).thenReturn(false);
        enterAndArrive(esoren);
        clearInvocations(esoren.player, esoren.inventory);
        if (recovery) {
            VisitEngine fresh = new VisitEngine(env, worlds, store, new Writes(true));
            assertEquals(RecoveryDecision.RESTORE, fresh.recover(esoren.player));
        } else {
            engine.exit(esoren.player, ExitReason.COMMAND);
            env.runTicks();
        }
        verify(esoren.player).setGameMode(GameMode.CREATIVE);
        verify(esoren.inventory).setHeldItemSlot(4);
        verify(esoren.player).setHealth(12.5);
        verify(esoren.player).setFoodLevel(8);
        verify(esoren.player).setSaturation(2.5f);
        verify(esoren.player).setExhaustion(1.25f);
        verify(esoren.player).setFireTicks(6);
        verify(esoren.player).setFreezeTicks(33);
        verify(esoren.player).setRemainingAir(120);
        verify(esoren.player).setFallDistance(4.5f);
        verify(esoren.player).setAllowFlight(true);
        verify(esoren.player).setFlying(true);
        verify(esoren.player).setFlySpeed(0.25f);
        verify(esoren.player).setWalkSpeed(0.3f);
        verify(esoren.player).setCollidable(true);
        verify(esoren.player).setInvulnerable(false);
        assertSame(diamond, esoren.contents[0]);
        assertSame(helmet, esoren.contents[39]);
        assertSame(shield, esoren.contents[40]);
        assertFalse(store.exists(esoren.player.getUniqueId()));
        assertEquals(1, esoren.saves);
    }

}
