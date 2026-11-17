package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnfilledTagsTest {
    @Test void findsPlaceholdersWrittenAsTags() {
        assertEquals(Set.of("date", "phase"), Lang.unfilledTags("<aqua>Winter</aqua> <date> | <phase> {strength}%"));
        assertEquals(Set.of(), Lang.unfilledTags("<gold><bold>Hi</bold></gold> {player} <click:run_command:'/x'>x</click> <#ff8800>y"));
        assertEquals(Set.of(), Lang.unfilledTags("<red>Use /pay <Player> <XP>"));
        assertEquals(Set.of(), Lang.unfilledTags("<yellow>Usage: /vadmin permissions <plugin> [page]"));
        assertEquals(Set.of(), Lang.unfilledTags("<gold>Nodes <gray>(/vadmin permissions <plugin>):"));
        assertEquals(Set.of(), Lang.unfilledTags("You lack vystorm_core.config.<plugin>."));
        assertEquals(Set.of(), Lang.unfilledTags("<red>请指定：/vadmin language import <file> <plugin> <lang>"));
    }
}
