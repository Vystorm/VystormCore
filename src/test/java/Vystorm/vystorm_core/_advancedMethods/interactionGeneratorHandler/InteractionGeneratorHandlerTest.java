package Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractionGeneratorHandlerTest {
    @Test void givenHitboxWhenClickedTwiceThenDeduplicateAndRemoveOnOwnerCleanup() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);b.when(Bukkit::getCurrentTick).thenReturn(100);
            World w=mock(World.class);Interaction entity=mock(Interaction.class);UUID id=UUID.randomUUID();
            when(entity.getUniqueId()).thenReturn(id);when(entity.isValid()).thenReturn(false);
            when(w.spawn(any(Location.class),eq(Interaction.class),org.mockito.ArgumentMatchers.<Consumer<Interaction>>any())).thenAnswer(call->{
                Consumer<Interaction> setup=call.getArgument(2);setup.accept(entity);return entity;
            });
            Plugin owner=mock(Plugin.class);when(owner.isEnabled()).thenReturn(true);
            Player player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            AtomicInteger clicks=new AtomicInteger(), attacks=new AtomicInteger();
            var h=new InteractionGeneratorHandler();assertSame(entity,h.spawn(owner,new Location(w,0,0,0),1,2,p->clicks.incrementAndGet(),p->attacks.incrementAndGet()));
            assertSame(entity,h.get(id));
            verify(entity).setPersistent(false);verify(entity).setInteractionHeight(2);
            assertTrue(h.handle(id,player,false));assertTrue(h.handle(id,player,false));assertEquals(1,clicks.get());
            assertTrue(h.handle(id,player,true));assertEquals(1,attacks.get());
            b.when(Bukkit::getCurrentTick).thenReturn(101);h.handle(id,player,false);assertEquals(2,clicks.get());
            h.removeAll(owner);verify(entity).remove();assertNull(h.get(id));assertFalse(h.handle(id,player,false));
        }
    }
    @Test void givenInvalidDimensionsWhenSpawningThenNeverCreateEntity() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);Plugin owner=mock(Plugin.class);when(owner.isEnabled()).thenReturn(true);
            World w=mock(World.class);
            assertThrows(IllegalArgumentException.class,()->new InteractionGeneratorHandler().spawn(owner,new Location(w,0,0,0),Float.NaN,1,p->{},null));
            verifyNoInteractions(w);
        }
    }
}
