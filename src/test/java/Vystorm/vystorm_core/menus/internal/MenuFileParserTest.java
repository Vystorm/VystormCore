package Vystorm.vystorm_core.menus.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuFileParserTest {
    private static final String HEAD = "\"format\": \"vystorm-menu\", \"version\": 1, \"id\": \"shop\"";

    private static MenuFileParser.Parsed parse(String body) { return MenuFileParser.parse("shop.json", "{ " + body + " }", name -> !name.equals("NO_SUCH_ITEM")); }

    private static MenuFileParser.Parsed widgets(String widgets) { return parse(HEAD + ", \"widgets\": [ " + widgets + " ]"); }

    /** The file is refused and the one message names {@code field} and holds {@code reason}. */
    private static void refused(MenuFileParser.Parsed parsed, String field, String reason) {
        assertFalse(parsed.ok(), "expected the file to be refused");
        assertNull(parsed.menu());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains(field) && e.contains(reason)), parsed.errors().toString());
    }

    @Test
    void givenTheEditorsDemoMenus_whenRead_thenBothLoadWithTheirFields() throws Exception {
        MenuFile main = FileMenuLayoutTest.demo("main");
        assertEquals("main", main.id());
        assertEquals("mainmenu", main.command());
        assertEquals(MenuFile.Frame.MENU, main.frame());
        assertEquals(List.of("display", "search", "close"), main.header());
        assertEquals(2.04, main.width());
        assertEquals(1.12, main.height());
        assertEquals(28, main.widgets().size());
        assertTrue(main.mainMenu(), "a tile in the main menu unless the file says otherwise");
        assertFalse(main.placeholders(), "{player} alone needs no refresh");
        MenuFile.Widget fatigue = main.widget("fatigue");
        assertEquals(new MenuFile.Click(List.of(new MenuFile.Action(MenuFile.ActionKind.CONSOLE, "fatigue reset {player}"),
                new MenuFile.Action(MenuFile.ActionKind.MESSAGE, "<green>You feel rested again.")), 3600, "vystorm.vip"), fatigue.click());
        assertEquals(List.of(new MenuFile.Action(MenuFile.ActionKind.OPEN, "fatigue-help")), fatigue.rightClick().actions());
        assertEquals("!vystorm.guest", main.widget("warehouse").whenPermission());
        assertEquals(List.of(new MenuFile.Action(MenuFile.ActionKind.PLAYER, "herbarium"), new MenuFile.Action(MenuFile.ActionKind.CLOSE, "")),
                main.widget("herbarium").click().actions());

        MenuFile chest = FileMenuLayoutTest.demo("locks-general");
        assertEquals(MenuFile.Frame.CHEST, chest.frame());
        assertEquals(9, chest.columns());
        assertEquals(6, chest.rows());
        assertEquals(49, chest.widget("back").slot());
        assertEquals(List.of(new MenuFile.Action(MenuFile.ActionKind.BACK, "")), chest.widget("back").click().actions());
    }

    @Test
    void givenTheDemoMenus_whenRead_thenNoWarningIsRaisedForThem() throws Exception {
        assertEquals(List.of(), MenuFileParser.parse("main.json", FileMenuLayoutTest.resource("main.json"), n -> true).warnings());
        assertEquals(List.of(), MenuFileParser.parse("locks-general.json", FileMenuLayoutTest.resource("locks-general.json"), n -> true).warnings());
    }

    @Test
    void givenNoJsonOrAnotherFormat_whenRead_thenRefusedWithTheReason() {
        refused(MenuFileParser.parse("shop.json", "{ not json", n -> true), "not JSON", "");
        refused(MenuFileParser.parse("shop.json", "[1, 2]", n -> true), "not a vystorm-menu file", "");
        refused(MenuFileParser.parse("shop.json", "{ \"id\": \"shop\" }", n -> true), "format", "not a vystorm-menu file");
        refused(parse("\"format\": \"vystorm-scene\", \"version\": 1, \"id\": \"shop\""), "format", "not a vystorm-menu file");
        refused(parse("\"format\": \"vystorm-menu\", \"version\": 2, \"id\": \"shop\""), "version", "2 is not supported");
        refused(parse("\"format\": \"vystorm-menu\", \"version\": \"1\", \"id\": \"shop\""), "version", "not supported");
        refused(MenuFileParser.parse("shop.json", "{ " + HEAD + " } and more", n -> true), "not JSON", "");
        refused(MenuFileParser.parse("shop.json", "", n -> true), "not", "");
    }

    @Test
    void givenBadOrReservedIds_whenRead_thenRefused() {
        refused(MenuFileParser.parse("Shop!.json", "{ \"format\": \"vystorm-menu\", \"version\": 1, \"id\": \"Shop!\" }", n -> true), "id", "not allowed");
        for (String word : List.of("list", "search", "settings", "news", "player", "access"))
            refused(MenuFileParser.parse(word + ".json", "{ \"format\": \"vystorm-menu\", \"version\": 1, \"id\": \"" + word + "\" }", n -> true), "id", "word of the /menu command");
        refused(MenuFileParser.parse("other.json", "{ " + HEAD + " }", n -> true), "id", "must equal the file name");
        refused(parse("\"format\": \"vystorm-menu\", \"version\": 1"), "id", "missing");
        refused(widgets("{ \"id\": \"Bad Id\", \"type\": \"label\" }"), "widgets[0]", "id is not allowed");
        for (String reserved : List.of("title", "footer", "hint", "display", "search", "close", "chest", "head-line", "foot-line"))
            refused(widgets("{ \"id\": \"" + reserved + "\", \"type\": \"label\" }"), "widgets[0]", "used by the frame");
        refused(widgets("{ \"id\": \"vystorm:back\", \"type\": \"label\" }"), "widgets[0]", "id is not allowed");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\" }, { \"id\": \"a\", \"type\": \"label\" }"), "widgets[1]", "used twice");
        refused(widgets("{ \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\" }, { \"id\": \"a.icon\", \"type\": \"label\" }"), "a.icon", "used by the tile");
    }

    @Test
    void givenNumbersOutOfRange_whenRead_thenRefusedInsteadOfCorrected() {
        refused(parse(HEAD + ", \"size\": { \"width\": 5, \"height\": 1 }"), "size.width", "out of range (0.2 to 4.5)");
        refused(parse(HEAD + ", \"size\": { \"width\": 1, \"height\": 0.1 }"), "size.height", "out of range (0.2 to 1.6)");
        refused(parse(HEAD + ", \"size\": { \"width\": \"wide\" }"), "size.width", "must be a number");
        refused(parse(HEAD + ", \"frame\": \"chest\", \"grid\": { \"columns\": 13, \"rows\": 3 }"), "grid.columns", "out of range (1 to 12)");
        refused(parse(HEAD + ", \"frame\": \"chest\", \"grid\": { \"columns\": 9, \"rows\": 2.5 }"), "grid.rows", "whole number");
        refused(parse(HEAD + ", \"pages\": 100"), "pages", "out of range (1 to 99)");
        refused(parse(HEAD + ", \"frame\": \"window\""), "frame", "not one of menu, chest, none");
        refused(parse(HEAD + ", \"backdrop\": \"pink\""), "backdrop", "not one of");
        refused(parse(HEAD + ", \"mode\": \"vr\""), "mode", "not one of");
        refused(parse(HEAD + ", \"header\": [\"close\", \"help\"]"), "header", "not a header button");
        refused(parse(HEAD + ", \"permission\": \"two words\""), "permission", "not a permission");
        refused(parse(HEAD + ", \"command\": \"my shop\""), "command", "not a command name");
        refused(parse(HEAD + ", \"mainMenu\": \"no\""), "mainMenu", "true or false");
        refused(parse(HEAD + ", \"group\": \"toys\""), "group", "not a group of the main menu");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"width\": 0, \"height\": 0.1 }"), "widgets[0] \"a\"", "above 0");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"u\": 400 }"), "widgets[0] \"a\".u", "out of range");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"when\": { \"page\": 2 } }"), "widgets[0] \"a\"", "when.page 2 is above the menu's 1 page");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"fill\": \"red\" }"), "fill", "not a colour");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"align\": \"middle\" }"), "align", "not one of start, center, end");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"text\": 5 }"), "text", "must be text");
    }

    @Test
    void givenUnknownTypesSlotsAndIcons_whenRead_thenRefused() {
        refused(widgets("{ \"id\": \"a\", \"type\": \"toggle\" }"), "widgets[0] \"a\"", "type \"toggle\" is unknown");
        refused(widgets("{ \"id\": \"a\", \"type\": \"input\" }"), "widgets[0] \"a\"", "is unknown");
        refused(widgets("{ \"id\": \"a\", \"type\": \"tile\" }"), "widgets[0] \"a\"", "needs an icon");
        refused(widgets("{ \"id\": \"a\", \"type\": \"icon\", \"icon\": \"not a material\" }"), "icon", "not a material name");
        refused(widgets("{ \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"slot\": 3 }"), "widgets[0] \"a\"", "slot needs the frame \"chest\"");
        refused(parse(HEAD + ", \"frame\": \"chest\", \"grid\": { \"columns\": 9, \"rows\": 1 }, \"widgets\": [ { \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"slot\": 9 } ]"),
                "widgets[0] \"a\"", "slot 9 is outside the grid (0 to 8)");
        refused(parse(HEAD + ", \"frame\": \"chest\", \"widgets\": [ { \"id\": \"a\", \"type\": \"button\", \"slot\": 1 } ]"), "widgets[0] \"a\"", "only tiles can have a slot");
        refused(parse(HEAD + ", \"widgets\": {}"), "widgets", "must be a list");
        refused(widgets("5"), "widgets[0]", "must be an object");
    }

    @Test
    void givenBadActions_whenRead_thenRefused() {
        String tile = "{ \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"click\": { \"actions\": [ %s ] } }";
        refused(widgets(tile.formatted("{ \"teleport\": \"spawn\" }")), "click.actions[0]", "unknown action");
        refused(widgets(tile.formatted("{ \"command\": \"  \" }")), "click.actions[0].command", "is empty");
        refused(widgets(tile.formatted("{ \"command\": \"/\" }")), "click.actions[0].command", "is empty");
        refused(widgets(tile.formatted("{ \"command\": \"say hi\", \"as\": \"op\" }")), "click.actions[0].as", "\"player\" or \"console\"");
        refused(widgets(tile.formatted("{ \"open\": \"Not An Id\" }")), "click.actions[0].open", "not a menu id");
        refused(widgets(tile.formatted("{ \"back\": false }")), "click.actions[0]", "unknown action");
        refused(widgets(tile.formatted("\"close\"")), "click.actions[0]", "must be an object");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"click\": { \"actions\": [ { \"close\": true } ] } }"), "widgets[0] \"a\".click", "cannot be clicked");
        refused(widgets("{ \"id\": \"a\", \"type\": \"button\", \"click\": { \"actions\": [ { \"close\": true } ], \"cooldown\": 86401 } }"), "click.cooldown", "out of range (0 to 86400)");
        refused(widgets("{ \"id\": \"a\", \"type\": \"button\", \"rightClick\": { \"actions\": [ { \"close\": true } ], \"permission\": \"a b\" } }"), "rightClick.permission", "not a permission");
        refused(widgets("{ \"id\": \"a\", \"type\": \"button\", \"click\": [] }"), "click", "must be an object");
    }

    @Test
    void givenAConsoleCommandWithAPlaceholder_whenRead_thenRefused() {
        String tile = "{ \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"click\": { \"actions\": [ { \"command\": \"%s\", \"as\": \"console\" } ], \"cooldown\": 5 } }";
        refused(widgets(tile.formatted("eco give {player} %vystorm_eco_bonus%")), "click.actions[0].command", "may only contain {player} and {uuid}, not %vystorm_eco_bonus%");
        refused(widgets(tile.formatted("say %player_name%")), "click.actions[0].command", "not %player_name%");
        assertTrue(widgets(tile.formatted("eco give {player} 5")).ok());
        assertTrue(widgets(tile.formatted("whitelist add {uuid}")).ok());
        assertTrue(widgets(tile.formatted("say 50% off, 20% more")).ok(), "a percent sign alone is no placeholder");
        // the scene format's name for "as"
        MenuFileParser.Parsed run = widgets("{ \"id\": \"a\", \"type\": \"button\", \"click\": { \"actions\": [ { \"command\": \"/say hi\", \"run\": \"console\" } ], \"permission\": \"x.y\" } }");
        assertTrue(run.ok(), run.errors().toString());
        assertEquals(new MenuFile.Action(MenuFile.ActionKind.CONSOLE, "say hi"), run.menu().widget("a").click().actions().get(0));
        assertEquals(List.of(), run.warnings());
    }

    @Test
    void givenAConsoleCommandEveryoneCanRun_whenRead_thenOneWarningPerWidget() {
        String open = "{ \"id\": \"%s\", \"type\": \"button\", \"click\": { \"actions\": [ { \"command\": \"say a\", \"as\": \"console\" }, { \"command\": \"say b\", \"as\": \"console\" } ] %s } %s }";
        MenuFileParser.Parsed parsed = widgets(open.formatted("free", "", "") + ", " + open.formatted("timed", ", \"cooldown\": 10", "") + ", "
                + open.formatted("vip", ", \"permission\": \"shop.vip\"", "") + ", " + open.formatted("hidden", "", ", \"when\": { \"permission\": \"shop.staff\" }"));
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertEquals(1, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.warnings().get(0).contains("\"free\"") && parsed.warnings().get(0).contains("console command that every player can run"));
        // a menu behind a permission is not open to everyone
        MenuFileParser.Parsed guarded = parse(HEAD + ", \"permission\": \"shop.use\", \"widgets\": [ " + open.formatted("free", "", "") + " ]");
        assertEquals(List.of(), guarded.warnings());
        // console commands on the left and on the right click of one widget: still one warning
        MenuFileParser.Parsed both = widgets("{ \"id\": \"both\", \"type\": \"button\", \"click\": { \"actions\": [ { \"command\": \"say a\", \"as\": \"console\" } ] },"
                + " \"rightClick\": { \"actions\": [ { \"command\": \"say b\", \"as\": \"console\" } ] } }");
        assertEquals(List.of("widgets[0] \"both\": a console command that every player can run as often as they like (no permission, no cooldown)"), both.warnings());
    }

    @Test
    void givenRemarks_whenRead_thenTheMenuLoadsWithWarnings() {
        MenuFileParser.Parsed parsed = parse(HEAD + ", \"colour\": \"blue\", \"frame\": \"none\", \"header\": [\"close\"], \"size\": { \"width\": 1, \"height\": 0.5 }, \"widgets\": ["
                + " { \"id\": \"far\", \"type\": \"label\", \"u\": 0.9, \"v\": 0.1, \"width\": 0.5, \"height\": 0.05 },"
                + " { \"id\": \"odd\", \"type\": \"icon\", \"u\": 0.1, \"v\": 0.1, \"icon\": \"minecraft:no_such_item\" },"
                + " { \"id\": \"go\", \"type\": \"button\", \"u\": 0.1, \"v\": 0.3, \"click\": { \"actions\": [ { \"close\": true }, { \"message\": \"bye\" } ] } } ]");
        assertTrue(parsed.ok(), parsed.errors().toString());
        String all = String.join("\n", parsed.warnings());
        assertTrue(all.contains("colour: unknown field"), all);
        assertTrue(all.contains("header buttons are not shown with frame \"none\""), all);
        assertTrue(all.contains("\"far\": reaches outside the panel"), all);
        assertTrue(all.contains("this server has no item NO_SUCH_ITEM"), all);
        assertTrue(all.contains("the menu is left here"), all);
        assertEquals("NO_SUCH_ITEM", parsed.menu().widget("odd").icon(), "the name is kept, the placeholder icon is chosen when shown");
        assertEquals(0.085, parsed.menu().widget("odd").width(), "sizes the file leaves out are the editor's defaults");
    }

    @Test
    void givenTwoTilesInOneCell_whenRead_thenWarnedUnlessAPermissionDecides() {
        String grid = HEAD + ", \"frame\": \"chest\", \"widgets\": [ { \"id\": \"a\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"slot\": 0 %s }, { \"id\": \"b\", \"type\": \"tile\", \"icon\": \"EMERALD\", \"slot\": 0 %s } ]";
        assertEquals(1, parse(grid.formatted("", "")).warnings().size());
        assertEquals(0, parse(grid.formatted(", \"when\": { \"permission\": \"vip\" }", ", \"when\": { \"permission\": \"!vip\" }")).warnings().size());
    }

    @Test
    void givenLimits_whenExceeded_thenRefused() {
        refused(MenuFileParser.parse("shop.json", "{ " + HEAD + ", \"title\": \"" + "x".repeat(MenuFileParser.MAX_FILE_BYTES) + "\" }", n -> true), "file is larger than", "");
        refused(parse(HEAD + ", \"title\": \"" + "x".repeat(MenuFileParser.MAX_TEXT + 1) + "\""), "title", "longer than 256 characters");
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"text\": \"" + "x".repeat(MenuFileParser.MAX_TEXT + 1) + "\" }"), "text", "longer than");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= MenuFileParser.MAX_WIDGETS; i++) many.append(i == 0 ? "" : ", ").append("{ \"id\": \"w").append(i).append("\", \"type\": \"label\" }");
        refused(widgets(many.toString()), "widgets", "at most " + MenuFileParser.MAX_WIDGETS);
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i <= MenuFileParser.MAX_ACTIONS; i++) actions.append(i == 0 ? "" : ", ").append("{ \"message\": \"hi\" }");
        refused(widgets("{ \"id\": \"a\", \"type\": \"button\", \"click\": { \"actions\": [ " + actions + " ] } }"), "click.actions", "at most " + MenuFileParser.MAX_ACTIONS);
        refused(widgets("{ \"id\": \"a\", \"type\": \"label\", \"text\": \"two\\nlines\" }"), "text", "line break");
        // deep nesting ends in an error message, not in a stack overflow
        MenuFileParser.Parsed deep = MenuFileParser.parse("shop.json", "{ " + HEAD + ", \"samples\": " + "[".repeat(20000) + "]".repeat(20000) + " }", n -> true);
        assertTrue(deep.ok() || !deep.errors().isEmpty());
    }

    @Test
    void givenTheNewTileFields_whenRead_thenKept() {
        MenuFileParser.Parsed parsed = parse(HEAD + ", \"title\": \"<gold>Shop\", \"mainMenu\": false, \"icon\": \"minecraft:emerald\", \"group\": \"Economy\","
                + " \"backdrop\": \"dim\", \"mode\": \"floating\", \"footer\": \"You have %vystorm_eco_balance% coins\","
                + " \"samples\": { \"%vystorm_eco_balance%\": \"120\" } ");
        assertTrue(parsed.ok(), parsed.errors().toString());
        MenuFile menu = parsed.menu();
        assertFalse(menu.mainMenu());
        assertEquals("EMERALD", menu.icon());
        assertEquals(MenuCategory.ECONOMY.group(), menu.group());
        assertEquals(ScreenSpec.Backdrop.DIM, menu.backdrop());
        assertEquals(ScreenSpec.Mode.FLOATING, menu.mode());
        assertEquals(List.of("close"), menu.header(), "the default header");
        assertTrue(menu.placeholders(), "a placeholder asks for a refresh while the menu is open");
        assertEquals("menus:shop", menu.key());
        assertEquals(List.of(), parsed.warnings(), "samples belong to the editor and are ignored without a remark");
    }

    @Test
    void givenColoursAndAlignment_whenRead_thenArgb() {
        MenuFileParser.Parsed parsed = widgets("{ \"id\": \"a\", \"type\": \"button\", \"u\": 0.1, \"v\": 0.2, \"fill\": \"#102030\", \"highlight\": \"#80FFEEDD\", \"align\": \"END\", \"textHeight\": 0.03, \"enabled\": false }");
        assertTrue(parsed.ok(), parsed.errors().toString());
        MenuFile.Widget a = parsed.menu().widget("a");
        assertEquals(0xFF102030, a.fill());
        assertEquals(0x80FFEEDD, a.highlight());
        assertEquals(ScreenWidget.Align.END, a.align());
        assertEquals(0.03, a.textHeight());
        assertFalse(a.enabled());
        assertEquals(0.22, a.width());
    }
}
