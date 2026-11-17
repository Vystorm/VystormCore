package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Manager view of {@link ConfigEditor}, reached through {@code api.settings().config()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link ConfigEditor}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface ConfigEditorInterface {
    /** The delegating default. Plugins use {@code api.settings().config()}. */
    ConfigEditorInterface DEFAULT = new ConfigEditorInterface() {};

    /** Registers the plugin's {@code config.yml} without a reload hook (see {@link #reloadHook}). */
    default boolean register(Plugin owner) { return ConfigEditor.register(owner); }

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
    default boolean register(Plugin owner, String file, Runnable reload) { return ConfigEditor.register(owner, file, reload); }

    /**
     * {@link #register(Plugin, String, Runnable)} with an explicit choice whether missing default keys are added to the
     * file on disk once per enable ({@link #mergeDefaults(Plugin, String)}, with backup). {@code false} for table-like
     * files whose entries admins delete on purpose; {@code true} for settings files that should receive new keys from
     * plugin updates. Guard calls with {@link Vystorm.vystorm_core.CoreApi.Capability#CONFIG_EDITOR_MERGE_OPTION}.
     *
     * @return false if the jar has no such default file
     * @since 0.27.1
     */
    default boolean register(Plugin owner, String file, Runnable reload, boolean mergeDefaults) {
        return ConfigEditor.register(owner, file, reload, mergeDefaults);
    }

    /**
     * Registers every settings file at the root of the plugin jar (all {@code *.yml} except plugin descriptors,
     * {@code setup.yml}, {@code legacy-*} and anything in sub-folders). Only {@code config.yml} gets missing defaults merged.
     * Guard with {@link Vystorm.vystorm_core.CoreApi.Capability#CONFIG_MENU}.
     *
     * @return number of registered files
     * @since 0.54.0
     */
    default int registerAll(Plugin owner, Runnable reload) { return ConfigEditor.registerAll(owner, reload); }

    /** {@link #registerAll(Plugin, Runnable)}; {@code mergeConfig = false} also leaves {@code config.yml} as it is, for plugins where a missing section means "off". */
    default int registerAll(Plugin owner, Runnable reload, boolean mergeConfig) { return ConfigEditor.registerAll(owner, reload, mergeConfig); }

    /** Sets the reload hook used for every file of the plugin registered without an own hook. */
    default void reloadHook(Plugin owner, Runnable reload) { ConfigEditor.reloadHook(owner, reload); }

    /**
     * Gives a key a label and description (both may be {@code Lang.ref} references); {@code null} keeps the derived
     * label ({@code max-size} → "Max size") or the comment from the default file. Applies to every registered file of
     * the plugin that has this path; may be called before or after {@link #register}.
     */
    default void label(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        ConfigEditor.label(owner, path, labelOrLangRef, descriptionRef);
    }

    /** Value range of a number key; a slider when the range has at most 1000 steps, otherwise a checked text field. */
    default void range(Plugin owner, String path, double min, double max, double step) { ConfigEditor.range(owner, path, min, max, step); }

    /** Fixed choices of a string key (stored value → label, insertion order; ids {@code [A-Za-z0-9_.:-]{1,64}}). */
    default void choices(Plugin owner, String path, LinkedHashMap<String, String> choices) { ConfigEditor.choices(owner, path, choices); }

    /**
     * Declares a boolean key of {@code config.yml} as a feature switch (shown in {@code /vadmin features}). Keys named
     * {@code enabled} are feature switches without a declaration; use this for other names ({@code auto-protect}).
     * Guard with {@link Vystorm.vystorm_core.CoreApi.Capability#FEATURE_SWITCHES}.
     *
     * @since 0.60.0
     */
    default void feature(Plugin owner, String path) { ConfigEditor.feature(owner, path); }

    /** {@link #feature(Plugin, String)} with a label and description (both may be {@code Lang.ref} references; null keeps the derived text). @since 0.60.0 */
    default void feature(Plugin owner, String path, String labelOrLangRef, String descriptionRef) {
        ConfigEditor.feature(owner, path, labelOrLangRef, descriptionRef);
    }

    /** Keeps a key out of the feature switches, also when it is named {@code enabled}. @since 0.60.0 */
    default void notFeature(Plugin owner, String path) { ConfigEditor.notFeature(owner, path); }

    /** Opens the feature switches of all plugins the player may configure (a Vystorm screen; without one the config editor). @since 0.60.0 */
    default void openFeatures(Player player) { ConfigEditor.openFeatures(player); }

    /** Opens the feature switches of one plugin; false when the plugin is unknown, has no switches or the player lacks the permission. @since 0.60.0 */
    default boolean openFeatures(Player player, String plugin) { return ConfigEditor.openFeatures(player, plugin); }

    /** Never shows this key (or anything below it). */
    default void hidden(Plugin owner, String path) { ConfigEditor.hidden(owner, path); }

    /**
     * When changes of this key (or anything below it) take effect; shown in drafts and after saving. Default:
     * {@code IMMEDIATE} with a reload hook, otherwise {@code RELOAD}. {@code RESTART} tells the admin to restart.
     */
    default void activation(Plugin owner, String path, SettingPolicy.Activation activation) { ConfigEditor.activation(owner, path, activation); }

    /**
     * Adds every key of the jar's {@code config.yml} that is missing in the data folder file (with its comments).
     * Existing values are never changed; before writing, the old file is copied to {@code config.yml.bak-<date>}.
     * Registering {@code config.yml} does this once per enable automatically. Nothing is written if the file is not
     * valid YAML or contains keys with a dot (Bukkit would turn them into nested keys).
     *
     * @return number of added values
     */
    default int mergeDefaults(Plugin owner) { return ConfigEditor.mergeDefaults(owner); }

    /** {@link #mergeDefaults(Plugin)} for another YAML file of the plugin. */
    default int mergeDefaults(Plugin owner, String file) { return ConfigEditor.mergeDefaults(owner, file); }

    /** Removes all registrations of the plugin (automatic on disable). */
    default void unregister(Plugin owner) { ConfigEditor.unregister(owner); }

    /** Names of the plugins whose configuration the sender may edit, sorted. */
    default List<String> plugins(CommandSender viewer) { return ConfigEditor.plugins(viewer); }

    /** Opens the overview of all plugin configurations the player may edit. */
    default void openOverview(Player player) { ConfigEditor.openOverview(player); }

    /** Opens the config sections of one plugin (name case-insensitive); false if unknown or not allowed. */
    default boolean open(Player player, String plugin) { return ConfigEditor.open(player, plugin); }

    /** {@code vystorm_core.config.<plugin name lower-case>}. */
    default String permission(Plugin owner) { return ConfigEditor.permission(owner); }

    /**
     * Sends the change history of the config editor ({@code /vadmin config history}): newest first, only plugins the
     * viewer may configure, each entry with a clickable undo. {@code plugin} null = all plugins; pages start at 1.
     *
     * @since 0.44.0
     */
    default void history(CommandSender viewer, String plugin, int page) { ConfigEditor.history(viewer, plugin, page); }

    /**
     * Undoes one recorded change ({@code /vadmin config undo <id> [force]}): the values go back, the file is saved with the
     * usual backup and reload, and the undo is recorded itself. Values changed again since then block the undo unless
     * {@code force}. Messages go to the viewer.
     *
     * @return whether the change was undone
     * @since 0.44.0
     */
    default boolean undo(CommandSender viewer, int id, boolean force) { return ConfigEditor.undo(viewer, id, force); }

    /**
     * Whether a config path is treated as secret: last part {@code password, passwort, secret, token, api-key, apikey,
     * key, jdbc, url, username, user, host} (also as suffix {@code *-password}, {@code *_token}, {@code dbPassword} …,
     * case-insensitive; since 0.27.1 also {@code pass, pwd, uri, dsn, jdbc-url, webhook, *-webhook, *-secret-key,
     * private-key, access-key, connection-string}), or anything below {@code database.shared}, {@code storage.mysql} or a
     * part named {@code credentials}.
     */
    default boolean secret(String path) { return ConfigEditor.secret(path); }
}
