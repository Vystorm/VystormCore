package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.71.0: a plugin's chest fallback gets arg, session values and a returning pick's result. */
class FallbackContextTest {
    Player player;
    Plugin owner;
    final List<FallbackContext> calls = new ArrayList<>();
    Consumer<FallbackContext> inChest = ctx -> { };

    @BeforeEach
    void setUp() {
        PanelRegistry.clear();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        // no panel hub and no Core plugin in tests: PanelOpener.open goes straight to the plugin's fallback
        PanelRegistry.register(owner, "example:area", PanelSpec.builder("Area").render(ctx -> Panel.page("Area"))
                .contextFallback((p, ctx) -> { calls.add(ctx); inChest.accept(ctx); }).build());
    }

    @AfterEach
    void tearDown() { PanelRegistry.clear(); }

    PanelRegistry.Registered reg() { return PanelRegistry.find("example:area").orElseThrow(); }

    @Test
    void givenOneArgumentFallback_whenOpened_thenStillCalledWithThePlayer() {
        AtomicReference<Player> seen = new AtomicReference<>();
        PanelRegistry.register(owner, "example:old", PanelSpec.builder("Old").render(ctx -> Panel.page("Old")).fallback(seen::set).build());
        PanelOpenResult r = PanelOpener.open(player, PanelRegistry.find("example:old").orElseThrow(), "x");
        assertEquals(PanelOpenResult.FALLBACK, r);
        assertSame(player, seen.get());
        assertNull(PanelRegistry.find("example:old").orElseThrow().spec().contextFallback());
    }

    @Test
    void givenOneArgumentFallback_whenPickReturnEndsInIt_thenResultStillInChat() {
        AtomicReference<Player> seen = new AtomicReference<>();
        PanelRegistry.register(owner, "example:old", PanelSpec.builder("Old").render(ctx -> Panel.page("Old")).fallback(seen::set).build());
        PanelResume resume = new PanelResume(PanelRegistry.find("example:old").orElseThrow(), "a", new LinkedHashMap<>(Map.of("k", 1)));
        PanelOpener.reopen(player, resume, Result.ok("Picked"));
        assertSame(player, seen.get());
        verify(player).sendMessage("§a✔ Picked");
    }

    @Test
    void givenFreshOpen_whenFallbackRuns_thenArgEmptySessionNoResultNotReturning() {
        assertEquals(PanelOpenResult.FALLBACK, PanelOpener.open(player, reg(), "farm"));
        FallbackContext ctx = calls.getFirst();
        assertSame(player, ctx.player());
        assertEquals("farm", ctx.arg());
        assertNull(ctx.session().get("count"));
        assertEquals(Optional.empty(), ctx.result());
        assertFalse(ctx.returning());
        assertEquals(0, ctx.session().id());
    }

    @Test
    void givenNullArg_whenFallbackRuns_thenArgIsEmpty() {
        PanelOpener.open(player, reg(), null);
        assertEquals("", calls.getFirst().arg());
    }

    @Test
    void givenPickReturnFromScreenSession_whenReopenEndsInChest_thenArgValuesAndResultArrive() {
        // the failing case seen in the client: a pick started from the screen session (counter 3) comes back into the chest
        Map<String, Object> carried = new LinkedHashMap<>(Map.of("count", 3));
        PanelResume resume = new PanelResume(reg(), "e5", carried);
        PanelOpener.reopen(player, resume, Result.ok("Picked: Alex"));
        FallbackContext ctx = calls.getFirst();
        assertEquals("e5", ctx.arg());
        assertEquals(Integer.valueOf(3), ctx.session().get("count"));
        assertEquals("Picked: Alex", ctx.result().orElseThrow().message());
        assertTrue(ctx.returning());
        verify(player).sendMessage("§a✔ Picked: Alex");   // the chest did not say it showed the result
    }

    @Test
    void givenChestShowsResult_whenReopened_thenNoChatLine() {
        inChest = FallbackContext::resultShown;
        PanelOpener.reopen(player, new PanelResume(reg(), "e5", new LinkedHashMap<>()), Result.ok("Picked"));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void givenValuesWrittenInChest_whenPickReturnFromContext_thenTheNextOpenSeesThem() {
        PanelOpener.open(player, reg(), "e5");
        FallbackContext chest = calls.getFirst();
        chest.session().put("count", 4);
        PickReturn back = PickReturn.panel(chest);
        assertEquals(Optional.of(owner), back.owner());
        assertEquals(Integer.valueOf(4), back.session().get("count"));
        back.session().put("last", "Picked: stone");   // the pick handler writes into the carried values
        assertEquals("Picked: stone", chest.session().get("last"), "one session: the chest's own map is carried");
        back.go(player, Result.ok("Picked: stone"));
        FallbackContext again = calls.get(1);
        assertEquals("e5", again.arg());
        assertEquals(Integer.valueOf(4), again.session().get("count"));
        assertEquals("Picked: stone", again.result().orElseThrow().message());
    }

    @Test
    void givenValuesWrittenInChest_whenReturnOpensOnScreenOrClientMod_thenThatSessionSeesThem() {
        // the screen route and the client mod both seed their new session through PanelOpener.seed: shown on the hub
        List<PanelMessage> sent = new ArrayList<>();
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        PanelChannel channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> Protocol.CAP_PANELS,
                (p, d) -> sent.add(PanelMessage.decodeClientbound(d)), Logger.getAnonymousLogger(), nanos::get);
        PanelHub hub = new PanelHub(channel, nanos::get, Logger.getAnonymousLogger(), e -> { }, Runnable::run, Runnable::run);
        channel.listener(hub);
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Hello(1, 1, 0, 0)));
        AtomicReference<String> rendered = new AtomicReference<>();
        PanelRegistry.register(owner, "example:pay", PanelSpec.builder("Pay").render(ctx -> {
            rendered.set(ctx.arg() + "|" + ctx.session().get("count"));
            return Panel.page("Pay");
        }).contextFallback((p, ctx) -> calls.add(ctx)).build());
        PanelRegistry.Registered pay = PanelRegistry.find("example:pay").orElseThrow();
        PanelOpener.open(player, pay, "e5");
        calls.getFirst().session().put("count", 4);
        PanelResume resume = PanelOpener.resumeOf(calls.getFirst()).orElseThrow();
        PanelOpener.Seed seed = new PanelOpener.Seed(player.getUniqueId(), pay.id(), resume.values(), List.of());
        assertEquals(PanelOpenResult.NATIVE, PanelOpener.withSeed(seed, () -> hub.openNative(player, pay, resume.arg(), 0)));
        assertEquals("e5|4", rendered.get());
    }

    @Test
    void givenChestWroteValues_whenPanelOpenedFreshAgain_thenSessionStartsEmpty() {
        PanelOpener.open(player, reg(), "e5");
        calls.getFirst().session().put("count", 4);
        PanelOpener.open(player, reg(), "e5");   // a command or menu opens it again: no table keeps old values
        assertNull(calls.get(1).session().get("count"));
        assertFalse(calls.get(1).returning());
    }

    @Test
    void givenContext_whenReopened_thenSameSessionByTheNormalRoute() {
        PanelOpener.open(player, reg(), "e5");
        FallbackContext first = calls.getFirst();
        first.session().put("count", 2);
        assertEquals(PanelOpenResult.FALLBACK, first.reopen());
        FallbackContext second = calls.get(1);
        assertEquals("e5", second.arg());
        assertEquals(Integer.valueOf(2), second.session().get("count"));
        second.session().put("count", 3);
        assertEquals(Integer.valueOf(3), first.session().get("count"), "the same map, not a copy");
        assertFalse(second.returning());
        assertEquals(Optional.empty(), second.result());
    }

    @Test
    void givenReopenFromInsideTheFallbackCall_whenCalled_thenNoneAndNoLoop() {
        AtomicReference<PanelOpenResult> inner = new AtomicReference<>();
        inChest = ctx -> inner.set(ctx.reopen());
        PanelOpener.open(player, reg(), "e5");
        assertEquals(PanelOpenResult.NONE, inner.get());
        assertEquals(1, calls.size());
    }

    @Test
    void givenOfflinePlayerOrDisabledOwner_whenReopenedOrReturned_thenNothingOpens() {
        PanelOpener.open(player, reg(), "e5");
        FallbackContext ctx = calls.getFirst();
        when(owner.isEnabled()).thenReturn(false);
        assertEquals(PanelOpenResult.NONE, ctx.reopen());
        PickReturn.panel(ctx).go(player, Result.ok("Done"));
        assertEquals(1, calls.size());
        verify(player).sendMessage("§a✔ Done");   // a disabled owner leaves only the chat line
        when(owner.isEnabled()).thenReturn(true);
        when(player.isOnline()).thenReturn(false);
        assertEquals(PanelOpenResult.NONE, ctx.reopen());
        PickReturn.panel(ctx).go(player, Result.ok("Later"));
        assertEquals(1, calls.size());
    }

    @Test
    void givenContextFallback_whenGetterUsedAsOneArgument_thenRunsWithFreshContext() {
        reg().spec().fallback().accept(player);
        FallbackContext ctx = calls.getFirst();
        assertEquals("", ctx.arg());
        assertNotNull(ctx.session());
        assertFalse(ctx.returning());
        assertTrue(PickReturn.panel(ctx).owner().isPresent(), "found by its registered spec");
    }

    @Test
    void givenLaterFallbackCall_whenBuilt_thenItWins() {
        AtomicReference<Player> old = new AtomicReference<>();
        PanelSpec a = PanelSpec.builder("A").render(ctx -> Panel.page("A")).contextFallback((p, c) -> { }).fallback(old::set).build();
        assertNull(a.contextFallback());
        a.fallback().accept(player);
        assertSame(player, old.get());
        PanelSpec b = PanelSpec.builder("B").render(ctx -> Panel.page("B")).fallback(old::set).contextFallback((p, c) -> { }).build();
        assertNotNull(b.contextFallback());
    }

    @Test
    void givenForeignContext_whenPickReturn_thenNowhere() {
        FallbackContext foreign = mock(FallbackContext.class);
        PickReturn back = PickReturn.panel(foreign);
        assertTrue(back.owner().isEmpty());
        assertTrue(PanelOpener.resumeOf(foreign).isEmpty());
    }

    @Test
    void givenCapability_whenAsked_thenSince071() {
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.PANEL_FALLBACK_CONTEXT));
        assertTrue(CoreApi.has("panel-fallback-context"));
    }

    // ------------------------------------------------------------------ review fixes

    void open(Player p) { }
    void open(Player p, int page) { }

    @Test
    void givenOldCallForms_whenCompiled_thenNullAndOverloadedMethodReferencesStillResolve() {
        PanelSpec a = PanelSpec.builder("A").render(ctx -> Panel.page("A")).fallback(null).build();
        assertNull(a.fallback());
        PanelSpec b = PanelSpec.builder("B").render(ctx -> Panel.page("B")).fallback(this::open).build();
        assertNotNull(b.fallback());
        assertNull(b.contextFallback());
    }

    @Test
    void givenFallbackThatThrowsAfterResultShown_whenReopened_thenResultStillInChat() {
        inChest = ctx -> { ctx.resultShown(); throw new IllegalStateException("chest failed"); };
        when(owner.getLogger()).thenReturn(Logger.getAnonymousLogger());
        PanelOpener.reopen(player, new PanelResume(reg(), "e5", new LinkedHashMap<>()), Result.ok("Picked"));
        verify(player).sendMessage("§a✔ Picked");
    }

    @Test
    void givenResultShownCalledAfterTheCall_whenReopened_thenIgnoredAndChatSent() {
        PanelOpener.reopen(player, new PanelResume(reg(), "e5", new LinkedHashMap<>()), Result.ok("Picked"));
        calls.getFirst().resultShown();   // a tick later, from a click handler: too late
        verify(player).sendMessage("§a✔ Picked");
    }

    @Test
    void givenNestedOpenOfTheSamePanelInsideTheFallback_whenRun_thenItIsAFreshOpen() {
        inChest = ctx -> { if (calls.size() == 1) PanelOpener.open(player, reg(), "other"); };
        PanelOpener.reopen(player, new PanelResume(reg(), "e5", new LinkedHashMap<>(Map.of("count", 3))), Result.ok("Picked"));
        assertEquals(2, calls.size());
        FallbackContext nested = calls.get(1);
        assertEquals("other", nested.arg());
        assertNull(nested.session().get("count"));
        assertFalse(nested.returning());
        assertEquals(Optional.empty(), nested.result());
    }

    @Test
    void givenOlderContextReopenedInsideAFallbackOfTheSamePanel_whenCalled_thenNone() {
        PanelOpener.open(player, reg(), "e5");
        FallbackContext older = calls.getFirst();
        AtomicReference<PanelOpenResult> inner = new AtomicReference<>();
        inChest = ctx -> { if (ctx != older) inner.set(older.reopen()); };
        PanelOpener.open(player, reg(), "e5");
        assertEquals(PanelOpenResult.NONE, inner.get());
        assertEquals(2, calls.size());
    }

    @Test
    void givenPanelRegisteredAgainByAnotherPluginInstance_whenOldContextReopensOrReturns_thenNothingOpens() {
        PanelOpener.open(player, reg(), "e5");
        FallbackContext old = calls.getFirst();
        old.session().put("count", 3);
        PanelRegistry.clear();
        Plugin again = mock(Plugin.class);
        when(again.isEnabled()).thenReturn(true);
        PanelRegistry.register(again, "example:area", PanelSpec.builder("Area").render(ctx -> Panel.page("Area"))
                .contextFallback((p, ctx) -> calls.add(ctx)).build());
        assertEquals(PanelOpenResult.NONE, old.reopen());
        PickReturn.panel(old).go(player, Result.ok("Late"));
        assertEquals(1, calls.size());
        verify(player).sendMessage("§a✔ Late");
    }

    @Test
    void givenContextOfOnePlayer_whenReturnGoesToAnotherPlayer_thenOnlyChat() {
        PanelOpener.open(player, reg(), "e5");
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isOnline()).thenReturn(true);
        PickReturn.panel(calls.getFirst()).go(other, Result.ok("Picked"));
        assertEquals(1, calls.size());
        verify(other).sendMessage("§a✔ Picked");
    }
}
