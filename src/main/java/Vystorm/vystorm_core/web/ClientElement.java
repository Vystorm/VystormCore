package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.clientproto.ui.UiText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Typed building blocks for native elements of the Vystorm client mod ({@code PROTOCOL.md} §4 in the repo
 * Vystorm/vystorm-client), sent through {@link ClientUi}. A builder contains only the fields that were set
 * (the client uses its defaults for the rest); that is why the same builder works as a PATCH:
 *
 * <pre>{@code
 * ClientUi ui = ClientUi.of(plugin);
 * ui.put(player, ClientElement.bar("xp").anchor(ClientElement.Anchor.BOTTOM).offset(0, 52)
 *         .value(340).max(1000).color("#FF7CFC00").label("Lv 12 · 340/1000 XP").segments(10));
 * ui.patch(player, ClientElement.bar("xp").value(347).label("Lv 12 · 347/1000 XP"));   // only value + label
 * ui.toast(player, ClientElement.toast("Character level 13").text("+1 skill point").style(ClientElement.Toast.Style.SUCCESS));
 * }</pre>
 *
 * IDs are local ({@code [a-z0-9][a-z0-9_.:/-]*}); {@link ClientUi} prefixes the namespace of the plugin
 * ({@code xp} → {@code vystormskills:xp}). Elements are checked when sent, with the same schema as in the client
 * (limits are rejected, not truncated). Not thread-safe; do not share builders between threads.
 *
 * @since 0.20.0
 */
public final class ClientElement {
    private ClientElement() {}

    /** Reference point of a HUD element; {@code offset} counts inwards from the anchor. */
    public enum Anchor {
        TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT;

        String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    // ------------------------------------------------------------------ entry points

    /** Box with a title and lines (quest tracker, character values …). */
    public static Panel panel(String id) { return new Panel(id); }

    /** Progress bar (XP, reputation, cooldown …). */
    public static Bar bar(String id) { return new Bar(id); }

    /** Bar with 1..12 slots (skills with cooldown, key, count). */
    public static Slots slots(String id) { return new Slots(id); }

    /** World marker at a position. */
    public static Marker marker(String id) { return new Marker(id); }

    /** Tooltip rule: appends lines to matching items. */
    public static Tooltip tooltip(String id) { return new Tooltip(id); }

    /**
     * HUD list: title and up to 12 rows with icon, text, right-hand text and a small bar (quest goals,
     * party members, resources). Needs {@link ClientServices#CAP_UI_EXTENSIONS} (client ≥ 0.9.0).
     * @since 0.26.4
     */
    public static HudList list(String id) { return new HudList(id); }

    /** A row for {@link HudList}. @since 0.26.4 */
    public static ListRow row(String label) { return new ListRow().label(label); }

    /** A row for {@link HudList} with formatted text. @since 0.26.4 */
    public static ListRow row(Text label) { return new ListRow().label(label); }

    /**
     * Placeholder for tooltip lines: the client inserts the value from {@code minecraft:custom_data} of the item, e.g.
     * {@code "Quality: " + ClientElement.data("PublicBukkitValues.vystorm_items:quality")}. Path: 1..4 segments
     * {@code [A-Za-z0-9_:-]}, separated by {@code .}. Lines whose placeholders are all missing are dropped. Needs
     * {@link ClientServices#CAP_UI_EXTENSIONS}.
     * @since 0.26.4
     */
    public static String data(String path) { return "{data:" + path + "}"; }

    /** A slot for {@link Slots}. */
    public static Slot slot() { return new Slot(); }

    /** One-time message (no element, no ID). */
    public static Toast toast(String title) { return new Toast(Text.plain(title)); }

    /** One-time message with a formatted title. */
    public static Toast toast(Text title) { return new Toast(title); }

    // ------------------------------------------------------------------ text and icon

    /**
     * Formatted text without click/hover actions: segments with colour ({@code #RRGGBB}, {@code #AARRGGBB} or
     * Minecraft name such as {@code gold}) and style. At most 16 segments and 256 characters. Control characters, {@code §},
     * Unicode format characters (bidi, zero width …) and line separators are removed as in the client.
     */
    public static final class Text {
        private final List<JsonObject> segments = new ArrayList<>();

        private Text() {}

        public static Text plain(String text) { return new Text().then(text); }

        public static Text of(String text, String color) { return new Text().then(text, color); }

        /** Appends a segment in the element's default colour. */
        public Text then(String text) { return then(text, null); }

        public Text then(String text, String color) {
            JsonObject s = new JsonObject();
            s.addProperty("text", UiText.sanitize(text));
            if (color != null) s.addProperty("color", color);
            segments.add(s);
            return this;
        }

        public Text bold() { return flag("bold"); }
        public Text italic() { return flag("italic"); }
        public Text underlined() { return flag("underlined"); }
        public Text strikethrough() { return flag("strikethrough"); }

        private Text flag(String name) {
            if (!segments.isEmpty()) segments.getLast().addProperty(name, true);
            return this;
        }

        JsonElement json() {
            if (segments.size() == 1 && segments.getFirst().size() == 1) return segments.getFirst().get("text").deepCopy();
            JsonArray a = new JsonArray();
            for (JsonObject s : segments) a.add(s.deepCopy());
            return a;
        }
    }

    /** Icon: item (optionally with a model from the resource pack), GUI sprite or PNG from the web origin. */
    public static final class Icon {
        private final JsonObject json;

        private Icon(JsonObject json) { this.json = json; }

        /** {@code minecraft:diamond_sword} etc. */
        public static Icon item(String itemId) { return item(itemId, null); }

        public static Icon item(Material material) { return item(material.getKey().toString()); }

        /**
         * Icon of a real stack: item ID plus its {@code minecraft:item_model} (own models from the
         * resource pack, e.g. of Vystorm items/MMOItems). Other components (custom model data, dye, glint) are not
         * carried over – for items whose look depends on them, set an own {@code item_model}. @since 0.26.4
         */
        public static Icon item(org.bukkit.inventory.ItemStack stack) {
            java.util.Objects.requireNonNull(stack, "stack");
            String model = null;
            org.bukkit.inventory.meta.ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
            model = Vystorm.vystorm_core.compat.ItemCompat.model(meta);
            return item(stack.getType().getKey().toString(), model);
        }

        /** Item with an item model from the server resource pack, e.g. {@code vystorm:items/flame_blade}. */
        public static Icon item(String itemId, String model) {
            JsonObject o = new JsonObject();
            o.addProperty("item", itemId);
            if (model != null) o.addProperty("model", model);
            return new Icon(o);
        }

        /** GUI sprite, e.g. {@code minecraft:hud/heart/full}. */
        public static Icon sprite(String sprite) {
            JsonObject o = new JsonObject();
            o.addProperty("sprite", sprite);
            return new Icon(o);
        }

        /** PNG path on the web platform, e.g. {@code /m/items/icons/flame.png} (≤ 64 KiB, ≤ 128 px). */
        public static Icon image(String path) {
            JsonObject o = new JsonObject();
            o.addProperty("image", path);
            return new Icon(o);
        }

        JsonObject json() { return json.deepCopy(); }
    }

    // ------------------------------------------------------------------ base

    /** Common base of all elements. */
    public abstract static sealed class Element<B extends Element<B>> permits Hud, Marker, Tooltip {
        final JsonObject json = new JsonObject();

        Element(String id, String type) {
            json.addProperty("id", Objects.requireNonNull(id, "id"));
            json.addProperty("type", type);
        }

        @SuppressWarnings("unchecked")
        B self() { return (B) this; }

        /** Local ID (without the plugin's namespace). */
        public String id() { return json.get("id").getAsString(); }

        public String type() { return json.get("type").getAsString(); }

        /** Lifetime from receipt in ms (0 = until removed). */
        public B ttl(long millis) { json.addProperty("ttl", millis); return self(); }

        /** The set fields as JSON (copy). */
        public JsonObject toJson() { return json.deepCopy(); }

        B set(String field, String value) { if (value != null) json.addProperty(field, value); else json.remove(field); return self(); }
        B set(String field, Number value) { json.addProperty(field, value); return self(); }
        B set(String field, boolean value) { json.addProperty(field, value); return self(); }
        B set(String field, Text value) { if (value != null) json.add(field, value.json()); else json.remove(field); return self(); }
        B set(String field, Icon value) { if (value != null) json.add(field, value.json()); else json.remove(field); return self(); }
    }

    /** HUD elements (panel, bar, slots) share the limit of 32 per player. */
    public abstract static sealed class Hud<B extends Hud<B>> extends Element<B> permits Panel, Bar, Slots, HudList {
        Hud(String id, String type) { super(id, type); }

        public B anchor(Anchor anchor) { return set("anchor", anchor.wire()); }

        /** GUI pixels inwards from the anchor (±4000). */
        public B offset(int x, int y) { set("x", x); return set("y", y); }

        /** Drawing order (±1000). */
        public B order(int order) { return set("order", order); }

        /**
         * Stack with other {@code stack} elements of the same anchor (also of other plugins) instead of overlapping: each
         * sits at its offset unless it would cover the previous one (by {@link #order}) – then directly behind it (2 px).
         * Top/middle anchors stack downwards, bottom anchors upwards. Older clients ignore the field (Core leaves it
         * out there). @since 0.26.4
         */
        public B stack(boolean stack) { return set("stack", stack); }
    }

    // ------------------------------------------------------------------ types

    public static final class Panel extends Hud<Panel> {
        Panel(String id) { super(id, "panel"); }

        /** 0 = automatic, otherwise 16..320; with a fixed width lines are wrapped. */
        public Panel width(int width) { return set("width", width); }
        public Panel padding(int padding) { return set("padding", padding); }
        public Panel background(String color) { return set("background", color); }
        public Panel border(String color) { return set("border", color); }
        public Panel textColor(String color) { return set("textColor", color); }
        public Panel title(String title) { return set("title", Text.plain(title)); }
        public Panel title(Text title) { return set("title", title); }
        public Panel icon(Icon icon) { return set("icon", icon); }

        /** Appends a line (at most 12). */
        public Panel line(String line) { return line(Text.plain(line)); }

        public Panel line(Text line) {
            JsonArray lines = json.has("lines") ? json.getAsJsonArray("lines") : new JsonArray();
            lines.add(line.json());
            json.add("lines", lines);
            return this;
        }

        /** Replaces all lines (may be empty). */
        public Panel lines(List<Text> lines) {
            JsonArray a = new JsonArray();
            for (Text t : lines) a.add(t.json());
            json.add("lines", a);
            return this;
        }
    }

    public static final class Bar extends Hud<Bar> {
        Bar(String id) { super(id, "bar"); }

        public Bar width(int width) { return set("width", width); }
        public Bar height(int height) { return set("height", height); }
        public Bar value(double value) { return set("value", value); }
        public Bar max(double max) { return set("max", max); }
        public Bar color(String color) { return set("color", color); }
        public Bar background(String color) { return set("background", color); }
        public Bar textColor(String color) { return set("textColor", color); }
        public Bar label(String label) { return set("label", Text.plain(label)); }
        public Bar label(Text label) { return set("label", label); }
        public Bar segments(int segments) { return set("segments", segments); }

        /** Placeholder in the {@link #label}: current value, rounded. Needs {@code CAP_UI_EXTENSIONS}. @since 0.26.4 */
        public static final String VALUE = "{value}";
        /** Placeholder in the {@link #label}: maximum, rounded. @since 0.26.4 */
        public static final String MAX = "{max}";
        /** Placeholder in the {@link #label}: current value as seconds, {@code m:ss} or {@code h:mm:ss} (rounded up). @since 0.26.4 */
        public static final String TIME = "{time}";

        /**
         * The client keeps the value running from receipt with {@code unitsPerSecond} (also negative, limited to
         * 0..max), e.g. forge temperature, regeneration, countdown; every PATCH sets the value again. Older clients
         * show the fixed value (Core leaves {@code rate} out there). @since 0.26.4
         */
        public Bar rate(double unitsPerSecond) { return set("rate", unitsPerSecond); }

        /**
         * Countdown: {@code value} = remaining time in seconds, {@code max} = total time, {@code rate} = −1. With
         * {@code label("Wave in " + Bar.TIME)} the text counts down as well (placeholders need
         * {@code CAP_UI_EXTENSIONS}, otherwise {@link ClientUi#put} returns false). @since 0.26.4
         */
        public Bar countdown(long remainingMs, long totalMs) {
            double total = Math.max(1, totalMs) / 1000.0;
            set("max", total);
            set("value", Math.max(0, Math.min(total, remainingMs / 1000.0)));
            return set("rate", -1);
        }
    }

    public static final class Slots extends Hud<Slots> {
        Slots(String id) { super(id, "slots"); }

        /** Slot size in GUI pixels (16..32). */
        public Slots size(int size) { return set("size", size); }
        public Slots gap(int gap) { return set("gap", gap); }

        /** Appends a slot (at most 12). */
        public Slots slot(Slot slot) {
            JsonArray a = json.has("slots") ? json.getAsJsonArray("slots") : new JsonArray();
            a.add(slot.json.deepCopy());
            json.add("slots", a);
            return this;
        }

        /** Replaces all slots. */
        public Slots slots(List<Slot> slots) {
            JsonArray a = new JsonArray();
            for (Slot s : slots) a.add(s.json.deepCopy());
            json.add("slots", a);
            return this;
        }
    }

    /** One slot of a {@link Slots} bar. The client counts the cooldown down itself from receipt. */
    public static final class Slot {
        final JsonObject json = new JsonObject();

        Slot() {}

        public Slot icon(Icon icon) { json.add("icon", icon.json()); return this; }

        /** Key hint, at most 4 characters. */
        public Slot key(String key) { json.addProperty("key", UiText.sanitize(key)); return this; }
        public Slot count(int count) { json.addProperty("count", count); return this; }

        /** Remaining and total cooldown in ms ({@code total ≥ remaining}); 0/0 = ready. */
        public Slot cooldown(long remainingMs, long totalMs) {
            long left = Math.max(0, remainingMs);
            json.addProperty("cooldownMs", left);
            json.addProperty("cooldownTotalMs", Math.max(left, totalMs));
            return this;
        }

        /** Highlighted (e.g. the skill is running). */
        public Slot active(boolean active) { json.addProperty("active", active); return this; }
        public Slot label(String label) { json.add("label", Text.plain(label).json()); return this; }
        public Slot label(Text label) { json.add("label", label.json()); return this; }
    }

    public static final class Marker extends Element<Marker> {
        Marker(String id) { super(id, "marker"); }

        /** Position (required). */
        public Marker at(double x, double y, double z) { set("x", x); set("y", y); return set("z", z); }

        /** Only in this world, e.g. {@code minecraft:overworld}; not set = any. */
        /**
         * Dimension key such as {@code minecraft:overworld}. Since 0.26.4 a plain Bukkit world name ({@code "world"}) is
         * converted to that world's key when the world is loaded, because the client only knows dimension keys.
         */
        public Marker world(String dimension) { return set("world", dimensionKey(dimension)); }
        public Marker world(World world) { return set("world", world.getKey().toString()); }

        static String dimensionKey(String dimension) {
            if (dimension == null || dimension.isEmpty() || dimension.indexOf(':') >= 0) return dimension;
            try {
                World world = org.bukkit.Bukkit.getServer() == null ? null : org.bukkit.Bukkit.getWorld(dimension);
                return world == null ? dimension : world.getKey().toString();
            } catch (RuntimeException | LinkageError noServer) {
                return dimension;
            }
        }
        public Marker label(String label) { return set("label", Text.plain(label)); }
        public Marker label(Text label) { return set("label", label); }
        public Marker color(String color) { return set("color", color); }
        public Marker icon(Icon icon) { return set("icon", icon); }
        public Marker maxDistance(double blocks) { return set("maxDistance", blocks); }
        public Marker showDistance(boolean show) { return set("showDistance", show); }
        public Marker clampToScreen(boolean clamp) { return set("clampToScreen", clamp); }

        /**
         * Attaches the marker to an entity: the client follows its (interpolated) position while it is loaded there
         * (top edge + {@link #yOffset}); otherwise {@link #at} counts as the last known position – it stays required.
         * Older clients show the marker at {@code at}. @since 0.26.4
         */
        public Marker entity(java.util.UUID entity) { return set("entity", entity == null ? null : entity.toString()); }

        public Marker entity(org.bukkit.entity.Entity entity) { return entity(entity == null ? null : entity.getUniqueId()); }

        /** Blocks above the reference point (entity top edge, terrain or {@code y}), ±64. @since 0.26.4 */
        public Marker yOffset(double blocks) { return set("yOffset", blocks); }

        /**
         * Puts the marker on the ground: the client takes the terrain height at {@code x/z} (if the chunk is loaded there),
         * otherwise {@code y}. For targets in unloaded chunks whose height the server does not know. @since 0.26.4
         */
        public Marker ground(boolean ground) { return set("ground", ground); }
    }

    public static final class Tooltip extends Element<Tooltip> {
        Tooltip(String id) { super(id, "tooltip"); }

        /** Matches this item ({@code minecraft:diamond_sword}). */
        public Tooltip matchItem(String itemId) { match().addProperty("item", itemId); return this; }
        public Tooltip matchItem(Material material) { return matchItem(material.getKey().toString()); }

        /**
         * Matches a string value in {@code minecraft:custom_data}, e.g.
         * {@code matchCustomData("PublicBukkitValues.vystorm_items:id", "flame_blade")}.
         */
        public Tooltip matchCustomData(String path, String equals) {
            JsonObject cd = new JsonObject();
            cd.addProperty("path", path);
            cd.addProperty("equals", equals);
            return condition(cd);
        }

        /**
         * Matches if {@code minecraft:custom_data} has a value at the path (any type). Needs
         * {@code CAP_UI_EXTENSIONS}. @since 0.26.4
         */
        public Tooltip matchCustomDataPresent(String path) {
            JsonObject cd = new JsonObject();
            cd.addProperty("path", path);
            cd.addProperty("present", true);
            return condition(cd);
        }

        /**
         * Matches if the string at the path is the UUID of the viewing player (e.g. "bound to you"). The client
         * compares with its own UUID; the server learns nothing. Needs {@code CAP_UI_EXTENSIONS}. @since 0.26.4
         */
        public Tooltip matchCustomDataViewer(String path) {
            JsonObject cd = new JsonObject();
            cd.addProperty("path", path);
            cd.addProperty("equalsViewer", true);
            return condition(cd);
        }

        /**
         * Several {@code matchCustomData*} calls are combined with AND (at most 4; more than one condition needs
         * {@code CAP_UI_EXTENSIONS}).
         */
        private Tooltip condition(JsonObject cd) {
            JsonObject match = match();
            JsonElement existing = match.get("customData");
            if (existing == null) match.add("customData", cd);
            else if (existing.isJsonArray()) existing.getAsJsonArray().add(cd);
            else {
                JsonArray all = new JsonArray();
                all.add(existing);
                all.add(cd);
                match.add("customData", all);
            }
            return this;
        }

        /** Appends a line (1..8). */
        public Tooltip line(String line) { return line(Text.plain(line)); }

        public Tooltip line(Text line) {
            JsonArray lines = json.has("lines") ? json.getAsJsonArray("lines") : new JsonArray();
            lines.add(line.json());
            json.add("lines", lines);
            return this;
        }

        private JsonObject match() {
            if (!json.has("match")) json.add("match", new JsonObject());
            return json.getAsJsonObject("match");
        }
    }

    /**
     * HUD list (client ≥ 0.9.0, {@link ClientServices#CAP_UI_EXTENSIONS}). A PATCH replaces {@code rows} as a whole
     * (other fields stay): {@code ui.patch(p, ClientElement.list("quests").rows(newRows))}.
     * @since 0.26.4
     */
    public static final class HudList extends Hud<HudList> {
        HudList(String id) { super(id, "list"); }

        /** 0 = automatic, otherwise 40..320; labels that are too long are shortened. */
        public HudList width(int width) { return set("width", width); }
        public HudList padding(int padding) { return set("padding", padding); }
        public HudList background(String color) { return set("background", color); }
        public HudList border(String color) { return set("border", color); }
        public HudList textColor(String color) { return set("textColor", color); }
        /** Colour of the right-hand texts (default {@code #FFC8C8C8}). */
        public HudList rightColor(String color) { return set("rightColor", color); }
        public HudList title(String title) { return set("title", Text.plain(title)); }
        public HudList title(Text title) { return set("title", title); }

        /** Appends a row (at most 12). */
        public HudList row(ListRow row) {
            JsonArray a = json.has("rows") ? json.getAsJsonArray("rows") : new JsonArray();
            a.add(row.json.deepCopy());
            json.add("rows", a);
            return this;
        }

        /** Replaces all rows (may be empty). */
        public HudList rows(List<ListRow> rows) {
            JsonArray a = new JsonArray();
            for (ListRow r : rows) a.add(r.json.deepCopy());
            json.add("rows", a);
            return this;
        }
    }

    /** Row of a {@link HudList}: icon, text, right-hand text, small bar (all optional, not all empty). @since 0.26.4 */
    public static final class ListRow {
        final JsonObject json = new JsonObject();

        ListRow() {}

        public ListRow icon(Icon icon) { json.add("icon", icon.json()); return this; }
        public ListRow label(String label) { json.add("label", Text.plain(label).json()); return this; }
        public ListRow label(Text label) { json.add("label", label.json()); return this; }
        public ListRow right(String right) { json.add("right", Text.plain(right).json()); return this; }
        public ListRow right(Text right) { json.add("right", right.json()); return this; }

        /**
         * Small bar below the row; {@code color} null = default green. With a bar, {@link #right} may contain the placeholders
         * {@link Bar#VALUE}, {@link Bar#MAX} and {@link Bar#TIME} (value of this bar).
         */
        public ListRow bar(double value, double max, String color) {
            JsonObject b = new JsonObject();
            b.addProperty("value", value);
            b.addProperty("max", max);
            if (color != null) b.addProperty("color", color);
            json.add("bar", b);
            return this;
        }

        /**
         * The client keeps the bar value of this row running from receipt with {@code unitsPerSecond} (also negative,
         * 0..max), including placeholders in the right-hand text; every PUT/PATCH of the list sets it again.
         *
         * @throws IllegalStateException without a previous {@link #bar}
         * @since 0.26.4
         */
        public ListRow rate(double unitsPerSecond) {
            if (!json.has("bar")) throw new IllegalStateException("bar(...) first");
            json.getAsJsonObject("bar").addProperty("rate", unitsPerSecond);
            return this;
        }

        /**
         * Remaining-time bar: value/maximum in seconds, {@code rate} −1; with {@code right(Bar.TIME)} the right-hand text
         * counts down (e.g. "1:29") without the server patching. @since 0.26.4
         */
        public ListRow countdown(long remainingMs, long totalMs, String color) {
            double total = Math.max(1, totalMs) / 1000.0;
            bar(Math.max(0, Math.min(total, remainingMs / 1000.0)), total, color);
            return rate(-1);
        }
    }

    /** One-time message at the top right (at most 3 visible, 5 waiting). */
    public static final class Toast {
        public enum Style { INFO, SUCCESS, WARNING, ERROR }

        final JsonObject json = new JsonObject();

        Toast(Text title) { json.add("title", title.json()); }

        public Toast text(String text) { json.add("text", Text.plain(text).json()); return this; }
        public Toast text(Text text) { json.add("text", text.json()); return this; }
        public Toast icon(Icon icon) { json.add("icon", icon.json()); return this; }

        /** 1000..10000 ms (default 5000). */
        public Toast duration(int millis) { json.addProperty("durationMs", millis); return this; }
        public Toast style(Style style) { json.addProperty("style", style.name().toLowerCase(Locale.ROOT)); return this; }

        /**
         * Label of the action ({@code "[Y] <label>"} on the toast, ≤ 32 characters); without this call the client shows
         * "Accept" in the player's language. Only effective with {@link ClientUi#toast(org.bukkit.entity.Player, Toast,
         * java.util.function.Consumer)}; {@link ClientUi#toast(org.bukkit.entity.Player, Toast)} sends the toast
         * without an action. @since 0.26.4
         */
        public Toast action(String label) { return action(Text.plain(label)); }

        /** Like {@link #action(String)} with formatted text. @since 0.26.4 */
        public Toast action(Text label) {
            JsonObject a = new JsonObject();
            a.add("label", label.json());
            json.add("action", a);
            return this;
        }

        public JsonObject toJson() { return json.deepCopy(); }
    }
}
