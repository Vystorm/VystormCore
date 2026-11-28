package Vystorm.vystorm_core.actions.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure checks before a vanilla command is dispatched for an admin: which command roots the line runs ({@code execute
 * ... run <cmd>} counts the inner command too) and which roots are never run from a menu. Core-internal.
 */
public final class VanillaDispatch {
    /** Never run through the runner, whatever the permissions. */
    public static final Set<String> BLOCKED = Set.of("op", "deop", "stop", "reload", "restart");

    private VanillaDispatch() {}

    /** The line without a leading slash and without a {@code minecraft:} prefix on the first word; trimmed. */
    public static String normalize(String line) {
        String s = line == null ? "" : line.trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("minecraft:")) s = s.substring("minecraft:".length());
        return s;
    }

    /** Every command root the line runs: the first word and the word after each {@code run}. Lower case, no namespace. */
    public static List<String> roots(String line) {
        String[] words = normalize(line).split("\\s+");
        List<String> out = new ArrayList<>();
        if (words.length == 0 || words[0].isEmpty()) return out;
        out.add(root(words[0]));
        for (int i = 1; i < words.length - 1; i++) if (words[i].equalsIgnoreCase("run")) out.add(root(words[i + 1]));
        return out;
    }

    private static String root(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        int colon = w.indexOf(':');
        return colon >= 0 ? w.substring(colon + 1) : w;
    }

    /** Whether the line has selectors or relative/local coordinates, which resolve against the sender. */
    public static boolean contextual(String line) {
        String s = normalize(line);
        return s.contains("@") || s.contains("~") || s.contains("^");
    }

    /** The first blocked root of the line, or null. */
    public static String blocked(String line) {
        for (String r : roots(line)) if (BLOCKED.contains(r)) return r;
        return null;
    }

    /** {@code minecraft.command.<root>} for every root of the line. */
    public static List<String> permissions(String line) {
        List<String> out = new ArrayList<>();
        for (String r : roots(line)) out.add("minecraft.command." + switch (r) {
            case "tp" -> "teleport";
            case "xp" -> "experience";
            case "tell", "w" -> "msg";
            case "tm" -> "teammsg";
            default -> r;
        });
        return out;
    }
}
