package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class XMLEntryTest {
    @TempDir Path directory;
    @Test
    void roundTripsXmlContentAndRemovesEntries() {
        Path path = directory.resolve("data.xml");
        XMLEntry entry = new XMLEntry(path, "data");
        entry.setEntry("message", "<Vystorm> & Grüße");
        entry.saveEntry();
        XMLEntry loaded = new XMLEntry(path, "data");
        loaded.loadEntry();
        assertEquals("<Vystorm> & Grüße", loaded.getEntry("message"));
        loaded.removeEntry("message");
        assertFalse(loaded.containsEntry("message"));
    }
    @Test
    void rejectsExternalEntityDeclarations() throws Exception {
        Path path = directory.resolve("data.xml");
        Files.writeString(path, "<!DOCTYPE library [<!ENTITY external SYSTEM 'file:///does-not-exist'>]><library><value>&external;</value></library>");
        assertThrows(RuntimeException.class, () -> new XMLEntry(path, "data").loadEntry());
    }
}
