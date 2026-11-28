package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import Vystorm.vystorm_core.screen.Screens;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The screen canvas in the runtime: partial map sends with a budget, pointer samples, press and release. */
class ScreenCanvasRuntimeTest {
    private final Plugin core = mock(Plugin.class);
    private final World world = mock(World.class);
    private final List<Entity> spawned = new ArrayList<>();
    private final List<ScreenInput> inputs = new ArrayList<>();
    private final List<Runnable> nextTick = new ArrayList<>();
    private long now = 100_000;
    private ScreenRuntime runtime;
    private org.mockito.MockedConstruction<org.bukkit.inventory.ItemStack> airStacks;

    record Patch(int tile, int x, int y, int w, int h) {}
    private final List<Patch> patches = new ArrayList<>();
    private int fullTiles;

    private final ScreenEntities fake = new ScreenEntities() {
        private <T extends Entity> T entity(Class<T> type) {
            T e = mock(type);
            UUID id = UUID.randomUUID();
            when(e.getUniqueId()).thenReturn(id);
            when(e.isValid()).thenReturn(true);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            when(pdc.has(any(), any())).thenReturn(true);
            when(e.getPersistentDataContainer()).thenReturn(pdc);
            spawned.add(e);
            return e;
        }
        @Override public Entity mount(Player viewer, Location at) { return entity(Entity.class); }
        @Override public TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup) { TextDisplay d = entity(TextDisplay.class); setup.accept(d); return d; }
        @Override public Interaction interaction(Player viewer, Location at, float w, float h) { return entity(Interaction.class); }
        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) { return entity(org.bukkit.entity.ItemDisplay.class); }
        @Override public void remove(Entity entity) { }
    };

    private final ScreenMapFrames maps = new ScreenMapFrames() {
        @Override public boolean supported() { return true; }
        @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) {
            fullTiles += tiles.size();
            return new Handle() {
                @Override public void pixels(int tile, byte[] colours) { fullTiles++; }
                @Override public void patch(int tile, int x, int y, int w, int h, byte[] colours) {
                    assertEquals(w * h, colours.length);
                    patches.add(new Patch(tile, x, y, w, h));
                }
                @Override public void remove() { }
            };
        }
    };

    @BeforeEach void setUp() {
        airStacks = mockConstruction(org.bukkit.inventory.ItemStack.class, (stack, context) -> when(stack.getType()).thenReturn(Material.AIR));
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        runtime = new ScreenRuntime(core, fake, () -> now, p -> true, p -> false);
        runtime.useMaps(maps);
        runtime.coalesceWheel(nextTick::add);
    }

    @AfterEach void tearDown() { airStacks.close(); }

    private Location feet;

    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        feet = new Location(world, 10, 64, 10, 0, 0);
        when(p.getLocation()).thenReturn(feet);
        when(p.getEyeLocation()).thenReturn(new Location(world, 10, 65.62, 10, 0, 0));
        when(p.isOnGround()).thenReturn(true);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    private static final ScreenWidget.MapGrid GRID = new ScreenWidget.MapGrid(2, 2);
    /** Map widget around the panel centre: centre-origin u -0.3..0.3, v -0.25..0.25 (the anchor view points at 0,0). */
    private static final ScreenLayout LAYOUT = new ScreenLayout(1.0, 0.7, List.of(
            ScreenWidget.map("canvas", 0.2, 0.1, 0.6, 0.5, GRID, "no map"),
            ScreenWidget.button("close", 0.02, 0.62, 0.2, 0.06, "Close")));

    private ScreenSession open(Player p) {
        assertTrue(runtime.open(core, p, ScreenSpec.builder("test:canvas", LAYOUT).mode(ScreenSpec.Mode.PINNED).onInput((s, i) -> inputs.add(i)).build()).opened());
        return runtime.session(p).orElseThrow();
    }

    private void runTicks(int n) {
        for (int i = 0; i < n; i++) {
            List<Runnable> due = List.copyOf(nextTick);
            nextTick.clear();
            due.forEach(Runnable::run);
        }
    }

    private Entity clickBox() { return spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow(); }

    private <T extends ScreenInput> List<T> of(Class<T> type) { return inputs.stream().filter(type::isInstance).map(type::cast).toList(); }

    // ---- C1 partial map redraw ----------------------------------------------------------------------------------

    @Test void givenShownCanvas_whenOnePixelChanges_thenOnePatchOfOnePixelAndNoFullTile() {
        Player p = player();
        ScreenSession s = open(p);
        byte[] canvas = new byte[GRID.pixelWidth() * GRID.pixelHeight()];
        java.util.Arrays.fill(canvas, ScreenMapCodec.BIT_ZERO);   // what the frames showed at the start
        canvas[200 * 256 + 140] = 18;                                // tile 3 (right, bottom), tile pixel (12, 200-127+1)
        int before = fullTiles;
        s.setMap("canvas", canvas);
        assertEquals(before, fullTiles, "no full tile after the first picture");
        assertEquals(List.of(new Patch(3, 12, 74, 1, 1)), patches);
        canvas[200 * 256 + 140] = 30;                                // the same array changed in place and sent again
        s.setMap("canvas", canvas);
        assertEquals(new Patch(3, 12, 74, 1, 1), patches.get(1));
    }

    @Test void givenFullRedraw_whenBudgetIsTwoTiles_thenTheRestFollowsInTheNextTick() {
        runtime.mapBudget(2 * (128 * 127 + ScreenMapSender.PATCH_OVERHEAD));
        Player p = player();
        ScreenSession s = open(p);
        byte[] canvas = new byte[GRID.pixelWidth() * GRID.pixelHeight()];
        java.util.Arrays.fill(canvas, (byte) 34);
        s.setMap("canvas", canvas);
        assertEquals(2, patches.size(), "two tiles in this tick");
        runTicks(1);
        assertEquals(4, patches.size(), "the other two in the next tick");
        runTicks(2);
        assertTrue(nextTick.isEmpty(), "no tick task once everything is sent");
    }

    @Test void givenDefaultBudget_whenNamed_thenFourFullTilesPerTick() {
        assertEquals(64 * 1024, Screens.MAP_BYTES_PER_TICK);
        assertTrue(4 * (128 * 127 + ScreenMapSender.PATCH_OVERHEAD) <= Screens.MAP_BYTES_PER_TICK);
    }

    // ---- cost for screens that use none of it -------------------------------------------------------------------

    @Test void givenScreenWithoutCanvasCalls_whenLookingAndClicking_thenNoPointerNoPressAndNoTickTask() {
        Player p = player();
        open(p);
        for (int i = 0; i < 10; i++) runtime.onRotate(p, i * 0.5f, 0);
        runtime.onClick(p, clickBox(), true);
        now += 1_000;
        assertTrue(of(ScreenInput.Pointer.class).isEmpty());
        assertTrue(of(ScreenInput.Press.class).isEmpty());
        assertTrue(nextTick.isEmpty(), "nothing scheduled");
        assertEquals(1, of(ScreenInput.Click.class).size());
    }

    // ---- C2 pointer samples -------------------------------------------------------------------------------------

    @Test void givenTrackedCanvas_whenManyRotationsInOneTick_thenOnePointerPerTick() {
        Player p = player();
        ScreenSession s = open(p);
        assertTrue(s.trackPointer("canvas", true));
        runTicks(1);                                    // tick 1: the poll reads (0, 0)
        assertEquals(1, of(ScreenInput.Pointer.class).size());
        for (int i = 1; i <= 5; i++) { feet.setYaw(i); runtime.onRotate(p, i, 0); }   // all in tick 1
        assertEquals(1, of(ScreenInput.Pointer.class).size(), "the first sample of this tick was the poll");
        runTicks(1);                                    // tick 2: the newest position
        List<ScreenInput.Pointer> samples = of(ScreenInput.Pointer.class);
        assertEquals(2, samples.size());
        assertEquals("canvas", samples.get(1).widgetId());
        assertNotEquals(samples.get(0).u(), samples.get(1).u(), 1e-9);
        runTicks(1);                                    // tick 3: nothing moved
        assertEquals(2, of(ScreenInput.Pointer.class).size());
        feet.setYaw(6);
        runtime.onRotate(p, 6, 0);                      // tick 3 had none yet: delivered at once
        assertEquals(3, of(ScreenInput.Pointer.class).size());
    }

    @Test void givenTrackedCanvas_whenPointerOutsideIt_thenNoSamples() {
        Player p = player();
        ScreenSession s = open(p);
        s.trackPointer("canvas", true);
        feet.setPitch(-40);                              // far above the panel
        runTicks(3);
        assertTrue(of(ScreenInput.Pointer.class).isEmpty());
        s.trackPointer("canvas", false);
        runTicks(2);
        assertTrue(nextTick.isEmpty(), "polling stops when no widget asks");
    }

    // ---- C3 press and release -----------------------------------------------------------------------------------

    @Test void givenTrackPress_whenRightHeldThenLetGo_thenDownBeforeTheFirstClickAndUpAfterTheReleaseTime() {
        Player p = player();
        ScreenSession s = open(p);
        assertTrue(s.trackPress(true));
        for (int i = 0; i < 4; i++) {                    // uses every 200 ms, the server ticks every 50 ms
            runtime.onClick(p, clickBox(), true);
            for (int k = 0; k < 4; k++) { now += 50; runTicks(1); }
        }
        // last use at +600 ms (relative); up once 350 ms passed
        for (int k = 0; k < 4; k++) { now += 50; runTicks(1); }
        List<ScreenInput> seen = inputs.stream().filter(i -> !(i instanceof ScreenInput.Hover)).toList();
        assertEquals(new ScreenInput.Press(true, true), seen.get(0));
        assertTrue(seen.get(1) instanceof ScreenInput.Click c && c.right() && "canvas".equals(c.widgetId()));
        assertEquals(List.of(new ScreenInput.Press(true, true), new ScreenInput.Press(true, false)), of(ScreenInput.Press.class));
        assertEquals(4, of(ScreenInput.Click.class).size(), "every repeat is still a click");
        runTicks(2);
        assertTrue(nextTick.isEmpty());
    }

    @Test void givenButtonDown_whenScreenCloses_thenUpBeforeClosed() {
        Player p = player();
        ScreenSession s = open(p);
        s.trackPress(true);
        runtime.onClick(p, clickBox(), true);
        runtime.close(p, ScreenInput.Reason.PLAYER);
        int n = inputs.size();
        assertEquals(new ScreenInput.Press(true, false), inputs.get(n - 2));
        assertEquals(new ScreenInput.Closed(ScreenInput.Reason.PLAYER), inputs.get(n - 1));
    }

    // ---- C0 experiment: panel holes ------------------------------------------------------------------------------

    @Test void givenClientThatCannotDrawMaps_whenHolesAsked_thenMapsShownBackgroundCutAndPointerSeeThrough() {
        runtime.mapsDrawableFor(p -> false);
        Player p = player();
        runtime.mapHoles(p, true);
        ScreenSession s = open(p);
        runtime.mapHoles(p, false);
        assertTrue(s.showsMap("canvas"), "shown although the client is a 26.3 one");
        assertEquals(4, ((ScreenRuntime.Session) s).backgroundPieces().size());
        assertEquals(3, ((ScreenRuntime.Session) s).backgroundParts.size(), "three more background displays");
        TextDisplay pointer = (TextDisplay) spawned.stream().filter(e -> e instanceof TextDisplay).reduce((a, b) -> b).orElseThrow();
        verify(pointer).setSeeThrough(true);
        Player other = player();
        assertFalse(open(other).showsMap("canvas"), "without the experiment the placeholder stays");
    }

    @Test void givenButtonDown_whenTrackPressSwitchedOff_thenUpAndNoMorePress() {
        Player p = player();
        ScreenSession s = open(p);
        s.trackPress(true);
        runtime.onClick(p, clickBox(), true);
        s.trackPress(false);
        assertEquals(List.of(new ScreenInput.Press(true, true), new ScreenInput.Press(true, false)), of(ScreenInput.Press.class));
        runtime.onClick(p, clickBox(), true);
        assertEquals(2, of(ScreenInput.Press.class).size());
        assertFalse(s.trackPointer(null, true));
    }

    @Test void givenLeftClick_whenTrackPress_thenNoPressOnlyTheClick() {
        Player p = player();
        ScreenSession s = open(p);
        s.trackPress(true);
        runtime.onClick(p, clickBox(), false);
        assertTrue(of(ScreenInput.Press.class).isEmpty());
        assertEquals(1, of(ScreenInput.Click.class).size());
    }
}
