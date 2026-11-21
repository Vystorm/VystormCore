package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 0.66.1: 26.3 clients sort text displays back to front by the distance of each display's text origin (top-left corner
 * of its text block, see {@link ScreenGeometry#textOrigin}). PINNED boxes must sort behind their labels everywhere on
 * the screen, and moving the displays along the view ray must not change where anything is drawn.
 */
class ScreenDrawOrderTest {
    private final Plugin core = mock(Plugin.class);
    private final World world = mock(World.class);
    private final List<TextDisplay> texts = new ArrayList<>();
    private ScreenRuntime runtime;

    private final ScreenEntities fake = new ScreenEntities() {
        private <T extends Entity> T entity(Class<T> type) {
            T e = mock(type);
            when(e.getUniqueId()).thenReturn(UUID.randomUUID());
            when(e.isValid()).thenReturn(true);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(pdc.has(any(), any())).thenReturn(true);
            when(e.getPersistentDataContainer()).thenReturn(pdc);
            return e;
        }
        @Override public Entity mount(Player viewer, Location at) { return entity(Entity.class); }
        @Override public TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup) { TextDisplay d = entity(TextDisplay.class); texts.add(d); setup.accept(d); return d; }
        @Override public Interaction interaction(Player viewer, Location at, float w, float h) { return entity(Interaction.class); }
        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) { return entity(org.bukkit.entity.ItemDisplay.class); }
        @Override public void remove(Entity entity) { }
    };

    @BeforeEach void setUp() {
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        runtime = new ScreenRuntime(core, fake, System::currentTimeMillis, p -> true, p -> false);
    }

    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getLocation()).thenReturn(new Location(world, 10, 64, 10, 0, 0));
        when(p.getEyeLocation()).thenReturn(new Location(world, 10, 65.62, 10, 0, 0));
        when(p.isOnGround()).thenReturn(true);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    private static Transformation last(TextDisplay d) {
        ArgumentCaptor<Transformation> c = ArgumentCaptor.forClass(Transformation.class);
        verify(d, atLeastOnce()).setTransformation(c.capture());
        return c.getValue();
    }

    /** Sort distance the 26.3 client uses for a display showing a text block of {@code widthPx} x {@code lines}. */
    private static double sortKey(Transformation t, double widthPx) {
        Vector3f p = t.getTranslation(), s = t.getScale();
        double[] o = ScreenGeometry.textOrigin(p.x, p.y, s.x, s.y, widthPx, 1);
        return Math.sqrt(o[0] * o[0] + o[1] * o[1] + p.z * p.z);
    }

    /** Panel-plane position at distance 1 (what the player sees), independent of how far along the ray the display is. */
    private static double seenX(Transformation t) { return t.getTranslation().x / -t.getTranslation().z * ScreenRuntime.PINNED_DISTANCE; }
    private static double seenY(Transformation t) { return t.getTranslation().y / -t.getTranslation().z * ScreenRuntime.PINNED_DISTANCE; }
    private static double seenScale(Transformation t) { return t.getScale().y / -t.getTranslation().z * ScreenRuntime.PINNED_DISTANCE; }

    @Test void givenPinnedButtonGrid_whenDrawn_thenEveryBoxSortsBehindItsLabelAndLooksUnmoved() {
        // the suite's ScreenProbe grid: 6 x 9 buttons over the whole view, labels "Label rc"
        double bw = 0.30, bh = 0.07, dx = 0.34, dy = 0.115;
        List<ScreenWidget> grid = new ArrayList<>();
        for (int r = 0; r < 9; r++) for (int c = 0; c < 6; c++)
            grid.add(ScreenWidget.button("b" + r + c, 0.04 + c * dx, 0.04 + r * dy, bw, bh, "Label " + r + c));
        ScreenLayout layout = new ScreenLayout(2.1, 1.12, grid);
        assertTrue(runtime.open(core, player(), ScreenSpec.builder("test:grid", layout).onInput((s, i) -> { }).fallback(p -> { }).build()).opened());

        // spawn order: backdrop, background, then box + label per button, then the pointer
        assertEquals(2 + 2 * grid.size() + 1, texts.size());
        double background = sortKey(last(texts.get(1)), 9);
        for (int i = 0; i < grid.size(); i++) {
            ScreenWidget w = grid.get(i);
            Transformation box = last(texts.get(2 + 2 * i)), label = last(texts.get(3 + 2 * i));
            double boxKey = sortKey(box, 9);
            double est = ScreenRuntime.textWidth("Label 00") - 2;
            // the real label width is not known on the server: any width near the estimate must still sort in front
            for (double width = Math.max(0, est - 10); width <= est + 10; width += 1)
                assertTrue(sortKey(label, width) < boxKey, w.id() + ": label (width " + width + ") must sort in front of its box");
            assertTrue(boxKey < background, w.id() + ": box must sort in front of the panel background");

            // what the player sees is unchanged: same place and size on the plane at distance 1 as before 0.66.1
            double cx = w.u() + w.width() / 2 - layout.width() / 2, cy = layout.height() / 2 - (w.v() + w.height() / 2);
            assertEquals(cx - w.width() / 16, seenX(box), 1e-5, w.id() + " box x");
            assertEquals(w.width() / ScreenRuntime.GLYPH, box.getScale().x / -box.getTranslation().z, 1e-4, w.id() + " box width");
            assertEquals(cy - w.height() / 2 - w.height() / ScreenRuntime.GLYPH * ScreenRuntime.PIXEL, seenY(box), 1e-5, w.id() + " box y");
            assertEquals(cx, seenX(label), 1e-5, w.id() + " label x");
            assertTrue(seenScale(label) > 0.05 && seenScale(label) < 0.5, w.id() + " label scale " + seenScale(label));
        }
        // the pointer stays the nearest text display, so it is drawn on top of everything
        double pointer = Math.sqrt(1 + 0.03 * 0.03 * 2);
        for (int i = 1; i < texts.size() - 1; i++) assertTrue(sortKey(last(texts.get(i)), 9) > pointer, "display " + i + " behind the pointer");
    }

    @Test void givenButtonOnFilledSection_whenDrawn_thenSectionBoxAndLabelSortInDrawingOrder() {
        // a filled section on the right half with a button in its far corner: the button's corner lies farther from the
        // centre than the section's, which put the button behind the section on 26.3 without layers
        ScreenLayout layout = new ScreenLayout(1.6, 0.9, List.of(
                ScreenWidget.fill("section", 0.8, 0.1, 0.75, 0.75, 0xFF303844),
                ScreenWidget.button("far", 1.2, 0.7, 0.3, 0.1, "Far corner")));
        assertTrue(runtime.open(core, player(), ScreenSpec.builder("test:section", layout).onInput((s, i) -> { }).fallback(p -> { }).build()).opened());
        // backdrop, background, section box, button box, button label, pointer
        assertEquals(6, texts.size());
        double background = sortKey(last(texts.get(1)), 9), section = sortKey(last(texts.get(2)), 9), box = sortKey(last(texts.get(3)), 9);
        double label = sortKey(last(texts.get(4)), ScreenRuntime.textWidth("Far corner") - 2);
        assertTrue(background > section && section > box && box > label, background + " > " + section + " > " + box + " > " + label);
    }

    @Test void givenOverlaps_whenLayered_thenEachWidgetLiesAboveWhatIsBelowIt() {
        int[][] layers = ScreenGeometry.layers(
                List.of(new double[] { 0, 0, 1, 1 }, new double[] { 0.1, 0.1, 0.2, 0.2 }, new double[] { 1, 0, 1, 1 }, new double[] { 0.15, 0.15, 0.1, 0.1 }),
                List.of(true, true, false, true));
        assertArrayEquals(new int[] { 1, 2 }, layers[0]);   // on the background
        assertArrayEquals(new int[] { 3, 4 }, layers[1]);   // on the first widget
        assertArrayEquals(new int[] { 1, 1 }, layers[2]);   // touches the first one only at an edge: on the background
        assertArrayEquals(new int[] { 5, 6 }, layers[3]);   // on the second one
        assertTrue(ScreenGeometry.layerDistance(0, 1) > ScreenGeometry.layerDistance(1, 1));
        assertEquals(ScreenGeometry.layerDistance(ScreenGeometry.LAYER_TOP, 1), ScreenGeometry.layerDistance(99, 1));
        assertTrue(ScreenGeometry.layerDistance(99, 1) > 1.05, "the top layer stays behind the pointer at distance 1");
    }
}
