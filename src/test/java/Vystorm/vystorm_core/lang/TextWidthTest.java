package Vystorm.vystorm_core.lang;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** CJK-aware display width, wrapping, centring and truncation. */
class TextWidthTest {
    @Test
    void givenMixedText_whenColumnsCounted_thenFullWidthCountsTwoAndCodesZero() {
        assertEquals(5, TextWidth.columns("Hello"));
        assertEquals(8, TextWidth.columns("简体中文"));
        assertEquals(4, TextWidth.columns("§a中§l文"));
        assertEquals(2, TextWidth.columns("，"));          // full-width comma
        assertEquals(3, TextWidth.columns("ｦab"));        // half-width katakana is narrow
        assertEquals(2, TextWidth.columns("한"));
        assertEquals(1, TextWidth.columns("é"));    // combining accent takes no column
        assertEquals(4, TextWidth.columns("ab\nabcd"));   // widest line
        assertEquals(4, TextWidth.columns("§x§f§f§8§8§0§0中文"));
        assertEquals(2, TextWidth.columns(Component.text("中")));
        assertTrue(TextWidth.isWide('中'));
        assertFalse(TextWidth.isWide('A'));
    }

    @Test
    void givenText_whenPixelsMeasured_thenMinecraftFontApproximated() {
        assertEquals(6, TextWidth.pixels("a"));
        assertEquals(2, TextWidth.pixels("i"));
        assertEquals(4, TextWidth.pixels(" "));
        assertEquals(9, TextWidth.pixels("中"));
        assertEquals(18, TextWidth.pixels("中文"));
        assertEquals(7, TextWidth.pixels("§la"));          // bold adds one pixel
        assertEquals(6, TextWidth.pixels("§l§ra"));
    }

    @Test
    void givenChineseSentence_whenWrapped_thenLinesFitAndPunctuationNeverStartsALine() {
        String text = "这是一段很长的中文文本，用来测试物品描述的自动换行。每一行都不应该超过限制！";
        List<String> lines = TextWidth.wrap(text, 12);
        assertTrue(lines.size() > 3, lines.toString());
        assertEquals(text, String.join("", lines));
        for (String line : lines) {
            int cols = TextWidth.columns(line);
            assertTrue(cols <= 12 || (cols <= 14 && "，。！？、".indexOf(line.charAt(line.length() - 1)) >= 0), line + " " + cols);
            assertTrue("，。！？、）".indexOf(line.charAt(0)) < 0, "starts with punctuation: " + line);
        }
    }

    @Test
    void givenOpeningPunctuation_whenAtLineEnd_thenMovedToTheNextLine() {
        List<String> lines = TextWidth.wrap("一二三四“五六”", 10);
        assertEquals(List.of("一二三四", "“五六”"), lines);
    }

    @Test
    void givenLatinText_whenWrapped_thenBreaksAtSpacesAndSplitsLongWords() {
        assertEquals(List.of("Hello", "world foo"), TextWidth.wrap("Hello world foo", 9));
        assertEquals(List.of("abcdef", "ghij"), TextWidth.wrap("abcdefghij", 6));
        assertEquals(List.of("one", "", "two"), TextWidth.wrap("one\n\ntwo", 10));
        assertEquals(List.of(""), TextWidth.wrap(null, 10));
    }

    @Test
    void givenMixedLanguages_whenWrapped_thenCjkBreaksAnywhereAndLatinWordsStayWhole() {
        List<String> lines = TextWidth.wrap("使用 /language 命令选择语言", 10);
        assertTrue(lines.stream().anyMatch(l -> l.contains("/language")), lines.toString());
        for (String line : lines) assertTrue(TextWidth.columns(line) <= 10, line);
    }

    @Test
    void givenColouredText_whenWrapped_thenColourCarriedToContinuedLines() {
        List<String> lines = TextWidth.wrap("§c§l红色粗体文字很长很长", 8);
        assertTrue(lines.size() >= 2);
        for (String line : lines.subList(1, lines.size())) assertTrue(line.startsWith("§c§l"), line);
        assertEquals("§a", TextWidth.activeCodes("§c§lx§ay"));
        assertEquals("", TextWidth.activeCodes("§cx§r"));
    }

    @Test
    void givenText_whenCenteredOrTruncated_thenWidthsRespected() {
        assertEquals("   中文", TextWidth.center("中文", 10));
        assertEquals("   abcd", TextWidth.center("abcd", 10));
        assertEquals("中文…", TextWidth.truncate("中文字符串", 5));
        assertEquals("abcd…", TextWidth.truncate("abcdefgh", 5));
        assertEquals("abc", TextWidth.truncate("abc", 5));
        assertEquals("中文  ", TextWidth.padRight("中文", 6));
        String centered = TextWidth.centerChat("中文");
        assertEquals((TextWidth.CHAT_PIXELS - 18) / 2 / 4, centered.indexOf('中'));
    }

    @Test
    void givenLangLines_whenWrapped_thenCjkLoreSplit() {
        Lang lang = Lang.create("WrapTest", java.util.Map.of("en", "lore: \"<red>这是一段很长的物品描述文本，需要换行显示\""));
        List<Component> lines = lang.wrappedLines(null, "lore", 10);
        assertTrue(lines.size() >= 3);
        for (Component c : lines) assertTrue(TextWidth.columns(PlainTextComponentSerializer.plainText().serialize(c)) <= 12);
    }
}
