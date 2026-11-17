package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LanguageAuditTest {
    @Test void givenMissingAndMismatchedTranslations_whenAudited_thenDoesNotHideThemBehindFallback() {
        Lang lang = Lang.create("audit", Map.of("en", "hello: 'Hi {name}'\nonly: 'English'\n", "de", "hello: 'Hallo {player}'\n"));
        var audit = lang.audit().stream().filter(a -> a.language().equals("de")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("only"), audit.missing());
        assertEquals(java.util.List.of("only"), audit.englishFallback());
        assertEquals(java.util.List.of("hello"), audit.placeholderMismatch());
        assertEquals("English", lang.raw("de", "only"), "audit must not change normal fallback");
    }
}
