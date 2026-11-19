package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core.screen.internal.InventoryListLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The admin config menu: chest pages Overview → Plugin → Category → Options, built from the editor's model, so a plugin
 * needs no per-key code. Values are typed in Core's dialogs (clickable chat where native dialogs are missing). Permissions
 * are rechecked on every click. Server thread only. @since 0.54.0
 */
final class ConfigMenu implements Listener {
    private static final int PER_PAGE = 45, PREVIOUS = 45, BACK = 49, NEXT = 53, LINE = 38, DESCRIPTION_LINES = 6;
    interface Click { void run(Player player, boolean right); }   // package-private for ConfigMenuClickTest
    /** How a slot looks in the list view of the screen (0.66.0): heading or row, name, value, description. */
    record Row(boolean heading, Component name, Component value, Component description) {}
    private record Entry(ItemStack icon, Click click, Row row) {}
    static final class Holder implements InventoryHolder {
        final UUID viewer; final ConfigEditorService.PluginState state;
        final Map<Integer, Click> actions = new HashMap<>();
        Inventory inventory;
        Holder(UUID viewer, ConfigEditorService.PluginState state) { this.viewer = viewer; this.state = state; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final Plugin core;
    private final ConfigEditorService service;
    ConfigMenu(Plugin core, ConfigEditorService service) { this.core = core; this.service = service; }

    static void start(Plugin core) {
        ConfigEditorService service = ConfigEditor.service();
        ConfigMenu menu = new ConfigMenu(core, service);
        service.menu(menu);
        Bukkit.getPluginManager().registerEvents(menu, core);
    }
    private static Lang lang() { return Localization.get().core(); }

    // ------------------------------------------------------------------ pages

    void overview(Player p, int page) {
        List<Entry> entries = new ArrayList<>();
        for (var state : service.visible(p)) {
            String name = state.owner.getName();
            List<Component> lore = new ArrayList<>();
            int changed = service.categories(state).stream().flatMap(c -> c.root().all().stream().map(path -> service.option(state, c.file(), path))).mapToInt(o -> o.changed() ? 1 : 0).sum();
            Component changedText = lang().component(p, "config-menu.plugin-lore", "changed", changed);
            lore.add(changedText);
            boolean pending = FirstRunSetup.pending(name);
            if (pending) lore.add(lang().component(p, "config-menu.setup-open"));
            entries.add(new Entry(item(Material.BOOK, Component.text(name), lore, false), (v, r) -> plugin(v, state, 0),
                    new Row(false, Component.text(name), changedText, pending ? lang().component(p, "config-menu.setup-open") : null)));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.BARRIER, "close", (v, r) -> v.closeInventory()));
        footer.put(47, button(p, Material.CLOCK, "history", (v, r) -> { v.closeInventory(); service.sendHistory(v, null, 1); }));
        if (FirstRunSetup.anyPending(p)) footer.put(51, button(p, Material.NETHER_STAR, "setup", (v, r) -> FirstRunSetup.open(v, null)));
        show(p, null, lang().component(p, "config-menu.overview-title"), entries, page, next -> overview(p, next), footer);
    }

    /**
     * A plugin's page: every category as a heading (click: the category alone), under it the category's own values with
     * their current value and description, then its sections as rows to open (0.66.0; before, only the categories).
     */
    void plugin(Player p, ConfigEditorService.PluginState state, int page) {
        List<Entry> entries = new ArrayList<>();
        Runnable refresh = () -> plugin(p, state, page);
        String name = state.owner.getName();
        for (var category : service.categories(state)) {
            String title = shortTitle(category.title(), name);
            List<Component> lore = new ArrayList<>(description(category.description()));
            int changed = (int) category.root().all().stream().map(path -> service.option(state, category.file(), path)).filter(ConfigEditorService.OptionView::changed).count();
            Component count = lang().component(p, "config-menu.category-lore", "count", category.root().size(), "changed", changed);
            lore.add(count);
            entries.add(new Entry(item(Material.CHEST, Component.text(category.title()), lore, changed > 0),
                    (v, r) -> folder(v, state, category.file(), category.title(), category.root(), 0, () -> plugin(v, state, page)),
                    new Row(true, Component.text(title), count, null)));
            contents(p, state, category.file(), category.title(), title, category.root(), entries, refresh);
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.DARK_OAK_DOOR, "back", (v, r) -> overview(v, 0)));
        footer.put(47, button(p, Material.COMPASS, "search", (v, r) -> search(v, state)));
        footer.put(48, button(p, Material.HOPPER, "only-changed", (v, r) -> changed(v, state, 0)));
        footer.put(50, button(p, Material.CLOCK, "history", (v, r) -> { v.closeInventory(); service.sendHistory(v, name, 1); }));
        if (FirstRunSetup.setupOn() && FirstRunSetup.has(name)) footer.put(51, button(p, Material.NETHER_STAR, "setup", (v, r) -> FirstRunSetup.open(v, name)));
        show(p, state, lang().component(p, "config-menu.plugin-title", "plugin", name), entries, page, next -> plugin(p, state, next), footer);
    }

    /**
     * The own values of {@code folder} (item names "{@code heading} › value" so the chest says where they belong), then
     * one row per sub-section that opens it.
     */
    private void contents(Player p, ConfigEditorService.PluginState state, String file, String title, String heading, ConfigTree.Folder folder,
                          List<Entry> entries, Runnable refresh) {
        for (String path : folder.options()) entries.add(option(p, state, service.option(state, file, path), refresh, heading));
        for (var sub : folder.folders()) entries.add(folderEntry(p, state, file, title, sub, false, refresh));
    }

    /** A sub-section: as a heading (its values follow) or as a row to open it. */
    private Entry folderEntry(Player p, ConfigEditorService.PluginState state, String file, String title, ConfigTree.Folder sub, boolean heading, Runnable back) {
        String name = ConfigEditorService.human(sub.name());
        int changed = (int) sub.all().stream().map(path -> service.option(state, file, path)).filter(ConfigEditorService.OptionView::changed).count();
        String comment = service.comment(state, file, sub.path());
        List<Component> lore = new ArrayList<>(description(comment));
        Component count = lang().component(p, "config-menu.category-lore", "count", sub.size(), "changed", changed);
        lore.add(count);
        Component shownName = Component.text(heading ? name : name + " ›");
        return new Entry(item(Material.CHEST, Component.text(name), lore, changed > 0), (v, r) -> folder(v, state, file, title + " · " + name, sub, 0, back),
                new Row(heading, shownName, count, heading || comment == null || comment.isBlank() ? null : Component.text(comment)));
    }

    private void folder(Player p, ConfigEditorService.PluginState state, String file, String title, ConfigTree.Folder folder, int page, Runnable back) {
        Runnable refresh = () -> folder(p, state, file, title, folder, page, back);
        List<Entry> entries = new ArrayList<>();
        String own = title.contains(" · ") ? title.substring(title.lastIndexOf(" · ") + 3) : title;
        // 0.66.0: the section's own values first, then every sub-section as a heading with its values under it
        for (String path : folder.options()) entries.add(option(p, state, service.option(state, file, path), refresh, own));
        for (var sub : folder.folders()) {
            entries.add(folderEntry(p, state, file, title, sub, true, refresh));
            contents(p, state, file, title + " · " + ConfigEditorService.human(sub.name()), ConfigEditorService.human(sub.name()), sub, entries, refresh);
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.DARK_OAK_DOOR, "back", (v, r) -> back.run()));
        show(p, state, Component.text(shorten(title)), entries, page, next -> folder(p, state, file, title, folder, next, back), footer);
    }

    /** A category title without the plugin's name in front ("VystormChat · Showcase" → "Showcase"). */
    static String shortTitle(String title, String plugin) {
        String prefix = plugin + " · ";
        return title.startsWith(prefix) && title.length() > prefix.length() ? title.substring(prefix.length()) : title;
    }

    private void search(Player p, ConfigEditorService.PluginState state) {
        p.closeInventory();
        prompt(p, state, "config-menu.search", "", text -> {
            String query = text.toLowerCase(Locale.ROOT).strip();
            if (query.isEmpty()) { plugin(p, state, 0); return; }
            results(p, state, lang().component(p, "config-menu.search-title", "query", shorten(query)), query, false, 0);
        }, () -> plugin(p, state, 0));
    }
    private void changed(Player p, ConfigEditorService.PluginState state, int page) {
        results(p, state, lang().component(p, "config-menu.only-changed"), "", true, page);
    }
    private void results(Player p, ConfigEditorService.PluginState state, Component title, String query, boolean changedOnly, int page) {
        Runnable refresh = () -> results(p, state, title, query, changedOnly, page);
        List<Entry> entries = new ArrayList<>();
        for (var category : service.categories(state)) for (String path : category.root().all()) {
            var o = service.option(state, category.file(), path);
            if (changedOnly && !o.changed()) continue;
            if (!query.isEmpty() && !matches(p, o, query)) continue;
            entries.add(option(p, state, o, refresh, shortTitle(category.title(), state.owner.getName())));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.DARK_OAK_DOOR, "back", (v, r) -> plugin(v, state, 0)));
        if (entries.isEmpty()) lang().send(p, "config-menu.no-results");
        show(p, state, title, entries, page, next -> results(p, state, title, query, changedOnly, next), footer);
    }
    private static boolean matches(Player p, ConfigEditorService.OptionView o, String query) {
        return (o.path() + " " + Localization.get().resolve(p, o.label()) + " " + (o.description() == null ? "" : Localization.get().resolve(p, o.description()))).toLowerCase(Locale.ROOT).contains(query);
    }

    // ------------------------------------------------------------------ one option

    /**
     * One value. Chest item: name "{@code heading} › label" (the chest has no headings), lore: current value, the
     * description, default, restart note and click hints. List row: label, current value (ON/OFF in colour, gold when
     * changed), description with the default when changed and the restart note.
     */
    private Entry option(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, Runnable refresh, String heading) {
        String description = o.description() == null ? null : Localization.get().resolve(p, o.description());
        List<Component> lore = new ArrayList<>();
        lore.add(lang().component(p, "config-menu.current", "value", ConfigHistory.show(o.current(), 60)));
        lore.addAll(description(description));
        lore.add(lang().component(p, "config-menu.default", "value", ConfigHistory.show(o.def(), 60)));
        if (o.activation() == SettingPolicy.Activation.RESTART) lore.add(lang().component(p, "config-menu.restart"));
        lore.add(lang().component(p, switch (o.kind()) {
            case VALUE -> "config-menu.hint-edit";
            case STRUCTURE -> "config-menu.hint-structure";
            case FILE_ONLY -> "config-menu.hint-file";
        }));
        if (o.changed() && o.kind() != ConfigEditorService.OptionKind.FILE_ONLY) lore.add(lang().component(p, "config-menu.hint-reset"));
        String label = Localization.get().resolve(p, o.label());
        Component name = Component.text(heading == null || heading.isBlank() ? label : heading + " › " + label);
        return new Entry(item(material(o), name, lore, o.changed()), (v, right) -> {
            if (right) {
                if (o.changed() && o.kind() != ConfigEditorService.OptionKind.FILE_ONLY) { service.reset(v, state, o.file(), o.path()); refresh.run(); }
                return;
            }
            edit(v, state, o, refresh);
        }, new Row(false, Component.text(label), value(p, o), rowDescription(p, o, description)));
    }

    /** The current value as the list shows it: ON/OFF for switches, else the value, gold when it differs from the default. */
    static Component value(Player p, ConfigEditorService.OptionView o) {
        if (o.current() instanceof Boolean b) return lang().component(p, b ? "features.state-on" : "features.state-off");
        return Component.text(ConfigHistory.show(o.current(), 48)).color(net.kyori.adventure.text.format.TextColor.color(o.changed() ? 0xE3B04B : 0xF2F2F2));
    }

    private static Component rowDescription(Player p, ConfigEditorService.OptionView o, String description) {
        List<String> parts = new ArrayList<>();
        if (description != null && !description.isBlank()) parts.add(description.strip());
        if (o.changed()) parts.add(lang().plain(p, "config-menu.default", "value", ConfigHistory.show(o.def(), 40)));
        if (o.activation() == SettingPolicy.Activation.RESTART) parts.add(lang().plain(p, "config-menu.restart"));
        return parts.isEmpty() ? null : Component.text(String.join(" · ", parts));
    }
    private static Material material(ConfigEditorService.OptionView o) {
        if (o.kind() == ConfigEditorService.OptionKind.STRUCTURE) return Material.BUNDLE;
        if (o.kind() == ConfigEditorService.OptionKind.FILE_ONLY) return Material.GRAY_DYE;
        return switch (o.setting().kind()) {
            case TOGGLE -> Boolean.TRUE.equals(o.current()) ? Material.LIME_DYE : Material.RED_DYE;
            case NUMBER -> Material.COMPARATOR;
            case CHOICE -> Material.REPEATER;
            case TEXT -> Material.NAME_TAG;
        };
    }
    private void edit(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, Runnable refresh) {
        switch (o.kind()) {
            case FILE_ONLY -> lang().send(p, "config-menu.file-only", "file", "plugins/" + state.owner.getDataFolder().getName() + "/" + o.file(), "path", o.path());
            case STRUCTURE -> structure(p, state, o, 0, refresh);
            case VALUE -> {
                Setting s = o.setting();
                Object value = s.getter().apply(p);
                switch (s.kind()) {
                    case TOGGLE -> { apply(p, state, o, !Boolean.TRUE.equals(value)); refresh.run(); }
                    case CHOICE -> {
                        int at = 0;
                        for (int i = 0; i < s.options().size(); i++) if (s.options().get(i).id().equals(value)) at = i;
                        apply(p, state, o, s.options().get((at + 1) % s.options().size()).id());
                        refresh.run();
                    }
                    case NUMBER -> {
                        p.closeInventory();
                        String shown = value instanceof Number n ? ConfigEditorService.plainNumber(n.doubleValue()) : String.valueOf(value);
                        prompt(p, state, o.label() + " (" + ConfigEditorService.plainNumber(s.min()) + " – " + ConfigEditorService.plainNumber(s.max()) + ")", shown, text -> {
                            double number;
                            try { number = Double.parseDouble(text.trim().replace(',', '.')); } catch (NumberFormatException e) { number = Double.NaN; }
                            if (!Double.isFinite(number) || number < s.min() || number > s.max()) lang().send(p, "settings.range", "min", ConfigEditorService.plainNumber(s.min()), "max", ConfigEditorService.plainNumber(s.max()));
                            else apply(p, state, o, number);
                            refresh.run();
                        }, refresh);
                    }
                    case TEXT -> {
                        p.closeInventory();
                        prompt(p, state, o.label(), String.valueOf(value), text -> { apply(p, state, o, text); refresh.run(); }, refresh);
                    }
                }
            }
        }
    }
    private void apply(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, Object value) {
        String error = service.change(p, state, o.file(), o.path(), value);
        if (error != null) lang().send(p, "config-menu.error", "error", error);
    }
    private void prompt(Player p, ConfigEditorService.PluginState state, String question, String initial, java.util.function.Consumer<String> submit, Runnable cancel) {
        main.getApi().dialogs().prompt(core, p, lang().ref("config-menu.prompt-title"), question.startsWith("config-menu.") ? lang().ref(question) : question, initial,
                submit, cancel, v -> service.allowed(state, v));
    }

    // ------------------------------------------------------------------ lists of mappings

    private void structure(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, int page, Runnable back) {
        Runnable refresh = () -> structure(p, state, o, page, back);
        List<Map<String, Object>> list = service.structure(state, o.file(), o.path());
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            Map<String, Object> snapshot = list.get(i);
            List<Component> lore = new ArrayList<>();
            Map<String, Object> fields = StructureOps.fields(snapshot);
            fields.entrySet().stream().limit(8).forEach(e -> lore.add(Component.text(e.getKey() + ": " + ConfigHistory.show(e.getValue(), LINE)).color(net.kyori.adventure.text.format.NamedTextColor.GRAY)));
            lore.add(lang().component(p, "config-menu.entry-open"));
            if (list.size() > 1) lore.add(lang().component(p, "config-menu.entry-delete"));
            // 0.66.0: "Entry 2: <first value>" instead of an anonymous "Entry 2"; the list shows the other fields under it
            String first = fields.isEmpty() ? "" : ConfigHistory.show(fields.values().iterator().next(), 32);
            String entryName = lang().plain(p, "config-menu.entry-name", "index", i + 1) + (first.isEmpty() ? "" : ": " + first);
            String summary = String.join(" · ", fields.entrySet().stream().limit(6).map(e -> ConfigEditorService.human(e.getKey()) + ": " + ConfigHistory.show(e.getValue(), 24)).toList());
            entries.add(new Entry(item(Material.PAPER, Component.text(entryName), lore, false), (v, right) -> {
                if (!right) { entry(v, state, o, index, snapshot, refresh); return; }
                if (service.structure(state, o.file(), o.path()).size() < 2) return;
                v.closeInventory();
                main.getApi().dialogs().confirm(core, v, lang().ref("config-menu.delete-title"), lang().ref("config-menu.delete-confirm"), () -> {
                    var now = service.structure(state, o.file(), o.path());
                    if (index < now.size() && ConfigHistory.same(now.get(index).toString(), snapshot.toString())) service.setStructure(v, state, o.file(), o.path(), StructureOps.remove(now, index));
                    else lang().send(v, "config-menu.stale");
                    refresh.run();
                }, refresh, x -> service.allowed(state, x));
            }, new Row(false, Component.text(entryName), Component.text("›").color(net.kyori.adventure.text.format.TextColor.color(0xE3B04B)),
                    summary.isEmpty() ? null : Component.text(summary))));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.DARK_OAK_DOOR, "back", (v, r) -> back.run()));
        if (!list.isEmpty()) footer.put(51, button(p, Material.EMERALD, "add", (v, r) -> {
            var now = service.structure(state, o.file(), o.path());
            if (!now.isEmpty()) service.setStructure(v, state, o.file(), o.path(), StructureOps.add(now, now.getLast()));
            refresh.run();
        }));
        show(p, state, Component.text(shorten(Localization.get().resolve(p, o.label()))), entries, page, next -> structure(p, state, o, next, back), footer);
    }

    private void entry(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, int index, Map<String, Object> snapshot, Runnable back) {
        List<Entry> entries = new ArrayList<>();
        for (var field : StructureOps.fields(snapshot).entrySet()) {
            String key = field.getKey();
            Object value = field.getValue();
            List<Component> lore = List.of(lang().component(p, "config-menu.current", "value", ConfigHistory.show(value, 60)), lang().component(p, "config-menu.hint-edit"));
            Material icon = value instanceof Boolean b ? (b ? Material.LIME_DYE : Material.RED_DYE) : value instanceof Number ? Material.COMPARATOR : Material.NAME_TAG;
            entries.add(new Entry(item(icon, Component.text(ConfigEditorService.human(key)), lore, false), (v, r) -> {
                if (r) return;
                if (value instanceof Boolean b) { saveField(v, state, o, index, snapshot, key, !b, back); return; }
                v.closeInventory();
                String shown = value instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList()) : String.valueOf(value);
                prompt(v, state, ConfigEditorService.human(key), shown, text -> {
                    Object parsed = StructureOps.parse(value, text);
                    if (parsed == null) { lang().send(v, "config-menu.invalid-value"); entry(v, state, o, index, snapshot, back); }
                    else saveField(v, state, o, index, snapshot, key, parsed, back);
                }, () -> entry(v, state, o, index, snapshot, back));
            }, new Row(false, Component.text(ConfigEditorService.human(key)),
                    value instanceof Boolean b ? lang().component(p, b ? "features.state-on" : "features.state-off") : Component.text(ConfigHistory.show(value, 48)), null)));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, button(p, Material.DARK_OAK_DOOR, "back", (v, r) -> back.run()));
        show(p, state, lang().component(p, "config-menu.entry-name", "index", index + 1), entries, 0, next -> {}, footer);
    }
    private void saveField(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, int index, Map<String, Object> snapshot, String field, Object value, Runnable back) {
        var now = service.structure(state, o.file(), o.path());
        if (index >= now.size() || !now.get(index).toString().equals(snapshot.toString())) { lang().send(p, "config-menu.stale"); back.run(); return; }
        var updated = StructureOps.set(now, index, field, value);
        service.setStructure(p, state, o.file(), o.path(), updated);
        entry(p, state, o, index, updated.get(index), back);
    }

    // ------------------------------------------------------------------ inventory plumbing

    private void show(Player p, ConfigEditorService.PluginState state, Component title, List<Entry> entries, int requested, IntConsumer reopen, Map<Integer, Entry> footer) {
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.clamp(requested, 0, pages - 1);
        Holder holder = new Holder(p.getUniqueId(), state);
        holder.inventory = PlatformCompat.createInventory(holder, 54, pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title);
        for (int i = page * PER_PAGE, slot = 0; i < Math.min(entries.size(), (page + 1) * PER_PAGE); i++, slot++) {
            holder.inventory.setItem(slot, entries.get(i).icon());
            holder.actions.put(slot, entries.get(i).click());
        }
        Map<Integer, Entry> buttons = new java.util.TreeMap<>(footer);
        if (page > 0) buttons.put(PREVIOUS, button(p, Material.ARROW, "previous", (v, r) -> reopen.accept(page - 1)));
        if (page + 1 < pages) buttons.put(NEXT, button(p, Material.ARROW, "next", (v, r) -> reopen.accept(page + 1)));
        buttons.forEach((slot, entry) -> { holder.inventory.setItem(slot, entry.icon()); holder.actions.put(slot, entry.click()); });
        // 0.66.0: on a screen the page is a settings list (name, value, description), the footer slots a button bar
        List<InventoryListLayout.Item> rows = new ArrayList<>();
        for (int i = page * PER_PAGE, slot = 0; i < Math.min(entries.size(), (page + 1) * PER_PAGE); i++, slot++) {
            Row row = entries.get(i).row();
            if (row != null) rows.add(new InventoryListLayout.Item(slot, row.heading(), row.name(), row.value(), row.description()));
        }
        List<InventoryListLayout.Button> bar = new ArrayList<>();
        buttons.forEach((slot, entry) -> bar.add(new InventoryListLayout.Button(slot, entry.row().name())));
        String key = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title) + "#" + page;
        InventoryListLayout.show(holder.inventory, new InventoryListLayout.Spec(key, rows, bar));
        p.openInventory(holder.inventory);
    }
    /** A footer button: item for the chest, label for the screen's button bar. */
    private Entry button(Player p, Material material, String key, Click click) {
        Component label = lang().component(p, "config-menu." + key);
        return new Entry(item(material, label, List.of(), false), click, new Row(false, label, null, null));
    }
    private static List<Component> description(String text) {
        List<Component> out = new ArrayList<>();
        for (String line : ConfigTree.wrap(text, LINE, DESCRIPTION_LINES)) out.add(Component.text(line).color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        return out;
    }
    private static String shorten(String text) { return text.length() > 40 ? "…" + text.substring(text.length() - 39) : text; }
    private static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        PlatformCompat.editMeta(item, meta -> {
            PlatformCompat.displayName(meta, name.decoration(TextDecoration.ITALIC, false));
            PlatformCompat.lore(meta, lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
            if (glint) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        // menuHolder: a chest or barrel in the world is recognised by its location, without copying its block state
        if (!(InventoryCompat.menuHolder(InventoryCompat.top(event.getView())) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || !holder.viewer.equals(p.getUniqueId()) || event.getClickedInventory() != holder.inventory) return;
        Click action = holder.actions.get(event.getSlot());
        if (action == null) return;
        boolean right = event.isRightClick();
        // Never change inventories inside the click event: act one tick later and only if this menu is still open.
        Bukkit.getScheduler().runTask(core, () -> {
            if (!p.isOnline() || InventoryCompat.top(p.getOpenInventory()) != holder.inventory) return;
            if (holder.state != null && !service.allowed(holder.state, p)) { p.closeInventory(); return; }
            if (holder.state == null && ConfigEditor.plugins(p).isEmpty()) { p.closeInventory(); return; }
            try { action.run(p, right); }
            catch (RuntimeException failure) {
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("ConfigMenu", holder.state == null ? "overview" : holder.state.owner.getName(), "click", failure);
                lang().send(p, "config-menu.failed");
                p.closeInventory();
            }
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (InventoryCompat.menuHolder(InventoryCompat.top(event.getView())) instanceof Holder) event.setCancelled(true);
    }
}
