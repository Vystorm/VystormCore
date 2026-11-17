package Vystorm.vystorm_core.worlds.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyVisitFilesTest {
    @TempDir Path dir;

    private static String snapshot(UUID player) {
        return """
                version: 1
                player: %s
                items: encoded-items
                return:
                  world: world
                  x: 1.0
                  y: 70.0
                  z: 2.0
                """.formatted(player);
    }

    @Test
    void givenValidLegacySnapshot_whenRetried_thenDurablyTransferredAndSourcesArchived() throws Exception {
        UUID player = UUID.randomUUID();
        Path talent = dir.resolve("VystormSkills/talentwelt"), sessions = talent.resolve("sessions"), visits = dir.resolve("visits");
        Files.createDirectories(sessions);
        Files.writeString(sessions.resolve(player + ".yml"), snapshot(player));
        Files.writeString(sessions.resolve(player + ".applied"), "1");

        assertEquals("retried", new LegacyVisitFiles(talent, new VisitSnapshotStore(visits), "talent_world", m -> {}).operate(player, "retry"));

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(visits.resolve(player + ".yml").toFile());
        assertEquals(1, yaml.getInt("version"));
        assertEquals("vystormskills:talentwelt", yaml.getString("world-id"));
        assertEquals("talent_world", yaml.getString("world-name"));
        assertEquals("VystormSkills", yaml.getString("owner"));
        assertEquals("vystormskills:tw_item", yaml.getString("legacy-tool-key"));
        assertTrue(Files.exists(talent.resolve("sessions-1.16").resolve(player + ".yml")));
        assertTrue(Files.exists(talent.resolve("sessions-1.16").resolve(player + ".applied")));
        assertFalse(Files.exists(sessions.resolve(player + ".yml")));
    }

    @Test
    void givenExistingCoreSnapshot_whenRetried_thenLegacySourceIsArchivedWithoutOverwritingCore() throws Exception {
        UUID player = UUID.randomUUID();
        Path talent = dir.resolve("talent"), sessions = talent.resolve("sessions"), visits = dir.resolve("visits");
        Files.createDirectories(sessions);Files.createDirectories(visits);
        Files.writeString(sessions.resolve(player + ".yml"), snapshot(player));
        Files.writeString(visits.resolve(player + ".yml"), "newer-core-snapshot");

        assertEquals("retried", new LegacyVisitFiles(talent, new VisitSnapshotStore(visits), "world", m -> {}).operate(player, "retry"));

        assertEquals("newer-core-snapshot", Files.readString(visits.resolve(player + ".yml")));
        assertEquals(snapshot(player), Files.readString(talent.resolve("sessions-1.16").resolve(player + ".yml")));
    }

    @Test
    void givenArchiveCollision_whenArchived_thenBothGenerationsRemain() throws Exception {
        UUID player = UUID.randomUUID();
        Path talent = dir.resolve("talent"), sessions = talent.resolve("sessions"), archive = talent.resolve("sessions-1.16");
        Files.createDirectories(sessions);Files.createDirectories(archive);
        Files.writeString(sessions.resolve(player + ".yml"), snapshot(player));
        Files.writeString(archive.resolve(player + ".yml"), "previous-evidence");

        assertEquals("archived", new LegacyVisitFiles(talent, new VisitSnapshotStore(dir.resolve("visits")), "world", m -> {}).operate(player, "archive"));

        assertEquals("previous-evidence", Files.readString(archive.resolve(player + ".yml")));
        try (var entries = Files.list(archive)) { assertEquals(2, entries.count()); }
    }

    @Test
    void givenInvalidLegacySnapshot_whenRetried_thenItFailsClosedAndKeepsEvidence() throws Exception {
        UUID player = UUID.randomUUID();
        Path talent = dir.resolve("talent"), sessions = talent.resolve("sessions");
        Files.createDirectories(sessions);
        Files.writeString(sessions.resolve(player + ".yml"), "version: 1\nitems: encoded-items\n");
        List<String> log = new ArrayList<>();

        assertEquals("failed", new LegacyVisitFiles(talent, new VisitSnapshotStore(dir.resolve("visits")), "world", log::add).operate(player, "retry"));

        assertTrue(Files.exists(sessions.resolve(player + ".yml")));
        assertTrue(log.stream().anyMatch(message -> message.contains(player.toString())));
    }

    @Test
    void givenNoLegacyEvidence_whenOperated_thenNothing() {
        assertEquals("nothing", new LegacyVisitFiles(dir.resolve("talent"), new VisitSnapshotStore(dir.resolve("visits")), "world", m -> {}).operate(UUID.randomUUID(), "archive"));
    }
}
