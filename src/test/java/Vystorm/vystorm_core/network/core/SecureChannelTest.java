package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecureChannelTest {
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final NetworkKey key = KeySources.fromVelocitySecret("test-secret");

    @AfterEach void stop() { pool.shutdownNow(); }

    /** Two connected stream pairs: [clientIn, clientOut, hubIn, hubOut]. */
    private static Object[] pipes() throws IOException {
        PipedInputStream hubIn = new PipedInputStream(1 << 21);
        PipedOutputStream clientOut = new PipedOutputStream(hubIn);
        PipedInputStream clientIn = new PipedInputStream(1 << 21);
        PipedOutputStream hubOut = new PipedOutputStream(clientIn);
        return new Object[]{clientIn, clientOut, hubIn, hubOut};
    }

    private SecureChannel[] connect(NetworkKey clientKey, NetworkKey hubKey) throws Exception {
        Object[] p = pipes();
        Future<SecureChannel> hub = pool.submit(() -> Handshake.hub(hubKey, n -> true, (InputStream) p[2], (OutputStream) p[3]));
        SecureChannel client = Handshake.client(clientKey, "lobby", (InputStream) p[0], (OutputStream) p[1]);
        return new SecureChannel[]{client, hub.get(5, TimeUnit.SECONDS)};
    }

    @Test
    void givenSameKeyWhenHandshakingThenBothSidesExchangeFrames() throws Exception {
        SecureChannel[] c = connect(key, key);
        assertEquals("lobby", c[1].peerName());
        Frame hello = new Frame(FrameType.MESSAGE, "vystorm:test", "lobby", "*", new byte[]{7});
        c[0].send(hello);
        assertEquals(hello, c[1].receive());
        c[1].send(hello);
        assertEquals(hello, c[0].receive());
    }

    @Test
    void givenWrongKeyWhenHandshakingThenBothSidesFail() throws Exception {
        Object[] p = pipes();
        Future<SecureChannel> hub = pool.submit(() -> Handshake.hub(key, n -> true, (InputStream) p[2], (OutputStream) p[3]));
        HandshakeException client = assertThrows(HandshakeException.class,
                () -> Handshake.client(KeySources.fromVelocitySecret("other"), "lobby", (InputStream) p[0], (OutputStream) p[1]));
        assertEquals(HandshakeException.Reason.AUTH, client.reason());
        Exception hubFailure = assertThrows(Exception.class, () -> hub.get(5, TimeUnit.SECONDS));
        assertInstanceOf(HandshakeException.class, hubFailure.getCause());
    }

    @Test
    void givenDisallowedNameWhenHandshakingThenRejected() throws Exception {
        Object[] p = pipes();
        pool.submit(() -> Handshake.hub(key, n -> !n.equals("lobby"), (InputStream) p[2], (OutputStream) p[3]));
        HandshakeException failure = assertThrows(HandshakeException.class,
                () -> Handshake.client(key, "lobby", (InputStream) p[0], (OutputStream) p[1]));
        assertEquals(HandshakeException.Reason.NAME_REJECTED, failure.reason());
    }

    @Test
    void givenOtherProtocolVersionWhenHandshakingThenRejectedWithReason() throws Exception {
        Object[] p = pipes();
        Future<SecureChannel> hub = pool.submit(() -> Handshake.hub(key, n -> true, (InputStream) p[2], (OutputStream) p[3]));
        HandshakeException failure = assertThrows(HandshakeException.class,
                () -> Handshake.client(key, "lobby", (InputStream) p[0], (OutputStream) p[1], 2));
        assertEquals(HandshakeException.Reason.PROTOCOL, failure.reason());
        Exception hubFailure = assertThrows(Exception.class, () -> hub.get(5, TimeUnit.SECONDS));
        assertEquals(HandshakeException.Reason.PROTOCOL, ((HandshakeException) hubFailure.getCause()).reason());
    }

    /** Records sent by one channel, captured as raw bytes so the test can tamper with or replay them. */
    private static byte[][] capture(SecureChannel sender, ByteArrayOutputStream wire, Frame... frames) throws IOException {
        byte[][] records = new byte[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            int before = wire.size();
            sender.send(frames[i]);
            byte[] all = wire.toByteArray();
            records[i] = java.util.Arrays.copyOfRange(all, before, all.length);
        }
        return records;
    }

    private static SecureChannel[] pairOverBuffers(ByteArrayOutputStream wire, InputStream receiverIn) {
        byte[] k1 = new byte[32], k2 = new byte[32];
        k1[0] = 1; k2[0] = 2;
        SecureChannel sender = new SecureChannel(new ByteArrayInputStream(new byte[0]), wire, k1, k2,
                SecureChannel.DIR_CLIENT_TO_HUB, SecureChannel.DIR_HUB_TO_CLIENT, "");
        SecureChannel receiver = new SecureChannel(receiverIn, new ByteArrayOutputStream(), k2, k1,
                SecureChannel.DIR_HUB_TO_CLIENT, SecureChannel.DIR_CLIENT_TO_HUB, "lobby");
        return new SecureChannel[]{sender, receiver};
    }

    @Test
    void givenTamperedCiphertextWhenReceivedThenRejectedAndChannelBroken() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SecureChannel sender = pairOverBuffers(wire, InputStream.nullInputStream())[0];
        byte[] record = capture(sender, wire, Frame.of(FrameType.MESSAGE, new byte[]{1, 2, 3}))[0];
        record[record.length - 1] ^= 1;
        SecureChannel receiver = pairOverBuffers(new ByteArrayOutputStream(), new ByteArrayInputStream(record))[1];
        assertThrows(FrameException.class, receiver::receive);
        assertThrows(IOException.class, receiver::receive);
    }

    @Test
    void givenReplayedRecordWhenReceivedThenRejected() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SecureChannel sender = pairOverBuffers(wire, InputStream.nullInputStream())[0];
        byte[][] r = capture(sender, wire, Frame.of(FrameType.MESSAGE, new byte[]{1}));
        ByteArrayOutputStream replay = new ByteArrayOutputStream();
        replay.write(r[0]);
        replay.write(r[0]);
        SecureChannel receiver = pairOverBuffers(new ByteArrayOutputStream(), new ByteArrayInputStream(replay.toByteArray()))[1];
        assertNotNull(receiver.receive());
        assertThrows(FrameException.class, receiver::receive);
    }

    @Test
    void givenReorderedRecordsWhenReceivedThenRejected() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        SecureChannel sender = pairOverBuffers(wire, InputStream.nullInputStream())[0];
        byte[][] r = capture(sender, wire, Frame.of(FrameType.MESSAGE, new byte[]{1}), Frame.of(FrameType.MESSAGE, new byte[]{2}));
        ByteArrayOutputStream swapped = new ByteArrayOutputStream();
        swapped.write(r[1]);
        swapped.write(r[0]);
        SecureChannel receiver = pairOverBuffers(new ByteArrayOutputStream(), new ByteArrayInputStream(swapped.toByteArray()))[1];
        assertThrows(FrameException.class, receiver::receive);
    }

    @Test
    void givenOversizedRecordLengthWhenReadThenRejectedWithoutAllocating() {
        byte[] header = {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThrows(FrameException.class, () -> SecureChannel.readRecord(new DataInputStream(new ByteArrayInputStream(header)), 1024));
    }
}
