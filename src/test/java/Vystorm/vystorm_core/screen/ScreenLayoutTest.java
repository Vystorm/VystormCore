package Vystorm.vystorm_core.screen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenLayoutTest {
    private static final ScreenWidget BUTTON = ScreenWidget.button("hello", 0.05, 0.05, 0.2, 0.08, "Hello");

    @Test void givenButtonAtTopLeft_whenHitInside_thenButton() {
        ScreenLayout layout = new ScreenLayout(0.8, 0.5, List.of(BUTTON));
        // centre-origin (-0.25, 0.16) -> top-left space (0.15, 0.09): inside 0.05..0.25 x 0.05..0.13
        assertEquals("hello", layout.hit(-0.25, 0.16).orElseThrow().id());
    }
    @Test void givenPointOutsidePanel_whenHit_thenEmpty() {
        assertTrue(new ScreenLayout(0.8, 0.5, List.of(BUTTON)).hit(0.5, 0).isEmpty());
    }
    @Test void givenLabelUnderButton_whenHit_thenOnlyEnabledButtonOrInputCount() {
        ScreenWidget label = ScreenWidget.label("title", 0, 0, 0.8, 0.5, "Title");
        ScreenWidget off = ScreenWidget.button("off", 0.3, 0.3, 0.2, 0.1, "Off").withEnabled(false);
        ScreenWidget input = ScreenWidget.input("name", 0.55, 0.3, 0.2, 0.1, "Name");
        ScreenLayout layout = new ScreenLayout(0.8, 0.5, List.of(label, off, input));
        assertTrue(layout.hit(0.0, 0.2).isEmpty(), "labels are never hit");
        assertTrue(layout.hit(0.0, -0.1).isEmpty(), "disabled buttons are never hit");
        assertEquals("name", layout.hit(0.25, -0.1).orElseThrow().id());
    }
    @Test void givenOverlappingButtons_whenHit_thenLastDeclaredWins() {
        ScreenWidget a = ScreenWidget.button("a", 0.1, 0.1, 0.3, 0.2, "A"), b = ScreenWidget.button("b", 0.2, 0.15, 0.3, 0.2, "B");
        assertEquals("b", new ScreenLayout(0.8, 0.5, List.of(a, b)).hit(-0.1, 0.0).orElseThrow().id());
    }
    @Test void givenMapWidget_whenHit_thenClickableAndPixelFromPoint() {
        ScreenWidget.MapGrid grid = new ScreenWidget.MapGrid(2, 2);
        ScreenWidget map = ScreenWidget.map("map", 0.1, 0.1, 0.4, 0.3, grid, "placeholder");
        ScreenLayout layout = new ScreenLayout(0.8, 0.5, List.of(map));
        assertEquals("map", layout.hit(-0.1, 0.0).orElseThrow().id());
        // top-left corner of the map: centre-origin (0.1 - 0.4, 0.25 - 0.1)
        assertArrayEquals(new int[] { 0, 0 }, map.mapPixel(-0.3, 0.15, 0.8, 0.5));
        assertArrayEquals(new int[] { 255, 253 }, map.mapPixel(0.5, -0.5, 0.8, 0.5), "clamped to the canvas");
        assertEquals(256, grid.pixelWidth());
        assertEquals(254, grid.pixelHeight());
        assertNull(BUTTON.mapPixel(0, 0, 0.8, 0.5));
        assertNotEquals(0, map.restingFill() >>> 24, "maps have a dark box behind them");
    }
    @Test void givenMapWithoutGrid_thenRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenWidget("m", ScreenWidget.Kind.MAP, 0, 0, 0.1, 0.1, "", true));
        assertThrows(IllegalArgumentException.class, () -> new ScreenWidget.MapGrid(0, 1));
    }
    @Test void givenWidgets_thenListIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> new ScreenLayout(0.8, 0.5, List.of(BUTTON)).widgets().add(BUTTON));
    }
}
