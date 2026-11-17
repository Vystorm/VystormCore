package Vystorm.vystorm_core.screen.internal;

import Vystorm.vystorm_core.pack.ResourcePacks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.plugin.Plugin;

/**
 * Pack module {@code vystorm_screens} of Vystorm Screens (since 0.57.0): the 26.2 {@code text.vsh} with one extra branch
 * that mirrors glyphs in {@link #MARKER_RGB} through the view axis (the PINNED mouse pointer), plus the pointer glyph
 * {@link #POINTER} in font {@link #FONT}. The texture is stored rotated by 180° because the mirror turns it back.
 * Since 0.59.0 a second branch pins client maps that carry the {@link ScreenMapCodec} header onto the screen
 * (map widgets of PINNED screens); the pointer is pulled close to the camera so a depth-tested pointer stays above them.
 */
public final class ScreenShader {
    /** Text colour that marks the pointer glyph; the shader compares exactly against (254, 1, 254). */
    public static final int MARKER_RGB = 0xFEFEFE;
    public static final String POINTER = "\uE000";
    /** A solid 8x8 px block (white, tinted by the text colour), stretched for panel backgrounds and the backdrop. */
    public static final String SOLID = "\uE001";
    public static final String FONT = "vystorm_screens:screen";
    public static final String MODULE = "vystorm_screens";
    /** Own namespace only; the {@code assets/minecraft/} shader override is allowed for every module. */
    public static final List<String> NAMESPACES = List.of(MODULE);

    private ScreenShader() { }

    public static Map<String, byte[]> packFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("assets/minecraft/shaders/core/text.vsh", resource("screens/text.vsh"));
        files.put("assets/vystorm_screens/textures/pointer.png", resource("screens/pointer.png"));
        files.put("assets/vystorm_screens/textures/solid.png", resource("screens/solid.png"));
        String font = "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"vystorm_screens:pointer.png\",\"ascent\":8,\"height\":16,\"chars\":[\"\\uE000\"]},"
                + "{\"type\":\"bitmap\",\"file\":\"vystorm_screens:solid.png\",\"ascent\":7,\"height\":8,\"chars\":[\"\\uE001\"]}]}";
        files.put("assets/vystorm_screens/font/screen.json", font.getBytes(StandardCharsets.UTF_8));
        // HUD window (0.59.0): panel slices for 2 and 3 rows, a bar segment, and negative/positive spaces.
        files.put("assets/vystorm_screens/textures/hud_panel2.png", png(8, HudGlyphs.PANEL2_HEIGHT));
        files.put("assets/vystorm_screens/textures/hud_panel3.png", png(8, HudGlyphs.PANEL3_HEIGHT));
        files.put("assets/vystorm_screens/textures/hud_bar.png", png(HudGlyphs.SEGMENT_WIDTH, 5));
        files.put("assets/vystorm_screens/font/hud.json", HudGlyphs.fontJson().getBytes(StandardCharsets.UTF_8));
        // WHITE boss bars draw no bar: the HUD window uses them as rows (their titles only).
        files.put("assets/minecraft/textures/gui/sprites/boss_bar/white_background.png", transparentPng());
        files.put("assets/minecraft/textures/gui/sprites/boss_bar/white_progress.png", transparentPng());
        return files;
    }

    /** A solid white PNG (tinted by the text colour). */
    static byte[] png(int width, int height) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, 0xFFFFFFFF);
        return encode(image);
    }

    static byte[] transparentPng() {
        return encode(new java.awt.image.BufferedImage(182, 5, java.awt.image.BufferedImage.TYPE_INT_ARGB));
    }

    private static byte[] encode(java.awt.image.BufferedImage image) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static void register(Plugin core) {
        ResourcePacks.register(core, MODULE, NAMESPACES, packFiles(), 0);
    }

    private static byte[] resource(String path) {
        try (InputStream in = ScreenShader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing Core resource " + path);
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
