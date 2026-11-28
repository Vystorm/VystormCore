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
        /** Like the live one: the setup runs inside the spawn, then the entity is shown (its spawn bundle is written). */
        @Override public TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup) {
            TextDisplay d = entity(TextDisplay.class);
            setup.accept(d);
            shown(d);
            return d;
        }
        @Override public Interaction interaction(Player viewer, Location at, float w, float h) { return entity(Interaction.class); }
        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) { org.bukkit.entity.ItemDisplay d = entity(org.bukkit.entity.ItemDisplay.class); shown(d); return d; }
        @Override public void remove(Entity entity) { removed.add(entity); events.add(new Event("remove", entity)); }
    };

    /** Spawns ("show", the moment the spawn bundle goes out) and removes in call order. */
    record Event(String kind, Entity entity) {}
    private final List<Event> events = new ArrayList<>();
    /** Texts a display carried when it was shown, and how many values had been written to it by then. */
    private final java.util.Map<Entity, List<String>> textsAtShow = new java.util.HashMap<>();
    private final java.util.Map<Entity, Integer> writesAtShow = new java.util.HashMap<>();

    private void shown(Entity e) {
        events.add(new Event("show", e));
        textsAtShow.put(e, texts(e));
        writesAtShow.put(e, writes(e));
    }

    /** Every text set on a display so far, as plain text. */
    private static List<String> texts(Entity e) {
        return mockingDetails(e).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("text") && i.getArguments().length == 1)
                .map(i -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component) i.getArgument(0)))
                .toList();
    }

    /** Values written to an entity (setters and {@code text}); each one is an entity-data update once it is shown. */
    private static int writes(Entity e) {
        return (int) mockingDetails(e).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("set") || i.getMethod().getName().equals("text")).count();
    }

    private List<Entity> shownAfter(int from) {
        return events.subList(from, events.size()).stream().filter(ev -> ev.kind().equals("show")).map(Event::entity).toList();
    }

    /** {@code new ItemStack(AIR)} needs a running server; here it is a mock of type AIR. */
    private org.mockito.MockedConstruction<org.bukkit.inventory.ItemStack> airStacks;

    @org.junit.jupiter.api.AfterEach void closeAirStacks() { airStacks.close(); }

    @BeforeEach void setUp() {
        airStacks = mockConstruction(org.bukkit.inventory.ItemStack.class, (stack, context) -> when(stack.getType()).thenReturn(Material.AIR));
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

    @Test void givenSpectator_whenOpen_thenTheClassicMenuOpensWithoutALine() {
        List<String> told = new ArrayList<>();
        runtime.noticeRefusals((who, key) -> told.add(key));
        Player p = player(0, 0);
        when(p.getGameMode()).thenReturn(org.bukkit.GameMode.SPECTATOR);
        AtomicBoolean fallback = new AtomicBoolean();
        ScreenOpenResult result = runtime.open(core, p, spec(fallback));
        assertEquals(ScreenRuntime.REFUSED_SPECTATOR, result.refusedKey());
        assertTrue(fallback.get(), "a spectator gets the chest or dialog, which a spectator can click");
        assertTrue(spawned.isEmpty(), "no screen entity for a spectator");
        assertTrue(told.isEmpty(), "the menu opens, so there is nothing to explain");
        assertTrue(ScreenRuntime.silentRefusal(ScreenRuntime.REFUSED_SPECTATOR));
    }

    @Test void givenRepeatedRefusals_whenOpen_thenOneLineEveryFewSeconds() {
        List<String> told = new ArrayList<>();
        runtime.noticeRefusals((who, key) -> told.add(key));
        Player p = player(0, 0);
        when(p.isInWater()).thenReturn(true);
        // typed five times in a row: one line, not five
        for (int i = 0; i < 5; i++) { runtime.open(core, p, spec(new AtomicBoolean())); now += 500; }
        assertEquals(1, told.size());
        now += ScreenRuntime.REFUSAL_REPEAT_MILLIS;
        runtime.open(core, p, spec(new AtomicBoolean()));
        assertEquals(2, told.size(), "told again after a few seconds");
    }

    @Test void givenDeadPlayer_whenOpen_thenRefusedDead() {
        List<String> told = new ArrayList<>();
        runtime.noticeRefusals((who, key) -> told.add(key));
        Player p = player(0, 0);
        when(p.isDead()).thenReturn(true);
        assertEquals("screen.refused.dead", runtime.open(core, p, spec(new AtomicBoolean())).refusedKey());
        assertEquals(List.of("screen.refused.dead"), told);
        assertTrue(spawned.isEmpty());
    }

    @Test void givenEveryRefusalReason_whenOpen_thenThePlayerIsTold() {
        List<String> told = new ArrayList<>();
        runtime.noticeRefusals((who, key) -> told.add(key));
        Player riding = player(0, 0);
        when(riding.isInsideVehicle()).thenReturn(true);
        Player gliding = player(0, 0);
        when(gliding.isGliding()).thenReturn(true);
        Player falling = player(0, 0);
        when(falling.getFallDistance()).thenReturn(4f);
        when(falling.isOnGround()).thenReturn(false);
        Player swimming = player(0, 0);
        when(swimming.isInWater()).thenReturn(true);
        Player hit = player(0, 0);
        runtime.onDamage(hit);
        for (Player p : List.of(riding, gliding, falling, swimming, hit)) assertFalse(runtime.open(core, p, spec(new AtomicBoolean())).opened());
        assertEquals(List.of("screen.refused.riding", "screen.refused.gliding", "screen.refused.falling", "screen.refused.liquid", "screen.refused.combat"), told);
    }

    @Test void givenSilentRefusalOrQuietMirror_whenOpen_thenNothingIsTold() {
        List<String> told = new ArrayList<>();
        runtime.noticeRefusals((who, key) -> told.add(key));
        pack = true;
        runtime.requirePack(p -> false);
        assertEquals(ScreenRuntime.REFUSED_PACK, runtime.open(core, player(0, 0), spec(new AtomicBoolean())).refusedKey());
        runtime.requirePack(null);
        runtime.blockWhen((owner, keys) -> true);
        assertEquals(ScreenRuntime.REFUSED_OFF, runtime.open(core, player(0, 0), spec(new AtomicBoolean())).refusedKey());
        runtime.blockWhen(null);
        Player swimming = player(0, 0);
        when(swimming.isInWater()).thenReturn(true);
        assertEquals("screen.refused.liquid", runtime.open(core, swimming, spec(new AtomicBoolean()), null, false).refusedKey());
        assertTrue(told.isEmpty(), "screens off, no pack and a refused chest mirror explain themselves: " + told);
        assertFalse(runtime.tellRefusal(swimming, ScreenRuntime.REFUSED_OFF));
        assertTrue(runtime.tellRefusal(swimming, "screen.refused.liquid"));
        assertFalse(runtime.tellRefusal(swimming, "screen.refused.liquid"), "the caller's second line within a few seconds is dropped");
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
        // 0.65.0: while the click guard is up, a bare arm swing is the held button on the block behind, not a click
        // (was: + 200 ms); a new press is an attack on the click box and counts at once, see the guard tests below
        now += ScreenRuntime.GUARD_MILLIS + 1;
        runtime.onSwing(p);
        assertEquals(before + 1, clicks());
    }

    // ---- click guard (0.65.0): a click that closes or replaces a screen must not reach the world ----

    /** A screen that closes when it is clicked, like a Close button or a tile that leads to a chest menu. */
    private ScreenSpec closingSpec() {
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Close")));
        return ScreenSpec.builder("test:closing", layout).onInput((s, i) -> {
            inputs.add(i);
            if (i instanceof ScreenInput.Click) s.close();
        }).build();
    }

    /** The click box of the player's newest screen. */
    private Entity box() {
        Entity last = null;
        for (Entity e : spawned) if (e instanceof Interaction) last = e;
        return last;
    }

    @Test void givenClickThatClosesTheScreen_whenTheHeldButtonReachesTheWorld_thenStoppedForAShortTimeOnly() {
        Player p = player(0, 0);
        assertFalse(runtime.stopsWorldAction(p, true), "normal play is never touched");
        runtime.open(core, p, closingSpec());
        assertTrue(runtime.stopsWorldAction(p, true), "an open screen takes every click");
        runtime.onClick(p, box(), false);
        assertFalse(runtime.hasSession(p));
        now += 60;   // the client now aims at the block behind the screen and the button is still down
        assertTrue(runtime.stopsWorldAction(p, true), "break, mining, use and hit are cancelled");
        now += ScreenRuntime.GUARD_MILLIS;
        assertFalse(runtime.stopsWorldAction(p, true), "half a second later the player breaks blocks again");
        assertFalse(runtime.guarded(p.getUniqueId()));
    }

    @Test void givenButtonHeldDown_whenActionsKeepComing_thenGuardedUntilTheCapAndNotLonger() {
        Player p = player(0, 0);
        runtime.open(core, p, closingSpec());
        runtime.onClick(p, box(), true);
        long click = now;
        // creative breaks again every 6 ticks (300 ms) while the button is down, a held right button uses every 5 ticks
        for (now = click + 300; now <= click + ScreenRuntime.GUARD_MAX_MILLIS; now += 300)
            assertTrue(runtime.stopsWorldAction(p, true), "still held at +" + (now - click) + " ms");
        now = click + ScreenRuntime.GUARD_MAX_MILLIS + 1;
        assertFalse(runtime.stopsWorldAction(p, true), "the guard never blocks play for longer than its cap");
    }

    @Test void givenGuard_whenOnlyArmSwingsFollow_thenItEndsOnTime() {
        Player p = player(0, 0);
        runtime.open(core, p, closingSpec());
        runtime.onClick(p, box(), false);
        now += ScreenRuntime.GUARD_MILLIS - 50;
        assertTrue(runtime.stopsWorldAction(p, false));
        now += 51;
        assertFalse(runtime.stopsWorldAction(p, true), "a swing is no sign of a held button and does not prolong the guard");
    }

    @Test void givenGuard_whenTheClientReportsTheButtonReleased_thenItEndsAtOnce() {
        Player p = player(0, 0);
        runtime.open(core, p, closingSpec());
        runtime.onClick(p, box(), false);
        now += 100;
        assertTrue(runtime.stopsWorldAction(p, true));
        runtime.onButtonReleased(p);
        assertTrue(runtime.stopsWorldAction(p, false), "the same tick is still covered (another block aimed at)");
        now += ScreenRuntime.GUARD_RELEASE_MILLIS + 1;
        assertFalse(runtime.stopsWorldAction(p, true));
    }

    @Test void givenScreenClosedWithoutAClick_whenThePlayerActs_thenNoGuard() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.onClick(p, box(), false);                  // a click that changes nothing
        now += ScreenRuntime.CLICK_CLOSES_MILLIS + 1;
        runtime.onSneak(p, true);                          // closed with the sneak key
        assertFalse(runtime.stopsWorldAction(p, true));
        runtime.open(core, p, spec(new AtomicBoolean()));
        now += 1_000;
        runtime.close(p, ScreenInput.Reason.COMMAND);      // /screen close
        assertFalse(runtime.stopsWorldAction(p, true));
    }

    @Test void givenHandlerThatClosesOneTickAfterTheClick_whenClosed_thenGuardedToo() {
        Player p = player(0, 0);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.onClick(p, box(), false);
        now += 50;
        runtime.close(p, ScreenInput.Reason.PLUGIN);       // e.g. a chest screen whose plugin closed the menu on that click
        assertTrue(runtime.stopsWorldAction(p, true));
    }

    @Test void givenSlowClickHandlerThatCloses_whenClosed_thenTheGuardStartsAtTheClose() {
        Player p = player(0, 0);
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Save")));
        runtime.open(core, p, ScreenSpec.builder("test:slow-close", layout).onInput((s, i) -> {
            if (i instanceof ScreenInput.Click) { now += 3_000; s.close(); }
        }).build());
        runtime.onClick(p, box(), false);
        now += 100;
        assertTrue(runtime.stopsWorldAction(p, true), "3 s of saving must not use up the guard");
    }

    @Test void givenClickThatOpensAnotherScreen_whenTheNewOneIsStillBeingBuilt_thenItsClickBoxIsThereAndNothingLeaks() {
        List<Runnable> queue = new ArrayList<>();
        runtime = new ScreenRuntime(core, fake, () -> now, p -> pack, p -> false, queue::add);
        Player p = player(0, 0);
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Next")));
        ScreenSpec second = spec(new AtomicBoolean());
        runtime.open(core, p, ScreenSpec.builder("test:first", layout).onInput((s, i) -> {
            if (i instanceof ScreenInput.Click) runtime.open(core, p, second);
        }).build());
        Entity firstBox = box();
        assertNotNull(firstBox, "the click box exists before the screen is drawn");
        queue.remove(0).run();                              // two ticks later: the first screen is drawn
        runtime.onClick(p, firstBox, false);                // the click that opens the second screen
        Entity secondBox = box();
        assertNotSame(firstBox, secondBox, "the new click box is spawned in the same tick, not two ticks later");
        assertTrue(removed.contains(firstBox));
        assertTrue(runtime.stopsWorldAction(p, true));
        long before = clicks();
        now += 60;
        runtime.onSwing(p);                                 // the held button swings at the block behind
        assertTrue(runtime.onClick(p, secondBox, false), "the event is the screen's (cancelled) …");
        assertEquals(before, clicks(), "… but a screen that is not drawn yet cannot be clicked");
        queue.remove(0).run();                              // the second screen is drawn
        now += 140;                                         // 200 ms after the click: the old 150 ms window is over
        runtime.onSwing(p);
        assertEquals(before, clicks(), "a bare swing during the guard is the held button, not a click");
        runtime.onClick(p, secondBox, false);               // a new press on the new screen
        assertEquals(before + 1, clicks(), "a real click on the new screen counts at once");
    }

    @Test void givenRightClickThatOpensAnotherScreen_whenTheButtonStaysDown_thenItsRepeatsDoNotClickTheNewScreen() {
        Player p = player(0, 0);
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Next")));
        ScreenSpec second = spec(new AtomicBoolean());
        runtime.open(core, p, ScreenSpec.builder("test:first", layout).onInput((s, i) -> {
            if (i instanceof ScreenInput.Click) runtime.open(core, p, second);
        }).build());
        runtime.onClick(p, box(), true);
        long click = now, before = clicks();
        for (now = click + 200; now <= click + 1_000; now += 200)    // the client repeats "use" every 4 ticks while held
            assertTrue(runtime.onClick(p, box(), true));
        assertEquals(before, clicks(), "one press is one click");
        now = click + ScreenRuntime.GUARD_MAX_MILLIS + 1;
        runtime.onClick(p, box(), true);
        assertEquals(before + 1, clicks(), "after the guard the right button works again");
    }

    @Test void givenGuardedPlayer_whenQuit_thenNothingIsKept() {
        Player p = player(0, 0);
        runtime.open(core, p, closingSpec());
        runtime.onClick(p, box(), false);
        assertTrue(runtime.guarded(p.getUniqueId()));
        runtime.close(p, ScreenInput.Reason.QUIT);
        assertFalse(runtime.guarded(p.getUniqueId()));
    }

    // ---- 0.65.0: one click is one click, however long its handler takes ----

    /** A screen whose click handler blocks the server for {@code millis} (a config save with a reload) and rebuilds itself. */
    private ScreenSpec slowSpec(long millis) {
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Switch")));
        return ScreenSpec.builder("test:slow", layout).onInput((s, i) -> {
            inputs.add(i);
            if (i instanceof ScreenInput.Click) { now += millis; s.setLayout(layout); }
        }).build();
    }

    @Test void givenClickHandlerThatTakesSeconds_whenTheSecondHalfOfTheClickArrives_thenTheSwitchIsClickedOnce() {
        Player p = player(0, 0);
        runtime.open(core, p, slowSpec(3_000));
        Entity interaction = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        runtime.onClick(p, interaction, false);   // the attack on the click box: handled for 3 s
        runtime.onSwing(p);                       // the arm swing of the same click, read from the queue afterwards
        assertEquals(1, clicks(), "one physical click");
        now += 200;
        runtime.onSwing(p);                       // the next real click
        assertEquals(2, clicks());
    }

    @Test void givenSlowHandler_whenTheSwingComesFirstAndTheAttackSecond_thenAlsoOnce() {
        Player p = player(0, 0);
        runtime.open(core, p, slowSpec(400));
        Entity interaction = spawned.stream().filter(e -> e instanceof Interaction).findFirst().orElseThrow();
        runtime.onSwing(p);
        runtime.onClick(p, interaction, false);
        assertEquals(1, clicks());
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

    // ---- 0.66.2: smooth relayouts ----

    /** A label and a button away from the panel centre, so a player looking straight ahead hovers nothing. */
    private ScreenLayout sideLayout(String label, String button) {
        return new ScreenLayout(0.9, 0.55, List.of(
                ScreenWidget.label("l." + label, 0.05, 0.02, 0.8, 0.08, label),
                ScreenWidget.button("b." + button, 0.05, 0.40, 0.2, 0.08, button)));
    }

    /** Every text display shown since event {@code from} carried a text when it was shown, and nothing was written to it later. */
    private void assertCompleteSpawns(int from, String... widgetTexts) {
        List<Entity> fresh = shownAfter(from).stream().filter(e -> e instanceof TextDisplay).toList();
        assertFalse(fresh.isEmpty());
        for (Entity e : fresh) assertFalse(textsAtShow.get(e).isEmpty(), "a text display is shown only with its text");
        for (String text : widgetTexts) {
            Entity d = fresh.stream().filter(e -> textsAtShow.get(e).contains(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("no display was shown with \"" + text + "\""));
            assertEquals(writesAtShow.get(d), writes(d), "\"" + text + "\" gets no second data update for unchanged values");
        }
    }

    @Test void givenLabelsAndButtons_whenTheScreenOpens_thenEveryTextDisplayIsShownWithItsText() {
        Player p = player(0, 0);
        runtime.open(core, p, ScreenSpec.builder("test:side", sideLayout("Title", "Go")).onInput((s, i) -> inputs.add(i)).build());
        assertCompleteSpawns(0, "Title", "Go");
    }

    @Test void givenOpenScreen_whenSetLayout_thenTheNewTextsArriveWithTheirSpawns() {
        Player p = player(0, 0);
        runtime.open(core, p, ScreenSpec.builder("test:side", sideLayout("Title", "Go")).onInput((s, i) -> inputs.add(i)).build());
        int from = events.size();
        runtime.session(p).orElseThrow().setLayout(sideLayout("Page two", "Next"));
        assertCompleteSpawns(from, "Page two", "Next");
    }

    /** One button under the centre of the view, as on a tab change where the new page reuses the id for another widget. */
    private static ScreenLayout centreButton(String text) {
        return new ScreenLayout(0.9, 0.55, List.of(
                ScreenWidget.label("t", 0.05, 0.02, 0.8, 0.08, "Tab " + text),
                ScreenWidget.button("same", 0.35, 0.235, 0.2, 0.08, text)));
    }

    @Test void givenOpenScreen_whenSetLayout_thenTheNewSetAndThePointerAreShownBeforeTheOldSetIsRemoved() {
        Player p = player(0, 0);
        runtime.open(core, p, ScreenSpec.builder("test:tabs", centreButton("Old")).onInput((s, i) -> inputs.add(i)).build());
        runtime.onRotate(p, 0f, 0f);
        assertTrue(inputs.contains(new ScreenInput.Hover("same")));
        int from = events.size();
        List<Entity> before = List.copyOf(spawned);
        runtime.session(p).orElseThrow().setLayout(centreButton("New"));
        List<Event> relayout = List.copyOf(events.subList(from, events.size()));
        int lastShow = -1, firstRemove = Integer.MAX_VALUE;
        for (int i = 0; i < relayout.size(); i++) {
            if (relayout.get(i).kind().equals("show")) lastShow = i;
            else firstRemove = Math.min(firstRemove, i);
        }
        assertTrue(lastShow >= 0 && firstRemove < relayout.size(), "something was shown and something removed");
        assertTrue(lastShow < firstRemove, "every old entity is removed after the whole new set is shown: " + relayout);
        Entity newPointer = relayout.get(lastShow).entity();
        assertTrue(textsAtShow.get(newPointer).contains(ScreenShader.POINTER), "the pointer is shown last, on top of the new set");
        assertTrue(relayout.stream().filter(ev -> ev.kind().equals("remove")).allMatch(ev -> before.contains(ev.entity())), "only old entities are removed");
        assertEquals(4, relayout.stream().filter(ev -> ev.kind().equals("remove")).count(), "label, button box, button text and the old pointer");

        // hover and clicks belong to the new set: the old entities are never written again
        java.util.Map<Entity, Integer> oldWrites = new java.util.HashMap<>();
        for (Entity e : before) oldWrites.put(e, writes(e));
        Entity newBox = shownAfter(from).stream().filter(e -> textsAtShow.get(e).contains(ScreenShader.SOLID)).findFirst().orElseThrow();
        int boxWrites = writes(newBox);
        inputs.clear();
        runtime.onRotate(p, 0f, -30f);
        runtime.onRotate(p, 0f, 0f);
        assertEquals(List.of(new ScreenInput.Hover(null), new ScreenInput.Hover("same")), inputs);
        assertTrue(writes(newBox) > boxWrites, "the hover colour goes to the new box");
        for (Entity e : before) assertEquals(oldWrites.get(e), writes(e), "an old entity is not drawn after the relayout");
        runtime.onClick(p, box(), false);
        assertEquals("same", ((ScreenInput.Click) inputs.get(inputs.size() - 1)).widgetId());
    }

    @Test void givenShownMap_whenSetLayout_thenTheOldFramesGoAfterTheNewOnesAreShown() {
        List<String> order = new ArrayList<>();
        runtime.useMaps(new ScreenMapFrames() {
            int shown;
            @Override public boolean supported() { return true; }
            @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) {
                int n = ++shown;
                order.add("show " + n);
                return new Handle() {
                    @Override public void pixels(int tile, byte[] colours) { }
                    @Override public void remove() { order.add("remove " + n); }
                };
            }
        });
        Player p = player(0, 0);
        runtime.open(core, p, mapSpec());
        runtime.session(p).orElseThrow().setLayout(mapSpec().layout());
        assertEquals(List.of("show 1", "show 2", "remove 1"), order);
    }

    // wheel coalescing

    /** A list that scrolls by whole rows like MenuScreen: offset += delta, and a full relayout for every Scroll. */
    private final int[] offset = {0};
    private int relayouts;

    private ScreenSpec rowList() {
        return ScreenSpec.builder("test:rows", sideLayout("Row 0", "Go")).onInput((s, i) -> {
            inputs.add(i);
            if (i instanceof ScreenInput.Scroll scroll) {
                offset[0] += scroll.delta();
                relayouts++;
                s.setLayout(sideLayout("Row " + offset[0], "Go"));
            }
        }).build();
    }

    private List<ScreenInput.Scroll> scrolls() {
        return inputs.stream().filter(i -> i instanceof ScreenInput.Scroll).map(i -> (ScreenInput.Scroll) i).toList();
    }

    @Test void givenThreeWheelNotchesInOneTick_whenTheTickEnds_thenOneRelayoutByThreeRows() {
        List<Runnable> tick = new ArrayList<>();
        runtime.coalesceWheel(tick::add);
        Player p = player(0, 0);
        runtime.open(core, p, rowList());
        int removedBefore = removed.size();
        // the cancelled slot change is set back on the client one round trip later, so the server slot stays 4
        runtime.onWheel(p, 4, 5);
        runtime.onWheel(p, 4, 6);
        runtime.onWheel(p, 4, 7);
        assertEquals(0, relayouts, "nothing is rebuilt during the tick");
        assertEquals(removedBefore, removed.size());
        assertEquals(1, tick.size(), "one task for the whole tick");
        tick.remove(0).run();
        assertEquals(List.of(new ScreenInput.Scroll(3)), scrolls());
        assertEquals(1, relayouts);
        assertEquals(3, offset[0], "three notches move three rows");
    }

    @Test void givenNotchesInDifferentTicks_whenEachTickEnds_thenOneRelayoutEach() {
        List<Runnable> tick = new ArrayList<>();
        runtime.coalesceWheel(tick::add);
        Player p = player(0, 0);
        runtime.open(core, p, rowList());
        runtime.onWheel(p, 4, 5);
        tick.remove(0).run();
        runtime.onWheel(p, 4, 3);   // the client was set back to 4 meanwhile, then one notch up
        tick.remove(0).run();
        runtime.onWheel(p, 4, 3);
        tick.remove(0).run();
        assertEquals(List.of(new ScreenInput.Scroll(1), new ScreenInput.Scroll(-1), new ScreenInput.Scroll(-1)), scrolls());
        assertEquals(3, relayouts);
        assertEquals(-1, offset[0]);
    }

    @Test void givenAWildSpin_whenTheTickEnds_thenTheStepsAreCapped() {
        List<Runnable> tick = new ArrayList<>();
        runtime.coalesceWheel(tick::add);
        Player p = player(0, 0);
        runtime.open(core, p, rowList());
        for (int i = 0; i < 6; i++) runtime.onScroll(p, 4);
        tick.remove(0).run();
        assertEquals(List.of(new ScreenInput.Scroll(ScreenRuntime.MAX_WHEEL_STEPS)), scrolls());
    }

    @Test void givenWheelNotchesPending_whenTheScreenClosesFirst_thenNothingIsDelivered() {
        List<Runnable> tick = new ArrayList<>();
        runtime.coalesceWheel(tick::add);
        Player p = player(0, 0);
        runtime.open(core, p, rowList());
        runtime.onWheel(p, 4, 5);
        runtime.close(p, ScreenInput.Reason.PLAYER);
        tick.remove(0).run();
        assertTrue(scrolls().isEmpty());
    }

    @Test void givenSlotJumps_whenCountingWheelNotches_thenTheSetBackSlotIsAllowedFor() {
        // first report: the jump from the server slot
        assertEquals(1, ScreenRuntime.wheelSteps(4, 5, -1));
        assertEquals(3, ScreenRuntime.wheelSteps(4, 7, -1), "three notches within one client tick");
        assertEquals(-1, ScreenRuntime.wheelSteps(0, 8, -1), "wrap-around: slot 0 up to slot 8 is one notch");
        assertEquals(1, ScreenRuntime.wheelSteps(8, 0, -1));
        // the client has not got the set-back yet: count from its last report
        assertEquals(1, ScreenRuntime.wheelSteps(4, 6, 5));
        assertEquals(2, ScreenRuntime.wheelSteps(4, 8, 6));
        assertEquals(1, ScreenRuntime.wheelSteps(8, 1, 0), "wrap-around while the set-back is on its way");
        // the client was set back to the server slot: count from there
        assertEquals(1, ScreenRuntime.wheelSteps(4, 5, 5));
        assertEquals(1, ScreenRuntime.wheelSteps(4, 5, 6), "after a set-back the direction comes from the server slot");
        assertEquals(-1, ScreenRuntime.wheelSteps(4, 3, 6));
        // the client tells the server it is back on the server slot: no notch
        assertEquals(0, ScreenRuntime.wheelSteps(4, 4, 6));
    }

    // ---- map widgets (0.59.0) ----

    private final List<List<byte[]>> shownTiles = new ArrayList<>();
    private final List<byte[]> pushed = new ArrayList<>();
    private final List<int[]> patched = new ArrayList<>();
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
                @Override public void patch(int tile, int x, int y, int w, int h, byte[] colours) { patched.add(new int[] { tile, x, y, w, h }); pushed.add(colours); }
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

    @Test void givenShownMap_whenSetMap_thenTheChangedRectangleOfEveryTileSentAndRemovedOnClose() {
        runtime.useMaps(fakeMaps);
        Player p = player(0, 0);
        runtime.open(core, p, mapSpec());
        byte[] canvas = new byte[GRID.pixelWidth() * GRID.pixelHeight()];
        canvas[0] = 42;
        canvas[GRID.pixelWidth() - 1] = 43;
        runtime.session(p).orElseThrow().setMap("map", canvas);
        // screen canvas: only the changed part (rows 1..127, the header row stays) of each tile, no full tile
        assertEquals(2, pushed.size());
        assertArrayEquals(new int[] { 0, 0, 1, 128, 127 }, patched.get(0));
        assertArrayEquals(new int[] { 1, 0, 1, 128, 127 }, patched.get(1));
        assertEquals(42, pushed.get(0)[0], "canvas row 0 is tile row 1");
        assertEquals(43, pushed.get(1)[127]);
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

    @Test void givenClientWithoutCorePack_whenOpen_thenFallbackAndNothingSpawned() {
        java.util.Set<UUID> withPack = new java.util.HashSet<>();
        runtime.requirePack(p -> withPack.contains(p.getUniqueId()));
        Player p = player(0, 0);
        AtomicBoolean fallback = new AtomicBoolean();
        ScreenOpenResult result = runtime.open(core, p, spec(fallback));
        assertFalse(result.opened());
        assertEquals(ScreenRuntime.REFUSED_PACK, result.refusedKey());
        assertTrue(ScreenRuntime.silentRefusal(result.refusedKey()), "the player already got the pack message at join");
        assertTrue(fallback.get(), "the chest or dialog shows instead");
        assertTrue(spawned.isEmpty(), "no mount, no displays: nothing is drawn with a font the client does not have");
        assertTrue(runtime.session(p).isEmpty());
        withPack.add(p.getUniqueId());
        fallback.set(false);
        assertTrue(runtime.open(core, p, spec(fallback)).opened(), "once the pack is applied the screen opens");
        assertFalse(fallback.get());
    }

    @Test void givenServerVersions_thenOnly26_3AndNewerCount() {
        assertTrue(ScreenRuntime.atLeast263("26.3-R0.1-SNAPSHOT"));
        assertTrue(ScreenRuntime.atLeast263("26.10"));
        assertTrue(ScreenRuntime.atLeast263("27.1-R0.1-SNAPSHOT"));
        assertFalse(ScreenRuntime.atLeast263("26.2-R0.1-SNAPSHOT"));
        assertFalse(ScreenRuntime.atLeast263("1.20.6-R0.1-SNAPSHOT"));
        assertFalse(ScreenRuntime.atLeast263(null));
        assertFalse(ScreenRuntime.atLeast263("unknown"));
    }

    @Test void givenClientThatCannotDrawScreenMaps_whenMapScreenOpens_thenPlaceholderAndSeeThroughPointer() {
        List<Player> asked = new ArrayList<>();
        runtime.useMaps(new ScreenMapFrames() {
            @Override public boolean supported() { return true; }
            @Override public Handle show(Player viewer, ScreenMapCodec.Placement at, List<byte[]> tiles) { fail("no map frames for this client"); return null; }
        });
        runtime.mapsDrawableFor(p -> { asked.add(p); return false; });
        Player p = player(0, 0);
        ScreenWidget map = ScreenWidget.map("map", 0.1, 0.1, 0.3, 0.3, new ScreenWidget.MapGrid(1, 1), "no map here");
        ScreenOpenResult result = runtime.open(core, p, ScreenSpec.builder("test:map", new ScreenLayout(0.9, 0.55, List.of(map))).build());
        assertTrue(result.opened());
        assertEquals(List.of(p), asked);
        TextDisplay pointer = (TextDisplay) spawned.get(spawned.size() - 1);
        verify(pointer).setSeeThrough(true);
    }

    // ---- hands under a PINNED screen -----------------------------------------------------------------------------

    private static org.bukkit.inventory.ItemStack stack(Material type) {
        org.bukkit.inventory.ItemStack item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getType()).thenReturn(type);
        return item;
    }

    /** A player holding a sword in hotbar slot 0 (slots 1-8 empty) and {@code offHand} in the off hand. */
    private org.bukkit.inventory.PlayerInventory hands(Player p, org.bukkit.inventory.ItemStack offHand) {
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        org.bukkit.inventory.ItemStack sword = stack(Material.DIAMOND_SWORD);
        when(inventory.getHeldItemSlot()).thenReturn(0);
        when(inventory.getItem(anyInt())).thenAnswer(inv -> (int) inv.getArgument(0) == 0 ? sword : null);
        when(inventory.getItemInOffHand()).thenReturn(offHand);
        when(p.getInventory()).thenReturn(inventory);
        return inventory;
    }

    private static org.bukkit.inventory.ItemStack isAir() {
        return argThat(item -> item != null && item.getType() == Material.AIR);
    }

    @Test void givenSwordInMainHand_whenPinnedOpensAndCloses_thenAnEmptySlotIsHeldMeanwhileAndTheSwordAgainAfter() {
        Player p = player(0, 0);
        org.bukkit.inventory.PlayerInventory inventory = hands(p, null);
        assertTrue(runtime.open(core, p, spec(new AtomicBoolean())).opened());
        verify(inventory).setHeldItemSlot(1);
        verify(p).setInvisible(true);
        runtime.close(p, ScreenInput.Reason.PLAYER);
        verify(inventory).setHeldItemSlot(0);
        verify(p).setInvisible(false);
    }

    @Test void givenShieldInOffHand_whenPinnedOpens_thenOnlyTheClientIsToldTheOffHandIsEmpty() {
        Player p = player(0, 0);
        org.bukkit.inventory.ItemStack shield = stack(Material.SHIELD);
        org.bukkit.inventory.PlayerInventory inventory = hands(p, shield);
        assertTrue(runtime.open(core, p, spec(new AtomicBoolean())).opened());
        verify(p).sendEquipmentChange(eq(p), eq(org.bukkit.inventory.EquipmentSlot.OFF_HAND), isAir());
        verify(inventory, never()).setItemInOffHand(any());
        verify(inventory, never()).setItem(any(org.bukkit.inventory.EquipmentSlot.class), any());
        verify(inventory, never()).setItem(anyInt(), any());
        verify(p, never()).sendEquipmentChange(any(), eq(org.bukkit.inventory.EquipmentSlot.OFF_HAND), eq(shield));
    }

    @Test void givenHiddenOffHand_whenTheScreenCloses_thenTheClientGetsTheRealItemBack() {
        Player p = player(0, 0);
        org.bukkit.inventory.ItemStack shield = stack(Material.SHIELD);
        org.bukkit.inventory.PlayerInventory inventory = hands(p, shield);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.close(p, ScreenInput.Reason.PLAYER);
        verify(p).sendEquipmentChange(p, org.bukkit.inventory.EquipmentSlot.OFF_HAND, shield);
        verify(inventory, never()).setItemInOffHand(any());
    }

    @Test void givenHiddenOffHand_whenTheItemChangedMeanwhile_thenTheClosingSendsWhatTheServerHasNow() {
        Player p = player(0, 0);
        org.bukkit.inventory.PlayerInventory inventory = hands(p, stack(Material.SHIELD));
        runtime.open(core, p, spec(new AtomicBoolean()));
        org.bukkit.inventory.ItemStack totem = stack(Material.TOTEM_OF_UNDYING);
        when(inventory.getItemInOffHand()).thenReturn(totem);
        runtime.close(p, ScreenInput.Reason.PLAYER);
        verify(p).sendEquipmentChange(p, org.bukkit.inventory.EquipmentSlot.OFF_HAND, totem);
    }

    @Test void givenHiddenOffHand_whenThePluginIsDisabled_thenTheRealItemIsShownAgain() {
        Player p = player(0, 0);
        org.bukkit.inventory.ItemStack shield = stack(Material.SHIELD);
        hands(p, shield);
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.release(core);
        assertFalse(runtime.hasSession(p));
        verify(p).sendEquipmentChange(p, org.bukkit.inventory.EquipmentSlot.OFF_HAND, shield);
    }

    @Test void givenHiddenOffHand_whenThePlayerHasLeft_thenNothingIsSentAndTheItemIsUntouched() {
        Player p = player(0, 0);
        org.bukkit.inventory.ItemStack shield = stack(Material.SHIELD);
        org.bukkit.inventory.PlayerInventory inventory = hands(p, shield);
        runtime.open(core, p, spec(new AtomicBoolean()));
        when(p.isOnline()).thenReturn(false);
        runtime.close(p, ScreenInput.Reason.QUIT);
        verify(p, never()).sendEquipmentChange(any(), eq(org.bukkit.inventory.EquipmentSlot.OFF_HAND), eq(shield));
        verify(inventory, never()).setItemInOffHand(any());
    }

    @Test void givenEmptyOffHand_whenPinnedOpensAndCloses_thenNoEquipmentPacket() {
        Player p = player(0, 0);
        hands(p, stack(Material.AIR));
        runtime.open(core, p, spec(new AtomicBoolean()));
        runtime.close(p, ScreenInput.Reason.PLAYER);
        verify(p, never()).sendEquipmentChange(any(), any(org.bukkit.inventory.EquipmentSlot.class), any(org.bukkit.inventory.ItemStack.class));
    }

    @Test void givenFloatingScreen_whenOpened_thenTheOffHandStaysVisible() {
        pack = false;
        Player p = player(0, 0);
        hands(p, stack(Material.SHIELD));
        assertEquals(ScreenSpec.Mode.FLOATING, runtime.open(core, p, spec(new AtomicBoolean())).mode());
        runtime.close(p, ScreenInput.Reason.PLAYER);
        verify(p, never()).sendEquipmentChange(any(), any(org.bukkit.inventory.EquipmentSlot.class), any(org.bukkit.inventory.ItemStack.class));
    }
}
