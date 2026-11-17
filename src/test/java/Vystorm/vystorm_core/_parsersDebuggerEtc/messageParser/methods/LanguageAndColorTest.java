package Vystorm.vystorm_core._parsersDebuggerEtc.messageParser.methods;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguageAndColorTest {
    @Test
    void givenBundledLanguageFile_whenLookedUp_thenSectionedKeysResolve() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(LanguageSystem.RESOURCE), "Ressource im Jar unter language/");
        YamlConfiguration config = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream(LanguageSystem.RESOURCE)) {
            config.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        DataEntry entry = mock(DataEntry.class);
        when(entry.getEntry()).thenReturn(config);
        assertNotNull(LanguageSystem.lookup(entry, "no_permission"));
        assertNull(LanguageSystem.lookup(entry, "does_not_exist"));
        assertNull(LanguageSystem.lookup(null, "no_permission"));
    }

    @Test
    void givenAmpersandCodes_whenColored_thenOnlyValidCodesAreTranslated() {
        ColorCorrector colors = new ColorCorrector();
        assertEquals("§aHallo §lWelt & mehr &z", colors.color("&aHallo &LWelt & mehr &z"));
        assertEquals("banana", colors.color("banana"));
        assertNull(colors.color(null));
    }
}
