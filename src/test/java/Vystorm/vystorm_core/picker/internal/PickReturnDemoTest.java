package Vystorm.vystorm_core.picker.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.internal.PanelOpener;
import Vystorm.vystorm_core.panel.internal.PanelRegistry;
import Vystorm.vystorm_core.panel.internal.PanelResume;
import org.bukkit.plugin.Plugin;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickCandidate;
import Vystorm.vystorm_core.picker.PickKind;
import Vystorm.vystorm_core.picker.PickProtection;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.Picked;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPicks;
import Vystorm.vystorm_core.picker.WorldPick;
import Vystorm.vystorm_core.visual.BlockPosition;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PickReturnDemoTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    static JsonObject render(String arg, int count, Object last) {
        Player viewer = PickerPanelTest.viewer();
        return PanelTesting.render("vystorm_core:pick-demo", PickReturnDemo.build(viewer, arg, count, last, new PickReturnDemo.Handlers(null)), viewer);
    }

    @Test
    void givenState_whenRendered_thenArgCounterLastResultAndFourPickButtons() {
        JsonObject doc = render("a7", 3, "Picked: stone at 1 2 3");
        List<String> ids = PanelTesting.ids(doc);
        assertTrue(ids.containsAll(List.of("add", "pick-player", "pick-players", "pick-block", "pick-area")), ids.toString());
        String text = doc.toString();
        assertTrue(text.contains("Opened with: a7"), text);
        assertTrue(text.contains("Counter: 3"), text);
        assertTrue(text.contains("Last result: Picked: stone at 1 2 3"), text);
    }

    @Test
    void givenFreshSession_whenRendered_thenCounterZeroAndNoResultYet() {
        String text = render("", 0, null).toString();
        assertTrue(text.contains("Counter: 0"), text);
        assertTrue(text.contains("Last result: none yet"), text);
        assertTrue(text.contains("Opened with: -"), text);
    }

    @Test
    void givenPlusButton_whenClicked_thenSessionCounterUpAndRedrawn() {
        PanelSession session = PickReturn.none().session();
        session.put(PickReturnDemo.COUNT, 4);
        PanelAction a = mock(PanelAction.class);
        when(a.session()).thenReturn(session);
        Result r = new PickReturnDemo.Handlers(null).add(a);
        assertTrue(r.success());
        assertEquals(5, PickReturnDemo.count(session));
        verify(a).refresh();
    }

    @Test
    void givenOddSessionValue_whenCounted_thenZero() {
        PanelSession session = PickReturn.none().session();
        session.put(PickReturnDemo.COUNT, "x");
        assertEquals(0, PickReturnDemo.count(session));
        assertEquals(0, PickReturnDemo.count(null));
    }

    @Test
    void givenPlayerPick_whenHandedOver_thenLineInCarriedSessionInSinkAndAsMessage() {
        Player viewer = PickerPanelTest.viewer();
        PanelSession carried = PickReturn.none().session();
        carried.put(PickReturnDemo.COUNT, 2);
        List<String> sunk = new ArrayList<>();
        var pick = PickReturnDemo.playerPick(true, (p, text) -> sunk.add(text));
        assertTrue(pick.multiple());
        PickedPlayer alex = new PickedPlayer(UUID.randomUUID(), "Alex", true, 0);
        Result r = pick.onPick().picked(viewer, new PlayerPicks(List.of(alex), Set.of(alex.id()), Set.of(), carried));
        assertTrue(r.success());
        String line = carried.get(PickReturnDemo.LAST);
        assertEquals("Picked: Alex (added 1, removed 0)", line);
        assertEquals(List.of(line), sunk);
        assertEquals(Integer.valueOf(2), carried.get(PickReturnDemo.COUNT), "the counter is left as it was");
        assertTrue(PanelTesting.render("vystorm_core:pick-demo", PickReturnDemo.build(viewer, "a1", PickReturnDemo.count(carried), carried.get(PickReturnDemo.LAST),
                new PickReturnDemo.Handlers(null)), viewer).toString().contains("Last result: " + line));
    }

    /** A demo-like panel whose context fallback records what it was handed (no runtime, no server). */
    static List<FallbackContext> registerDemoLike(Plugin owner) {
        List<FallbackContext> seen = new ArrayList<>();
        PanelRegistry.register(owner, "vystorm_core:pick-demo", PanelSpec.builder("Return test").render(ctx -> Panel.page("x"))
                .contextFallback((p, ctx) -> seen.add(ctx)).build());
        return seen;
    }

    @Test
    void givenScreenSessionCounter3_whenPickReturnFallsBackIntoTheChest_thenChestShowsArgCounterAndResult() {
        Plugin owner = mock(Plugin.class);
        when(owner.isEnabled()).thenReturn(true);
        Player viewer = PickerPanelTest.viewer();
        when(viewer.isOnline()).thenReturn(true);
        PanelRegistry.clear();
        try {
            List<FallbackContext> seen = registerDemoLike(owner);
            PanelRegistry.Registered reg = PanelRegistry.find("vystorm_core:pick-demo").orElseThrow();
            // r48 in the client: counter 3 in the screen session, a pick, its return ends in the chest
            java.util.Map<String, Object> carried = new java.util.LinkedHashMap<>(java.util.Map.of(PickReturnDemo.COUNT, 3, PickReturnDemo.LAST, "Picked: Alex"));
            PanelOpener.reopen(viewer, new PanelResume(reg, "e5", carried), Result.ok("Picked: Alex"));
            FallbackContext chest = seen.getFirst();
            PickReturnDemo.ChestView view = PickReturnDemo.ChestView.of(viewer, chest, chest.result().orElse(null));
            assertEquals("e5", view.arg());
            assertEquals(3, view.count());
            assertEquals("Picked: Alex", view.last());
            assertEquals(List.of("✔ Picked: Alex"), view.status());

            // +1 in the chest is the same counter the screen renders, and a pick started there carries it back
            PickReturnDemo.add(chest);
            assertEquals(4, PickReturnDemo.count(chest.session()));
            assertTrue(PanelTesting.render("vystorm_core:pick-demo", PickReturnDemo.build(viewer, chest.arg(), PickReturnDemo.count(chest.session()),
                    chest.session().get(PickReturnDemo.LAST), new PickReturnDemo.Handlers(null)), viewer).toString().contains("Counter: 4"));
            PickReturn back = PickReturn.panel(chest);
            assertEquals(java.util.Optional.of(owner), back.owner());
            Result r = PickReturnDemo.playerPick(false, (p, t) -> { }).onPick().picked(viewer,
                    new PlayerPicks(List.of(new PickedPlayer(UUID.randomUUID(), "Sam", true, 0)), Set.of(), Set.of(), back.session()));
            back.go(viewer, r);
            FallbackContext again = seen.get(1);
            assertEquals("e5", again.arg());
            assertEquals(4, PickReturnDemo.count(again.session()));
            assertEquals("Picked: Sam (added 0, removed 0)", again.session().get(PickReturnDemo.LAST));
            assertTrue(again.returning());
        } finally {
            PanelRegistry.clear();
        }
    }

    @Test
    void givenFreshChest_whenViewed_thenNoStatusAndCounterZero() {
        Player viewer = PickerPanelTest.viewer();
        FallbackContext ctx = mock(FallbackContext.class);
        when(ctx.arg()).thenReturn("");
        when(ctx.session()).thenReturn(PickReturn.none().session());
        PickReturnDemo.ChestView view = PickReturnDemo.ChestView.of(viewer, ctx, null);
        assertEquals(0, view.count());
        assertTrue(view.status().isEmpty());
    }

    @Test
    void givenWorldPicks_whenBuilt_thenBuildProtectionAndHandlerWritesTheLine() {
        Player viewer = PickerPanelTest.viewer();
        WorldPick block = PickReturnDemo.worldPick(PickKind.BLOCK, (p, t) -> { });
        WorldPick area = PickReturnDemo.worldPick(PickKind.AREA, (p, t) -> { });
        assertEquals(PickKind.BLOCK, block.kind());
        assertEquals(PickKind.AREA, area.kind());
        assertEquals(PickProtection.BUILD, block.protection());
        assertEquals(PickProtection.BUILD, area.protection());
        PanelSession carried = PickReturn.none().session();
        Result r = block.onPick().picked(viewer, new PickCandidate(new Picked.Block(WORLD, new BlockPosition(1, 2, 3), "stone"), Optional.empty(), carried));
        assertEquals("Picked: stone at 1 2 3", carried.get(PickReturnDemo.LAST));
        assertTrue(r.success());
    }
}
