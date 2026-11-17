package Vystorm.vystorm_core.web.internal.clientproto;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Die Kopie in {@code web/internal/clientproto} muss dem Protokoll-Code des Client-Mods entsprechen (dieselbe
 * Aktionsprüfung auf beiden Seiten). Läuft {@code scripts/sync-clientproto.sh --check}; ohne Client-Checkout
 * ({@code VYSTORM_CLIENT_DIR} oder Standardpfad) übersprungen.
 */
class ClientProtoSyncTest {
    @Test
    void givenClientCheckout_whenCopyCompared_thenInSyncAndNoStaleFiles() throws Exception {
        String dir = System.getenv().getOrDefault("VYSTORM_CLIENT_DIR", System.getProperty("user.home") + "/MythicPluginsBuild/vystorm-client");
        assumeTrue(Files.isDirectory(Path.of(dir, "protocol")), "client checkout not available");
        Process p = new ProcessBuilder("bash", "scripts/sync-clientproto.sh", "--check", dir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "clientproto drifted from the client – run scripts/sync-clientproto.sh:\n" + out);
    }
}
