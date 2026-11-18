package Vystorm.vystorm_core.menus.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class MenuTextTest {
    private static final MenuText TEXT = MenuText.of(null, "Steve", 2, 5, null);

    /** True when the component or one of its children carries a click or hover event. */
    private static boolean interactive(Component component) {
        if (component.clickEvent() != null || component.hoverEvent() != null || component.insertion() != null) return true;
        return component.children().stream().anyMatch(MenuTextTest::interactive);
    }

    private static Component first(Component component) { return component.children().isEmpty() ? component : component.children().get(0); }

    @Test
    void givenTheTagsOfTheFormat_whenParsed_thenColoursAndDecorations() {
        assertEquals("Shop", TEXT.plain("<gold><bold>Shop</bold></gold>"));
        Component gold = TEXT.component("<gold>Shop");
        assertEquals(NamedTextColor.GOLD, gold.color() != null ? gold.color() : first(gold).color());
        Component hex = TEXT.component("<#12abef>Shop");
        assertEquals(TextColor.color(0x12ABEF), hex.color() != null ? hex.color() : first(hex).color());
        Component bold = TEXT.component("<b>Shop</b> now");
        assertTrue(bold.hasDecoration(TextDecoration.BOLD) || first(bold).hasDecoration(TextDecoration.BOLD));
        assertEquals("Shop now", TEXT.plain("<b>Shop</b> now"));
        assertEquals("abcd", TEXT.plain("<i>a</i><u>b</u><st>c</st><reset>d"));
        assertEquals("grey", TEXT.plain("<color:#808080>grey</color>"));
    }

    @Test
    void givenTheShortFormsOfTheEditor_whenParsed_thenTheSame() {
        // what the editor's preview accepts besides the documented tags (Format.parseMini)
        assertEquals("a|b|c|d", TEXT.plain("<grey>a</grey>|<dark_grey>b</dark_grey>|<em>c</em>|<colour:red>d</colour>") + "", "spellings");
        assertEquals("x<r>y", TEXT.plain("<bold>x<r>y"), "<r> is no MiniMessage tag; the reset tag is <reset>");
        assertEquals("x", TEXT.plain("<!bold>x"), "MiniMessage also reads the negated form");
        assertEquals("x", TEXT.plain("<c:red>x</c>"));
    }

    @Test
    void givenOtherTags_whenParsed_thenTheyStayVisibleAndCarryNoEvents() {
        String click = "<click:run_command:'/op me'>free diamonds</click>";
        assertEquals(click, TEXT.plain(click), "a click tag is shown as written");
        assertFalse(interactive(TEXT.component(click)));
        String hover = "<hover:show_text:'boo'>x</hover> <insert:abc>y</insert> <newline> <rainbow>z</rainbow> <lang:block.minecraft.stone>";
        assertEquals(hover, TEXT.plain(hover));
        assertFalse(interactive(TEXT.component(hover)));
        assertEquals("<shop> 1 < 2", TEXT.plain("<shop> 1 < 2"));
    }

    @Test
    void givenThePlaceholdersOfTheFormat_whenParsed_thenValuesArePlainText() {
        assertEquals("Hello Steve, page 2 of 5", TEXT.plain("Hello {player}, page {page} of {pages}"));
        MenuText evil = MenuText.of(null, "<red>Evil</red><click:run_command:'/op me'>", 1, 1, null);
        assertEquals("Hi <red>Evil</red><click:run_command:'/op me'>", evil.plain("Hi {player}"), "a name never becomes formatting");
        assertFalse(interactive(evil.component("Hi {player}")));
        assertEquals("", TEXT.plain(""));
        assertEquals("", TEXT.plain(null));
        assertEquals(Component.empty(), TEXT.component(null));
        assertEquals("%vystorm_eco_balance% {core:online}", TEXT.plain("%vystorm_eco_balance% {core:online}"), "without a placeholder manager the text stays as written");
    }

    @Test
    void givenAPlaceholderManager_whenParsed_thenItsValuesAreInsertedLiterally() {
        PlaceholderInterface manager = new PlaceholderInterface() {
            @Override public Prepared miniMessage(OfflinePlayer viewer, Player other, String text) {
                return new Prepared(text.replace("%vystorm_eco_balance%", "<vs_0>"), TagResolver.resolver(Placeholder.unparsed("vs_0", "<bold>120")), 1);
            }
        };
        MenuText text = MenuText.of(null, "Steve", 1, 1, manager);
        assertEquals("Steve has <bold>120 coins", text.plain("<gold>{player}</gold> has %vystorm_eco_balance% coins"));
        Component component = text.component("%vystorm_eco_balance%");
        assertFalse(component.hasDecoration(TextDecoration.BOLD));
        assertTrue(component instanceof TextComponent);
    }

    @Test
    void givenAManagerThatFails_whenParsed_thenTheTextIsShownAsWritten() {
        PlaceholderInterface broken = new PlaceholderInterface() {
            @Override public Prepared miniMessage(OfflinePlayer viewer, Player other, String text) { throw new IllegalStateException("down"); }
        };
        assertEquals("Balance %vystorm_eco_balance%", MenuText.of(null, "Steve", 1, 1, broken).plain("Balance %vystorm_eco_balance%"));
    }
}
