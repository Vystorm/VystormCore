package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class InventoryScreenLayoutTest {
    static List<InventoryScreenLayout.Slot> chest(int size) {
        List<InventoryScreenLayout.Slot> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            boolean filler = i % 9 == 0;
            boolean empty = i % 9 == 8;
            slots.add(new InventoryScreenLayout.Slot(i, empty ? null : filler ? Material.GRAY_STAINED_GLASS_PANE : Material.DIAMOND,
                    Component.text(filler ? " " : "Diamant Nummer " + i), List.of(Component.text("Preis: 120 XP"), Component.text("")), i % 3 + 1, filler));
        }
        return slots;
    }

    static InventoryScreenLayout.Result layout(int size, int columns, ScreenProfile profile) {
        return InventoryScreenLayout.layout(Component.text("Market"), chest(size), columns, profile, Component.text("Close"), Component.text("hint"));
    }

    @Test
    void givenSixRowChest_whenLaidOut_thenEveryItemIsAClickableTileAndFillersStayEmpty() {
        InventoryScreenLayout.Result r = layout(54, 9, ScreenProfile.DEFAULT);
        List<ScreenWidget> buttons = r.layout().widgets().stream().filter(w -> InventoryScreenLayout.slotOf(w.id()) >= 0).toList();
        assertEquals(54 - 6 - 6, buttons.size(), "fillers and empty slots are not clickable");
        assertTrue(buttons.stream().allMatch(ScreenWidget::clickable));
        assertEquals(-1, InventoryScreenLayout.slotOf("close"));
        assertEquals(13, InventoryScreenLayout.slotOf(InventoryScreenLayout.slotId(13)));
    }

    @Test
    void givenAnyChestSizeAndScreen_whenLaidOut_thenEverythingFitsTheViewAndThePanel() {
        for (ScreenProfile profile : List.of(ScreenProfile.DEFAULT, new ScreenProfile(3840.0 / 1009.0, 70, 1.0), new ScreenProfile(4.0 / 3.0, 50, 1.6))) {
            for (int size : new int[] {9, 27, 54}) {
                InventoryScreenLayout.Result r = layout(size, 9, profile);
                assertTrue(r.layout().width() <= profile.viewWidth() * 0.92 + 1e-9 || r.layout().width() <= 0.9 * profile.scale() + 1e-9);
                assertTrue(r.layout().height() <= profile.viewHeight() * 0.86 + 1e-9, "height " + size + " " + profile);
                for (ScreenWidget w : r.layout().widgets()) {
                    assertTrue(w.u() >= -1e-9 && w.u() + w.width() <= r.layout().width() + 1e-9, w.id());
                    assertTrue(w.v() >= -1e-9 && w.v() + w.height() <= r.layout().height() + 1e-9, w.id());
                }
            }
        }
    }

    @Test
    void givenItemWithLore_whenHinted_thenNameAndNonBlankLoreAreJoined() {
        InventoryScreenLayout.Result r = layout(9, 9, ScreenProfile.DEFAULT);
        String hint = InventoryScreenLayout.plain(r.hints().get(InventoryScreenLayout.slotId(1)));
        assertEquals("Diamant Nummer 1  ·  Preis: 120 XP", hint);
    }

    @Test
    void givenLongName_whenShortened_thenCutWithEllipsis() {
        assertEquals("Diamant Numme…", InventoryScreenLayout.plain(InventoryScreenLayout.shorten(Component.text("Diamant Nummer 12"), 14)));
        assertEquals("Kurz", InventoryScreenLayout.plain(InventoryScreenLayout.shorten(Component.text("Kurz"), 14)));
    }

    @Test
    void givenHopper_whenLaidOut_thenOneRowOfFive() {
        InventoryScreenLayout.Result r = InventoryScreenLayout.layout(Component.text("H"), chest(5), 5, ScreenProfile.DEFAULT, Component.text("C"), Component.text("h"));
        double firstV = r.layout().widgets().stream().filter(w -> w.id().equals("s1")).findFirst().orElseThrow().v();
        assertTrue(r.layout().widgets().stream().filter(w -> InventoryScreenLayout.slotOf(w.id()) >= 0).allMatch(w -> w.v() == firstV));
    }
}
