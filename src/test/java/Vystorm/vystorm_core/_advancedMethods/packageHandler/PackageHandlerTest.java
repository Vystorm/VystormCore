package Vystorm.vystorm_core._advancedMethods.packageHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageHandlerTest {
    @Test void givenIncomingPayloadWhenReceivingThenIsolateBufferAndRemoveOnlyOwnedRegistrations() {
        try(var b=mockStatic(Bukkit.class)) {
            var messenger=new StandardMessenger(); b.when(Bukkit::getMessenger).thenReturn(messenger); b.when(Bukkit::isPrimaryThread).thenReturn(true);
            Plugin owner=mock(Plugin.class); when(owner.isEnabled()).thenReturn(true);
            Player player=mock(Player.class); var count=new AtomicInteger();
            PluginMessageListener external=(channel,p,payload)->{};
            messenger.registerOutgoingPluginChannel(owner,"test:shared"); messenger.registerIncomingPluginChannel(owner,"test:shared",external);
            var h=new PackageHandler(); h.register(owner,"test:shared",(channel,p,payload)->{payload[0]=9;count.incrementAndGet();});
            byte[] payload={1,2}; messenger.dispatchIncomingMessage(player,"test:shared",payload);
            assertEquals(1,count.get()); assertEquals(1,payload[0]);
            assertTrue(h.unregister(owner,"test:shared"));
            assertTrue(messenger.isOutgoingChannelRegistered(owner,"test:shared"));
            assertEquals(1,messenger.getIncomingChannelRegistrations(owner,"test:shared").size());
        }
    }
    @Test void givenOutgoingChannelWhenSendingThenValidateAndCleanUp() {
        try(var b=mockStatic(Bukkit.class)) {
            var messenger=new StandardMessenger(); b.when(Bukkit::getMessenger).thenReturn(messenger); b.when(Bukkit::isPrimaryThread).thenReturn(true);
            Plugin owner=mock(Plugin.class); when(owner.isEnabled()).thenReturn(true);
            Player player=mock(Player.class); when(player.isOnline()).thenReturn(true);
            var h=new PackageHandler(); h.register(owner,"test:out",null);
            byte[] payload={4};h.send(owner,player,"test:out",payload);
            var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class); verify(player).sendPluginMessage(eq(owner),eq("test:out"),bytes.capture());
            assertArrayEquals(payload,bytes.getValue()); assertNotSame(payload,bytes.getValue());
            assertThrows(IllegalArgumentException.class,()->h.send(owner,player,"test:out",new byte[Messenger.MAX_MESSAGE_SIZE+1]));
            h.removeAll(owner); assertFalse(messenger.isOutgoingChannelRegistered(owner,"test:out"));
            assertThrows(IllegalArgumentException.class,()->h.send(owner,player,"test:out",payload));
        }
    }
}
