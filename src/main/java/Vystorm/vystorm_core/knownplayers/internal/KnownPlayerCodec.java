package Vystorm.vystorm_core.knownplayers.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The file {@code known-players.dat}: a header line {@code # vystorm known-players v1}, then one line per player
 * {@code uuid TAB name TAB firstSeen TAB lastSeen TAB flags TAB skin} (times in epoch ms, flags {@code -} or {@code h} for
 * "not findable when offline", skin empty without one). About 120 bytes per player. A line that cannot be read is
 * skipped and counted, never a reason to drop the file. Pure. Core-internal.
 */
public final class KnownPlayerCodec {
    public static final String HEADER = "# vystorm known-players v1";

    /** Read result: the entries and how many lines were damaged. */
    public record Decoded(List<KnownPlayerStore.Entry> entries, int damaged) {}

    private KnownPlayerCodec() {}

    public static String encode(List<KnownPlayerStore.Entry> entries) {
        StringBuilder out = new StringBuilder(64 + entries.size() * 120);
        out.append(HEADER).append('\n');
        for (KnownPlayerStore.Entry e : entries) {
            out.append(e.id()).append('\t').append(e.name()).append('\t').append(e.firstSeen()).append('\t').append(e.lastSeen())
                    .append('\t').append(e.hidden() ? 'h' : '-').append('\t').append(e.skin() == null ? "" : e.skin()).append('\n');
        }
        return out.toString();
    }

    public static Decoded decode(String text) {
        List<KnownPlayerStore.Entry> entries = new ArrayList<>();
        int damaged = 0;
        if (text == null) return new Decoded(entries, 0);
        for (String raw : text.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank() || line.startsWith("#")) continue;
            KnownPlayerStore.Entry e = line(line);
            if (e == null) damaged++;
            else entries.add(e);
        }
        return new Decoded(entries, damaged);
    }

    static KnownPlayerStore.Entry line(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 5) return null;
        try {
            UUID id = UUID.fromString(f[0]);
            long first = Long.parseLong(f[2]);
            long last = Long.parseLong(f[3]);
            if (last < 0 || first < 0) return null;
            String name = cleanName(f[1]);
            if (name.isEmpty()) return null;
            return new KnownPlayerStore.Entry(id, name, first, last, f.length > 5 ? f[5] : null, f[4].contains("h"));
        } catch (IllegalArgumentException damaged) {
            return null;
        }
    }

    /** Name without separators and control characters, at most 32 characters (Bedrock prefixes and long offline names fit). */
    public static String cleanName(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder();
        name.strip().codePoints().filter(c -> !Character.isISOControl(c) && !Character.isWhitespace(c)).limit(32).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Only an http(s) address without whitespace; anything else counts as "no skin". */
    public static String cleanSkin(String skin) {
        if (skin == null) return null;
        String s = skin.strip();
        if (s.isEmpty() || s.length() > 256 || !(s.startsWith("https://") || s.startsWith("http://"))) return null;
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i)) || Character.isISOControl(s.charAt(i))) return null;
        return s;
    }
}
