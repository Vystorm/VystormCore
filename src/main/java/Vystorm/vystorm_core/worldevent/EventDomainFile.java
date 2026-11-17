package Vystorm.vystorm_core.worldevent;

import Vystorm.vystorm_core.worldevent.engine.*;
import com.google.gson.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Core-owned persistent catalog/runtime. Invalid existing data is never reset or overwritten. */
final class EventDomainFile {
    private final Path path;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (value, type, context) -> Instant.parse(value.getAsString()))
            .registerTypeAdapter(Duration.class, (JsonSerializer<Duration>) (value, type, context) -> new JsonPrimitive(value.toString()))
            .registerTypeAdapter(Duration.class, (JsonDeserializer<Duration>) (value, type, context) -> Duration.parse(value.getAsString()))
            .create();
    private record Document(int schema, List<EventDefinition> definitions, EventRuntimeService.RuntimeSnapshot runtime, List<EventRuntimeService.PersistedActiveEvent> pendingEnds) { }
    EventDomainFile(Path path) { this.path = path; }
    List<ActiveEvent> load(EventRegistry registry, EventRuntimeService runtime) {
        if (!Files.exists(path)) return List.of();
        try {
            Document document = gson.fromJson(Files.readString(path), Document.class);
            if (document == null || document.schema != 1 || document.definitions == null || document.runtime == null)
                throw new IllegalStateException("Invalid event domain: " + path.getFileName());
            EventRegistry loaded = new EventRegistry(); document.definitions.forEach(loaded::register);
            for (var event : document.runtime.activeEvents()) if (loaded.find(event.eventId()).isEmpty())
                throw new IllegalStateException("Event definition missing: " + event.eventId());
            registry.replaceAll(loaded);
            var ended = new java.util.LinkedHashMap<java.util.UUID,ActiveEvent>();
            runtime.restore(document.runtime, registry).expiredWhileOffline().forEach(e -> ended.put(e.instanceId(),e));
            if (document.pendingEnds != null) for (var event : document.pendingEnds) {
                var definition = registry.find(event.eventId()).orElseThrow(() -> new IllegalStateException("Pending event definition missing"));
                ended.put(event.instanceId(),new ActiveEvent(event.instanceId(),definition,event.scope(),event.scopeKey(),event.startedAt(),event.endsAt()));
            }
            return List.copyOf(ended.values());
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    void save(EventRegistry registry, EventRuntimeService runtime) { save(registry,runtime,List.of()); }
    void save(EventRegistry registry, EventRuntimeService runtime, List<ActiveEvent> pending) {
        var pendingEnds = pending.stream().map(e -> new EventRuntimeService.PersistedActiveEvent(e.instanceId(),e.definition().id(),e.scope(),e.scopeKey(),e.startedAt(),e.endsAt())).toList();
        byte[] bytes = gson.toJson(new Document(1, List.copyOf(registry.all()), runtime.snapshotReadOnly(),pendingEnds)).getBytes(StandardCharsets.UTF_8);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
