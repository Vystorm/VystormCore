package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {

    @Test
    void givenFrameWhenEncodedAndDecodedThenAllFieldsSurvive() throws Exception {
        Frame frame = new Frame(FrameType.MESSAGE, "vystorm:test", "Lobby-ä", "*", new byte[]{1, 2, 3});
        assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)));
    }

    @Test
    void givenUtf8NamesWhenRoundTrippedThenTextIsExact() throws Exception {
        Frame frame = new Frame(FrameType.MESSAGE, "vystorm:test", "Lobby-ä", "Welt-ß", new byte[0]);
        Frame back = FrameCodec.decode(FrameCodec.encode(frame));
        assertEquals("Lobby-ä", back.from());
        assertEquals("Welt-ß", back.to());
    }

    @Test
    void givenOversizedPayloadWhenEncodedThenRejected() {
        Frame frame = Frame.of(FrameType.MESSAGE, new byte[FrameCodec.MAX_FRAME + 1]);
        assertThrows(FrameException.class, () -> FrameCodec.encode(frame));
    }

    @Test
    void givenOversizedInputWhenDecodedThenRejected() {
        assertThrows(FrameException.class, () -> FrameCodec.decode(new byte[FrameCodec.MAX_FRAME + 1]));
    }

    @Test
    void givenTruncatedInputWhenDecodedThenRejected() throws Exception {
        byte[] full = FrameCodec.encode(new Frame(FrameType.MESSAGE, "a:b", "x", "y", new byte[]{9, 9, 9}));
        assertThrows(FrameException.class, () -> FrameCodec.decode(Arrays.copyOf(full, full.length - 2)));
    }

    @Test
    void givenUnknownTypeWhenDecodedThenRejected() throws Exception {
        byte[] full = FrameCodec.encode(Frame.of(FrameType.HEARTBEAT, new byte[0]));
        full[0] = (byte) 200;
        assertThrows(FrameException.class, () -> FrameCodec.decode(full));
    }

    @Test
    void givenTrailingBytesWhenDecodedThenRejected() throws Exception {
        byte[] full = FrameCodec.encode(Frame.of(FrameType.HEARTBEAT, new byte[0]));
        assertThrows(FrameException.class, () -> FrameCodec.decode(Arrays.copyOf(full, full.length + 1)));
    }
}
