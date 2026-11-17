// Kopie aus Vystorm/vystorm-client (protocol, 0.9.0, PanelSchema.java). Nicht von Hand ändern, sondern dort ändern und neu kopieren.
package Vystorm.vystorm_core.web.internal.clientproto.panel;

import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelJson.*;
import static Vystorm.vystorm_core.web.internal.clientproto.panel.PanelLimits.*;

import Vystorm.vystorm_core.web.internal.clientproto.ui.Icon;
import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Panel-JSON (Schema v1) → {@link PanelDocument}. Dieselbe Klasse prüft in Core vor dem Senden (Spec §2, §3). */
public final class PanelSchema {
    private PanelSchema() {}

    public static JsonObject parseJson(String json, int maxChars) { return PanelJson.parse(json, maxChars); }

    public static PanelDocument document(String json, Deadline deadline) {
        return document(PanelJson.parse(json, MAX_DOCUMENT_BYTES), deadline);
    }

    public static PanelDocument document(JsonObject o, Deadline deadline) {
        int schema = integer(o, "schema", -1, -1, 255);
        if (schema < SCHEMA_MIN || schema > SCHEMA_MAX) throw invalid("schema unsupported");
        String panel = str(o, "panel", true, 64);
        if (!PanelIds.panel(panel)) throw invalid("panel invalid");
        Parser p = new Parser(deadline);
        PanelNode root = p.element(object(o.get("root"), "root"), 1, true);
        if (!(root instanceof PanelNode.Page page)) throw invalid("root must be a page");
        return new PanelDocument(schema, panel, page, java.util.Collections.unmodifiableMap(p.byId),
                java.util.Collections.unmodifiableMap(p.parents), List.copyOf(p.problems));
    }

    /** Parser-Zustand eines Dokuments (auch für einzelne Elemente aus Patches benutzt). */
    static final class Parser {
        final Deadline deadline;
        final Map<String, PanelNode> byId = new LinkedHashMap<>();
        final Map<PanelNode, PanelNode> parents = new IdentityHashMap<>();
        final List<PanelDocument.Problem> problems = new ArrayList<>();
        int count;

        Parser(Deadline deadline) { this.deadline = deadline; }

        PanelNode element(JsonObject o, int depth, boolean root) {
            if (depth > MAX_DEPTH) throw limit("elements nested too deeply");
            if (++count > MAX_ELEMENTS) throw limit("too many elements");
            if ((count & 63) == 0) deadline.check();
            String type = str(o, "type", true, 24);
            PanelNode.Common c = common(o);
            if (type.equals("page") != root) throw invalid(root ? "root must be a page" : "page only as root");
            PanelNode node = switch (type) {
                case "page" -> page(c, o, depth);
                case "section" -> new PanelNode.Section(c, text(o, "title"), choice(o, "variant", PanelNode.Section.Variant.CARD, PanelNode.Section.Variant.class),
                        bool(o, "collapsible", false), bool(o, "collapsed", false), children(o, "children", depth));
                case "row", "col" -> box(c, o, type.equals("row"), depth);
                case "grid" -> grid(c, o, depth);
                case "tabs" -> tabs(c, o, depth);
                case "heading" -> new PanelNode.Heading(c, required(text(o, "text"), "text"), integer(o, "level", 1, 1, 3));
                case "text" -> textNode(c, o);
                case "kv" -> kv(c, o);
                case "progress" -> progress(c, o);
                case "timer" -> timer(c, o);
                case "item" -> item(c, o);
                case "image" -> image(c, o);
                case "badge" -> new PanelNode.Badge(c, required(text(o, "text"), "text"), color(o, "color", PanelColor.of(PanelColor.Token.ACCENT)));
                case "swatch" -> {
                    PanelColor color = color(o, "color", null);
                    if (color == null) throw invalid("color missing");
                    yield new PanelNode.Swatch(c, color, integer(o, "size", 10, 4, 64));
                }
                case "divider" -> new PanelNode.Divider(c, integer(o, "size", 1, 1, 8));
                case "spacer" -> new PanelNode.Spacer(c, integer(o, "size", 8, 0, 200));
                default -> interactiveOrUnknown(type, c, o, depth);
            };
            if (node.interactive() && (c.id() == null || PanelIds.auto(c.id()))) throw invalid(type + " needs an id");
            if (c.id() != null && byId.putIfAbsent(c.id(), node) != null) throw invalid("duplicate id");
            for (PanelNode child : node.nested()) parents.put(child, node);
            return node;
        }

        int formDepth;

        PanelNode interactiveOrUnknown(String type, PanelNode.Common c, JsonObject o, int depth) {
            return switch (type) {
                case "button" -> button(c, o);
                case "toggle" -> new PanelNode.Toggle(c, required(text(o, "label"), "label"), text(o, "description"), bool(o, "value", false));
                case "slider" -> slider(c, o);
                case "number" -> numberField(c, o);
                case "input" -> input(c, o);
                case "select" -> select(c, o);
                case "form" -> form(c, o, depth);
                case "table" -> table(c, o, depth);
                case "list" -> list(c, o);
                default -> unsupported(type, c);
            };
        }

        PanelNode.Button button(PanelNode.Common c, JsonObject o) {
            UiText label = text(o, "label");
            PanelIcon icon = icon(o, "icon");
            if (label.isEmpty() && icon == null) throw invalid("button needs label or icon");
            int targets = (bool(o, "action", false) ? 1 : 0) + (o.has("navigate") ? 1 : 0) + (o.has("web") ? 1 : 0);
            if (targets != 1) throw invalid("button needs exactly one of action/navigate/web");
            PanelNode.Button.Target target;
            if (o.has("navigate")) {
                JsonObject n = object(o.get("navigate"), "navigate");
                String panel = str(n, "panel", true, 64);
                String arg = str(n, "arg", false, MAX_ARG_BYTES);
                if (!PanelIds.panel(panel) || (arg != null && arg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ARG_BYTES)) throw invalid("navigate invalid");
                target = new PanelNode.Button.Navigate(panel, arg == null ? "" : arg);
            } else if (o.has("web")) {
                JsonObject w = object(o.get("web"), "web");
                String module = str(w, "module", true, 32);
                String page = str(w, "page", false, 288);
                if (!Vystorm.vystorm_core.web.internal.clientproto.ModuleIds.valid(module)
                        || (page != null && !page.isEmpty() && !Vystorm.vystorm_core.web.internal.clientproto.ModuleIds.validPage(page))) throw invalid("web invalid");
                target = new PanelNode.Button.Web(new PanelNode.WebLink(module, page == null ? "" : page));
            } else {
                target = new PanelNode.Button.Act();
            }
            PanelNode.Button.Confirm confirm = confirm(o);
            return new PanelNode.Button(c, label, icon, choice(o, "style", PanelNode.Button.Style.DEFAULT, PanelNode.Button.Style.class), confirm, target);
        }

        record Range(double min, double max, double step, double value) {}

        Range range(JsonObject o) {
            double min = number(o, "min", Double.NaN, -1e12, 1e12);
            double max = number(o, "max", Double.NaN, -1e12, 1e12);
            double step = number(o, "step", 1, 1e-9, 1e12);
            if (!(min < max) || (max - min) / step > 100_000) throw invalid("range invalid");
            double value = number(o, "value", min, min, max);
            return new Range(min, max, step, value);
        }

        PanelNode.Slider slider(PanelNode.Common c, JsonObject o) {
            Range r = range(o);
            String format = str(o, "format", false, 64);
            if (format != null) format = UiText.sanitize(format);
            return new PanelNode.Slider(c, text(o, "label"), r.min(), r.max(), r.step(), r.value(), format,
                    integer(o, "decimals", 0, 0, 6), bool(o, "live", false));
        }

        PanelNode.NumberField numberField(PanelNode.Common c, JsonObject o) {
            Range r = range(o);
            return new PanelNode.NumberField(c, text(o, "label"), r.min(), r.max(), r.step(), integer(o, "decimals", 0, 0, 6), r.value());
        }

        PanelNode.Input input(PanelNode.Common c, JsonObject o) {
            int maxLength = integer(o, "maxLength", 64, 1, MAX_INPUT_LENGTH);
            PanelNode.Input.Chars chars = choice(o, "chars", PanelNode.Input.Chars.ANY, PanelNode.Input.Chars.class);
            String raw = str(o, "value", false, MAX_INPUT_LENGTH * 2);
            String value = raw == null ? "" : UiText.sanitize(raw);
            if (value.codePointCount(0, value.length()) > maxLength || !ActionCheck.charsOk(chars, value)) throw invalid("value invalid");
            return new PanelNode.Input(c, text(o, "label"), value, text(o, "placeholder"), maxLength, chars,
                    bool(o, "submit", false), bool(o, "search", false));
        }

        PanelNode.Select select(PanelNode.Common c, JsonObject o) {
            JsonArray a = array(o, "options", true, MAX_OPTIONS);
            if (a.isEmpty()) throw invalid("options empty");
            List<PanelNode.Select.Option> options = new ArrayList<>(a.size());
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonElement e : a) {
                JsonObject op = object(e, "options");
                String id = str(op, "id", true, 64);
                if (!PanelIds.rowKey(id) || !ids.add(id)) throw invalid("option id invalid");
                options.add(new PanelNode.Select.Option(id, required(text(op, "label"), "label"), icon(op, "icon"), tooltip(op)));
            }
            String value = str(o, "value", false, 64);
            if (value != null && !ids.contains(value)) throw invalid("value is no option");
            return new PanelNode.Select(c, text(o, "label"), options, value,
                    choice(o, "style", PanelNode.Select.Style.DROPDOWN, PanelNode.Select.Style.class), bool(o, "search", false));
        }

        PanelNode.Form form(PanelNode.Common c, JsonObject o, int depth) {
            if (formDepth > 0) throw invalid("forms cannot be nested");
            formDepth++;
            try {
                return new PanelNode.Form(c, children(o, "children", depth), required(text(o, "submit"), "submit"), confirm(o));
            } finally {
                formDepth--;
            }
        }

        PanelNode.Table table(PanelNode.Common c, JsonObject o, int depth) {
            JsonArray cols = array(o, "columns", true, MAX_COLUMNS);
            if (cols.isEmpty()) throw invalid("columns empty");
            List<PanelNode.Table.Column> columns = new ArrayList<>();
            java.util.Set<String> colIds = new java.util.HashSet<>();
            for (JsonElement e : cols) {
                JsonObject k = object(e, "columns");
                String id = str(k, "id", true, 64);
                if (!PanelIds.element(id) || !colIds.add(id)) throw invalid("column id invalid");
                columns.add(new PanelNode.Table.Column(id, text(k, "label"), size(k, "width"),
                        choice(k, "align", PanelNode.Align.START, PanelNode.Align.class), bool(k, "sortable", false)));
            }
            JsonArray rowsJson = array(o, "rows", false, MAX_TABLE_ROWS);
            List<PanelNode.Table.Row> rows = new ArrayList<>(rowsJson.size());
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (JsonElement e : rowsJson) {
                JsonObject r = object(e, "rows");
                String key = str(r, "key", true, 64);
                if (!PanelIds.rowKey(key) || !keys.add(key)) throw invalid("row key invalid");
                JsonArray cellsJson = array(r, "cells", true, MAX_COLUMNS);
                if (cellsJson.size() != columns.size()) throw invalid("cells count != columns");
                List<PanelNode.Table.Cell> cells = new ArrayList<>(cellsJson.size());
                for (JsonElement cell : cellsJson) cells.add(cell(cell, depth));
                rows.add(new PanelNode.Table.Row(key, cells, tooltip(r)));
            }
            PanelNode.Table.Sort sort = null;
            if (o.has("sort")) {
                JsonObject s = object(o.get("sort"), "sort");
                String column = str(s, "column", true, 64);
                String dir = str(s, "dir", false, 4);
                if (columns.stream().noneMatch(k -> k.id().equals(column) && k.sortable()) || (dir != null && !dir.equals("asc") && !dir.equals("desc")))
                    throw invalid("sort invalid");
                sort = new PanelNode.Table.Sort(column, "desc".equals(dir));
            }
            PanelNode.Table.Paging paging = null;
            if (o.has("paging")) {
                JsonObject p = object(o.get("paging"), "paging");
                int pages = integer(p, "pages", 1, 1, 10_000);
                paging = new PanelNode.Table.Paging(integer(p, "page", 0, 0, pages - 1), pages);
            }
            return new PanelNode.Table(c, columns, rows, sort, integer(o, "rowLines", 1, 1, 3), paging,
                    choice(o, "select", PanelNode.SelectMode.NONE, PanelNode.SelectMode.class), text(o, "empty"), selectedKey(o));
        }

        /** {@code selected}: Schlüssel der gewählten Zeile (Client 0.9.0); muss nicht auf der aktuellen Seite stehen. */
        String selectedKey(JsonObject o) {
            String key = str(o, "selected", false, 64);
            if (key != null && !PanelIds.rowKey(key)) throw invalid("selected invalid");
            return key;
        }

        /** Zelle: Text, {@code {text,sort}} (Textobjekt ohne {@code type}) oder button/badge/item/row daraus. */
        PanelNode.Table.Cell cell(JsonElement e, int depth) {
            if (e.isJsonObject() && e.getAsJsonObject().has("type")) {
                PanelNode node = element(e.getAsJsonObject(), depth + 1, false);
                if (!cellNode(node)) throw invalid("cell type not allowed");
                return new PanelNode.Table.NodeCell(node);
            }
            Double sort = e.isJsonObject() && e.getAsJsonObject().has("sort") ? number(e.getAsJsonObject(), "sort", 0, -1e15, 1e15) : null;
            return new PanelNode.Table.TextCell(PanelJson.text(e, "cell"), sort);
        }

        static boolean cellNode(PanelNode n) {
            if (n instanceof PanelNode.Button || n instanceof PanelNode.Badge || n instanceof PanelNode.ItemNode) return true;
            return n instanceof PanelNode.Box b && b.horizontal() && b.children().stream().allMatch(x ->
                    x instanceof PanelNode.Button || x instanceof PanelNode.Badge || x instanceof PanelNode.ItemNode);
        }

        PanelNode.ListView list(PanelNode.Common c, JsonObject o) {
            JsonArray a = array(o, "items", false, MAX_LIST_ITEMS);
            List<PanelNode.ListView.Entry> items = new ArrayList<>(a.size());
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (JsonElement e : a) {
                JsonObject i = object(e, "items");
                String key = str(i, "key", true, 64);
                if (!PanelIds.rowKey(key) || !keys.add(key)) throw invalid("item key invalid");
                items.add(new PanelNode.ListView.Entry(key, icon(i, "icon"), required(text(i, "title"), "title"), text(i, "subtitle"),
                        text(i, "right"), tooltip(i), text(i, "badge")));
            }
            return new PanelNode.ListView(c, items, choice(o, "select", PanelNode.SelectMode.NONE, PanelNode.SelectMode.class), text(o, "empty"),
                    selectedKey(o));
        }

        PanelNode unsupported(String type, PanelNode.Common c) {
            problems.add(new PanelDocument.Problem(c.id() == null || PanelIds.auto(c.id()) ? "" : c.id(), ERR_UNSUPPORTED, "unsupported type " + type));
            return new PanelNode.Unsupported(c, type);
        }

        PanelNode.Common common(JsonObject o) {
            String id = str(o, "id", false, 64);
            if (id != null && !PanelIds.elementOrAuto(id)) throw invalid("id invalid");
            return new PanelNode.Common(id, tooltip(o), bool(o, "visible", true), bool(o, "disabled", false), size(o, "width"),
                    integer(o, "minWidth", 0, 0, 2000), integer(o, "maxWidth", 0, 0, 2000), integer(o, "grow", 0, 0, 10),
                    choice(o, "align", PanelNode.Align.STRETCH, PanelNode.Align.class));
        }

        List<PanelNode> children(JsonObject o, String f, int depth) {
            JsonArray a = array(o, f, false, MAX_ELEMENTS);
            List<PanelNode> out = new ArrayList<>(a.size());
            for (JsonElement e : a) out.add(element(object(e, f), depth + 1, false));
            return out;
        }

        PanelNode.Page page(PanelNode.Common c, JsonObject o, int depth) {
            PanelNode.WebLink web = null;
            if (o.has("web")) {
                JsonObject w = object(o.get("web"), "web");
                String module = str(w, "module", true, 32);
                String page = str(w, "page", false, 288);
                if (!Vystorm.vystorm_core.web.internal.clientproto.ModuleIds.valid(module)
                        || (page != null && !page.isEmpty() && !Vystorm.vystorm_core.web.internal.clientproto.ModuleIds.validPage(page))) throw invalid("web invalid");
                web = new PanelNode.WebLink(module, page == null ? "" : page);
            }
            List<PanelNode> footer = children(o, "footer", depth);
            for (PanelNode f : footer) if (!(f instanceof PanelNode.Button)) throw invalid("footer only takes buttons");
            return new PanelNode.Page(c, text(o, "title"), text(o, "subtitle"), icon(o, "icon"),
                    choice(o, "size", PanelNode.Page.PageSize.MEDIUM, PanelNode.Page.PageSize.class),
                    choice(o, "theme", PanelNode.Page.Theme.VYSTORM, PanelNode.Page.Theme.class),
                    color(o, "accent", null), web, children(o, "children", depth), footer);
        }

        PanelNode.Box box(PanelNode.Common c, JsonObject o, boolean horizontal, int depth) {
            PanelNode.Box.Scroll scroll = choice(o, "scroll", PanelNode.Box.Scroll.NONE, PanelNode.Box.Scroll.class);
            int maxHeight = integer(o, "maxHeight", 0, 0, 4000);
            if (scroll == PanelNode.Box.Scroll.Y && maxHeight == 0) throw invalid("scroll y needs maxHeight");
            return new PanelNode.Box(c, horizontal, integer(o, "gap", 4, 0, 64), integer(o, "pad", 0, 0, 64),
                    choice(o, "justify", PanelNode.Justify.START, PanelNode.Justify.class), horizontal && bool(o, "wrap", false),
                    scroll, maxHeight, children(o, "children", depth));
        }

        PanelNode.Grid grid(PanelNode.Common c, JsonObject o, int depth) {
            int columns = integer(o, "columns", 0, 0, 12);
            int cell = integer(o, "cell", 0, 0, 400);
            if ((columns == 0) == (cell == 0) || (cell != 0 && cell < 16)) throw invalid("grid needs exactly one of columns/cell");
            return new PanelNode.Grid(c, columns, cell, integer(o, "gap", 4, 0, 64), children(o, "children", depth));
        }

        PanelNode.Tabs tabs(PanelNode.Common c, JsonObject o, int depth) {
            JsonArray a = array(o, "tabs", true, MAX_TABS);
            if (a.isEmpty()) throw invalid("tabs empty");
            List<PanelNode.Tabs.Tab> tabs = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonElement e : a) {
                JsonObject t = object(e, "tabs");
                String id = str(t, "id", true, 64);
                if (!PanelIds.element(id) || !ids.add(id)) throw invalid("tab id invalid");
                tabs.add(new PanelNode.Tabs.Tab(id, required(text(t, "label"), "label"), icon(t, "icon"), text(t, "badge"),
                        children(t, "children", depth), bool(t, "lazy", false)));
            }
            String selected = str(o, "selected", false, 64);
            if (selected == null) selected = tabs.getFirst().id();
            if (!ids.contains(selected)) throw invalid("selected unknown");
            return new PanelNode.Tabs(c, tabs, selected);
        }

        PanelNode.Text textNode(PanelNode.Common c, JsonObject o) {
            List<UiText> lines = o.has("lines") ? texts(o, "lines", 64) : List.of(required(text(o, "text"), "text"));
            return new PanelNode.Text(c, lines, choice(o, "style", PanelNode.Text.TextStyle.BODY, PanelNode.Text.TextStyle.class),
                    integer(o, "maxLines", 0, 0, 200), bool(o, "wrap", true));
        }

        PanelNode.Kv kv(PanelNode.Common c, JsonObject o) {
            List<PanelNode.Kv.KvRow> rows = new ArrayList<>();
            for (JsonElement e : array(o, "rows", true, MAX_KV_ROWS)) {
                JsonObject r = object(e, "rows");
                rows.add(new PanelNode.Kv.KvRow(required(text(r, "key"), "key"), text(r, "value"), icon(r, "icon"), tooltip(r)));
            }
            return new PanelNode.Kv(c, rows, integer(o, "keyWidth", 0, 0, 400));
        }

        PanelNode.Progress progress(PanelNode.Common c, JsonObject o) {
            double max = number(o, "max", 1, Double.MIN_VALUE, 1e12);
            return new PanelNode.Progress(c, number(o, "value", 0, 0, 1e12), max, text(o, "label"),
                    color(o, "color", PanelColor.of(PanelColor.Token.ACCENT)), integer(o, "segments", 0, 0, 20), integer(o, "height", 5, 2, 16),
                    number(o, "rate", 0, -1e9, 1e9));
        }

        /** {@code confirm: {title, text, yes, no}} von Knöpfen und (ab Client 0.9.0) Formularen; null ohne. */
        PanelNode.Button.Confirm confirm(JsonObject o) {
            if (!o.has("confirm")) return null;
            JsonObject k = object(o.get("confirm"), "confirm");
            return new PanelNode.Button.Confirm(required(text(k, "title"), "confirm.title"), text(k, "text"), text(k, "yes"), text(k, "no"));
        }

        PanelNode.Timer timer(PanelNode.Common c, JsonObject o) {
            if (!o.has("remainingMs")) throw invalid("remainingMs missing");
            long remaining = longValue(o, "remainingMs", 0, 0, 86_400_000L * 30);
            long total = longValue(o, "totalMs", remaining, 0, 86_400_000L * 30);
            if (total < remaining) throw invalid("totalMs < remainingMs");
            return new PanelNode.Timer(c, remaining, total, choice(o, "format", PanelNode.Timer.Format.MM_SS, PanelNode.Timer.Format.class), bool(o, "bar", false));
        }

        PanelNode.ItemNode item(PanelNode.Common c, JsonObject o) {
            PanelIcon icon = PanelJson.icon(o.get("icon"), "icon");
            int size = integer(o, "size", 16, 16, 32);
            if (size != 16 && size != 24 && size != 32) throw invalid("size must be 16, 24 or 32");
            PanelColor rarity = o.has("rarity") ? PanelColor.parse("rarity." + str(o, "rarity", true, 16)) : null;
            if (o.has("rarity") && rarity == null) throw invalid("rarity invalid");
            return new PanelNode.ItemNode(c, icon, size, bool(o, "slot", false), rarity == null ? null : rarity.token(), bool(o, "action", false));
        }

        PanelNode.Image image(PanelNode.Common c, JsonObject o) {
            JsonObject src = object(o.get("src"), "src");
            PanelNode.Image.Source source;
            if (src.size() != 1) throw invalid("src needs exactly one of image/sprite/blob");
            if (src.has("image")) {
                String path = str(src, "image", true, 200);
                if (!Icon.validImagePath(path)) throw invalid("src.image invalid");
                source = new PanelNode.Image.Source(PanelNode.Image.Source.Kind.WEB, path);
            } else if (src.has("sprite")) {
                String sprite = str(src, "sprite", true, 196);
                if (!Icon.validResource(sprite)) throw invalid("src.sprite invalid");
                source = new PanelNode.Image.Source(PanelNode.Image.Source.Kind.SPRITE, sprite);
            } else if (src.has("blob")) {
                String hash = str(src, "blob", true, 64);
                if (!PanelIds.sha256Hex(hash)) throw invalid("src.blob invalid");
                source = new PanelNode.Image.Source(PanelNode.Image.Source.Kind.BLOB, hash);
            } else throw invalid("src needs exactly one of image/sprite/blob");
            return new PanelNode.Image(c, source, integer(o, "width", 16, 1, 256), integer(o, "height", 16, 1, 256),
                    bool(o, "pixelated", true), text(o, "alt"));
        }

        static UiText required(UiText t, String f) {
            if (t.isEmpty()) throw invalid(f + " missing");
            return t;
        }

    }
}
