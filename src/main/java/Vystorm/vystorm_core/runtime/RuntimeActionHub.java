package Vystorm.vystorm_core.runtime;

import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-plugin action router for Vystorm systems.
 *
 * Actions use semantic ids (for example structure.plan, structure.materialize,
 * worldevent.signal, spawn.context, city.project) and neutral primitive payloads so
 * plugins do not need compile-time dependencies on each other.
 *
 * Handlers must not load chunks implicitly. Expensive work should return an asynchronous
 * CompletionStage and physical world mutation must still be committed in the correct server context.
 * <p>Preferred access: {@code api.runtime().actions()}.
 */
public final class RuntimeActionHub {
    public record Request(
            UUID requestId,
            String action,
            UUID worldId,
            int blockX,
            int blockY,
            int blockZ,
            Optional<UUID> playerId,
            Set<String> tags,
            Map<String, String> values,
            Map<String, Double> scores) {
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            action = normalize(action);
            Objects.requireNonNull(worldId, "worldId");
            playerId = Objects.requireNonNull(playerId, "playerId");
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            scores = Map.copyOf(Objects.requireNonNull(scores, "scores"));
            scores.values().forEach(value -> {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite score");
            });
        }

        public static Request world(String action, UUID worldId, int x, int y, int z) {
            return new Request(UUID.randomUUID(), action, worldId, x, y, z, Optional.empty(), Set.of(), Map.of(), Map.of());
        }
    }

    public record Response(
            String handlerId,
            boolean accepted,
            String status,
            Set<String> tags,
            Map<String, String> values,
            Map<String, Double> scores) {
        public Response {
            if (handlerId == null || handlerId.isBlank()) throw new IllegalArgumentException("handlerId missing");
            status = status == null ? "" : status;
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            scores = Map.copyOf(Objects.requireNonNull(scores, "scores"));
        }

        public static Response accepted(String handlerId, Map<String, String> values) {
            return new Response(handlerId, true, "accepted", Set.of(), values, Map.of());
        }

        public static Response declined(String handlerId, String status) {
            return new Response(handlerId, false, status, Set.of(), Map.of(), Map.of());
        }
    }

    public interface Handler {
        String id();
        Set<String> actions();
        default int priority() { return 0; }
        default boolean supports(Request request) { return actions().contains(request.action()); }
        CompletionStage<Response> handle(Request request);
    }

    public record Registration(UUID id, Plugin owner, Handler handler, long order) {}

    private static final RuntimeActionHub INSTANCE = new RuntimeActionHub();
    private final Map<UUID, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<String, UUID> handlerIds = new ConcurrentHashMap<>();
    /** Unveränderliche, sortierte Momentaufnahme; wird beim Registrieren atomar getauscht. */
    private volatile List<Registration> ordered = List.of();
    private final AtomicLong order = new AtomicLong();

    private RuntimeActionHub() {}
    public static RuntimeActionHub get() { return INSTANCE; }

    public synchronized Registration register(Plugin owner, Handler handler) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(handler, "handler");
        if (handler.id() == null || handler.id().isBlank()) throw new IllegalArgumentException("handler id missing");
        UUID existing = handlerIds.get(handler.id().toLowerCase(Locale.ROOT));
        Registration previous = existing == null ? null : registrations.get(existing);
        if (previous != null) Vystorm.vystorm_core.lifecycle.ProviderFailures.replaced("RuntimeActionHub", "Handler " + handler.id(), previous.owner(), owner);
        unregisterHandler(handler.id());
        Registration registration = new Registration(UUID.randomUUID(), owner, handler, order.incrementAndGet());
        registrations.put(registration.id(), registration);
        handlerIds.put(handler.id().toLowerCase(Locale.ROOT), registration.id());
        rebuild();
        return registration;
    }

    public synchronized boolean unregister(UUID registrationId) {
        Registration removed = registrations.remove(registrationId);
        if (removed == null) return false;
        handlerIds.remove(removed.handler().id().toLowerCase(Locale.ROOT), registrationId);
        rebuild();
        return true;
    }

    public synchronized boolean unregisterHandler(String handlerId) {
        if (handlerId == null) return false;
        UUID id = handlerIds.remove(handlerId.toLowerCase(Locale.ROOT));
        if (id == null) return false;
        registrations.remove(id);
        rebuild();
        return true;
    }

    public synchronized int unregisterOwner(Plugin owner) {
        int before = registrations.size();
        registrations.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        handlerIds.entrySet().removeIf(entry -> !registrations.containsKey(entry.getValue()));
        rebuild();
        return before - registrations.size();
    }

    /** Dispatches to the highest-priority enabled handler that accepts the action. */
    public CompletionStage<Response> dispatch(Request request) {
        Objects.requireNonNull(request, "request");
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            return onServerThread(() -> dispatch(request));
        }
        for (Registration registration : ordered) {
            if (!registration.owner().isEnabled()) continue;
            Handler handler = registration.handler();
            if (!handler.actions().contains(request.action()) || !handler.supports(request)) continue;
            try {
                CompletionStage<Response> stage = handler.handle(request);
                if (stage == null) return CompletableFuture.completedFuture(Response.declined(handler.id(), "null-stage"));
                return stage.exceptionally(failure -> Response.declined(handler.id(), "failed:" + failure.getClass().getSimpleName()));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(Response.declined(handler.id(), "failed:" + failure.getClass().getSimpleName()));
            }
        }
        return CompletableFuture.completedFuture(Response.declined("core", "no-handler"));
    }

    /** Broadcasts to every supporting enabled handler. */
    public CompletionStage<List<Response>> broadcast(Request request) {
        Objects.requireNonNull(request, "request");
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            return onServerThread(() -> broadcast(request));
        }
        List<CompletableFuture<Response>> futures = new ArrayList<>();
        for (Registration registration : ordered) {
            if (!registration.owner().isEnabled()) continue;
            Handler handler = registration.handler();
            if (!handler.actions().contains(request.action()) || !handler.supports(request)) continue;
            try {
                CompletableFuture<Response> future = handler.handle(request).toCompletableFuture()
                        .exceptionally(failure -> Response.declined(handler.id(), "failed:" + failure.getClass().getSimpleName()));
                futures.add(future);
            } catch (RuntimeException failure) {
                futures.add(CompletableFuture.completedFuture(Response.declined(handler.id(), "failed:" + failure.getClass().getSimpleName())));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public Collection<Registration> registrations() { return List.copyOf(ordered); }

    /** Handler selection and initial Bukkit reads always execute on the server thread. */
    private static <T> CompletionStage<T> onServerThread(java.util.function.Supplier<CompletionStage<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            org.bukkit.Bukkit.getScheduler().runTask(Vystorm.vystorm_core.main.getPlugin(), () -> {
                try {
                    operation.get().whenComplete((value, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(value);
                    });
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void rebuild() {
        List<Registration> next = new ArrayList<>(registrations.values());
        next.sort(Comparator.<Registration>comparingInt(entry -> entry.handler().priority()).reversed()
                .thenComparingLong(Registration::order));
        ordered = List.copyOf(next);
    }

    private static String normalize(String action) {
        if (action == null) throw new IllegalArgumentException("action missing");
        String normalized = action.trim().toLowerCase(Locale.ROOT).replace(' ', '.');
        if (normalized.isEmpty()) throw new IllegalArgumentException("action missing");
        return normalized;
    }
}
