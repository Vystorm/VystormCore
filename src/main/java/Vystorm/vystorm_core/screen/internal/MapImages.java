package Vystorm.vystorm_core.screen.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Images and GIF animations for screen map widgets (since 0.59.0): decodes GIF frames (offsets, delays, disposal),
 * scales them into a map canvas keeping the aspect ratio, and converts them to Minecraft map colours with
 * Floyd–Steinberg dithering. Colour lookups go through a 32x32x32 table, so a 256x254 frame takes a few milliseconds.
 * Pure Java, no server needed; run it off the main thread for long animations.
 */
public final class MapImages {
    /** One frame: map colour bytes of the whole canvas and how long it is shown. */
    public record Frame(byte[] pixels, int delayMillis) {}

    private static volatile byte[] lookup;
    private static volatile int[] palette;   // RGB per map colour byte (index = unsigned byte), -1 = unusable

    private MapImages() {}

    /** RGB of every usable map colour byte (bytes 0..3 are transparent). */
    static int[] palette() {
        int[] p = palette;
        if (p != null) return p;
        p = new int[256];
        java.util.Arrays.fill(p, -1);
        // screen canvas: Core's own copy of the table (MapPalette.getColor is deprecated for removal; same values)
        Vystorm.vystorm_core.screen.MapColors table = Vystorm.vystorm_core.screen.MapColors.table();
        for (int i = 4; i < 256; i++) p[i] = table.rgb(i);
        palette = p;
        return p;
    }

    /** Nearest map colour byte for an RGB value. */
    static byte nearest(int rgb) {
        byte[] table = lookup;
        if (table == null) table = buildLookup();
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return table[(r >> 3) << 10 | (g >> 3) << 5 | (b >> 3)];
    }

    private static synchronized byte[] buildLookup() {
        if (lookup != null) return lookup;
        int[] p = palette();
        byte[] table = new byte[32 * 32 * 32];
        for (int r = 0; r < 32; r++) for (int g = 0; g < 32; g++) for (int b = 0; b < 32; b++) {
            int rr = r << 3 | 4, gg = g << 3 | 4, bb = b << 3 | 4;
            long best = Long.MAX_VALUE;
            int bestIndex = 4;
            for (int i = 4; i < 256; i++) {
                if (p[i] < 0) continue;
                int dr = (p[i] >> 16 & 0xFF) - rr, dg = (p[i] >> 8 & 0xFF) - gg, db = (p[i] & 0xFF) - bb;
                // weighted distance: the eye is most sensitive to green
                long d = 2L * dr * dr + 4L * dg * dg + 3L * db * db;
                if (d < best) { best = d; bestIndex = i; }
            }
            table[r << 10 | g << 5 | b] = (byte) bestIndex;
        }
        lookup = table;
        return table;
    }

    /**
     * The image fitted into a {@code width x height} canvas (aspect kept, centred, transparent around it) as map colour
     * bytes. Pixels with alpha below 128 stay transparent (byte 0).
     */
    public static byte[] toMap(BufferedImage image, int width, int height, boolean dither) {
        double scale = Math.min(width / (double) image.getWidth(), height / (double) image.getHeight());
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale)), h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int ox = (width - w) / 2, oy = (height - h) / 2;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1 ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        int[] p = palette();
        byte[] out = new byte[width * height];
        float[] er = new float[(w + 2) * 2], eg = new float[(w + 2) * 2], eb = new float[(w + 2) * 2];
        for (int y = 0; y < h; y++) {
            int cur = (y & 1) * (w + 2), next = ((y + 1) & 1) * (w + 2);
            java.util.Arrays.fill(er, next, next + w + 2, 0);
            java.util.Arrays.fill(eg, next, next + w + 2, 0);
            java.util.Arrays.fill(eb, next, next + w + 2, 0);
            for (int x = 0; x < w; x++) {
                int argb = scaled.getRGB(x, y);
                if ((argb >>> 24) < 128) continue;
                int r = clamp((argb >> 16 & 0xFF) + (dither ? Math.round(er[cur + x + 1]) : 0));
                int gr = clamp((argb >> 8 & 0xFF) + (dither ? Math.round(eg[cur + x + 1]) : 0));
                int b = clamp((argb & 0xFF) + (dither ? Math.round(eb[cur + x + 1]) : 0));
                byte index = nearest(r << 16 | gr << 8 | b);
                out[(oy + y) * width + ox + x] = index;
                if (!dither) continue;
                int chosen = p[index & 0xFF];
                float dr = r - (chosen >> 16 & 0xFF), dg = gr - (chosen >> 8 & 0xFF), db = b - (chosen & 0xFF);
                spread(er, eg, eb, cur + x + 2, dr, dg, db, 7 / 16f);
                spread(er, eg, eb, next + x, dr, dg, db, 3 / 16f);
                spread(er, eg, eb, next + x + 1, dr, dg, db, 5 / 16f);
                spread(er, eg, eb, next + x + 2, dr, dg, db, 1 / 16f);
            }
        }
        return out;
    }

    private static void spread(float[] r, float[] g, float[] b, int at, float dr, float dg, float db, float share) {
        r[at] += dr * share;
        g[at] += dg * share;
        b[at] += db * share;
    }

    private static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /** Width and height of the first image (for choosing a map grid). */
    public static int[] size(BufferedImage first) { return new int[] { first.getWidth(), first.getHeight() }; }

    /**
     * Decodes all GIF frames composed on the logical screen (frame offsets and disposal applied) as full images with
     * their delays (at least 50 ms, one server tick). At most {@code maxFrames}.
     */
    public static List<BufferedImage> gifFrames(InputStream in, int maxFrames, List<Integer> delays) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("no GIF reader");
        ImageReader reader = readers.next();
        List<BufferedImage> frames = new ArrayList<>();
        try (ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            reader.setInput(stream, false);
            int count = reader.getNumImages(true);
            int width = 0, height = 0;
            IIOMetadata streamMeta = reader.getStreamMetadata();
            if (streamMeta != null) {
                Node screen = child(streamMeta.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                if (screen != null) { width = attr(screen, "logicalScreenWidth", 0); height = attr(screen, "logicalScreenHeight", 0); }
            }
            BufferedImage canvas = null;
            for (int i = 0; i < count && frames.size() < maxFrames; i++) {
                BufferedImage frame = reader.read(i);
                Node tree = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                Node descriptor = child(tree, "ImageDescriptor"), control = child(tree, "GraphicControlExtension");
                int left = descriptor == null ? 0 : attr(descriptor, "imageLeftPosition", 0), top = descriptor == null ? 0 : attr(descriptor, "imageTopPosition", 0);
                if (canvas == null) {
                    canvas = new BufferedImage(Math.max(width, left + frame.getWidth()), Math.max(height, top + frame.getHeight()), BufferedImage.TYPE_INT_ARGB);
                }
                String disposal = control == null ? "none" : text(control, "disposalMethod", "none");
                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, left, top, null);
                g.dispose();
                frames.add(copy(canvas));
                delays.add(Math.max(50, (control == null ? 10 : attr(control, "delayTime", 10)) * 10));
                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(java.awt.AlphaComposite.Clear);
                    clear.fillRect(left, top, frame.getWidth(), frame.getHeight());
                    clear.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
        } finally {
            reader.dispose();
        }
        return frames;
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Node child(Node parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (name.equals(n.getNodeName())) return n;
        return null;
    }

    private static int attr(Node node, String name, int fallback) {
        NamedNodeMap attrs = node.getAttributes();
        Node a = attrs == null ? null : attrs.getNamedItem(name);
        if (a == null) return fallback;
        try { return Integer.parseInt(a.getNodeValue()); } catch (NumberFormatException bad) { return fallback; }
    }

    private static String text(Node node, String name, String fallback) {
        NamedNodeMap attrs = node.getAttributes();
        Node a = attrs == null ? null : attrs.getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }
}
