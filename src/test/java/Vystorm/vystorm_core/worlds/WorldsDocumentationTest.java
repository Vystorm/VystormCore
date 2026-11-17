package Vystorm.vystorm_core.worlds;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldsDocumentationTest {
    @Test
    void givenServerStop_whenDocumented_thenAutoSaveFalseDoesNotPromiseShutdownDiscard() throws Exception {
        String api = Files.readString(Path.of("docs/API.md"));
        assertTrue(api.contains("autoSave(false) does not prevent the server shutdown save"));
    }

    @Test
    void givenPublicLifecycleContracts_whenDocumented_thenSaveAndFailedExitSemanticsAreExplicit() throws Exception {
        String api = Files.readString(Path.of("docs/API.md"));
        String worlds = api.substring(api.indexOf("## 23. Managed worlds"));
        assertTrue(worlds.contains("unloads according to `WorldSpec.autoSave()`"));
        assertTrue(worlds.contains("may block on disk I/O"));
        String hooks = Files.readString(Path.of("src/main/java/Vystorm/vystorm_core/worlds/VisitHooks.java"));
        assertTrue(hooks.contains("may still be inside"));
    }
}
