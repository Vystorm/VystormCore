package Vystorm.vystorm_core.licensing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Internal wire protocol. No filesystem cache, redirects, response-body logging, or exception details to callers. */
final class ModuleTransport {
    static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private static HttpClient http;

    private static synchronized HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return http;
    }

    static synchronized void shutdown() {
        if (http != null) http.shutdownNow();
        http = null;
    }

    record Reply(boolean granted, String lease, long deadline, byte[] jar) {}
    record Call(CompletableFuture<Reply> result, Runnable cancel) {}

    static Call request(ModuleRequest settings, String instance, String action, String lease) {
        String nonce = UUID.randomUUID().toString();
        long started = System.nanoTime();
        JsonObject body = body(settings, instance, action, lease, nonce);
        HttpRequest request = HttpRequest.newBuilder(settings.endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        LimitedBody subscriber = new LimitedBody(MAX_RESPONSE);
        CompletableFuture<HttpResponse<byte[]>> network = client().sendAsync(request, info -> subscriber);
        CompletableFuture<Reply> result = network.thenApplyAsync(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("Module service unavailable");
            return verify(settings, instance, action, lease, nonce, started, response.body());
        }).orTimeout(10, TimeUnit.SECONDS);
        Runnable cancel = () -> { subscriber.cancel(); network.cancel(true); result.cancel(false); };
        result.whenComplete((reply, failure) -> {
            if (failure != null) { subscriber.cancel(); network.cancel(true); }
        });
        return new Call(result, cancel);
    }

    static JsonObject body(ModuleRequest settings, String instance, String action, String lease, String nonce) {
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("action", action);
        body.addProperty("key", settings.key);
        body.addProperty("product", settings.product);
        body.addProperty("version", settings.version);
        body.addProperty("instance", instance);
        body.addProperty("nonce", nonce);
        body.addProperty("lease", lease);
        return body;
    }

    static Reply verify(ModuleRequest settings, String instance, String action, String lease, String nonce,
                        long started, byte[] response) {
        try {
            if (response.length > MAX_RESPONSE) throw new IllegalArgumentException();
            JsonObject envelope = JsonParser.parseString(new String(response, StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] payload = Base64.getDecoder().decode(envelope.get("payload").getAsString());
            byte[] signature = Base64.getDecoder().decode(envelope.get("sig").getAsString());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(settings.signingKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new IllegalArgumentException();
            JsonObject data = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(settings.key.getBytes(StandardCharsets.UTF_8)));
            if (!"1".equals(data.get("protocol").getAsString()) || !equal(data, "action", action)
                    || !equal(data, "product", settings.product) || !equal(data, "version", settings.version)
                    || !equal(data, "instance", instance) || !equal(data, "nonce", nonce)
                    || !equal(data, "licenseHash", keyHash)) throw new IllegalArgumentException();
            String status = data.get("status").getAsString();
            if ("denied".equals(status)) return new Reply(false, "", 0, new byte[0]);
            if (!"granted".equals(status)) throw new IllegalArgumentException();
            String grantedLease = data.get("lease").getAsString();
            if (!grantedLease.matches("[A-Za-z0-9_-]{16,128}")
                    || (!"acquire".equals(action) && !grantedLease.equals(lease))) throw new IllegalArgumentException();
            int ttl = Integer.parseInt(data.get("ttlSeconds").getAsString());
            if (ttl < 30 || ttl > 300) throw new IllegalArgumentException();
            byte[] jar = "acquire".equals(action) ? Base64.getDecoder().decode(data.get("jar").getAsString()) : new byte[0];
            if (jar.length > 4 * 1024 * 1024 || ("acquire".equals(action) && jar.length == 0)) throw new IllegalArgumentException();
            return new Reply(true, grantedLease, started + TimeUnit.SECONDS.toNanos(ttl), jar);
        } catch (Exception failure) {
            // Do not retain server-controlled response data, customer keys or parse exceptions in the error chain.
            throw new IllegalStateException("Invalid signed module response");
        }
    }

    private static boolean equal(JsonObject object, String key, String expected) {
        return expected.equals(object.get(key).getAsString());
    }

    /** Bounds the complete body, including chunked responses. Cancellation stops a slow/oversized body too. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { cancel(); return; }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            if (subscription != null) subscription.request(1);
        }
        public synchronized void onError(Throwable error) {
            result.completeExceptionally(new IllegalStateException("Module service unavailable"));
            bytes.reset();
        }
        public synchronized void onComplete() { result.complete(bytes.toByteArray()); bytes.reset(); }
        synchronized void cancel() {
            result.completeExceptionally(new IllegalStateException("Module response cancelled"));
            if (subscription != null) subscription.cancel();
            bytes.reset();
        }
    }
}
