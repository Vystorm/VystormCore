package Vystorm.vystorm_core.screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Minecraft map colour table as a constant (since 0.72.0, capability {@code map-palette}): what a
 * map colour byte looks like, the nearest byte for an RGB colour, and the 244 visible colours in a fixed order for
 * colour pickers. Reached through {@code api.gui().screens().mapColors()}.
 *
 * <p>Plugins used {@code org.bukkit.map.MapPalette} for this; {@code MapPalette.getColor} is deprecated for removal
 * on Paper 1.20.6, and its lookup goes through the server. This table is the game's own one, copied into Core, so it
 * gives the same answers on Paper 1.20.6, Paper/Purpur 26.x and Spigot 26.x (checked: 248 indices, bytes 0..3 are
 * transparent, 244 visible colours, the same RGB values on Spigot 26.2 and Paper 1.20.6). All 244 RGB values are
 * distinct, so a visible byte round-trips exactly through {@link #rgb} and {@link #nearest}.
 *
 * <p>Map colour bytes are {@code base * 4 + shade}: base colour 1..61, shade 0 = 180/255, 1 = 220/255, 2 = full,
 * 3 = 135/255 of the base colour. Bytes are signed in Java: byte 200 is {@code (byte) 200} = -56; every method takes
 * either form. Immutable and thread-safe.
 */
public final class MapColors {
    /** Number of colour bytes the game defines (0..247); bytes 248..255 are unused. */
    public static final int INDICES = 248;
    /** Number of visible (opaque) colours: bytes 4..247. */
    public static final int VISIBLE = 244;
    /** First visible byte; 0..3 draw nothing (transparent). */
    public static final int FIRST_VISIBLE = 4;

    /** RGB of bytes 4..247, from the game's map colour list (Spigot 26.2 = Paper 1.20.6). */
    private static final int[] RGB = {
            0x597D27, 0x6D9930, 0x7FB238, 0x435E1D, 0xAEA473, 0xD5C98C, 0xF7E9A3, 0x827B56,
            0x8C8C8C, 0xABABAB, 0xC7C7C7, 0x696969, 0xB40000, 0xDC0000, 0xFF0000, 0x870000,
            0x7070B4, 0x8A8ADC, 0xA0A0FF, 0x545487, 0x757575, 0x909090, 0xA7A7A7, 0x585858,
            0x005700, 0x006A00, 0x007C00, 0x004100, 0xB4B4B4, 0xDCDCDC, 0xFFFFFF, 0x878787,
            0x737681, 0x8D909E, 0xA4A8B8, 0x565861, 0x6A4C36, 0x825E42, 0x976D4D, 0x4F3928,
            0x4F4F4F, 0x606060, 0x707070, 0x3B3B3B, 0x2D2DB4, 0x3737DC, 0x4040FF, 0x212187,
            0x645432, 0x7B663E, 0x8F7748, 0x4B3F26, 0xB4B1AC, 0xDCD9D3, 0xFFFCF5, 0x878581,
            0x985924, 0xBA6D2C, 0xD87F33, 0x72431B, 0x7D3598, 0x9941BA, 0xB24CD8, 0x5E2872,
            0x486C98, 0x5884BA, 0x6699D8, 0x365172, 0xA1A124, 0xC5C52C, 0xE5E533, 0x79791B,
            0x599011, 0x6DB015, 0x7FCC19, 0x436C0D, 0xAA5974, 0xD06D8E, 0xF27FA5, 0x804357,
            0x353535, 0x414141, 0x4C4C4C, 0x282828, 0x6C6C6C, 0x848484, 0x999999, 0x515151,
            0x35596C, 0x416D84, 0x4C7F99, 0x284351, 0x592C7D, 0x6D3699, 0x7F3FB2, 0x43215E,
            0x24357D, 0x2C4199, 0x334CB2, 0x1B285E, 0x483524, 0x58412C, 0x664C33, 0x36281B,
            0x485924, 0x586D2C, 0x667F33, 0x36431B, 0x6C2424, 0x842C2C, 0x993333, 0x511B1B,
            0x111111, 0x151515, 0x191919, 0x0D0D0D, 0xB0A836, 0xD7CD42, 0xFAEE4D, 0x847E28,
            0x409A96, 0x4FBCB7, 0x5CDBD5, 0x307370, 0x345AB4, 0x3F6EDC, 0x4A80FF, 0x274387,
            0x009928, 0x00BB32, 0x00D93A, 0x00721E, 0x5B3C22, 0x6F4A2A, 0x815631, 0x442D19,
            0x4F0100, 0x600100, 0x700200, 0x3B0100, 0x937C71, 0xB4988A, 0xD1B1A1, 0x6E5D55,
            0x703919, 0x89461F, 0x9F5224, 0x542B13, 0x693D4C, 0x804B5D, 0x95576C, 0x4E2E39,
            0x4F4C61, 0x605D77, 0x706C8A, 0x3B3949, 0x835D19, 0xA0721F, 0xBA8524, 0x624613,
            0x485225, 0x58642D, 0x677535, 0x363D1C, 0x703637, 0x8A4243, 0xA04D4E, 0x542829,
            0x281C18, 0x31231E, 0x392923, 0x1E1512, 0x5F4B45, 0x745C54, 0x876B62, 0x473833,
            0x3D4040, 0x4B4F4F, 0x575C5C, 0x2E3030, 0x56333E, 0x693E4B, 0x7A4958, 0x40262E,
            0x352B40, 0x41354F, 0x4C3E5C, 0x282030, 0x352318, 0x412B1E, 0x4C3223, 0x281A12,
            0x35391D, 0x414624, 0x4C522A, 0x282B16, 0x642A20, 0x7A3327, 0x8E3C2E, 0x4B1F18,
            0x1A0F0B, 0x1F120D, 0x251610, 0x130B08, 0x852122, 0xA3292A, 0xBD3031, 0x641919,
            0x682C44, 0x7F3653, 0x943F61, 0x4E2133, 0x401114, 0x4F1519, 0x5C191D, 0x300D0F,
            0x0F585E, 0x126C73, 0x167E86, 0x0B4246, 0x286462, 0x327A78, 0x3A8E8C, 0x1E4B4A,
            0x3C1F2B, 0x4A2535, 0x562C3E, 0x2D1720, 0x0E7F5D, 0x119B72, 0x14B485, 0x0A5F46,
            0x464646, 0x565656, 0x646464, 0x343434, 0x987B67, 0xBA967E, 0xD8AF93, 0x725C4D,
            0x597569, 0x6D9081, 0x7FA796, 0x43584F,
    };

    /** Brightest shade first: 2 (full), 1, 0, 3. Before {@link #TABLE}: the constructor reads it. */
    private static final int[] SHADES = { 2, 1, 0, 3 };

    private static final MapColors TABLE = new MapColors();

    /** Visible bytes in picker order, built once. */
    private final byte[] ordered;

    private MapColors() {
        if (RGB.length != VISIBLE) throw new IllegalStateException("map colour table has " + RGB.length + " entries");
        ordered = order();
    }

    /** The table. Plugins reach it through {@code api.gui().screens().mapColors()}. */
    public static MapColors table() { return TABLE; }

    /** RGB ({@code 0xRRGGBB}) of a map colour byte, or -1 for the transparent bytes 0..3 and the unused 248..255. */
    public int rgb(byte colour) { return rgb(colour & 0xFF); }

    /** {@link #rgb(byte)} for an unsigned index 0..255 (or a negative byte value); -1 when not visible. */
    public int rgb(int index) {
        int i = index & 0xFF;
        return i < FIRST_VISIBLE || i >= INDICES ? -1 : RGB[i - FIRST_VISIBLE];
    }

    /** True for the 244 visible bytes 4..247. */
    public boolean visible(byte colour) { int i = colour & 0xFF; return i >= FIRST_VISIBLE && i < INDICES; }

    /**
     * The visible map colour byte nearest to {@code rgb} ({@code 0xRRGGBB}, alpha ignored). Distance as Bukkit's
     * {@code MapPalette.matchColor} (a red-mean weighted RGB distance, the same integer formula); ties go to the lower
     * byte, so the answers equal {@code matchColor} for opaque colours. A visible colour's own RGB gives back its byte. About 244 comparisons:
     * cache the results when converting whole pictures.
     */
    public byte nearest(int rgb) { return nearest(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF); }

    /** {@link #nearest(int)} for separate channels 0..255 (clamped). */
    public byte nearest(int red, int green, int blue) {
        int r = clamp(red), g = clamp(green), b = clamp(blue);
        long best = Long.MAX_VALUE;
        int index = FIRST_VISIBLE;
        for (int k = 0; k < RGB.length; k++) {
            int c = RGB[k];
            int cr = c >> 16 & 0xFF, cg = c >> 8 & 0xFF, cb = c & 0xFF;
            // Bukkit MapPalette.getDistance in integers: red weight 1024 + (r1 + r2), green 2048, blue 1024 + 510 - (r1 + r2)
            int rsum = r + cr, dr = r - cr, dg = g - cg, db = b - cb;
            long d = (long) (1024 + rsum) * dr * dr + 2048L * dg * dg + (long) (1024 + 510 - rsum) * db * db;
            if (d < best) { best = d; index = k + FIRST_VISIBLE; }
        }
        return (byte) index;
    }

    /**
     * The 244 visible bytes in a fixed order for colour pickers: one row of four per base colour, its shades from
     * bright to dark (shade 2, 1, 0, 3). Base colours come greys first (from white to black), then by hue (red, orange,
     * yellow, green, cyan, blue, purple, pink), each hue bucket of 15° from light to dark. The order is computed from
     * this table only, so it is the same on every server and in every Core version that keeps the table. A fresh copy.
     */
    public byte[] pickerOrder() { return ordered.clone(); }

    /** {@link #pickerOrder()} as a list (unmodifiable). */
    public List<Byte> pickerList() {
        List<Byte> list = new ArrayList<>(ordered.length);
        for (byte b : ordered) list.add(b);
        return List.copyOf(list);
    }

    /** Base colour 1..61 of a visible byte ({@code byte / 4}), 0 for a transparent one. */
    public int base(byte colour) { return visible(colour) ? (colour & 0xFF) >> 2 : 0; }

    /** Shade 0..3 of a byte ({@code byte % 4}). */
    public int shade(byte colour) { return colour & 3; }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static byte[] order() {
        int bases = VISIBLE / 4;   // 61: bytes 4..247 = base colours 1..61
        Integer[] base = new Integer[bases];
        for (int i = 0; i < bases; i++) base[i] = i + 1;
        Arrays.sort(base, (a, b) -> {
            double[] ka = key(RGB[a * 4 + 2 - FIRST_VISIBLE]), kb = key(RGB[b * 4 + 2 - FIRST_VISIBLE]);
            for (int i = 0; i < ka.length; i++) if (ka[i] != kb[i]) return Double.compare(ka[i], kb[i]);
            return Integer.compare(a, b);
        });
        byte[] out = new byte[VISIBLE];
        int n = 0;
        for (int b : base) for (int shade : SHADES) out[n++] = (byte) (b * 4 + shade);
        return out;
    }

    /** Sort key of a full-brightness base colour: {grey 0 / colour 1, hue bucket, -value, hue}. */
    private static double[] key(int rgb) {
        double r = (rgb >> 16 & 0xFF) / 255.0, g = (rgb >> 8 & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double chroma = max - min;
        double saturation = max == 0 ? 0 : chroma / max;
        if (saturation < 0.12) return new double[] { 0, 0, -max, 0 };
        double hue;
        if (max == r) hue = 60 * (((g - b) / chroma) % 6);
        else if (max == g) hue = 60 * ((b - r) / chroma + 2);
        else hue = 60 * ((r - g) / chroma + 4);
        if (hue < 0) hue += 360;
        return new double[] { 1, Math.floor(hue / 15), -max, hue };
    }
}
