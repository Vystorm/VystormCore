package Vystorm.vystorm_core.picker.internal;

import static Vystorm.vystorm_core.picker.internal.PickerModelTest.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickEnd;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.PlayerSource;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PickerPanelTest {
    static Player viewer() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(PickerModelTest.VIEWER);
        when(p.getName()).thenReturn("Me");
        return p;
    }

    static PlayerPickFlow flow(PlayerPick pick, PickerModel model) {
        return new PlayerPickFlow(mock(Plugin.class), viewer(), pick, PickReturn.none(), model, "f1", 0, f -> { });
    }

    static JsonObject render(PlayerPickFlow flow) {
        return PanelTesting.render("vystorm_core:player-pick",
                PickerPanel.build(flow, flow.viewer, false, PickerModelTest.NOW, ZoneOffset.UTC, true, new PickerPanel.Handlers(null, flow)), flow.viewer);
    }

    static List<String> listKeys(JsonObject doc) {
        JsonObject list = PanelTesting.find(doc, PickerPanel.LIST);
        assertNotNull(list, "list element");
        List<String> keys = new ArrayList<>();
        for (JsonElement e : list.getAsJsonArray("items")) keys.add(e.getAsJsonObject().get("key").getAsString());
        return keys;
    }

    @Test
    void givenSinglePick_whenRendered_thenSchemaValidRowsAndTabs() {
        PlayerPick pick = PlayerPick.builder("Add a buddy").onPick((v, p) -> Result.ok()).build();
        PickerModel model = PickerModelTest.single();
        PlayerPickFlow flow = flow(pick, model);
        model.refresh(PickerModelTest.world());
        model.select(model.firstTab());
        JsonObject doc = render(flow);
        assertEquals(List.of("p:" + id(2)), listKeys(doc), "Nearby: Alex");
        assertTrue(listKeys(doc).getFirst().length() <= 64);
        List<String> ids = PanelTesting.ids(doc);
        assertTrue(ids.containsAll(List.of("tab-online", "tab-nearby", "tab-recent", "tab-search", "back")), ids.toString());
        assertFalse(ids.contains("done"), "no Done in single mode");
        assertNull(PanelTesting.find(doc, PickerPanel.SEARCH), "the field only on the Search tab");
    }

    @Test
    void givenMultipleOnSearchTab_whenRendered_thenSearchFieldDoneAndChosenBadge() {
        PlayerPick pick = PlayerPick.builder("Share").multiple(5).confirmLabel("Share").onPick((v, p) -> Result.ok()).build();
        PickerModel model = PickerModelTest.model(true, 5, Set.of(), c -> true, c -> c.name().equals("Alessia") ? PickVerdict.deny("no") : PickVerdict.ok());
        PlayerPickFlow flow = flow(pick, model);
        model.query("ale");
        model.refresh(PickerModelTest.world());
        model.select(PlayerSource.SEARCH);
        model.toggle(id(2));
        JsonObject doc = render(flow);
        assertNotNull(PanelTesting.find(doc, PickerPanel.SEARCH));
        assertEquals(List.of("p:" + id(2), "p:" + id(11), "p:" + id(10)), listKeys(doc));
        JsonObject done = PanelTesting.find(doc, "done");
        assertNotNull(done);
        assertEquals("Share", PanelTesting.plain(done.get("label")));
        JsonArray items = PanelTesting.find(doc, PickerPanel.LIST).getAsJsonArray("items");
        assertTrue(items.get(0).getAsJsonObject().has("badge"), "Alex is ticked");
        assertTrue(items.get(1).getAsJsonObject().has("right"), "Alessia shows the reason");
    }

    @Test
    void givenOneSourceAndManyRows_whenRendered_thenNoTabsAndPageButtons() {
        PlayerPick pick = PlayerPick.builder("Online").sources(PlayerSource.ONLINE).onPick((v, p) -> Result.ok()).build();
        PickerModel model = new PickerModel(PickerModelTest.VIEWER, List.of(PlayerSource.ONLINE), false, 1, false, 16, Set.of(), c -> true, c -> PickVerdict.ok(), PickerModel.Limits.DEFAULTS);
        PlayerPickFlow flow = flow(pick, model);
        model.refresh(PickerChestLayoutTest.many(250));
        JsonObject doc = render(flow);
        List<String> ids = PanelTesting.ids(doc);
        assertFalse(ids.stream().anyMatch(i -> i.startsWith("tab-")), ids.toString());
        assertEquals(200, listKeys(doc).size());
        assertTrue(ids.contains("prev") && ids.contains("next"));
    }

    @Test
    void givenCloseReasons_whenMapped_thenOnlyPlayerClosesGoBack() {
        assertEquals(PickEnd.BACK, PickerRuntime.endForScreen(ScreenInput.Reason.PLAYER));
        assertEquals(PickEnd.INTERRUPTED, PickerRuntime.endForScreen(ScreenInput.Reason.DAMAGE));
        assertEquals(PickEnd.INTERRUPTED, PickerRuntime.endForScreen(ScreenInput.Reason.TELEPORT));
        assertEquals(PickEnd.INTERRUPTED, PickerRuntime.endForScreen(ScreenInput.Reason.DEATH));
        assertEquals(PickEnd.QUIT, PickerRuntime.endForScreen(ScreenInput.Reason.QUIT));
        assertEquals(PickEnd.REPLACED, PickerRuntime.endForScreen(ScreenInput.Reason.PLUGIN));
        assertEquals(PickEnd.BACK, PickerRuntime.endForPanel(PanelLimits.CLOSED_PLAYER));
        assertEquals(PickEnd.REPLACED, PickerRuntime.endForPanel(PanelLimits.CLOSED_REPLACED));
        assertEquals(PickEnd.REPLACED, PickerRuntime.endForPanel(PanelLimits.CLOSED_SERVER));
        assertEquals(PickEnd.INTERRUPTED, PickerRuntime.endForPanel(PanelLimits.CLOSED_ERROR));
        assertEquals(PickEnd.BACK, PickerRuntime.endForChest(null), "Spigot has no close reason");
        assertEquals(PickEnd.BACK, PickerRuntime.endForChest("PLAYER"));
        assertEquals(PickEnd.REPLACED, PickerRuntime.endForChest("OPEN_NEW"));
        assertEquals(PickEnd.QUIT, PickerRuntime.endForChest("DISCONNECT"));
        assertEquals(PickEnd.INTERRUPTED, PickerRuntime.endForChest("DEATH"));
        for (PickEnd end : PickEnd.values()) assertEquals(end == PickEnd.DONE || end == PickEnd.BACK, PickerRuntime.returns(end), end.name());
    }

    @Test
    void givenIdleFlow_whenClockPasses_thenTimedOutAfterTheTimeoutOnly() {
        PlayerPick pick = PlayerPick.builder("x").timeout(Duration.ofSeconds(30)).onPick((v, p) -> Result.ok()).build();
        PlayerPickFlow flow = flow(pick, PickerModelTest.single());
        flow.lastActivity = 1_000;
        assertFalse(PickerRuntime.timedOut(flow, 1_000 + 30_000));
        assertTrue(PickerRuntime.timedOut(flow, 1_000 + 30_001));
        assertEquals(Duration.ofSeconds(600), PlayerPick.builder("x").timeout(Duration.ofHours(1)).onPick((v, p) -> Result.ok()).build().timeout());
        assertEquals(Duration.ofSeconds(10), PlayerPick.builder("x").timeout(Duration.ZERO).onPick((v, p) -> Result.ok()).build().timeout());
    }

    @Test
    void givenBuilderMisuse_whenBuilt_thenRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> PlayerPick.builder("x").build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> PlayerPick.builder("x").preselected(Set.of(id(1))).onPick((v, p) -> Result.ok()).build());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PlayerPick.builder("x").multiple(0));
        PlayerPick all = PlayerPick.builder("x").onPick((v, p) -> Result.ok()).build();
        assertEquals(List.of(PlayerSource.values()), all.sources());
        assertFalse(all.multiple());
        assertTrue(PlayerPick.builder("x").multiple(1).onPick((v, p) -> Result.ok()).build().multiple());
    }

    @Test
    void givenCapabilities_whenAsked_thenPickerSince071() {
        assertEquals(Optional.of("0.71.0"), CoreApi.since(CoreApi.Capability.PLAYER_PICKER));
        assertTrue(CoreApi.has("pick-return"));
    }

    /** Defect 1 of the first client look: on a screen the Search tab asks for the name at once and the flow stays. */
    @Test
    void givenScreenRoute_whenSearchTabClicked_thenOnePromptIsAskedAndThePickerStaysOpen() {
        PlayerPick pick = PlayerPick.builder("x").multiple(5).onPick((v, p) -> Result.ok()).build();
        PickerModel model = PickerModelTest.model(true, 5, Set.of(), c -> true, c -> PickVerdict.ok());
        PlayerPickFlow flow = flow(pick, model);
        flow.route = PlayerPickFlow.Route.SCREEN;
        PickerRuntime rt = new PickerRuntime(mock(Plugin.class));
        rt.track(flow);
        PickerPanel.Handlers h = new PickerPanel.Handlers(rt, flow);
        Vystorm.vystorm_core.panel.PanelAction a = mock(Vystorm.vystorm_core.panel.PanelAction.class);
        Vystorm.vystorm_core.panel.internal.PanelOpener.takeTypeNext(flow.viewer);
        assertTrue(h.tab(a, PlayerSource.SEARCH).success());
        assertEquals(PickerPanel.SEARCH, Vystorm.vystorm_core.panel.internal.PanelOpener.takeTypeNext(flow.viewer), "the typing box of the search field opens at once");
        assertEquals(PlayerSource.SEARCH, model.tab());
        // the typed answer comes back as a search on the same, still running flow
        assertTrue(h.search(a, "ale").success());
        assertFalse(flow.ended, "typing must not end the picker");
        assertEquals("ale", model.query());
        model.refresh(PickerModelTest.world());   // what the screen's redraw does
        assertEquals(List.of("Alex", "Alessia", "Alexandra"), PickerModelTest.names(model, 200));
        org.mockito.Mockito.verify(a, org.mockito.Mockito.never()).closeAndThen(org.mockito.ArgumentMatchers.any());
        assertTrue(h.tab(a, PlayerSource.ONLINE).success());
        assertNull(Vystorm.vystorm_core.panel.internal.PanelOpener.takeTypeNext(flow.viewer), "other tabs ask for nothing");
    }

    @Test
    void givenChestOrClientModRoute_whenSearchTabClicked_thenNoTypingRequest() {
        PlayerPick pick = PlayerPick.builder("x").onPick((v, p) -> Result.ok()).build();
        for (PlayerPickFlow.Route route : List.of(PlayerPickFlow.Route.NATIVE, PlayerPickFlow.Route.CHEST)) {
            PlayerPickFlow flow = flow(pick, PickerModelTest.single());
            flow.route = route;
            PickerRuntime rt = new PickerRuntime(mock(Plugin.class));
            rt.track(flow);
            new PickerPanel.Handlers(rt, flow).tab(mock(Vystorm.vystorm_core.panel.PanelAction.class), PlayerSource.SEARCH);
            assertNull(Vystorm.vystorm_core.panel.internal.PanelOpener.takeTypeNext(flow.viewer), route.name());
        }
    }

    /** Defect 5: an empty Search tab said "No player found with that name." before anything was typed. */
    @Test
    void givenSearchTabWithoutText_whenRendered_thenTheListAsksForANameInsteadOfNoPlayerFound() {
        PlayerPick pick = PlayerPick.builder("x").onPick((v, p) -> Result.ok()).build();
        PickerModel model = PickerModelTest.single();
        PlayerPickFlow flow = flow(pick, model);
        model.refresh(PickerModelTest.world());
        model.select(PlayerSource.SEARCH);
        JsonObject doc = render(flow);
        String all = doc.toString();
        assertFalse(all.contains("No player found"), all);
        assertEquals(1, all.split("Offline players show from 3 letters on", -1).length - 1, "said once, as the list's empty line");
        model.query("zzz");
        model.refresh(PickerModelTest.world());
        assertTrue(render(flow).toString().contains("No player found"), "a search without hits still says so");
    }

    /** Defect 5: the viewer's own refused row was not greyed out, only the red reason marked it. */
    @Test
    void givenARowTheRuleRefuses_whenRendered_thenNameAndStatusAreGreyAndTheReasonRed() {
        PlayerPick pick = PlayerPick.builder("x").multiple(5).onPick((v, p) -> Result.ok()).build();
        PickerModel model = PickerModelTest.model(true, 5, Set.of(), c -> true, c -> c.name().equals("Cleo") ? PickVerdict.deny("busy") : PickVerdict.ok());
        PlayerPickFlow flow = flow(pick, model);
        model.refresh(PickerModelTest.world());
        model.select(PlayerSource.ONLINE);
        JsonArray items = PanelTesting.find(render(flow), PickerPanel.LIST).getAsJsonArray("items");
        String alex = items.get(0).toString(), cleo = items.get(2).toString();
        assertTrue(cleo.contains("Cleo") && cleo.contains("\"color\":\"#AAAAAA\"") && cleo.contains("#555555"), cleo);
        assertTrue(cleo.contains("busy") && cleo.contains("#FF5555"), cleo);
        assertFalse(alex.contains("#AAAAAA"), "allowed rows keep their colours: " + alex);
    }

    @Test
    void givenKeys_whenParsed_thenRowKeyFormat() {
        assertEquals(id(5), PickerPanel.parse("p:" + id(5)));
        assertNull(PickerPanel.parse("x:" + id(5)));
        assertNull(PickerPanel.parse("p:nope"));
    }
}
