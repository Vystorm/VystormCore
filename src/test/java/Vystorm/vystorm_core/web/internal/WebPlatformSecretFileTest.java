package Vystorm.vystorm_core.web.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Das automatisch erzeugte Tunnel-Geheimnis landet in config.yml; andere Systembenutzer dürfen es nicht lesen. */
class WebPlatformSecretFileTest {
    @TempDir Path dir;

    @Test
    void givenWorldReadableConfig_whenSecretWritten_thenOthersLoseAccessButOwnerAndGroupKeepIt() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path config = Files.writeString(dir.resolve("config.yml"), "web:\n  gateway:\n    secret: x\n");
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-rw-r--"));
        assertTrue(WebPlatform.protectSecretFile(config));
        var perms = Files.getPosixFilePermissions(config);
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ) && perms.contains(PosixFilePermission.OWNER_WRITE));
        assertTrue(perms.contains(PosixFilePermission.GROUP_READ));
    }
}
