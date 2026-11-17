package Vystorm.vystorm_core.screen.internal;

import org.bukkit.block.BlockFace;

/**
 * Wire format of PINNED map tiles (since 0.59.0). Each tile is a real 128x128 client map in an invisible client-only
 * item frame. Maps are drawn by the client with the same {@code text} core shader as text, so Core's {@code text.vsh}
 * can recognise them and pin them to the screen like the panel.
 *
 * <p>Row 0 of every tile is a header the shader reads with {@code texelFetch}; it is never shown (the shader samples
 * rows 1..127 only). Header pixels are bits: {@link #BIT_ONE} (snow, exact RGB 255,255,255) is 1, {@link #BIT_ZERO}
 * (black, exact RGB 25,25,25) is 0, MSB first, 16 bits per word:
 * <ol>
 *   <li>pixels 0..15: {@link #SIGNATURE}; anything else and the shader leaves the map alone (ordinary maps in frames
 *       and in the hand stay vanilla);</li>
 *   <li>16..31 / 32..47: tile centre x / y in panel units (view space at distance 1, x right, y up), {@code (v + 8) * 4096};</li>
 *   <li>48..63 / 64..79: half width / half height in panel units, {@code v * 4096}.</li>
 * </ol>
 */
public final class ScreenMapCodec {
    /** Pixels per tile row, and visible pixel rows per tile (row 0 carries the header). */
    public static final int SIZE = 128, VISIBLE_ROWS = 127;
    /** Map colour bytes (base colour * 4 + shade 2 = full brightness): SNOW and COLOR_BLACK. */
    public static final byte BIT_ONE = 34, BIT_ZERO = 118;
    public static final int SIGNATURE = 0xB5A3;
    /** Fixed-point scale of the header words. */
    public static final double FIXED = 4096.0, OFFSET = 8.0;
    /** Item frame geometry (26.2 {@code ItemFrame.createBoundingBox}): centre offset from the block centre, half depth. */
    static final double FRAME_OFFSET = 0.46875, FRAME_HALF_DEPTH = 0.03125;
    /** Vanilla {@code EntityRenderer.shouldRender} inflates the culling box by this much. */
    static final double CULL_INFLATE = 0.5;

    private ScreenMapCodec() { }

    /** The 128 header pixels for a tile centred at {@code (cx, cy)} with half size {@code (hw, hh)} in panel units. */
    public static byte[] header(double cx, double cy, double hw, double hh) {
        byte[] row = new byte[SIZE];
        java.util.Arrays.fill(row, BIT_ZERO);
        word(row, 0, SIGNATURE);
        word(row, 16, fixed(cx + OFFSET));
        word(row, 32, fixed(cy + OFFSET));
        word(row, 48, fixed(hw));
        word(row, 64, fixed(hh));
        return row;
    }

    /** Reads a header word back (-1 when a pixel is not a bit colour); the shader does the same. */
    public static int read(byte[] tile, int start) {
        int value = 0;
        for (int i = 0; i < 16; i++) {
            byte b = tile[start + i];
            if (b != BIT_ONE && b != BIT_ZERO) return -1;
            value = value << 1 | (b == BIT_ONE ? 1 : 0);
        }
        return value;
    }

    static int fixed(double value) {
        long v = Math.round(value * FIXED);
        return (int) Math.max(0, Math.min(0xFFFF, v));
    }

    private static void word(byte[] row, int start, int value) {
        for (int i = 0; i < 16; i++) row[start + i] = ((value >> (15 - i)) & 1) == 1 ? BIT_ONE : BIT_ZERO;
    }

    /**
     * One tile ready to send: the header in row 0, canvas rows {@code ty*127 .. ty*127+126} of columns
     * {@code tx*128 .. tx*128+127} below it. {@code canvas} is {@code canvasWidth} pixels wide.
     */
    public static byte[] tile(byte[] canvas, int canvasWidth, int tx, int ty, byte[] header) {
        byte[] out = new byte[SIZE * SIZE];
        System.arraycopy(header, 0, out, 0, SIZE);
        for (int r = 0; r < VISIBLE_ROWS; r++)
            System.arraycopy(canvas, (ty * VISIBLE_ROWS + r) * canvasWidth + tx * SIZE, out, (r + 1) * SIZE, SIZE);
        return out;
    }

    /** Where the client-only frames hang and how safely the eye sits inside their culling box (blocks, &gt; 0). */
    public record Placement(int x, int y, int z, BlockFace facing, double margin) { }

    /**
     * Frames are never seen at their real place (the shader moves the map), but the client only draws entities whose
     * culling box (frame box inflated by {@link #CULL_INFLATE}) touches the view. Putting the frame so that the eye lies
     * inside that box keeps it drawn wherever the player looks. Picks the horizontal axis with the larger margin.
     */
    public static Placement placement(double eyeX, double eyeY, double eyeZ) {
        int bx = floor(eyeX), by = floor(eyeY), bz = floor(eyeZ);
        double fx = eyeX - bx, fz = eyeZ - bz;
        double mx = margin(fx), mz = margin(fz);
        if (mx >= mz) return new Placement(bx, by, bz, fx < 0.5 ? BlockFace.EAST : BlockFace.WEST, mx);
        return new Placement(bx, by, bz, fz < 0.5 ? BlockFace.SOUTH : BlockFace.NORTH, mz);
    }

    /** Distance from the eye (block fraction {@code f}) to the near side of the inflated box along the frame axis. */
    static double margin(double f) {
        double centre = f < 0.5 ? 0.5 - FRAME_OFFSET : 0.5 + FRAME_OFFSET; // facing +axis hangs at the low side
        return FRAME_HALF_DEPTH + CULL_INFLATE - Math.abs(f - centre);
    }

    private static int floor(double v) { return (int) Math.floor(v); }
}
