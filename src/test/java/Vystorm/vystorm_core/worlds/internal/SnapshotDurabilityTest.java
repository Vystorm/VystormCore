package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotDurabilityTest {
    @TempDir Path dir;

    @Test
    void givenSnapshot_whenSaved_thenDirectoryForcedAfterRenameBeforeReturning() throws Exception {
        UUID id = UUID.randomUUID();
        List<Path> forced = new ArrayList<>();
        VisitSnapshotStore store = new VisitSnapshotStore(dir, m -> {}, directory -> {
            assertTrue(Files.exists(dir.resolve(id + ".yml")));
            assertFalse(Files.exists(dir.resolve(id + ".yml.tmp")));
            forced.add(directory);
        });
        store.save(VisitSnapshotStoreTest.sample(id));
        assertEquals(List.of(dir), forced);
    }
}
