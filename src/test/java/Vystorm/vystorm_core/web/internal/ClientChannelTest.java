package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.WebLogin;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.WebMessage;
import Vystorm.vystorm_core.web.internal.clientproto.Wire;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientChannelTest {
    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    static final int CAPS = Protocol.CAP_BROWSER_OVERLAY | Protocol.CAP_HUD | Protocol.CAP_EXTERNAL_BROWSER;

    final List<WebMessage> sent = new ArrayList<>();
    final List<String> logLines = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong();
    String publicUrl = "https://web.vystorm.net";
    Set<String> modules = Set.of("skills");
    boolean mayOpen = true;
    int issued;
    Player player;
    ClientChannel channel;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Esoren");
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logLines.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        channel = channel(new ClientChannel.Settings(true, true, true, "skills", 10), logger);
    }

    ClientChannel channel(ClientChannel.Settings settings, Logger logger) {
        return new ClientChannel(settings, channelLogins(), (p, name, data) -> {
            assertEquals(Protocol.CHANNEL_WEB, name);
            sent.add(WebMessage.decodeClientbound(data));
        }, logger, nanos::get);
    }

    ClientChannel.Logins channelLogins() {
        return new ClientChannel.Logins() {
            @Override public String publicUrl() { return publicUrl; }
            @Override public boolean registered(String module) { return modules.contains(module); }
            @Override public Optional<WebLogin> issue(Player p, String module) {
                if (!mayOpen) return Optional.empty();
                issued++;
                return Optional.of(new WebLogin(TOKEN, publicUrl, module, Instant.now().plusSeconds(60)));
            }
            @Override public String title(String module) { return "Talentbaum"; }
        };
    }

    void receive(WebMessage message) { channel.onMessage(player, WebMessage.encode(message)); }

    void hello() { receive(new WebMessage.Hello(1, 1, "0.3.0", "fabric", CAPS)); }

    @Test
    void givenHello_whenReceived_thenWelcomeWithBaseUrlOnce() {
        hello();
        hello();
        assertEquals(1, sent.size());
        WebMessage.Welcome w = (WebMessage.Welcome) sent.getFirst();
        assertEquals("https://web.vystorm.net", w.webBase());
        assertEquals("skills", w.hotkeyModule());
        assertEquals(Protocol.SERVER_OVERLAY | Protocol.SERVER_UI, w.serverFlags());
        assertTrue(channel.hasClient(player.getUniqueId()));
        assertTrue(channel.uiEnabled(player.getUniqueId()));
        assertFalse(channel.overlayAvailable(player.getUniqueId()), "no consent yet: no token fetched in this connection");
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_PRELOAD));
        assertTrue(channel.overlayAvailable(player.getUniqueId()));
        assertEquals(CAPS, channel.capabilities(player.getUniqueId()));
    }

    @Test
    void givenWebOff_whenHello_thenEmptyBaseAndNoOverlay() {
        publicUrl = "";
        hello();
        WebMessage.Welcome w = (WebMessage.Welcome) sent.getFirst();
        assertEquals("", w.webBase());
        assertEquals(Protocol.SERVER_UI, w.serverFlags(), "native elements work without the web platform");
        assertFalse(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenUnsupportedVersionRange_whenHello_thenSilent() {
        receive(new WebMessage.Hello(Protocol.VERSION + 1, Protocol.VERSION + 2, "9.0", "fabric", CAPS));
        assertTrue(sent.isEmpty());
        assertFalse(channel.hasClient(player.getUniqueId()));
    }

    @Test
    void givenOpenRequestWithPermission_whenReceived_thenOpenWithToken() {
        hello();
        receive(new WebMessage.OpenRequest(7, "skills", Protocol.TARGET_OVERLAY));
        WebMessage.Open open = (WebMessage.Open) sent.get(1);
        assertEquals(7, open.requestId());
        assertEquals(TOKEN, open.token());
        assertTrue(open.ttlSeconds() >= 58 && open.ttlSeconds() <= 60);
        assertEquals("Talentbaum", open.title());
    }

    @Test
    void givenOpenRequestBeforeHello_whenReceived_thenIgnored() {
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_OVERLAY));
        assertTrue(sent.isEmpty());
        assertEquals(0, issued);
    }

    @Test
    void givenMissingRightsOrModule_whenOpenRequest_thenDeniedWithReason() {
        hello();
        receive(new WebMessage.OpenRequest(1, "unknown", Protocol.TARGET_OVERLAY));
        assertEquals(Protocol.DENY_UNKNOWN_MODULE, ((WebMessage.Denied) sent.get(1)).reason());
        mayOpen = false;
        receive(new WebMessage.OpenRequest(2, "skills", Protocol.TARGET_OVERLAY));
        assertEquals(Protocol.DENY_NO_PERMISSION, ((WebMessage.Denied) sent.get(2)).reason());
        publicUrl = "";
        receive(new WebMessage.OpenRequest(3, "skills", Protocol.TARGET_OVERLAY));
        assertEquals(Protocol.DENY_WEB_OFF, ((WebMessage.Denied) sent.get(3)).reason());
    }

    @Test
    void givenPreloadDisabled_whenPreloadRequest_thenDenied() {
        channel = channel(new ClientChannel.Settings(true, true, false, "skills", 10), null);
        hello();
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_PRELOAD));
        assertInstanceOf(WebMessage.Denied.class, sent.get(1));
        assertEquals(0, issued);
    }

    @Test
    void givenManyRequests_whenBurstExceeded_thenRateLimited() {
        hello();
        for (int i = 1; i <= 6; i++) receive(new WebMessage.OpenRequest(i, "skills", Protocol.TARGET_OVERLAY));
        assertEquals(3, issued);
        assertEquals(Protocol.DENY_RATE_LIMITED, ((WebMessage.Denied) sent.getLast()).reason());
    }

    @Test
    void givenMalformedMessages_whenTooMany_thenPlayerIgnoredUntilQuit() {
        for (int i = 0; i <= ClientChannel.MAX_MALFORMED; i++) channel.onMessage(player, new byte[]{0x01, (byte) 0xFF});
        hello();
        assertTrue(sent.isEmpty());
        assertTrue(logLines.stream().anyMatch(l -> l.contains("malformed")));
        channel.quit(player.getUniqueId());
        hello();
        assertEquals(1, sent.size());
    }

    @Test
    void givenWrongDirectionOrTrailingBytes_whenReceived_thenIgnored() {
        channel.onMessage(player, WebMessage.encode(new WebMessage.Welcome(1, "https://evil.net", "", 1)));
        byte[] hello = WebMessage.encode(new WebMessage.Hello(1, 1, "0.3.0", "fabric", CAPS));
        byte[] trailing = java.util.Arrays.copyOf(hello, hello.length + 1);
        channel.onMessage(player, trailing);
        channel.onMessage(player, new byte[2048]);
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenClientWithOverlay_whenShow_thenShowSentAndValidated() {
        assertFalse(channel.show(player, "skills", ""), "no client yet");
        hello();
        assertFalse(channel.show(player, "skills", ""), "not consented yet");
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_PRELOAD));
        assertTrue(channel.show(player, "skills", "/app/skills#node/12"));
        assertEquals(new WebMessage.Show("skills", "/app/skills#node/12"), sent.getLast());
        assertFalse(channel.show(player, "skills", "https://evil.net/"));
        assertFalse(channel.show(player, "unknown", ""));
        assertTrue(channel.close(player, "skills"));
        assertEquals(new WebMessage.Close("skills"), sent.getLast());
    }

    @Test
    void givenClientWithoutBrowser_whenShow_thenFalse() {
        receive(new WebMessage.Hello(1, 1, "0.3.0", "fabric", Protocol.CAP_HUD));
        assertFalse(channel.overlayAvailable(player.getUniqueId()));
        assertFalse(channel.show(player, "skills", ""));
    }

    @Test
    void givenClientState_whenReceived_thenStored() {
        hello();
        receive(new WebMessage.ClientState(Protocol.STATE_UI_HIDDEN));
        assertEquals(Protocol.STATE_UI_HIDDEN, channel.clientState(player.getUniqueId()));
    }

    @Test
    void givenAnyFlow_whenLogging_thenTokenNeverLogged() {
        hello();
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_OVERLAY));
        for (int i = 0; i <= ClientChannel.MAX_MALFORMED; i++) channel.onMessage(player, new byte[]{0x03});
        assertTrue(logLines.stream().noneMatch(l -> l.contains(TOKEN)));
        assertFalse(sent.get(1).toString().contains(TOKEN));
    }

    @Test
    void givenWireHelpers_whenUnknownId_thenIgnored() {
        channel.onMessage(player, new Wire.Writer().writeVarInt(0x7E).toByteArray());
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenDeniedTokenRequest_whenOverlayAvailable_thenStillFalse() {
        hello();
        mayOpen = false;
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_OVERLAY));
        assertFalse(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenUiDisabled_whenHello_thenNoUiFlag() {
        channel = channel(new ClientChannel.Settings(true, true, true, "skills", 10, false, ClientChannel.QuitSessions.CLIENT), null);
        hello();
        assertEquals(Protocol.SERVER_OVERLAY, ((WebMessage.Welcome) sent.getFirst()).serverFlags());
        assertFalse(channel.uiEnabled(player.getUniqueId()));
    }

    @Test
    void givenHooks_whenHelloAndStateChanges_thenReadyOnceAndStateOnlyOnChange() {
        List<String> calls = new ArrayList<>();
        ClientChannel.Logins logins = new ClientChannel.Logins() {
            @Override public String publicUrl() { return publicUrl; }
            @Override public boolean registered(String module) { return true; }
            @Override public Optional<WebLogin> issue(Player p, String module) { return Optional.empty(); }
            @Override public String title(String module) { return ""; }
        };
        channel = new ClientChannel(new ClientChannel.Settings(true, true, true, "skills", 10), logins, (p, n, d) -> sent.add(WebMessage.decodeClientbound(d)),
                null, nanos::get, new ClientChannel.Hooks() {
            @Override public void ready(Player p, int caps, String version, String loader) { calls.add("ready " + caps + " " + version + " " + loader); }
            @Override public void state(Player p, int previous, int state) { calls.add("state " + previous + "->" + state); }
        });
        receive(new WebMessage.ClientState(Protocol.STATE_UI_HIDDEN));   // vor HELLO: ignoriert
        hello();
        hello();
        receive(new WebMessage.ClientState(Protocol.STATE_UI_HIDDEN));
        nanos.addAndGet(1_000_000_000L);
        receive(new WebMessage.ClientState(Protocol.STATE_UI_HIDDEN));
        nanos.addAndGet(1_000_000_000L);
        receive(new WebMessage.ClientState(0));
        assertEquals(List.of("ready " + CAPS + " 0.3.0 fabric", "state 0->1", "state 1->0"), calls);
    }

    @Test
    void givenQuitSessionsSetting_whenParsed_thenClientByDefault() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("enabled", true);
        assertEquals(ClientChannel.QuitSessions.CLIENT, ClientChannel.Settings.from(yaml).quitSessions());
        yaml.set("end-sessions-on-quit", "all");
        assertEquals(ClientChannel.QuitSessions.ALL, ClientChannel.Settings.from(yaml).quitSessions());
        yaml.set("end-sessions-on-quit", "none");
        assertEquals(ClientChannel.QuitSessions.NONE, ClientChannel.Settings.from(yaml).quitSessions());
        yaml.set("ui.enabled", false);
        assertFalse(ClientChannel.Settings.from(yaml).ui());
    }

    void helloV2() { receive(new WebMessage.Hello(1, 2, "0.4.0", "fabric", CAPS)); }

    @Test
    void givenClientOfferingVersionTwo_whenHello_thenWelcomeVersionTwo() {
        helloV2();
        assertEquals(2, ((WebMessage.Welcome) sent.getFirst()).version());
        assertEquals(2, channel.version(player.getUniqueId()));
    }

    @Test
    void givenVersionOneClient_whenHello_thenWelcomeVersionOne() {
        hello();
        assertEquals(1, ((WebMessage.Welcome) sent.getFirst()).version());
    }

    @Test
    void givenTrustBit_whenNoTokenYet_thenOverlayAvailableAndShowSent() {
        helloV2();
        assertFalse(channel.overlayAvailable(player.getUniqueId()), "no trust reported yet");
        assertFalse(channel.show(player, "skills", "/app/skills"));
        receive(new WebMessage.ClientState(Protocol.STATE_ORIGIN_TRUSTED));
        assertTrue(channel.overlayAvailable(player.getUniqueId()), "trusted + overlay capability, even without preload token");
        assertEquals(0, issued, "no token issued just for the trust signal");
        assertTrue(channel.show(player, "skills", "/app/skills"));
        assertEquals(new WebMessage.Show("skills", "/app/skills"), sent.getLast());
        // Die Anmeldung passiert danach wie immer über OPEN_REQUEST → frisches Einmal-Token.
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_OVERLAY));
        assertEquals(1, issued);
        receive(new WebMessage.ClientState(0));
        assertFalse(channel.overlayAvailable(player.getUniqueId()), "trust withdrawn: normal menu again");
    }

    @Test
    void givenVersionTwoWithoutTrustBit_whenTokenFetched_thenStillNotAvailable() {
        helloV2();
        receive(new WebMessage.ClientState(0));
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_EXTERNAL));
        assertEquals(1, issued);
        assertFalse(channel.overlayAvailable(player.getUniqueId()), "client says it would not follow SHOW");
    }

    @Test
    void givenVersionOneClientWithBit_whenReceived_thenIgnoredUntilToken() {
        hello();
        receive(new WebMessage.ClientState(Protocol.STATE_ORIGIN_TRUSTED));
        assertFalse(channel.overlayAvailable(player.getUniqueId()), "bit has no meaning in protocol 1");
        receive(new WebMessage.OpenRequest(1, "skills", Protocol.TARGET_PRELOAD));
        assertTrue(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenTrustBitWithoutBrowserCapability_whenChecked_thenNotAvailable() {
        receive(new WebMessage.Hello(1, 2, "0.4.0", "fabric", Protocol.CAP_HUD));
        receive(new WebMessage.ClientState(Protocol.STATE_ORIGIN_TRUSTED));
        assertFalse(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenTrustBitButOverlayOff_whenChecked_thenNotAvailable() {
        channel = channel(new ClientChannel.Settings(true, false, true, "skills", 10), null);
        helloV2();
        receive(new WebMessage.ClientState(Protocol.STATE_ORIGIN_TRUSTED));
        assertFalse(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenStateFlood_whenRateLimited_thenLatestStateStillStored() {
        helloV2();
        for (int i = 0; i < 20; i++) receive(new WebMessage.ClientState(i % 2 == 0 ? Protocol.STATE_UI_HIDDEN : 0));
        receive(new WebMessage.ClientState(Protocol.STATE_ORIGIN_TRUSTED));
        assertEquals(Protocol.STATE_ORIGIN_TRUSTED, channel.clientState(player.getUniqueId()));
        assertTrue(channel.overlayAvailable(player.getUniqueId()));
    }

    @Test
    void givenPanelsHook_whenHelloWithCapPanels_thenWelcomeAnnouncesPanels() {
        channel = new ClientChannel(new ClientChannel.Settings(true, true, true, "skills", 10), channelLogins(), (p, name, data) -> sent.add(WebMessage.decodeClientbound(data)),
                Logger.getAnonymousLogger(), nanos::get, new ClientChannel.Hooks() {
                    @Override public boolean panels(Player p, int capabilities) { return (capabilities & Protocol.CAP_PANELS) != 0; }
                });
        receive(new WebMessage.Hello(1, 2, "0.8.0", "fabric", Protocol.CAP_EXTERNAL_BROWSER | Protocol.CAP_PANELS));
        WebMessage.Welcome w = (WebMessage.Welcome) sent.getFirst();
        assertTrue((w.serverFlags() & Protocol.SERVER_PANELS) != 0);
    }
}
