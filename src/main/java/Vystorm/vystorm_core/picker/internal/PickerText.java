package Vystorm.vystorm_core.picker.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Texts of a picker row as language keys with placeholders, the same for the screen and the chest. Last seen is
 * coarse for normal players ("today", "this week", "this month", "earlier"; owner decision 2) and an exact date only
 * with {@code vystorm_core.pickers.exact-last-seen}. Pure. Core-internal.
 */
public final class PickerText {
    /** A language key of Core with its placeholders (name, value pairs), or a raw text ({@code key} null). */
    public record Line(String key, Object[] args, Object raw) {
        public static Line of(String key, Object... args) { return new Line(key, args, null); }
        public static Line raw(Object text) { return new Line(null, new Object[0], text); }
    }

    public enum Seen { TODAY, WEEK, MONTH, EARLIER, UNKNOWN }

    static final long DAY = 24L * 60 * 60 * 1000;
    private static final DateTimeFormatter EXACT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private PickerText() {}

    public static Seen seen(long lastSeen, long now) {
        if (lastSeen <= 0) return Seen.UNKNOWN;
        long age = Math.max(0, now - lastSeen);
        if (age < DAY) return Seen.TODAY;
        if (age < 7 * DAY) return Seen.WEEK;
        if (age < 31 * DAY) return Seen.MONTH;
        return Seen.EARLIER;
    }

    /** "online", "online - 12 m", "seen this week" or (exact) "seen 2026-11-20 14:05". */
    public static Line status(PickerModel.Candidate c, boolean exact, long now, ZoneId zone) {
        if (c.online()) return c.distance() >= 0 ? Line.of("pickers.row.online-near", "distance", Math.round(c.distance())) : Line.of("pickers.row.online");
        Seen s = seen(c.lastSeen(), now);
        if (exact && s != Seen.UNKNOWN) return Line.of("pickers.row.seen-at", "date", EXACT.format(Instant.ofEpochMilli(c.lastSeen()).atZone(zone)));
        return Line.of("pickers.row.seen-" + s.name().toLowerCase(Locale.ROOT));
    }

    /** Key of a tab label ({@code {count}} placeholder). */
    public static String tabKey(Vystorm.vystorm_core.picker.PlayerSource s) { return "pickers.tab." + s.name().toLowerCase(Locale.ROOT); }
}
