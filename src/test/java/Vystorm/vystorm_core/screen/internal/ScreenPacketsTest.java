package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScreenPacketsTest {
    @Test
    void givenPacketNames_whenChecked_thenOnlyWindowOpensAreDropped() {
        assertTrue(ScreenPackets.opensWindow("ClientboundOpenScreenPacket"));
        assertTrue(ScreenPackets.opensWindow("PacketPlayOutOpenWindow"));
        assertFalse(ScreenPackets.opensWindow("ClientboundContainerSetContentPacket"));
        assertFalse(ScreenPackets.opensWindow("ClientboundSetEntityDataPacket"));
    }
}
