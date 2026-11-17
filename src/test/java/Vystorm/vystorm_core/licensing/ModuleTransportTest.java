package Vystorm.vystorm_core.licensing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class ModuleTransportTest {
    final KeyPair keys = keys();
    final ModuleRequest settings = new ModuleRequest(URI.create("https://licenses.example/modules"), "trees", "1.0",
            "CUSTOMER-ONLY-TEST-KEY", keys.getPublic(), "paid.example.", "paid.example.Entry");

    static KeyPair keys() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    JsonObject payload() throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("protocol", 1);
        data.addProperty("action", "acquire");
        data.addProperty("product", "trees");
        data.addProperty("version", "1.0");
        data.addProperty("instance", "test-instance");
        data.addProperty("nonce", "fresh-nonce");
        data.addProperty("licenseHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(settings.key.getBytes(StandardCharsets.UTF_8))));
        data.addProperty("status", "granted");
        data.addProperty("lease", "random-lease-identifier");
        data.addProperty("ttlSeconds", 90);
        data.addProperty("jar", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        return data;
    }

    byte[] signed(JsonObject data, KeyPair pair) throws Exception {
        byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payload);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("payload", Base64.getEncoder().encodeToString(payload));
        envelope.addProperty("sig", Base64.getEncoder().encodeToString(signer.sign()));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    ModuleTransport.Reply verify(byte[] response) {
        return ModuleTransport.verify(settings, "test-instance", "acquire", "", "fresh-nonce", 100, response);
    }

    @Test void givenSignedCustomerResponse_whenVerified_thenBoundedLeaseAndModuleAreReturned() throws Exception {
        var reply = verify(signed(payload(), keys));
        assertTrue(reply.granted());
        assertEquals(90_000_000_100L, reply.deadline());
        assertArrayEquals(new byte[] {1, 2, 3}, reply.jar());
        assertFalse(settings.toString().contains(settings.key));
    }

    @Test void givenWrongBindingOrSignature_whenVerified_thenResponseIsRejectedWithoutLeakingData() throws Exception {
        for (String field : List.of("protocol", "action", "product", "version", "instance", "nonce", "licenseHash", "status")) {
            JsonObject data = payload();
            data.addProperty(field, "wrong-" + settings.key);
            byte[] response = signed(data, keys);
            Exception failure = assertThrows(IllegalStateException.class, () -> verify(response), field);
            assertFalse(failure.toString().contains(settings.key));
            assertNull(failure.getCause());
        }
        byte[] foreign = signed(payload(), keys());
        assertThrows(IllegalStateException.class, () -> verify(foreign));
    }

    @Test void givenInvalidTtlOrChangedLease_whenVerified_thenNoAuthorization() throws Exception {
        for (String ttl : List.of("29", "301", "90.1", "2147483648")) {
            JsonObject data = payload(); data.addProperty("ttlSeconds", ttl);
            byte[] response = signed(data, keys);
            assertThrows(IllegalStateException.class, () -> verify(response));
        }
        JsonObject data = payload(); data.addProperty("action", "renew");
        byte[] response = signed(data, keys);
        assertThrows(IllegalStateException.class, () -> ModuleTransport.verify(settings, "test-instance", "renew",
                "different-lease-identifier", "fresh-nonce", 100, response));
    }

    @Test void givenSignedDenial_whenVerified_thenNoJarOrLeaseIsRequired() throws Exception {
        JsonObject data = payload(); data.addProperty("status", "denied");
        data.remove("jar"); data.remove("lease"); data.remove("ttlSeconds");
        assertFalse(verify(signed(data, keys)).granted());
    }

    @Test void givenUnsafeEndpoint_whenConfigured_thenCredentialsCannotGoToHttpOrUrlParameters() {
        for (String uri : List.of("http://example.com/modules", "https://user@example.com/modules",
                "https://example.com/modules?key=x", "https://example.com/modules#x")) {
            assertThrows(IllegalArgumentException.class, () -> new ModuleRequest(URI.create(uri), "trees", "1.0",
                    settings.key, keys.getPublic(), "paid.example.", "paid.example.Entry"));
        }
    }

    @Test void givenOversizeOrCancelledBody_whenMoreBytesArrive_thenSubscriptionIsCancelled() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        var body = new ModuleTransport.LimitedBody(3);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
        org.mockito.Mockito.verify(subscription).cancel();
        var cancelled = new ModuleTransport.LimitedBody(3);
        cancelled.cancel();
        Flow.Subscription late = mock(Flow.Subscription.class);
        cancelled.onSubscribe(late);
        org.mockito.Mockito.verify(late).cancel();
    }

    @Test void givenBoundedChunkedBody_whenCompleted_thenBytesAreCombined() {
        var body = new ModuleTransport.LimitedBody(3);
        body.onSubscribe(mock(Flow.Subscription.class));
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {2, 3})));
        body.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, body.getBody().toCompletableFuture().join());
    }
}
