package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.compat.JsonCompat;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.ScreenWidget;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one menu file ({@code vystorm-menu} version 1) and checks it. The rules are those of the editor's
 * {@code Format.validate}; where the editor quietly corrects a value when it opens a file (a number out of range, an
 * unknown frame), this reader refuses the file, so a typing mistake is reported instead of showing a different menu.
 * Every message names the field and the reason. Pure: no server needed, apart from the test whether a material
 * exists, which the caller passes in.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class MenuFileParser {
    public static final String FORMAT = "vystorm-menu";
    public static final int VERSION = 1;
    /** Limits that keep a broken or hostile file from costing the server anything. */
    public static final int MAX_FILE_BYTES = 256 * 1024, MAX_WIDGETS = 300, MAX_TEXT = 256, MAX_COMMAND = 256, MAX_ACTIONS = 16;
    /** A length or position outside this range is a mistake (the largest panel is 4.5 x 1.6). */
    static final double MAX_LENGTH = 16;

    /** Words {@code /menu} handles itself; a menu with such an id could never be opened by its bare id. */
    public static final Set<String> RESERVED_MENU_IDS = Set.of("list", "search", "settings", "news", "player", "access",
            "profile", "inbox", "einstellungen");
    /** Ids of the frame and of Core's Back button. */
    public static final Set<String> RESERVED_WIDGET_IDS = Set.of("title", "footer", "hint", "display", "search", "close", "chest",
            "head-line", "foot-line", "vystorm:back");
    public static final List<String> HEADER_BUTTONS = List.of("display", "search", "close");
    /** Group names of the main menu a file may use for its tile; the empty name is the first, general section. */
    public static final Map<String, MenuCategory> GROUPS = groups();

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern WIDGET_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,47}");
    private static final Pattern MATERIAL = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern PERMISSION = Pattern.compile("!?[A-Za-z0-9_.*-]{1,128}");
    private static final Pattern COLOUR = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    /** {@code %name%}: a placeholder of Core or of another placeholder plugin. */
    static final Pattern PERCENT = Pattern.compile("%[^%\\s]+%");
    /** {@code {namespace:key}}: the short form of Core's placeholders. */
    static final Pattern BRACES = Pattern.compile("\\{[A-Za-z][A-Za-z0-9]{0,31}:[^{}\\s]+}");

    private static final Set<String> MENU_FIELDS = Set.of("format", "version", "id", "title", "command", "permission", "frame", "size", "grid",
            "header", "footer", "backdrop", "mode", "pages", "samples", "widgets", "mainMenu", "icon", "group");
    private static final Set<String> WIDGET_FIELDS = Set.of("id", "type", "u", "v", "width", "height", "slot", "text", "icon", "description",
            "align", "textHeight", "fill", "highlight", "enabled", "when", "click", "rightClick");

    /** {@code menu} is null when the file has errors. Warnings do not stop a menu from loading. */
    public record Parsed(MenuFile menu, List<String> errors, List<String> warnings) {
        public boolean ok() { return menu != null; }
    }

    private final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    /** Set while a widget's clicks are read: one of them runs a console command that nothing guards. */
    private boolean openConsole;
    private final Predicate<String> materialKnown;

    private MenuFileParser(Predicate<String> materialKnown) { this.materialKnown = materialKnown; }

    private static Map<String, MenuCategory> groups() {
        Map<String, MenuCategory> out = new LinkedHashMap<>();
        for (MenuCategory category : List.of(MenuCategory.CHARACTER, MenuCategory.ECONOMY, MenuCategory.COMMUNITY, MenuCategory.WORLD,
                MenuCategory.BUILDING, MenuCategory.ADMIN)) out.put(category.name().toLowerCase(Locale.ROOT), category);
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * @param fileName the file's name ({@code shop.json}); the part before {@code .json} must be the menu's id
     * @param json the file's text
     * @param materialKnown whether a material name ({@code EMERALD}) is an item of this server
     */
    public static Parsed parse(String fileName, String json, Predicate<String> materialKnown) {
        MenuFileParser parser = new MenuFileParser(materialKnown == null ? name -> true : materialKnown);
        MenuFile menu = null;
        try {
            menu = parser.read(fileName, json);
        } catch (RuntimeException | StackOverflowError unexpected) {
            // a file must never take the loader down, whatever it contains
            parser.errors.add("could not be read (" + unexpected.getClass().getSimpleName() + ")");
        }
        return new Parsed(parser.errors.isEmpty() ? menu : null, List.copyOf(parser.errors), List.copyOf(parser.warnings));
    }

    private MenuFile read(String fileName, String json) {
        if (json == null) { errors.add("file is empty"); return null; }
        if (json.length() > MAX_FILE_BYTES) { errors.add("file is larger than " + MAX_FILE_BYTES / 1024 + " KB"); return null; }
        if (!json.isEmpty() && json.charAt(0) == '﻿') json = json.substring(1);
        JsonElement root;
        try (com.google.gson.stream.JsonReader reader = JsonCompat.reader(json)) {
            root = JsonParser.parseReader(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new com.google.gson.JsonSyntaxException("text after the end of the menu");
        } catch (java.io.IOException | RuntimeException notJson) {
            String detail = notJson.getMessage() == null ? notJson.getClass().getSimpleName() : notJson.getMessage();
            errors.add("not JSON (" + (detail.length() > 120 ? detail.substring(0, 120) + "…" : detail) + ")");
            return null;
        }
        if (root == null || !root.isJsonObject()) { errors.add("not a " + FORMAT + " file (the file must hold one JSON object)"); return null; }
        JsonObject o = root.getAsJsonObject();
        if (!isString(o.get("format")) || !FORMAT.equals(o.get("format").getAsString())) {
            errors.add("format: not a " + FORMAT + " file (\"format\" must be \"" + FORMAT + "\")");
            return null;
        }
        Integer version = whole(o.get("version"));
        if (version == null || version != VERSION) {
            errors.add("version: " + show(o.get("version")) + " is not supported (this Core reads version " + VERSION + ")");
            return null;
        }
        for (String field : o.keySet()) if (!MENU_FIELDS.contains(field)) warnings.add(field + ": unknown field, ignored");

        String id = string(o, "id", "", 64).trim();
        if (!o.has("id")) errors.add("id: missing");
        else if (!ID.matcher(id).matches()) errors.add("id: \"" + cut(id) + "\" is not allowed (a-z, 0-9, _ and -, 1 to 32 characters, starting with a letter or digit)");
        else if (RESERVED_MENU_IDS.contains(id)) errors.add("id: \"" + id + "\" is a word of the /menu command and cannot be a menu id");
        String expected = fileName == null ? null : fileName.replaceFirst("(?i)\\.json$", "");
        if (expected != null && ID.matcher(id).matches() && !expected.equals(id))
            errors.add("id: \"" + id + "\" must equal the file name (\"" + cut(expected) + "\")");

        String title = string(o, "title", "", MAX_TEXT);
        String command = string(o, "command", "", 64).trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.isEmpty() && !ID.matcher(command).matches())
            errors.add("command: \"" + cut(command) + "\" is not a command name (a-z, 0-9, _ and -, without the slash)");
        String permission = permission(o, "permission", "permission");
        String frameName = choice(o, "frame", "menu", List.of("menu", "chest", "none"));
        MenuFile.Frame frame = MenuFile.Frame.valueOf(frameName.toUpperCase(Locale.ROOT));

        double width = 2.04, height = 1.12;
        JsonObject size = object(o, "size");
        if (size != null) {
            width = number(size, "width", "size.width", 2.04, 0.2, 4.5);
            height = number(size, "height", "size.height", 1.12, 0.2, 1.6);
        }
        int columns = 9, rows = 3;
        JsonObject grid = object(o, "grid");
        if (grid != null) {
            columns = integer(grid, "columns", "grid.columns", 9, 1, 12);
            rows = integer(grid, "rows", "grid.rows", 3, 1, 8);
        }

        List<String> header = new ArrayList<>();
        if (!o.has("header")) header.add("close");
        else if (!o.get("header").isJsonArray()) errors.add("header: must be a list of " + HEADER_BUTTONS);
        else for (JsonElement e : o.getAsJsonArray("header")) {
            String name = isString(e) ? e.getAsString().toLowerCase(Locale.ROOT) : null;
            if (name == null || !HEADER_BUTTONS.contains(name)) errors.add("header: " + show(e) + " is not a header button (" + String.join(", ", HEADER_BUTTONS) + ")");
            else if (!header.contains(name)) header.add(name);
        }
        if (frame == MenuFile.Frame.NONE && o.has("header") && !header.isEmpty())
            warnings.add("header: header buttons are not shown with frame \"none\"");
        String footer = string(o, "footer", "", MAX_TEXT);
        ScreenSpec.Backdrop backdrop = ScreenSpec.Backdrop.valueOf(choice(o, "backdrop", "black", List.of("black", "dim", "gray", "white", "none")).toUpperCase(Locale.ROOT));
        ScreenSpec.Mode mode = ScreenSpec.Mode.valueOf(choice(o, "mode", "auto", List.of("auto", "pinned", "floating")).toUpperCase(Locale.ROOT));
        int pages = integer(o, "pages", "pages", 1, 1, 99);

        boolean mainMenu = bool(o, "mainMenu", "mainMenu", true);
        String icon = material(o, "icon", "icon");
        String group = "";
        if (o.has("group")) {
            String name = string(o, "group", "", 32).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) group = "";
            else if (!GROUPS.containsKey(name)) errors.add("group: \"" + cut(name) + "\" is not a group of the main menu (" + String.join(", ", GROUPS.keySet()) + ")");
            else group = GROUPS.get(name).group();
        }

        List<MenuFile.Widget> widgets = new ArrayList<>();
        if (o.has("widgets") && !o.get("widgets").isJsonArray()) errors.add("widgets: must be a list");
        else if (o.has("widgets")) {
            JsonArray list = o.getAsJsonArray("widgets");
            if (list.size() > MAX_WIDGETS) errors.add("widgets: " + list.size() + " widgets, at most " + MAX_WIDGETS + " are allowed");
            else {
                Set<String> seen = new HashSet<>();
                Map<Integer, List<int[]>> slots = new HashMap<>();
                boolean menuOpen = permission.isEmpty();
                for (int i = 0; i < list.size(); i++) {
                    MenuFile.Widget widget = widget(list.get(i), i, frame, width, height, columns * rows, pages, seen, slots, menuOpen);
                    if (widget != null) widgets.add(widget);
                }
                derivedIds(widgets, frame);
            }
        }

        boolean placeholders = dynamic(title) || dynamic(footer);
        for (MenuFile.Widget widget : widgets) placeholders |= dynamic(widget.text()) || dynamic(widget.description());
        if (!errors.isEmpty()) return null;
        return new MenuFile(id, title, command, permission, frame, width, height, columns, rows, header, footer, backdrop, mode, pages,
                mainMenu, icon, group, widgets, placeholders);
    }

    /** True when the text holds a placeholder whose value can change while the menu is open. */
    static boolean dynamic(String text) {
        return text != null && !text.isEmpty() && (PERCENT.matcher(text).find() || BRACES.matcher(text).find());
    }

    private MenuFile.Widget widget(JsonElement element, int index, MenuFile.Frame frame, double panelWidth, double panelHeight, int cells,
                                   int pages, Set<String> seen, Map<Integer, List<int[]>> slots, boolean menuOpen) {
        if (element == null || !element.isJsonObject()) { errors.add("widgets[" + index + "]: must be an object"); return null; }
        JsonObject w = element.getAsJsonObject();
        String id = string(w, "id", "widgets[" + index + "].id", "", 64).trim();
        String at = "widgets[" + index + "]" + (id.isEmpty() ? "" : " \"" + cut(id) + "\"");
        for (String field : w.keySet()) if (!WIDGET_FIELDS.contains(field)) warnings.add(at + ": unknown field \"" + cut(field) + "\", ignored");
        if (!WIDGET_ID.matcher(id).matches()) errors.add(at + ": id is not allowed (a-z, 0-9, _ . and -, 1 to 48 characters, starting with a letter or digit)");
        else if (RESERVED_WIDGET_IDS.contains(id)) errors.add(at + ": id \"" + id + "\" is used by the frame of the menu");
        else if (!seen.add(id)) errors.add(at + ": the id is used twice");

        String typeName = string(w, "type", at + ".type", "", 32).toLowerCase(Locale.ROOT);
        MenuFile.Type type;
        try {
            type = MenuFile.Type.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(at + ": type \"" + cut(typeName) + "\" is unknown (label, button, tile, icon, fill)");
            return null;
        }

        JsonObject when = object(w, "when", at + ".when");
        String whenPermission = when == null ? "" : permission(when, "permission", at + ".when.permission");
        int whenPage = when == null ? 0 : integer(when, "page", at + ".when.page", 0, 0, 99);
        if (whenPage > pages) errors.add(at + ": when.page " + whenPage + " is above the menu's " + pages + " page" + (pages == 1 ? "" : "s"));

        int slot = -1;
        double u = 0, v = 0, width = defaultSize(type)[0], height = defaultSize(type)[1];
        if (w.has("slot") && !w.get("slot").isJsonNull()) {
            Integer value = whole(w.get("slot"));
            if (value == null || value < 0) errors.add(at + ": slot must be a whole number from 0");
            else if (type != MenuFile.Type.TILE) errors.add(at + ": only tiles can have a slot");
            else if (frame != MenuFile.Frame.CHEST) errors.add(at + ": slot needs the frame \"chest\"");
            else if (value >= cells) errors.add(at + ": slot " + value + " is outside the grid (0 to " + (cells - 1) + ")");
            else {
                slot = value;
                // two tiles in one cell on the same page; tiles with a permission condition may share a cell on purpose
                List<int[]> taken = slots.computeIfAbsent(slot, k -> new ArrayList<>());
                if (whenPermission.isEmpty()) for (int[] other : taken)
                    if (other[1] == 0 && (other[0] == 0 || whenPage == 0 || other[0] == whenPage)) { warnings.add(at + ": slot " + slot + " already holds a tile"); break; }
                taken.add(new int[] { whenPage, whenPermission.isEmpty() ? 0 : 1 });
            }
        }
        if (slot < 0) {
            u = number(w, "u", at + ".u", 0, -MAX_LENGTH, MAX_LENGTH);
            v = number(w, "v", at + ".v", 0, -MAX_LENGTH, MAX_LENGTH);
            width = number(w, "width", at + ".width", width, 0, MAX_LENGTH);
            height = number(w, "height", at + ".height", height, 0, MAX_LENGTH);
            if (!(width > 0) || !(height > 0)) errors.add(at + ": width and height must be above 0");
            else if (frame != MenuFile.Frame.CHEST && (u < -1e-9 || v < -1e-9 || u + width > panelWidth + 1e-9 || v + height > panelHeight + 1e-9))
                warnings.add(at + ": reaches outside the panel");
        }

        String text = string(w, "text", at + ".text", "", MAX_TEXT);
        String description = string(w, "description", at + ".description", "", MAX_TEXT);
        int before = errors.size();
        String icon = material(w, "icon", at + ".icon");
        if ((type == MenuFile.Type.TILE || type == MenuFile.Type.ICON) && icon.isEmpty() && errors.size() == before) errors.add(at + ": a " + typeName + " needs an icon (a material name such as EMERALD)");
        String alignName = choice(w, "align", at + ".align", "center", List.of("start", "center", "end"));
        ScreenWidget.Align align = ScreenWidget.Align.valueOf(alignName.toUpperCase(Locale.ROOT));
        double textHeight = number(w, "textHeight", at + ".textHeight", 0, 0, MAX_LENGTH);
        Integer fill = colour(w, "fill", at + ".fill"), highlight = colour(w, "highlight", at + ".highlight");
        boolean enabled = bool(w, "enabled", at + ".enabled", true);

        boolean guarded = !menuOpen || !whenPermission.isEmpty();
        openConsole = false;
        MenuFile.Click click = click(w, "click", at, type, guarded), rightClick = click(w, "rightClick", at, type, guarded);
        // one remark per widget, however many console commands its clicks hold
        if (openConsole) warnings.add(at + ": a console command that every player can run as often as they like (no permission, no cooldown)");
        return new MenuFile.Widget(id, type, slot, u, v, width, height, text, icon, description, align, textHeight, fill, highlight, enabled,
                whenPermission, whenPage, click, rightClick);
    }

    /** Default size of a widget without one, as the editor creates it. */
    private static double[] defaultSize(MenuFile.Type type) {
        return switch (type) {
            case LABEL -> new double[] { 0.5, 0.05 };
            case BUTTON -> new double[] { 0.22, 0.07 };
            case TILE -> new double[] { 0.19, 0.16 };
            case ICON -> new double[] { 0.085, 0.085 };
            case FILL -> new double[] { 0.4, 0.004 };
        };
    }

    /** A tile also shows widgets named {@code <id>.icon} and {@code <id>.name}; no other widget may have those ids. */
    private void derivedIds(List<MenuFile.Widget> widgets, MenuFile.Frame frame) {
        Set<String> ids = new HashSet<>();
        for (MenuFile.Widget widget : widgets) ids.add(widget.id());
        for (MenuFile.Widget widget : widgets) {
            if (widget.type() != MenuFile.Type.TILE) continue;
            for (String suffix : List.of(".icon", ".name"))
                if (ids.contains(widget.id() + suffix))
                    errors.add("widgets \"" + widget.id() + suffix + "\": the id is used by the tile \"" + widget.id() + "\" for its own parts");
        }
    }

    private MenuFile.Click click(JsonObject w, String field, String at, MenuFile.Type type, boolean guarded) {
        if (!w.has(field) || w.get(field).isJsonNull()) return MenuFile.Click.NONE;
        String where = at + "." + field;
        if (!w.get(field).isJsonObject()) { errors.add(where + ": must be an object with \"actions\""); return MenuFile.Click.NONE; }
        JsonObject c = w.getAsJsonObject(field);
        for (String key : c.keySet()) if (!Set.of("actions", "cooldown", "permission").contains(key)) warnings.add(where + ": unknown field \"" + cut(key) + "\", ignored");
        int cooldown = integer(c, "cooldown", where + ".cooldown", 0, 0, 86400);
        String permission = permission(c, "permission", where + ".permission");
        List<MenuFile.Action> actions = new ArrayList<>();
        if (c.has("actions") && !c.get("actions").isJsonArray()) { errors.add(where + ".actions: must be a list"); return MenuFile.Click.NONE; }
        JsonArray list = c.has("actions") ? c.getAsJsonArray("actions") : new JsonArray();
        if (list.size() > MAX_ACTIONS) { errors.add(where + ".actions: " + list.size() + " actions, at most " + MAX_ACTIONS + " are allowed"); return MenuFile.Click.NONE; }
        if (list.size() == 0) return MenuFile.Click.NONE;
        if (!type.clickable()) errors.add(where + ": a " + type.name().toLowerCase(Locale.ROOT) + " cannot be clicked (only buttons and tiles)");
        for (int n = 0; n < list.size(); n++) {
            String here = where + ".actions[" + n + "]";
            MenuFile.Action action = action(list.get(n), here);
            if (action == null) continue;
            actions.add(action);
            if (action.kind() == MenuFile.ActionKind.CONSOLE && permission.isEmpty() && cooldown == 0 && !guarded) openConsole = true;
            boolean leaves = action.kind() == MenuFile.ActionKind.OPEN || action.kind() == MenuFile.ActionKind.BACK || action.kind() == MenuFile.ActionKind.CLOSE;
            if (leaves && n < list.size() - 1) warnings.add(here + ": the menu is left here, the actions after it still run");
        }
        return new MenuFile.Click(actions, cooldown, permission);
    }

    private MenuFile.Action action(JsonElement element, String here) {
        if (element == null || !element.isJsonObject()) { errors.add(here + ": must be an object"); return null; }
        JsonObject a = element.getAsJsonObject();
        if (a.has("command")) {
            if (!isString(a.get("command"))) { errors.add(here + ".command: must be text"); return null; }
            String command = a.get("command").getAsString().trim();
            if (command.startsWith("/")) command = command.substring(1).trim();
            // "run" is the name of the scene format for "as"
            JsonElement as = a.has("as") ? a.get("as") : a.get("run");
            String sender = as == null || as.isJsonNull() ? "player" : isString(as) ? as.getAsString().toLowerCase(Locale.ROOT) : null;
            if (sender == null || !(sender.equals("player") || sender.equals("console"))) { errors.add(here + ".as: must be \"player\" or \"console\""); return null; }
            if (command.isEmpty()) { errors.add(here + ".command: is empty"); return null; }
            if (command.length() > MAX_COMMAND) { errors.add(here + ".command: longer than " + MAX_COMMAND + " characters"); return null; }
            if (control(command)) { errors.add(here + ".command: contains a line break or control character"); return null; }
            if (sender.equals("console")) {
                // Only {player} and {uuid} are ever filled into a command, and a command never passes the placeholder
                // manager, so no value of a placeholder can reach the console. A %placeholder% here is a mistake of the
                // author (it would stay as written), and is refused so that nobody builds on it.
                Matcher percent = PERCENT.matcher(command);
                if (percent.find()) {
                    errors.add(here + ".command: a console command may only contain {player} and {uuid}, not " + cut(percent.group()));
                    return null;
                }
                return new MenuFile.Action(MenuFile.ActionKind.CONSOLE, command);
            }
            return new MenuFile.Action(MenuFile.ActionKind.PLAYER, command);
        }
        if (a.has("message")) {
            if (!isString(a.get("message"))) { errors.add(here + ".message: must be text"); return null; }
            String message = a.get("message").getAsString();
            if (message.length() > MAX_TEXT) { errors.add(here + ".message: longer than " + MAX_TEXT + " characters"); return null; }
            return new MenuFile.Action(MenuFile.ActionKind.MESSAGE, message);
        }
        if (a.has("open")) {
            String target = isString(a.get("open")) ? a.get("open").getAsString().trim() : "";
            if (!ID.matcher(target).matches()) { errors.add(here + ".open: \"" + cut(target) + "\" is not a menu id"); return null; }
            return new MenuFile.Action(MenuFile.ActionKind.OPEN, target);
        }
        if (isTrue(a.get("back"))) return new MenuFile.Action(MenuFile.ActionKind.BACK, "");
        if (isTrue(a.get("close"))) return new MenuFile.Action(MenuFile.ActionKind.CLOSE, "");
        errors.add(here + ": unknown action (command, message, open, back or close)");
        return null;
    }

    // ------------------------------------------------------------------ typed fields

    private static boolean isString(JsonElement e) { return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(); }
    private static boolean isTrue(JsonElement e) { return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean(); }
    private static boolean control(String text) { return text.chars().anyMatch(c -> c < 0x20 || c == 0x7F); }
    private static String cut(String text) { return text.length() <= 40 ? text : text.substring(0, 39) + "…"; }
    private static String show(JsonElement e) {
        if (e == null || e.isJsonNull()) return "nothing";
        String text = e.toString();
        return cut(text);
    }

    /** A JSON number that is a whole number, else null. */
    private static Integer whole(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double value;
        try { value = p.getAsDouble(); } catch (NumberFormatException notANumber) { return null; }
        return Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) <= 1_000_000 ? (int) value : null;
    }

    private String string(JsonObject o, String field, String fallback, int max) { return string(o, field, field, fallback, max); }

    private String string(JsonObject o, String field, String name, String fallback, int max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return fallback;
        if (!isString(e)) { errors.add(name + ": must be text"); return fallback; }
        String text = e.getAsString();
        if (text.length() > max) { errors.add(name + ": longer than " + max + " characters"); return fallback; }
        if (control(text)) { errors.add(name + ": contains a line break or control character"); return fallback; }
        return text;
    }

    private String permission(JsonObject o, String field, String name) {
        String node = string(o, field, name, "", 160).trim();
        if (!node.isEmpty() && !PERMISSION.matcher(node).matches()) {
            errors.add(name + ": \"" + cut(node) + "\" is not a permission (node or !node)");
            return "";
        }
        return node;
    }

    private String choice(JsonObject o, String field, String fallback, List<String> allowed) { return choice(o, field, field, fallback, allowed); }

    private String choice(JsonObject o, String field, String name, String fallback, List<String> allowed) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return fallback;
        String value = isString(e) ? e.getAsString().toLowerCase(Locale.ROOT) : null;
        if (value == null || !allowed.contains(value)) {
            errors.add(name + ": " + show(e) + " is not one of " + String.join(", ", allowed));
            return fallback;
        }
        return value;
    }

    private JsonObject object(JsonObject o, String field) { return object(o, field, field); }

    private JsonObject object(JsonObject o, String field, String name) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) { errors.add(name + ": must be an object"); return null; }
        return e.getAsJsonObject();
    }

    private double number(JsonObject o, String field, String name, double fallback, double min, double max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return fallback;
        double value = Double.NaN;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            try { value = e.getAsDouble(); } catch (NumberFormatException notANumber) { value = Double.NaN; }
        }
        if (!Double.isFinite(value)) { errors.add(name + ": must be a number"); return fallback; }
        if (value < min || value > max) { errors.add(name + ": " + trim(value) + " is out of range (" + trim(min) + " to " + trim(max) + ")"); return fallback; }
        return value;
    }

    private int integer(JsonObject o, String field, String name, int fallback, int min, int max) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return fallback;
        Integer value = whole(e);
        if (value == null) { errors.add(name + ": must be a whole number"); return fallback; }
        if (value < min || value > max) { errors.add(name + ": " + value + " is out of range (" + min + " to " + max + ")"); return fallback; }
        return value;
    }

    private boolean bool(JsonObject o, String field, String name, boolean fallback) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) return fallback;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) { errors.add(name + ": must be true or false"); return fallback; }
        return e.getAsBoolean();
    }

    /** {@code "#RRGGBB"} or {@code "#AARRGGBB"} as ARGB; null for a missing or empty field (Core's own colour). */
    private Integer colour(JsonObject o, String field, String name) {
        String text = string(o, field, name, "", 16).trim();
        if (text.isEmpty()) return null;
        Matcher m = COLOUR.matcher(text);
        if (!m.matches()) { errors.add(name + ": \"" + cut(text) + "\" is not a colour (#RRGGBB or #AARRGGBB)"); return null; }
        long value = Long.parseLong(m.group(1), 16);
        return m.group(1).length() == 6 ? (int) (0xFF000000L | value) : (int) value;
    }

    /** A material name in upper case without {@code minecraft:}; empty when missing. Unknown on this server: a warning, the name is kept. */
    private String material(JsonObject o, String field, String name) {
        String text = string(o, field, name, "", 80).trim().toUpperCase(Locale.ROOT);
        if (text.startsWith("MINECRAFT:")) text = text.substring("MINECRAFT:".length());
        if (text.isEmpty()) return "";
        if (!MATERIAL.matcher(text).matches()) { errors.add(name + ": \"" + cut(text) + "\" is not a material name (for example EMERALD)"); return ""; }
        boolean known;
        try { known = materialKnown.test(text); } catch (RuntimeException | LinkageError failed) { known = true; }
        if (!known) warnings.add(name + ": this server has no item " + text + ", a placeholder icon is shown");
        return text;
    }

    private static String trim(double value) {
        String text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
