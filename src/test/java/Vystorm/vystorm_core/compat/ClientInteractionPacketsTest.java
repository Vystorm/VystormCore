package Vystorm.vystorm_core.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

class ClientInteractionPacketsTest {
    // Plain packet fixtures reproduce the two real protocol shapes without providing any server API classes.
    record ServerboundAttackPacket(int entityId) {}
    record ServerboundInteractPacket(int entityId,String hand) {}
    record ClientboundLevelChunkWithLightPacket(int x,int z) {}
    record PacketPlayInUseEntity(int entityId,Action action) {}
    static final class Action { final String hand;Action(String hand){this.hand=hand;}String getType(){return "INTERACT";} }
    @Test void givenSplit26xPacketsWhenDecodedThenAttackAndOffHandAreKept()throws Exception {
        assertEquals(new ClientEvents.Interaction(123,true,EquipmentSlot.HAND),ClientEvents.interaction(new ServerboundAttackPacket(123)));
        assertEquals(new ClientEvents.Interaction(456,false,EquipmentSlot.OFF_HAND),ClientEvents.interaction(new ServerboundInteractPacket(456,"OFF_HAND")));
    }
    @Test void givenLegacyCombinedPacketWhenDecodedThenItsActionAndHandAreKept()throws Exception {
        assertEquals(new ClientEvents.Interaction(789,false,EquipmentSlot.HAND),ClientEvents.interaction(new PacketPlayInUseEntity(789,new Action("MAIN_HAND"))));
    }
    @Test void given26xChunkRecordWhenDecodedThenNegativeCoordinatesArePreserved()throws Exception {
        assertEquals(PlatformCompat.getChunkKey(-52,39),ClientEvents.chunkKey(new ClientboundLevelChunkWithLightPacket(-52,39)));
    }
}
