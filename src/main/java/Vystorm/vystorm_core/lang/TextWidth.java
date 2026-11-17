package Vystorm.vystorm_core.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Display width of text for layout that the client does not do itself: item lore lines, centred chat lines, sign and
 * book lines, fixed-width columns. Chinese, Japanese and Korean characters (and full-width forms, most emoji) are about
 * twice as wide as Latin letters, so counting {@code String.length()} breaks those layouts.
 *
 * <ul>
 *   <li><b>Columns</b> ({@link #columns}): East Asian width – full-width/wide characters count 2, combining marks and
 *       zero-width characters 0, everything else 1. Use for "max N characters per lore line" style limits.</li>
 *   <li><b>Pixels</b> ({@link #pixels}): an approximation of Minecraft's default font (advance including the 1 px gap:
 *       most Latin letters 6, {@code i}/{@code l}/{@code .} 2, space 4, CJK 9). Bold adds 1 per character. Use for
 *       centring in chat (chat is {@link #CHAT_PIXELS} wide) or fitting sign lines ({@link #SIGN_PIXELS}).</li>
 * </ul>
 *
 * Legacy {@code §x}/{@code &x} colour codes count 0 and are carried over to wrapped lines; pass MiniMessage text through
 * {@code PlainTextComponentSerializer} or a {@code Lang.text(...)} legacy string first. Thread-safe, never throws.
 *
 * @since 0.45.0
 */
public final class TextWidth {
    /** Width of the chat box at default settings, in pixels. */
    public static final int CHAT_PIXELS = 320;
    /** Usable width of one sign line, in pixels. */
    public static final int SIGN_PIXELS = 90;
    /** Usable width of one book page line, in pixels. */
    public static final int BOOK_PIXELS = 114;
    /** A comfortable item lore line, in columns (about 30 Latin letters or 15 Chinese characters). */
    public static final int LORE_COLUMNS = 30;

    private static final String CLOSING = "，。、；：？！）》」』】〕〉”’…—～·%,.;:?!)]}％）";
    private static final String OPENING = "（《「『【〔〈“‘([{";

    private TextWidth() {}

    // ------------------------------------------------------------------ single characters

    /** Whether the code point is East Asian wide or full-width (CJK ideographs, kana, Hangul, full-width forms, emoji). */
    public static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // Hangul Jamo
                || (cp >= 0x2E80 && cp <= 0x303E)  // CJK radicals, Kangxi, ideographic punctuation and space
                || (cp >= 0x3041 && cp <= 0x33FF)  // kana, Bopomofo, Hangul compatibility, CJK strokes, enclosed
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK extension A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK unified ideographs
                || (cp >= 0xA000 && cp <= 0xA4CF)  // Yi
                || (cp >= 0xA960 && cp <= 0xA97F)  // Hangul Jamo extended A
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // Hangul syllables
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK compatibility ideographs
                || (cp >= 0xFE10 && cp <= 0xFE19)  // vertical forms
                || (cp >= 0xFE30 && cp <= 0xFE6F)  // CJK compatibility and small forms
                || (cp >= 0xFF00 && cp <= 0xFF60)  // full-width ASCII and punctuation
                || (cp >= 0xFFE0 && cp <= 0xFFE6)  // full-width signs
                || (cp >= 0x1F300 && cp <= 0x1F64F) // pictographs, emoticons
                || (cp >= 0x1F900 && cp <= 0x1F9FF) // supplemental symbols and pictographs
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK extensions B and later
    }

    /** Whether the code point takes no space (combining marks, zero-width space/joiners, variation selectors). */
    public static boolean isZeroWidth(int cp) {
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF) return true;
        if (cp >= 0xFE00 && cp <= 0xFE0F) return true;
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT
                || type == Character.CONTROL;
    }

    /** Columns of one code point: 2 wide, 0 zero-width, else 1. */
    public static int columns(int cp) { return isZeroWidth(cp) ? 0 : isWide(cp) ? 2 : 1; }

    /** Approximate pixel advance of one code point in Minecraft's default font (not bold). */
    public static int pixels(int cp) {
        if (isZeroWidth(cp)) return 0;
        if (isWide(cp)) return 9;
        return switch (cp) {
            case '!', '\'', ',', '.', ':', ';', '|', 'i', 'l' -> 2;
            case '`' -> 3;
            case ' ', 'I', '[', ']', 't', '"', '(', ')', '*', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    // ------------------------------------------------------------------ strings

    /** Removes legacy colour and format codes ({@code §a}, {@code &l}, {@code §x§r§r§g§g§b§b}). */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int code = codeLength(text, i);
            if (code > 0) { i += code - 1; continue; }
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    /** Display columns of a (legacy-formatted) string; the widest line if it contains line breaks. */
    public static int columns(String text) {
        int best = 0;
        for (String line : lines(text)) best = Math.max(best, measure(line, false));
        return best;
    }

    /** Display columns of a component (plain text). */
    public static int columns(Component component) {
        return component == null ? 0 : columns(PlainTextComponentSerializer.plainText().serialize(component));
    }

    /** Approximate pixel width of a (legacy-formatted) string in the default font; {@code §l} bold adds 1 per char. */
    public static int pixels(String text) {
        int best = 0;
        for (String line : lines(text)) best = Math.max(best, measure(line, true));
        return best;
    }

    /**
     * Wraps text to lines of at most {@code maxColumns} columns. Latin text breaks at spaces (words longer than a line
     * are split), CJK text between any two characters, without starting a line with closing punctuation (，。！？）…) or
     * ending one with opening punctuation (（“《…). Existing line breaks are kept; the active legacy colour/format codes
     * are repeated at the start of every continued line. Never returns an empty list.
     */
    public static List<String> wrap(String text, int maxColumns) {
        List<String> out = new ArrayList<>();
        int max = Math.max(2, maxColumns);
        String carry = "";
        for (String paragraph : lines(text)) {
            List<String> wrapped = wrapLine(carry + paragraph, max);
            out.addAll(wrapped);
            carry = activeCodes(carry + paragraph);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /** Pads {@code text} with leading spaces so it is centred in {@code totalColumns} columns (unchanged if wider). */
    public static String center(String text, int totalColumns) {
        String value = text == null ? "" : text;
        int pad = (totalColumns - columns(value)) / 2;
        return pad <= 0 ? value : " ".repeat(pad) + value;
    }

    /** Pads {@code text} with leading spaces so it is centred in a chat line ({@link #CHAT_PIXELS}). */
    public static String centerChat(String text) { return centerPixels(text, CHAT_PIXELS); }

    /** Pads {@code text} with leading spaces (4 px each) so it is centred in {@code totalPixels}. */
    public static String centerPixels(String text, int totalPixels) {
        String value = text == null ? "" : text;
        int pad = (totalPixels - pixels(value)) / 2 / pixels(' ');
        return pad <= 0 ? value : " ".repeat(pad) + value;
    }

    /** Pads {@code text} with trailing spaces to {@code totalColumns} columns (for aligned columns in monospace output). */
    public static String padRight(String text, int totalColumns) {
        String value = text == null ? "" : text;
        int pad = totalColumns - columns(value);
        return pad <= 0 ? value : value + " ".repeat(pad);
    }

    /** Cuts {@code text} to at most {@code maxColumns} columns, ending with "…" when something was cut. */
    public static String truncate(String text, int maxColumns) {
        String value = text == null ? "" : text;
        if (columns(value) <= maxColumns) return value;
        int budget = Math.max(0, maxColumns - 1), used = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int code = codeLength(value, i);
            if (code > 0) { out.append(value, i, i + code); i += code; continue; }
            int cp = value.codePointAt(i);
            int w = columns(cp);
            if (used + w > budget) break;
            out.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return out.append('…').toString();
    }

    // ------------------------------------------------------------------ internals

    private static List<String> lines(String text) {
        return List.of((text == null ? "" : text).split("\n", -1));
    }

    private static int measure(String line, boolean pixels) {
        int total = 0;
        boolean bold = false;
        for (int i = 0; i < line.length(); ) {
            int code = codeLength(line, i);
            if (code > 0) {
                char c = Character.toLowerCase(line.charAt(i + code - 1));
                if (code == 2) { if (c == 'l') bold = true; else if (c == 'r' || isColor(c)) bold = false; }
                else bold = false; // §x hex colour resets formats
                i += code;
                continue;
            }
            int cp = line.codePointAt(i);
            if (pixels) { int px = pixels(cp); total += px == 0 ? 0 : px + (bold ? 1 : 0); }
            else total += columns(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    private static boolean isColor(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); }

    /** Length of a legacy code at {@code i} ({@code §a} = 2, {@code §x§r§r§g§g§b§b} = 14), else 0. */
    private static int codeLength(String s, int i) {
        char c = s.charAt(i);
        if ((c != '§' && c != '&') || i + 1 >= s.length()) return 0;
        char k = Character.toLowerCase(s.charAt(i + 1));
        if (k == 'x' && i + 14 <= s.length()) {
            for (int j = i + 2; j < i + 14; j += 2)
                if (s.charAt(j) != c || Character.digit(s.charAt(j + 1), 16) < 0) return c == '§' ? 2 : 0;
            return 14;
        }
        if (isColor(k) || (k >= 'k' && k <= 'o') || k == 'r') return 2;
        return 0;
    }

    /** The colour and format codes in effect at the end of {@code text} (to continue it on the next line). */
    static String activeCodes(String text) {
        String color = "";
        StringBuilder formats = new StringBuilder();
        String s = text == null ? "" : text;
        for (int i = 0; i < s.length(); ) {
            int code = codeLength(s, i);
            if (code == 0) { i += Character.charCount(s.codePointAt(i)); continue; }
            String raw = s.substring(i, i + code);
            char k = Character.toLowerCase(s.charAt(i + code - 1));
            if (code == 14 || isColor(k)) { color = raw; formats.setLength(0); }
            else if (k == 'r') { color = ""; formats.setLength(0); }
            else formats.append(raw);
            i += code;
        }
        return color + formats;
    }

    private enum Kind { CODE, SPACE, WORD, WIDE }
    private record Tok(String text, int cols, Kind kind) {}

    private static List<Tok> tokens(String line) {
        List<Tok> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int wordCols = 0;
        for (int i = 0; i < line.length(); ) {
            int code = codeLength(line, i);
            int cp = line.codePointAt(i);
            boolean breaksWord = code > 0 || cp == ' ' || isWide(cp);
            if (breaksWord && !word.isEmpty()) { out.add(new Tok(word.toString(), wordCols, Kind.WORD)); word.setLength(0); wordCols = 0; }
            if (code > 0) { out.add(new Tok(line.substring(i, i + code), 0, Kind.CODE)); i += code; continue; }
            String ch = new String(Character.toChars(cp));
            if (cp == ' ') out.add(new Tok(ch, 1, Kind.SPACE));
            else if (isWide(cp)) out.add(new Tok(ch, 2, Kind.WIDE));
            else { word.append(ch); wordCols += columns(cp); }
            i += Character.charCount(cp);
        }
        if (!word.isEmpty()) out.add(new Tok(word.toString(), wordCols, Kind.WORD));
        return out;
    }

    private static boolean closing(Tok t) { return t.kind != Kind.CODE && t.kind != Kind.SPACE && CLOSING.indexOf(t.text.codePointAt(0)) >= 0; }
    private static boolean opening(Tok t) { return t.kind != Kind.CODE && t.kind != Kind.SPACE && OPENING.indexOf(t.text.codePointAt(t.text.length() - 1)) >= 0; }

    private static List<String> wrapLine(String line, int max) {
        List<String> out = new ArrayList<>();
        List<Tok> current = new ArrayList<>();
        int width = 0;
        String prefix = "";
        List<Tok> queue = new ArrayList<>(tokens(line));
        for (int index = 0; index < queue.size(); index++) {
            Tok t = queue.get(index);
            if (t.kind == Kind.CODE) { current.add(t); continue; }
            if (t.kind == Kind.SPACE && width == 0 && !out.isEmpty()) continue;  // no leading space on a continued line
            if (t.kind == Kind.WORD && t.cols > max) {                           // split an over-long word
                List<Tok> parts = new ArrayList<>();
                StringBuilder part = new StringBuilder();
                int cols = 0, room = Math.max(1, max - width);
                for (int i = 0; i < t.text.length(); ) {
                    int cp = t.text.codePointAt(i);
                    if (cols + columns(cp) > room && !part.isEmpty()) { parts.add(new Tok(part.toString(), cols, Kind.WORD)); part.setLength(0); cols = 0; room = max; }
                    part.appendCodePoint(cp); cols += columns(cp);
                    i += Character.charCount(cp);
                }
                if (!part.isEmpty()) parts.add(new Tok(part.toString(), cols, Kind.WORD));
                queue.remove(index);
                queue.addAll(index, parts);
                index--;
                continue;
            }
            boolean fits = width + t.cols <= max;
            if (!fits && closing(t) && width > 0) fits = true;                   // hanging punctuation
            if (fits) { current.add(t); width += t.cols; continue; }
            if (t.kind == Kind.SPACE) {                                         // break at the space itself
                out.add(emit(prefix, current));
                prefix = activeCodes(prefix + join(current));
                current = new ArrayList<>();
                width = 0;
                continue;
            }
            List<Tok> moved = new ArrayList<>();
            int last = lastVisible(current);
            if (last >= 0 && opening(current.get(last)) && last > 0) {          // keep opening punctuation with what follows
                moved.addAll(current.subList(last, current.size()));
                current = new ArrayList<>(current.subList(0, last));
            }
            if (lastVisible(current) >= 0) {
                out.add(emit(prefix, current));
                prefix = activeCodes(prefix + join(current));
                current = new ArrayList<>(moved);
                width = 0;
                for (Tok m : moved) width += m.cols;
            }
            current.add(t);
            width += t.cols;
        }
        if (lastVisible(current) >= 0 || out.isEmpty()) out.add(emit(prefix, current));
        return out;
    }

    private static int lastVisible(List<Tok> toks) {
        for (int i = toks.size() - 1; i >= 0; i--) if (toks.get(i).kind != Kind.CODE && toks.get(i).kind != Kind.SPACE) return i;
        return -1;
    }

    private static String join(List<Tok> toks) {
        StringBuilder out = new StringBuilder();
        for (Tok t : toks) out.append(t.text);
        return out.toString();
    }

    private static String emit(String prefix, List<Tok> toks) {
        int end = toks.size();
        while (end > 0 && toks.get(end - 1).kind == Kind.SPACE) end--;
        return prefix + join(toks.subList(0, end));
    }
}
