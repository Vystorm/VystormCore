package Vystorm.vystorm_core._advancedMethods.areaHandler;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AreaHandlerTest {
    private World world() { World w = mock(World.class); when(w.getUID()).thenReturn(UUID.randomUUID()); return w; }
    private Area rectangle() {
        Area a = new Area(); a.setAreaType(AreaType.Rectangle);
        a.setCorner1X(-1); a.setCorner2X(-17); a.setCorner1Z(17); a.setCorner2Z(-1);
        a.setY1(70); a.setY2(60); return a;
    }
    @Test void givenReversedCornersWhenFindingThenChecksExactBoundsHeightAndWorld() {
        World w = world(); Area a = rectangle(); a.setWorld(w.getUID());
        AreaHandler handler = new AreaHandler(); handler.register(a);
        assertEquals(java.util.List.of(a), handler.find(new Location(w,-17,60,-1)));
        assertTrue(handler.find(new Location(w,-18,60,-1)).isEmpty());
        assertTrue(handler.find(new Location(w,-17,71,-1)).isEmpty());
        assertTrue(handler.find(new Location(world(),-17,60,-1)).isEmpty());
        assertTrue(handler.find(new Location(w,Double.NaN,60,0)).isEmpty());
    }
    @Test void givenCircleAndCylinderWhenFindingThenExcludeBoundingBoxCornersAndRespectHeight() {
        World w = world(); Area a = new Area(); a.setAreaType(AreaType.fullCircle); a.setRadius(5);
        a.setCenterX(100); a.setCenterZ(200);
        AreaHandler handler = new AreaHandler(); handler.register(a);
        assertFalse(handler.find(new Location(w,103,500,204)).isEmpty());
        assertTrue(handler.find(new Location(w,104,0,204)).isEmpty());
        a.setAreaType(AreaType.Cylinder); a.setY1(10); a.setY2(20); handler.update(a);
        assertFalse(handler.find(new Location(w,103,10,204)).isEmpty());
        assertTrue(handler.find(new Location(w,103,21,204)).isEmpty());
    }
    @Test void givenRegisteredAreaWhenMovingOrRemovingThenOldIndexIsCleared() {
        World w = world(); Area a = rectangle(); AreaHandler h = new AreaHandler(); h.register(a);
        a.setCorner1X(100); a.setCorner2X(120);
        assertFalse(h.find(new Location(w,-10,65,0)).isEmpty()); // registered snapshot until explicit update
        assertTrue(h.update(a)); assertTrue(h.find(new Location(w,-10,65,0)).isEmpty());
        assertFalse(h.find(new Location(w,110,65,0)).isEmpty());
        h.register(a); assertEquals(1,h.find(new Location(w,110,65,0)).size());
        assertTrue(h.unregister(a)); assertFalse(h.unregister(a)); assertTrue(h.find(new Location(w,110,65,0)).isEmpty());
    }
    @Test void givenHugeBoundsWhenRegisteredThenAvoidPerBlockIndexAndIntegerOverflow() {
        Area a = new Area(); a.setAreaType(AreaType.fullCircle); a.setRadius(Integer.MAX_VALUE);
        AreaHandler h = new AreaHandler(); h.register(a);
        assertEquals(1,h.find(new Location(world(),0,0,0)).size());
        h.clear(); assertTrue(h.find(new Location(world(),0,0,0)).isEmpty());
        a.setRadius(-1); assertThrows(IllegalArgumentException.class,()->h.register(a));
    }
}
