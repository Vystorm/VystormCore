package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.web.ClientElement;
import Vystorm.vystorm_core.web.ClientServices;
import Vystorm.vystorm_core.web.internal.clientproto.Protocol;
import Vystorm.vystorm_core.web.internal.clientproto.UiMessage;
import Vystorm.vystorm_core.web.internal.clientproto.ui.ToastSpec;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiElement;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiSchema;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 0.26.4: Client-0.9.0-Erweiterungen nur mit {@code CAP_UI_EXTENSIONS}, rein verbessernde Felder für ältere weglassen, Toast-Aktionen. */
class ClientUiExtensionsTest {
    static final int OLD = Protocol.CAP_HUD | Protocol.CAP_TOASTS | Protocol.CAP_MARKERS | Protocol.CAP_TOOLTIPS;
    static final int NEW = OLD | Protocol.CAP_UI_EXTENSIONS;

    final List<UiMessage> sent = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    Player player;
    Player other;
    ClientUiHub hub;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        hub = new ClientUiHub(ClientUiHub.Settings.defaults(), (p, data) -> { if (p == player) sent.add(UiMessage.decodeClientbound(data)); },
                (p, id, code, detail) -> { }, nanos::get);
    }

    void ticks(int n) { for (int i = 0; i < n; i++) { nanos.addAndGet(50_000_000L); hub.flush(); } }

    static JsonObject withId(ClientElement.Element<?> e, String id) {
        JsonObject o = e.toJson();
        o.addProperty("id", id);
        return o;
    }

    JsonObject lastJson() {
        UiMessage m = sent.getLast();
        String json = m instanceof UiMessage.Put p ? p.json() : m instanceof UiMessage.Patch p ? p.json() : ((UiMessage.Toast) m).json();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void capabilityConstantMatchesProtocol() {
        assertEquals(Protocol.CAP_UI_EXTENSIONS, ClientServices.CAP_UI_EXTENSIONS);
    }

    @Test
    void givenOldClient_whenListOrDynamicTooltipOrBarLabel_thenNoCapability() {
        hub.connected(player, OLD);
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.put(player, withId(ClientElement.list("q").title("Quests"), "p:q")).status());
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.put(player, withId(ClientElement.tooltip("t").matchItem("minecraft:stick")
                .line("Qualität: " + ClientElement.data("PublicBukkitValues.vystorm_items:quality")), "p:t")).status());
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.put(player, withId(ClientElement.tooltip("t")
                .matchCustomDataViewer("PublicBukkitValues.vystorm_items:soulbound").line("An dich gebunden"), "p:t")).status());
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.put(player, withId(ClientElement.bar("b").countdown(5000, 10000)
                .label("Noch " + ClientElement.Bar.TIME), "p:b")).status());
        assertEquals(ClientUiHub.Status.OK, hub.put(player, withId(ClientElement.tooltip("t")
                .matchCustomData("PublicBukkitValues.vystorm_items:id", "flame").line("Set"), "p:t")).status(), "0.8 tooltip still works");
        ticks(1);
        assertEquals(1, sent.size());
    }

    @Test
    void givenOldClient_whenEnhancementFields_thenStrippedAndPatchOfThemUnchanged() {
        hub.connected(player, OLD);
        UUID npc = UUID.randomUUID();
        assertTrue(hub.put(player, withId(ClientElement.bar("b").value(5).max(10).rate(2).stack(true), "p:b")).ok());
        assertTrue(hub.put(player, withId(ClientElement.marker("m").at(1, 2, 3).entity(npc).yOffset(0.5).ground(true), "p:m")).ok());
        ticks(1);
        JsonObject marker = lastJson();
        assertFalse(marker.has("entity") || marker.has("yOffset") || marker.has("ground"));
        JsonObject bar = JsonParser.parseString(((UiMessage.Put) sent.getFirst()).json()).getAsJsonObject();
        assertFalse(bar.has("rate") || bar.has("stack"));
        assertEquals(ClientUiHub.Status.UNCHANGED, hub.patch(player, "p:b", JsonParser.parseString("{\"rate\":3}").getAsJsonObject()).status());
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.patch(player, "p:b", JsonParser.parseString("{\"label\":\"{value}\"}").getAsJsonObject()).status());
    }

    @Test
    void givenNewClient_whenExtensions_thenSentAsIs() {
        hub.connected(player, NEW);
        UUID npc = UUID.randomUUID();
        assertTrue(hub.put(player, withId(ClientElement.list("q").title("Quests").stack(true).anchor(ClientElement.Anchor.TOP_RIGHT)
                .row(ClientElement.row("Holz").right("3/10").bar(3, 10, "#FF55FF55").icon(ClientElement.Icon.item("minecraft:oak_log")))
                .row(ClientElement.row(ClientElement.Text.of("Boss", "red"))), "p:q")).ok());
        assertTrue(hub.put(player, withId(ClientElement.marker("m").at(1, 2, 3).entity(npc).ground(true), "p:m")).ok());
        ticks(1);
        UiElement.HudList list = (UiElement.HudList) UiSchema.element(JsonParser.parseString(((UiMessage.Put) sent.getFirst()).json()).getAsJsonObject());
        assertEquals(2, list.rows().size());
        assertTrue(list.stack());
        UiElement.Marker m = (UiElement.Marker) UiSchema.element(lastJson());
        assertEquals(npc, m.entity());
        assertTrue(m.ground());
        assertTrue(hub.patch(player, "p:q", ClientElement.list("q").rows(List.of(ClientElement.row("Fertig"))).toJson()).ok());
        ticks(10);
        assertEquals(1, lastJson().getAsJsonArray("rows").size(), "rows replaced as a whole by PATCH");
    }

    @Test
    void givenToastWithAction_whenClientAccepts_thenHandlerRunsOnceWithinWindow() {
        hub.connected(player, NEW);
        AtomicInteger runs = new AtomicInteger();
        JsonObject toast = ClientElement.toast("Einladung").action("Beitreten").duration(8000).toJson();
        assertTrue(hub.toast(player, toast, "party:", p -> { assertSame(player, p); runs.incrementAndGet(); }).ok());
        ticks(1);
        ToastSpec spec = UiSchema.toast(lastJson());
        String id = spec.action().id();
        assertTrue(id.startsWith("party:toast/"));
        assertEquals("Beitreten", spec.action().label().plainText());
        assertEquals(1, hub.pendingActions(player.getUniqueId()));

        hub.onMessage(player, UiMessage.encode(new UiMessage.Action("party:toast/zzz")));
        assertEquals(0, runs.get(), "unknown id ignored");
        hub.onMessage(other, UiMessage.encode(new UiMessage.Action(id)));
        assertEquals(0, runs.get(), "other player cannot trigger it");
        hub.onMessage(player, UiMessage.encode(new UiMessage.Action(id)));
        assertEquals(1, runs.get());
        nanos.addAndGet(1_000_000_000L);
        hub.onMessage(player, UiMessage.encode(new UiMessage.Action(id)));
        assertEquals(1, runs.get(), "at most once");
        assertEquals(0, hub.pendingActions(player.getUniqueId()));
    }

    @Test
    void givenToastAction_whenExpiredOrReleasedOrOldClient_thenNothing() {
        AtomicInteger runs = new AtomicInteger();
        JsonObject toast = ClientElement.toast("Einladung").duration(5000).toJson();
        hub.connected(player, OLD);
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.toast(player, toast, "party:", p -> runs.incrementAndGet()).status());
        JsonObject raw = toast.deepCopy();
        raw.add("action", JsonParser.parseString("{\"id\":\"party:x\"}"));
        assertEquals(ClientUiHub.Status.NO_CAPABILITY, hub.toast(player, raw).status(), "raw action JSON also needs the capability");

        hub.connected(player, NEW);
        assertTrue(hub.toast(player, toast, "party:", p -> runs.incrementAndGet()).ok());
        ticks(1);
        String id = UiSchema.toast(lastJson()).action().id();
        nanos.addAndGet(ToastSpec.serverActionWindowMs(5000) * 1_000_000L);
        hub.onMessage(player, UiMessage.encode(new UiMessage.Action(id)));
        assertEquals(0, runs.get(), "expired");

        assertTrue(hub.toast(player, toast, "party:", p -> runs.incrementAndGet()).ok());
        hub.releasePrefix("party:");
        assertEquals(0, hub.pendingActions(player.getUniqueId()), "plugin disabled: handlers dropped");
    }

    @Test
    void givenManyToastActionsAndFlood_whenAccepted_thenBoundedAndRateLimited() {
        hub.connected(player, NEW);
        AtomicInteger runs = new AtomicInteger();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ClientUiHub.MAX_ACTIONS + 4; i++) {
            assertTrue(hub.toast(player, ClientElement.toast("T" + i).toJson(), "p:", p -> runs.incrementAndGet()).ok());
            ticks(1);
            ids.add(UiSchema.toast(lastJson()).action().id());
        }
        assertEquals(ClientUiHub.MAX_ACTIONS, hub.pendingActions(player.getUniqueId()), "oldest dropped");
        hub.onMessage(player, UiMessage.encode(new UiMessage.Action(ids.getFirst())));
        assertEquals(0, runs.get());
        for (String id : ids.subList(4, ids.size())) hub.onMessage(player, UiMessage.encode(new UiMessage.Action(id)));
        assertTrue(runs.get() <= 4, "2/s, burst 4 – got " + runs.get());
    }
}
