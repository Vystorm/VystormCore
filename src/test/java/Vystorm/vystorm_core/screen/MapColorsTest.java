package Vystorm.vystorm_core.screen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MapColorsTest {
    private final MapColors table = MapColors.table();

    /** {index -> rgb} from the checked-in list (gallery-paint palette.json: Spigot 26.2 MapPalette, all opaque colours). */
    private static int[] checkedIn() throws Exception {
        String json;
        try (var in = MapColorsTest.class.getClassLoader().getResourceAsStream("screen/map-palette.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int[] rgb = new int[256];
        java.util.Arrays.fill(rgb, -1);
        Matcher m = Pattern.compile("\\[(\\d+),(\\d+),(\\d+),(\\d+)]").matcher(json.replace(" ", ""));
        int count = 0;
        while (m.find()) {
            rgb[Integer.parseInt(m.group(1))] = Integer.parseInt(m.group(2)) << 16 | Integer.parseInt(m.group(3)) << 8 | Integer.parseInt(m.group(4));
            count++;
        }
        assertEquals(MapColors.VISIBLE, count, "entries in map-palette.json");
        return rgb;
    }

    @Test void givenCheckedInPalette_whenCompared_thenEveryIndexHasTheSameRgb() throws Exception {
        int[] expected = checkedIn();
        for (int i = 0; i < 256; i++) assertEquals(expected[i], table.rgb(i), "index " + i);
        for (int i = 0; i < 4; i++) assertFalse(table.visible((byte) i));
        assertTrue(table.visible((byte) 4));
        assertTrue(table.visible((byte) 247));
        assertFalse(table.visible((byte) 248));
        assertEquals(table.rgb(200), table.rgb((byte) 200), "signed and unsigned forms agree");
    }

    @Test void givenEveryVisibleColour_whenItsRgbIsMatched_thenTheSameByteComesBack() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 4; i < 248; i++) {
            int rgb = table.rgb(i);
            assertTrue(seen.add(rgb), "RGB values are distinct");
            assertEquals((byte) i, table.nearest(rgb), "round trip of " + i);
        }
    }

    @Test void givenPickerOrder_whenRead_thenEveryVisibleByteOnceGreysFirstShadesBrightToDark() {
        byte[] order = table.pickerOrder();
        assertEquals(244, order.length);
        Set<Byte> all = new HashSet<>();
        for (byte b : order) { assertTrue(table.visible(b)); all.add(b); }
        assertEquals(244, all.size());
        // white (base 8, byte 34) is the brightest grey: the very first row, shades 2, 1, 0, 3
        assertArrayEquals(new byte[] { 34, 33, 32, 35 }, java.util.Arrays.copyOf(order, 4));
        for (int row = 0; row < 61; row++) {
            int base = table.base(order[row * 4]);
            for (int k = 0; k < 4; k++) assertEquals(base, table.base(order[row * 4 + k]), "one base colour per row");
            assertEquals(2, table.shade(order[row * 4]));
            assertEquals(3, table.shade(order[row * 4 + 3]));
        }
        assertArrayEquals(order, table.pickerOrder(), "stable");
        order[0] = 0;
        assertEquals(34, table.pickerOrder()[0], "a copy each time");
        assertEquals(244, table.pickerList().size());
    }

    @Test void givenManager_whenAsked_thenTheTable() {
        assertSame(MapColors.table(), ScreensInterface.DEFAULT.mapColors());
    }

    // ---- the same table on every platform -----------------------------------------------------------------------

    /** RGB 0..255 in steps of 15, plus a few odd values: 5,000 colours. */
    private static List<int[]> samples() {
        List<int[]> out = new ArrayList<>();
        int[] steps = { 0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 7 };
        for (int r : steps) for (int g : steps) for (int b : steps) out.add(new int[] { r, g, b });
        out.add(new int[] { 173, 216, 230 });   // light sky blue: a lavender (the study's note)
        return out;
    }

    /** Compares getColor and matchColor of the MapPalette in {@code classpath} with the table. */
    private void sameAs(String classpath, String platform) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String path : classpath.split(File.pathSeparator)) if (!path.isBlank()) urls.add(Path.of(path).toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> palette = Class.forName("org.bukkit.map.MapPalette", true, loader);
            var colors = palette.getDeclaredField("colors");
            colors.setAccessible(true);
            assertEquals(MapColors.INDICES, ((Object[]) colors.get(null)).length, platform + ": indices");
            Method getColor = palette.getMethod("getColor", byte.class);
            for (int i = 0; i < MapColors.INDICES; i++) {
                java.awt.Color c = (java.awt.Color) getColor.invoke(null, (byte) i);
                if (i < 4) assertTrue(c.getAlpha() < 255, platform + ": " + i + " is transparent");
                else {
                    assertEquals(255, c.getAlpha(), platform + ": " + i);
                    assertEquals(table.rgb(i), c.getRGB() & 0xFFFFFF, platform + ": rgb of " + i);
                }
            }
            Method match = palette.getMethod("matchColor", int.class, int.class, int.class);
            for (int[] s : samples())
                assertEquals((byte) match.invoke(null, s[0], s[1], s[2]), table.nearest(s[0], s[1], s[2]), platform + ": nearest " + java.util.Arrays.toString(s));
        }
    }

    @Test void givenPaper26Api_whenCompared_thenSameColoursAndSameNearest() throws Exception {
        sameAs(test.TestClasspath.property("vystorm.test.classpath"), "Paper 1.21.11 API (test classpath)");
    }

    @Test void givenSpigot262Api_whenCompared_thenSameColoursAndSameNearest() throws Exception {
        sameAs(test.TestClasspath.property("vystorm.test.spigotClasspath"), "Spigot 26.2");
    }

    @Test void givenPaper1206Api_whenCompared_thenSameColoursAndSameNearest() throws Exception {
        String baseline = test.TestClasspath.property("vystorm.test.baselineClasspath");
        StringBuilder paper = new StringBuilder();
        for (String path : baseline.split(File.pathSeparator)) if (path.contains("paper-api-1.20.6") || path.contains("guava") || path.contains("annotations")) paper.append(path).append(File.pathSeparator);
        assertTrue(paper.toString().contains("paper-api-1.20.6"), "Paper 1.20.6 API jar on the baseline classpath");
        sameAs(paper.toString(), "Paper 1.20.6");
    }
}
