package Vystorm.vystorm_core.panel.internal;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelMessage;
import Vystorm.vystorm_core.web.internal.clientproto.panel.Transfer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelChannelTest {
    final List<PanelMessage> sent = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    int caps = Protocol.CAP_PANELS;
    Player player;
    PanelChannel channel;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Esoren");
        channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> caps, (p, data) -> sent.add(PanelMessage.decodeClientbound(data)),
                Logger.getAnonymousLogger(), nanos::get);
        channel.listener(new PanelChannel.Listener() {
            @Override public void ready(Player p) { events.add("ready"); }
            @Override public void request(Player p, PanelMessage.Request r) { events.add("request " + r.panelId()); }
            @Override public void action(Player p, PanelMessage.Action a) { events.add("action " + a.elementId()); }
        });
    }

    void receive(PanelMessage m) { channel.onMessage(player, PanelMessage.encode(m)); }

    @Test
    void givenHello_whenReceivedTwice_thenOneWelcomeWithFeatureIntersection() {
        receive(new PanelMessage.Hello(1, 1, FEATURE_TIMER | FEATURE_GRAPH, 0));
        receive(new PanelMessage.Hello(1, 1, FEATURE_TIMER, 0));
        assertEquals(1, sent.size());
        PanelMessage.Welcome w = (PanelMessage.Welcome) sent.getFirst();
        assertEquals(1, w.schema());
        assertEquals(FEATURE_TIMER, w.features());
        assertEquals("vystorm_core:menu", w.hotkeyPanel());
        assertTrue(channel.ready(player.getUniqueId()));
        assertEquals(List.of("ready"), events);
    }

    @Test
    void givenClientWithoutPanelCapability_whenHello_thenSilent() {
        caps = Protocol.CAP_HUD;
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        assertTrue(sent.isEmpty());
        assertFalse(channel.ready(player.getUniqueId()));
    }

    @Test
    void givenUnsupportedSchema_whenHello_thenSilent() {
        receive(new PanelMessage.Hello(2, 3, 0, 0));
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenNoHello_whenRequest_thenIgnored() {
        receive(new PanelMessage.Request(1, "a:b", ""));
        assertTrue(events.isEmpty());
    }

    @Test
    void givenRequestFlood_whenOverRate_thenDenied() {
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        for (int i = 1; i <= 40; i++) receive(new PanelMessage.Request(i, "a:b", ""));
        long forwarded = events.stream().filter(e -> e.startsWith("request")).count();
        assertEquals(6, forwarded, "2/s, burst 6 (Final Review Important 6: every request is a full render)");
        assertTrue(sent.stream().anyMatch(m -> m instanceof PanelMessage.Denied d && d.reason() == Protocol.DENY_RATE_LIMITED));
    }

    @Test
    void givenMalformedMessages_whenOver20_thenIgnoredUntilQuit() {
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        byte[] wrongDirection = PanelMessage.encode(new PanelMessage.Close(0, CLOSED_SERVER));
        for (int i = 0; i < 21; i++) channel.onMessage(player, wrongDirection);
        receive(new PanelMessage.Action(1, 1, "x", KIND_CLICK, ""));
        assertFalse(events.contains("action x"));
        channel.quit(player.getUniqueId());
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        assertTrue(channel.ready(player.getUniqueId()));
    }

    @Test
    void givenQueuedChunks_whenFlushed_thenAtMostEightPerTick() {
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        sent.clear();
        byte[] raw = new byte[400_000];
        new java.util.Random(3).nextBytes(raw);
        List<PanelMessage> parts = Transfer.split(1, TRANSFER_BLOB, raw);
        assertTrue(parts.size() > 9);
        assertTrue(channel.queue(player, parts));
        channel.flush();
        assertEquals(8, sent.size());
        while (sent.size() < parts.size()) channel.flush();
        assertEquals(parts, sent);
    }

    @Test
    void givenQueuedTransfer_whenSmallMessageSent_thenItWaitsBehindChunks() {
        receive(new PanelMessage.Hello(1, 1, 0, 0));
        sent.clear();
        byte[] raw = new byte[200_000];
        new java.util.Random(4).nextBytes(raw);
        channel.queue(player, Transfer.split(7, TRANSFER_DOCUMENT, raw));
        channel.send(player, new PanelMessage.Result(1, 1, STATUS_OK, "{}"));
        while (sent.isEmpty() || !(sent.getLast() instanceof PanelMessage.Result)) channel.flush();
        assertInstanceOf(PanelMessage.Result.class, sent.getLast());
        assertTrue(sent.stream().filter(m -> m instanceof PanelMessage.Chunk).count() > 1);
    }
}
