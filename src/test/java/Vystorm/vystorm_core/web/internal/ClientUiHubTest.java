package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.ClientElement;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.UiMessage;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientUiHubTest {
    static final int ALL = Protocol.CAP_HUD | Protocol.CAP_TOASTS | Protocol.CAP_MARKERS | Protocol.CAP_TOOLTIPS;

    final List<UiMessage> sent = new ArrayList<>();
    final List<String> rejected = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    Player player;
    ClientUiHub hub;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        hub = hub(ClientUiHub.Settings.defaults());
    }

    ClientUiHub hub(ClientUiHub.Settings settings) {
        return new ClientUiHub(settings, (p, data) -> sent.add(UiMessage.decodeClientbound(data)),
                (p, id, code, detail) -> rejected.add(id + "/" + code + "/" + detail), nanos::get);
    }

    static JsonObject bar(String id, double value) {
        return withId(ClientElement.bar("x").anchor(ClientElement.Anchor.BOTTOM).offset(0, 52).value(value).max(1000).label("Lv 1").segments(10).toJson(), id);
    }

    static JsonObject withId(JsonObject o, String id) {
        o.addProperty("id", id);
        return o;
    }

    static JsonObject fields(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    void ticks(int n) { for (int i = 0; i < n; i++) { nanos.addAndGet(50_000_000L); hub.flush(); } }

    @Test
    void givenNoClient_whenPut_thenNothingSent() {
        assertEquals(ClientUiHub.Status.NO_CLIENT, hub.put(player, bar("skills:xp", 1)).status());
        ticks(2);
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenClientWithoutHud_whenPutBar_thenNoCapabilityButToastWorks() {
        hub.connected(player, Protocol.CAP_TOASTS);
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.put(player, bar("skills:xp", 1)).status());
        assertTrue(hub.toast(player, ClientElement.toast("Level 2").style(ClientElement.Toast.Style.SUCCESS).toJson()).ok());
        ticks(1);
        assertEquals(1, sent.size());
        assertInstanceOf(UiMessage.Toast.class, sent.getFirst());
    }

    @Test
    void givenValidElement_whenPut_thenSentNextTickAndValidByClientSchema() {
        hub.connected(player, ALL);
        assertEquals(ClientUiHub.Status.OK, hub.put(player, bar("skills:xp", 340)).status());
        assertTrue(sent.isEmpty(), "sent in the tick, not immediately");
        ticks(1);
        UiMessage.Put put = (UiMessage.Put) sent.getFirst();
        assertEquals("skills:xp", UiSchema.element(UiSchema.parseObject(put.json(), Protocol.MAX_ELEMENT_JSON_BYTES)).id());
        assertTrue(hub.has(player.getUniqueId(), "skills:xp"));
        assertEquals(ClientUiHub.Status.UNCHANGED, hub.put(player, bar("skills:xp", 340)).status(), "same element again");
    }

    @Test
    void givenInvalidElement_whenPut_thenInvalidWithFieldName() {
        hub.connected(player, ALL);
        JsonObject bad = bar("skills:xp", 1);
        bad.addProperty("width", 9999);
        ClientUiHub.Result r = hub.put(player, bad);
        assertEquals(ClientUiHub.Status.INVALID, r.status());
        assertTrue(r.detail().contains("width"));
        JsonObject unknown = fields("{\"id\":\"skills:q\",\"type\":\"hologram\"}");
        assertEquals(ClientUiHub.Status.INVALID, hub.put(player, unknown).status());
        ticks(1);
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenManyPatchesInOneTick_whenFlushed_thenOneMergedPatch() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:xp", 1));
        ticks(1);
        sent.clear();
        hub.patch(player, "skills:xp", fields("{\"value\":2}"));
        hub.patch(player, "skills:xp", fields("{\"value\":3,\"label\":\"Lv 1 · 3\"}"));
        hub.patch(player, "skills:xp", fields("{\"value\":4}"));
        ticks(1);
        assertEquals(1, sent.size());
        JsonObject patch = fields(((UiMessage.Patch) sent.getFirst()).json());
        assertEquals("skills:xp", patch.get("id").getAsString());
        assertEquals(4, patch.get("value").getAsInt());
        assertEquals("Lv 1 · 3", patch.get("label").getAsString());
        assertFalse(patch.has("width"), "only changed fields");
    }

    @Test
    void givenPatchInterval_whenPatchedAgain_thenWaitsAndCoalesces() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:xp", 1));
        ticks(1);
        hub.patch(player, "skills:xp", fields("{\"value\":2}"));
        ticks(1);
        sent.clear();
        hub.patch(player, "skills:xp", fields("{\"value\":3}"));
        hub.flush();
        hub.patch(player, "skills:xp", fields("{\"value\":5}"));
        ticks(2);
        assertTrue(sent.isEmpty(), "within 5 ticks of the last patch");
        ticks(3);
        assertEquals(1, sent.size());
        assertEquals(5, fields(((UiMessage.Patch) sent.getFirst()).json()).get("value").getAsInt());
    }

    @Test
    void givenUnchangedOrUnknownPatch_whenSent_thenNothing() {
        hub.connected(player, ALL);
        assertEquals(ClientUiHub.Status.UNKNOWN_ID, hub.patch(player, "skills:xp", fields("{\"value\":2}")).status());
        hub.put(player, bar("skills:xp", 2));
        ticks(1);
        sent.clear();
        assertEquals(ClientUiHub.Status.UNCHANGED, hub.patch(player, "skills:xp", fields("{\"value\":2}")).status());
        assertEquals(ClientUiHub.Status.INVALID, hub.patch(player, "skills:xp", fields("{\"type\":\"panel\"}")).status());
        assertEquals(ClientUiHub.Status.INVALID, hub.patch(player, "skills:xp", fields("{\"height\":99}")).status());
        ticks(10);
        assertTrue(sent.isEmpty());
    }

    @Test
    void givenPutAfterPatchInSameTick_whenFlushed_thenPatchDroppedAndOrderKept() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:xp", 1));
        ticks(1);
        sent.clear();
        hub.patch(player, "skills:xp", fields("{\"value\":2}"));
        hub.put(player, bar("skills:xp", 9));
        hub.patch(player, "skills:xp", fields("{\"value\":10}"));
        ticks(1);
        assertEquals(2, sent.size());
        assertInstanceOf(UiMessage.Put.class, sent.get(0));
        assertEquals(10, fields(((UiMessage.Patch) sent.get(1)).json()).get("value").getAsInt());
    }

    @Test
    void givenHudLimit_whenMoreThan32_thenLimit() {
        hub.connected(player, ALL);
        for (int i = 0; i < 32; i++) assertTrue(hub.put(player, bar("skills:b" + i, 1)).ok());
        assertEquals(ClientUiHub.Status.LIMIT, hub.put(player, bar("skills:b32", 1)).status());
        assertTrue(hub.put(player, bar("skills:b0", 2)).ok(), "replacing is fine");
        hub.remove(player, List.of("skills:b1"));
        assertTrue(hub.put(player, bar("skills:b32", 1)).ok());
    }

    @Test
    void givenBurst_whenManyMessages_thenRateLimitedAcrossTicks() {
        hub = hub(new ClientUiHub.Settings(true, 5, 20, 10, 64, 384));
        hub.connected(player, ALL);
        for (int i = 0; i < 30; i++) hub.toast(player, ClientElement.toast("t" + i).toJson());
        hub.flush();
        assertEquals(10, sent.size(), "burst");
        ticks(1);                       // 50 ms bei 20/s = 1 Nachricht
        assertEquals(11, sent.size());
        ticks(40);
        assertEquals(30, sent.size(), "queue drains, nothing lost");
    }

    @Test
    void givenPerTickCap_whenBurstAvailable_thenAtMostPerTick() {
        hub = hub(new ClientUiHub.Settings(true, 5, 40, 120, 8, 384));
        hub.connected(player, ALL);
        for (int i = 0; i < 20; i++) hub.toast(player, ClientElement.toast("t" + i).toJson());
        hub.flush();
        assertEquals(8, sent.size());
    }

    @Test
    void givenQueueLimit_whenFull_thenQueueFull() {
        hub = hub(new ClientUiHub.Settings(true, 5, 1, 1, 1, 16));
        hub.connected(player, ALL);
        for (int i = 0; i < 16; i++) assertTrue(hub.toast(player, ClientElement.toast("t" + i).toJson()).ok());
        assertEquals(ClientUiHub.Status.QUEUE_FULL, hub.toast(player, ClientElement.toast("x").toJson()).status());
    }

    @Test
    void givenOwnerReleased_whenPrefixCleared_thenOnlyPlayersWithElementsGetClear() {
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        hub.connected(player, ALL);
        hub.connected(other, ALL);
        hub.put(player, bar("skills:xp", 1));
        hub.put(player, bar("quests:tracker", 1));
        ticks(1);
        sent.clear();
        hub.patch(player, "skills:xp", fields("{\"value\":3}"));
        hub.releasePrefix("skills:");
        ticks(10);
        assertEquals(List.of(new UiMessage.Clear("skills:")), sent, "pending patch dropped, no clear for the other player");
        assertFalse(hub.has(player.getUniqueId(), "skills:xp"));
        assertTrue(hub.has(player.getUniqueId(), "quests:tracker"));
    }

    @Test
    void givenRemove_whenKnownAndUnknownIds_thenOneRemoveWithKnownIds() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:a", 1));
        hub.put(player, bar("skills:b", 1));
        assertEquals(ClientUiHub.Status.UNCHANGED, hub.remove(player, List.of("skills:zz")).status());
        hub.remove(player, List.of("skills:a", "skills:zz", "skills:b"));
        ticks(1);
        assertEquals(new UiMessage.Remove(List.of("skills:a", "skills:b")), sent.getLast());
    }

    @Test
    void givenTtl_whenExpired_thenHasFalseAndPatchUnknown() {
        hub.connected(player, ALL);
        JsonObject marker = withId(ClientElement.marker("m").at(1, 64, 2).label("Hier").ttl(1000).toJson(), "quests:m");
        assertTrue(hub.put(player, marker).ok());
        assertTrue(hub.has(player.getUniqueId(), "quests:m"));
        nanos.addAndGet(1_000_000_000L);
        assertFalse(hub.has(player.getUniqueId(), "quests:m"));
        assertEquals(ClientUiHub.Status.UNKNOWN_ID, hub.patch(player, "quests:m", fields("{\"label\":\"x\"}")).status());
    }

    @Test
    void givenLargePatch_whenFlushed_thenFullPutInstead() {
        hub.connected(player, ALL);
        ClientElement.Panel panel = ClientElement.panel("x").title("T");
        hub.put(player, withId(panel.toJson(), "skills:p"));
        ticks(1);
        sent.clear();
        StringBuilder text = new StringBuilder();
        text.append("ä".repeat(250));
        JsonObject lines = new JsonObject();
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (int i = 0; i < 12; i++) {
            com.google.gson.JsonArray segs = new com.google.gson.JsonArray();
            JsonObject seg = new JsonObject();
            seg.addProperty("text", text.toString());
            seg.addProperty("color", "#FFAABBCC");
            segs.add(seg);
            a.add(segs);
        }
        lines.add("lines", a);
        assertTrue(hub.patch(player, "skills:p", lines).ok());
        ticks(1);
        assertInstanceOf(UiMessage.Put.class, sent.getFirst(), "patch > 4 KiB is sent as a full put");
    }

    @Test
    void givenPatchesWithUnknownFields_whenMergedTooLarge_thenTooLargeLikeClient() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:xp", 1));
        String big = "x".repeat(3000);
        for (int i = 0; i < 8; i++) {
            JsonObject extra = new JsonObject();
            extra.addProperty("unknown" + i, big);
            ClientUiHub.Result r = hub.patch(player, "skills:xp", extra);
            if (r.status() == ClientUiHub.Status.TOO_LARGE) {
                assertTrue(i >= 5, "only once above 16 KiB");
                return;
            }
            assertTrue(r.ok());
        }
        fail("merged element above 16 KiB must be refused (client 0.3.2 rejects it)");
    }

    @Test
    void givenReject_whenReceived_thenListenerAndMirrorDropped() {
        hub.connected(player, ALL);
        hub.put(player, bar("skills:xp", 1));
        hub.onMessage(player, UiMessage.encode(new UiMessage.Reject("skills:xp", Protocol.REJECT_LIMIT, "too many bar elements")));
        assertEquals(List.of("skills:xp/2/too many bar elements"), rejected);
        assertFalse(hub.has(player.getUniqueId(), "skills:xp"));
        hub.onMessage(player, UiMessage.encode(new UiMessage.Put("{}")));   // falsche Richtung
        hub.onMessage(player, new byte[4096]);
        assertEquals(1, rejected.size());
    }

    @Test
    void givenManyRejects_whenFlooded_thenThrottled() {
        hub.connected(player, ALL);
        for (int i = 0; i < 50; i++) hub.onMessage(player, UiMessage.encode(new UiMessage.Reject("", Protocol.REJECT_RATE, "rate")));
        assertEquals(10, rejected.size());
    }

    @Test
    void givenDisconnect_whenFlushed_thenQueueDropped() {
        hub.connected(player, ALL);
        hub.toast(player, ClientElement.toast("a").toJson());
        hub.disconnected(player.getUniqueId());
        ticks(1);
        assertTrue(sent.isEmpty());
        assertFalse(hub.supports(player.getUniqueId(), Protocol.CAP_TOASTS));
    }

    @Test
    void givenDisabled_whenConnected_thenIgnored() {
        hub = hub(new ClientUiHub.Settings(false, 5, 30, 100, 32, 384));
        hub.connected(player, ALL);
        assertEquals(ClientUiHub.Status.DISABLED, hub.put(player, bar("skills:xp", 1)).status());
    }
}
