package Vystorm.vystorm_core.inbox.internal;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inbox messages per player in {@code plugins/vystorm_core/inbox/<uuid>.yml}. Each file is read on first use and kept
 * in memory; every change writes it atomically. Messages older than {@code maxAgeDays} (0 = no limit) are dropped,
 * and at most {@code maxPerPlayer} are kept (oldest first out). A file that cannot be read is moved aside and that
 * player's inbox is not saved until the next restart. Thread-safe. Not public API.
 */
public final class InboxStore {
    public static final int MAX_TEXT = 500;
    public static final int MAX_ARGS = 12;
    static final long DAY = 24L * 60 * 60 * 1000;

    /**
     * One message: either {@code namespace}+{@code key}+{@code args} (rendered on reading) or a plain {@code text}
     * ({@code key} empty). {@code source} is the sending plugin's name.
     */
    public record Message(long id, long time, String source, String namespace, String key, List<String> args, String text, boolean read) {
        public Message {
            source = source == null ? "?" : source;
            namespace = namespace == null ? "" : namespace;
            key = key == null ? "" : key;
            args = args == null ? List.of() : List.copyOf(args);
            text = text == null ? "" : text;
        }
        Message asRead() { return read ? this : new Message(id, time, source, namespace, key, args, text, true); }
        boolean plain() { return key.isEmpty(); }
    }

    private final Path folder;
    private final Map<UUID, List<Message>> boxes = new HashMap<>();
    private final Set<UUID> broken = new HashSet<>();

    public InboxStore(Path folder) { this.folder = folder; }

    /** Adds a message and saves; returns it. */
    public synchronized Message add(UUID player, long now, String source, String namespace, String key, List<String> args, String text,
                                    int maxPerPlayer, int maxAgeDays) {
        List<Message> box = box(player);
        long id = box.isEmpty() ? 1 : box.getLast().id() + 1;
        List<String> cleanArgs = new ArrayList<>();
        if (args != null) for (String a : args) { if (cleanArgs.size() >= MAX_ARGS * 2) break; cleanArgs.add(clean(a, 120)); }
        Message message = new Message(id, now, clean(source, 40), namespace, key == null ? "" : key.strip(), cleanArgs, clean(text, MAX_TEXT), false);
        box.add(message);
        prune(box, now, maxPerPlayer, maxAgeDays);
        save(player, box);
        return message;
    }

    /** Messages not older than {@code maxAgeDays}, newest first. */
    public synchronized List<Message> messages(UUID player, long now, int maxAgeDays) {
        List<Message> box = box(player), out = new ArrayList<>();
        for (int i = box.size() - 1; i >= 0; i--) if (fresh(box.get(i), now, maxAgeDays)) out.add(box.get(i));
        return out;
    }

    public synchronized int unread(UUID player, long now, int maxAgeDays) {
        int n = 0;
        for (Message m : box(player)) if (!m.read() && fresh(m, now, maxAgeDays)) n++;
        return n;
    }

    /** Marks everything as read; saves only if something changed. */
    public synchronized void markRead(UUID player) {
        List<Message> box = box(player);
        boolean changed = false;
        for (int i = 0; i < box.size(); i++) if (!box.get(i).read()) { box.set(i, box.get(i).asRead()); changed = true; }
        if (changed) save(player, box);
    }

    /** Removes read messages; returns how many. */
    public synchronized int deleteRead(UUID player) {
        List<Message> box = box(player);
        int before = box.size();
        box.removeIf(Message::read);
        if (box.size() != before) save(player, box);
        return before - box.size();
    }

    /** Forgets the cached box (e.g. after the player left); the file stays. */
    public synchronized void evict(UUID player) { boxes.remove(player); }

    private List<Message> box(UUID player) {
        return boxes.computeIfAbsent(player, this::load);
    }

    private Path file(UUID player) { return folder.resolve(player + ".yml"); }

    private List<Message> load(UUID player) {
        List<Message> out = new ArrayList<>();
        Path file = file(player);
        if (folder == null || !Files.exists(file)) return out;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            broken.add(player);
            Path aside = AtomicFiles.moveAside(file);
            java.util.logging.Logger.getLogger("Vystorm_Core").severe("[Inbox] " + file.getFileName() + " could not be read (" + failure.getMessage()
                    + "). It was kept as " + aside.getFileName() + "; this inbox is not saved until the next restart.");
            return out;
        }
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        if (section == null) return out;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            try {
                out.add(new Message(Long.parseLong(id.trim()), s.getLong("time"), s.getString("source", "?"), s.getString("namespace", ""),
                        s.getString("key", ""), s.getStringList("args"), s.getString("text", ""), s.getBoolean("read")));
            } catch (NumberFormatException skipped) {
                DebugMessages.mid("[Inbox] Skipped message with invalid id " + id + " in " + file.getFileName());
            }
        }
        out.removeIf(m -> m.key().isEmpty() && m.text().isEmpty());
        out.sort(java.util.Comparator.comparingLong(Message::id));
        return out;
    }

    private void save(UUID player, List<Message> box) {
        if (folder == null || broken.contains(player)) return;
        Path file = file(player);
        try {
            if (box.isEmpty()) { Files.deleteIfExists(file); return; }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of("Inbox of " + player + " (/vmenu inbox). Written by Vystorm Core."));
            for (Message m : box) {
                String k = "messages." + m.id();
                yaml.set(k + ".time", m.time());
                yaml.set(k + ".source", m.source());
                if (!m.plain()) {
                    yaml.set(k + ".namespace", m.namespace());
                    yaml.set(k + ".key", m.key());
                    if (!m.args().isEmpty()) yaml.set(k + ".args", m.args());
                } else yaml.set(k + ".text", m.text());
                if (m.read()) yaml.set(k + ".read", true);
            }
            Files.createDirectories(folder);
            AtomicFiles.write(file, yaml);
        } catch (IOException | RuntimeException failure) {
            DebugMessages.high("[Inbox] " + file.getFileName() + " not saved: " + failure);
        }
    }

    static boolean fresh(Message m, long now, int maxAgeDays) { return maxAgeDays <= 0 || now - m.time() <= maxAgeDays * DAY; }

    static void prune(List<Message> box, long now, int maxPerPlayer, int maxAgeDays) {
        box.removeIf(m -> !fresh(m, now, maxAgeDays));
        int keep = Math.max(1, maxPerPlayer);
        while (box.size() > keep) box.removeFirst();
    }

    /** Plain single-line text without control characters or § codes, cut to {@code max}. */
    static String clean(String raw, int max) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.replace('§', '&').toCharArray()) out.append(Character.isISOControl(c) ? ' ' : c);
        String s = out.toString().replaceAll("\\s{2,}", " ").strip();
        return s.length() > max ? s.substring(0, max).strip() : s;
    }
}
