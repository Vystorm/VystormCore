package Vystorm.vystorm_core._coreMethods.ressourceLoader;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RessourceHandlerTest {
    @Test
    void givenSavedConfigMissingDefaults_whenMerged_thenOnlyMissingLeafKeysAreAdded() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("a: 1\nsection:\n  x: default\n  y: 2\nscalar:\n  child: 3\n");
        YamlConfiguration saved = new YamlConfiguration();
        saved.loadFromString("a: 99\nsection:\n  x: custom\nscalar: keep\n");
        assertEquals(1, RessourceHandler.mergeMissingKeys(defaults, saved));
        assertEquals(99, saved.getInt("a"));
        assertEquals("custom", saved.getString("section.x"));
        assertEquals(2, saved.getInt("section.y"));
        assertEquals("keep", saved.getString("scalar"));
        assertEquals(0, RessourceHandler.mergeMissingKeys(defaults, saved));
    }
}
