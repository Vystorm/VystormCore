package Vystorm.vystorm_core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Version and capability query for consumer plugins. Replaces probes such as {@code Class.forName(".. ChatActions")},
 * {@code getMethod} tests or {@code catch (LinkageError)}.
 *
 * <pre>{@code
 * // Core < 0.11.0 does not know this class: then the answer is "too old".
 * boolean ok;
 * try { ok = CoreApi.has(CoreApi.Capability.ITEM_SERVICES); } catch (LinkageError tooOld) { ok = false; }
 * if (!ok) { getLogger().severe("Vystorm Core >= 0.10.0 required"); disable(); return; }
 * }</pre>
 *
 * <p>{@link #VERSION} is deliberately not a compile-time constant, so consumers see the runtime value and not
 * the value they were built against. All methods are thread-safe.
 *
 * @since 0.11.0
 */
public final class CoreApi {
    /** Running Core version, e.g. {@code 0.11.0-Vystorm-Core-26.2}; {@code 0.0.0} if unknown. */
    public static final String VERSION = loadVersion();

    /** Capability names (strings, so new capabilities do not affect old consumers). */
    public static final class Capability {
        private Capability() {}
        /**
         * Everything is reached through the managers of {@link API}: the features of the static classes are parts of
         * the existing managers ({@code api.data().files()}, {@code api.settings().config()},
         * {@code api.message().chat()}, {@code api.gui().panels()} ...) or of the new ones ({@code api.compat()},
         * {@code tasks()}, {@code loot()}, {@code web()}, {@code parties()}, {@code items()}, {@code mobs()},
         * {@code runtime()}). The static classes remain. @since 0.64.0
         */
        public static final String MANAGERS = "managers";
        /**
         * {@code api.priority().stages()}: handlers of shared Bukkit events registered with a named stage, dispatched
         * by Core in one configured plugin order ({@code event-order} in Core's config.yml). @since 0.64.0
         */
        public static final String EVENT_STAGES = "event-stages";
        /**
         * {@code api.placeholders()}: one registry for the values of all Vystorm plugins
         * ({@code %vystorm_<namespace>_<key>%}, <code>{namespace:key}</code>), Core's own values, the bridge to
         * PlaceholderAPI in both directions and {@code /vadmin placeholders}. @since 0.65.0
         */
        public static final String PLACEHOLDERS = "placeholders";
        /**
         * {@code api.gui().fileMenus()}: screen menus from files in {@code plugins/vystorm_core/menus} (format
         * {@code vystorm-menu} version 1, written by the menu editor), opened with {@code /vmenu <id>}, their own
         * command or their tile in the main menu; {@code /vadmin menus}. @since 0.65.0
         */
        public static final String FILE_MENUS = "file-menus";
        /**
         * {@code CommandSpec.Builder.visibleWhen}: tab completion and help of a command node follow a condition
         * (feature switch) at once; {@code api.command().refresh()} sends the players their command list again; a
         * second {@code register} of the same label by the same plugin after startup keeps the Paper node. @since 0.65.0
         */
        public static final String COMMAND_VISIBILITY = "command-visibility";
        /**
         * {@code Ui.Section.list()}: a panel section drawn as a settings list on Vystorm Screens (one full-width row per
         * setting: name, value on the right, description under the name, the whole row clickable). @since 0.66.0
         */
        public static final String PANEL_LIST_LAYOUT = "panel-list-layout";
        /**
         * Rankings as placeholders: {@code PlaceholderNamespace.ranking(key, RankingInfo, RankingSource)} declares
         * {@code %vystorm_<ns>_top_<key>_<n>_name|value|raw|uuid%}, {@code ..._rank_<key>%} and {@code ..._value_<key>%};
         * {@code api.placeholders().rankings()}, {@code top(id, n)}, {@code topAsync}, {@code rank}, {@code rankingValue};
         * {@code /vadmin placeholders rankings}. Plugins check {@code CoreApi.has("placeholder-rankings")}. @since 0.67.0
         */
        public static final String PLACEHOLDER_RANKINGS = "placeholder-rankings";
        /** Shared Bukkit/Spigot adapters, native persistence and portable client events. @since 0.51.0 */
        public static final String BUKKIT_COMPAT = "bukkit-compat";
        /** Cross-server network via a Velocity/BungeeCord hub: {@link #network()}, {@code /vnet}, server selector. @since 0.53.0 */
        public static final String NETWORK = "network";
        /** Clear the personal recent-menu history without changing favorites. @since 0.49.0 */
        public static final String MENU_HISTORY_CLEAR = "menu-history-clear";
        /** Paper 1.20.6+ feature detection and portable UI forms. @since 0.48.0 */
        public static final String SERVER_COMPAT = "server-compat";
        /** Source-based stat contributions. @since 0.35.0 */
        public static final String STAT_SUMS = "stat-sums";
        /** Signed anvil previews and delivery commits for managed items. @since 0.39.0 */
        public static final String ITEM_ANVIL = "item-anvil";
        /** Shared weighted mob/chest loot tables. @since 0.34.0 */
        public static final String LOOT_TABLES = "loot-tables";
        /** {@code loot.LootRoll}: categories, table references, luck, tier filters, pity, seeds, conditions, {@code /vadmin loot}. @since 0.44.0 */
        public static final String LOOT_ROLLS = "loot-rolls";
        /** {@code loot.MobLoot}: mob drops from the mob's own table, luck from the mob level only, kill precedence, {@code LootRoll.forMob}. @since 0.44.0 */
        public static final String MOB_LOOT = "mob-loot";
        /** Per-player block projections, aperture geometry and particle paths. @since 0.29.0 */
        public static final String VISUALS = "visuals";
        /** Masked horizontal block projections. @since 0.30.0 */
        public static final String HORIZONTAL_VISUALS = "horizontal-visuals";
        public static final String RUNTIME_HUBS = "runtime-hubs";
        public static final String COMBAT_REGISTRY = "combat-registry";
        public static final String THREAT = "threat";
        public static final String WORLD_EVENTS = "world-events";
        /** Owner-bound event registration, execution and persistence. @since 0.31.0 */
        public static final String WORLD_EVENT_RUNTIME = "world-event-runtime";
        /** Client-only snowy biome packets. @since 0.33.0 */
        public static final String CLIENT_BIOMES = "client-biomes";
        public static final String PROJECTED_BIOMES = "projected-biomes";
        /** {@code display.ClientDisplays}: client-only map walls (fake item frames + map packets), Paper/Purpur 1.21.8-26.2, runtime check {@code ClientDisplays.supported()}. @since 0.44.0 */
        public static final String CLIENT_DISPLAYS = "client-displays";
        /** {@code packet.ClientChunks}: client-only chunk packets (previews of terrain the server has not generated), Paper/Purpur 1.21.8-26.3, runtime check {@code ClientChunks.supported()}. @since 0.45.0 */
        public static final String CLIENT_CHUNKS = "client-chunks";
        public static final String WORLD_TREE_PLANS = "world-tree-plans";
        public static final String FUNGUS_GROWTH_PLANS = "fungus-growth-plans";
        public static final String GIANT_WORLD_TREES = "giant-world-trees";
        /** World-tree roots dig below the planting level; {@code WorldTreePlan.depth}. @since 0.39.1 */
        public static final String UNDERGROUND_TREE_ROOTS = "underground-tree-roots";
        /** SpigotMC update check for installed Vystorm plugins, /vadmin updates. @since 0.40.0 */
        public static final String UPDATE_CHECK = "update-check";
        /** {@code lifecycle.UpdateSources.spigot(plugin, id)}: plugins register their own SpigotMC resource for the update check. @since 0.40.2 */
        public static final String UPDATE_SOURCES = "update-sources";
        /** {@code playermenu.PlayerMenu}: entries in the shift-right-click menu about another player, {@code /menu player}. @since 0.43.0 */
        public static final String PLAYER_MENU = "player-menu";
        /** {@code news.News}: server news board, {@code /vmenu news}; plugins may post. @since 0.44.0 */
        public static final String NEWS = "news";
        /** Built-in timed server polls. @since 0.47.0 */
        public static final String SERVER_POLLS = "server-polls";
        /** Editing news in place via dialog or command. @since 0.46.0 */
        public static final String NEWS_EDIT = "news-edit";
        /** {@code inbox.Inbox}: personal messages for (offline) players, rendered in the reader's language, {@code /vmenu inbox}. @since 0.55.0 */
        public static final String INBOX = "inbox";
        /** {@code profile.Profiles}: sections on the player profile card, {@code /vmenu profile}. @since 0.56.0 */
        public static final String PROFILES = "profiles";
        /** {@code screen.Screens}: clickable screens in the player's view (PINNED with shader pointer, FLOATING with crosshair). @since 0.57.0 */
        public static final String SCREENS = "screens";
        /** HUD corner window: {@link Vystorm.vystorm_core.screen.Huds}, {@link Vystorm.vystorm_core.screen.HudCard}. @since 0.59.0 */
        public static final String HUD_WINDOW = "hud-window";
        /** {@code commands.CoreCommands}/{@code CommandSpec}/{@code Arg}: central command trees with localized subcommands, keyword values, help, root aliases, {@code /vhelp}, {@code /vadmin commands}, plugin.yml adapter. @since 0.45.0 */
        public static final String COMMANDS = "commands";
        /** {@code ConfigEditor.history}/{@code undo}: change history of the config editor with undo, {@code /vadmin config history}. @since 0.44.0 */
        public static final String CONFIG_HISTORY = "config-history";
        /** Chest menu for all registered configs, {@code ConfigEditor.registerAll} and first-run setups from a plugin's {@code setup.yml}, {@code /vadmin setup}. @since 0.54.0 */
        public static final String CONFIG_MENU = "config-menu";
        /** {@code ConfigEditor.feature/notFeature/openFeatures}: feature switches of a plugin as a screen, {@code /vadmin features}. @since 0.60.0 */
        public static final String FEATURE_SWITCHES = "feature-switches";
        /**
         * {@code /language} selection menu and command, {@code lang/zh_cn.yml} (Simplified Chinese), configurable fallback chain
         * ({@code Localization.fallbackChain}), {@code language.server}/{@code force}/{@code min-coverage}, coverage
         * ({@code Localization.coverage}, {@code Lang.coverage}), {@code PlayerLanguageChangeEvent}, {@code TextWidth},
         * {@code /vadmin language audit|export|import}. @since 0.45.0
         */
        public static final String LANGUAGE_SELECTION = "language-selection";
        /** Durable local reward pouch with idempotent credit and receipt recovery. @since 0.32.0 */
        public static final String REWARD_POUCH = "reward-pouch";
        public static final String VERSIONED_STORE = "versioned-store";
        public static final String CONFIG_MIGRATOR = "config-migrator";
        public static final String TASK_GROUPS = "task-groups";
        public static final String MYTHIC_BRIDGE = "mythic-bridge";
        public static final String REGIONS = "regions";
        public static final String CURVES = "curves";
        public static final String PARTY = "party";
        public static final String REWARD_ELIGIBILITY = "reward-eligibility";
        public static final String PARTY_ACTIVITIES = "party-activities";
        public static final String SETTINGS = "settings";
        public static final String CHAT_CHANNELS = "chat-channels";
        public static final String CHAT_ACTIONS = "chat-actions";
        public static final String MOB_SERVICES = "mob-services";
        public static final String ITEM_REFS = "item-refs";
        public static final String ITEM_SERVICES = "item-services";
        public static final String RESOURCE_PACK = "resource-pack";
        public static final String DISCIPLINES = "disciplines";
        /** Automatic clean-up of all registries when a plugin is disabled ({@code lifecycle.CoreLifecycle}). */
        public static final String LIFECYCLE_CLEANUP = "lifecycle-cleanup";
        /** {@code MobExtensions.sink(Plugin, TriggerSink)}. */
        public static final String TRIGGER_SINK_OWNER = "trigger-sink-owner";
        /** Several providers per {@code ItemRefs} namespace with priority, MythicMobs/MMOItems as the built-in fallback. */
        public static final String ITEM_REF_PRIORITY = "item-ref-priority";
        /** {@code mob.MobRefs}: Vystorm mobs before MythicMobs. */
        public static final String MOB_REFS = "mob-refs";
        /** {@link Vystorm.vystorm_core.spawn.SpawnPointService}: fixed spawn points per region. */
        public static final String SPAWN_POINTS = "spawn-points";
        /** Shared MySQL/MariaDB database: {@code database.SharedDatabase}, {@code SchemaMigrator}, {@code api.database().shared()}. */
        public static final String SHARED_DATABASE = "shared-database";
        /** {@code progression.CharacterServices}: character level and character XP (Vystorm Skills). */
        public static final String CHARACTER = "character";
        /** {@code web.WebServices}: web modules (pages and API) through Core's web platform (embedded or gateway). */
        public static final String WEB = "web";
        /** {@code WebServices.push}, {@code publishData}, {@code WebModule.Builder.snapshot}: live channel and versioned game data. */
        public static final String WEB_LIVE = "web-live";
        /** {@code web.ClientServices}: channel {@code vystorm:web} for the optional Vystorm client mod (overlay instead of menu). */
        public static final String WEB_CLIENT = "web-client";
        /**
         * {@code web.ClientUi}/{@code web.ClientElement}: native elements of the client mod (channel {@code vystorm:ui}),
         * events {@code VystormClientReadyEvent}, {@code VystormClientStateEvent}, {@code VystormClientUiRejectEvent}.
         */
        public static final String WEB_CLIENT_UI = "web-client-ui";
        /**
         * Client protocol 2: the client mod (≥ 0.4.0) reports the consent to the web origin
         * ({@code ClientServices.STATE_ORIGIN_TRUSTED}, {@code originTrusted}); {@code overlayAvailable} is therefore valid
         * right after the join.
         */
        public static final String WEB_CLIENT_TRUST = "web-client-trust";
        /**
         * {@code api.lang()} / {@code lang.Localization}: language bundles {@code lang/<code>.yml} per plugin, language per
         * viewer (client locale, personal choice, {@code language.default}), MiniMessage with {@code {name}} placeholders.
         */
        public static final String LOCALIZATION = "localization";
        /**
         * Localized web platform: per-request language ({@code WebContext.language()}), {@code /core/i18n}, {@code {{…}}}
         * tokens in HTML assets, {@code Lang.ref} as module title/description and {@code WebException} message.
         */
        public static final String WEB_LOCALIZATION = "web-localization";
        /**
         * {@code WebServices.openOrLink/openOrElse} (overlay if the client mod is present and trusted, else chat link or
         * the plugin's fallback), {@code WebServices.overlayPage}, {@code WebServices.broadcast}; Core's jar bundles the
         * web UI (default UI without gateway or {@code web.ui-dir}).
         */
        public static final String WEB_OPEN = "web-open";
        /**
         * Web page {@code /app/settings} for every settings section registered through {@code api.settings()} (live both
         * ways), {@code SettingsInterface.changed(player)}, {@code SettingsRegistry.view/save/coerce/onChange}.
         */
        public static final String WEB_SETTINGS = "web-settings";
        /**
         * {@code panel.Panels}/{@code PanelSpec}/{@code Ui}: native panels of the client mod (channel {@code vystorm:panel}),
         * action validation, diff/PATCH, fallback to the plugin menu, Paper dialog or web link; events
         * {@code VystormPanelOpenEvent}, {@code VystormPanelCloseEvent}, {@code VystormPanelErrorEvent},
         * {@code GuiInteface.unregisterMenu}. Since 0.25.1: typed element builders ({@code Ui.Heading}, {@code Ui.Text},
         * {@code Ui.NumberField} …) instead of one collective builder – plugins need 0.25.1.
         */
        public static final String PANELS = "panels";
        /**
         * {@code worlds.ManagedWorlds}: plugin worlds with their own generator, rebuild on a changed layout hash,
         * game rules, world profile, protection region, visits with state saved on disk ({@code VisitService}),
         * command allow list and visibility; {@code /vworlds}.
         */
        public static final String MANAGED_WORLDS = "managed-worlds";
        /** {@code item.ItemRestrictions}: Core lock for items; {@code ItemServices.validate} reports them as QUARANTINED. */
        public static final String ITEM_RESTRICTIONS = "item-restrictions";
        /** Durable background visit admission with main-thread state revalidation. @since 0.26.2 */
        public static final String ASYNC_DURABLE_VISITS = "async-durable-visits";
        /** Typed restore outcomes and hardened managed-world recovery. @since 0.26.1 */
        public static final String VISIT_RESTORE_OUTCOME = "visit-restore-outcome";
        /** {@code api.admin()}: own {@code /vadmin} modules; a module with the ID of a built-in catalog entry replaces it. Feature is older, flag @since 0.26.4 */
        public static final String ADMIN_MODULES = "admin-modules";
        /** {@code api.registerTutorial(Plugin)} / {@code TutorialGuide}. Feature is older, flag @since 0.26.4 */
        public static final String TUTORIALS = "tutorials";
        /** {@code _coreMethods.dataStorage.AtomicFiles} (since 0.11.0). Flag @since 0.26.4 */
        public static final String ATOMIC_FILES = "atomic-files";
        /** {@code mob.MobService.spawnRules()} / {@code MobSpawnRule} (since 0.15.0). Flag @since 0.26.4 */
        public static final String MOB_SPAWN_RULES = "mob-spawn-rules";
        /** {@code TaskGroup} forgets one-shot tasks that have run and schedules through the plugin's server. @since 0.26.4 */
        public static final String TASK_GROUP_ONE_SHOTS = "task-group-one-shots";
        /** {@code mythic.MmoItemsBridge.idOf(ItemStack)}: MMOItems {@code TYPE:ID} of a stack. @since 0.26.4 */
        public static final String MMOITEMS_IDS = "mmoitems-ids";
        /** {@code WebServices.openOrLink(Player, String, String, Component, Runnable)} with its own link label. @since 0.26.4 */
        public static final String WEB_OPEN_LABEL = "web-open-label";
        /** {@code CombatRegistry.Kind} (HARM/SUPPORT) in the context, {@code evaluate(a, t, kind)}, {@code kindOf(effects)}. @since 0.26.4 */
        public static final String COMBAT_KINDS = "combat-kinds";
        /**
         * Native elements for client ≥ 0.9.0 ({@code ClientServices.CAP_UI_EXTENSIONS}): {@code ClientElement.list},
         * {@code Bar.rate/countdown} and label placeholders, {@code Hud.stack}, {@code Marker.entity/yOffset/ground},
         * tooltip placeholders {@code ClientElement.data(path)} and {@code matchCustomDataPresent/Viewer} (AND),
         * {@code Icon.item(ItemStack)}, {@code ClientUi.visible}. @since 0.26.4
         */
        public static final String CLIENT_UI_EXTENSIONS = "client-ui-extensions";
        /** {@code ClientUi.toast(Player, Toast, Consumer<Player>)}: toasts with an action (key of the client). @since 0.26.4 */
        public static final String CLIENT_TOAST_ACTIONS = "client-toast-actions";
        /** {@code panel.PanelTesting} and {@code web.ClientUiTesting} for unit tests without a server. @since 0.26.4 */
        public static final String CLIENT_UI_TESTING = "client-ui-testing";
        /**
         * Panels: {@code Result.pending()} + {@code PanelAction.complete(Result)}, {@code PanelAction.closeAndThen},
         * {@code Ui.Progress.rate}, {@code Ui.Form.confirm}, {@code Ui.ListView/Table.selected}, {@code ListItem.selected},
         * {@code Ui.Button.openSettings}, {@code Ui.item(ItemStack)}. @since 0.26.4
         */
        public static final String PANEL_EXTRAS = "panel-extras";
        /** @since 0.26.5 */
        public static final String MENU_EXPLORER = "menu-explorer";
        /** @since 0.26.5 */
        public static final String MENU_ACCESS_DIAGNOSTICS = "menu-access-diagnostics";
        /** @since 0.26.5 */
        public static final String SETTINGS_DRAFTS = "settings-drafts";
        /** @since 0.26.5 */
        public static final String SETTINGS_PROFILES = "settings-profiles";
        /** Guided, selective cross-plugin settings presets. @since 0.52.0 */
        public static final String SETUP_WIZARD = "setup-wizard";
        /** @since 0.26.5 */
        public static final String LANGUAGE_AUDIT = "language-audit";
        /** @since 0.26.5 */
        public static final String FEATURE_DIAGNOSTICS = "feature-diagnostics";
        /** @since 0.26.5 */
        public static final String MIGRATION_PREVIEWS = "migration-previews";
        /** @since 0.26.5 */
        public static final String ONBOARDING = "onboarding";
        /** @since 0.26.5 */
        public static final String ROUTINE_MESSAGES = "routine-messages";
        /** @since 0.26.5 */
        public static final String TREE_GROWTH_PROFILES = "tree-growth-profiles";
        /**
         * {@code _uiMethods.settings.ConfigEditor}: whole plugin YAML files in the native admin settings menu
         * ({@code /vadmin config}), schema from the jar file, secrets hidden, permission
         * {@code vystorm_core.config.<plugin>}, missing defaults added with a backup. @since 0.27.0
         */
        public static final String CONFIG_EDITOR = "config-editor";
        /** {@code /vadmin permissions [plugin]}: permission nodes of all Vystorm plugins. @since 0.27.0 */
        public static final String PERMISSION_LIST = "permission-list";
        /**
         * {@code ConfigEditor.register(Plugin, String, Runnable, boolean mergeDefaults)}: only {@code config.yml} gets
         * missing defaults automatically, other files only with {@code mergeDefaults = true}. @since 0.27.1
         */
        public static final String CONFIG_EDITOR_MERGE_OPTION = "config-editor-merge-option";
        /** MythicMobs custom drops resolve native ItemRefs without MMOItems. @since 0.27.2 */
        public static final String MYTHIC_ITEM_DROPS = "mythic-item-drops";
        /** Optional signed RAM modules and expiring customer leases for paid plugins. @since 0.28.0 */
        public static final String LICENSED_MODULES = "licensed-modules";
        /** This class itself. */
        public static final String CORE_API = "core-api";
    }

    private static final Map<String, String> SINCE;

    static {
        Map<String, String> since = new TreeMap<>();
        since.put(Capability.RUNTIME_HUBS, "0.2.0");
        since.put(Capability.COMBAT_REGISTRY, "0.2.0");
        since.put(Capability.THREAT, "0.2.0");
        since.put(Capability.WORLD_EVENTS, "0.3.0");
        since.put(Capability.WORLD_EVENT_RUNTIME, "0.31.0");
        since.put(Capability.REWARD_POUCH, "0.32.0");
        since.put(Capability.CLIENT_BIOMES, "0.33.0");
        since.put(Capability.STAT_SUMS, "0.35.0");
        since.put(Capability.ITEM_ANVIL, "0.40.1");
        since.put(Capability.PROJECTED_BIOMES, "0.36.0");
        since.put(Capability.CLIENT_DISPLAYS, "0.44.0");
        since.put(Capability.CLIENT_CHUNKS, "0.45.0");
        since.put(Capability.WORLD_TREE_PLANS, "0.37.0");
        since.put(Capability.FUNGUS_GROWTH_PLANS, "0.38.0");
        since.put(Capability.GIANT_WORLD_TREES, "0.39.0");
        since.put(Capability.UNDERGROUND_TREE_ROOTS, "0.39.1");
        since.put(Capability.UPDATE_CHECK, "0.40.0");
        since.put(Capability.UPDATE_SOURCES, "0.40.2");
        since.put(Capability.PLAYER_MENU, "0.43.0");
        since.put(Capability.NEWS, "0.44.0");
        since.put(Capability.NEWS_EDIT, "0.46.0");
        since.put(Capability.SERVER_POLLS, "0.47.0");
        since.put(Capability.SERVER_COMPAT, "0.48.0");
        since.put(Capability.BUKKIT_COMPAT, "0.51.0");
        since.put(Capability.NETWORK, "0.53.0");
        since.put(Capability.COMMANDS, "0.45.0");
        since.put(Capability.CONFIG_HISTORY, "0.44.0");
        since.put(Capability.CONFIG_MENU, "0.54.0");
        since.put(Capability.FEATURE_SWITCHES, "0.60.0");
        since.put(Capability.INBOX, "0.55.0");
        since.put(Capability.PROFILES, "0.56.0");
        since.put(Capability.SCREENS, "0.57.0");
        since.put(Capability.HUD_WINDOW, "0.59.0");
        since.put(Capability.LANGUAGE_SELECTION, "0.45.0");
        since.put(Capability.LOOT_TABLES, "0.34.0");
        since.put(Capability.LOOT_ROLLS, "0.44.0");
        since.put(Capability.MOB_LOOT, "0.44.0");
        since.put(Capability.VERSIONED_STORE, "0.3.0");
        since.put(Capability.CONFIG_MIGRATOR, "0.3.0");
        since.put(Capability.TASK_GROUPS, "0.3.0");
        since.put(Capability.MYTHIC_BRIDGE, "0.3.0");
        since.put(Capability.REGIONS, "0.4.0");
        since.put(Capability.CURVES, "0.5.0");
        since.put(Capability.PARTY, "0.6.0");
        since.put(Capability.PARTY_ACTIVITIES, "0.26.5");
        since.put(Capability.REWARD_ELIGIBILITY, "0.26.5");
        since.put(Capability.SETTINGS, "0.7.0");
        since.put(Capability.CHAT_CHANNELS, "0.26.6");
        since.put(Capability.CHAT_ACTIONS, "0.8.0");
        since.put(Capability.MOB_SERVICES, "0.9.0");
        since.put(Capability.ITEM_REFS, "0.9.0");
        since.put(Capability.ITEM_SERVICES, "0.10.0");
        since.put(Capability.RESOURCE_PACK, "0.10.0");
        since.put(Capability.DISCIPLINES, "0.10.0");
        since.put(Capability.LIFECYCLE_CLEANUP, "0.10.3");
        since.put(Capability.TRIGGER_SINK_OWNER, "0.10.3");
        since.put(Capability.CORE_API, "0.11.0");
        since.put(Capability.ITEM_REF_PRIORITY, "0.12.0");
        since.put(Capability.MOB_REFS, "0.12.0");
        since.put(Capability.SPAWN_POINTS, "0.13.0");
        since.put(Capability.SHARED_DATABASE, "0.14.0");
        since.put(Capability.CHARACTER, "0.16.0");
        since.put(Capability.WEB, "0.17.0");
        since.put(Capability.WEB_LIVE, "0.18.0");
        since.put(Capability.WEB_CLIENT, "0.19.0");
        since.put(Capability.WEB_CLIENT_UI, "0.20.0");
        since.put(Capability.WEB_CLIENT_TRUST, "0.21.0");
        since.put(Capability.LOCALIZATION, "0.22.0");
        since.put(Capability.WEB_LOCALIZATION, "0.23.0");
        since.put(Capability.WEB_OPEN, "0.24.0");
        since.put(Capability.WEB_SETTINGS, "0.24.0");
        since.put(Capability.PANELS, "0.25.1");   // API rebuilt before the freeze (typed builders), 0.25.0 no longer valid
        since.put(Capability.MANAGED_WORLDS, "0.26.0");
        since.put(Capability.ITEM_RESTRICTIONS, "0.26.0");
        since.put(Capability.VISIT_RESTORE_OUTCOME, "0.26.1");
        since.put(Capability.ASYNC_DURABLE_VISITS, "0.26.2");
        // Older features whose flag only arrived in 0.26.4: on older Cores check via LinkageError or atLeast.
        since.put(Capability.ADMIN_MODULES, "0.26.4");
        since.put(Capability.TUTORIALS, "0.26.4");
        since.put(Capability.ATOMIC_FILES, "0.26.4");
        since.put(Capability.MOB_SPAWN_RULES, "0.26.4");
        since.put(Capability.TASK_GROUP_ONE_SHOTS, "0.26.4");
        since.put(Capability.MMOITEMS_IDS, "0.26.4");
        since.put(Capability.WEB_OPEN_LABEL, "0.26.4");
        since.put(Capability.COMBAT_KINDS, "0.26.4");
        since.put(Capability.CLIENT_UI_EXTENSIONS, "0.26.4");
        since.put(Capability.CLIENT_TOAST_ACTIONS, "0.26.4");
        since.put(Capability.CLIENT_UI_TESTING, "0.26.4");
        since.put(Capability.PANEL_EXTRAS, "0.26.4");
        since.put(Capability.MENU_EXPLORER, "0.26.5");
        since.put(Capability.MENU_HISTORY_CLEAR, "0.49.0");
        since.put(Capability.MENU_ACCESS_DIAGNOSTICS, "0.26.5");
        since.put(Capability.SETTINGS_DRAFTS, "0.26.5");
        since.put(Capability.SETTINGS_PROFILES, "0.26.5");
        since.put(Capability.SETUP_WIZARD, "0.52.0");
        since.put(Capability.LANGUAGE_AUDIT, "0.26.5");
        since.put(Capability.FEATURE_DIAGNOSTICS, "0.26.5");
        since.put(Capability.MIGRATION_PREVIEWS, "0.26.5");
        since.put(Capability.TREE_GROWTH_PROFILES, "0.26.5");
        since.put(Capability.ONBOARDING, "0.26.5");
        since.put(Capability.ROUTINE_MESSAGES, "0.26.5");
        since.put(Capability.CONFIG_EDITOR, "0.27.0");
        since.put(Capability.PERMISSION_LIST, "0.27.0");
        since.put(Capability.CONFIG_EDITOR_MERGE_OPTION, "0.27.1");
        since.put(Capability.MYTHIC_ITEM_DROPS, "0.27.2");
        since.put(Capability.LICENSED_MODULES, "0.28.0");
        since.put(Capability.VISUALS, "0.29.0");
        since.put(Capability.HORIZONTAL_VISUALS, "0.30.0");
        since.put(Capability.MANAGERS, "0.64.0");
        since.put(Capability.EVENT_STAGES, "0.64.0");
        since.put(Capability.PLACEHOLDERS, "0.65.0");
        since.put(Capability.FILE_MENUS, "0.65.0");
        since.put(Capability.COMMAND_VISIBILITY, "0.65.0");
        since.put(Capability.PANEL_LIST_LAYOUT, "0.66.0");
        since.put(Capability.PLACEHOLDER_RANKINGS, "0.67.0");
        SINCE = Collections.unmodifiableMap(since);
    }

    private CoreApi() {}

    /** Running version (same as {@link #VERSION}). */
    public static String version() { return VERSION; }

    /** Whether the running Core version is at least {@code minimum} (only the numeric part {@code x.y.z} counts). */
    public static boolean atLeast(String minimum) { return compare(VERSION, minimum) >= 0; }

    /** Whether the capability exists in this Core version. Unknown names return {@code false}. */
    public static boolean has(String capability) {
        if (capability == null) return false;
        return SINCE.containsKey(capability.trim().toLowerCase(Locale.ROOT));
    }

    /** Version since which a capability exists. */
    public static Optional<String> since(String capability) {
        return capability == null ? Optional.empty() : Optional.ofNullable(SINCE.get(capability.trim().toLowerCase(Locale.ROOT)));
    }

    /** All capabilities with the version that introduced them. */
    public static Map<String, String> capabilities() { return SINCE; }

    /**
     * The legacy API while Core is enabled. Empty before {@code onEnable} and after {@code onDisable}
     * (unlike {@code main.getApi()}, which then returns the stale instance).
     */
    public static Optional<API> api() {
        Plugin core = plugin();
        return core == null || !core.isEnabled() ? Optional.empty() : Optional.ofNullable(main.getApi());
    }

    /**
     * The cross-server network (never null; a no-op without proxy). See {@link Vystorm.vystorm_core.network.Network}.
     * @since 0.53.0
     */
    public static Vystorm.vystorm_core.network.Network network() {
        return Vystorm.vystorm_core.network.paper.PaperNetwork.network();
    }

    /** The Core plugin, if loaded. */
    public static Plugin plugin() {
        try {
            return Bukkit.getServer() == null ? null : main.getPlugin();
        } catch (RuntimeException notLoaded) {
            return null;
        }
    }

    /** Compares the numeric part of two versions ({@code 0.10.3-Vystorm-Core-26.2} → 0.10.3). */
    public static int compare(String a, String b) {
        int[] left = numeric(a), right = numeric(b);
        for (int i = 0; i < 3; i++) if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
        return 0;
    }

    static int[] numeric(String version) {
        int[] parts = new int[3];
        if (version == null) return parts;
        String core = version.trim().split("[^0-9.]", 2)[0];
        String[] split = core.split("\\.");
        for (int i = 0; i < 3 && i < split.length; i++) {
            try { parts[i] = split[i].isEmpty() ? 0 : Integer.parseInt(split[i]); } catch (NumberFormatException ignored) { parts[i] = 0; }
        }
        return parts;
    }

    private static String loadVersion() {
        try (InputStream in = CoreApi.class.getResourceAsStream("/vystorm-core.properties")) {
            if (in == null) return "0.0.0";
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("version", "0.0.0");
            return value.contains("${") ? "0.0.0" : value;
        } catch (IOException | RuntimeException failure) {
            return "0.0.0";
        }
    }
}
