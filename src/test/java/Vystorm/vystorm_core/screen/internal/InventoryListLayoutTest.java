package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** Chest menus that are settings (the config editor) as a list: every row is still its slot. */
class InventoryListLayoutTest {
    static InventoryListLayout.Spec spec(int sections, int values) {
        List<InventoryListLayout.Item> rows = new ArrayList<>();
        int slot = 0;
        for (int s = 0; s < sections; s++) {
            rows.add(new InventoryListLayout.Item(slot++, true, Component.text("Section " + s), Component.text(values + " options, 0 changed"), null));
            for (int v = 0; v < values; v++)
                rows.add(new InventoryListLayout.Item(slot++, false, Component.text("Value number " + v), Component.text(v % 2 == 0 ? "ON" : "250"),
                        Component.text("What value " + v + " does on the server")));
        }
        return new InventoryListLayout.Spec("page#0", rows,
                List.of(new InventoryListLayout.Button(49, Component.text("Back")), new InventoryListLayout.Button(53, Component.text("Next page"))));
    }

    static InventoryScreenLayout.Result layout(InventoryListLayout.Spec spec, ScreenProfile profile, int offset) {
        return InventoryListLayout.layout(Component.text("Settings: VystormChat"), spec, profile, offset, Component.text("Close"), Component.text("Chest"), null,
                (from, to, total) -> Component.text(from + " to " + to + " of " + total), p -> Component.text("hint · ").append(p));
    }

    @Test
    void givenSpec_whenLaidOut_thenRowsAreTheirSlotsAndButtonsFormABar() {
        InventoryScreenLayout.Result r = layout(spec(2, 3), ScreenProfile.DEFAULT, 0);
        List<ScreenWidget> clickable = r.layout().widgets().stream().filter(ScreenWidget::clickable).toList();
        List<Integer> slots = clickable.stream().map(w -> InventoryScreenLayout.slotOf(w.id())).filter(s -> s >= 0).sorted().toList();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 49, 53), slots, "every row and button is a click on its slot, nothing else");
        assertTrue(r.layout().widgets().stream().anyMatch(w -> w.text() instanceof Component c && ScreenListLayout.plain(c).equals("Value number 2")),
                "full names, not cut to 14 characters");
        assertTrue(r.layout().widgets().stream().anyMatch(w -> w.text() instanceof Component c && ScreenListLayout.plain(c).equals("What value 2 does on the server")));
        assertEquals("hint · 1 to 6 of 6", ScreenListLayout.plain(r.hint()));
        ScreenWidget back = clickable.stream().filter(w -> w.id().equals("s49")).findFirst().orElseThrow();
        ScreenWidget row = clickable.stream().filter(w -> w.id().equals("s1")).findFirst().orElseThrow();
        assertTrue(back.v() > row.v(), "buttons below the list");
        assertTrue(r.hints().get("s1") != null && ScreenListLayout.plain(r.hints().get("s1")).contains("What value 0 does"), "hover line with the description");
    }

    @Test
    void givenLongSpec_whenScrolled_thenWindowMovesAndEverythingStaysInsideThePanel() {
        InventoryListLayout.Spec spec = spec(4, 10);
        for (ScreenProfile profile : List.of(ScreenProfile.DEFAULT, ScreenProfile.DEFAULT.withAspect(4.0 / 3).withScale(1.3), ScreenProfile.DEFAULT.withScale(0.7))) {
            int max = InventoryListLayout.clamp(spec, profile, 1000);
            assertTrue(max > 0, "44 rows do not fit at once");
            for (int offset : List.of(0, max / 2, max)) {
                InventoryScreenLayout.Result r = layout(spec, profile, offset);
                double w = r.layout().width(), h = r.layout().height();
                for (ScreenWidget wd : r.layout().widgets()) {
                    assertTrue(wd.u() >= -1e-9 && wd.u() + wd.width() <= w + 1e-9, "x of " + wd);
                    assertTrue(wd.v() >= -1e-9 && wd.v() + wd.height() <= h + 1e-9, "y of " + wd);
                }
            }
            String end = ScreenListLayout.plain(layout(spec, profile, max).hint());
            assertTrue(end.endsWith("to 40 of 40"), end);
        }
        assertEquals(0, InventoryListLayout.clamp(spec, ScreenProfile.DEFAULT, -5));
    }

    @Test
    void givenRegisteredMenu_whenAsked_thenItsSpecElseNull() {
        Object menu = new Object();
        assertNull(InventoryListLayout.of(menu));
        InventoryListLayout.Spec spec = spec(1, 1);
        InventoryListLayout.show(menu, spec);
        assertSame(spec, InventoryListLayout.of(menu));
        assertNull(InventoryListLayout.of(null));
    }
}
