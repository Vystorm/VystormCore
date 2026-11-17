package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {
    @TempDir Path dir;

    @Test
    void givenInjectedNewlines_whenLogged_thenSingleLineWithoutControlChars() throws Exception {
        try (AuditLog log = new AuditLog(dir, 1024 * 1024, 3, true, Logger.getLogger("t"))) {
            log.log("api", UUID.randomUUID(), "Evil\nevent=forged", "POST /x\r\n", "1.2.3.4", "200", "a\u0000b \"quoted\"");
            log.flush(2000);
            String text = Files.readString(dir.resolve("audit.log"));
            assertEquals(1, text.lines().count(), text);
            assertTrue(text.contains("player=Evil_event=forged"));
            assertTrue(text.contains("detail=\"a_b 'quoted'\""));
        }
    }

    @Test
    void givenSizeCap_whenManyEntries_thenRotatedAndBounded() throws Exception {
        try (AuditLog log = new AuditLog(dir, 64 * 1024, 3, true, Logger.getLogger("t"))) {
            for (int i = 0; i < 3000; i++) log.log("api", null, "p" + i, "GET /x", "1.2.3.4", "200", "x".repeat(50));
            log.flush(5000);
        }
        assertTrue(Files.exists(dir.resolve("audit.log")));
        assertTrue(Files.exists(dir.resolve("audit.log.1")));
        assertTrue(Files.exists(dir.resolve("audit.log.2")));
        assertFalse(Files.exists(dir.resolve("audit.log.3")));
        long total = 0;
        try (var files = Files.list(dir)) { for (Path p : (Iterable<Path>) files::iterator) total += Files.size(p); }
        assertTrue(total <= 3 * 64 * 1024, "total " + total);
    }

    @Test
    void givenDisabled_whenLogged_thenNoFile() {
        try (AuditLog log = new AuditLog(dir, 64 * 1024, 3, false, Logger.getLogger("t"))) {
            log.log("login", null, null, null, null, "ok", null);
            log.flush(1000);
        }
        assertFalse(Files.exists(dir.resolve("audit.log")));
    }
}
