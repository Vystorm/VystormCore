package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every change saved through the config editor (sections, drafts, profiles and undos): who changed which value of which
 * file when, with the value before and after. Kept in {@code plugins/vystorm_core/config-history.yml}, newest
 * {@code max} entries. Values of secret paths are never recorded (the editor cannot change them anyway).
 * Server thread only. Not public API; used by {@code /vadmin config history|undo}.
 *
 * @since 0.44.0
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ConfigHistory {
    public static final int DEFAULT_MAX = 200;
    static final int MAX_VALUE_CHARS = 2000;

    /** One changed value; {@code null} means "the key was not in the file" (the jar default applied). */
    public record Change(String path, Object before, Object after) {}

    /** One save. {@code undoOf} is the entry this one reverted (0 = a normal save), {@code undoneBy} the entry that reverted this one. */
    public record Entry(int id, long time, String actor, String plugin, String file, List<Change> changes, int undoOf, int undoneBy) {
        public Entry {
            changes = List.copyOf(changes);
        }
        public boolean undone() { return undoneBy > 0; }
    }

    private final Path file;
    private final int max;
    private final List<Entry> entries = new ArrayList<>();
    private int nextId = 1;
    private Path broken;

    public ConfigHistory(Path file, int max) {
        this.file = file;
        this.max = Math.clamp(max, 1, 5000);
        load();
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            // Never start empty and overwrite the history with the next change: keep the file aside and stop saving.
            broken = AtomicFiles.moveAside(file);
            java.util.logging.Logger.getLogger("Vystorm_Core").severe("[ConfigHistory] config-history.yml could not be read (" + failure.getMessage()
                    + "). It was kept as " + broken.getFileName() + " and the history is not saved until the next restart."
                    + " Fix that file, rename it back to config-history.yml and restart.");
            return;
        }
        for (Map<?, ?> raw : yaml.getMapList("entries")) {
            try {
                List<Change> changes = new ArrayList<>();
                if (raw.get("changes") instanceof List<?> list) for (Object o : list) {
                    if (!(o instanceof Map<?, ?> c) || c.get("path") == null) continue;
                    changes.add(new Change(String.valueOf(c.get("path")), c.get("before"), c.get("after")));
                }
                entries.add(new Entry(number(raw.get("id")), (long) numberLong(raw.get("time")), text(raw.get("actor")),
                        text(raw.get("plugin")), text(raw.get("file")), changes, number(raw.get("undo-of")), number(raw.get("undone-by"))));
            } catch (RuntimeException broken) {
                DebugMessages.mid("[ConfigHistory] Skipped an unreadable entry: " + broken);
            }
        }
        int highest = entries.stream().mapToInt(Entry::id).max().orElse(0);
        nextId = Math.max(highest + 1, yaml.getInt("next-id", 1));
    }

    private static int number(Object o) { return o instanceof Number n ? n.intValue() : o == null ? 0 : Integer.parseInt(o.toString().trim()); }
    private static long numberLong(Object o) { return o instanceof Number n ? n.longValue() : o == null ? 0 : Long.parseLong(o.toString().trim()); }
    private static String text(Object o) { return o == null ? "" : o.toString(); }

    /**
     * Records one save. Changes whose value did not really change, and secret paths, are left out; nothing is written
     * when no change remains.
     *
     * @return the new entry, or null if nothing was recorded
     */
    public Entry record(String actor, String plugin, String fileName, List<Change> changes, int undoOf, long now) {
        List<Change> kept = new ArrayList<>();
        for (Change change : changes) {
            if (change == null || change.path() == null || ConfigEditorService.secret(change.path())) continue;
            if (same(change.before(), change.after())) continue;
            kept.add(new Change(change.path(), plain(change.before()), plain(change.after())));
        }
        if (kept.isEmpty()) return null;
        Entry entry = new Entry(nextId++, now, actor == null ? "?" : actor, plugin, fileName, kept, Math.max(0, undoOf), 0);
        if (undoOf > 0) {
            // Undoing an undo puts the values of the entry that undo reverted back: that entry is active (and undoable) again.
            int restored = get(undoOf).map(Entry::undoOf).orElse(0);
            for (int i = 0; i < entries.size(); i++) {
                Entry old = entries.get(i);
                if (old.id() == undoOf) entries.set(i, withUndoneBy(old, entry.id()));
                else if (restored > 0 && old.id() == restored && old.undoneBy() == undoOf) entries.set(i, withUndoneBy(old, 0));
            }
        }
        entries.add(entry);
        while (entries.size() > max) entries.removeFirst();
        save();
        return entry;
    }

    private static Entry withUndoneBy(Entry old, int undoneBy) {
        return new Entry(old.id(), old.time(), old.actor(), old.plugin(), old.file(), old.changes(), old.undoOf(), undoneBy);
    }

    public Optional<Entry> get(int id) { return entries.stream().filter(e -> e.id() == id).findFirst(); }

    /** Matching entries, newest first. */
    public List<Entry> newestFirst(Predicate<Entry> filter) {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) if (filter.test(entries.get(i))) out.add(entries.get(i));
        return out;
    }

    public int size() { return entries.size(); }

    public int max() { return max; }

    /** Where the unreadable history file was moved to, or null when it loaded fine; while set nothing is saved. */
    Path broken() { return broken; }

    private void save() {
        if (file == null || broken != null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Changes made with the Vystorm config editor (/vadmin config history). Written by Core."));
        yaml.set("next-id", nextId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id()); m.put("time", e.time()); m.put("actor", e.actor()); m.put("plugin", e.plugin()); m.put("file", e.file());
            if (e.undoOf() > 0) m.put("undo-of", e.undoOf());
            if (e.undoneBy() > 0) m.put("undone-by", e.undoneBy());
            List<Map<String, Object>> changes = new ArrayList<>();
            for (Change c : e.changes()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("path", c.path());
                if (c.before() != null) cm.put("before", c.before());
                if (c.after() != null) cm.put("after", c.after());
                changes.add(cm);
            }
            m.put("changes", changes);
            out.add(m);
        }
        yaml.set("entries", out);
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, yaml);
        } catch (IOException | RuntimeException failure) {
            DebugMessages.high("[ConfigHistory] config-history.yml not saved: " + failure);
        }
    }

    /** Values as plain YAML types (String, Number, Boolean, List); very long texts are kept up to {@link #MAX_VALUE_CHARS}. */
    static Object plain(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(o instanceof Number || o instanceof Boolean ? o : String.valueOf(o));
            return out;
        }
        String text = String.valueOf(value);
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) : text;
    }

    /** Whether two config values are the same: numbers by value (30 = 30.0), lists element by element, else by text. */
    public static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof List<?> || b instanceof List<?>) return false;
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** Short display text of a value: {@code null} as "—", lists comma-separated, long texts cut to {@code limit} characters. */
    public static String show(Object value, int limit) {
        String text;
        if (value == null) text = "—";
        else if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object o : list) { if (out.length() > 1) out.append(", "); out.append(o); }
            text = out.append(']').toString();
        } else if (value instanceof String s) text = "\"" + s + "\"";
        else text = String.valueOf(value);
        text = text.replace('\n', ' ').replace('§', '&');
        return text.length() > limit ? text.substring(0, Math.max(1, limit - 1)) + "…" : text;
    }
}
