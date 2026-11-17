package Vystorm.vystorm_core._uiMethods.settings;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SetupDefinitionTest {
    static final Map<String, Object> SCHEMA = Map.of("config.yml:cold.damage", 1.0, "config.yml:enabled", true,
            "config.yml:radius", 16, "config.yml:prefix", "[x]", "other.yml:a.b", 2, "config.yml:db.password", "x");
    static final Function<SetupDefinition.Target, Object> LOOKUP = t -> SCHEMA.get(t.file() + ":" + t.path());
    static final String GOOD = """
            version: 1
            intro: { en: "Hello", de: "Hallo" }
            questions:
              - id: cold
                text: { en: "How cold?" }
                type: choice
                recommended: normal
                options:
                  - id: mild
                    label: { en: "Mild" }
                    set: { "config.yml:cold.damage": 0.5, "other.yml:a.b": 1 }
                  - id: normal
                    label: { en: "Normal", de: "Normal" }
                    set: { "config.yml:cold.damage": 1 }
              - id: radius
                text: { en: "Radius" }
                type: number
                target: "config.yml:radius"
                min: 1
                max: 64
              - id: prefix
                text: { en: "Prefix" }
                type: text
                target: "config.yml:prefix"
            """;

    private static String fails(String text) {
        return assertThrows(SetupDefinition.Invalid.class, () -> SetupDefinition.parse(text, LOOKUP, p -> p.endsWith("password"))).getMessage();
    }
    private static SetupDefinition good() throws Exception { return SetupDefinition.parse(GOOD, LOOKUP, p -> false); }

    @Test void parsesDottedTargetsAndLanguages() throws Exception {
        var d = good();
        assertEquals(3, d.questions().size());
        var option = d.questions().getFirst().options().getFirst();
        assertEquals(0.5, option.set().get(new SetupDefinition.Target("config.yml", "cold.damage")));
        assertEquals("Hallo", SetupDefinition.text(d.intro(), "de"));
    }
    @Test void textFallsBackToEnglishThenFirst() {
        assertEquals("E", SetupDefinition.text(Map.of("en", "E", "de", "D"), "fr"));
        assertEquals("D", SetupDefinition.text(Map.of("de", "D"), "fr"));
        assertEquals("D", SetupDefinition.text(Map.of("de", "D"), "de_de"));
    }
    @Test void recommendedAnswersOnlyCoverQuestionsWithRecommendation() throws Exception {
        assertEquals(Map.of(new SetupDefinition.Target("config.yml", "cold.damage"), 1), good().recommended());
    }
    @Test void answersBecomeChanges() throws Exception {
        var changes = good().changes(Map.of("cold", "mild", "radius", "32", "prefix", "[y]"));
        assertEquals(0.5, changes.get(new SetupDefinition.Target("config.yml", "cold.damage")));
        assertEquals(1, changes.get(new SetupDefinition.Target("other.yml", "a.b")));
        assertEquals(32, changes.get(new SetupDefinition.Target("config.yml", "radius")));
        assertEquals("[y]", changes.get(new SetupDefinition.Target("config.yml", "prefix")));
    }
    @Test void numberAnswersAreChecked() throws Exception {
        var q = good().questions().get(1);
        assertNull(q.number("0"));
        assertNull(q.number("65"));
        assertNull(q.number("1.5"));
        assertNull(q.number("NaN"));
        assertEquals(64, q.number("64"));
    }
    @Test void rejectsUnknownTarget() {
        assertTrue(fails(GOOD.replace("config.yml:radius", "config.yml:nope")).contains("nope"));
    }
    @Test void rejectsSecretTarget() {
        assertTrue(fails(GOOD.replace("config.yml:prefix", "config.yml:db.password")).contains("secret"));
    }
    @Test void rejectsWrongValueType() {
        assertTrue(fails(GOOD.replace("\"config.yml:cold.damage\": 1 }", "\"config.yml:cold.damage\": \"x\" }")).contains("type"));
    }
    @Test void rejectsFractionForWholeTarget() {
        assertTrue(fails(GOOD.replace("\"other.yml:a.b\": 1", "\"other.yml:a.b\": 1.5")).contains("type"));
    }
    @Test void rejectsMissingRecommendation() {
        assertTrue(fails(GOOD.replace("recommended: normal", "recommended: hard")).contains("recommended"));
    }
    @Test void rejectsDuplicateIdsUnknownTypesAndBrokenYaml() {
        assertTrue(fails(GOOD.replace("id: radius", "id: cold")).contains("duplicate"));
        assertTrue(fails(GOOD.replace("type: number", "type: slider")).contains("type"));
        assertNotNull(fails("questions: ["));
        assertNotNull(fails("version: 2\nquestions: []"));
        assertNotNull(fails("version: 1\nquestions: []"));
    }
    @Test void numberTargetMustBeNumeric() {
        assertTrue(fails(GOOD.replace("target: \"config.yml:radius\"", "target: \"config.yml:prefix\"")).contains("type"));
    }
    @Test void listsKeepTheirOrder() throws Exception {
        assertEquals(List.of("mild", "normal"), good().questions().getFirst().options().stream().map(SetupDefinition.Option::id).toList());
    }
}
