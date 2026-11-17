package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

class PlatformCompatTest {
    @Test void givenBukkitLocaleWhenReadThenRegionalLanguageIsRetained() {
        Player player=mock(Player.class);
        when(player.locale()).thenThrow(new NoSuchMethodError());
        when(player.getLocale()).thenReturn("pt_br");
        assertEquals(Locale.forLanguageTag("pt-BR"),PlatformCompat.locale(player));
    }
    @Test void givenBukkitItemWhenMetadataEditedThenStoredBackOnTheStack() {
        ItemStack stack=mock(ItemStack.class);ItemMeta meta=mock(ItemMeta.class);
        when(stack.editMeta(any())).thenThrow(new NoSuchMethodError());
        when(stack.getItemMeta()).thenReturn(meta);when(stack.setItemMeta(meta)).thenReturn(true);
        doThrow(new NoSuchMethodError()).when(meta).displayName(any());
        assertTrue(PlatformCompat.editMeta(stack,m->PlatformCompat.displayName(m,Component.text("Camera"))));
        verify(meta).setDisplayName("Camera");verify(stack).setItemMeta(meta);
    }
    @Test void givenBukkitExperienceWhenSetAndReadThenBoundaryBalancesRoundTrip() {
        Player player=mock(Player.class);AtomicInteger level=new AtomicInteger();AtomicReference<Float> progress=new AtomicReference<>(0f);
        doAnswer(a->{level.set(a.getArgument(0));return null;}).when(player).setLevel(anyInt());
        doAnswer(a->{progress.set(a.getArgument(0));return null;}).when(player).setExp(anyFloat());
        when(player.getLevel()).thenAnswer(a->level.get());when(player.getExp()).thenAnswer(a->progress.get());
        for(int total:new int[]{0,6,7,351,352,393,394,1394,1395,1506,1507,100000,Integer.MAX_VALUE}) {
            PlatformCompat.bukkitExperience(player,total);
            assertEquals(total,PlatformCompat.bukkitExperience(player),"XP "+total);
            assertTrue(progress.get()>=0 && progress.get()<1,"progress "+total);
        }
    }
    @Test void givenBukkitTeleportCancellationWhenFutureCompletesThenItReportsFalse() {
        Player player=mock(Player.class);Location target=new Location(mock(World.class),10,64,20);
        when(player.teleportAsync(target,TeleportCause.PLUGIN)).thenThrow(new NoSuchMethodError());
        when(player.teleport(target,TeleportCause.PLUGIN)).thenReturn(false);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            assertFalse(PlatformCompat.teleportAsync(player,target).join());
            verify(player).teleport(target,TeleportCause.PLUGIN);
        }
    }
    @Test void givenNegativeChunkCoordinatesWhenPackedThenBothCoordinatesSurvive() {
        for(int x:new int[]{Integer.MIN_VALUE,-31,0,31,Integer.MAX_VALUE})
            for(int z:new int[]{Integer.MIN_VALUE,-99,0,99,Integer.MAX_VALUE}) {
                long key=PlatformCompat.getChunkKey(x,z);assertEquals(x,(int)key);assertEquals(z,(int)(key>>32));
            }
    }
}
