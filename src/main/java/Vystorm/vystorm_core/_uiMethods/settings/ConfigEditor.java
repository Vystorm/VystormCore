package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Makes a whole plugin YAML file editable in Core's native admin settings without writing one {@link Setting} per key.
 * <p>
 * The <b>default file in the plugin jar</b> ({@code owner.getResource(file)}) is the schema: key order, value types and
 * the comments above/next to each key (shown as descriptions). Values come from the file in the plugin's data folder;
 * missing keys show the jar default. Every top-level key becomes one server section (sections above
 * {@link #MAX_PER_SECTION} values are split); all sections of a plugin are listed under one entry
 * "Configuration: &lt;Plugin&gt;" ({@code /vadmin config <plugin>}, {@code /settings} → server settings).
 * <p>
 * Types: boolean → toggle; whole numbers → slider for a small known range ({@link #range} or a comment such as
 * {@code (1-64)}), otherwise a validated whole-number text field; decimals → validated decimal text field (slider only
 * with {@link #range}); strings → text (≤ 256 characters) or {@link #choices}; lists of plain values → comma-separated
 * text that is saved back as a list; lists of maps and other structures are only listed as "edit in file" (never
 * rewritten). Secrets ({@link #secret(String)}) are never shown, saved through the editor or exported in profiles.
 * <p>
 * Rights: only players with {@code vystorm_core.config.<plugin name lower-case>} (child of
 * {@value #PERMISSION_ALL}, which is a child of {@code vystorm_core.admin}; default op) see and change the values.
 * <p>
 * Saving writes only the changed keys into the file as it is on disk (other keys, hand edits and comments stay; a
 * backup is written before the first save of a file per enable) and runs the reload hook; failures of the hook are
 * contained and reported to the admin. Without a hook the admin is told that the change applies after the plugin's
 * reload command or a restart. Registering {@code config.yml} also adds its missing default keys to the file once
 * ({@link #mergeDefaults}); other files only with {@link #register(Plugin, String, Runnable, boolean)} and
 * {@code mergeDefaults = true} (since 0.27.1), so entries an admin deleted from a table-like file stay deleted.
 * <p>
 * All methods: <b>server thread</b>. Registrations of a disabled plugin are released automatically.
 * <pre>{@code
 * if (CoreApi.has(CoreApi.Capability.CONFIG_EDITOR)) {
 *     ConfigEditor.register(this, "config.yml", this::reloadSettings);
 *     ConfigEditor.range(this, "fields.max-size", 16, 4096, 16);
 * }
 * }</pre>
 *
 * <p>Preferred access: {@code api.settings().config()}.
 *
 * @since 0.27.0
 */
public final class ConfigEditor {
    /** Prefix of the per-plugin permission {@code vystorm_core.config.<plugin>}. */
    public static final String PERMISSION_PREFIX = "vystorm_core.config.";
    /** Parent of every per-plugin config permission (child of {@code vystorm_core.admin}). */
    public static final String PERMISSION_ALL = "vystorm_core.config.*";
    /** Most values in one dialog section; bigger top-level groups are split into parts. */
    public static final int MAX_PER_SECTION = 24;
    /** {@link SettingsRegistry#group(String)} of editor sections. */
    public static final String GROUP = "config";

    private static volatile ConfigEditorService service;

    private ConfigEditor() {}

    static ConfigEditorService service() {
        ConfigEditorService s = service;
        if (s != null) return s;
        synchronized (ConfigEditor.class) {
            if (service == null) service = ConfigEditorService.bukkit();
            return service;
        }
    }

    /** Test seam / replacement of the default service. */
    static void install(ConfigEditorService replacement) { service = replacement; }

    /** Registers the plugin's {@code config.yml} without a reload hook (see {@link #reloadHook}). */
    public static boolean register(Plugin owner) { return service().register(owner, "config.yml", null); }

    /**
     * Registers a YAML file of the plugin (path relative to the data folder, e.g. {@code config.yml} or
     * {@code spawns.yml}) whose default is in the jar. Registering the same file again replaces the hook.
     * <p>
     * Since 0.27.1 only {@code config.yml} gets its missing default keys merged into the file automatically. Other files
     * (spawn/loot/mob tables …) are left as they are: keys missing there still show the jar default in the editor, but
     * nothing is written until an admin saves. Use {@link #register(Plugin, String, Runnable, boolean)} to opt in.
     *
     * @param reload runs after a save (the plugin's own reload logic); {@code null} = applies after reload/restart
     * @return false if the jar has no such default file
     */
    public static boolean register(Plugin owner, String file, Runnable reload) { return service().register(owner, file, reload); }

    /**
     * {@link #register(Plugin, String, Runnable)} with an explicit choice whether missing default keys are added to the
     * file on disk once per enable ({@link #mergeDefaults(Plugin, String)}, with backup). {@code false} for table-like
     * files whose entries admins delete on purpose; {@code true} for settings files that should receive new keys from
     * plugin updates. Guard calls with {@link Vystorm.vystorm_core.CoreApi.Capability#CONFIG_EDITOR_MERGE_OPTION}.
     *
     * @return false if the jar has no such default file
     * @since 0.27.1
     */
    public static boolean register(Plugin owner, String file, Runnable reload, boolean mergeDefaults) {
        return service().register(owner, file, reload, mergeDefaults);
    }

    /**
     * Registers every settings file at the root of the plugin jar (all {@code *.yml} except plugin descriptors,
     * {@code setup.yml}, {@code legacy-*} and anything in sub-folders). Only {@code config.yml} gets missing defaults merged.
     * Guard with {@link Vystorm.vystorm_core.CoreApi.Capability#CONFIG_MENU}.
     *
     * @return number of registered files
     * @since 0.54.0
     */
    public static int registerAll(Plugin owner, Runnable reload) { return service().registerAll(owner, reload); }

    /** {@link #registerAll(Plugin, Runnable)}; {@code mergeConfig = false} also leaves {@code config.yml} as it is, for plugins where a missing section means "off". */
    public static int registerAll(Plugin owner, Runnable reload, boolean mergeConfig) { return service().registerAll(owner, reload, mergeConfig); }

    /** Sets the reload hook used for every file of the plugin registered without an own hook. */
    public static void reloadHook(Plugin owner, Runnable reload) { service().reloadHook(owner, reload); }

    /**
     * Gives a key a label and description (both may be {@code Lang.ref} references); {@code null} keeps the derived
     * label ({@code max-size} → "Max size") or the comment from the default file. Applies to every registered file of
     * the plugin that has this path; may be called before or after {@link #register}.
     */
    public static void label(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        service().label(owner, path, labelOrLangRef, descriptionRef);
    }

    /** Value range of a number key; a slider when the range has at most 1000 steps, otherwise a checked text field. */
    public static void range(Plugin owner, String path, double min, double max, double step) { service().range(owner, path, min, max, step); }

    /** Fixed choices of a string key (stored value → label, insertion order; ids {@code [A-Za-z0-9_.:-]{1,64}}). */
    public static void choices(Plugin owner, String path, LinkedHashMap<String, String> choices) { service().choices(owner, path, choices); }

    /**
     * Declares a boolean key of {@code config.yml} as a feature switch (shown in {@code /vadmin features}). Keys named
     * {@code enabled} are feature switches without a declaration; use this for other names ({@code auto-protect}).
     * Guard with {@link Vystorm.vystorm_core.CoreApi.Capability#FEATURE_SWITCHES}.
     *
     * @since 0.60.0
     */
    public static void feature(Plugin owner, String path) { service().feature(owner, path, true); }

    /** {@link #feature(Plugin, String)} with a label and description (both may be {@code Lang.ref} references; null keeps the derived text). @since 0.60.0 */
    public static void feature(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        service().feature(owner, path, true);
        if (labelOrLangRef != null || descriptionRef != null) service().label(owner, path, labelOrLangRef, descriptionRef);
    }

    /** Keeps a key out of the feature switches, also when it is named {@code enabled}. @since 0.60.0 */
    public static void notFeature(Plugin owner, String path) { service().feature(owner, path, false); }

    /** Opens the feature switches of all plugins the player may configure (a Vystorm screen; without one the config editor). @since 0.60.0 */
    public static void openFeatures(Player player) { FeatureScreen.overview(service(), player, 0); }

    /** Opens the feature switches of one plugin; false when the plugin is unknown, has no switches or the player lacks the permission. @since 0.60.0 */
    public static boolean openFeatures(Player player, String plugin) {
        ConfigEditorService.PluginState state = service().stateByName(plugin);
        if (state == null || !ConfigEditorService.allowed(state, player) || service().features(state).isEmpty()) return false;
        FeatureScreen.plugin(service(), player, state, 0);
        return true;
    }

    /** Never shows this key (or anything below it). */
    public static void hidden(Plugin owner, String path) { service().hidden(owner, path); }

    /**
     * When changes of this key (or anything below it) take effect; shown in drafts and after saving. Default:
     * {@code IMMEDIATE} with a reload hook, otherwise {@code RELOAD}. {@code RESTART} tells the admin to restart.
     */
    public static void activation(Plugin owner, String path, SettingPolicy.Activation activation) { service().activation(owner, path, activation); }

    /**
     * Adds every key of the jar's {@code config.yml} that is missing in the data folder file (with its comments).
     * Existing values are never changed; before writing, the old file is copied to {@code config.yml.bak-<date>}.
     * Registering {@code config.yml} does this once per enable automatically. Nothing is written if the file is not
     * valid YAML or contains keys with a dot (Bukkit would turn them into nested keys).
     *
     * @return number of added values
     */
    public static int mergeDefaults(Plugin owner) { return service().mergeDefaults(owner, "config.yml"); }

    /** {@link #mergeDefaults(Plugin)} for another YAML file of the plugin. */
    public static int mergeDefaults(Plugin owner, String file) { return service().mergeDefaults(owner, file); }

    /** Removes all registrations of the plugin (automatic on disable). */
    public static void unregister(Plugin owner) { service().release(owner); }

    /** Names of the plugins whose configuration the sender may edit, sorted. */
    public static List<String> plugins(CommandSender viewer) { return service().plugins(viewer); }

    /** Core only: starts the chest menu and the first-run setups. */
    public static void startMenu(Plugin core) { ConfigMenu.start(core); FirstRunSetup.start(core); }

    /** Opens the overview of all plugin configurations the player may edit. */
    public static void openOverview(Player player) { service().showOverview(player); }

    /** Opens the config sections of one plugin (name case-insensitive); false if unknown or not allowed. */
    public static boolean open(Player player, String plugin) { return service().open(player, plugin); }

    /** {@code vystorm_core.config.<plugin name lower-case>}. */
    public static String permission(Plugin owner) { return PERMISSION_PREFIX + owner.getName().toLowerCase(Locale.ROOT); }

    /**
     * Sends the change history of the config editor ({@code /vadmin config history}): newest first, only plugins the
     * viewer may configure, each entry with a clickable undo. {@code plugin} null = all plugins; pages start at 1.
     *
     * @since 0.44.0
     */
    public static void history(CommandSender viewer, String plugin, int page) { service().sendHistory(viewer, plugin, page); }

    /**
     * Undoes one recorded change ({@code /vadmin config undo <id> [force]}): the values go back, the file is saved with the
     * usual backup and reload, and the undo is recorded itself. Values changed again since then block the undo unless
     * {@code force}. Messages go to the viewer.
     *
     * @return whether the change was undone
     * @since 0.44.0
     */
    public static boolean undo(CommandSender viewer, int id, boolean force) { return service().undo(viewer, id, force) != null; }

    /**
     * Whether a config path is treated as secret: last part {@code password, passwort, secret, token, api-key, apikey,
     * key, jdbc, url, username, user, host} (also as suffix {@code *-password}, {@code *_token}, {@code dbPassword} …,
     * case-insensitive; since 0.27.1 also {@code pass, pwd, uri, dsn, jdbc-url, webhook, *-webhook, *-secret-key,
     * private-key, access-key, connection-string}), or anything below {@code database.shared}, {@code storage.mysql} or a
     * part named {@code credentials}.
     */
    public static boolean secret(String path) { return ConfigEditorService.secret(path); }
}
