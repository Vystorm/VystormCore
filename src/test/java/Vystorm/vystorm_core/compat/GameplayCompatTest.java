package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class GameplayCompatTest {
    @Test void quantityCopiesDoNotMutateTheRewardTemplate() {
        ItemStack template=mock(ItemStack.class),copy=mock(ItemStack.class);
        when(template.asQuantity(7)).thenThrow(new NoSuchMethodError());
        when(template.clone()).thenReturn(copy);
        assertSame(copy,GameplayCompat.asQuantity(template,7));
        verify(copy).setAmount(7);
        verify(template,never()).setAmount(anyInt());
    }
    @Test void nearbyLivingQueriesKeepBoundingBoxSemanticsAndExcludeDisplays() {
        World world=mock(World.class);Location at=new Location(world,10,64,20);
        LivingEntity mob=mock(LivingEntity.class);Entity display=mock(Entity.class);
        when(world.getNearbyEntities(at,8,8,8)).thenReturn(List.of(display,mob));
        assertEquals(List.of(mob),GameplayCompat.getNearbyLivingEntities(at,8));
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
    @Test void equipmentDropChanceUsesTheRequestedBukkitSlot() {
        EntityEquipment equipment=mock(EntityEquipment.class);
        GameplayCompat.setDropChance(equipment,EquipmentSlot.OFF_HAND,0.25f);
        verify(equipment).setItemInOffHandDropChance(0.25f);
        verifyNoMoreInteractions(equipment);
    }
    @Test void navigationRejectsUnloadedDestinationsWithoutLoadingChunks() {
        World world=mock(World.class);Mob mob=mock(Mob.class);
        when(mob.getWorld()).thenReturn(world);
        assertFalse(MobNavigation.moveTo(mob,new Location(world,320,64,160),1));
        verify(world).isChunkLoaded(20,10);
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }
}
