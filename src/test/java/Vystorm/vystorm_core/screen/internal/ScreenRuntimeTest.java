package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenLayout;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScreenRuntimeTest {
    private final Plugin core = mock(Plugin.class);
    private final World world = mock(World.class);
    private final List<Entity> spawned = new ArrayList<>(), removed = new ArrayList<>();
    private final List<ScreenInput> inputs = new ArrayList<>();
    private long now = 100_000;

    private long clicks() { return inputs.stream().filter(i -> i instanceof ScreenInput.Click).count(); }
    private boolean pack = true;
    private ScreenRuntime runtime;

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
        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) { org.bukkit.entity.ItemDisplay d = entity(org.bukkit.entity.ItemDisplay.class); return d; }
        @Override public void remove(Entity entity) { removed.add(entity); }
    };

    @BeforeEach void setUp() {
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        runtime = new ScreenRuntime(core, fake, () -> now, p -> pack, p -> false);
    }

    private Player player(float yaw, float pitch) {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        Location feet = new Location(world, 10, 64, 10, yaw, pitch);
        when(p.getLocation()).thenReturn(feet);
        when(p.getEyeLocation()).thenReturn(new Location(world, 10, 65.62, 10, yaw, pitch));
        when(p.isOnGround()).thenReturn(true);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    private ScreenSpec spec(AtomicBoolean fallback) {
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(
                ScreenWidget.label("title", 0.05, 0.02, 0.8, 0.08, "Vystorm Screens"),
                ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Centre")));
        return ScreenSpec.builder("test:demo", layout).onInput((s, i) -> inputs.add(i)).fallback(p -> fallback.set(true)).build();
    }

    @Test void givenOpenSession_whenQuit_thenAllEntitiesRemovedAndPlayerEjected() {
        Player p = player(0, 0);
        assertTrue(runtime.open(core, p, spec(new AtomicBoolean())).opened());
        int created = spawned.size();
        runtime.close(p, ScreenInput.Reason.QUIT);
        assertEquals(created, removed.size(), "every screen entity is removed");
        verify(spawned.get(0)).removePassenger(p);
        verify(p, never()).teleport(any(Location.class));
        assertEquals(new ScreenInput.Closed(ScreenInput.Reason.QUIT), inputs.get(inputs.size() - 1));
        assertFalse(runtime.hasSession(p));
    }

    @Test void givenOpenSession_whenDeath_thenClosedWithDeath() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.close(p, ScreenInput.Reason.DEATH);
        assertEquals(new ScreenInput.Closed(ScreenInput.Reason.DEATH), inputs.get(inputs.size() - 1));
    }

    @Test void givenOpenSession_whenSneak_thenClosedAndPlayerReturned() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        assertTrue(runtime.onSneak(p, true));
        verify(p).teleport(any(Location.class));
        assertEquals(new ScreenInput.Closed(ScreenInput.Reason.PLAYER), inputs.get(inputs.size() - 1));
    }

    @Test void givenTwoPlayers_whenClickOnOthersInteraction_thenIgnored() {
        Player a = player(0, 0), b = player(0, 0);
        runtime.open(core, a, spec(new AtomicBoolean()));
        Entity interactionOfA = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        runtime.open(core, b, spec(new AtomicBoolean()));
        inputs.clear();
        assertFalse(runtime.onClick(b, interactionOfA, true));
        assertEquals(0, clicks());
        assertTrue(runtime.onClick(a, interactionOfA, true));
        assertEquals(1, clicks());
    }

    @Test void givenLookingAtCentreButton_whenClick_thenButtonClicked() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        Entity interaction = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        runtime.onClick(p, interaction, false);
        ScreenInput.Click click = (ScreenInput.Click) inputs.stream().filter(i -> i instanceof ScreenInput.Click).reduce((a, b) -> b).orElseThrow();
        assertEquals("centre", click.widgetId());
        assertFalse(click.right());
    }

    @Test void givenPitch90_whenMove_thenNoExceptionAndNoHover() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        assertDoesNotThrow(() -> runtime.onRotate(p, 0f, 90f));
        assertDoesNotThrow(() -> runtime.onRotate(p, 180f, -90f));
    }

    @Test void givenNoPack_whenAuto_thenFloating() {
        pack = false;
        ScreenOpenResult result = runtime.open(core, player(0, 0), spec(new AtomicBoolean()));
        assertEquals(ScreenSpec.Mode.FLOATING, result.mode());
        pack = true;
        assertEquals(ScreenSpec.Mode.PINNED, runtime.open(core, player(0, 0), spec(new AtomicBoolean())).mode());
    }

    @Test void givenFalling_whenOpen_thenRefusedAndFallbackCalled() {
        Player p = player(0, 0);
        when(p.getFallDistance()).thenReturn(4f);
        when(p.isOnGround()).thenReturn(false);
        AtomicBoolean fallback = new AtomicBoolean();
        ScreenOpenResult result = runtime.open(core, p, spec(fallback));
        assertFalse(result.opened());
        assertEquals("screen.refused.falling", result.refusedKey());
        assertTrue(fallback.get());
        assertTrue(spawned.isEmpty());
    }

    @Test void givenOpenScreen_whenOpeningAnother_thenTheNewOneReplacesIt() {
        Player p = player(0, 0);
        AtomicBoolean firstFallback = new AtomicBoolean(), secondFallback = new AtomicBoolean();
        assertTrue(runtime.open(core, p, spec(firstFallback)).opened());
        Vystorm.vystorm_core.screen.ScreenSession first = runtime.session(p).orElseThrow();
        ScreenOpenResult second = runtime.open(core, p, spec(secondFallback));
        assertTrue(second.opened());
        assertFalse(first.isOpen());
        assertNotSame(first, runtime.session(p).orElseThrow());
        assertFalse(firstFallback.get() || secondFallback.get(), "no classic menu when a screen replaces a screen");
    }

    // ---- history (0.61.0) ----

    private ScreenRuntime.Origin origin(String key, List<String> reopened) {
        return new ScreenRuntime.Origin(key, p -> { reopened.add(key); runtime.open(core, p, spec(new AtomicBoolean()), origin(key, reopened)); }, true);
    }

    @Test void givenScreenClosedByCode_whenNextOpens_thenBackReopensTheFirst() {
        Player p = player(0, 0);
        List<String> reopened = new ArrayList<>();
        runtime.open(core, p, spec(new AtomicBoolean()), origin("menu", reopened));
        assertFalse(runtime.hasBack(p), "the first screen has no parent");
        runtime.session(p).orElseThrow().close();
        now += 50;
        runtime.open(core, p, spec(new AtomicBoolean()), origin("detail", reopened));
        assertTrue(runtime.hasBack(p));
        assertTrue(runtime.back(p));
        assertEquals(List.of("menu"), reopened);
        assertTrue(runtime.hasSession(p));
        assertFalse(runtime.hasBack(p), "back at the root");
        assertFalse(runtime.back(p));
    }

    @Test void givenThreeScreens_whenBackTwice_thenOrderIsKept() {
        Player p = player(0, 0);
        List<String> reopened = new ArrayList<>();
        runtime.open(core, p, spec(new AtomicBoolean()), origin("a", reopened));
        runtime.open(core, p, spec(new AtomicBoolean()), origin("b", reopened));   // replaces a while it is open
        runtime.open(core, p, spec(new AtomicBoolean()), null);                    // a chest mirror: no place of its own
        assertTrue(runtime.back(p));
        assertTrue(runtime.back(p));
        assertEquals(List.of("b", "a"), reopened);
        assertFalse(runtime.hasBack(p));
    }

    @Test void givenChildScreen_whenParentPlaceOpensAgainByItself_thenHistoryShrinks() {
        Player p = player(0, 0);
        List<String> reopened = new ArrayList<>();
        runtime.open(core, p, spec(new AtomicBoolean()), origin("list", reopened));
        runtime.open(core, p, spec(new AtomicBoolean()), origin("detail", reopened));
        runtime.open(core, p, spec(new AtomicBoolean()), origin("list", reopened));  // the plugin's own back button
        assertFalse(runtime.hasBack(p), "list is the root again, not list > detail > list");
    }

    @Test void givenHistory_whenPlayerClosesOrTimePasses_thenItEnds() {
        Player p = player(0, 0);
        List<String> reopened = new ArrayList<>();
        runtime.open(core, p, spec(new AtomicBoolean()), origin("a", reopened));
        runtime.open(core, p, spec(new AtomicBoolean()), origin("b", reopened));
        runtime.close(p, ScreenInput.Reason.PLAYER);
        runtime.open(core, p, spec(new AtomicBoolean()), origin("c", reopened));
        assertFalse(runtime.hasBack(p), "closed by the player");
        runtime.session(p).orElseThrow().close();
        now += ScreenRuntime.LINK_MILLIS + 1;
        runtime.open(core, p, spec(new AtomicBoolean()), origin("d", reopened));
        assertFalse(runtime.hasBack(p), "opened much later: a fresh start");
    }

    @Test void givenParent_whenScreenOpens_thenItGetsCoresBackButtonUnlessItHasItsOwn() {
        Player p = player(0, 0);
        List<String> reopened = new ArrayList<>();
        runtime.open(core, p, spec(new AtomicBoolean()), origin("a", reopened));
        assertTrue(runtime.session(p).orElseThrow().spec().layout().widgets().stream().noneMatch(w -> w.id().equals(ScreenRuntime.BACK_ID)));
        runtime.open(core, p, spec(new AtomicBoolean()), null);
        assertTrue(runtime.layoutOf(p).widgets().stream().anyMatch(w -> w.id().equals(ScreenRuntime.BACK_ID)));
        runtime.open(core, p, spec(new AtomicBoolean()), new ScreenRuntime.Origin("dialog", q -> { }, false));
        assertTrue(runtime.layoutOf(p).widgets().stream().noneMatch(w -> w.id().equals(ScreenRuntime.BACK_ID)), "this screen has its own back controls");
    }

    @Test void givenClickOpensAnotherScreen_whenItsSecondHalfArrives_thenTheNewScreenIgnoresIt() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.onSwing(p);
        long before = clicks();
        runtime.open(core, p, spec(new AtomicBoolean()));   // what the first half of the click did
        runtime.onSwing(p);                                  // the same click, counted again by the client
        assertEquals(before, clicks(), "the new screen must not be clicked by the click that opened it");
        now += 200;
        runtime.onSwing(p);
        assertEquals(before + 1, clicks());
    }

    @Test void givenRecentDamage_whenOpen_thenRefusedCombat() {
        Player p = player(0, 0);
        runtime.onDamage(p);
        now += 1_000;
        assertEquals("screen.refused.combat", runtime.open(core, p, spec(new AtomicBoolean())).refusedKey());
        now += 5_000;
        assertTrue(runtime.open(core, p, spec(new AtomicBoolean())).opened());
    }

    @Test void givenTaggedEntityInLoadedChunk_whenSweep_thenOnlyLeftoversRemoved() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        List<Entity> live = List.copyOf(spawned);
        Entity leftover = fake.mount(p, p.getLocation());
        List<Entity> loaded = new ArrayList<>(live);
        loaded.add(leftover);
        assertEquals(1, runtime.sweep(loaded));
        assertEquals(List.of(leftover), removed);
    }

    @Test void givenHandlerThrows_whenInput_thenSessionStaysUsable() {
        Player p = player(0, 0);
        ScreenSpec failing = ScreenSpec.builder("test:boom", new ScreenLayout(0.5, 0.5, List.of())).onInput((s, i) -> { throw new IllegalStateException("boom"); }).build();
        runtime.open(core, p, failing);
        assertDoesNotThrow(() -> runtime.onScroll(p, 1));
        assertTrue(runtime.hasSession(p));
    }

    @Test void givenLeftClick_whenAttackAndSwingArrive_thenCountedOnce() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        Entity interaction = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        inputs.clear();
        runtime.onClick(p, interaction, false);
        runtime.onSwing(p);
        assertEquals(1, clicks());
        now += 200;
        runtime.onSwing(p);
        assertEquals(2, clicks(), "a later click counts again");
    }

    @Test void givenPinned_whenOpened_thenBackdropAndPanelBackgroundSpawned() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        // backdrop + background + label + button (box and text) + pointer = 6 text displays
        assertEquals(6, spawned.stream().filter(e -> e instanceof TextDisplay).count());
    }

    @Test void givenWallNextToPlayer_whenBlackBackdrop_thenHiddenAndRestoredOnClose() {
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        org.bukkit.block.data.BlockData real = mock(org.bukkit.block.data.BlockData.class);
        when(stone.getBlockData()).thenReturn(real);
        // the block directly east of the eye block is stone, everything else air
        when(world.getBlockAt(any(Location.class))).thenAnswer(inv -> { Location l = inv.getArgument(0); Block air = mock(Block.class); when(air.getType()).thenReturn(Material.AIR);
            return l.getBlockX() == 11 && l.getBlockY() == 65 && l.getBlockZ() == 10 ? stone : air; });
        org.bukkit.block.data.BlockData air = mock(org.bukkit.block.data.BlockData.class);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.createBlockData(Material.AIR)).thenReturn(air);
            Player p = player(0, 0);
            assertTrue(runtime.open(core, p, spec(new AtomicBoolean())).opened());
            verify(p, times(1)).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(air));
            runtime.close(p, ScreenInput.Reason.PLAYER);
            verify(p).sendBlockChange(any(Location.class), org.mockito.ArgumentMatchers.eq(real));
        }
    }

    @Test void givenHoverChange_whenRotate_thenHoverDelivered() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        inputs.clear();
        runtime.onRotate(p, 0f, 0f);
        runtime.onRotate(p, 0f, -30f);
        assertTrue(inputs.contains(new ScreenInput.Hover("centre")));
        assertTrue(inputs.contains(new ScreenInput.Hover(null)));
    }

    @Test void givenOpenSession_whenSetLayout_thenOldWidgetsRemovedAndNewSpawned() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        var session = runtime.session(p).orElseThrow();
        int before = removed.size(), spawnedBefore = spawned.size();
        session.setLayout(new ScreenLayout(0.9, 0.55, List.of(
                ScreenWidget.icon("i", 0.1, 0.1, 0.1, 0.1, Material.COMPASS),
                ScreenWidget.button("b", 0.3, 0.3, 0.2, 0.08, "B"))));
        assertEquals(before + 4, removed.size(), "label, button box, button text and the pointer are removed");
        assertEquals(spawnedBefore + 4, spawned.size(), "icon, button box, button text and the pointer again on top");
        List<Entity> fresh = spawned.subList(spawnedBefore, spawned.size());
        runtime.close(p, ScreenInput.Reason.PLAYER);
        assertTrue(removed.containsAll(fresh), "the new widgets are removed on close");
    }

    // ---- map widgets (0.59.0) ----

    private final List<List<byte[]>> shownTiles = new ArrayList<>();
    private final List<byte[]> pushed = new ArrayList<>();
    private final List<ScreenMapCodec.Placement> placements = new ArrayList<>();
    private int mapRemovals;
    private boolean mapsSupported = true;

    private final ScreenMapFrames fakeMaps = new ScreenMapFrames() {
        @Override public boolean supported() { return mapsSupported; }
        @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) {
            shownTiles.add(tiles);
            placements.add(at);
            return new Handle() {
                @Override public void pixels(int tile, byte[] colours) { pushed.add(colours); }
                @Override public void remove() { mapRemovals++; }
            };
        }
    };

    private static final ScreenWidget.MapGrid GRID = new ScreenWidget.MapGrid(2, 1);

    private ScreenSpec mapSpec() {
        ScreenLayout layout = new ScreenLayout(1.0, 0.6, List.of(
                ScreenWidget.map("map", 0.1, 0.1, 0.6, 0.3, GRID, "no map"),
                ScreenWidget.button("close", 0.75, 0.1, 0.2, 0.08, "Close")));
        return ScreenSpec.builder("test:map", layout).onInput((s, i) -> inputs.add(i)).build();
    }

    private TextDisplay pointer() {
        // the pointer is the last text display spawned
        return (TextDisplay) spawned.stream().filter(e -> e instanceof TextDisplay).reduce((a, b) -> b).orElseThrow();
    }

    @Test void givenPinnedMapScreen_whenOpened_thenFramesWithHeadersShownAndPointerDepthTested() {
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        assertEquals(ScreenSpec.Mode.PINNED, runtime.open(core, p, mapSpec()).mode());
        assertEquals(1, shownTiles.size());
        List<byte[]> tiles = shownTiles.get(0);
        assertEquals(2, tiles.size());
        // tile 0: left half of the widget. Widget x 0.1..0.7 of a 1.0 panel -> centre-origin -0.4..0.2; tile 0 centre -0.25
        assertEquals(-0.25, ScreenMapCodec.read(tiles.get(0), 16) / ScreenMapCodec.FIXED - ScreenMapCodec.OFFSET, 1e-3);
        assertEquals(0.05, ScreenMapCodec.read(tiles.get(1), 16) / ScreenMapCodec.FIXED - ScreenMapCodec.OFFSET, 1e-3);
        // y: top 0.3 - 0.1 = 0.2, half height 0.15 -> centre 0.05
        assertEquals(0.05, ScreenMapCodec.read(tiles.get(0), 32) / ScreenMapCodec.FIXED - ScreenMapCodec.OFFSET, 1e-3);
        assertEquals(0.15, ScreenMapCodec.read(tiles.get(0), 48) / ScreenMapCodec.FIXED, 1e-3);
        assertEquals(0.15, ScreenMapCodec.read(tiles.get(0), 64) / ScreenMapCodec.FIXED, 1e-3);
        assertEquals(ScreenMapCodec.placement(10, 65.62, 10), placements.get(0), "frames hang at the seated eye");
        verify(pointer()).setSeeThrough(false);
        assertTrue(runtime.session(p).orElseThrow().showsMap("map"));
    }

    @Test void givenShownMap_whenSetMap_thenEveryTileSentAndRemovedOnClose() {
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        runtime.open(core, p, mapSpec());
        byte[] canvas = new byte[GRID.pixelWidth() * GRID.pixelHeight()];
        canvas[0] = 42;
        canvas[GRID.pixelWidth() - 1] = 43;
        runtime.session(p).orElseThrow().setMap("map", canvas);
        assertEquals(2, pushed.size());
        assertEquals(42, pushed.get(0)[128], "canvas row 0 is tile row 1");
        assertEquals(43, pushed.get(1)[128 + 127]);
        runtime.close(p, ScreenInput.Reason.PLAYER);
        assertEquals(1, mapRemovals);
    }

    @Test void givenWrongPixelCount_whenSetMap_thenRejected() {
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        runtime.open(core, p, mapSpec());
        assertThrows(IllegalArgumentException.class, () -> runtime.session(p).orElseThrow().setMap("map", new byte[10]));
    }

    @Test void givenFloatingScreen_whenMapWidget_thenPlaceholderAndNoFrames() {
        runtime.useMaps(fakeMaps);
        pack = false;
        Player p = player(0, 0);
        assertEquals(ScreenSpec.Mode.FLOATING, runtime.open(core, p, mapSpec()).mode());
        assertTrue(shownTiles.isEmpty());
        assertFalse(runtime.session(p).orElseThrow().showsMap("map"));
        assertDoesNotThrow(() -> runtime.session(p).orElseThrow().setMap("map", new byte[GRID.pixelWidth() * GRID.pixelHeight()]));
        assertTrue(pushed.isEmpty());
    }

    @Test void givenNoFrameSupport_whenPinnedMap_thenPlaceholderAndSeeThroughPointer() {
        mapsSupported = false;
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        runtime.open(core, p, mapSpec());
        assertTrue(shownTiles.isEmpty());
        verify(pointer()).setSeeThrough(true);
    }

    @Test void givenMapUnderPointer_whenClick_thenClickCarriesMapId() {
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        ScreenLayout layout = new ScreenLayout(1.0, 0.6, List.of(ScreenWidget.map("map", 0.2, 0.1, 0.6, 0.4, GRID, "")));
        runtime.open(core, p, ScreenSpec.builder("test:map", layout).onInput((s, i) -> inputs.add(i)).build());
        Entity interaction = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        runtime.onClick(p, interaction, false);
        ScreenInput.Click click = (ScreenInput.Click) inputs.stream().filter(i -> i instanceof ScreenInput.Click).findFirst().orElseThrow();
        assertEquals("map", click.widgetId());
        int[] px = layout.widgets().get(0).mapPixel(click.u(), click.v(), 1.0, 0.6);
        assertEquals(GRID.pixelWidth() / 2, px[0], 1);
        assertEquals(GRID.pixelHeight() / 2, px[1], 1);
    }

    @Test void givenTexts_whenMeasured_thenNarrowGlyphsCountLess() {
        assertTrue(ScreenRuntime.textWidth("iiii") < ScreenRuntime.textWidth("MMMM"));
        assertEquals(2 + 6 * 5, ScreenRuntime.textWidth("Hello".replace("l", "o")));
    }
}
