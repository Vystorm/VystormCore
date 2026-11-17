package Vystorm.vystorm_core.network.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PairingTest {

    /** Mutable test clock. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final TestClock clock = new TestClock();

    @Test
    void givenOfferAndReplyThenBothSidesDeriveSameKeyAndCode() throws Exception {
        Pairing.HubSide hub = Pairing.hubStart("proxy-1", "127.0.0.1", 25599, clock);
        Pairing.ClientSide client = Pairing.clientAccept(PairingOffer.decode(hub.offer().encode()), clock);
        Optional<NetworkKey> hubKey = hub.acceptReply(client.publicKey());
        assertEquals(Optional.of(client.key()), hubKey);
        assertEquals(Optional.of(client.code()), hub.code());
    }

    @Test
    void givenCodeThenSixUnambiguousCharacters() throws Exception {
        Pairing.HubSide hub = Pairing.hubStart("p", "h", 1, clock);
        String code = Pairing.clientAccept(hub.offer(), clock).code();
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{6}"), code);
    }

    @Test
    void givenSecondReplyForSameOfferThenOfferBurnt() throws Exception {
        Pairing.HubSide hub = Pairing.hubStart("p", "h", 1, clock);
        Pairing.ClientSide real = Pairing.clientAccept(hub.offer(), clock);
        Pairing.ClientSide other = Pairing.clientAccept(hub.offer(), clock);
        assertTrue(hub.acceptReply(real.publicKey()).isPresent());
        assertTrue(hub.acceptReply(other.publicKey()).isEmpty());
        assertTrue(hub.compromised());
        assertTrue(hub.key().isEmpty());
    }

    @Test
    void givenExistingKeyWhenOfferArrivesThenIgnored() {
        PairingGate gate = new PairingGate(clock);
        assertTrue(gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), true).isEmpty());
    }

    @Test
    void givenPendingPairingWhenSecondOfferArrivesThenIgnored() {
        PairingGate gate = new PairingGate(clock);
        assertTrue(gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false).isPresent());
        assertTrue(gate.acceptOffer(Pairing.hubStart("evil", "10.0.0.9", 1, clock).offer(), false).isEmpty());
    }

    @Test
    void givenWrongCodeThenNotConfirmedAndRightCodeConfirmsOnce() {
        PairingGate gate = new PairingGate(clock);
        Pairing.ClientSide pending = gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false).orElseThrow();
        assertTrue(gate.confirm("ZZZZZZ".equals(pending.code()) ? "YYYYYY" : "ZZZZZZ").isEmpty());
        assertEquals(Optional.of(pending), gate.confirm(pending.code().toLowerCase()));
        assertTrue(gate.confirm(pending.code()).isEmpty());
    }

    @Test
    void givenTenMinutesPassedThenPendingExpiresAndNewOfferAccepted() {
        PairingGate gate = new PairingGate(clock);
        Pairing.ClientSide pending = gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false).orElseThrow();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertTrue(gate.confirm(pending.code()).isEmpty());
        assertTrue(gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false).isPresent());
    }

    @Test
    void givenExpiredHubOfferThenReplyRefused() throws Exception {
        Pairing.HubSide hub = Pairing.hubStart("p", "h", 1, clock);
        Pairing.ClientSide client = Pairing.clientAccept(hub.offer(), clock);
        clock.advance(Duration.ofMinutes(11));
        assertTrue(hub.acceptReply(client.publicKey()).isEmpty());
    }

    @Test
    void givenUnpairThenNewOfferAccepted() {
        PairingGate gate = new PairingGate(clock);
        gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false);
        gate.unpair();
        assertTrue(gate.acceptOffer(Pairing.hubStart("p", "h", 1, clock).offer(), false).isPresent());
    }

    @Test
    void givenGarbageWhenDecodingOfferThenRejected() {
        assertThrows(FrameException.class, () -> PairingOffer.decode(new byte[]{1, 2, 3}));
        assertThrows(FrameException.class, () -> PairingOffer.decode(new byte[5000]));
    }
}
