package Vystorm.vystorm_core.licensing.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import test.module.fixture.RamModuleFixture;

class RamModuleClassLoaderTest {
    private static final String PREFIX = "test.module.fixture.";
    private static final String FIXTURE_PATH = "test/module/fixture/RamModuleFixture.class";

    @Test
    void loadsModuleClassesAndResourcesWithoutParentFallback() throws Exception {
        byte[] classBytes = classBytes();
        byte[] jar = jar(entry("test/", new byte[0]), entry("test/module/", new byte[0]), entry("test/module/fixture/", new byte[0]),
                entry("META-INF/", new byte[0]), entry(FIXTURE_PATH, classBytes),
                entry("test/module/fixture/data.txt", "module-data".getBytes(StandardCharsets.UTF_8)));
        try (RamModuleClassLoader loader = new RamModuleClassLoader(jar, getClass().getClassLoader(), PREFIX)) {
            Class<?> loaded = loader.loadClass(RamModuleFixture.class.getName());
            assertNotSame(RamModuleFixture.class, loaded);
            assertEquals(loader, loaded.getClassLoader());
            Method marker = loaded.getMethod("marker");
            assertEquals("from-module", marker.invoke(null));
            assertEquals("module-data", new String(loader.getResourceAsStream("test/module/fixture/data.txt").readAllBytes(), StandardCharsets.UTF_8));
            assertNull(loader.getResourceAsStream("test/module/fixture/parent-only.txt"));
            assertNull(loader.getResourceAsStream("test/module/fixture/NotPackaged.class"));
            assertTrue(loader.getResource("test/module/fixture/data.txt").toString().startsWith("rammodule:/"));
            assertTrue(loader.getResource("java/lang/String.class") != null);
        }
    }

    @Test
    void refusesParentFallbackForMissingModuleClassAndResource() throws Exception {
        try (RamModuleClassLoader loader = new RamModuleClassLoader(jar(), getClass().getClassLoader(), PREFIX)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(RamModuleFixture.class.getName()));
            assertNull(loader.getResourceAsStream("test/module/fixture/RamModuleFixture.class"));
        }
    }

    @Test
    void rejectsNonZipGarbageButAcceptsAValidEmptyZip() throws Exception {
        assertThrows(IOException.class, () -> new RamModuleClassLoader(new byte[] {1, 2, 3}, getClass().getClassLoader(), PREFIX));
        try (RamModuleClassLoader ignored = new RamModuleClassLoader(jar(), getClass().getClassLoader(), PREFIX)) {
            assertNull(ignored.getResourceAsStream(FIXTURE_PATH));
        }
    }

    @Test
    void closeClearsModuleResourcesAndPreventsNewDefinitions() throws Exception {
        RamModuleClassLoader loader = new RamModuleClassLoader(jar(entry(FIXTURE_PATH, classBytes())), getClass().getClassLoader(), PREFIX);
        loader.close();
        assertNull(loader.getResourceAsStream(FIXTURE_PATH));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass(RamModuleFixture.class.getName()));
        loader.close();
    }

    @Test
    void rejectsBadPackagePrefixesIncludingProtectedAncestors() {
        for (String prefix : List.of("test.module", "test..module.", "java.", "java.lang.", "org.", "org.bukkit.",
                "io.papermc.paper.", "Vystorm.", "Vystorm.vystorm_core.internal.")) {
            assertThrows(IllegalArgumentException.class, () -> new RamModuleClassLoader(jar(), getClass().getClassLoader(), prefix), prefix);
        }
    }

    @Test
    void rejectsUnsafePathsDuplicateEntriesAndNonModuleClasses() throws Exception {
        for (String path : List.of("../test/module/fixture/x.txt", "/test/module/fixture/x.txt", "test\\module\\fixture\\x.txt",
                "test/module/fixture/../x.txt", "test/module/other/Outside.class")) {
            assertThrows(IOException.class, () -> new RamModuleClassLoader(jar(entry(path, new byte[] {1})), getClass().getClassLoader(), PREFIX), path);
        }
        assertThrows(IOException.class, () -> new RamModuleClassLoader(duplicateJar(FIXTURE_PATH), getClass().getClassLoader(), PREFIX));
    }

    @Test
    void rejectsInvalidClassBytesMultiReleaseAndOtherMetadata() throws Exception {
        try (RamModuleClassLoader loader = new RamModuleClassLoader(jar(entry(FIXTURE_PATH, new byte[] {0, 1, 2})), getClass().getClassLoader(), PREFIX)) {
            assertThrows(LinkageError.class, () -> loader.loadClass(RamModuleFixture.class.getName()));
        }
        for (String path : List.of("META-INF/versions/9/test/module/fixture/X.class", "META-INF/services/example.Service")) {
            assertThrows(IOException.class, () -> new RamModuleClassLoader(jar(entry(path, new byte[] {1})), getClass().getClassLoader(), PREFIX), path);
        }
    }

    @Test
    void enforcesArchiveAndExpandedSizeLimits() throws Exception {
        assertThrows(IOException.class, () -> new RamModuleClassLoader(new byte[4 * 1024 * 1024 + 1], getClass().getClassLoader(), PREFIX));
        byte[] expanded = new byte[16 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> new RamModuleClassLoader(jar(entry("test/module/fixture/large.bin", expanded)), getClass().getClassLoader(), PREFIX));
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < 4097; i++) many.add(entry("test/module/fixture/r" + i, new byte[0]));
        assertThrows(IOException.class, () -> new RamModuleClassLoader(jar(many.toArray(Entry[]::new)), getClass().getClassLoader(), PREFIX));
    }

    @Test
    void ignoresManifestButRejectsNativeEntries() throws Exception {
        try (RamModuleClassLoader loader = new RamModuleClassLoader(jar(entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8)),
                entry("test/module/fixture/data.txt", new byte[] {1})), getClass().getClassLoader(), PREFIX)) {
            assertArrayEquals(new byte[] {1}, loader.getResourceAsStream("test/module/fixture/data.txt").readAllBytes());
        }
        assertThrows(IOException.class, () -> new RamModuleClassLoader(jar(entry("test/module/fixture/libnative.so", new byte[] {1})), getClass().getClassLoader(), PREFIX));
    }

    private static byte[] classBytes() throws IOException {
        try (InputStream in = RamModuleFixture.class.getResourceAsStream("RamModuleFixture.class")) {
            if (in == null) throw new IOException("Fixture class bytes missing");
            return in.readAllBytes();
        }
    }

    private static Entry entry(String name, byte[] bytes) { return new Entry(name, bytes); }

    private static byte[] jar(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.bytes());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] duplicateJar(String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        for (int i = 0; i < 2; i++) {
            byte[] data = new byte[] {(byte) i};
            CRC32 crc = new CRC32();
            crc.update(data);
            leInt(output, 0x04034b50); leShort(output, 20); leShort(output, 0); leShort(output, 0);
            leInt(output, 0); leInt(output, (int) crc.getValue()); leInt(output, data.length); leInt(output, data.length);
            leShort(output, filename.length); leShort(output, 0); output.write(filename); output.write(data);
            leInt(central, 0x02014b50); leShort(central, 20); leShort(central, 20); leShort(central, 0);
            leShort(central, 0); leInt(central, 0); leInt(central, (int) crc.getValue());
            leInt(central, data.length); leInt(central, data.length); leShort(central, filename.length);
            leShort(central, 0); leShort(central, 0); leShort(central, 0); leShort(central, 0); leInt(central, 0);
            leInt(central, offset); central.write(filename);
            offset += 30 + filename.length + data.length;
        }
        byte[] cdir = central.toByteArray();
        output.write(cdir);
        leInt(output, 0x06054b50); leShort(output, 0); leShort(output, 0); leShort(output, 2); leShort(output, 2);
        leInt(output, cdir.length); leInt(output, offset); leShort(output, 0);
        return output.toByteArray();
    }

    private static void leShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void leInt(ByteArrayOutputStream out, int value) {
        leShort(out, value);
        leShort(out, value >>> 16);
    }

    private record Entry(String name, byte[] bytes) {}
}
