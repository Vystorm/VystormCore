package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.main;
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
    private record Entry(ItemStack icon, Click click) {}
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
            lore.add(lang().component(p, "config-menu.plugin-lore", "changed", changed));
            if (FirstRunSetup.pending(name)) lore.add(lang().component(p, "config-menu.setup-open"));
            entries.add(new Entry(item(Material.BOOK, Component.text(name), lore, false), (v, r) -> plugin(v, state, 0)));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.BARRIER, "close"), (v, r) -> v.closeInventory()));
        footer.put(47, new Entry(button(p, Material.CLOCK, "history"), (v, r) -> { v.closeInventory(); service.sendHistory(v, null, 1); }));
        if (FirstRunSetup.anyPending(p)) footer.put(51, new Entry(button(p, Material.NETHER_STAR, "setup"), (v, r) -> FirstRunSetup.open(v, null)));
        show(p, null, lang().component(p, "config-menu.overview-title"), entries, page, next -> overview(p, next), footer);
    }

    void plugin(Player p, ConfigEditorService.PluginState state, int page) {
        List<Entry> entries = new ArrayList<>();
        for (var category : service.categories(state)) {
            List<Component> lore = new ArrayList<>(description(category.description()));
            int changed = (int) category.root().all().stream().map(path -> service.option(state, category.file(), path)).filter(ConfigEditorService.OptionView::changed).count();
            lore.add(lang().component(p, "config-menu.category-lore", "count", category.root().size(), "changed", changed));
            entries.add(new Entry(item(Material.CHEST, Component.text(category.title()), lore, changed > 0),
                    (v, r) -> folder(v, state, category.file(), category.title(), category.root(), 0, () -> plugin(v, state, page))));
        }
        String name = state.owner.getName();
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.DARK_OAK_DOOR, "back"), (v, r) -> overview(v, 0)));
        footer.put(47, new Entry(button(p, Material.COMPASS, "search"), (v, r) -> search(v, state)));
        footer.put(48, new Entry(button(p, Material.HOPPER, "only-changed"), (v, r) -> changed(v, state, 0)));
        footer.put(50, new Entry(button(p, Material.CLOCK, "history"), (v, r) -> { v.closeInventory(); service.sendHistory(v, name, 1); }));
        if (FirstRunSetup.setupOn() && FirstRunSetup.has(name)) footer.put(51, new Entry(button(p, Material.NETHER_STAR, "setup"), (v, r) -> FirstRunSetup.open(v, name)));
        show(p, state, lang().component(p, "config-menu.plugin-title", "plugin", name), entries, page, next -> plugin(p, state, next), footer);
    }

    private void folder(Player p, ConfigEditorService.PluginState state, String file, String title, ConfigTree.Folder folder, int page, Runnable back) {
        Runnable refresh = () -> folder(p, state, file, title, folder, page, back);
        List<Entry> entries = new ArrayList<>();
        for (var sub : folder.folders()) {
            String name = ConfigEditorService.human(sub.name());
            int changed = (int) sub.all().stream().map(path -> service.option(state, file, path)).filter(ConfigEditorService.OptionView::changed).count();
            List<Component> lore = new ArrayList<>(description(service.comment(state, file, sub.path())));
            lore.add(lang().component(p, "config-menu.category-lore", "count", sub.size(), "changed", changed));
            entries.add(new Entry(item(Material.CHEST, Component.text(name), lore, changed > 0), (v, r) -> folder(v, state, file, title + " · " + name, sub, 0, refresh)));
        }
        for (String path : folder.options()) entries.add(option(p, state, service.option(state, file, path), refresh));
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.DARK_OAK_DOOR, "back"), (v, r) -> back.run()));
        show(p, state, Component.text(shorten(title)), entries, page, next -> folder(p, state, file, title, folder, next, back), footer);
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
            entries.add(option(p, state, o, refresh));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.DARK_OAK_DOOR, "back"), (v, r) -> plugin(v, state, 0)));
        if (entries.isEmpty()) lang().send(p, "config-menu.no-results");
        show(p, state, title, entries, page, next -> results(p, state, title, query, changedOnly, next), footer);
    }
    private static boolean matches(Player p, ConfigEditorService.OptionView o, String query) {
        return (o.path() + " " + Localization.get().resolve(p, o.label()) + " " + (o.description() == null ? "" : Localization.get().resolve(p, o.description()))).toLowerCase(Locale.ROOT).contains(query);
    }

    // ------------------------------------------------------------------ one option

    private Entry option(Player p, ConfigEditorService.PluginState state, ConfigEditorService.OptionView o, Runnable refresh) {
        List<Component> lore = new ArrayList<>(description(o.description() == null ? null : Localization.get().resolve(p, o.description())));
        lore.add(lang().component(p, "config-menu.current", "value", ConfigHistory.show(o.current(), 60)));
        lore.add(lang().component(p, "config-menu.default", "value", ConfigHistory.show(o.def(), 60)));
        if (o.activation() == SettingPolicy.Activation.RESTART) lore.add(lang().component(p, "config-menu.restart"));
        lore.add(lang().component(p, switch (o.kind()) {
            case VALUE -> "config-menu.hint-edit";
            case STRUCTURE -> "config-menu.hint-structure";
            case FILE_ONLY -> "config-menu.hint-file";
        }));
        if (o.changed() && o.kind() != ConfigEditorService.OptionKind.FILE_ONLY) lore.add(lang().component(p, "config-menu.hint-reset"));
        Component name = Component.text(Localization.get().resolve(p, o.label()));
        return new Entry(item(material(o), name, lore, o.changed()), (v, right) -> {
            if (right) {
                if (o.changed() && o.kind() != ConfigEditorService.OptionKind.FILE_ONLY) { service.reset(v, state, o.file(), o.path()); refresh.run(); }
                return;
            }
            edit(v, state, o, refresh);
        });
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
            StructureOps.fields(snapshot).entrySet().stream().limit(8).forEach(e -> lore.add(Component.text(e.getKey() + ": " + ConfigHistory.show(e.getValue(), LINE)).color(net.kyori.adventure.text.format.NamedTextColor.GRAY)));
            lore.add(lang().component(p, "config-menu.entry-open"));
            if (list.size() > 1) lore.add(lang().component(p, "config-menu.entry-delete"));
            entries.add(new Entry(item(Material.PAPER, lang().component(p, "config-menu.entry-name", "index", i + 1), lore, false), (v, right) -> {
                if (!right) { entry(v, state, o, index, snapshot, refresh); return; }
                if (service.structure(state, o.file(), o.path()).size() < 2) return;
                v.closeInventory();
                main.getApi().dialogs().confirm(core, v, lang().ref("config-menu.delete-title"), lang().ref("config-menu.delete-confirm"), () -> {
                    var now = service.structure(state, o.file(), o.path());
                    if (index < now.size() && ConfigHistory.same(now.get(index).toString(), snapshot.toString())) service.setStructure(v, state, o.file(), o.path(), StructureOps.remove(now, index));
                    else lang().send(v, "config-menu.stale");
                    refresh.run();
                }, refresh, x -> service.allowed(state, x));
            }));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.DARK_OAK_DOOR, "back"), (v, r) -> back.run()));
        if (!list.isEmpty()) footer.put(51, new Entry(button(p, Material.EMERALD, "add"), (v, r) -> {
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
            }));
        }
        Map<Integer, Entry> footer = new HashMap<>();
        footer.put(BACK, new Entry(button(p, Material.DARK_OAK_DOOR, "back"), (v, r) -> back.run()));
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
        footer.forEach((slot, entry) -> { holder.inventory.setItem(slot, entry.icon()); holder.actions.put(slot, entry.click()); });
        if (page > 0) { holder.inventory.setItem(PREVIOUS, button(p, Material.ARROW, "previous")); holder.actions.put(PREVIOUS, (v, r) -> reopen.accept(page - 1)); }
        if (page + 1 < pages) { holder.inventory.setItem(NEXT, button(p, Material.ARROW, "next")); holder.actions.put(NEXT, (v, r) -> reopen.accept(page + 1)); }
        p.openInventory(holder.inventory);
    }
    private ItemStack button(Player p, Material material, String key) {
        return item(material, lang().component(p, "config-menu." + key), List.of(), false);
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
