package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.visual.Point3;
import java.util.Locale;
import org.bukkit.block.BlockFace;

/**
 * Typed coordinates of the world pick's options menu: {@code x y z}, separated by spaces or commas, decimals with a
 * dot, {@code ~} and {@code ~2} relative to the player's position; a face pick may add a fourth word
 * ({@code north east south west up down} or their first letter). Pure. Core-internal.
 */
public final class CoordinateInput {
    private CoordinateInput() {}

    /** Error keys under {@code pickers.world.typed.}. */
    public static final String FORMAT = "format", FACE = "face";

    /** A point and an optional face (null = not typed), or an error key. */
    public record Parsed(Point3 point, BlockFace face, String error) {
        public boolean ok() { return error == null; }
        static Parsed error(String key) { return new Parsed(null, null, key); }
    }

    public static Parsed parse(String text, Point3 origin, boolean allowFace) {
        if (text == null) return Parsed.error(FORMAT);
        String[] parts = text.trim().split("[\\s,;]+");
        if (parts.length == 1 && parts[0].isEmpty()) return Parsed.error(FORMAT);
        if (parts.length != 3 && !(allowFace && parts.length == 4)) return Parsed.error(FORMAT);
        double[] v = new double[3];
        double[] base = {origin.x(), origin.y(), origin.z()};
        for (int i = 0; i < 3; i++) {
            Double d = number(parts[i], base[i]);
            if (d == null) return Parsed.error(FORMAT);
            v[i] = d;
        }
        BlockFace face = null;
        if (parts.length == 4) {
            face = face(parts[3]);
            if (face == null) return Parsed.error(FACE);
        }
        return new Parsed(new Point3(v[0], v[1], v[2]), face, null);
    }

    static Double number(String s, double base) {
        try {
            if (s.startsWith("~")) return s.length() == 1 ? base : base + finite(Double.parseDouble(s.substring(1)));
            return finite(Double.parseDouble(s));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    private static double finite(double d) {
        if (!Double.isFinite(d) || Math.abs(d) > 30_000_000) throw new NumberFormatException("out of range");
        return d;
    }

    static BlockFace face(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> BlockFace.NORTH;
            case "east", "e" -> BlockFace.EAST;
            case "south", "s" -> BlockFace.SOUTH;
            case "west", "w" -> BlockFace.WEST;
            case "up", "u", "top" -> BlockFace.UP;
            case "down", "d", "bottom" -> BlockFace.DOWN;
            default -> null;
        };
    }

    /** The prefill: a block position as "x y z". */
    public static String text(int x, int y, int z) { return x + " " + y + " " + z; }
}
