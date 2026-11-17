package Vystorm.vystorm_core._parsersDebuggerEtc.debugger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Producers only read the snapshot; the writer owns YAML parsing and atomic file replacement. */
final class DebugStateStore {
    private final Path file;
    private final Consumer<String> emergency;
    private volatile Map<String, Boolean> switches = Map.of();
    private final Set<String> discovered = ConcurrentHashMap.newKeySet();
    private long nextRead;
    private long nextWarning;

    /** Konsolen-Schalter je Stufe: {@code _console.LOW} usw. Die Debug-Datei erhält unabhängig davon alles. */
    static final String CONSOLE = "_console";

    DebugStateStore(Path directory, Consumer<String> emergency) {
        file = directory.resolve("states.yml"); this.emergency = emergency;
        for (String level : new String[]{"LOW", "MID", "HIGH", "SHUTDOWN"}) discovered.add(CONSOLE + "." + level);
        // One initial read during service construction, so disabled levels are respected from the first message.
        try { reload(); } catch (IOException failure) { warn(failure); }
    }
    boolean enabled(String pointer, String level) {
        String key = pointer + "." + level;
        Boolean state = switches.get(key);
        if (state == null) discovered.add(key);
        return state == null ? defaultEnabled(key) : state;
    }
    private static boolean defaultEnabled(String key) {
        // Routine (LOW/MID) standardmäßig nicht in der Konsole; Wichtiges (HIGH) und SHUTDOWN immer.
        if (key.startsWith(CONSOLE + ".")) return key.endsWith(".HIGH") || key.endsWith(".SHUTDOWN");
        return !Set.of("PriorityHandler.LOW", "ParticleHandler.LOW", "ParticleHandler.MID").contains(key);
    }

    /** Ob Meldungen dieser Stufe in der Konsole erscheinen. */
    boolean console(String level) {
        Boolean state = switches.get(CONSOLE + "." + level);
        return state == null ? defaultEnabled(CONSOLE + "." + level) : state;
    }
    void maintain() {
        long now = System.nanoTime();
        if (now < nextRead) return;
        nextRead = now + TimeUnit.SECONDS.toNanos(2);
        try { reload(); } catch (IOException failure) { warn(failure); }
    }
    void reload() throws IOException {
        try {
            String original = Files.exists(file) ? Files.readString(file) : "";
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(original);
            Map<String, Boolean> loaded = snapshot(yaml);
            // Publish only after successful parsing and validation. Never replace a malformed user file.
            switches = Map.copyOf(loaded);
            Set<String> additions = new HashSet<>(discovered);
            for (String key : additions) if (!yaml.contains(key)) yaml.set(key, defaultEnabled(key));
            if (!additions.isEmpty()) {
                String current = Files.exists(file) ? Files.readString(file) : "";
                if (!current.equals(original)) return; // An editor changed the file: merge again next cycle.
                writeAtomically(yaml.saveToString());
                switches = Map.copyOf(snapshot(yaml));
                discovered.removeAll(additions);
            }
        } catch (InvalidConfigurationException | IllegalArgumentException failure) {
            throw new IOException("Invalid debugger switches; keeping last valid settings", failure);
        }
    }
    private Map<String, Boolean> snapshot(YamlConfiguration yaml) throws IOException {
        Map<String, Boolean> loaded = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (!key.matches(".+\\.(LOW|MID|HIGH|SHUTDOWN)")) continue;
            Object value = yaml.get(key);
            if (!(value instanceof Boolean state)) throw new IOException("Debug switch must be boolean: " + key);
            loaded.put(key, state);
        }
        return loaded;
    }
    private void writeAtomically(String content) throws IOException {
        DebugLogWriter.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "states-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DebugLogWriter.forceDirectory(file.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }
    private void warn(IOException failure) {
        long now = System.nanoTime();
        if (now < nextWarning) return;
        nextWarning = now + TimeUnit.SECONDS.toNanos(5);
        try { emergency.accept(failure.getMessage()); } catch (RuntimeException ignored) {}
    }
}
