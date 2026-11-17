package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelActionTest {
    final List<PanelMessage> sent = new ArrayList<>();
    final List<Object> events = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final AtomicInteger clicks = new AtomicInteger();
    final AtomicBoolean allowed = new AtomicBoolean(true);
    final AtomicBoolean showButton = new AtomicBoolean(true);
    volatile String hudLabel = "HUD";
    Player player, other;
    Plugin owner;
    PanelChannel channel;
    PanelHub hub;
    int session;

    @BeforeEach
    void setUp() {
        PanelRegistry.clear();
        player = player("Esoren");
        other = player("Mallory");
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Example");
        channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> Protocol.CAP_PANELS,
                (p, data) -> { if (p == player) sent.add(PanelMessage.decodeClientbound(data)); }, Logger.getAnonymousLogger(), nanos::get);
        hub = new PanelHub(channel, nanos::get, Logger.getAnonymousLogger(), events::add, Runnable::run, Runnable::run);
        channel.listener(hub);
        PanelRegistry.register(owner, "example:p", PanelSpec.builder("P").access(p -> allowed.get()).render(ctx -> {
            Panel page = Panel.page("P").children(
                    Ui.toggle("hud", hudLabel).value(true).onChange((a, v) -> Result.ok()),
                    Ui.slider("range", "R").range(4, 64, 4).value(16).onChange((a, v) -> Result.ok()),
                    Ui.button("off").label("Aus").disabled(true).onClick(a -> { clicks.incrementAndGet(); return Result.ok(); }),
                    Ui.button("boom").label("Fehler").onClick(a -> { throw new IllegalStateException("bug"); }));
            if (showButton.get()) page.child(Ui.button("rm").label("Entfernen").onClick(a -> {
                clicks.incrementAndGet();
                showButton.set(false);
                a.refresh();
                return Result.ok("entfernt");
            }));
            return page;
        }).build());
        hello(player);
        hello(other);
        hub.openNative(player, PanelRegistry.find("example:p").orElseThrow(), "", 0);
        session = ((PanelMessage.Open) sent.getLast()).session();
        sent.clear();
    }

    @AfterEach
    void tearDown() { PanelRegistry.clear(); }

    static Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        return p;
    }

    void hello(Player p) { channel.onMessage(p, PanelMessage.encode(new PanelMessage.Hello(1, 1, 0, 0))); }

    void act(Player p, int session, int seq, String id, int kind, String value) {
        channel.onMessage(p, PanelMessage.encode(new PanelMessage.Action(session, seq, id, kind, value)));
    }

    int lastStatus() { return ((PanelMessage.Result) sent.stream().filter(m -> m instanceof PanelMessage.Result).reduce((a, b) -> b).orElseThrow()).status(); }

    @Test
    void givenValidToggle_whenChanged_thenOk() {
        act(player, session, 1, "hud", KIND_CHANGE, "false");
        assertEquals(STATUS_OK, lastStatus());
    }

    @Test
    void givenForeignSession_whenAction_thenStale() {
        act(other, session, 1, "rm", KIND_CLICK, "");
        assertEquals(0, clicks.get());
        act(player, session + 99, 1, "rm", KIND_CLICK, "");
        assertEquals(STATUS_STALE, lastStatus());
        assertEquals(0, clicks.get());
    }

    @Test
    void givenUnknownOrDisabledElement_whenClicked_thenStale() {
        act(player, session, 1, "nope", KIND_CLICK, "");
        assertEquals(STATUS_STALE, lastStatus());
        act(player, session, 2, "off", KIND_CLICK, "");
        assertEquals(STATUS_STALE, lastStatus());
        assertEquals(0, clicks.get());
    }

    @Test
    void givenOutOfBoundsOrWrongKind_whenAction_thenDenied() {
        act(player, session, 1, "range", KIND_CHANGE, "18");
        assertEquals(STATUS_DENIED, lastStatus());
        act(player, session, 2, "range", KIND_CHANGE, "68");
        assertEquals(STATUS_DENIED, lastStatus());
        act(player, session, 3, "hud", KIND_CLICK, "");
        assertEquals(STATUS_DENIED, lastStatus());
    }

    @Test
    void givenDuplicateSeq_whenRepeated_thenHandledOnce() {
        act(player, session, 5, "rm", KIND_CLICK, "");
        act(player, session, 5, "rm", KIND_CLICK, "");
        assertEquals(1, clicks.get());
        assertEquals(1, sent.stream().filter(m -> m instanceof PanelMessage.Result).count());
    }

    @Test
    void givenFlood_whenOverRate_thenRateLimited() {
        for (int i = 1; i <= 60; i++) act(player, session, i, "hud", KIND_CHANGE, i % 2 == 0 ? "true" : "false");
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Result r && r.status() == STATUS_RATE));
    }

    @Test
    void givenAccessRevoked_whenAction_thenDenied() {
        allowed.set(false);
        act(player, session, 1, "rm", KIND_CLICK, "");
        assertEquals(STATUS_DENIED, lastStatus());
        assertEquals(0, clicks.get());
    }

    @Test
    void givenHandlerThrows_whenAction_thenErrorResult() {
        act(player, session, 1, "boom", KIND_CLICK, "");
        assertEquals(STATUS_ERROR, lastStatus());
    }

    /** Review Focus 4: Refresh entfernt den Knopf; ein zweiter Klick ist veraltet, der Handler läuft nicht erneut. */
    @Test
    void givenElementRemovedByRefresh_whenClicked_thenStaleAndHandlerNotCalled() {
        act(player, session, 1, "rm", KIND_CLICK, "");
        assertEquals(STATUS_OK, lastStatus());
        nanos.addAndGet(200_000_000L);
        hub.tick();
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Patch
                || m instanceof PanelMessage.Open o && (o.flags() & FLAG_KEEP_STATE) != 0), "refresh sent a patch or a keep-state open");
        act(player, session, 2, "rm", KIND_CLICK, "");
        assertEquals(STATUS_STALE, lastStatus());
        assertEquals(1, clicks.get());
    }

    /** Review Focus 5: Plugin wird deaktiviert – Sitzung schließt, Aktion geht ins Leere. */
    @Test
    void givenOwnerDisabled_whenAction_thenSessionClosedAndStale() {
        when(owner.isEnabled()).thenReturn(false);
        act(player, session, 1, "rm", KIND_CLICK, "");
        assertEquals(STATUS_STALE, lastStatus());
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Close c && c.session() == session));
        assertEquals(0, clicks.get());
        assertEquals(0, hub.openSessions(player.getUniqueId()));
    }

    @Test
    void givenRelease_whenOwnerDisabled_thenAllItsSessionsClose() {
        hub.release(owner);
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Close c && c.session() == session && c.reason() == CLOSED_SERVER));
        assertTrue(PanelRegistry.find("example:p").isEmpty());
    }

    /** Final Review Important 5: erfundene Element-IDs legen keine Buckets an; STALE-Antworten sind ratenbegrenzt. */
    @Test
    void givenForgedElementIds_whenFlooded_thenNoBucketGrowthAndBoundedReplies() {
        for (int i = 1; i <= 5_000; i++) {
            act(player, session, i, "forged." + i, KIND_CLICK, "");
            nanos.addAndGet(1_000_000L);   // 5 s
        }
        assertTrue(hub.elementBuckets(player.getUniqueId()) <= 5, "buckets only for elements of the document: " + hub.elementBuckets(player.getUniqueId()));
        sent.clear();
        for (int i = 1; i <= 1_000; i++) act(player, session + 77, i, "rm", KIND_CLICK, "");   // fremde Sitzung, ohne Zeit
        long replies = sent.stream().filter(m -> m instanceof PanelMessage.Result).count();
        assertTrue(replies <= 60, "replies to a flood are bounded: " + replies);
    }

    /** Final Review Important 9: gesperrtes Feld blockiert das Formular nicht und kommt nicht beim Handler an. */
    @Test
    void givenFormWithDisabledField_whenSubmittedWithIt_thenOkWithoutTheField() {
        java.util.concurrent.atomic.AtomicReference<Object> got = new java.util.concurrent.atomic.AtomicReference<>();
        PanelRegistry.register(owner, "example:f", PanelSpec.builder("F").render(ctx -> Panel.page("F").children(
                Ui.form("pay", "OK").children(Ui.input("pay.a", "A"), Ui.input("pay.locked", "L").value("fest").disabled(true))
                        .onSubmit((a, v) -> { got.set(v); return Result.ok(); }))).build());
        hub.openNative(player, PanelRegistry.find("example:f").orElseThrow(), "", 0);
        int f = ((PanelMessage.Open) sent.getLast()).session();
        act(player, f, 1, "pay", KIND_SUBMIT, "{\"pay.a\":\"x\",\"pay.locked\":\"anders\"}");
        assertEquals(STATUS_OK, lastStatus());
        assertEquals(java.util.Map.of("pay.a", "x"), got.get());
    }

    /** Final Review Important 2: der Client meldet eine rev-Lücke → nächster Tick schickt das volle Dokument. */
    @Test
    void givenResyncError_whenTicked_thenFullKeepStateOpen() {
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Error(session, "", ERR_INVALID, "resync: rev gap: want 3 got 4")));
        nanos.addAndGet(200_000_000L);
        hub.tick();
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Open o && o.session() == session && (o.flags() & FLAG_KEEP_STATE) != 0),
                "full document although nothing changed: " + sent);
        sent.clear();
        nanos.addAndGet(200_000_000L);
        hub.tick();
        assertTrue(sent.isEmpty(), "only once");
    }

    /** Minor: schließt der Server die oberste Sitzung, wird die darunter (wieder sichtbar) frisch gerendert. */
    @Test
    void givenServerClosesTop_whenTicked_thenNewTopRefreshed() {
        PanelRegistry.register(owner, "example:q", PanelSpec.builder("Q").render(ctx -> Panel.page("Q")).build());
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Request(1, "example:q", "")));
        sent.clear();
        hub.close(player, "example:q");
        showButton.set(false);   // Panel p sieht jetzt anders aus
        nanos.addAndGet(200_000_000L);
        hub.tick();
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Patch pa && pa.session() == session
                || m instanceof PanelMessage.Open o && o.session() == session), "panel below refreshed: " + sent);
    }

    /** Re-Review Minor 2/3: resync gilt für jede Sitzung im Stapel und höchstens etwa 1×/s je Sitzung. */
    @Test
    void givenResyncFloodAndNonTopSession_whenTicked_thenAtMostOneFullPerSecond() {
        PanelRegistry.register(owner, "example:q", PanelSpec.builder("Q").render(ctx -> Panel.page("Q")).build());
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Request(1, "example:q", "")));
        int q = ((PanelMessage.Open) sent.getLast()).session();
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Error(session, "", ERR_INVALID, "resync: rev gap")));   // nicht oben
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Closed(q, CLOSED_PLAYER)));
        sent.clear();
        nanos.addAndGet(200_000_000L);
        hub.tick();
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Open o && o.session() == session && (o.flags() & FLAG_KEEP_STATE) != 0), "non-top resync remembered");
        long fulls = 0;
        for (int i = 0; i < 10; i++) {
            nanos.addAndGet(110_000_000L);   // 1,1 s insgesamt, je Tick ein resync
            channel.onMessage(player, PanelMessage.encode(new PanelMessage.Error(session, "", ERR_INVALID, "resync: x" + i)));
            hub.tick();
        }
        fulls = sent.stream().filter(m -> m instanceof PanelMessage.Open o && o.session() == session).count();
        assertTrue(fulls <= 3, "forced full renders ~1/s: " + fulls);
    }

    /** Re-Review Minor 6: volle OPENs erhöhen {@code rev}, damit ein verlorenes OPEN beim nächsten Patch als Lücke auffällt. */
    @Test
    void givenFullKeepStateOpen_whenNextPatch_thenRevSkipsOne() {
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Error(session, "", ERR_INVALID, "resync: rev gap")));
        nanos.addAndGet(200_000_000L);
        hub.tick();
        sent.clear();
        hudLabel = "HUD 2";   // nur eine Beschriftung ändert sich → Patch
        hub.refresh(player, "example:p");
        nanos.addAndGet(1_200_000_000L);
        hub.tick();
        PanelMessage.Patch p = (PanelMessage.Patch) sent.stream().filter(m -> m instanceof PanelMessage.Patch).findFirst().orElseThrow();
        assertEquals(3, p.rev(), "OPEN rev 1, full OPEN bumped to 2, patch 3");
    }
}
