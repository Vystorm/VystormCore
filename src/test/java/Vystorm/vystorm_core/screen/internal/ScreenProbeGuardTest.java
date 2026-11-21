package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.screen.ScreenInput;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 0.66.1: the screen world guard stops the player's own clicks into the world while a screen is open, but never the
 * synthetic events a plugin fires from a screen click handler to ask protection plugins (Gallery's slideshow row
 * answered "You lack permission" because its probe was cancelled by the guard).
 */
class ScreenProbeGuardTest {
    private final Plugin core = mock(Plugin.class);
    private final World world = mock(World.class);
    private final List<Entity> spawned = new ArrayList<>();
    private final EventStageTesting stages = EventStageTesting.detached();
    private ScreenRuntime runtime;
    private final List<PlayerInteractEntityEvent> probes = new ArrayList<>();
    private final List<BlockBreakEvent> brokenInHandler = new ArrayList<>();

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

    @BeforeEach void setUp() {
        when(core.getName()).thenReturn("vystorm_core");
        when(core.getLogger()).thenReturn(Logger.getLogger("test"));
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        runtime = new ScreenRuntime(core, fake, () -> 100_000L, p -> true, p -> false);
        ScreenWorldGuard.register(stages.stages(), EventStageTesting.plugin("vystorm_core"), () -> runtime);
    }

    private Player player() {
        Player p = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id);
        when(p.getLocation()).thenReturn(new Location(world, 10, 64, 10, 0, 0));
        when(p.getEyeLocation()).thenReturn(new Location(world, 10, 65.62, 10, 0, 0));
        when(p.isOnGround()).thenReturn(true);
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    private PlayerInteractEntityEvent probe(Player p) {
        PlayerInteractEntityEvent e = mock(PlayerInteractEntityEvent.class);
        when(e.getPlayer()).thenReturn(p);
        return e;
    }

    private BlockBreakEvent breaking(Player p) {
        BlockBreakEvent e = mock(BlockBreakEvent.class);
        when(e.getPlayer()).thenReturn(p);
        return e;
    }

    /** A screen whose button, like Gallery's slideshow row, asks the protection plugins before it acts. */
    private ScreenSpec probingScreen(Player p) {
        ScreenLayout layout = new ScreenLayout(0.9, 0.55, List.of(ScreenWidget.button("centre", 0.35, 0.235, 0.2, 0.08, "Slideshow")));
        return ScreenSpec.builder("test:probe", layout).onInput((s, input) -> {
            if (!(input instanceof ScreenInput.Click)) return;
            probes.add(stages.fire(probe(p)));
            brokenInHandler.add(stages.fire(breaking(p)));
        }).fallback(x -> { }).build();
    }

    private Entity box() { return spawned.stream().filter(e -> e instanceof Interaction).reduce((a, b) -> b).orElseThrow(); }

    @Test void givenOpenScreen_whenTheClickHandlerFiresAProtectionProbe_thenTheGuardLetsItThrough() {
        Player p = player();
        assertTrue(runtime.open(core, p, probingScreen(p)).opened());
        runtime.onClick(p, box(), false);
        assertEquals(1, probes.size(), "the handler ran");
        verify(probes.get(0), never()).setCancelled(anyBoolean());
        verify(brokenInHandler.get(0), never()).setCancelled(anyBoolean());
        assertFalse(ScreenDispatch.active(), "the mark ends with the handler");
    }

    @Test void givenOpenScreen_whenThePlayerClicksIntoTheWorld_thenTheGuardStillStopsIt() {
        Player p = player();
        runtime.open(core, p, probingScreen(p));
        PlayerInteractEntityEvent real = stages.fire(probe(p));
        BlockBreakEvent broken = stages.fire(breaking(p));
        verify(real).setCancelled(true);
        verify(broken).setCancelled(true);
    }

    @Test void givenADialogButtonHandler_whenItProbes_thenNotStoppedEvenWithTheScreenOpen() {
        Player p = player();
        runtime.open(core, p, probingScreen(p));
        PlayerInteractEntityEvent[] inDialog = new PlayerInteractEntityEvent[1];
        ScreenDispatch.run(() -> inDialog[0] = stages.fire(probe(p)));
        verify(inDialog[0], never()).setCancelled(anyBoolean());
        PlayerInteractEntityEvent after = stages.fire(probe(p));
        verify(after).setCancelled(true);
    }

    @Test void givenNestedHandlers_whenTheInnerEnds_thenTheOuterIsStillMarked() {
        ScreenDispatch.run(() -> {
            ScreenDispatch.run(() -> assertTrue(ScreenDispatch.active()));
            assertTrue(ScreenDispatch.active());
        });
        assertFalse(ScreenDispatch.active());
        assertThrows(IllegalStateException.class, () -> ScreenDispatch.run(() -> { throw new IllegalStateException("handler"); }));
        assertFalse(ScreenDispatch.active(), "a failing handler leaves no mark");
    }
}
