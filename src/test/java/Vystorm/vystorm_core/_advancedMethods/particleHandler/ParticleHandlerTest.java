package Vystorm.vystorm_core._advancedMethods.particleHandler;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticleHandlerTest {
    @Test void givenOffsetsWhenSpawningThenPreserveValuesAndShareExistingBudget() {
        try (var b = mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world = mock(World.class); Location at = new Location(world, 1, 2, 3);
            var handler = new ParticleHandler(5);
            handler.spawn(Particle.FLAME, at, 3, .25, .2, .25, .01, null);
            handler.spawn(Particle.FLAME, at, 4, null);
            verify(world).spawnParticle(Particle.FLAME, at, 3, .25, .2, .25, .01, null);
            verify(world).spawnParticle(Particle.FLAME, at, 2, 0d, 0d, 0d, 0d, null);
            assertThrows(IllegalArgumentException.class,
                    () -> handler.spawn(Particle.FLAME, at, 1, Double.NaN, 0, 0, 0, null));
            verifyNoMoreInteractions(world);
        }
    }
    @Test void givenLineAndCircleWhenEmittingThenPreserveInputsAndProduceExpectedPositions() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world=mock(World.class); var h=new ParticleHandler();
            Location from=new Location(world,1,2,3), to=new Location(world,5,2,3);
            h.line(Particle.FLAME,from,to,3,null);
            var x=ArgumentCaptor.forClass(Double.class);
            verify(world,times(3)).spawnParticle(eq(Particle.FLAME),x.capture(),eq(2d),eq(3d),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),isNull());
            assertEquals(java.util.List.of(1d,3d,5d),x.getAllValues()); assertEquals(1,from.getX());
            clearInvocations(world);h.circle(Particle.FLAME,from,2,4,null);
            var cx=ArgumentCaptor.forClass(Double.class);var cz=ArgumentCaptor.forClass(Double.class);
            verify(world,times(4)).spawnParticle(eq(Particle.FLAME),cx.capture(),eq(2d),cz.capture(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),isNull());
            for(int i=0;i<4;i++)assertEquals(2,Math.hypot(cx.getAllValues().get(i)-1,cz.getAllValues().get(i)-3),1e-9);
            // Reuse the same resolution at a different center/radius without mutating cached offsets or inputs.
            clearInvocations(world);h.circle(Particle.FLAME,to,3,4,null);
            verify(world).spawnParticle(Particle.FLAME,8d,2d,3d,1,0d,0d,0d,0d,null);
            assertEquals(new Location(world,1,2,3),from);
            assertEquals(new Location(world,5,2,3),to);
        }
    }
    @Test void givenInvalidParticleDataWhenEmittingThenRejectBeforeWorldMutation() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world=mock(World.class); Location at=new Location(world,0,0,0);var h=new ParticleHandler();
            assertThrows(IllegalArgumentException.class,()->h.spawn(Particle.DUST,at,1,null));
            assertThrows(IllegalArgumentException.class,()->h.spawn(Particle.FLAME,at,-1,null));
            assertThrows(IllegalArgumentException.class,()->h.circle(Particle.FLAME,at,Double.NaN,4,null));
            verifyNoInteractions(world);
        }
    }
    @Test void givenSharedBudgetWhenWorldAndPlayerEmitThenClampAndResetOnNextTick() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            b.when(Bukkit::getCurrentTick).thenReturn(Integer.MAX_VALUE);
            World world=mock(World.class);Player player=mock(Player.class);
            when(player.getWorld()).thenReturn(world);
            Location at=new Location(world,0,0,0);var h=new ParticleHandler(5);
            h.spawn(Particle.FLAME,at,3,null);
            h.spawn(player,Particle.FLAME,at,4,null);
            h.spawn(Particle.FLAME,at,1,null);
            verify(world).spawnParticle(Particle.FLAME,at,3,0d,0d,0d,0d,null);
            verify(player).spawnParticle(Particle.FLAME,at,2,0d,0d,0d,0d,null);
            verifyNoMoreInteractions(world);
            b.when(Bukkit::getCurrentTick).thenReturn(Integer.MIN_VALUE);
            h.spawn(Particle.FLAME,at,5,null);
            verify(world).spawnParticle(Particle.FLAME,at,5,0d,0d,0d,0d,null);
        }
    }
    @Test void givenExtremePointCountsWhenBudgetReducesShapesThenKeepEndpointsAndFullCircle() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world=mock(World.class);var h=new ParticleHandler(6);
            Location from=new Location(world,0,2,0),to=new Location(world,10,2,0);
            h.spawn(Particle.FLAME,from,3,null);
            clearInvocations(world);
            h.line(Particle.FLAME,from,to,Integer.MAX_VALUE,null);
            var x=ArgumentCaptor.forClass(Double.class);
            verify(world,times(3)).spawnParticle(eq(Particle.FLAME),x.capture(),eq(2d),eq(0d),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),isNull());
            assertEquals(java.util.List.of(0d,5d,10d),x.getAllValues());
            b.when(Bukkit::getCurrentTick).thenReturn(1);
            clearInvocations(world);
            h.circle(Particle.FLAME,from,2,Integer.MAX_VALUE,null);
            var cx=ArgumentCaptor.forClass(Double.class);var cz=ArgumentCaptor.forClass(Double.class);
            verify(world,times(6)).spawnParticle(eq(Particle.FLAME),cx.capture(),eq(2d),cz.capture(),eq(1),eq(0d),eq(0d),eq(0d),eq(0d),isNull());
            for(int i=0;i<6;i++) {
                assertEquals(2*Math.cos(2*Math.PI*i/6),cx.getAllValues().get(i),1e-9);
                assertEquals(2*Math.sin(2*Math.PI*i/6),cz.getAllValues().get(i),1e-9);
            }
            verifyNoMoreInteractions(world);
        }
    }
    @Test void givenInsufficientShapeBudgetWhenZeroCountEmitsThenPreserveSpecialCountAndChargeBudget() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world=mock(World.class);Location at=new Location(world,0,0,0);var h=new ParticleHandler(2);
            h.circle(Particle.FLAME,at,1,3,null); // Too little for a circle; budget remains available.
            h.spawn(Particle.FLAME,at,0,null);
            h.line(Particle.FLAME,at,at,2,null); // One remaining slot cannot form a line.
            h.spawn(Particle.FLAME,at,0,null);
            h.spawn(Particle.FLAME,at,0,null);
            verify(world,times(2)).spawnParticle(Particle.FLAME,at,0,0d,0d,0d,0d,null);
            verifyNoMoreInteractions(world);
        }
    }
    @Test void givenWrongWorldOrInvalidCallWhenRejectedThenKeepBudgetAndThreadSafety() {
        try(var b=mockStatic(Bukkit.class)) {
            b.when(Bukkit::isPrimaryThread).thenReturn(true);
            World world=mock(World.class);Player player=mock(Player.class);
            when(player.getWorld()).thenReturn(mock(World.class));
            Location at=new Location(world,0,0,0);var h=new ParticleHandler(1);
            h.spawn(player,Particle.FLAME,at,1,null);
            assertThrows(IllegalArgumentException.class,()->h.spawn(Particle.FLAME,at,-1,null));
            h.spawn(Particle.FLAME,at,1,null);
            verify(world).spawnParticle(Particle.FLAME,at,1,0d,0d,0d,0d,null);
            verify(player,never()).spawnParticle(any(Particle.class),any(Location.class),anyInt(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),any());
            b.when(Bukkit::isPrimaryThread).thenReturn(false);
            assertThrows(IllegalStateException.class,()->h.spawn(Particle.FLAME,at,1,null));
            verifyNoMoreInteractions(world);
        }
    }
}
