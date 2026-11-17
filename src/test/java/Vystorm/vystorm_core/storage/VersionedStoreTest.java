package Vystorm.vystorm_core.storage;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionedStoreTest {
    record State(int count, String name) {}
    static final class Memory implements VersionedStore.Backend {
        final AtomicReference<String> value = new AtomicReference<>();
        public String read() { return value.get(); }
        public void write(String v) { value.set(v); }
    }

    @Test void nestedFolderUsesActualLoaderIndexAndRoundTrips(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(dir.toFile());
        org.mockito.Mockito.when(plugin.getName()).thenReturn("PouchTest");
        var api = org.mockito.Mockito.mock(Vystorm.vystorm_core.API.class);
        var data = new Vystorm.vystorm_core._coreMethods.dataStorage.DefaultDataHandler();
        org.mockito.Mockito.when(api.data()).thenReturn(data);
        try (var debug = org.mockito.Mockito.mockStatic(Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.class)) {
            var store = VersionedStore.open(plugin, api, "rewards/dream", "player.yml", State.class, 1, Map.of(), () -> new State(0, "fresh"));
            assertEquals(new State(0, "fresh"), store.load());
            store.save(new State(3, "earned"));
            var reopened = VersionedStore.open(plugin, api, "rewards/dream", "player.yml", State.class, 1, Map.of(), () -> new State(0, "fresh"));
            assertEquals(new State(3, "earned"), reopened.load());
            assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("rewards/dream/player.yml")));
        }
    }

    @Test void emptyStoreCreatesInitialState() {
        var memory = new Memory();
        var store = new VersionedStore<>(memory, State.class, 1, Map.of(), () -> new State(0, "fresh"), null);
        assertEquals(new State(0, "fresh"), store.load());
        assertTrue(memory.value.get().contains("\"schema\":1"));
    }

    @Test void legacyUnversionedStateIsMigratedAndRewritten() {
        var memory = new Memory();
        memory.value.set("{\"count\":3}");
        Map<Integer, UnaryOperator<JsonObject>> steps = Map.of(
                2, json -> { json.addProperty("name", "added"); return json; },
                3, json -> { json.addProperty("count", json.get("count").getAsInt() * 10); return json; });
        var store = new VersionedStore<>(memory, State.class, 3, steps, () -> new State(0, ""), null);
        assertEquals(new State(30, "added"), store.load());
        assertTrue(memory.value.get().contains("\"schema\":3"));
        // Second load runs no migration again.
        assertEquals(new State(30, "added"), store.load());
    }

    @Test void newerSchemaIsNeverOverwritten() {
        var memory = new Memory();
        memory.value.set("{\"schema\":9,\"data\":{\"count\":1}}");
        var store = new VersionedStore<>(memory, State.class, 2, Map.of(), () -> new State(0, ""), null);
        assertThrows(IllegalStateException.class, store::load);
        assertTrue(memory.value.get().contains("\"schema\":9"));
    }

    @Test void corruptJsonIsQuarantinedAndNeverOverwritten() {
        var memory = new Memory();
        memory.value.set("{\"schema\":1,\"data\":{broken");
        AtomicReference<String> quarantined = new AtomicReference<>();
        var store = new VersionedStore<>(memory, State.class, 1, Map.of(), () -> new State(0, ""), null, quarantined::set);
        assertThrows(VersionedStore.CorruptStoreException.class, store::load);
        assertEquals(memory.value.get(), quarantined.get());
        assertTrue(memory.value.get().contains("broken"));
    }

    @Test void migrationReturningNullFailsWithClearMessage() {
        var memory = new Memory();
        memory.value.set("{\"count\":1}");
        Map<Integer, UnaryOperator<JsonObject>> steps = Map.of(2, json -> null);
        var store = new VersionedStore<>(memory, State.class, 2, steps, () -> new State(0, ""), null);
        var failure = assertThrows(IllegalStateException.class, store::load);
        assertTrue(failure.getMessage().contains("schema 2"));
        assertEquals("{\"count\":1}", memory.value.get());
    }
}
