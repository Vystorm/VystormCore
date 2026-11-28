package Vystorm.vystorm_core.actions.internal;

import Vystorm.vystorm_core.actions.ActionLogEntry;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit line per action (pure): 12 tab-separated fields, {@code time actorId actorName plugin action world target
 * before after command result message}. Backslash, tab, line breaks, the section sign and every other control
 * character are escaped (backslash-t, backslash-n, backslash-r, backslash-backslash, and backslash-u plus four hex digits for the rest), so a
 * value can never start a new line, fake a field or carry colour codes into a console. Core-internal.
 */
public final class ActionLogFormat {
    static final int FIELDS = 12;
    private static final Pattern MONTH_FILE = Pattern.compile("(\\d{4})-(\\d{2})\\.log");

    private ActionLogFormat() {}

    public static String line(ActionLogEntry e) {
        String[] f = {e.time().toString(), e.actorId() == null ? "" : e.actorId().toString(), e.actorName(), e.plugin(), e.action(), e.world(),
                e.target(), e.before(), e.after(), e.command(), e.result(), e.message()};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) out.append('\t');
            out.append(escape(f[i]));
        }
        return out.append('\n').toString();
    }

    /** Parses one line; null for a broken line. */
    public static ActionLogEntry parse(String line) {
        if (line == null || line.isBlank()) return null;
        String[] f = line.split("\t", -1);
        if (f.length != FIELDS) return null;
        try {
            Instant time = Instant.parse(unescape(f[0]));
            UUID actor = f[1].isEmpty() ? null : UUID.fromString(unescape(f[1]));
            return new ActionLogEntry(time, actor, unescape(f[2]), unescape(f[3]), unescape(f[4]), unescape(f[5]), unescape(f[6]),
                    unescape(f[7]), unescape(f[8]), unescape(f[9]), unescape(f[10]), unescape(f[11]));
        } catch (DateTimeParseException | IllegalArgumentException broken) {
            return null;
        }
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (unsafe(c))
                        out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /**
     * Characters that are escaped in the log and must never reach a console or chat line raw: C0 and C1 controls,
     * DEL, the section sign (legacy colours), line and paragraph separators, and the bidi and zero-width format
     * characters that make a line look different from what it says.
     */
    public static boolean unsafe(char c) {
        return c < 0x20 || c == 0x7f || c == '\u00a7' || (c >= 0x80 && c < 0xa0) || c == '\u2028' || c == '\u2029'
                || (c >= '\u200b' && c <= '\u200f') || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069') || c == '\ufeff';
    }

    public static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { out.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException bad) { out.append("\\u"); }
                    } else out.append("\\u");
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    /** {@code 2026-11.log}. */
    public static String fileName(YearMonth month) { return String.format("%04d-%02d.log", month.getYear(), month.getMonthValue()); }

    public static String fileName(Instant time) { return fileName(YearMonth.from(time.atZone(ZoneOffset.UTC))); }

    /** The month of a log file name, or null. */
    public static YearMonth month(String fileName) {
        var m = MONTH_FILE.matcher(fileName);
        if (!m.matches()) return null;
        int month = Integer.parseInt(m.group(2));
        return month < 1 || month > 12 ? null : YearMonth.of(Integer.parseInt(m.group(1)), month);
    }

    /** Files (names) older than {@code keep} months before {@code now}. */
    public static List<String> outdated(List<String> names, YearMonth now, int keep) {
        YearMonth oldest = now.minusMonths(Math.max(1, keep) - 1L);
        List<String> out = new ArrayList<>();
        for (String n : names) {
            YearMonth m = month(n);
            if (m != null && m.isBefore(oldest)) out.add(n);
        }
        return out;
    }
}
