package Vystorm.vystorm_core._uiMethods.notifications;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, player-reference-free queue for low-priority routine messages. Main-thread confined. */
final class RoutineQueue {
    static final int MAX_DISTINCT = 16;
    static final int MAX_MESSAGE_BYTES = 512;
    static final int MAX_TOTAL_BYTES = 8192;
    static final int MAX_REPEAT = 999_999;
    static final int MAX_OMITTED_PROVIDERS = MAX_DISTINCT;

    record Pending(Plugin provider, String text, int repeats, int bytes) {}
    record Snapshot(List<Pending> messages, int omitted) {}
    private record Key(Plugin provider, String text) {}
    private static final class MutablePending {
        final Plugin provider;
        final String text;
        final int bytes;
        int repeats = 1;
        MutablePending(Plugin provider, String text, int bytes) { this.provider = provider; this.text = text; this.bytes = bytes; }
        Pending freeze() { return new Pending(provider, text, repeats, bytes); }
    }

    private final LinkedHashMap<Key, MutablePending> messages = new LinkedHashMap<>();
    private final Map<Plugin, Integer> omittedByProvider = new LinkedHashMap<>();
    private int encodedBytes;

    void add(Plugin provider, Component message) {
        String text = safeText(message);
        if (text.isBlank()) return;
        Key key = new Key(provider, text);
        MutablePending current = messages.get(key);
        if (current != null) {
            current.repeats = Math.min(MAX_REPEAT, current.repeats + 1);
            return;
        }
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (messages.size() >= MAX_DISTINCT || encodedBytes + bytes > MAX_TOTAL_BYTES) {
            if (!omittedByProvider.containsKey(provider) && omittedByProvider.size() >= MAX_OMITTED_PROVIDERS) return;
            omittedByProvider.compute(provider, (ignored, count) -> Math.min(MAX_REPEAT, (count == null ? 0 : count) + 1));
            return;
        }
        messages.put(key, new MutablePending(provider, text, bytes));
        encodedBytes += bytes;
    }

    void remove(Plugin provider) {
        var iterator = messages.entrySet().iterator();
        while (iterator.hasNext()) {
            MutablePending pending = iterator.next().getValue();
            if (pending.provider == provider) {
                encodedBytes -= pending.bytes;
                iterator.remove();
            }
        }
        omittedByProvider.remove(provider);
    }

    void removeDisabledProviders() {
        var iterator = messages.entrySet().iterator();
        while (iterator.hasNext()) {
            MutablePending pending = iterator.next().getValue();
            if (!pending.provider.isEnabled()) {
                encodedBytes -= pending.bytes;
                iterator.remove();
            }
        }
        omittedByProvider.keySet().removeIf(provider -> !provider.isEnabled());
    }

    Snapshot drain() {
        List<Pending> copy = new ArrayList<>(messages.size());
        messages.values().forEach(value -> copy.add(value.freeze()));
        int omitted = omittedByProvider.values().stream().mapToInt(Integer::intValue).sum();
        Snapshot result = new Snapshot(List.copyOf(copy), omitted);
        messages.clear(); encodedBytes = 0; omittedByProvider.clear();
        return result;
    }

    boolean isEmpty() { return messages.isEmpty() && omittedByProvider.isEmpty(); }
    int distinctCount() { return messages.size(); }
    int encodedBytes() { return encodedBytes; }
    int omittedCount() { return omittedByProvider.values().stream().mapToInt(Integer::intValue).sum(); }

    static String safeText(Component message) {
        String raw = PlainTextComponentSerializer.plainText().serialize(message);
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_MESSAGE_BYTES));
        int bytes = 0;
        for (int offset = 0; offset < raw.length();) {
            int codePoint = raw.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + codePointBytes > MAX_MESSAGE_BYTES) break;
            out.appendCodePoint(codePoint);
            bytes += codePointBytes;
            offset += charCount;
        }
        return out.toString().strip();
    }
}
