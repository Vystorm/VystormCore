package Vystorm.vystorm_core._uiMethods.settings;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInterface;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implementation behind {@link ConfigEditor}. Server thread only. */
final class ConfigEditorService {
    static final String MAIN_FILE = "config.yml";
    private static final int PER_PAGE = 16;
    private static final int MAX_SLIDER_STEPS = 1000;
    private static final int MAX_DESCRIPTION = 180;
    private static final Set<String> SECRET_NAMES = Set.of("password", "passwort", "secret", "token", "api-key", "apikey",
            "key", "jdbc", "url", "username", "user", "host", "pass", "pwd", "uri", "dsn", "jdbc-url", "webhook",
            "private-key", "access-key", "secret-key", "connection-string");
    private static final List<String> SECRET_SUFFIXES = List.of("password", "passwort", "secret", "token", "api-key", "apikey", "jdbc",
            "jdbc-url", "webhook", "webhook-url", "private-key", "access-key", "secret-key", "connection-string");
    private static final List<String> SECRET_TREES = List.of("database.shared", "storage.mysql");
    private static final Pattern RANGE = Pattern.compile(
            "(?:[(\\[]\\s*|\\b(?:range|bereich|between|zwischen|min(?:imum)?|values?|werte?)\\b[^0-9\\n-]{0,12})"
                    + "(-?\\d+(?:[.,]\\d+)?)\\s*(?:\\.\\.|-|–|—|bis|to|and|und)\\s*(-?\\d+(?:[.,]\\d+)?)"
                    + "|(-?\\d+(?:[.,]\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE);

    /** Adds and removes the dynamic per-plugin permissions. */
    interface Permissions {
        void add(String node, String description, String parent);
        void remove(String node, String parent);
    }

    static final Permissions BUKKIT_PERMISSIONS = new Permissions() {
        @Override public void add(String node, String description, String parent) {
            var manager = Bukkit.getPluginManager();
            Permission permission = manager.getPermission(node);
            if (permission == null) {
                permission = new Permission(node, description, PermissionDefault.OP);
                manager.addPermission(permission);
            }
            permission.addParent(parent, true);
        }
        @Override public void remove(String node, String parent) {
            var manager = Bukkit.getPluginManager();
            Permission parentPermission = manager.getPermission(parent);
            if (parentPermission != null && parentPermission.getChildren().remove(node) != null) parentPermission.recalculatePermissibles();
            manager.removePermission(node);
        }
    };

    static ConfigEditorService bukkit() {
        ConfigEditorService service = new ConfigEditorService(() -> Vystorm.vystorm_core.main.getPlugin(),
                () -> Vystorm.vystorm_core.main.getApi().settings(), () -> Vystorm.vystorm_core.main.getApi().dialogs(), BUKKIT_PERMISSIONS);
        service.historySource = () -> {
            Plugin core = Vystorm.vystorm_core.main.getPlugin();
            if (!core.getConfig().getBoolean("config-history.enabled", true)) return null;
            int max = Math.clamp(core.getConfig().getInt("config-history.max-entries", ConfigHistory.DEFAULT_MAX), 1, 5000);
            // Re-read from the file when max-entries changed, so the new limit applies without a restart.
            if (service.history == null || service.history.max() != max)
                service.history = new ConfigHistory(core.getDataFolder().toPath().resolve("config-history.yml"), max);
            return service.history;
        };
        return service;
    }

    private final Supplier<Plugin> core;
    private final Supplier<SettingsInterface> settings;
    private final Supplier<DialogInterface> dialogs;
    private final Permissions permissions;
    private final Map<Plugin, PluginState> plugins = new LinkedHashMap<>();
    /** The change history; null source or null result = not recorded (switched off). */
    private ConfigHistory history;
    private Supplier<ConfigHistory> historySource = () -> null;

    /** Tests: record into this history. */
    void history(ConfigHistory history) { this.history = history; this.historySource = () -> this.history; }

    ConfigHistory history() {
        try { return historySource.get(); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[ConfigEditor] Change history unavailable: " + failure); return null; }
    }

    ConfigEditorService(Supplier<Plugin> core, Supplier<SettingsInterface> settings, Supplier<DialogInterface> dialogs, Permissions permissions) {
        this.core = core; this.settings = settings; this.dialogs = dialogs; this.permissions = permissions;
    }

    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }

    // ------------------------------------------------------------------ state

    /** Annotations of one config path. */
    static final class Meta {
        String label, description;
        double min = Double.NaN, max = Double.NaN, step = Double.NaN;
        LinkedHashMap<String, String> choices;
        boolean hidden;
        SettingPolicy.Activation activation;
        /** Feature switch (0.60.0): TRUE declared, FALSE excluded, null = automatic ({@code enabled} keys). */
        Boolean feature;
    }

    /** A registered file: schema from the jar, values from the data folder. */
    static final class FileBinding {
        /** Marker for "the key was not in the file" in {@link #pending}. */
        private static final Object ABSENT = new Object();
        final Plugin owner; final String file; final YamlConfiguration schema; final Runnable reload;
        private YamlConfiguration own; private long modified = Long.MIN_VALUE;
        /** Paths changed through the editor since the last save → value before the first change (or {@link #ABSENT}). */
        private final LinkedHashMap<String, Object> pending = new LinkedHashMap<>();
        private boolean backedUp;
        /** What the last successful {@link #save} wrote: path, value before, value after (for the change history). */
        private List<ConfigHistory.Change> lastChanges = List.of();
        FileBinding(Plugin owner, String file, YamlConfiguration schema, Runnable reload) {
            this.owner = owner; this.file = file; this.schema = schema; this.reload = reload;
        }
        boolean main() { return MAIN_FILE.equals(file); }
        Path path() { return owner.getDataFolder().toPath().resolve(file); }
        /**
         * The live configuration: the plugin's own {@code getConfig()} for config.yml, else a copy re-read when the file
         * changed (a file that does not exist yet reads as the jar default, so a save creates the complete file).
         */
        FileConfiguration config() {
            if (main()) return owner.getConfig();
            Path path = path();
            long stamp;
            try { stamp = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1; } catch (IOException e) { stamp = -1; }
            if (own == null || stamp != modified) {
                YamlConfiguration loaded = new YamlConfiguration();
                loaded.options().parseComments(true);
                try { loaded.loadFromString(stamp >= 0 ? Files.readString(path, StandardCharsets.UTF_8) : defaultText(owner, file)); }
                catch (IOException | InvalidConfigurationException e) { throw new IllegalStateException("Unreadable " + file + ": " + e.getMessage(), e); }
                Vystorm.vystorm_core._coreMethods.dataStorage.YamlNodes.release(loaded);
                loaded.setDefaults(schema);
                own = loaded; modified = stamp;
                pending.clear();
            }
            return own;
        }
        /** Sets a value in the live configuration and remembers the path for {@link #save}. */
        void set(String path, Object value) {
            FileConfiguration config = config();
            if (!pending.containsKey(path)) pending.put(path, config.contains(path, true) ? config.get(path) : ABSENT);
            config.set(path, value);
        }
        /**
         * Writes the pending paths into the file <b>as it is on disk now</b> (hand edits, other keys and comments stay;
         * the plugin's in-memory config is never dumped over the file). A missing file is created from the jar default.
         * Before the first write of this registration the old file is copied to {@code <file>.bak-<date>}. On failure the
         * live configuration is put back to the values before the change.
         *
         * @return false if nothing was pending (nothing written)
         */
        boolean save() throws IOException {
            if (pending.isEmpty()) return false;
            try {
                Path target = path();
                boolean exists = Files.exists(target);
                String text = exists ? Files.readString(target, StandardCharsets.UTF_8) : defaultText(owner, file);
                YamlConfiguration disk = parse(text, file);
                if (hasDottedKeys(text)) throw new UnsafeFileException("config.reason-dotted-keys", file + " contains keys with a dot");
                FileConfiguration live = live();
                for (String path : pending.keySet()) {
                    boolean had = disk.contains(path, true);
                    disk.set(path, live.get(path));
                    if (!had) copyComments(schema, disk, path);
                }
                if (exists && !backedUp) { backup(target, file); backedUp = true; }
                writeKeepingPermissions(target, disk.saveToString().getBytes(StandardCharsets.UTF_8));
                List<ConfigHistory.Change> changes = new ArrayList<>();
                for (var e : pending.entrySet())
                    changes.add(new ConfigHistory.Change(e.getKey(), e.getValue() == ABSENT ? null : e.getValue(), live.get(e.getKey())));
                lastChanges = List.copyOf(changes);
                pending.clear();
                if (!main()) { own = null; modified = Long.MIN_VALUE; }
                return true;
            } catch (IOException | RuntimeException failure) {
                revert();
                throw failure;
            }
        }
        List<ConfigHistory.Change> lastChanges() { return lastChanges; }
        /** The current value in the file, {@code null} if the key is not there (the jar default applies). */
        Object current(String path) {
            FileConfiguration config = config();
            return config.contains(path, true) ? config.get(path) : null;
        }
        /** The configuration the pending values were set in, without re-reading the file. */
        private FileConfiguration live() { return main() ? owner.getConfig() : own; }
        /** Puts the live configuration back to the values before the pending changes. */
        private void revert() {
            try {
                FileConfiguration live = live();
                if (live != null) for (var e : pending.entrySet()) live.set(e.getKey(), e.getValue() == ABSENT ? null : e.getValue());
            } catch (RuntimeException ignored) { /* live config unreadable: nothing to put back */ }
            pending.clear();
        }
    }

    /** A file the editor refuses to write; {@code reason} is a Core lang key shown to the admin. */
    static final class UnsafeFileException extends IOException {
        final String reason;
        UnsafeFileException(String reason, String message) { super(message); this.reason = reason; }
    }

    /** Parses YAML with comments; invalid YAML becomes an {@link UnsafeFileException}. */
    static YamlConfiguration parse(String text, String file) throws UnsafeFileException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException invalid) {
            throw new UnsafeFileException("config.reason-invalid-yaml", file + " is not valid YAML: " + invalid.getMessage());
        }
        return yaml;
    }

    /**
     * Whether a YAML text has a key containing {@code .}: Bukkit splits such keys into nested sections on load, so writing
     * the file back would change its structure. Invalid YAML counts as false (callers parse it separately).
     */
    static boolean hasDottedKeys(String text) {
        YamlConfiguration raw = new YamlConfiguration();
        raw.options().pathSeparator('\u001F');
        try { raw.loadFromString(text); } catch (InvalidConfigurationException invalid) { return false; }
        for (String key : raw.getKeys(true)) if (key.indexOf('.') >= 0) return true;
        return false;
    }

    /** The jar default as text ({@code ""} if missing). */
    static String defaultText(Plugin owner, String file) throws IOException {
        try (InputStream in = owner.getResource(file)) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void backup(Path target, String file) throws IOException {
        Path backup = target.resolveSibling(file.substring(file.lastIndexOf('/') + 1) + ".bak-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")));
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Atomic write that keeps the POSIX permissions of the replaced file (the temp file of {@link AtomicFiles} is
     * owner-only, which would lock out e.g. an FTP user of another account); a new file gets the data folder's
     * permissions without execute bits.
     */
    static void writeKeepingPermissions(Path target, byte[] bytes) throws IOException {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = null;
        try {
            if (Files.exists(target)) permissions = Files.getPosixFilePermissions(target);
            else if (target.getParent() != null && Files.isDirectory(target.getParent())) {
                permissions = EnumSet.copyOf(Files.getPosixFilePermissions(target.getParent()));
                permissions.removeAll(EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE, java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
            }
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException notPosix) { permissions = null; }
        AtomicFiles.write(target, bytes);
        if (permissions != null && !permissions.isEmpty()) {
            try { Files.setPosixFilePermissions(target, permissions); }
            catch (UnsupportedOperationException | IOException ignored) { /* best effort */ }
        }
    }

    /** Comments of a default key onto the same key of {@code target}; optional. */
    private static void copyComments(YamlConfiguration defaults, ConfigurationSection target, String path) {
        try {
            List<String> comments = defaults.getComments(path);
            if (!comments.isEmpty()) target.setComments(path, comments);
            List<String> inline = defaults.getInlineComments(path);
            if (!inline.isEmpty()) target.setInlineComments(path, inline);
        } catch (RuntimeException | LinkageError ignored) { /* comments are optional */ }
    }

    /** A built section, kept for the plugin page. */
    record Built(String id, String file, String title, List<String> fileOnly) {}

    static final class PluginState {
        final Plugin owner;
        final Map<String, FileBinding> files = new LinkedHashMap<>();
        final Map<String, Meta> meta = new LinkedHashMap<>();
        final Set<String> merged = new HashSet<>();
        final List<Built> built = new ArrayList<>();
        final Map<String, Leaf> leaves = new LinkedHashMap<>();
        final Map<String, List<String>> fileOnly = new LinkedHashMap<>();
        Runnable reloadHook;
        boolean permission;
        PluginState(Plugin owner) { this.owner = owner; }
    }

    private PluginState state(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        return plugins.computeIfAbsent(owner, PluginState::new);
    }

    private Meta meta(Plugin owner, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path");
        return state(owner).meta.computeIfAbsent(path, p -> new Meta());
    }

    // ------------------------------------------------------------------ public operations

    /** Only {@code config.yml} gets missing defaults merged automatically (0.27.1). */
    boolean register(Plugin owner, String file, Runnable reload) { return register(owner, file, reload, MAIN_FILE.equals(file(file))); }

    boolean register(Plugin owner, String file, Runnable reload, boolean merge) {
        String name = file(file);
        YamlConfiguration schema = defaults(owner, name);
        if (schema == null) {
            DebugMessages.high("[ConfigEditor] " + owner.getName() + " has no default " + name + " in its jar; nothing registered");
            return false;
        }
        PluginState state = state(owner);
        if (merge && state.merged.add(name)) {
            try { mergeDefaults(owner, name); }
            catch (RuntimeException failure) { ProviderFailures.report("ConfigEditor", owner.getName(), "mergeDefaults " + name, failure); }
        }
        state.files.put(name, new FileBinding(owner, name, schema, reload));
        ensurePermission(state);
        rebuild(state);
        return true;
    }

    void reloadHook(Plugin owner, Runnable reload) { state(owner).reloadHook = reload; }

    void label(Plugin owner, String path, String label, String description) {
        Meta m = meta(owner, path); m.label = label; m.description = description; changed(owner);
    }

    void range(Plugin owner, String path, double min, double max, double step) {
        if (!(min < max) || !(step > 0) || !Double.isFinite(min) || !Double.isFinite(max)) throw new IllegalArgumentException("range needs min < max and step > 0");
        Meta m = meta(owner, path); m.min = min; m.max = max; m.step = step; changed(owner);
    }

    void choices(Plugin owner, String path, LinkedHashMap<String, String> choices) {
        if (choices == null || choices.isEmpty()) throw new IllegalArgumentException("choices");
        for (var e : choices.entrySet()) new Setting.Option(e.getKey(), e.getValue());
        meta(owner, path).choices = new LinkedHashMap<>(choices); changed(owner);
    }

    void hidden(Plugin owner, String path) { meta(owner, path).hidden = true; changed(owner); }

    void activation(Plugin owner, String path, SettingPolicy.Activation activation) {
        meta(owner, path).activation = Objects.requireNonNull(activation, "activation"); changed(owner);
    }

    private void changed(Plugin owner) {
        PluginState state = plugins.get(owner);
        if (state != null && !state.files.isEmpty()) rebuild(state);
    }

    void release(Plugin owner) {
        if (owner == null) return;
        PluginState state = plugins.remove(owner);
        if (state == null) return;
        if (settings.get() instanceof SettingsRegistry registry)
            for (Built b : state.built) {
                try { registry.unregister(owner, b.id()); } catch (RuntimeException ignored) { /* registry already cleared */ }
            }
        if (state.permission) {
            try { permissions.remove(ConfigEditor.permission(owner), ConfigEditor.PERMISSION_ALL); }
            catch (RuntimeException | LinkageError failure) { DebugMessages.high("[ConfigEditor] Removing permission of " + owner.getName() + " failed: " + failure); }
        }
    }

    List<String> plugins(CommandSender viewer) {
        return plugins.values().stream().filter(s -> allowed(s, viewer) && !s.built.isEmpty())
                .map(s -> s.owner.getName()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    static boolean allowed(PluginState state, CommandSender viewer) {
        return state.owner.isEnabled() && viewer != null && viewer.hasPermission(ConfigEditor.permission(state.owner));
    }

    private PluginState byName(String name) {
        if (name == null) return null;
        return plugins.values().stream().filter(s -> s.owner.getName().equalsIgnoreCase(name.trim())).findFirst().orElse(null);
    }

    boolean open(Player player, String plugin) {
        PluginState state = byName(plugin);
        if (state == null || !allowed(state, player) || state.built.isEmpty()) return false;
        if (menu != null) menu.plugin(player, state, 0); else openPlugin(player, state, 0);
        return true;
    }

    /** Back navigation from a registry section: the plugin page of that section, else the overview. */
    void openFrom(Player player, String sectionKey) {
        if (sectionKey != null) {
            String owner = sectionKey.contains(":") ? sectionKey.substring(0, sectionKey.indexOf(':')) : "";
            PluginState state = byName(owner);
            if (state != null && allowed(state, player)) { if (menu != null) menu.plugin(player, state, 0); else openPlugin(player, state, 0); return; }
        }
        showOverview(player);
    }

    // ------------------------------------------------------------------ dialogs

    void openOverview(Player player, int requested) {
        List<PluginState> visible = plugins.values().stream().filter(s -> allowed(s, player) && !s.built.isEmpty())
                .sorted(Comparator.comparing(s -> s.owner.getName(), String.CASE_INSENSITIVE_ORDER)).toList();
        int pages = Math.max(1, (visible.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<DialogButton> buttons = new ArrayList<>();
        for (PluginState s : visible.subList(page * PER_PAGE, Math.min(visible.size(), (page + 1) * PER_PAGE)))
            buttons.add(new DialogButton(lang().component(player, "config.plugin-button", "plugin", s.owner.getName()),
                    (p, r) -> { if (!open(p, s.owner.getName())) lang().send(p, "config.unknown-plugin", "plugin", s.owner.getName()); }));
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> openOverview(p, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> openOverview(p, page + 1)));
        if (!visible.isEmpty() && history() != null) buttons.add(new DialogButton(lang().component(player, "config-history.button"),
                lang().component(player, "config-history.button-tooltip"), (p, r) -> sendHistory(p, null, 1)));
        buttons.add(new DialogButton(lang().component(player, "config.back-server"), (p, r) -> settings.get().openScope(p, SettingScope.SERVER)));
        Component title = lang().component(player, "config.overview-title");
        dialogs.get().open(core.get(), player, new DialogRequest(pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                List.of(DialogBody.plainMessage(lang().component(player, visible.isEmpty() ? "config.overview-empty" : "config.overview-text"), 300)),
                List.of(), buttons, Player::isOnline, () -> {}, "Schließen", 2));
    }

    private void openPlugin(Player player, PluginState state, int requested) {
        List<Built> sections = state.built;
        int pages = Math.max(1, (sections.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        String key = state.owner.getName().toLowerCase(Locale.ROOT) + ":";
        List<DialogButton> buttons = new ArrayList<>();
        for (Built b : sections.subList(page * PER_PAGE, Math.min(sections.size(), (page + 1) * PER_PAGE)))
            buttons.add(new DialogButton(Component.text(b.title()), (p, r) -> {
                if (!settings.get().openSection(p, key + b.id())) lang().send(p, "common.settings-gone");
            }));
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> openPlugin(p, state, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> openPlugin(p, state, page + 1)));
        if (history() != null) buttons.add(new DialogButton(lang().component(player, "config-history.button"),
                lang().component(player, "config-history.button-tooltip"), (p, r) -> sendHistory(p, state.owner.getName(), 1)));
        buttons.add(new DialogButton(lang().component(player, "config.back-overview"), (p, r) -> openOverview(p, 0)));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(lang().component(player, "config.plugin-text", "count", sections.size(),
                "files", String.join(", ", state.files.keySet())), 300));
        for (var e : state.fileOnly.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String keys = String.join(", ", e.getValue());
            if (keys.length() > 400) keys = keys.substring(0, 397) + "…";
            body.add(DialogBody.plainMessage(lang().component(player, "config.file-only",
                    "file", "plugins/" + state.owner.getDataFolder().getName() + "/" + e.getKey(), "keys", keys), 300));
        }
        Component title = lang().component(player, "config.plugin-title", "plugin", state.owner.getName());
        dialogs.get().open(core.get(), player, new DialogRequest(pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                body, List.of(), buttons, p -> allowed(state, p), () -> {}, "Schließen", 2));
    }

    // ------------------------------------------------------------------ read model and edits of the admin menu (0.54.0)

    private volatile ConfigMenu menu;
    void menu(ConfigMenu menu) { this.menu = menu; }
    enum OptionKind { VALUE, STRUCTURE, FILE_ONLY }
    record Category(String file, String top, String title, String description, ConfigTree.Folder root) {}
    record OptionView(String file, String path, OptionKind kind, Setting setting, Object def, Object current, boolean changed,
                      SettingPolicy.Activation activation, String label, String description) {}

    List<PluginState> visible(Player player) {
        return plugins.values().stream().filter(s -> allowed(s, player) && !s.files.isEmpty())
                .sorted(Comparator.comparing(s -> s.owner.getName(), String.CASE_INSENSITIVE_ORDER)).toList();
    }
    void showOverview(Player player) { if (menu != null) menu.overview(player, 0); else openOverview(player, 0); }

    List<Category> categories(PluginState state) {
        List<Category> out = new ArrayList<>();
        for (FileBinding binding : state.files.values())
            for (var group : groups(binding.schema).entrySet()) {
                List<String> paths = group.getValue().stream().filter(p -> !secret(p) && !hidden(state, p)).toList();
                if (paths.isEmpty()) continue;
                out.add(new Category(binding.file, group.getKey(), title(state.owner, binding.file, group.getKey(), null), comment(binding.schema, group.getKey()), ConfigTree.build(group.getKey(), paths)));
            }
        return out;
    }
    String comment(PluginState state, String file, String path) { FileBinding b = state.files.get(file); return b == null ? null : comment(b.schema, path); }

    OptionView option(PluginState state, String file, String path) {
        FileBinding binding = state.files.get(file);
        Leaf leaf = state.leaves.get(file + ":" + path);
        Meta meta = state.meta.getOrDefault(path, new Meta());
        Object def = binding.schema.get(path);
        Object current = binding.config().get(path, def);
        if (def instanceof ConfigurationSection) def = null;
        if (current instanceof ConfigurationSection) current = null;
        OptionKind kind = leaf != null ? OptionKind.VALUE : StructureOps.isStructure(def) || StructureOps.isStructure(current) ? OptionKind.STRUCTURE : OptionKind.FILE_ONLY;
        String label = leaf != null ? leaf.setting().label() : meta.label != null && !meta.label.isBlank() ? meta.label : label(path);
        String description = leaf != null ? leaf.setting().description() : meta.description != null ? meta.description : comment(binding.schema, path);
        return new OptionView(file, path, kind, leaf == null ? null : leaf.setting(), def, current, !ConfigHistory.same(StructureOps.copy(current), StructureOps.copy(def)),
                activation(state, binding, path), label, description);
    }

    private void saved(PluginState state, FileBinding binding, String path, Player player) {
        Leaf leaf = state.leaves.get(binding.file + ":" + path);
        boolean restart = activation(state, binding, path) == SettingPolicy.Activation.RESTART;
        try { afterSave(state, binding, leaf == null ? List.of() : List.of(leaf), player); }
        catch (RuntimeException reported) { return; }
        if (leaf == null && restart) lang().send(player, "config.restart-needed", "plugin", state.owner.getName());
    }
    /** Validates and saves one value; returns the problem for the admin, or null. */
    String change(Player player, PluginState state, String file, String path, Object raw) {
        FileBinding binding = state.files.get(file);
        Leaf leaf = state.leaves.get(file + ":" + path);
        if (binding == null || leaf == null) return lang().text(player, "common.settings-gone");
        Object value = SettingsRegistry.coerce(leaf.setting(), raw);
        if (value == null) return lang().text(player, "config-menu.invalid-value");
        String problem = leaf.setting().setter().apply(player, value);
        if (problem != null) return problem;
        saved(state, binding, path, player);
        return null;
    }
    void reset(Player player, PluginState state, String file, String path) {
        FileBinding binding = state.files.get(file);
        if (binding == null || secret(path) || hidden(state, path)) return;
        binding.set(path, StructureOps.copy(binding.schema.get(path)));
        saved(state, binding, path, player);
    }
    List<Map<String, Object>> structure(PluginState state, String file, String path) {
        List<?> raw = state.files.get(file).config().getList(path);
        return StructureOps.entries(raw == null ? List.of() : raw);
    }
    void setStructure(Player player, PluginState state, String file, String path, List<Map<String, Object>> value) {
        FileBinding binding = state.files.get(file);
        if (binding == null || secret(path) || hidden(state, path)) return;
        binding.set(path, value);
        saved(state, binding, path, player);
    }
    // ------------------------------------------------------------------ feature switches (0.60.0)

    /** One on/off switch of a feature: a boolean key of {@code config.yml}. Label and description may be lang references. */
    record FeatureView(String file, String path, String label, String description, boolean on, boolean def, SettingPolicy.Activation activation) {}

    void feature(Plugin owner, String path, boolean feature) { meta(owner, path).feature = feature; }

    /** Top-level booleans that are housekeeping, not features. */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");

    /**
     * Feature switches without a declaration: keys named {@code enabled}, and booleans at the top level of the file
     * ({@code speedometer}, {@code auto-protect}) except housekeeping such as {@code debug} or {@code metrics}.
     */
    static boolean featureKey(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path.toLowerCase(Locale.ROOT));
    }

    /** {@code travel-gates.energy.enabled} → "Travel gates › Energy"; a bare {@code enabled} → "Enabled". */
    static String featureLabel(String path) {
        String subject = path.endsWith(".enabled") ? path.substring(0, path.length() - ".enabled".length()) : path;
        List<String> parts = new ArrayList<>();
        for (String part : subject.split("\\.")) parts.add(human(part));
        return String.join(" › ", parts);
    }

    PluginState stateByName(String name) { return byName(name); }
    Plugin corePlugin() { return core.get(); }

    /** Feature switches of the plugin's {@code config.yml} in file order; empty when the file is not registered. */
    List<FeatureView> features(PluginState state) {
        List<FeatureView> out = new ArrayList<>();
        FileBinding binding = state.files.get(MAIN_FILE);
        if (binding == null) return out;
        for (String path : binding.schema.getKeys(true)) {
            if (!(binding.schema.get(path) instanceof Boolean def)) continue;
            Meta meta = state.meta.get(path);
            Boolean declared = meta == null ? null : meta.feature;
            if (declared == null ? !featureKey(path) : !declared) continue;
            if (secret(path) || hidden(state, path) || !state.leaves.containsKey(binding.file + ":" + path)) continue;
            String label = meta != null && meta.label != null && !meta.label.isBlank() ? meta.label : featureLabel(path);
            String description = meta != null && meta.description != null ? meta.description : comment(binding.schema, path);
            if ((description == null || description.isBlank()) && path.endsWith(".enabled"))
                description = comment(binding.schema, path.substring(0, path.length() - ".enabled".length()));
            out.add(new FeatureView(binding.file, path, label, description == null ? "" : description,
                    binding.config().getBoolean(path, def), def, activation(state, binding, path)));
        }
        return out;
    }

    /** Flips one feature switch and saves it like any editor change; returns the problem for the admin, or null. */
    String toggleFeature(Player player, PluginState state, FeatureView feature) {
        if (!allowed(state, player)) return lang().text(player, "common.settings-gone");
        return change(player, state, feature.file(), feature.path(), !feature.on());
    }

    /** The value in effect, {@code null} if the plugin or file is not registered. */
    Object value(Plugin owner, String file, String path) {
        PluginState state = plugins.get(owner);
        FileBinding binding = state == null ? null : state.files.get(file);
        return binding == null ? null : binding.config().get(path, binding.schema.get(path));
    }
    /** Writes the changes of a setup; each file is saved once. Every target is checked before anything is written. */
    boolean applyBatch(Player player, String plugin, Map<SetupDefinition.Target, Object> changes) {
        PluginState state = byName(plugin);
        if (state == null || !allowed(state, player)) { lang().send(player, "common.settings-gone"); return false; }
        LinkedHashMap<FileBinding, List<Map.Entry<SetupDefinition.Target, Object>>> byFile = new LinkedHashMap<>();
        for (var change : changes.entrySet()) {
            FileBinding binding = state.files.get(change.getKey().file());
            if (binding == null || secret(change.getKey().path()) || hidden(state, change.getKey().path())) {
                lang().send(player, "config.not-saved", "plugin", plugin, "file", change.getKey().file(), "reason", lang().text(player, "config-menu.not-registered"));
                return false;
            }
            byFile.computeIfAbsent(binding, b -> new ArrayList<>()).add(change);
        }
        boolean ok = true;
        for (var entry : byFile.entrySet()) {
            FileBinding binding = entry.getKey();
            for (var change : entry.getValue()) binding.set(change.getKey().path(), change.getValue());
            boolean restart = entry.getValue().stream().anyMatch(c -> activation(state, binding, c.getKey().path()) == SettingPolicy.Activation.RESTART);
            try {
                if (!binding.save()) continue;
                record(player.getName(), state, binding, 0);
                finish(state, binding, restart, player);
            } catch (IOException | RuntimeException failure) {
                String reason = failure instanceof UnsafeFileException unsafe ? unsafe.reason : "config.reason-io";
                if (!(failure instanceof UnsafeFileException)) ProviderFailures.report("ConfigEditor", plugin, "setup " + binding.file, failure);
                lang().send(player, "config.not-saved", "plugin", plugin, "file", binding.file, "reason", lang().text(player, reason));
                ok = false;
            }
        }
        return ok;
    }
    /** Whether a jar resource is a settings file that {@code registerAll} should offer. */
    static boolean editableDefault(String name) {
        if (name.contains("/") || !name.endsWith(".yml") || name.startsWith("legacy-")) return false;
        return !Set.of("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.yml", "folia-plugin.yml", SetupDefinition.FILE).contains(name);
    }
    /** Registers every settings file at the root of the plugin jar; only {@code config.yml} gets missing defaults merged. */
    int registerAll(Plugin owner, Runnable reload) { return registerAll(owner, reload, true); }
    int registerAll(Plugin owner, Runnable reload, boolean mergeConfig) {
        int count = 0;
        try {
            java.net.URL location = owner.getClass().getProtectionDomain().getCodeSource().getLocation();
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(Path.of(location.toURI()).toFile())) {
                for (String name : jar.stream().map(java.util.jar.JarEntry::getName).filter(ConfigEditorService::editableDefault).sorted().toList())
                    if (register(owner, name, reload, mergeConfig && MAIN_FILE.equals(name))) count++;
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException failure) {
            ProviderFailures.report("ConfigEditor", owner.getName(), "registerAll", failure);
            if (count == 0 && register(owner, MAIN_FILE, reload)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ building sections

    private void ensurePermission(PluginState state) {
        if (state.permission) return;
        try {
            permissions.add(ConfigEditor.permission(state.owner),
                    "Konfiguration von " + state.owner.getName() + " im Einstellungsmenü sehen und ändern", ConfigEditor.PERMISSION_ALL);
            state.permission = true;
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[ConfigEditor] Permission for " + state.owner.getName() + " not registered: " + failure);
        }
    }

    /** One editable value or a value that can only be edited in the file. */
    private record Leaf(String path, Setting setting, SettingPolicy.Activation activation) {}

    private void rebuild(PluginState state) {
        Plugin owner = state.owner;
        SettingsInterface registry = settings.get();
        SettingsRegistry full = registry instanceof SettingsRegistry r ? r : null;
        if (full != null) for (Built b : state.built) full.unregister(owner, b.id());
        else if (!state.built.isEmpty()) {
            DebugMessages.mid("[ConfigEditor] Settings registry replaced; " + owner.getName() + " keeps its first config sections");
            return;
        }
        state.built.clear();
        state.leaves.clear();
        state.fileOnly.clear();
        Set<String> ids = new HashSet<>();
        for (FileBinding binding : state.files.values()) {
            List<String> fileOnly = new ArrayList<>();
            state.fileOnly.put(binding.file, fileOnly);
            for (var group : groups(binding.schema).entrySet()) {
                Set<String> settingIds = new HashSet<>();
                List<Leaf> leaves = new ArrayList<>();
                for (String path : group.getValue()) {
                    if (secret(path) || hidden(state, path)) continue;
                    Leaf leaf;
                    try { leaf = leaf(state, binding, path, settingIds); }
                    catch (RuntimeException failure) {
                        ProviderFailures.report("ConfigEditor", owner.getName(), "build " + path, failure);
                        leaf = null;
                    }
                    if (leaf == null) fileOnly.add(path); else { leaves.add(leaf); state.leaves.put(binding.file + ":" + path, leaf); }
                }
                if (leaves.isEmpty()) continue;
                List<List<Leaf>> parts = split(group.getKey(), leaves);
                for (int i = 0; i < parts.size(); i++) {
                    String id = sectionId(binding.file, group.getKey(), parts.size() > 1 ? i + 1 : 0, ids);
                    String title = title(owner, binding.file, group.getKey(), parts.size() > 1 ? (i + 1) + "/" + parts.size() : null);
                    List<Leaf> part = parts.get(i);
                    List<String> partFileOnly = fileOnly.stream().filter(p -> p.equals(group.getKey()) || p.startsWith(group.getKey() + ".")).toList();
                    String description = comment(binding.schema, group.getKey());
                    try {
                        registry.register(owner, SettingsSection.server(id, title, description, ConfigEditor.permission(owner),
                                part.stream().map(Leaf::setting).toList(), p -> afterSave(state, binding, part, p)));
                    } catch (RuntimeException failure) {
                        ProviderFailures.report("ConfigEditor", owner.getName(), "register section " + id, failure);
                        continue;
                    }
                    state.built.add(new Built(id, binding.file, title, partFileOnly));
                    if (full != null) {
                        String key = owner.getName().toLowerCase(Locale.ROOT) + ":" + id;
                        full.group(key, ConfigEditor.GROUP);
                        full.groupOpener(this::openFrom);
                        // Whole configs stay excluded from broad profile exports; the setup wizard explicitly
                        // selects bounded sections after secrets and file-only structures were excluded above.
                        for (Leaf leaf : part) full.policy(owner, id, leaf.setting().id(), new SettingPolicy(leaf.activation(), false));
                    }
                }
            }
        }
        DebugMessages.mid("[ConfigEditor] " + owner.getName() + ": " + state.built.size() + " config sections, files " + state.files.keySet());
    }

    /** Top-level key → leaf paths below it in schema order; plain top-level values are collected under {@code ""}. */
    static LinkedHashMap<String, List<String>> groups(YamlConfiguration schema) {
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        List<String> root = new ArrayList<>();
        groups.put("", root);
        for (String top : schema.getKeys(false)) {
            ConfigurationSection section = schema.getConfigurationSection(top);
            if (section == null || section.getKeys(false).isEmpty()) { root.add(top); continue; }
            List<String> leaves = new ArrayList<>();
            for (String sub : section.getKeys(true)) {
                ConfigurationSection child = section.getConfigurationSection(sub);
                if (child == null || child.getKeys(false).isEmpty()) leaves.add(top + "." + sub);
            }
            groups.put(top, leaves);
        }
        if (root.isEmpty()) groups.remove("");
        return groups;
    }

    /** Splits a big group into parts of at most {@link ConfigEditor#MAX_PER_SECTION}, keeping second-level groups together where possible. */
    static <T> List<List<T>> split(String top, List<T> leaves) {
        int max = ConfigEditor.MAX_PER_SECTION;
        if (leaves.size() <= max) return List.of(leaves);
        LinkedHashMap<String, List<T>> sub = new LinkedHashMap<>();
        for (T leaf : leaves) {
            String path = leaf instanceof Leaf l ? l.path() : leaf.toString();
            String rest = top.isEmpty() ? path : path.substring(Math.min(path.length(), top.length() + 1));
            int dot = rest.indexOf('.');
            sub.computeIfAbsent(dot < 0 ? "" : rest.substring(0, dot), k -> new ArrayList<>()).add(leaf);
        }
        List<List<T>> parts = new ArrayList<>();
        List<T> current = new ArrayList<>();
        for (List<T> group : sub.values()) {
            if (!current.isEmpty() && current.size() + group.size() > max) { parts.add(current); current = new ArrayList<>(); }
            for (T leaf : group) {
                if (current.size() == max) { parts.add(current); current = new ArrayList<>(); }
                current.add(leaf);
            }
        }
        if (!current.isEmpty()) parts.add(current);
        return parts;
    }

    private static boolean hidden(PluginState state, String path) {
        for (var e : state.meta.entrySet())
            if (e.getValue().hidden && (path.equals(e.getKey()) || path.startsWith(e.getKey() + "."))) return true;
        return false;
    }

    private static SettingPolicy.Activation activation(PluginState state, FileBinding binding, String path) {
        String best = null; SettingPolicy.Activation found = null;
        for (var e : state.meta.entrySet()) {
            if (e.getValue().activation == null) continue;
            String key = e.getKey();
            if ((path.equals(key) || path.startsWith(key + ".")) && (best == null || key.length() > best.length())) { best = key; found = e.getValue().activation; }
        }
        if (found != null) return found;
        return binding.reload != null || state.reloadHook != null ? SettingPolicy.Activation.IMMEDIATE : SettingPolicy.Activation.RELOAD;
    }

    /** Builds the setting for one path; null if the value can only be edited in the file. */
    private Leaf leaf(PluginState state, FileBinding binding, String path, Set<String> usedIds) {
        YamlConfiguration schema = binding.schema;
        Meta meta = state.meta.getOrDefault(path, new Meta());
        Object def = schema.get(path);
        if (def instanceof ConfigurationSection || def instanceof Map<?, ?>) return null;
        String id = settingId(path, usedIds);
        String label = meta.label != null && !meta.label.isBlank() ? meta.label : label(path);
        String description = meta.description != null ? meta.description : comment(schema, path);
        FileBinding config = binding;
        Setting setting;
        if (def instanceof Boolean) {
            setting = Setting.toggle(id, label, description, p -> config.config().getBoolean(path),
                    (p, v) -> { config.set(path, v); return null; });
        } else if (def instanceof Integer || def instanceof Long || def instanceof Short || def instanceof Byte) {
            setting = whole(id, label, description, path, config, def instanceof Long, meta, commentRange(description, ((Number) def).doubleValue(), true));
        } else if (def instanceof Double || def instanceof Float) {
            setting = decimal(id, label, description, path, config, meta, commentRange(description, ((Number) def).doubleValue(), false));
        } else if (def instanceof List<?> list) {
            setting = list(id, label, description, path, config, list);
        } else {
            setting = meta.choices != null ? choice(id, label, description, path, config, meta.choices)
                    : text(id, label, description, path, config);
        }
        return setting == null ? null : new Leaf(path, setting, activation(state, binding, path));
    }

    private static Setting whole(String id, String label, String description, String path, FileBinding config,
                                 boolean wide, Meta meta, double[] commentRange) {
        double min = Double.isNaN(meta.min) ? (commentRange == null ? Double.NaN : commentRange[0]) : meta.min;
        double max = Double.isNaN(meta.max) ? (commentRange == null ? Double.NaN : commentRange[1]) : meta.max;
        double step = Double.isNaN(meta.step) ? 1 : meta.step;
        boolean wholeStep = Math.abs(step - Math.rint(step)) < 1e-9;
        if (!Double.isNaN(min) && (max - min) / step <= MAX_SLIDER_STEPS) {
            return Setting.number(id, label, description, min, max, step, p -> config.config().getDouble(path), (p, v) -> {
                if (wholeStep) setWhole(config, path, Math.round(v), wide); else config.set(path, round(v));
                return null;
            });
        }
        long low = Double.isNaN(min) ? (wide ? Long.MIN_VALUE : Integer.MIN_VALUE) : (long) Math.ceil(min);
        long high = Double.isNaN(max) ? (wide ? Long.MAX_VALUE : Integer.MAX_VALUE) : (long) Math.floor(max);
        return Setting.text(id, label, description, 20, p -> Long.toString(config.config().getLong(path)), (p, v) -> {
            long value;
            try { value = Long.parseLong(v.trim()); } catch (NumberFormatException e) { return lang().text(p, "settings.not-integer"); }
            if (value < low || value > high) return lang().text(p, "settings.range", "min", low, "max", high);
            setWhole(config, path, value, wide);
            return null;
        });
    }

    private static void setWhole(FileBinding config, String path, long value, boolean wide) {
        if (!wide && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) config.set(path, (int) value); else config.set(path, value);
    }

    private static Setting decimal(String id, String label, String description, String path, FileBinding config,
                                   Meta meta, double[] commentRange) {
        if (!Double.isNaN(meta.min) && (meta.max - meta.min) / meta.step <= MAX_SLIDER_STEPS)
            return Setting.number(id, label, description, meta.min, meta.max, meta.step, p -> config.config().getDouble(path),
                    (p, v) -> { config.set(path, round(v)); return null; });
        double min = !Double.isNaN(meta.min) ? meta.min : commentRange != null ? commentRange[0] : -1e15;
        double max = !Double.isNaN(meta.max) ? meta.max : commentRange != null ? commentRange[1] : 1e15;
        return Setting.text(id, label, description, 32, p -> plainNumber(config.config().getDouble(path)), (p, v) -> {
            double value;
            try { value = Double.parseDouble(v.trim().replace(',', '.')); } catch (NumberFormatException e) { return lang().text(p, "settings.not-number"); }
            if (!Double.isFinite(value) || value < min || value > max) return lang().text(p, "settings.range", "min", plainNumber(min), "max", plainNumber(max));
            config.set(path, value);
            return null;
        });
    }

    private static Setting choice(String id, String label, String description, String path, FileBinding config,
                                  LinkedHashMap<String, String> choices) {
        return Setting.choice(id, label, description, choices, p -> config.config().getString(path, ""), (p, v) -> {
            // An unknown stored value preselects the first option; a case-only difference is not a change.
            if (v.equalsIgnoreCase(config.config().getString(path, ""))) return null;
            config.set(path, v);
            return null;
        });
    }

    private static Setting text(String id, String label, String description, String path, FileBinding config) {
        return Setting.text(id, label, description, 256, p -> config.config().getString(path, ""), (p, v) -> {
            String current = config.config().getString(path, "");
            if (current.length() > 256) return current.startsWith(v) ? null : lang().text(p, "config.too-long");
            config.set(path, v);
            return null;
        });
    }

    /** Lists of plain values as comma-separated text; null for lists of maps/lists (edit in file). */
    private static Setting list(String id, String label, String description, String path, FileBinding config, List<?> def) {
        if (def.stream().anyMatch(v -> v instanceof Map<?, ?> || v instanceof List<?> || v instanceof ConfigurationSection)) return null;
        Class<?> type = def.isEmpty() ? String.class
                : def.stream().allMatch(v -> v instanceof Integer || v instanceof Long) ? Long.class
                : def.stream().allMatch(v -> v instanceof Number) ? Double.class
                : def.stream().allMatch(v -> v instanceof Boolean) ? Boolean.class : String.class;
        return Setting.text(id, label, description, 256, p -> join(config.config().getList(path, List.of())), (p, v) -> {
            List<?> current = config.config().getList(path, List.of());
            if (current.stream().anyMatch(x -> x instanceof Map<?, ?> || x instanceof List<?>)) return lang().text(p, "config.too-long");
            String joined = join(current);
            if (joined.length() > 256) return joined.startsWith(v) ? null : lang().text(p, "config.too-long");
            if (current.stream().anyMatch(x -> String.valueOf(x).contains(","))) return v.equals(joined) ? null : lang().text(p, "config.list-commas");
            List<Object> values = new ArrayList<>();
            for (String part : v.split("[,\\n]")) {
                String item = part.trim();
                if (item.isEmpty()) continue;
                try {
                    if (type == Long.class) { long n = Long.parseLong(item); values.add(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) (int) n : n); }
                    else if (type == Double.class) values.add(Double.parseDouble(item.replace(',', '.')));
                    else if (type == Boolean.class) {
                        if (!item.equalsIgnoreCase("true") && !item.equalsIgnoreCase("false")) throw new NumberFormatException(item);
                        values.add(Boolean.parseBoolean(item));
                    } else values.add(item);
                } catch (NumberFormatException e) { return lang().text(p, "config.list-invalid", "value", item); }
            }
            config.set(path, values);
            return null;
        });
    }

    private static String join(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (Object o : list) { if (!out.isEmpty()) out.append(", "); out.append(o); }
        return out.toString();
    }

    /** Runs once after a save of one section: write the file, run the hook, tell the admin. */
    private void afterSave(PluginState state, FileBinding binding, List<Leaf> part, Player player) {
        String name = state.owner.getName();
        boolean written;
        try { written = binding.save(); }
        catch (IOException | RuntimeException failure) {
            String reason = failure instanceof UnsafeFileException unsafe ? unsafe.reason : "config.reason-io";
            if (failure instanceof UnsafeFileException) DebugMessages.high("[ConfigEditor] " + name + ": " + failure.getMessage() + "; nothing written");
            else ProviderFailures.report("ConfigEditor", name, "save " + binding.file, failure);
            lang().send(player, "config.not-saved", "plugin", name, "file", binding.file, "reason", lang().text(player, reason));
            throw failure instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) failure;
        }
        if (!written) return;
        DebugMessages.mid("[ConfigEditor] " + player.getName() + " saved " + name + "/" + binding.file);
        record(player.getName(), state, binding, 0);
        finish(state, binding, part.stream().anyMatch(l -> l.activation() == SettingPolicy.Activation.RESTART), player);
    }

    /** After a written save: run the reload hook and tell the admin whether the change is active. */
    private void finish(PluginState state, FileBinding binding, boolean restart, CommandSender viewer) {
        String name = state.owner.getName();
        Runnable hook = binding.reload != null ? binding.reload : state.reloadHook;
        if (hook != null) {
            try { hook.run(); }
            catch (RuntimeException | LinkageError failure) {
                ProviderFailures.report("ConfigEditor", name, "reload", failure);
                lang().send(viewer, "config.reload-failed", "plugin", name);
                return;
            }
        }
        if (restart) lang().send(viewer, "config.restart-needed", "plugin", name);
        else if (hook == null) lang().send(viewer, "config.reload-needed", "plugin", name, "command", name.toLowerCase(Locale.ROOT));
        else lang().send(viewer, "config.applied", "plugin", name);
    }

    /** Adds the last save of {@code binding} to the change history; a failing history never fails the save. */
    private ConfigHistory.Entry record(String actor, PluginState state, FileBinding binding, int undoOf) {
        ConfigHistory log = history();
        if (log == null) return null;
        try { return log.record(actor, state.owner.getName(), binding.file, binding.lastChanges(), undoOf, System.currentTimeMillis()); }
        catch (RuntimeException failure) { ProviderFailures.report("ConfigEditor", state.owner.getName(), "history", failure); return null; }
    }

    // ------------------------------------------------------------------ change history (/vadmin config history|undo)

    static final int HISTORY_PER_PAGE = 6;
    static final int HISTORY_CHANGES_SHOWN = 5;
    private static final java.time.format.DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static boolean mayEdit(CommandSender viewer, String plugin) {
        return viewer != null && viewer.hasPermission(ConfigEditor.PERMISSION_PREFIX + plugin.toLowerCase(Locale.ROOT));
    }

    /** {@code /vadmin config history [plugin] [page]}: newest changes first, each with an undo link. */
    void sendHistory(CommandSender viewer, String plugin, int requested) {
        ConfigHistory log = history();
        if (log == null) { lang().send(viewer, "config-history.disabled"); return; }
        String filter = plugin == null || plugin.isBlank() ? null : plugin.trim();
        List<ConfigHistory.Entry> visible = log.newestFirst(e -> mayEdit(viewer, e.plugin()) && (filter == null || e.plugin().equalsIgnoreCase(filter)));
        if (visible.isEmpty()) { lang().send(viewer, "config-history.empty"); return; }
        int pages = Math.max(1, (visible.size() + HISTORY_PER_PAGE - 1) / HISTORY_PER_PAGE);
        int page = Math.max(1, Math.min(requested, pages));
        lang().send(viewer, filter == null ? "config-history.header-all" : "config-history.header-plugin",
                "plugin", filter == null ? "" : filter, "page", page, "pages", pages);
        for (ConfigHistory.Entry entry : visible.subList((page - 1) * HISTORY_PER_PAGE, Math.min(visible.size(), page * HISTORY_PER_PAGE))) {
            String when = java.time.Instant.ofEpochMilli(entry.time()).atZone(java.time.ZoneId.systemDefault()).format(HISTORY_TIME);
            Component line = lang().component(viewer, "config-history.entry", "id", entry.id(), "time", when, "actor", entry.actor(),
                    "plugin", entry.plugin(), "file", entry.file());
            if (entry.undoOf() > 0) line = line.append(Component.space()).append(lang().component(viewer, "config-history.undo-of", "id", entry.undoOf()));
            if (entry.undone()) line = line.append(Component.space()).append(lang().component(viewer, "config-history.undone-by", "id", entry.undoneBy()));
            else line = line.append(Component.space()).append(lang().component(viewer, "config-history.undo-button")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/vadmin config undo " + entry.id()))
                    .hoverEvent(lang().component(viewer, "config-history.undo-hover", "id", entry.id())));
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, line);
            List<ConfigHistory.Change> changes = entry.changes();
            for (ConfigHistory.Change change : changes.subList(0, Math.min(HISTORY_CHANGES_SHOWN, changes.size())))
                lang().send(viewer, "config-history.change", "path", change.path(),
                        "before", ConfigHistory.show(change.before(), 48), "after", ConfigHistory.show(change.after(), 48));
            if (changes.size() > HISTORY_CHANGES_SHOWN) lang().send(viewer, "config-history.more-changes", "count", changes.size() - HISTORY_CHANGES_SHOWN);
        }
        if (page < pages) {
            String command = "/vadmin config history " + (filter == null ? "*" : filter) + " " + (page + 1);
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, lang().component(viewer, "config-history.next-page", "page", page + 1)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command)).hoverEvent(Component.text(command)));
        }
    }

    /**
     * {@code /vadmin config undo <id> [force]}: puts the values of one change back and saves like the editor (backup,
     * reload, recorded as a new entry). Values changed again since then are a conflict; {@code force} undoes anyway.
     *
     * @return the new history entry, or null if nothing was undone
     */
    ConfigHistory.Entry undo(CommandSender viewer, int id, boolean force) {
        ConfigHistory log = history();
        if (log == null) { lang().send(viewer, "config-history.disabled"); return null; }
        ConfigHistory.Entry entry = log.get(id).filter(e -> mayEdit(viewer, e.plugin())).orElse(null);
        if (entry == null) { lang().send(viewer, "config-history.unknown", "id", id); return null; }
        if (entry.undone()) { lang().send(viewer, "config-history.already-undone", "id", id, "by", entry.undoneBy()); return null; }
        PluginState state = byName(entry.plugin());
        FileBinding binding = state == null || !state.owner.isEnabled() ? null : state.files.get(entry.file());
        if (binding == null) { lang().send(viewer, "config-history.plugin-gone", "plugin", entry.plugin(), "file", entry.file()); return null; }
        List<ConfigHistory.Change> changes = entry.changes().stream().filter(c -> !secret(c.path()) && !hidden(state, c.path())).toList();
        List<String> conflicts = new ArrayList<>();
        try {
            for (ConfigHistory.Change change : changes)
                if (!ConfigHistory.same(binding.current(change.path()), change.after())) conflicts.add(change.path());
        } catch (RuntimeException unreadable) {
            lang().send(viewer, "config.not-saved", "plugin", entry.plugin(), "file", entry.file(), "reason", lang().text(viewer, "config.reason-invalid-yaml"));
            return null;
        }
        if (!conflicts.isEmpty() && !force) {
            String paths = String.join(", ", conflicts);
            if (paths.length() > 300) paths = paths.substring(0, 297) + "…";
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(viewer, lang().component(viewer, "config-history.conflict", "id", id, "paths", paths).append(Component.space())
                    .append(lang().component(viewer, "config-history.force-button")
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/vadmin config undo " + id + " force"))
                            .hoverEvent(lang().component(viewer, "config-history.force-hover"))));
            return null;
        }
        for (ConfigHistory.Change change : changes) binding.set(change.path(), change.before());
        boolean written;
        try { written = binding.save(); }
        catch (IOException | RuntimeException failure) {
            String reason = failure instanceof UnsafeFileException unsafe ? unsafe.reason : "config.reason-io";
            if (!(failure instanceof UnsafeFileException)) ProviderFailures.report("ConfigEditor", entry.plugin(), "undo " + entry.file(), failure);
            lang().send(viewer, "config.not-saved", "plugin", entry.plugin(), "file", entry.file(), "reason", lang().text(viewer, reason));
            return null;
        }
        if (!written) { lang().send(viewer, "config-history.nothing", "id", id); return null; }
        ConfigHistory.Entry undo = record(viewer.getName(), state, binding, id);
        DebugMessages.mid("[ConfigEditor] " + viewer.getName() + " undid change #" + id + " of " + entry.plugin() + "/" + entry.file());
        lang().send(viewer, "config-history.undone", "id", id, "count", changes.size(), "new", undo == null ? "?" : "#" + undo.id());
        finish(state, binding, changes.stream().anyMatch(c -> activation(state, binding, c.path()) == SettingPolicy.Activation.RESTART), viewer);
        return undo;
    }

    // ------------------------------------------------------------------ defaults

    int mergeDefaults(Plugin owner, String file) {
        String name = file(file);
        YamlConfiguration defaults = defaults(owner, name);
        if (defaults == null) return 0;
        Path target = owner.getDataFolder().toPath().resolve(name);
        try {
            if (!Files.exists(target)) {
                byte[] bytes;
                try (InputStream in = owner.getResource(name)) { bytes = in == null ? new byte[0] : in.readAllBytes(); }
                writeKeepingPermissions(target, bytes);
                return (int) defaults.getKeys(true).stream().filter(p -> !defaults.isConfigurationSection(p)).count();
            }
            String text = Files.readString(target, StandardCharsets.UTF_8);
            YamlConfiguration current;
            try { current = parse(text, name); }
            catch (UnsafeFileException invalid) {
                DebugMessages.high("[ConfigEditor] " + owner.getName() + "/" + name + " is not valid YAML; missing defaults not added: " + invalid.getMessage());
                return 0;
            }
            List<String> added = new ArrayList<>();
            int values = 0;
            for (String path : defaults.getKeys(true)) {
                if (!addMissing(current, defaults, path)) continue;
                added.add(path);
                if (!defaults.isConfigurationSection(path)) values++;
            }
            if (added.isEmpty()) return 0;
            if (hasDottedKeys(text)) {
                DebugMessages.high("[ConfigEditor] " + owner.getName() + "/" + name + " contains keys with a dot; " + values
                        + " missing default value(s) not added (writing would nest those keys)");
                return 0;
            }
            Path backup = target.resolveSibling(name.substring(name.lastIndexOf('/') + 1) + ".bak-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")));
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            writeKeepingPermissions(target, current.saveToString().getBytes(StandardCharsets.UTF_8));
            if (MAIN_FILE.equals(name)) {
                try {
                    FileConfiguration live = owner.getConfig();
                    if (live != null) for (String path : added) addMissing(live, defaults, path);
                } catch (RuntimeException failure) { DebugMessages.high("[ConfigEditor] In-memory config of " + owner.getName() + " not updated: " + failure); }
            }
            try { owner.getLogger().info("Added " + values + " missing default value(s) to " + name + " (backup " + backup.getFileName() + ")"); }
            catch (RuntimeException ignored) { /* logger unavailable in tests */ }
            return values;
        } catch (IOException failure) {
            ProviderFailures.report("ConfigEditor", owner.getName(), "mergeDefaults " + name, failure);
            return 0;
        }
    }

    /** Copies one default path (with comments) into {@code target} if it and no blocking ancestor value exist there. */
    private static boolean addMissing(ConfigurationSection target, YamlConfiguration defaults, String path) {
        if (target.contains(path, true)) return false;
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String parent = path.substring(0, dot);
            if (target.contains(parent, true) && !target.isConfigurationSection(parent)) return false;
            dot = parent.lastIndexOf('.');
        }
        if (defaults.isConfigurationSection(path)) target.createSection(path);
        else target.set(path, defaults.get(path));
        copyComments(defaults, target, path);
        return true;
    }

    static YamlConfiguration defaults(Plugin owner, String file) {
        try (InputStream in = owner.getResource(file)) {
            if (in == null) return null;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().parseComments(true);
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return Vystorm.vystorm_core._coreMethods.dataStorage.YamlNodes.release(yaml);
        } catch (IOException | InvalidConfigurationException failure) {
            ProviderFailures.report("ConfigEditor", owner.getName(), "read default " + file, failure);
            return null;
        }
    }

    static String file(String file) {
        String name = file == null || file.isBlank() ? MAIN_FILE : file.replace('\\', '/').trim();
        if (name.startsWith("/") || name.contains("..") || name.contains(":") || !(name.endsWith(".yml") || name.endsWith(".yaml")))
            throw new IllegalArgumentException("config file must be a relative .yml path: " + file);
        return name;
    }

    // ------------------------------------------------------------------ naming and metadata

    static boolean secret(String path) {
        if (path == null) return true;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String tree : SECRET_TREES) if (lower.equals(tree) || lower.startsWith(tree + ".")) return true;
        // camelCase parts count like kebab-case: dbPassword → db-password, botToken → bot-token
        String[] parts = path.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT).split("\\.");
        for (String part : parts) if (part.equals("credentials")) return true;
        String last = parts[parts.length - 1].replace('_', '-');
        if (SECRET_NAMES.contains(last)) return true;
        for (String suffix : SECRET_SUFFIXES) if (last.endsWith("-" + suffix)) return true;
        return false;
    }

    /** {@code fields.max-size} → "Max size"; deeper paths keep the intermediate parts ("Limits › Max size"). */
    static String label(String path) {
        String[] parts = path.split("\\.");
        int from = parts.length > 1 ? 1 : 0;
        List<String> out = new ArrayList<>();
        for (int i = from; i < parts.length; i++) out.add(human(parts[i]));
        return String.join(" › ", out);
    }

    static String human(String key) {
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('-', ' ').replace('_', ' ').trim().replaceAll("\\s+", " ");
        if (spaced.isEmpty()) return key;
        spaced = spaced.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String title(Plugin owner, String file, String top, String part) {
        StringBuilder title = new StringBuilder(owner.getName());
        String stem = stem(file);
        if (!MAIN_FILE.equals(file)) title.append(" · ").append(human(stem));
        title.append(" · ").append(top.isEmpty() ? human(MAIN_FILE.equals(file) ? "general" : stem) : human(top));
        if (part != null) title.append(" (").append(part).append(')');
        return title.toString();
    }

    private static String stem(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String sectionId(String file, String top, int part, Set<String> used) {
        String base = (MAIN_FILE.equals(file) ? "" : slug(stem(file)) + "-") + (top.isEmpty() ? "general" : slug(top));
        base = "cfg-" + base;
        if (base.length() > 32) base = base.substring(0, 32);
        String id = part > 0 ? base + "-" + part : base;
        int n = 2;
        while (!used.add(id)) id = (part > 0 ? base + "-" + part : base) + "-" + n++;
        return id;
    }

    static String settingId(String path, Set<String> used) {
        String id = path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (id.length() > 64 || !used.add(id)) {
            String hash = Integer.toHexString(path.hashCode());
            id = id.substring(0, Math.min(id.length(), 63 - hash.length())) + "-" + hash;
            int n = 2;
            String base = id;
            while (!used.add(id)) id = base.substring(0, Math.min(base.length(), 60)) + "-" + n++;
        }
        return id;
    }

    static String slug(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "x" : s;
    }

    /** Comments above and next to a key in the default file, joined; blank and separator lines dropped. */
    static String comment(YamlConfiguration schema, String path) {
        if (path == null || path.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        try {
            for (String c : schema.getComments(path)) if (c != null && !c.isBlank()) lines.add(c.trim());
            for (String c : schema.getInlineComments(path)) if (c != null && !c.isBlank()) lines.add(c.trim());
        } catch (RuntimeException | LinkageError ignored) { return ""; }
        String joined = String.join(" ", lines).replaceAll("[-=#*]{4,}", "").replaceAll("\\s+", " ").trim();
        return joined.length() > MAX_DESCRIPTION ? joined.substring(0, MAX_DESCRIPTION - 1) + "…" : joined;
    }

    /** A range written in the comment ({@code (1-64)}, {@code 0..100}, "range 1 to 10"); null if none fits the default. */
    static double[] commentRange(String comment, double def, boolean whole) {
        if (comment == null || comment.isEmpty()) return null;
        Matcher m = RANGE.matcher(comment);
        while (m.find()) {
            String a = m.group(1) != null ? m.group(1) : m.group(3), b = m.group(1) != null ? m.group(2) : m.group(4);
            try {
                double min = Double.parseDouble(a.replace(',', '.')), max = Double.parseDouble(b.replace(',', '.'));
                if (whole && (min != Math.rint(min) || max != Math.rint(max))) continue;
                if (min < max && def >= min && def <= max) return new double[] {min, max};
            } catch (NumberFormatException ignored) { /* try the next match */ }
        }
        return null;
    }

    private static double round(double v) { return Math.round(v * 1_000_000d) / 1_000_000d; }

    static String plainNumber(double value) {
        if (!Double.isFinite(value)) return Double.toString(value);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** For tests: sections built for a plugin. */
    List<Built> built(Plugin owner) { PluginState s = plugins.get(owner); return s == null ? List.of() : List.copyOf(s.built); }
    /** For tests: values listed as "edit in file". */
    Map<String, List<String>> fileOnly(Plugin owner) { PluginState s = plugins.get(owner); return s == null ? Map.of() : Map.copyOf(s.fileOnly); }
}
