package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.VisitTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.worlds.Protection;
import Vystorm.vystorm_core.worlds.VisitPolicy;
import Vystorm.vystorm_core.worlds.WorldSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldsListenerTest {
    @TempDir Path dir;
    World main, talent;
    Env env;
    VisitEngine engine;
    WorldsListener listener;
    FakePlayer esoren;

    @BeforeEach
    void setUp() {
        main = world("world");
        talent = world("vystorm_talentwelt");
        env = new Env(main);
        env.worlds.put(talent.getName(), talent);
        Worlds worlds = new Worlds();
        worlds.add(new ManagedWorldImpl(plugin("VystormSkills"), WorldSpec.builder("vystormskills:talentwelt", "vystorm_talentwelt", mock(ChunkGenerator.class))
                .protect(Protection.PORTALS).policy(VisitPolicy.builder().allowCommands("talente").build()).build(), talent, dir, false));
        engine = new VisitEngine(env, worlds, new VisitSnapshotStore(dir.resolve("visits")), new Writes(true));
        listener = new WorldsListener(engine, worlds, env);
        esoren = new FakePlayer(env, "Esoren", new Location(main, 0, 64, 0));
        engine.enter(esoren.player, "vystormskills:talentwelt", new Location(talent, 0, 100, 0));
        env.runTicks();
    }

    private PlayerCommandPreprocessEvent command(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(esoren.player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void givenVisitor_whenTypingMarketCommand_thenCancelledWithMessage() {
        var blocked = command("/market verkaufen 100");
        listener.onCommandPreCancel(blocked);
        verify(blocked).setCancelled(true);
        assertEquals(List.of("worlds.visit.command-blocked"), env.messages);
        when(blocked.isCancelled()).thenReturn(true);
        listener.onCommand(blocked);
        assertEquals(List.of("worlds.visit.command-blocked"), env.messages, "the final check must not repeat Core's message");
        var allowed = command("/talente welt verlassen");
        listener.onCommandPreCancel(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test
    void givenCorePreCancelledCommand_whenAnotherPluginUncancels_thenFinalCheckCancelsWithoutSecondMessage() {
        var blocked = command("/market sell");
        listener.onCommandPreCancel(blocked);
        when(blocked.isCancelled()).thenReturn(false);
        listener.onCommand(blocked);
        verify(blocked, times(2)).setCancelled(true);
        assertEquals(List.of("worlds.visit.command-blocked"), env.messages);
    }

    @Test
    void givenDisallowedCommandAlreadyCancelledByAnotherPlugin_whenPreCancelRuns_thenNoCoreMessageIsSent() {
        var blocked = command("/market verkaufen 100");
        when(blocked.isCancelled()).thenReturn(true);
        listener.onCommandPreCancel(blocked);
        listener.onCommand(blocked);
        verify(blocked).setCancelled(true);
        assertTrue(env.messages.isEmpty());
    }

    @Test
    void givenCommandHandlers_whenRegistered_thenPreCancelAtLowestAndRecheckAtHighestEvenIfCancelled() throws NoSuchMethodException {
        EventHandler handler = WorldsListener.class
                .getDeclaredMethod("onCommandPreCancel", PlayerCommandPreprocessEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.LOWEST, handler.priority());
        assertFalse(handler.ignoreCancelled());
        handler = WorldsListener.class
                .getDeclaredMethod("onCommand", PlayerCommandPreprocessEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertFalse(handler.ignoreCancelled());
    }

    @Test
    void givenVisitor_whenDroppingOrDying_thenItemsStay() {
        var drop = new PlayerDropItemEvent(esoren.player, mock(org.bukkit.entity.Item.class));
        listener.onDrop(drop);
        assertTrue(drop.isCancelled());
        List<ItemStack> drops = new ArrayList<>(List.of(item("tool")));
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(esoren.player);
        when(death.getDrops()).thenReturn(drops);
        listener.onDeath(death);
        verify(death).setKeepInventory(true);
        assertTrue(drops.isEmpty());
    }

    @Test
    void givenStranger_whenForeignTeleportIntoWorld_thenCancelled() {
        FakePlayer alex = new FakePlayer(env, "Alex", new Location(main, 5, 64, 5));
        Player p = alex.player;
        var event = new PlayerTeleportEvent(p, new Location(main, 5, 64, 5), new Location(talent, 0, 100, 0));
        listener.onTeleport(event);
        assertTrue(event.isCancelled());
        assertTrue(env.messages.contains("worlds.visit.teleport-blocked"));
    }
}
