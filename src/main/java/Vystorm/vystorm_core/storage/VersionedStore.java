package Vystorm.vystorm_core.storage;

import Vystorm.vystorm_core.API;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Crash-sicherer Zustands-Speicher mit Schema-Version: der gesamte Zustand wird als JSON unter einem Schlüssel
 * gespeichert, ältere Versionen werden Schritt für Schritt migriert, ein fehlgeschlagenes Speichern wird zurückgerollt.
 * Neuere (unbekannte) Versionen werden nie überschrieben.
 * <p>Preferred access: {@code api.data().versioned()}.
 */
public final class VersionedStore<T> {
    /** Liest/schreibt den rohen Text; Standard ist ein Core-DataEntry. */
    public interface Backend { String read(); void write(String value); }

    private final Backend backend;private final Class<T> type;private final int schema;
    private final TreeMap<Integer, UnaryOperator<JsonObject>> migrations;private final Supplier<T> empty;
    private final Gson gson;
    private final Consumer<String> quarantine;

    public VersionedStore(Backend backend, Class<T> type, int schema, Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty, Gson gson) {
        this(backend, type, schema, migrations, empty, gson, null);
    }

    /**
     * Wie der Standard-Konstruktor; {@code quarantine} erhält den Rohtext, wenn er nicht lesbar ist (z. B. um ihn als
     * Sicherung abzulegen), bevor {@link #load()} mit {@link CorruptStoreException} abbricht.
     *
     * @since 0.11.1
     */
    public VersionedStore(Backend backend, Class<T> type, int schema, Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty, Gson gson,
                          Consumer<String> quarantine) {
        this.quarantine = quarantine;
        this.backend = Objects.requireNonNull(backend);this.type = Objects.requireNonNull(type);this.schema = schema;
        this.migrations = new TreeMap<>(migrations);this.empty = Objects.requireNonNull(empty);this.gson = gson == null ? new Gson() : gson;
        if (schema < 1) throw new IllegalArgumentException("schema must be >= 1");
        for (int target : this.migrations.keySet()) if (target < 2 || target > schema) throw new IllegalArgumentException("migration target out of range: " + target);
    }

    /**
     * Öffnet einen Speicher in {@code <Plugin-Ordner>/<folder>/<file>} über die Core-Datenschnittstelle.
     * {@code migrations} bildet Zielversion auf einen Schritt ab (z. B. 2 → migriert 1 zu 2).
     */
    public static <T> VersionedStore<T> open(JavaPlugin plugin, API core, String folder, String file, Class<T> type, int schema,
                                             Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty) throws Exception {
        return open((org.bukkit.plugin.Plugin) plugin, core, folder, file, type, schema, migrations, empty);
    }

    /** Additive overload for lifecycle-owned modules with separate data folders. @since 0.31.0 */
    @SuppressWarnings("deprecation")
    public static <T> VersionedStore<T> open(org.bukkit.plugin.Plugin plugin, API core, String folder, String file, Class<T> type, int schema,
                                             Map<Integer, UnaryOperator<JsonObject>> migrations, Supplier<T> empty) throws Exception {
        Path path = plugin.getDataFolder().toPath().resolve(folder).resolve(file);
        if (!Files.exists(path)) core.data().creator().createFile(file, path.getParent());
        if (!core.data().loader().autoLoad(plugin, path)) throw new IllegalStateException("Store unavailable: " + path);
        // Loader indexes by the immediate parent name, even for nested plugin folders.
        var entry = core.data().dataEntry(plugin, path.getParent().getFileName().toString(), path, file);
        if (entry == null) throw new IllegalStateException("Loaded store entry unavailable: " + path);
        Backend backend = new Backend() {
            public String read() { synchronized (entry) { return entry.getEntry("state"); } }
            public void write(String value) {
                synchronized (entry) {
                    String old = entry.getEntry("state");
                    entry.setEntry("state", value);
                    try { entry.saveEntry(); } catch (RuntimeException failure) { entry.setEntry("state", old); throw failure; }
                }
            }
        };
        Consumer<String> quarantine = raw -> {
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.writeString(backup, raw);
                Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.high("[VersionedStore] Unreadable store " + path + " backed up to " + backup);
            } catch (java.io.IOException failure) {
                Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.high("[VersionedStore] Could not back up unreadable store " + path, failure);
            }
        };
        return new VersionedStore<>(backend, type, schema, migrations, empty, null, quarantine);
    }

    /** Unlesbarer Speicherinhalt (kaputtes JSON, falsches Format). Der Inhalt wird nie überschrieben. @since 0.11.1 */
    public static final class CorruptStoreException extends IllegalStateException {
        public CorruptStoreException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Lädt und migriert den Zustand; ein leerer Speicher liefert den Leerzustand und legt ihn an. Unlesbarer Inhalt
     * wird (falls konfiguriert) gesichert und führt zu {@link CorruptStoreException}; der Aufrufer darf dann nicht mit
     * einem Leerzustand weiterspeichern.
     */
    public T load() {
        String raw = backend.read();
        if (raw == null || raw.isBlank()) { T fresh = empty.get(); save(fresh); return fresh; }
        JsonObject envelope;
        JsonObject data;int version;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) throw new IllegalStateException("Store content is not a JSON object");
            envelope = parsed.getAsJsonObject();
            if (envelope.has("schema") && envelope.has("data")) {
                version = envelope.get("schema").getAsInt();data = envelope.getAsJsonObject("data");
            } else { version = 1;data = envelope; } // Vor dem Versionsformat gespeicherte Zustände gelten als Version 1.
        } catch (RuntimeException unreadable) {
            if (quarantine != null) {
                try { quarantine.accept(raw); } catch (RuntimeException ignored) { }
            }
            throw new CorruptStoreException("Store content is unreadable: " + unreadable.getMessage(), unreadable);
        }
        if (version > schema) throw new IllegalStateException("Store schema " + version + " is newer than supported " + schema);
        boolean migrated = version < schema;
        if (migrated) for (var step : migrations.subMap(version + 1, true, schema, true).entrySet()) {
            data = step.getValue().apply(data);
            if (data == null) throw new IllegalStateException("Migration to schema " + step.getKey() + " returned null");
        }
        T state = gson.fromJson(data, type);
        if (state == null) throw new IllegalStateException("Store content is empty");
        if (migrated) save(state);
        return state;
    }

    public void save(T state) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("schema", schema);
        envelope.add("data", gson.toJsonTree(Objects.requireNonNull(state)));
        backend.write(gson.toJson(envelope));
    }

    public int schema() { return schema; }
}
