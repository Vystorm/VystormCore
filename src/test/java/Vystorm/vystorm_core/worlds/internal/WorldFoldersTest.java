package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.WorldFolders.Decision.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review I3: nie einen fremden Ordner löschen; Löschfehler melden. */
class WorldFoldersTest {
    @TempDir Path dir;

    @Test
    void givenDeleteIoFailure_whenWiped_thenFailureReportedAndFileRetained() throws Exception {
        Path region = Files.createDirectories(dir.resolve("w/region"));
        Path file = region.resolve("r.0.0.mca");
        try (var files = org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(file)).thenThrow(new java.nio.file.AccessDeniedException(file.toString()));
            Files.writeString(file, "x");
            var failures = WorldFolders.wipe(dir.resolve("w"));
            assertTrue(failures.stream().anyMatch(message -> message.startsWith(file.toString() + ":")));
            assertEquals("x", Files.readString(file));
        }
    }

    @Test
    void givenMarkerAndHash_whenDecided_thenLoadWipeOrRefuse() {
        var own = new WorldFolders.Marker("p:w", "P", "h1", false);
        assertEquals(LOAD, WorldFolders.decide(false, null, "p:w", "h1"), "new folder");
        assertEquals(REFUSE_FOREIGN, WorldFolders.decide(true, null, "p:w", "h1"), "chunk data but no marker");
        assertEquals(LOAD, WorldFolders.decide(true, own, "p:w", "h1"));
        assertEquals(WIPE_AND_LOAD, WorldFolders.decide(true, own, "p:w", "h2"));
        assertEquals(REFUSE_FOREIGN, WorldFolders.decide(true, new WorldFolders.Marker("other:w", "O", "h1", false), "p:w", "h1"));
    }

    @Test
    void givenMarker_whenWrittenAndRead_thenSameAndLegacyFileOnlyAsFallback() throws Exception {
        Path folder = dir.resolve("w");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("vystorm-layout.txt"), "legacyhash\n");
        assertEquals(new WorldFolders.Marker("p:w", "", "legacyhash", true), WorldFolders.readMarker(folder, "p:w", "vystorm-layout.txt").orElseThrow());
        assertTrue(WorldFolders.readMarker(folder, "p:w", null).isEmpty(), "legacy file only counts when the spec names it");
        WorldFolders.writeMarker(folder, new WorldFolders.Marker("p:w", "P", "h1", false));
        assertEquals(new WorldFolders.Marker("p:w", "P", "h1", false), WorldFolders.readMarker(folder, "p:w", "vystorm-layout.txt").orElseThrow());
    }

    @Test
    void givenUnreadableMarker_whenRead_thenTreatedAsForeign() throws Exception {
        Path folder = dir.resolve("w");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(WorldFolders.MARKER), "id: [broken");
        var marker = WorldFolders.readMarker(folder, "p:w", null).orElseThrow();
        assertEquals(REFUSE_FOREIGN, WorldFolders.decide(true, marker, "p:w", "h"));
    }

    @Test
    void givenWorldFolder_whenWiped_thenOnlyChunkEntityAndPoiDataGone() throws Exception {
        Path folder = dir.resolve("w");
        for (String sub : new String[]{"region", "entities", "poi", "data"}) {
            Files.createDirectories(folder.resolve(sub));
            Files.writeString(folder.resolve(sub).resolve("r.0.0.mca"), "x");
        }
        Files.writeString(folder.resolve("level.dat"), "x");
        assertTrue(WorldFolders.hasData(folder));
        assertEquals(java.util.List.of(), WorldFolders.wipe(folder));
        assertFalse(WorldFolders.hasData(folder));
        assertTrue(Files.exists(folder.resolve("data/r.0.0.mca")));
        assertTrue(Files.exists(folder.resolve("level.dat")));
    }

    @Test
    void givenUndeletableFile_whenWiped_thenFailureReported() throws Exception {
        // Root ignores directory permissions, so the file cannot be made undeletable there.
        assumeFalse("root".equals(System.getProperty("user.name")));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions unavailable");
        Path region = dir.resolve("w/region");
        Files.createDirectories(region);
        Files.writeString(region.resolve("r.0.0.mca"), "x");
        Files.setPosixFilePermissions(region, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertFalse(WorldFolders.wipe(dir.resolve("w")).isEmpty());
        } finally {
            Files.setPosixFilePermissions(region, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void givenMalformedMarkerAndLegacyHash_whenRead_thenNeverAdoptedAsOwned() throws Exception {
        Path folder = dir.resolve("foreign");Files.createDirectories(folder);
        Files.writeString(folder.resolve("vystorm-layout.txt"), "old");
        Files.writeString(folder.resolve(WorldFolders.MARKER), "layout-hash: old\n");
        var marker = WorldFolders.readMarker(folder, "p:w", "vystorm-layout.txt").orElseThrow();
        assertEquals(REFUSE_FOREIGN, WorldFolders.decide(true, marker, "p:w", "new"));
        Files.delete(folder.resolve(WorldFolders.MARKER));Files.createDirectory(folder.resolve(WorldFolders.MARKER));
        marker = WorldFolders.readMarker(folder, "p:w", "vystorm-layout.txt").orElseThrow();
        assertEquals(REFUSE_FOREIGN, WorldFolders.decide(true, marker, "p:w", "new"));
    }
}
