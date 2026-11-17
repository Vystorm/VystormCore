package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigMenuModelTest {
    @Test void nestedSectionsBecomeFoldersAndSingleValuesStay() {
        var root = ConfigTree.build("cold", List.of("cold.enabled", "cold.damage.base", "cold.damage.max", "cold.deep.only", "cold.a.b.c", "cold.a.b.d"));
        assertEquals(List.of("cold.enabled", "cold.deep.only"), root.options());
        assertEquals(List.of("cold.damage", "cold.a"), root.folders().stream().map(ConfigTree.Folder::path).toList());
        assertEquals(6, root.size());
        assertEquals(List.of("cold.a.b"), root.folders().get(1).folders().stream().map(ConfigTree.Folder::path).toList());
    }
    @Test void topLevelValuesHaveNoFolder() {
        assertEquals(List.of("a", "b"), ConfigTree.build("", List.of("a", "b")).options());
    }
    @Test void wrapBreaksAtSpacesAndLimitsLines() {
        assertEquals(List.of("aaa bbb", "ccc"), ConfigTree.wrap("aaa bbb ccc", 7, 5));
        assertEquals(List.of("aaa…"), ConfigTree.wrap("aaa bbb", 3, 1));
        assertTrue(ConfigTree.wrap(null, 7, 5).isEmpty());
    }
    private static List<Map<String, Object>> sample() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("x", 1));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "a"); entry.put("weight", 2.5); entry.put("on", true); entry.put("sub", nested); entry.put("tags", new ArrayList<>(List.of("p", "q")));
        return new ArrayList<>(List.of(entry));
    }
    @Test void structureEditsLeaveTheInputAlone() {
        var list = sample();
        var added = StructureOps.add(list, list.getFirst());
        assertEquals(2, added.size());
        assertEquals(1, list.size());
        var changed = StructureOps.set(added, 1, "sub.x", 9);
        assertEquals(1, ((Map<?, ?>) added.get(1).get("sub")).get("x"));
        assertEquals(9, ((Map<?, ?>) changed.get(1).get("sub")).get("x"));
        assertEquals(1, ((Map<?, ?>) changed.get(0).get("sub")).get("x"));
        assertEquals(1, StructureOps.remove(changed, 0).size());
    }
    @Test void unknownFieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StructureOps.set(sample(), 0, "nope", 1));
        assertThrows(IllegalArgumentException.class, () -> StructureOps.set(sample(), 0, "name.x", 1));
    }
    @Test void fieldsFlattenAndKeepOrder() {
        assertEquals(List.of("name", "weight", "on", "sub.x", "tags"), List.copyOf(StructureOps.fields(sample().getFirst()).keySet()));
    }
    @Test void parsingFollowsTheCurrentType() {
        assertEquals(5, StructureOps.parse(1, " 5 "));
        assertNull(StructureOps.parse(1, "5.5"));
        assertEquals(1.5, StructureOps.parse(2.0, "1,5"));
        assertNull(StructureOps.parse(2.0, "NaN"));
        assertEquals(Boolean.TRUE, StructureOps.parse(false, "TRUE"));
        assertNull(StructureOps.parse(false, "x"));
        assertEquals(List.of(1, 2), StructureOps.parse(List.of(7), "1, 2"));
        assertNull(StructureOps.parse(List.of(7), "1, b"));
        assertEquals(" spaced", StructureOps.parse("x", " spaced"));
    }
    @Test void structureMeansNonEmptyListOfMappings() {
        assertTrue(StructureOps.isStructure(sample()));
        assertFalse(StructureOps.isStructure(List.of()));
        assertFalse(StructureOps.isStructure(List.of("a")));
    }
    @Test void onlyPlainSettingsFilesAreAutoRegistered() {
        assertTrue(ConfigEditorService.editableDefault("config.yml"));
        assertTrue(ConfigEditorService.editableDefault("spawns.yml"));
        for (String name : List.of("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.yml", "setup.yml", "legacy-texts.yml", "lang/en.yml", "a.txt"))
            assertFalse(ConfigEditorService.editableDefault(name), name);
    }
}
