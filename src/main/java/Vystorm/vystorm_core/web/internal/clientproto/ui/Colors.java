// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, Colors.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.ui;

import java.util.Locale;
import java.util.Map;

/** Farben als ARGB-int: {@code #RRGGBB}, {@code #AARRGGBB} oder einer der 16 Minecraft-Farbnamen. */
public final class Colors {
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0xFF000000), Map.entry("dark_blue", 0xFF0000AA), Map.entry("dark_green", 0xFF00AA00),
            Map.entry("dark_aqua", 0xFF00AAAA), Map.entry("dark_red", 0xFFAA0000), Map.entry("dark_purple", 0xFFAA00AA),
            Map.entry("gold", 0xFFFFAA00), Map.entry("gray", 0xFFAAAAAA), Map.entry("dark_gray", 0xFF555555),
            Map.entry("blue", 0xFF5555FF), Map.entry("green", 0xFF55FF55), Map.entry("aqua", 0xFF55FFFF),
            Map.entry("red", 0xFFFF5555), Map.entry("light_purple", 0xFFFF55FF), Map.entry("yellow", 0xFFFFFF55),
            Map.entry("white", 0xFFFFFFFF));

    private Colors() {}

    /** @return ARGB oder null, wenn ungültig */
    public static Integer parse(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(v);
        if (named != null) return named;
        if (!v.startsWith("#") || (v.length() != 7 && v.length() != 9)) return null;
        for (int i = 1; i < v.length(); i++) if (Character.digit(v.charAt(i), 16) < 0) return null;
        long parsed = Long.parseLong(v.substring(1), 16);
        return v.length() == 7 ? (int) (0xFF000000L | parsed) : (int) parsed;
    }
}
