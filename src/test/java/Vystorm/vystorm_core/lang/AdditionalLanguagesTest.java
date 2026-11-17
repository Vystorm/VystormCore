package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.internal.LanguageChoices;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AdditionalLanguagesTest {
    @Test void givenRegionalClients_whenAutomatic_thenBundledLanguageIsSelected() {
        Localization service = new Localization();
        Lang core = service.core();
        for (var entry : Map.of("fr-CA", "fr", "pt-PT", "pt_br", "pt-BR", "pt_br", "it-CH", "it", "ru-RU", "ru").entrySet()) {
            Player player = mock(Player.class);
            when(player.locale()).thenReturn(Locale.forLanguageTag(entry.getKey()));
            assertEquals(entry.getValue(), core.language(player), entry.getKey());
            assertEquals(100.0, core.coverage(entry.getValue()), 0.0001);
            assertNotEquals(core.raw("en", "common.close"), core.raw(entry.getValue(), "common.close"));
        }
    }
    @Test void givenNewLanguages_whenSelecting_thenCodesNativeNamesAndAutomaticWordsWork() {
        LanguageChoices choices = LanguageChoices.of(new Localization());
        for (String code : new String[]{"fr", "pt_br", "it", "ru"}) {
            assertEquals(Optional.of(code), choices.parse(code));
            assertEquals(Optional.of(code), choices.parse(CoreLanguage.nativeName(code)));
        }
        assertEquals(Optional.of("pt_br"), choices.parse("pt_PT"));
        for (String word : new String[]{"Automatique", "Automático", "Automatico", "Автоматически"})
            assertEquals(Optional.of("auto"), choices.parse(word));
    }
}
