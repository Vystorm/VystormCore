package Vystorm.vystorm_core.playermenu.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lifecycle.CoreLifecycle;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerMenuRegistryTest {
    private PlayerMenuRegistry registry;
    private Plugin party, city;
    private Player viewer, target;

    @BeforeEach void setUp() {
        registry = new PlayerMenuRegistry();
        party = plugin("Party");
        city = plugin("City");
        viewer = mock(Player.class);
        target = mock(Player.class);
    }

    @AfterEach void tearDown() { PlayerMenuRegistry.get().clear(); }

    @Test void givenEntriesWithPriorities_whenRendered_thenLowerPriorityFirstAndTiesInRegistrationOrder() {
        registry.register(city, entry("stats", 200));
        registry.register(party, entry("invite", 10));
        registry.register(city, entry("invite", 100));
        registry.register(party, entry("kick", 100));

        assertEquals(List.of("party:invite", "city:invite", "party:kick", "city:stats"), keys(registry.render(viewer, target)));
        assertEquals(List.of("party:invite", "city:invite", "party:kick", "city:stats"), registry.keys());
    }

    @Test void givenVisibilityPredicate_whenRendered_thenOnlyMatchingPairsSeeTheEntry() {
        Player other = mock(Player.class);
        registry.register(party, PlayerMenuEntry.builder("invite").icon((v, t) -> icon())
                .visibleWhen((v, t) -> t == target).onClick((v, t, c) -> PlayerMenu.Result.CLOSE).build());

        assertEquals(List.of("party:invite"), keys(registry.render(viewer, target)));
        assertEquals(List.of(), registry.render(viewer, other));
    }

    @Test void givenNullOrAirIcon_whenRendered_thenEntryIsHidden() {
        registry.register(party, PlayerMenuEntry.builder("none").icon((v, t) -> null).onClick((v, t, c) -> null).build());
        ItemStack air = mock(ItemStack.class);
        when(air.isEmpty()).thenReturn(true);
        registry.register(party, PlayerMenuEntry.builder("air").icon((v, t) -> air).onClick((v, t, c) -> null).build());

        assertTrue(registry.render(viewer, target).isEmpty());
    }

    @Test void givenDisabledOwner_whenRendered_thenItsEntriesAreHidden() {
        registry.register(party, entry("invite", 0));
        when(party.isEnabled()).thenReturn(false);

        assertTrue(registry.render(viewer, target).isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register(party, entry("again", 0)));
    }

    @Test void givenPluginReleased_whenRendered_thenOnlyItsEntriesAreGone() {
        registry.register(party, entry("invite", 0));
        registry.register(party, entry("kick", 1));
        registry.register(city, entry("invite", 2));

        registry.release(party);

        assertEquals(List.of("city:invite"), registry.keys());
    }

    @Test void givenCoreLifecycle_whenPluginDisabled_thenSharedRegistryReleasesItsEntries() {
        PlayerMenuRegistry.get().register(party, entry("invite", 0));
        PlayerMenuRegistry.get().register(city, entry("invite", 0));

        CoreLifecycle.releaseAll(party);

        assertEquals(List.of("city:invite"), PlayerMenu.registered());
    }

    @Test void givenUnregisterById_whenAnotherPluginAsks_thenOnlyTheOwnerCanRemoveIt() {
        registry.register(party, entry("invite", 0));
        Plugin impostor = plugin("Party");

        assertFalse(registry.unregister(impostor, "invite"), "same name, other instance");
        assertFalse(registry.unregister(party, "missing"));
        assertTrue(registry.unregister(party, "invite"));
        assertTrue(registry.keys().isEmpty());
    }

    @Test void givenSameIdTwice_whenRegistered_thenTheNewEntryReplacesTheOld() {
        AtomicInteger clicked = new AtomicInteger();
        registry.register(party, entry("invite", 0));
        registry.register(party, PlayerMenuEntry.builder("invite").icon((v, t) -> icon())
                .onClick((v, t, c) -> { clicked.incrementAndGet(); return PlayerMenu.Result.REFRESH; }).build());

        var rendered = registry.render(viewer, target);
        assertEquals(1, rendered.size());
        assertEquals(PlayerMenu.Result.REFRESH, registry.click(rendered.getFirst().registered(), viewer, target, ClickType.LEFT).orElseThrow());
        assertEquals(1, clicked.get());
    }

    @Test void givenFailingEntries_whenRenderedOrClicked_thenOnlyThatEntryIsAffected() {
        registry.register(party, PlayerMenuEntry.builder("bad-icon").priority(0)
                .icon((v, t) -> { throw new IllegalStateException("icon"); }).onClick((v, t, c) -> null).build());
        registry.register(party, PlayerMenuEntry.builder("bad-visible").priority(1).icon((v, t) -> icon())
                .visibleWhen((v, t) -> { throw new NoSuchMethodError("old api"); }).onClick((v, t, c) -> null).build());
        registry.register(city, PlayerMenuEntry.builder("bad-click").priority(2).icon((v, t) -> icon())
                .onClick((v, t, c) -> { throw new IllegalArgumentException("click"); }).build());
        registry.register(city, entry("good", 3));

        var rendered = registry.render(viewer, target);

        assertEquals(List.of("city:bad-click", "city:good"), keys(rendered));
        assertTrue(registry.click(rendered.get(0).registered(), viewer, target, ClickType.LEFT).isEmpty());
        assertEquals(PlayerMenu.Result.CLOSE, registry.click(rendered.get(1).registered(), viewer, target, ClickType.RIGHT).orElseThrow());
    }

    @Test void givenNullClickResult_whenClicked_thenKeep() {
        registry.register(party, PlayerMenuEntry.builder("info").icon((v, t) -> icon()).onClick((v, t, c) -> null).build());

        var registered = registry.render(viewer, target).getFirst().registered();

        assertEquals(PlayerMenu.Result.KEEP, registry.click(registered, viewer, target, ClickType.LEFT).orElseThrow());
    }

    @Test void givenUnregisteredEntry_whenVisibilityRechecked_thenItIsNoLongerVisible() {
        registry.register(party, entry("invite", 0));
        var registered = registry.render(viewer, target).getFirst().registered();

        registry.unregister(party, "invite");

        assertFalse(registry.visible(registered, viewer, target));
    }

    @Test void givenMoreEntriesThanFit_whenRendered_thenCappedByPriority() {
        for (int i = 0; i < PlayerMenu.MAX_ENTRIES + 5; i++) registry.register(party, entry("e" + i, 100 - i));

        var rendered = registry.render(viewer, target);

        assertEquals(PlayerMenu.MAX_ENTRIES, rendered.size());
        assertEquals("party:e" + (PlayerMenu.MAX_ENTRIES + 4), rendered.getFirst().key());
    }

    @Test void givenInvalidEntries_whenBuilt_thenRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlayerMenuEntry.builder("Bad Id"));
        assertThrows(IllegalArgumentException.class, () -> PlayerMenuEntry.builder(""));
        assertThrows(NullPointerException.class, () -> PlayerMenuEntry.builder("x").onClick((v, t, c) -> null).build());
        assertThrows(NullPointerException.class, () -> PlayerMenuEntry.builder("x").icon((v, t) -> icon()).build());
    }

    @Test void givenEntryCount_whenSized_thenHeaderRowPlusEntryRows() {
        assertEquals(18, PlayerMenuView.size(1));
        assertEquals(18, PlayerMenuView.size(9));
        assertEquals(27, PlayerMenuView.size(10));
        assertEquals(54, PlayerMenuView.size(PlayerMenu.MAX_ENTRIES));
        assertEquals(54, PlayerMenuView.size(100));
    }

    private static List<String> keys(List<PlayerMenuRegistry.Rendered> rendered) {
        return rendered.stream().map(PlayerMenuRegistry.Rendered::key).toList();
    }

    private static PlayerMenuEntry entry(String id, int priority) {
        return PlayerMenuEntry.builder(id).priority(priority).icon((v, t) -> icon()).onClick((v, t, c) -> PlayerMenu.Result.CLOSE).build();
    }

    private static ItemStack icon() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.STONE);
        return stack;
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }
}
