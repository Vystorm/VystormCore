package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

class ScreenMapCodecTest {
    private static String shader() {
        return new String(ScreenShader.packFiles().get("assets/minecraft/shaders/core/text.vsh"), StandardCharsets.UTF_8);
    }

    @Test void givenHeader_whenRead_thenWordsRoundTrip() {
        byte[] row = ScreenMapCodec.header(-0.3125, 0.25, 0.175, 0.17);
        assertEquals(ScreenMapCodec.SIZE, row.length);
        assertEquals(ScreenMapCodec.SIGNATURE, ScreenMapCodec.read(row, 0));
        assertEquals(-0.3125, ScreenMapCodec.read(row, 16) / ScreenMapCodec.FIXED - ScreenMapCodec.OFFSET, 1e-3);
        assertEquals(0.25, ScreenMapCodec.read(row, 32) / ScreenMapCodec.FIXED - ScreenMapCodec.OFFSET, 1e-3);
        assertEquals(0.175, ScreenMapCodec.read(row, 48) / ScreenMapCodec.FIXED, 1e-3);
        assertEquals(0.17, ScreenMapCodec.read(row, 64) / ScreenMapCodec.FIXED, 1e-3);
    }

    @Test void givenHeader_thenOnlyBitColoursUsed() {
        for (byte b : ScreenMapCodec.header(1, -1, 0.5, 0.5)) assertTrue(b == ScreenMapCodec.BIT_ONE || b == ScreenMapCodec.BIT_ZERO);
    }

    @Test void givenBitColours_thenTheyAreSnowAndBlackAtFullBrightness() {
        // MapColor ids: SNOW = 8 (0xFFFFFF), COLOR_BLACK = 29 (0x191919); shade 2 = modifier 255, so RGB is exact.
        assertEquals(8 * 4 + 2, ScreenMapCodec.BIT_ONE);
        assertEquals(29 * 4 + 2, ScreenMapCodec.BIT_ZERO);
        assertTrue(shader().contains("ivec3(255, 255, 255)"));
        assertTrue(shader().contains("ivec3(25, 25, 25)"));
    }

    @Test void givenOutOfRangeValues_whenEncoded_thenClamped() {
        byte[] row = ScreenMapCodec.header(-100, 100, -1, 50);
        assertEquals(0, ScreenMapCodec.read(row, 16));
        assertEquals(0xFFFF, ScreenMapCodec.read(row, 32));
        assertEquals(0, ScreenMapCodec.read(row, 48));
        assertEquals(0xFFFF, ScreenMapCodec.read(row, 64));
    }

    @Test void givenShader_thenUsesSameSignatureAndFixedPoint() {
        String s = shader();
        assertTrue(s.contains("vystormWord(0) == " + ScreenMapCodec.SIGNATURE), "signature must match the Java constant");
        assertTrue(s.contains("/ " + (int) ScreenMapCodec.FIXED + ".0 - " + (int) ScreenMapCodec.OFFSET + ".0"), "centre decoding");
        assertTrue(s.contains("textureSize(Sampler0, 0) == ivec2(128, 128)"));
        assertTrue(s.contains("(1.0 + UV0.y * 127.0) / 128.0"), "header row hidden");
    }

    @Test void givenCanvas_whenTile_thenHeaderOnTopAndRowsShiftedByOne() {
        int width = 256, height = 254;
        byte[] canvas = new byte[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) canvas[y * width + x] = (byte) ((x + 3 * y) % 100);
        byte[] header = ScreenMapCodec.header(0, 0, 0.1, 0.1);
        byte[] tile = ScreenMapCodec.tile(canvas, width, 1, 1, header);
        assertEquals(128 * 128, tile.length);
        assertEquals(ScreenMapCodec.SIGNATURE, ScreenMapCodec.read(tile, 0));
        // tile (1,1), visible row 0 = canvas row 127, column 128
        assertEquals(canvas[127 * width + 128], tile[128]);
        assertEquals(canvas[(127 + 126) * width + 255], tile[127 * 128 + 127]);
    }

    @Test void givenEyeInLowHalfOfBlock_whenPlaced_thenFrameHangsOnLowSideFacingPositive() {
        ScreenMapCodec.Placement p = ScreenMapCodec.placement(10.2, 65.3, 10.5);
        assertEquals(BlockFace.EAST, p.facing());
        assertEquals(10, p.x());
        assertEquals(65, p.y());
        assertEquals(10, p.z());
        ScreenMapCodec.Placement q = ScreenMapCodec.placement(-3.5, 70, -7.9);
        assertEquals(BlockFace.SOUTH, q.facing(), "x sits mid-block (bad margin), z near the low side");
        assertEquals(-4, q.x());
        assertEquals(-8, q.z());
    }

    @Test void givenAnyEye_whenPlaced_thenEyeInsideInflatedCullingBoxWithMargin() {
        for (double fx = 0; fx < 1; fx += 0.037) for (double fz = 0; fz < 1; fz += 0.041) for (double fy = 0; fy < 1; fy += 0.13) {
            double ex = 100 + fx, ey = 64 + fy, ez = -50 + fz;
            ScreenMapCodec.Placement p = ScreenMapCodec.placement(ex, ey, ez);
            // 26.2 ItemFrame box with a map: 1 x 1 x 1/16, centre = block centre - facing * 0.46875; culling inflates by 0.5
            double cx = p.x() + 0.5 - p.facing().getModX() * 0.46875, cy = p.y() + 0.5, cz = p.z() + 0.5 - p.facing().getModZ() * 0.46875;
            double hx = (p.facing().getModX() != 0 ? 0.03125 : 0.5) + 0.5, hy = 1.0, hz = (p.facing().getModZ() != 0 ? 0.03125 : 0.5) + 0.5;
            double inside = Math.min(hx - Math.abs(ex - cx), Math.min(hy - Math.abs(ey - cy), hz - Math.abs(ez - cz)));
            assertTrue(inside >= 0.06, "eye must stay inside the culling box at " + ex + "," + ey + "," + ez + " (" + inside + ")");
            assertTrue(inside <= p.margin() + 1e-9, "reported margin is the frame axis, the tightest one");
        }
    }
}
