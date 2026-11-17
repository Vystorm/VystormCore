package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisitSnapshotStoreTest {
    @TempDir Path dir;

    static VisitSnapshot sample(UUID id) {
        return new VisitSnapshot(VisitSnapshot.FORMAT, id, "vystormskills:talentwelt", "vystorm_talentwelt", "VystormSkills", 1234L,
                "world", 10.5, 64, -3.5, 90f, 10f, "SURVIVAL", "aW52", "ZXh0cmE=", 4,
                List.of(new VisitSnapshot.Effect("minecraft:speed", 1200, 1, false, true, true)),
                17.5, 18, 3.5f, 1.25f, 40, 60, 150, 2.5f, true, false, 0.05f, 0.25f, true, false, null);
    }

    @Test
    void givenSnapshot_whenSavedAndLoaded_thenEveryFieldRoundTrips() throws Exception {
        VisitSnapshotStore store = new VisitSnapshotStore(dir);
        UUID id = UUID.randomUUID();
        store.save(sample(id));
        assertTrue(store.exists(id));
        assertEquals(sample(id), store.load(id).orElseThrow());
        assertFalse(Files.exists(dir.resolve(id + ".yml.tmp")), "temporary file renamed atomically");
        assertEquals(List.of(id), store.all());
        store.delete(id);
        assertFalse(store.exists(id));
    }

    @Test
    void givenIsolatedExperience_whenRoundTripAndReturnLocationChanged_thenXpPreserved() throws Exception {
        VisitSnapshotStore store = new VisitSnapshotStore(dir);
        UUID id = UUID.randomUUID();
        VisitSnapshot original = sample(id).withExperience(31, 1543, .42f).returnTo("other", 1, 2, 3, 4, 5);
        store.save(original);
        assertEquals(original, store.load(id).orElseThrow());
        assertEquals(31, original.experienceLevel());
    }

    @Test
    void givenSkills116Format1File_whenLoaded_thenDefaultsForNewFields() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yml"), """
                version: 1
                player: %s
                created-at: 99
                return: {world: world, x: 1.0, y: 70.0, z: 2.0, yaw: 0.0, pitch: 0.0}
                game-mode: CREATIVE
                items: abc
                held-slot: 2
                effects: []
                health: 12.0
                food: 15
                saturation: 2.0
                fire-ticks: 0
                fall-distance: 0.0
                allow-flight: false
                flying: false
                fly-speed: 0.1
                collidable: true
                invulnerable: false
                world-id: vystormskills:talentwelt
                world-name: vystorm_talentwelt
                owner: VystormSkills
                legacy-tool-key: vystormskills:tw_item
                """.formatted(id), StandardCharsets.UTF_8);
        VisitSnapshot s = new VisitSnapshotStore(dir).load(id).orElseThrow();
        assertEquals(1, s.format());
        assertEquals("CREATIVE", s.gameMode());
        assertEquals("", s.extraItems());
        assertEquals(-1, s.air(), "air not saved by format 1 → not restored");
        assertEquals(-1f, s.walkSpeed());
        assertEquals("vystormskills:tw_item", s.legacyToolKey());
    }

    @Test
    void givenBrokenFile_whenLoaded_thenSetAsideNeverDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yml"), "format: 2\nitems: [unclosed", StandardCharsets.UTF_8);
        VisitSnapshotStore store = new VisitSnapshotStore(dir);
        assertTrue(store.load(id).isEmpty());
        assertFalse(store.exists(id));
        try (var files = Files.list(dir)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith(id + ".yml.broken-"))); }
    }

    @Test
    void givenNewerFormat_whenLoaded_thenLeftUntouched() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yml"), "format: " + (VisitSnapshot.FORMAT + 1) + "\nitems: x\nreturn: {world: w}\n", StandardCharsets.UTF_8);
        VisitSnapshotStore store = new VisitSnapshotStore(dir);
        assertTrue(store.load(id).isEmpty());
        assertTrue(store.exists(id), "a newer Core may still restore it");
    }
}
