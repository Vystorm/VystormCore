package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.26.4: {@link Result#pending()} + {@link PanelAction#complete}, {@link PanelAction#closeAndThen}. */
class PanelExtrasTest {
    final List<PanelMessage> sent = new ArrayList<>();
    final List<Object> events = new ArrayList<>();
    final List<Runnable> mainQueue = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final AtomicReference<PanelAction> lastAction = new AtomicReference<>();
    final AtomicInteger opened = new AtomicInteger();
    Player player;
    Plugin owner;
    PanelChannel channel;
    PanelHub hub;
    int session;

    @BeforeEach
    void setUp() {
        PanelRegistry.clear();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Esoren");
        when(player.isOnline()).thenReturn(true);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        when(owner.getName()).thenReturn("Example");
        channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> Protocol.CAP_PANELS,
                (p, data) -> sent.add(PanelMessage.decodeClientbound(data)), Logger.getAnonymousLogger(), nanos::get);
        hub = new PanelHub(channel, nanos::get, Logger.getAnonymousLogger(), events::add, Runnable::run, mainQueue::add);
        channel.listener(hub);
        PanelRegistry.register(owner, "example:p", PanelSpec.builder("P").render(ctx -> Panel.page("P").children(
                Ui.button("save").label("Speichern").onClick(a -> {
                    lastAction.set(a);
                    return Result.pending();
                }),
                Ui.button("now").label("Sofort").onClick(a -> {
                    a.complete(Result.ok("zu früh"));   // vor dem Zurückgeben: wird ignoriert, Ergebnis ist "ok"
                    return Result.ok();
                }),
                Ui.button("sync").label("Sync").onClick(a -> {
                    a.complete(Result.ok("gleich da"));
                    return Result.pending();
                }),
                Ui.button("dialog").label("Dialog").onClick(a -> {
                    a.closeAndThen(p -> opened.incrementAndGet());
                    return Result.ok();
                }))).build());
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Hello(1, 1, 0, 0)));
        hub.openNative(player, PanelRegistry.find("example:p").orElseThrow(), "", 0);
        session = ((PanelMessage.Open) sent.getLast()).session();
        sent.clear();
    }

    @AfterEach
    void tearDown() { PanelRegistry.clear(); }

    void click(int seq, String id) {
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Action(session, seq, id, KIND_CLICK, "")));
    }

    List<PanelMessage.Result> results() {
        return sent.stream().filter(m -> m instanceof PanelMessage.Result).map(m -> (PanelMessage.Result) m).toList();
    }

    void runMainQueue() {
        List<Runnable> now = List.copyOf(mainQueue);
        mainQueue.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void givenPendingResult_whenCompletedLater_thenResultSentOnceOnMainThread() {
        click(1, "save");
        assertTrue(results().isEmpty(), "nothing sent while pending");
        PanelAction action = lastAction.get();
        action.complete(Result.error("DB down"));
        assertTrue(results().isEmpty(), "complete hops to the server thread");
        runMainQueue();
        assertEquals(1, results().size());
        assertEquals(STATUS_ERROR, results().getFirst().status());
        assertEquals(1, results().getFirst().seq());
        action.complete(Result.ok());
        runMainQueue();
        assertEquals(1, results().size(), "only the first complete counts");
    }

    @Test
    void givenPendingResult_whenNeverCompleted_thenErrorAfterTimeout() {
        click(1, "save");
        nanos.addAndGet((Result.PENDING_TIMEOUT_SECONDS - 1) * 1_000_000_000L);
        hub.tick();
        assertTrue(results().isEmpty());
        nanos.addAndGet(2_000_000_000L);
        hub.tick();
        assertEquals(1, results().size());
        assertEquals(STATUS_ERROR, results().getFirst().status());
        lastAction.get().complete(Result.ok());
        runMainQueue();
        assertEquals(1, results().size(), "late complete is ignored");
    }

    @Test
    void givenCompleteBeforeHandlerReturns_whenPending_thenSentRightAway() {
        click(1, "sync");
        runMainQueue();
        assertEquals(1, results().size());
        assertEquals(STATUS_OK, results().getFirst().status());
        click(2, "now");
        runMainQueue();
        assertEquals(2, results().size(), "complete without pending is ignored, the normal result is sent");
    }

    @Test
    void givenCloseAndThen_whenClicked_thenAllPanelsClosedAndNextRunsTwoTicksLater() {
        click(1, "dialog");
        assertEquals(STATUS_OK, results().getFirst().status());
        PanelMessage.Close close = (PanelMessage.Close) sent.stream().filter(m -> m instanceof PanelMessage.Close).findFirst().orElseThrow();
        assertEquals(0, close.session(), "one P_CLOSE for all sessions (old clients never close a foreign screen on session 0)");
        assertEquals(0, hub.openSessions(player.getUniqueId()));
        assertEquals(0, opened.get(), "not in the same tick");
        runMainQueue();
        assertEquals(0, opened.get(), "not one tick later");
        runMainQueue();
        assertEquals(1, opened.get());
    }

    @Test
    void givenCloseAndThen_whenPlayerLeft_thenNextNotRun() {
        click(1, "dialog");
        when(player.isOnline()).thenReturn(false);
        runMainQueue();
        runMainQueue();
        assertEquals(0, opened.get());
    }
}
