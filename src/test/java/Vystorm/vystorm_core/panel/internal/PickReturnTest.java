package Vystorm.vystorm_core.panel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSession;
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
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PickReturnTest {
    final List<PanelMessage> sent = new ArrayList<>();
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    final AtomicReference<Object> seen = new AtomicReference<>();
    Player player;
    Plugin owner;
    PanelHub hub;
    PanelRegistry.Registered reg;

    @BeforeEach
    void setUp() {
        PanelRegistry.clear();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        PanelChannel channel = new PanelChannel(PanelChannel.Settings.defaults(), uuid -> Protocol.CAP_PANELS,
                (p, d) -> sent.add(PanelMessage.decodeClientbound(d)), Logger.getAnonymousLogger(), nanos::get);
        hub = new PanelHub(channel, nanos::get, Logger.getAnonymousLogger(), e -> { }, Runnable::run, Runnable::run);
        channel.listener(hub);
        PanelRegistry.register(owner, "example:pay", PanelSpec.builder("Pay").render(ctx -> {
            seen.set(ctx.arg() + "|" + ctx.session().get("payee"));
            return Panel.page("Pay");
        }).build());
        reg = PanelRegistry.find("example:pay").orElseThrow();
        channel.onMessage(player, PanelMessage.encode(new PanelMessage.Hello(1, 1, 0, 0)));
    }

    @AfterEach
    void tearDown() { PanelRegistry.clear(); }

    private PanelHub.Act action(Map<String, Object> values) {
        PanelHub.Session session = new PanelHub.Session(7, player, reg, "acct-1");
        session.state.putAll(values);
        return new PanelHub.Act(player, null, session);
    }

    @Test
    void givenActionWithSessionValues_whenPanelReturn_thenValuesCopiedAndWritable() {
        PanelHub.Act act = action(Map.of("tab", "history"));
        PickReturn back = PickReturn.panel(act);
        assertEquals("history", back.session().get("tab"));
        back.session().put("payee", "Alex");
        assertNull(act.session().get("payee"), "the carried values are a copy");
        assertEquals(Optional.of(owner), back.owner());
    }

    @Test
    void givenCarriedValues_whenReopenedNatively_thenNewSessionSeesThemAndStatusGoesToChat() {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("payee", "Alex"));
        PanelOpener.Seed seed = new PanelOpener.Seed(player.getUniqueId(), reg.id(), values, List.of("✔ Picked"));
        PanelOpenResult result = PanelOpener.withSeed(seed, () -> hub.openNative(player, reg, "acct-1", 0));
        assertEquals(PanelOpenResult.NATIVE, result);
        assertEquals("acct-1|Alex", seen.get());
        assertFalse(seed.statusShown, "the client mod has no status line: Core sends it to chat");
    }

    @Test
    void givenSeedForAnotherPanelOrPlayer_whenOpened_thenNotApplied() {
        PanelOpener.Seed other = new PanelOpener.Seed(UUID.randomUUID(), reg.id(), new LinkedHashMap<>(Map.of("payee", "X")), List.of());
        PanelOpener.withSeed(other, () -> hub.openNative(player, reg, "a", 0));
        assertEquals("a|null", seen.get());
    }

    @Test
    void givenHandlerResult_whenTurnedIntoStatus_thenMarkedOkOrError() {
        assertEquals(List.of("✔ Saved"), PanelOpener.status(player, Result.ok("Saved")));
        assertEquals(List.of("✖ No"), PanelOpener.status(player, Result.error("No")));
        assertTrue(PanelOpener.status(player, Result.ok()).isEmpty());
        assertTrue(PanelOpener.status(player, null).isEmpty());
    }

    @Test
    void givenForeignAction_whenPanelReturn_thenNowhereWithEmptySession() {
        PanelAction foreign = mock(PanelAction.class);
        PickReturn back = PickReturn.panel(foreign);
        assertTrue(back.owner().isEmpty());
        assertNull(back.session().get("x"));
        back.go(player, Result.ok("Done"));
        verify(player).sendMessage("§a✔ Done");
    }

    @Test
    void givenRunReturn_whenGo_thenCodeRunsAndMessageInChat() {
        AtomicReference<Player> ran = new AtomicReference<>();
        PickReturn back = PickReturn.to(ran::set);
        PanelSession values = back.session();
        values.put("k", 1);
        back.go(player, Result.error("Too many"));
        assertEquals(player, ran.get());
        verify(player).sendMessage("§c✖ Too many");
        assertEquals(Integer.valueOf(1), values.get("k"));
    }

    @Test
    void givenCapability_whenAsked_thenPickReturnSince071() {
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.PICK_RETURN));
    }
}
