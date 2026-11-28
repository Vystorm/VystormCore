package Vystorm.vystorm_core.survey.internal;

import Vystorm.vystorm_core.survey.SurveyRule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.Inflater;

/**
 * Chunks exported from real region files by the study's {@code export-fixtures.js} (classes of the chunk and its
 * eight neighbours, the kinds and room labels the Node reference computed).
 */
final class SurveyFixtures {
    static final String[] NAMES = {
            "ow-43.241", "ow-35.246", "ow--176.341",
            "nether--4.-4", "nether--4.-2", "nether--2.-2",
            "end-236.8", "end-229.5", "end-234.7"};
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

    record Fixture(String name, String dim, int height, int minY, int reach, int hall, boolean voidBelow, int chunkX,
                   int chunkZ, int rooms, byte[] grid, byte[] kinds, int[] labels) {
        /** The Node rule; Node has no door rule, so doors never stop reach here. */
        SurveyRule rule() { return new SurveyRule(reach, hall, voidBelow, false); }

        SurveyGrid fullGrid() { return new SurveyGrid(16, minY, height, grid.clone()); }

        /** The nine chunks of the grid as classes ({@code [(dz + 1) * 3 + dx + 1]}). */
        ChunkClasses[] chunks() {
            ChunkClasses[] out = new ChunkClasses[9];
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    byte[] c = new byte[256 * height];
                    for (int y = 0; y < height; y++)
                        for (int z = 0; z < 16; z++)
                            System.arraycopy(grid, (y * 48 + (dz + 1) * 16 + z) * 48 + (dx + 1) * 16, c, (y * 16 + z) * 16, 16);
                    out[(dz + 1) * 3 + dx + 1] = ChunkClasses.of(minY, height, c);
                }
            return out;
        }
    }

    private SurveyFixtures() {}

    static List<Fixture> all() {
        List<Fixture> out = new ArrayList<>();
        for (String name : NAMES) out.add(load(name));
        return out;
    }

    static Fixture load(String name) {
        try (InputStream in = SurveyFixtures.class.getResourceAsStream("/survey/" + name + ".vsfx")) {
            if (in == null) throw new IllegalStateException("fixture missing: " + name);
            return parse(name, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fixtures of a folder (the benchmark set outside the repository). */
    static List<java.util.function.Supplier<Fixture>> folder(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<java.util.function.Supplier<Fixture>> out = new ArrayList<>();
            for (Path p : files.filter(f -> f.toString().endsWith(".vsfx")).sorted().toList())
                out.add(() -> {
                    try { return parse(p.getFileName().toString(), Files.readAllBytes(p)); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Fixture parse(String name, byte[] packed) {
        byte[] raw = inflate(packed);
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.get() != 'V' || b.get() != 'S' || b.get() != 'F' || b.get() != 'X') throw new IllegalStateException("not a fixture");
        int version = b.get();
        if (version != 1) throw new IllegalStateException("fixture version " + version);
        int dim = b.get();
        int height = b.getShort(6) & 0xFFFF, minY = b.getShort(8);
        int reach = raw[10], hall = raw[11];
        boolean voidBelow = raw[12] != 0;
        int cx = b.getInt(16), cz = b.getInt(20), rooms = b.getInt(24);
        int g = 48 * 48 * height, k = 256 * height;
        byte[] grid = new byte[g];
        System.arraycopy(raw, 32, grid, 0, g);
        byte[] kinds = new byte[k];
        System.arraycopy(raw, 32 + g, kinds, 0, k);
        int[] labels = new int[k];
        for (int i = 0; i < k; i++) labels[i] = b.getShort(32 + g + k + 2 * i) & 0xFFFF;
        String d = dim == 0 ? "ow" : dim == 1 ? "nether" : "end";
        return new Fixture(name, d, height, minY, reach, hall, voidBelow, cx, cz, rooms, grid, kinds, labels);
    }

    private static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 40);
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) throw new IllegalStateException("truncated fixture");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalStateException(e);
        } finally {
            inf.end();
        }
    }
}
