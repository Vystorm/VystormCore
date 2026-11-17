package Vystorm.vystorm_core.panel;

import Vystorm.vystorm_core.web.internal.clientproto.panel.PanelIds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.Material;

/**
 * Element factory for panels (spec §3.2, §5). IDs {@code [a-z0-9][a-z0-9_.:/-]{0,63}}; required for everything interactive.
 * Texts: String, {@code Lang.ref} or Adventure {@code Component}. Builders are not thread-safe and are built anew for
 * every render. Handlers only get validated values. Every element has its own builder type that offers only its
 * fields (since 0.25.1). @since 0.25.0
 */
public final class Ui {
    private Ui() {}


    @FunctionalInterface public interface Click { Result handle(PanelAction action); }
    @FunctionalInterface public interface Change<T> { Result handle(PanelAction action, T value); }

    public record Sort(String column, boolean descending) {}

    public enum Align { START, CENTER, END, STRETCH }
    public enum Chars { ANY, NAME, INTEGER, DECIMAL }
    public enum Style { PRIMARY, DEFAULT, DANGER, GHOST }
    public enum Variant { CARD, PLAIN, NOTICE, WARN, ERROR }
    public enum TimerFormat { MM_SS, HH_MM_SS, RELATIVE }
    public enum TextStyle { BODY, MUTED, OK, WARN, ERROR }
    /** Distribution of the children in {@link #row()}/{@link #col()}. @since 0.25.1 */
    public enum Justify { START, CENTER, END, BETWEEN }

    static String checkId(String id) {
        if (!PanelIds.element(id)) throw new IllegalArgumentException("invalid panel element id: " + id);
        return id;
    }

    // ------------------------------------------------------------------ base

    /** Common fields of all elements. */
    public abstract static class Element<T extends Element<T>> {
        final String type;
        String id;
        final Map<String, Object> fields = new LinkedHashMap<>();
        final List<Object> tooltip = new ArrayList<>();
        final List<Element<?>> children = new ArrayList<>();
        Object handler;   // Click or Change<?>
        int handlerKind;  // PanelLimits.KIND_*

        Element(String type, String id) {
            this.type = type;
            this.id = id == null ? null : checkId(id);
        }

        @SuppressWarnings("unchecked") T self() { return (T) this; }

        /** Optional ID for non-interactive elements (for targeted patches). */
        public T id(String id) { this.id = checkId(id); return self(); }
        /** Up to 8 lines. */
        public T tooltip(Object... lines) { for (Object l : lines) tooltip.add(Objects.requireNonNull(l)); return self(); }
        public T visible(boolean v) { fields.put("visible", v); return self(); }
        public T disabled(boolean v) { fields.put("disabled", v); return self(); }
        public T width(int px) { fields.put("width", px); return self(); }
        public T widthPercent(int percent) { fields.put("width", percent + "%"); return self(); }
        public T fill() { fields.put("width", "fill"); return self(); }
        public T grow(int weight) { fields.put("grow", weight); return self(); }
        public T minWidth(int px) { fields.put("minWidth", px); return self(); }
        public T maxWidth(int px) { fields.put("maxWidth", px); return self(); }
        public T align(Align a) { fields.put("align", a.name().toLowerCase(Locale.ROOT)); return self(); }

        /** Texts (String/Component) are only resolved per player when written. */
        T text(String field, Object text) { fields.put(field, new TextRef(text)); return self(); }

        T handler(int kind, Object handler) { this.handlerKind = kind; this.handler = Objects.requireNonNull(handler); return self(); }
    }

    /** Marks a text field (String/Lang.ref/Component). */
    record TextRef(Object value) {}

    /** Container with children. */
    public abstract static class Container<T extends Container<T>> extends Element<T> {
        Container(String type, String id) { super(type, id); }

        public T children(Element<?>... elements) { for (Element<?> e : elements) children.add(Objects.requireNonNull(e)); return self(); }
        public T child(Element<?> element) { children.add(Objects.requireNonNull(element)); return self(); }
    }

    // ------------------------------------------------------------------ icons

    /** Icon ({@code item}, {@code sprite}, {@code image}). */
    public sealed interface Icon permits ItemIcon, SpriteIcon, ImageIcon {}

    public record SpriteIcon(String sprite) implements Icon {}

    public record ImageIcon(String webPath) implements Icon {}

    /** Item as an icon <b>and</b> as an {@code item} element. */
    public static final class ItemIcon extends Element<ItemIcon> implements Icon {
        final String item;
        final String model;
        int count;
        boolean glint;

        ItemIcon(String item, String model) {
            super("item", null);
            this.item = item;
            this.model = model;
        }

        public ItemIcon count(int count) { this.count = count; return this; }
        public ItemIcon glint() { this.glint = true; return this; }
        public ItemIcon size(int size) { fields.put("size", size); return this; }
        public ItemIcon slot() { fields.put("slot", true); return this; }
        public ItemIcon rarity(String rarity) { fields.put("rarity", rarity); return this; }
        /** Clickable (needs {@link #id(String)}). */
        public ItemIcon onClick(Click click) { fields.put("action", true); return handler(1, click); }
    }

    public static ItemIcon item(Material material) { return new ItemIcon(material.getKey().toString(), null); }

    /**
     * Icon/element of a real stack: item ID, {@code minecraft:item_model} (own models), count and
     * enchantment glint (enchantments or glint override). Custom model data and dye are not carried over; for own
     * items set an {@code item_model}. @since 0.26.4
     */
    public static ItemIcon item(org.bukkit.inventory.ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        org.bukkit.inventory.meta.ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        String model = Vystorm.vystorm_core.compat.ItemCompat.model(meta);
        ItemIcon icon = new ItemIcon(stack.getType().getKey().toString(), model);
        if (stack.getAmount() > 1) icon.count(stack.getAmount());
        if (meta != null && (meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : meta.hasEnchants())) icon.glint();
        return icon;
    }
    public static ItemIcon item(String itemId, String model) { return new ItemIcon(itemId, model); }
    public static SpriteIcon spriteIcon(String sprite) { return new SpriteIcon(sprite); }
    public static ImageIcon imageIcon(String webPath) { return new ImageIcon(webPath); }

    // ------------------------------------------------------------------ Container

    public static final class Section extends Container<Section> {
        Section(Object title) { super("section", null); text("title", title); }
        public Section variant(Variant v) { fields.put("variant", v.name().toLowerCase(Locale.ROOT)); return this; }
        public Section collapsible(boolean collapsed) { fields.put("collapsible", true); fields.put("collapsed", collapsed); return this; }
    }

    public static final class Box extends Container<Box> {
        Box(String type) { super(type, null); }
        public Box gap(int px) { fields.put("gap", px); return this; }
        public Box pad(int px) { fields.put("pad", px); return this; }
        public Box justify(Justify justify) { fields.put("justify", justify.name().toLowerCase(Locale.ROOT)); return this; }
        public Box wrap() { fields.put("wrap", true); return this; }
        /** Vertically scrollable with a maximum height ({@code col} only). */
        public Box scrollY(int maxHeight) { fields.put("scroll", "y"); fields.put("maxHeight", maxHeight); return this; }
    }

    public static final class Grid extends Container<Grid> {
        Grid() { super("grid", null); }
        public Grid gap(int px) { fields.put("gap", px); return this; }
    }

    public static final class Tabs extends Element<Tabs> {
        final List<Object[]> tabs = new ArrayList<>();   // {id, label, lazy, List<Element<?>>}

        Tabs(String id) { super("tabs", id); }

        public Tabs tab(String id, Object label, Element<?>... children) {
            tabs.add(new Object[]{checkId(id), label, false, List.of(children)});
            return this;
        }

        /** Tab whose content only arrives when it is opened: {@code onTab} → then {@code refresh()}. */
        public Tabs lazyTab(String id, Object label) {
            tabs.add(new Object[]{checkId(id), label, true, List.of()});
            return this;
        }

        public Tabs select(String tabId) { fields.put("selected", tabId); return this; }
        public Tabs onTab(Change<String> handler) { return handler(6, handler); }
    }

    public static Section section(Object title) { return new Section(title); }
    public static Box row() { return new Box("row"); }
    public static Box col() { return new Box("col"); }
    public static Grid grid(int columns) { Grid g = new Grid(); g.fields.put("columns", columns); return g; }
    public static Grid gridCells(int minCellWidth) { Grid g = new Grid(); g.fields.put("cell", minCellWidth); return g; }
    public static Tabs tabs(String id) { return new Tabs(id); }

    // ------------------------------------------------------------------ display

    /** Heading (level 1–3). @since 0.25.1 */
    public static final class Heading extends Element<Heading> {
        Heading() { super("heading", null); }
        public Heading level(int level) { fields.put("level", level); return this; }
    }

    /** Body text (one or more lines). @since 0.25.1 */
    public static final class Text extends Element<Text> {
        Text() { super("text", null); }
        public Text style(TextStyle s) { fields.put("style", s.name().toLowerCase(Locale.ROOT)); return this; }
        public Text maxLines(int n) { fields.put("maxLines", n); return this; }
        public Text nowrap() { fields.put("wrap", false); return this; }
    }

    /** Badge; colour {@code #RRGGBB} or theme name ({@code ok}, {@code warn}, {@code rarity.epic} …). @since 0.25.1 */
    public static final class Badge extends Element<Badge> {
        Badge() { super("badge", null); }
        public Badge color(String color) { fields.put("color", color); return this; }
    }

    /** Colour swatch. @since 0.25.1 */
    public static final class Swatch extends Element<Swatch> {
        Swatch() { super("swatch", null); }
        public Swatch size(int px) { fields.put("size", px); return this; }
    }

    /** Divider line (thickness 1–8). @since 0.25.1 */
    public static final class Divider extends Element<Divider> {
        Divider() { super("divider", null); }
        public Divider size(int px) { fields.put("size", px); return this; }
    }

    /** Empty space. @since 0.25.1 */
    public static final class Spacer extends Element<Spacer> {
        Spacer() { super("spacer", null); }
    }

    /** Progress bar. @since 0.25.1 */
    public static final class Progress extends Element<Progress> {
        Progress() { super("progress", null); }
        public Progress label(Object label) { return text("label", label); }
        public Progress color(String color) { fields.put("color", color); return this; }
        public Progress segments(int n) { fields.put("segments", n); return this; }
        public Progress height(int px) { fields.put("height", px); return this; }
        /**
         * The value keeps running on the client from receipt with {@code unitsPerSecond} (also negative, limited to 0..max) –
         * e.g. forge temperature or regeneration without constant refreshes. Client &lt; 0.9.0 shows the fixed value.
         * @since 0.26.4
         */
        public Progress rate(double unitsPerSecond) { fields.put("rate", unitsPerSecond); return this; }
    }

    /** Countdown, counted by the client itself. @since 0.25.1 */
    public static final class Timer extends Element<Timer> {
        Timer() { super("timer", null); }
        public Timer format(TimerFormat f) { fields.put("format", switch (f) { case MM_SS -> "mm:ss"; case HH_MM_SS -> "hh:mm:ss"; case RELATIVE -> "relative"; }); return this; }
        public Timer bar() { fields.put("bar", true); return this; }
    }

    /** Image from a sprite or a web file. @since 0.25.1 */
    public static final class Image extends Element<Image> {
        Image() { super("image", null); }
        public Image alt(Object alt) { return text("alt", alt); }
        public Image smooth() { fields.put("pixelated", false); return this; }
    }

    public static Heading heading(Object text) { return new Heading().text("text", text); }
    public static Text text(Object text) { return new Text().text("text", text); }
    public static Text lines(Object... lines) { Text s = new Text(); s.fields.put("lines", new TextList(List.of(lines))); return s; }
    public static Badge badge(Object text) { return new Badge().text("text", text); }
    public static Swatch swatch(String color) { Swatch s = new Swatch(); s.fields.put("color", color); return s; }
    public static Divider divider() { return new Divider(); }
    public static Spacer spacer(int size) { Spacer s = new Spacer(); s.fields.put("size", size); return s; }
    public static Progress progress(double value, double max) { Progress s = new Progress(); s.fields.put("value", value); s.fields.put("max", max); return s; }
    public static Timer timer(long remainingMs, long totalMs) { Timer s = new Timer(); s.fields.put("remainingMs", remainingMs); s.fields.put("totalMs", totalMs); return s; }
    public static Image sprite(String sprite, int w, int h) { Image s = new Image(); s.fields.put("src", Map.of("sprite", sprite)); s.fields.put("width", w); s.fields.put("height", h); return s; }
    public static Image webImage(String path, int w, int h) { Image s = new Image(); s.fields.put("src", Map.of("image", path)); s.fields.put("width", w); s.fields.put("height", h); return s; }

    record TextList(List<Object> lines) {}

    public static final class Kv extends Element<Kv> {
        final List<Object[]> rows = new ArrayList<>();
        Kv() { super("kv", null); }
        public Kv row(Object key, Object value) { rows.add(new Object[]{key, value}); return this; }
        public Kv keyWidth(int px) { fields.put("keyWidth", px); return this; }
    }

    public static Kv kv() { return new Kv(); }

    // ------------------------------------------------------------------ table, list

    public static final class Table extends Element<Table> {
        final List<Map<String, Object>> columns = new ArrayList<>();
        final List<TableRow> rows = new ArrayList<>();

        Table(String id) { super("table", id); }

        public Table column(String id, Object label) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", checkId(id));
            c.put("label", new TextRef(label));
            columns.add(c);
            return this;
        }

        /** Makes the last column sortable. */
        public Table sortable() { last().put("sortable", true); return this; }
        public Table alignEnd() { last().put("align", "end"); return this; }
        public Table alignCenter() { last().put("align", "center"); return this; }
        /** Width of the last column in GUI px ({@link #width(int)} is the width of the whole table). @since 0.25.1 */
        public Table columnWidth(int px) { last().put("width", px); return this; }
        /** Width of the last column in percent of the table. @since 0.25.1 */
        public Table columnWidthPercent(int percent) { last().put("width", percent + "%"); return this; }

        private Map<String, Object> last() {
            if (columns.isEmpty()) throw new IllegalStateException("column(...) first");
            return columns.getLast();
        }

        public Table row(TableRow row) { rows.add(row); return this; }
        public <R> Table rows(List<R> items, Function<R, TableRow> mapper) { for (R r : items) rows.add(mapper.apply(r)); return this; }
        public Table empty(Object text) { return text("empty", text); }
        /** Row selected by the server, like {@link ListView#selected}. @since 0.26.4 */
        public Table selected(String key) {
            if (key != null && !PanelIds.rowKey(key)) throw new IllegalArgumentException("invalid row key: " + key);
            if (key == null) fields.remove("selected"); else fields.put("selected", key);
            return this;
        }
        public Table sort(String column, boolean descending) { fields.put("sort", Map.of("column", column, "dir", descending ? "desc" : "asc")); return this; }
        public Table rowLines(int lines) { fields.put("rowLines", lines); return this; }
        /** Server-side paging: sorting and page changes arrive as an action. */
        public Table paging(int page, int pages, Change<Integer> onPage, Change<Sort> onSort) {
            fields.put("paging", Map.of("page", page, "pages", pages));
            this.onPage = onPage;
            this.onSort = onSort;
            return this;
        }
        public Table onSelect(Change<String> onSelect) { fields.put("select", "single"); this.onSelect = onSelect; return this; }

        Change<Integer> onPage;
        Change<Sort> onSort;
        Change<String> onSelect;
    }

    public static final class TableRow {
        final String key;
        final List<Object> cells = new ArrayList<>();   // Cell | Element<?>
        final List<Object> tooltip = new ArrayList<>();

        TableRow(String key) {
            if (!PanelIds.rowKey(key)) throw new IllegalArgumentException("invalid row key: " + key);
            this.key = key;
        }

        public TableRow cell(Object text) { cells.add(text instanceof Cell || text instanceof Element<?> ? text : new Cell(text)); return this; }
        public TableRow tooltip(Object... lines) { tooltip.addAll(List.of(lines)); return this; }
    }

    public static final class Cell {
        final Object text;
        Double sort;
        Cell(Object text) { this.text = Objects.requireNonNull(text); }
        public Cell sort(double value) { this.sort = value; return this; }
    }

    public static Table table(String id) { return new Table(id); }
    /** Table row (not to be confused with {@link #row()}, the flex row). */
    public static TableRow row(String key) { return new TableRow(key); }
    public static Cell cell(Object text) { return new Cell(text); }
    /** Several buttons/badges/items in one cell. */
    public static Box buttons(Element<?>... items) { return row().gap(2).children(items); }

    public static final class ListView extends Element<ListView> {
        final List<ListItem> items = new ArrayList<>();
        ListView(String id) { super("list", id); }
        public ListView item(ListItem item) { items.add(item); return this; }
        public <R> ListView items(List<R> data, Function<R, ListItem> mapper) { for (R r : data) items.add(mapper.apply(r)); return this; }
        public ListView empty(Object text) { return text("empty", text); }
        public ListView onSelect(Change<String> onSelect) { fields.put("select", "single"); return handler(7, onSelect); }
        /**
         * Entry selected by the server (e.g. list + details next to it). A changed value sets the selection on the client,
         * an unchanged one leaves the player's choice in place. Client &lt; 0.9.0 shows the selection only after a click.
         * @since 0.26.4
         */
        public ListView selected(String key) {
            if (key != null && !PanelIds.rowKey(key)) throw new IllegalArgumentException("invalid item key: " + key);
            if (key == null) fields.remove("selected"); else fields.put("selected", key);
            return this;
        }
    }

    public static final class ListItem {
        final String key;
        final Object title;
        Object subtitle, right, badge;
        Icon icon;
        boolean selected;
        final List<Object> tooltip = new ArrayList<>();
        ListItem(String key, Object title) {
            if (!PanelIds.rowKey(key)) throw new IllegalArgumentException("invalid item key: " + key);
            this.key = key;
            this.title = Objects.requireNonNull(title);
        }
        public ListItem subtitle(Object s) { subtitle = s; return this; }
        public ListItem right(Object s) { right = s; return this; }
        public ListItem badge(Object s) { badge = s; return this; }
        public ListItem icon(Icon i) { icon = i; return this; }
        /** Marks this entry as selected (short form of {@link ListView#selected}). @since 0.26.4 */
        public ListItem selected() { selected = true; return this; }
        public ListItem tooltip(Object... lines) { tooltip.addAll(List.of(lines)); return this; }
    }

    public static ListView list(String id) { return new ListView(id); }
    public static ListItem listItem(String key, Object title) { return new ListItem(key, title); }

    // ------------------------------------------------------------------ inputs

    public static final class Button extends Element<Button> {
        Icon icon;
        Button(String id) { super("button", id); }
        public Button label(Object label) { return text("label", label); }
        public Button icon(Icon icon) { this.icon = icon; return this; }
        public Button primary() { fields.put("style", "primary"); return this; }
        public Button danger() { fields.put("style", "danger"); return this; }
        public Button ghost() { fields.put("style", "ghost"); return this; }
        public Button confirm(Object title, Object text) { fields.put("confirm", Map.of("title", new TextRef(title), "text", new TextRef(text))); return this; }
        public Button onClick(Click click) { fields.put("action", true); return handler(1, click); }
        /** Opens a panel (name in the own namespace or full ID {@code ns:name}); {@code arg} is client input at the target. */
        public Button navigate(String panel, String arg) { fields.put("navigate", new Nav(panel, arg == null ? "" : arg)); return this; }
        public Button web(String module, String subPath) { fields.put("web", Map.of("module", module, "page", subPath == null ? "" : subPath)); return this; }
        /**
         * Opens Core's settings panel with only this section ({@code "plugin:section"}, as in
         * {@code SettingsRegistry}); empty = all. Without mod/panels the fallback of the target applies (settings dialog).
         * For a Paper dialog or an own menu: {@link PanelAction#closeAndThen} in the click handler.
         * @since 0.26.4
         */
        public Button openSettings(String sectionKey) { return navigate("vystorm_core:settings", sectionKey == null ? "" : sectionKey); }
    }

    record Nav(String panel, String arg) {}

    public static final class Toggle extends Element<Toggle> {
        Toggle(String id, Object label) { super("toggle", id); text("label", label); }
        public Toggle description(Object d) { return text("description", d); }
        public Toggle value(boolean v) { fields.put("value", v); return this; }
        public Toggle onChange(Change<Boolean> h) { return handler(2, h); }
    }

    public static final class Slider extends Element<Slider> {
        Slider(String id, Object label) { super("slider", id); text("label", label); }
        public Slider range(double min, double max, double step) { fields.put("min", min); fields.put("max", max); fields.put("step", step); return this; }
        public Slider value(double v) { fields.put("value", v); return this; }
        /** Pattern with {@code {v}}, e.g. {@code "{v} blocks"} (slider only). */
        public Slider format(String pattern) { fields.put("format", pattern); return this; }
        public Slider decimals(int n) { fields.put("decimals", n); return this; }
        /** The slider already sends while dragging (≤ 5/s). */
        public Slider live() { fields.put("live", true); return this; }
        public Slider onChange(Change<Double> h) { return handler(2, h); }
    }

    /** Number field with −/+ (for large ranges instead of a slider). @since 0.25.1 */
    public static final class NumberField extends Element<NumberField> {
        NumberField(String id, Object label) { super("number", id); text("label", label); }
        public NumberField range(double min, double max, double step) { fields.put("min", min); fields.put("max", max); fields.put("step", step); return this; }
        public NumberField value(double v) { fields.put("value", v); return this; }
        public NumberField decimals(int n) { fields.put("decimals", n); return this; }
        public NumberField onChange(Change<Double> h) { return handler(2, h); }
    }

    public static final class Input extends Element<Input> {
        Input(String id, Object label) { super("input", id); text("label", label); }
        public Input value(String v) { fields.put("value", v); return this; }
        public Input placeholder(Object p) { return text("placeholder", p); }
        public Input maxLength(int n) { fields.put("maxLength", n); return this; }
        public Input chars(Chars c) { fields.put("chars", c.name().toLowerCase(Locale.ROOT)); return this; }
        public Input submitOnEnter() { fields.put("submit", true); return this; }
        public Input onChange(Change<String> h) { return handler(2, h); }
        /** Search field: sends SEARCH (debounced 300 ms). */
        public Input search(Change<String> h) { fields.put("search", true); return handler(9, h); }
    }

    public static final class Select extends Element<Select> {
        final List<Object[]> options = new ArrayList<>();   // {id, label, icon}
        Select(String id, Object label) { super("select", id); text("label", label); }
        public Select option(String id, Object label) { options.add(new Object[]{id, label, null}); return this; }
        public Select option(String id, Object label, Icon icon) { options.add(new Object[]{id, label, icon}); return this; }
        public Select value(String id) { fields.put("value", id); return this; }
        public Select segmented() { fields.put("style", "segmented"); return this; }
        public Select searchable() { fields.put("search", true); return this; }
        public Select onChange(Change<String> h) { return handler(2, h); }
    }

    public static final class Form extends Container<Form> {
        Form(String id, Object submit) { super("form", id); text("submit", submit); }
        public Form onSubmit(Change<Map<String, Object>> h) { return handler(3, h); }
        /** Asks before submitting like {@link Button#confirm}. Client &lt; 0.9.0 submits without asking. @since 0.26.4 */
        public Form confirm(Object title, Object text) { fields.put("confirm", Map.of("title", new TextRef(title), "text", new TextRef(text))); return this; }
    }

    public static Button button(String id) { return new Button(id); }
    public static Toggle toggle(String id, Object label) { return new Toggle(id, label); }
    public static Slider slider(String id, Object label) { return new Slider(id, label); }
    public static NumberField number(String id, Object label) { return new NumberField(id, label); }
    public static Input input(String id, Object label) { return new Input(id, label); }
    public static Select select(String id, Object label) { return new Select(id, label); }
    public static Form form(String id, Object submit) { return new Form(id, submit); }
}
