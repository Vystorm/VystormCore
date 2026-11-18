package me.clip.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Hand-written minimal stand-in for PlaceholderAPI's static entry class: the two documented methods Core calls by
 * reflection, with the documented behaviour (a placeholder of an unknown expansion, or one the expansion answers with
 * null, stays in the text). See {@link PlaceholderHook} for why this exists.
 */
public final class PlaceholderAPI {
    private PlaceholderAPI() {}

    public static String setPlaceholders(OfflinePlayer player, String text) {
        return replace(text, false, (expansion, params) -> expansion.onRequest(player, params));
    }

    public static String setRelationalPlaceholders(Player one, Player two, String text) {
        return replace(text, true, (expansion, params) ->
                expansion instanceof Relational relational ? relational.onPlaceholderRequest(one, two, params) : null);
    }

    public static boolean isRegistered(String identifier) { return StubExpansions.get(identifier) != null; }

    private interface Call { String ask(PlaceholderExpansion expansion, String params); }

    private static String replace(String text, boolean relational, Call call) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('%', i);
            int close = open < 0 ? -1 : text.indexOf('%', open + 1);
            if (close < 0) break;
            String inner = text.substring(open + 1, close);
            if (relational && inner.startsWith("rel_")) inner = inner.substring(4);
            else if (relational) inner = "";
            int under = inner.indexOf('_');
            PlaceholderExpansion expansion = under <= 0 ? null : StubExpansions.get(inner.substring(0, under));
            String value = expansion == null ? null : call.ask(expansion, inner.substring(under + 1));
            if (value == null) {
                out.append(text, i, close);
                i = close;
                continue;
            }
            out.append(text, i, open).append(value);
            i = close + 1;
        }
        return out.append(text, i, text.length()).toString();
    }
}
