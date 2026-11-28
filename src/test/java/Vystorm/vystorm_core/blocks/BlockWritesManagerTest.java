package Vystorm.vystorm_core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.main;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** The manager {@code api.blocks()} and the four capabilities of the bulk block writes (0.69.0). */
class BlockWritesManagerTest {
    @Test
    void givenCore069_whenCapabilitiesQueried_thenAllFourExistSince069() {
        assertTrue(CoreApi.has("block-writes"));
        assertTrue(CoreApi.has(CoreApi.Capability.BLOCK_WRITES));
        assertEquals(Optional.of("0.69.0"), CoreApi.since("chunk-loads"));
        assertEquals(Optional.of("0.69.0"), CoreApi.since(CoreApi.Capability.BLOCK_WRITES));
        assertEquals(Optional.of("0.69.0"), CoreApi.since(CoreApi.Capability.BLOCKS_EDITED_EVENT));
        assertEquals(Optional.of("0.69.0"), CoreApi.since(CoreApi.Capability.EDIT_ASK));
        assertEquals("chunk-loads", CoreApi.Capability.CHUNK_LOADS);
        assertEquals("blocks-edited-event", CoreApi.Capability.BLOCKS_EDITED_EVENT);
        assertEquals("edit-ask", CoreApi.Capability.EDIT_ASK);
    }

    @Test
    void givenNewApi_whenBlocksOverridden_thenDefaultThenReplacementAndNullRejected() {
        try (MockedStatic<main> core = mockStatic(main.class)) {
            API api = new API("test");
            assertSame(BlockWritesInterface.DEFAULT, api.blocks());
            BlockWritesInterface mine = new BlockWritesInterface() {};
            api.overrideBlocksHandler(mine);
            assertSame(mine, api.blocks());
            NullPointerException rejected = assertThrows(NullPointerException.class, () -> api.overrideBlocksHandler(null));
            assertEquals("replacement", rejected.getMessage());
            assertSame(mine, api.blocks());
        }
    }
}
