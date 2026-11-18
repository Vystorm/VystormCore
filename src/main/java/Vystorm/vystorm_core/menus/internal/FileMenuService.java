package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.autoGui.GuiCallbacks;
import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.screen.ScreenInput;
import Vystorm.vystorm_core.screen.ScreenOpenResult;
import Vystorm.vystorm_core.screen.ScreenProfile;
import Vystorm.vystorm_core.screen.ScreenSession;
import Vystorm.vystorm_core.screen.ScreenSpec;
import Vystorm.vystorm_core.screen.Screens;
import Vystorm.vystorm_core.screen.internal.MenuScreen;
import Vystorm.vystorm_core.screen.internal.ScreenRuntime;
import Vystorm.vystorm_core.screen.internal.ScreenSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Screen menus from files ({@code plugins/vystorm_core/menus/<id>.json}, format {@code vystorm-menu} version 1; since
 * 0.65.0). Reads and checks the folder, registers every menu like Core's other menus ({@code /vmenu <id>}, the tile in
 * the main menu, the file's own command), shows a menu to a viewer and runs its click actions. A file with a mistake
 * is skipped with one console line; nothing a file contains is ever thrown at a player. Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version. Plugins use
 * {@code api.gui().fileMenus()}.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class FileMenuService {
    /** At most this many files are read from the folder. */
    static final int MAX_FILES = 200;
    /** Prefix of the keys in the menu registry: {@code vystorm_core:menus/<id>}. */
    static final String REGISTRY_PREFIX = "menus/";
    /** Names that are safe to put into a console command: no spaces, no selectors. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    /** How an attempt to open a menu ended. */
    public enum Result { OPENED, UNKNOWN, DENIED }

    private static volatile FileMenuService shared;

    /** One menu a viewer has open as a screen. */
    private static final class Open {
        final MenuFile menu;
        int page = 1;
        String hovered;
        FileMenuLayout.Built built;
        ScreenSession session;
        long refreshed;
        Open(MenuFile menu) { this.menu = menu; }
    }

    /** A chest shown instead of the screen: the widget behind each slot. */
    private record ChestView(MenuFile menu, Map<Integer, MenuFile.Widget> slots, Map<Integer, Runnable> controls) {}

    private final Plugin core;
    private final API api;
    private final Path folder;
    private final Logger log;
    private final LongSupplier clock;
    /** Material name to the item of this server, null when there is none. */
    private final java.util.function.Function<String, Material> items;
    private final Map<String, MenuFile> menus = new LinkedHashMap<>();
    private final Map<String, List<String>> errors = new TreeMap<>(), warnings = new TreeMap<>();
    private final Map<String, CommandRegistration> commands = new HashMap<>();
    /** What each registered command was built from, to leave it alone on a reload that did not change it. */
    private final Map<String, String> commandSources = new HashMap<>();
    private final Map<String, String> commandProblems = new TreeMap<>();
    private final Set<String> registered = new HashSet<>();
    private final Map<UUID, Open> open = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> warnedNames = new HashSet<>();
    private boolean serverLoaded;

    FileMenuService(Plugin core, API api, Path folder, LongSupplier clock, java.util.function.Function<String, Material> items) {
        this.items = items;
        this.core = core;
        this.api = api;
        this.folder = folder;
        this.log = core.getLogger();
        this.clock = clock;
    }

    /** The running service, or null while Core is not enabled. */
    public static FileMenuService shared() { return shared; }

    /**
     * Core: reads the folder and registers the menus; the files' own commands follow on the first tick, when every
     * plugin's commands exist and none can be shadowed.
     */
    public static void start(Plugin core, API api) {
        FileMenuService service = new FileMenuService(core, api, core.getDataFolder().toPath().resolve("menus"), System::currentTimeMillis, FileMenuService::serverItem);
        shared = service;
        service.reload();
        core.getServer().getScheduler().runTask(core, () -> { service.serverLoaded = true; service.syncCommands(); });
        core.getServer().getScheduler().runTaskTimer(core, service::refresh, 20L, 20L);
    }

    /** Core: forgets everything (Core is being disabled; its commands and menu entries go with the plugin). */
    public static void stop() {
        FileMenuService service = shared;
        shared = null;
        if (service == null) return;
        service.open.clear();
        service.menus.clear();
    }

    public boolean enabled() { return core.getConfig().getBoolean("menus.enabled", true); }

    public Path folder() { return folder; }

    public List<String> ids() { return List.copyOf(menus.keySet()); }

    public MenuFile menu(String id) { return id == null ? null : menus.get(id.toLowerCase(Locale.ROOT)); }

    /** File name to the reasons it was skipped. */
    public Map<String, List<String>> errors() { return copy(errors); }

    /** File name to the remarks about a file that was loaded. */
    public Map<String, List<String>> warnings() { return copy(warnings); }

    /** Menu id to the reason its own command was not registered. */
    public Map<String, String> commandProblems() { return Map.copyOf(commandProblems); }

    private static Map<String, List<String>> copy(Map<String, List<String>> source) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        source.forEach((file, lines) -> out.put(file, List.copyOf(lines)));
        return java.util.Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------ loading

    /** Reads the folder again and replaces every registration; returns how many menus are loaded. */
    public int reload() {
        unregisterTiles();
        menus.clear();
        errors.clear();
        warnings.clear();
        commandProblems.clear();
        if (!enabled()) {
            syncCommands();
            closeChanged();
            return 0;
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException | RuntimeException failed) {
            log.warning("[Menus] Folder " + folder.getFileName() + " could not be created: " + failed);
            syncCommands();
            closeChanged();
            return 0;
        }
        for (Path file : files()) read(file);
        for (MenuFile menu : menus.values()) checkTargets(menu);
        errors.forEach((file, lines) -> log.warning("[Menus] " + file + " skipped: " + lines.get(0)
                + (lines.size() > 1 ? " (and " + (lines.size() - 1) + " more, see /vadmin menus)" : "")));
        warnings.forEach((file, lines) -> lines.forEach(line -> log.warning("[Menus] " + file + ": " + line)));
        for (MenuFile menu : menus.values()) register(menu);
        syncCommands();
        closeChanged();
        if (!menus.isEmpty() || !errors.isEmpty())
            log.info("[Menus] " + menus.size() + " menu" + (menus.size() == 1 ? "" : "s") + " loaded" + (errors.isEmpty() ? "" : ", " + errors.size() + " skipped"));
        return menus.size();
    }

    private List<Path> files() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json") && Files.isRegularFile(p))
                    .sorted().forEach(out::add);
        } catch (IOException | RuntimeException failed) {
            log.warning("[Menus] Folder " + folder.getFileName() + " could not be read: " + failed);
        }
        if (out.size() > MAX_FILES) {
            log.warning("[Menus] " + out.size() + " files in the menus folder, only the first " + MAX_FILES + " are read");
            return out.subList(0, MAX_FILES);
        }
        return out;
    }

    private void read(Path file) {
        String name = file.getFileName().toString();
        String json;
        try {
            if (Files.size(file) > MenuFileParser.MAX_FILE_BYTES) {
                errors.put(name, List.of("file is larger than " + MenuFileParser.MAX_FILE_BYTES / 1024 + " KB"));
                return;
            }
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failed) {
            errors.put(name, List.of("could not be read (" + failed.getClass().getSimpleName() + ")"));
            return;
        }
        MenuFileParser.Parsed parsed = MenuFileParser.parse(name, json, item -> material(item) != null);
        if (!parsed.ok()) { errors.put(name, parsed.errors()); return; }
        MenuFile menu = parsed.menu();
        if (menus.containsKey(menu.id())) { errors.put(name, List.of("id: \"" + menu.id() + "\" is already used by another file")); return; }
        menus.put(menu.id(), menu);
        // remarks are kept under <id>.json, the name the file has apart from the case of its ending
        List<String> remarks = new ArrayList<>(parsed.warnings());
        String flat = withoutFlatIcon(menu);
        if (!flat.isEmpty()) remarks.add("icons: screens draw item icons from flat textures and show paper for items without one (" + flat + ")");
        if (!remarks.isEmpty()) warnings.put(menu.id() + ".json", remarks);
    }

    /**
     * The icons of the menu that exist on this server but have no flat texture (chests, beds, heads, carpets ...):
     * screens of current servers draw icons as texture sprites and show paper for these. Empty on servers that draw
     * icons as items.
     */
    private String withoutFlatIcon(MenuFile menu) {
        if (!spriteIcons()) return "";
        Set<String> names = new java.util.TreeSet<>();
        for (MenuFile.Widget widget : menu.widgets())
            if (!widget.icon().isEmpty() && material(widget.icon()) != null && !Vystorm.vystorm_core.screen.internal.ScreenSprites.has(widget.icon())) names.add(widget.icon());
        return String.join(", ", names);
    }

    /** True where screens draw icons as texture sprites (the server's text API has them), as ScreenRuntime decides it. */
    private static boolean spriteIcons() {
        try {
            Vystorm.vystorm_core.screen.internal.SpriteGlyphs.glyph("minecraft:items", "item/paper");
            return true;
        } catch (LinkageError | RuntimeException olderApi) {
            return false;
        }
    }

    /** An {@code open} action that names a menu no file provides: the player gets a message, the owner a warning. */
    private void checkTargets(MenuFile menu) {
        for (MenuFile.Widget widget : menu.widgets())
            for (MenuFile.Click click : List.of(widget.click(), widget.rightClick()))
                for (MenuFile.Action action : click.actions())
                    if (action.kind() == MenuFile.ActionKind.OPEN && !menus.containsKey(action.text()))
                        warnings.computeIfAbsent(menu.id() + ".json", k -> new ArrayList<>())
                                .add("widgets \"" + widget.id() + "\": opens the menu \"" + action.text() + "\", which does not exist");
    }

    /** The item with that name, or null. */
    private Material material(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return items.apply(name); } catch (RuntimeException | LinkageError unknown) { return null; }
    }

    /** The item of the running server with that name, or null (unknown in this version, air, a block without an item). */
    static Material serverItem(String name) {
        Material material = Material.getMaterial(name);
        return material == null || material.isAir() || !material.isItem() ? null : material;
    }

    // ------------------------------------------------------------------ registration

    private void register(MenuFile menu) {
        if (!menu.mainMenu()) return;
        String key = REGISTRY_PREFIX + menu.id();
        Material icon = material(menu.icon());
        try {
            api.gui().registerMenu(core, key, viewer -> tileTitle(menu, viewer), icon == null ? Material.BOOK : icon,
                    viewer -> allowed(viewer, menu.permission()), viewer -> show(viewer, menu), menu.group(), viewer -> "");
            registered.add(key);
            if (!menu.permission().isEmpty() && !menu.permission().startsWith("!"))
                api.gui().menuRequiredPermission(core, key, menu.permission());
        } catch (RuntimeException | LinkageError failed) {
            warnings.computeIfAbsent(menu.id() + ".json", k -> new ArrayList<>()).add("no tile in the main menu (" + failed.getMessage() + ")");
            log.warning("[Menus] " + menu.id() + ": no tile in the main menu: " + failed);
        }
    }

    private void unregisterTiles() {
        for (String key : registered) {
            try { api.gui().unregisterMenu(core, key); } catch (RuntimeException | LinkageError ignored) { }
        }
        registered.clear();
    }

    private static String commandSource(MenuFile menu) { return menu.command() + "\n" + menu.permission() + "\n" + menu.title(); }

    /** Removes the commands of menus that are gone or changed theirs, then registers what is missing. */
    private void syncCommands() {
        // one update of the players' command lists for all of it, not one per command
        Vystorm.vystorm_core.commands.CommandBatch.run(() -> {
            for (var it = commands.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                MenuFile menu = menus.get(entry.getKey());
                if (menu != null && !menu.command().isEmpty() && commandSource(menu).equals(commandSources.get(entry.getKey()))) continue;
                try { api.command().unregister(entry.getValue()); } catch (RuntimeException | LinkageError ignored) { }
                commandSources.remove(entry.getKey());
                it.remove();
            }
            if (serverLoaded) registerCommands();
        });
    }

    /** Registers the files' own commands. A name that already belongs to a command is left alone. */
    private void registerCommands() {
        for (MenuFile menu : menus.values()) {
            String name = menu.command();
            if (name.isEmpty() || commands.containsKey(menu.id())) continue;
            String owner = commandOwner(name);
            if (owner != null) {
                commandProblems.put(menu.id(), "/" + name + " already belongs to " + owner);
                log.warning("[Menus] " + menu.id() + ".json: the command /" + name + " already belongs to " + owner + " and is not registered; /vmenu " + menu.id() + " still opens the menu");
                continue;
            }
            try {
                CommandSpec.Builder spec = CommandSpec.command(name).playerOnly()
                        .description("Opens the menu " + (menu.title().isEmpty() ? menu.id() : MenuText.of(null, "", 1, menu.pages(), null).plain(menu.title())))
                        .executes(context -> openOrTell(context.player(), menu.id()));
                if (!menu.permission().isEmpty() && !menu.permission().startsWith("!")) spec.permission(menu.permission());
                commands.put(menu.id(), api.command().register(core, Localization.get().core(), spec.build()));
                commandSources.put(menu.id(), commandSource(menu));
            } catch (RuntimeException | LinkageError failed) {
                commandProblems.put(menu.id(), "/" + name + " could not be registered");
                log.warning("[Menus] " + menu.id() + ".json: the command /" + name + " could not be registered: " + failed);
            }
        }
    }

    /** Who owns the command name already, or null when it is free. */
    private String commandOwner(String name) {
        for (Map.Entry<String, CommandRegistration> other : commands.entrySet())
            if (other.getValue().label().equals(name)) return "the menu " + other.getKey();
        try {
            var known = api.command().find(name);
            if (known.isPresent()) return known.get().plugin().getName();
            CommandMap map = PlatformCompat.getCommandMap();
            Command existing = map == null ? null : map.getCommand(name);
            if (existing == null) return null;
            return existing instanceof org.bukkit.command.PluginIdentifiableCommand plugin && plugin.getPlugin() != null
                    ? plugin.getPlugin().getName() : "the server";
        } catch (RuntimeException | LinkageError unknown) {
            return "another command";
        }
    }

    /** Screens of menus that changed or are gone close; a viewer of an unchanged menu is left alone. */
    private void closeChanged() {
        for (Map.Entry<UUID, Open> entry : List.copyOf(open.entrySet())) {
            MenuFile was = entry.getValue().menu;
            if (was.equals(menus.get(was.id()))) continue;
            open.remove(entry.getKey());
            ScreenSession session = entry.getValue().session;
            try { if (session != null && session.isOpen()) session.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    // ------------------------------------------------------------------ opening

    /** The id a {@code /vmenu} argument names: {@code menus:<id>} or the bare id of a loaded menu; else null. */
    public String idOf(String key) {
        if (key == null) return null;
        String id = key.toLowerCase(Locale.ROOT);
        if (id.startsWith(MenuFile.KEY_PREFIX)) id = id.substring(MenuFile.KEY_PREFIX.length());
        return menus.containsKey(id) ? id : null;
    }

    /** Ids of the menus this player may open, for tab completion. */
    public List<String> idsFor(Player player) {
        List<String> out = new ArrayList<>();
        for (MenuFile menu : menus.values()) if (allowed(player, menu.permission())) out.add(menu.id());
        return out;
    }

    /** Opens the menu for the player; a chat line tells them when it does not exist or they may not open it. */
    public Result openOrTell(Player player, String id) {
        Result result = open(player, id);
        if (result == Result.DENIED) tell(player, "file-menus.no-permission");
        else if (result == Result.UNKNOWN) tell(player, "file-menus.missing", "menu", id == null ? "" : id);
        return result;
    }

    /** Opens the menu when it exists and the player has its permission. Says nothing to the player. */
    public Result open(Player player, String id) {
        MenuFile menu = menu(id);
        if (menu == null || player == null) return Result.UNKNOWN;
        if (!allowed(player, menu.permission())) return Result.DENIED;
        // a menu with a tile opens through the menu registry, so it counts as visited like every other menu
        String key = core.getName().toLowerCase(Locale.ROOT) + ":" + REGISTRY_PREFIX + menu.id();
        boolean viaRegistry = false;
        if (registered.contains(REGISTRY_PREFIX + menu.id())) {
            try { viaRegistry = api.gui().openMenu(player, key); } catch (RuntimeException | LinkageError failed) { viaRegistry = false; }
        }
        if (!viaRegistry) show(player, menu);
        return Result.OPENED;
    }

    /** A line of Core's language files for the player; a failure to send it is logged, never thrown. */
    private void tell(Player player, String key, Object... placeholders) {
        try { Localization.get().core().send(player, key, placeholders); }
        catch (RuntimeException | LinkageError failed) { log.warning("[Menus] Message " + key + " not sent: " + failed); }
    }

    /** "vip" = has it, "!vip" = lacks it, "" = always. */
    static boolean allowed(Player player, String permission) {
        if (permission == null || permission.isEmpty()) return true;
        if (player == null) return false;
        boolean lacks = permission.startsWith("!");
        return player.hasPermission(lacks ? permission.substring(1) : permission) != lacks;
    }

    private String tileTitle(MenuFile menu, Player viewer) {
        String plain = menu.title().isEmpty() ? "" : text(viewer, menu, 1).plain(menu.title()).strip();
        return plain.isEmpty() ? menu.id() : plain;
    }

    private MenuText text(Player viewer, MenuFile menu, int page) {
        return MenuText.of(viewer, viewer == null ? "" : viewer.getName(), page, menu.pages(), api.placeholders());
    }

    private FileMenuLayout.Built build(Player player, MenuFile menu, int page) {
        Lang lang = Localization.get().core();
        Map<String, Component> labels = new HashMap<>();
        for (String id : menu.header()) labels.put(id, lang.component(player, "menu.screen." + id));
        ScreenProfile profile;
        try { profile = Screens.of(core).profile(player); } catch (RuntimeException | LinkageError noPrefs) { profile = ScreenProfile.DEFAULT; }
        return FileMenuLayout.build(menu, new FileMenuLayout.View(profile, page, permission -> allowed(player, permission), text(player, menu, page),
                name -> { Material material = material(name); return material == null ? Material.PAPER : material; }, labels));
    }

    /** Shows the menu as a screen, or as a chest when no screen can open. No permission check: callers did it. */
    private void show(Player player, MenuFile menu) {
        Open state = new Open(menu);
        try {
            state.built = build(player, menu, 1);
            ScreenSpec spec = ScreenSpec.builder(menu.key(), state.built.layout())
                    .backdrop(menu.backdrop()).mode(menu.mode())
                    .onInput((session, input) -> handle(session, input, state))
                    .fallback(viewer -> chest(viewer, menu, 0))
                    .build();
            // the session of this player is replaced by the open below; remember ours before the first input arrives
            open.put(player.getUniqueId(), state);
            ScreenOpenResult result = Screens.of(core).open(player, spec, menu.key(), viewer -> { if (open(viewer, menu.id()) != Result.OPENED) tell(viewer, "common.menu-unavailable"); });
            if (!result.opened()) {
                open.remove(player.getUniqueId(), state);
                if (result.refusedKey() != null && !ScreenRuntime.silentRefusal(result.refusedKey())) tell(player, result.refusedKey());
                return;
            }
            state.session = Screens.of(core).session(player).orElse(null);
            state.refreshed = clock.getAsLong();
        } catch (RuntimeException | LinkageError failed) {
            open.remove(player.getUniqueId(), state);
            log.warning("[Menus] " + menu.id() + " could not be shown as a screen: " + failed);
            try { chest(player, menu, 0); } catch (RuntimeException | LinkageError alsoFailed) { log.warning("[Menus] " + menu.id() + " could not be shown as a chest: " + alsoFailed); }
        }
    }

    // ------------------------------------------------------------------ input

    private void handle(ScreenSession session, ScreenInput input, Open state) {
        Player player = session.player();
        MenuFile menu = state.menu;
        state.session = session;
        switch (input) {
            case ScreenInput.Hover hover -> {
                state.hovered = hover.widgetId();
                if (state.built.footerId() != null) session.setText(state.built.footerId(), state.built.footer(state.hovered));
            }
            case ScreenInput.Scroll scroll -> {
                int next = Math.max(1, Math.min(menu.pages(), state.page + scroll.delta()));
                if (next == state.page) return;
                state.page = next;
                state.hovered = null;
                state.built = build(player, menu, next);
                session.setLayout(state.built.layout());
            }
            case ScreenInput.Click click -> {
                String id = click.widgetId();
                if (id == null) return;
                if (menu.frame() != MenuFile.Frame.NONE && menu.header().contains(id)) { headerButton(session, menu, id); return; }
                MenuFile.Widget widget = menu.widget(id);
                if (widget != null) click(player, menu, widget, click.right(), state.page, () -> { if (session.isOpen()) session.close(); });
            }
            case ScreenInput.Closed closed -> open.remove(player.getUniqueId(), state);
            default -> { }
        }
    }

    /** The header buttons do what they do in Core's main menu. */
    private void headerButton(ScreenSession session, MenuFile menu, String id) {
        Player player = session.player();
        session.close();
        if (id.equals(MenuScreen.DISPLAY)) new ScreenSettings(core).open(player, viewer -> open(viewer, menu.id()));
        else if (id.equals(MenuScreen.SEARCH)) api.gui().menuExplorer().open(player, api.gui());
    }

    /** One click on a button or tile: permission, cooldown, then the actions in order. */
    void click(Player player, MenuFile menu, MenuFile.Widget widget, boolean right, int page, Runnable close) {
        MenuFile.Click click = right ? widget.rightClick() : widget.click();
        if (click.empty() || !widget.enabled()) return;
        if (!allowed(player, widget.whenPermission()) || !allowed(player, click.permission())) return;
        if (click.cooldown() > 0) {
            String key = player.getUniqueId() + "|" + menu.id() + "|" + widget.id() + (right ? "|R" : "|L");
            long now = clock.getAsLong();
            Long until = cooldowns.get(key);
            if (until != null && now < until) {
                tell(player, "file-menus.cooldown", "seconds", Math.max(1, (until - now + 999) / 1000));
                return;
            }
            if (cooldowns.size() > 2000) cooldowns.values().removeIf(end -> end <= now);
            cooldowns.put(key, now + click.cooldown() * 1000L);
        }
        for (MenuFile.Action action : click.actions()) {
            try {
                run(player, menu, action, page, close);
            } catch (RuntimeException | LinkageError failed) {
                log.warning("[Menus] " + menu.id() + ", widget " + widget.id() + ": " + action.kind().name().toLowerCase(Locale.ROOT) + " action failed: " + failed);
            }
        }
    }

    private void run(Player player, MenuFile menu, MenuFile.Action action, int page, Runnable close) {
        switch (action.kind()) {
            case PLAYER -> player.performCommand(fill(action.text(), player));
            case CONSOLE -> {
                // the name goes into a command with full rights: a name with a space or a selector would change the command
                if (action.text().contains("{player}") && !SAFE_NAME.matcher(player.getName()).matches()) {
                    if (warnedNames.add(player.getName()))
                        log.warning("[Menus] " + menu.id() + ": console command not run for the player with the unusual name \"" + player.getName() + "\"");
                    return;
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fill(action.text(), player));
            }
            case MESSAGE -> PlatformCompat.sendMessage(player, text(player, menu, page).component(action.text()));
            case OPEN -> {
                // a target that is missing or not allowed leaves this menu open and tells the player why
                MenuFile target = menu(action.text());
                if (target == null) { tell(player, "file-menus.missing", "menu", action.text()); return; }
                if (!allowed(player, target.permission())) { tell(player, "file-menus.no-permission"); return; }
                close.run();
                open(player, target.id());
            }
            case BACK -> {
                boolean went = false;
                try { went = Screens.of(core).back(player); } catch (RuntimeException | LinkageError noScreens) { went = false; }
                if (!went) close.run();
            }
            case CLOSE -> close.run();
        }
    }

    /** Commands get the player's name and UUID and nothing else. */
    static String fill(String command, Player player) {
        return command.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString());
    }

    // ------------------------------------------------------------------ placeholder refresh

    /** Every second: menus with placeholders get their texts again when the configured interval has passed. */
    private void refresh() {
        if (open.isEmpty()) return;
        long seconds = core.getConfig().getLong("menus.refresh-seconds", 2);
        if (seconds <= 0) return;
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Open> entry : List.copyOf(open.entrySet())) {
            Open state = entry.getValue();
            ScreenSession session = state.session;
            if (session == null || !session.isOpen()) { if (session != null) open.remove(entry.getKey(), state); continue; }
            if (!state.menu.placeholders() || now - state.refreshed < seconds * 1000L) continue;
            state.refreshed = now;
            try {
                refresh(session, state);
            } catch (RuntimeException | LinkageError failed) {
                log.warning("[Menus] " + state.menu.id() + ": texts not refreshed: " + failed);
            }
        }
    }

    /** Replaces the texts that changed; positions and the set of widgets stay as they were when the page was shown. */
    private void refresh(ScreenSession session, Open state) {
        FileMenuLayout.Built before = state.built, now = build(session.player(), state.menu, state.page);
        Map<String, Object> shown = new HashMap<>();
        for (var widget : before.layout().widgets()) shown.put(widget.id(), widget.text());
        state.built = now;
        for (var widget : now.layout().widgets()) {
            if (!shown.containsKey(widget.id()) || widget.id().equals(now.footerId())) continue;
            if (!widget.text().equals(shown.get(widget.id()))) session.setText(widget.id(), widget.text());
        }
        if (now.footerId() != null && !now.footer(state.hovered).equals(before.footer(state.hovered)))
            session.setText(now.footerId(), now.footer(state.hovered));
    }

    // ------------------------------------------------------------------ fallback: a chest

    /**
     * The menu as a chest, for players and servers without screens: every button and tile this viewer sees becomes an
     * item (name = text, lore = description), a left or right click runs the same actions. A 9-column chest frame
     * keeps its slots; everything else is listed in file order. Labels, fills, icons without a click, the header
     * buttons, the footer and the pages are not shown.
     */
    private void chest(Player player, MenuFile menu, int chestPage) {
        MenuText text = text(player, menu, 1);
        List<MenuFile.Widget> entries = new ArrayList<>();
        for (MenuFile.Widget widget : menu.widgets())
            if (widget.type().clickable() && widget.enabled() && allowed(player, widget.whenPermission())) entries.add(widget);
        Map<Integer, MenuFile.Widget> slots = new LinkedHashMap<>();
        Map<Integer, Runnable> controls = new HashMap<>();
        boolean keepSlots = menu.frame() == MenuFile.Frame.CHEST && menu.columns() == 9 && menu.rows() <= 6 && menu.pages() == 1
                && entries.stream().allMatch(w -> w.slot() >= 0) && entries.stream().map(MenuFile.Widget::slot).distinct().count() == entries.size();
        int size;
        if (keepSlots) {
            for (MenuFile.Widget widget : entries) slots.put(widget.slot(), widget);
            size = menu.rows() * 9;
        } else if (entries.size() <= 54) {
            for (int i = 0; i < entries.size(); i++) slots.put(i, entries.get(i));
            size = Math.max(9, (entries.size() + 8) / 9 * 9);
        } else {
            int pages = (entries.size() + 44) / 45, page = Math.max(0, Math.min(pages - 1, chestPage));
            for (int i = 0; i < 45 && page * 45 + i < entries.size(); i++) slots.put(i, entries.get(page * 45 + i));
            if (page > 0) controls.put(45, () -> chest(player, menu, page - 1));
            if (page < pages - 1) controls.put(53, () -> chest(player, menu, page + 1));
            size = 54;
        }
        ChestView view = new ChestView(menu, slots, controls);
        String title = LegacyComponentSerializer.legacySection().serialize(text.component(menu.title().isEmpty() ? menu.id() : menu.title()));
        Inventory inventory = api.gui().createInventory(core, null, player.getUniqueId(), size, title, new GuiCallbacks() {
            @Override public void click(InventoryClickEvent event) {
                event.setCancelled(true);
                if (event.getClickedInventory() != Vystorm.vystorm_core.compat.InventoryCompat.top(event.getView())) return;
                chestClick(player, view, event.getSlot(), event.isRightClick());
            }
        });
        slots.forEach((slot, widget) -> {
            Material material = material(widget.icon());
            inventory.setItem(slot, item(material == null ? Material.PAPER : material, text.component(widget.text()),
                    widget.description().isEmpty() ? List.of() : List.of(text.component(widget.description()))));
        });
        Lang lang = Localization.get().core();
        if (controls.containsKey(45)) inventory.setItem(45, item(Material.ARROW, lang.component(player, "common.previous"), List.of()));
        if (controls.containsKey(53)) inventory.setItem(53, item(Material.ARROW, lang.component(player, "common.next"), List.of()));
        // This chest is what is shown when no screen can open: Core must not try to show it as a screen again. That
        // attempt would end with the chest being opened a second time, and a personal managed inventory is released
        // when it closes, so the second one would let players take the items.
        api.gui().screens().keepAsChest(inventory);
        player.openInventory(inventory);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        PlatformCompat.editMeta(item, meta -> {
            PlatformCompat.displayName(meta, name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) PlatformCompat.lore(meta, lore.stream().map(line -> (Component) line.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
            try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES); } catch (RuntimeException | LinkageError noFlags) { }
        });
        return item;
    }

    /** The click runs one tick later: actions close and open inventories, which a click event must not do itself. */
    private void chestClick(Player player, ChestView view, int slot, boolean right) {
        Runnable control = view.controls().get(slot);
        MenuFile.Widget widget = view.slots().get(slot);
        if (control == null && widget == null) return;
        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) return;
            try {
                if (control != null) control.run();
                else click(player, view.menu(), widget, right, 1, player::closeInventory);
            } catch (RuntimeException | LinkageError failed) {
                log.warning("[Menus] " + view.menu().id() + ": click in the chest failed: " + failed);
            }
        });
    }
}
