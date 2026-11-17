package Vystorm.vystorm_core.lang;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Core's own wiring of the localization service: config, Core bundle, translator, personal language choice
 * (player data key {@code vystorm_core:pref.language}, shared by {@code /language} and {@code /vsettings} → Language),
 * the "Language" player settings section and {@link PlayerLanguageChangeEvent}.
 * <p>Preferred access: {@code api.lang().coreLanguage()}.
 */
public final class CoreLanguage implements Listener {
    static final String PREF = "language";
    /** Default of the legacy per-player {@code language} setting (playerData/defaultSettings.yml); not a choice. */
    static final String LEGACY_DEFAULT = "en_us";
    private static volatile Plugin plugin;
    private static volatile API settingsApi;
    private static volatile boolean refreshQueued;
    private final Plugin core;

    private CoreLanguage(Plugin core) { this.core = core; }

    /** Called first in Core's {@code onEnable}. */
    public static void enable(Plugin core) {
        plugin = core;
        Localization service = Localization.get();
        service.configure(core);
        Lang lang = service.register(core);
        service.installTranslator();
        CoreLanguage listener = new CoreLanguage(core);
        core.getServer().getPluginManager().registerEvents(listener, core);
        for (Player player : core.getServer().getOnlinePlayers()) listener.load(player);
        try { Vystorm.vystorm_core.lang.internal.LanguageCommand.register(core); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Localization] /language not registered: " + failure); }
        // Plugins enabled after Core add their lang files: refresh the language list once everything is up.
        queueRefresh();
        core.getLogger().info("Language: " + service.defaultLanguage() + ", server " + service.serverLanguage()
                + (service.forced() ? " (forced)" : "") + " (" + String.join(", ", lang.languages())
                + "; files in plugins/" + core.getDataFolder().getName() + "/lang)");
    }

    /** Registers the personal "Language" settings section (after the settings registry exists). */
    public static void registerSettings(API api, Plugin core) {
        settingsApi = api;
        api.settings().register(core, section(core));
    }

    private static SettingsSection section(Plugin core) {
        Lang lang = Localization.get().core();
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put(Localization.AUTO, lang.ref("settings.language.auto"));
        for (String code : Localization.get().selectableLanguages()) options.put(code, nativeName(code) + " (" + code + ")");
        return SettingsSection.player("language", lang.ref("settings.language.title"),
                lang.ref("settings.language.description"),
                List.of(Setting.choice("language", lang.ref("settings.language.label"), lang.ref("settings.language.hint"), options,
                        p -> Localization.get().personalLanguage(p.getUniqueId()).orElse(Localization.AUTO),
                        (p, value) -> {
                            if (Localization.get().forced()) return lang.plain(p, "language-select.forced", "language", nativeName(Localization.get().serverLanguage()));
                            setChoice(p, value, PlayerLanguageChangeEvent.Cause.SETTINGS);
                            return null;
                        })));
    }

    /**
     * Re-builds the option list of {@code /vsettings} → Language from {@link Localization#selectableLanguages()}
     * (after plugins registered bundles or a reload). Server thread.
     */
    public static void refreshSettings() {
        API api = settingsApi;
        Plugin core = plugin;
        if (api == null || core == null) return;
        try {
            if (api.settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry registry) registry.replace(core, section(core));
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] Language settings not refreshed: " + failure);
        }
    }

    private static void queueRefresh() {
        Plugin core = plugin;
        if (core == null || refreshQueued || !core.isEnabled()) return;
        refreshQueued = true;
        try {
            core.getServer().getScheduler().runTask(core, () -> { refreshQueued = false; refreshSettings(); });
        } catch (RuntimeException failure) {
            refreshQueued = false;
        }
    }

    public static void disable() {
        Localization.get().uninstallTranslator();
        Localization.get().clearPlayers();
        plugin = null;
        settingsApi = null;
    }

    /** The language's own name ({@code language.name} of any bundle, else Java's name of the locale in itself). */
    public static String nativeName(String code) {
        String normalized = Localization.normalize(code);
        Localization service = Localization.get();
        var own = service.core().translation(normalized, "language.name");
        if (own.isPresent()) return String.valueOf(own.get());
        for (Lang bundle : service.bundles()) {
            var named = bundle.translation(normalized, "language.name");
            if (named.isPresent()) return String.valueOf(named.get());
        }
        try {
            Locale locale = Locale.forLanguageTag(normalized.replace('_', '-'));
            String name = locale.getDisplayName(locale);
            if (!name.isBlank() && !name.equalsIgnoreCase(normalized)) return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        } catch (RuntimeException ignored) {
            // unusual code
        }
        return normalized;
    }

    /** The player's stored choice: a code or {@code auto}. */
    public static String choice(Player player) {
        return Localization.get().personalLanguage(player.getUniqueId()).orElse(Localization.AUTO);
    }

    /**
     * Stores the player's language choice ({@code auto} or a code) exactly like {@code /language} and
     * {@code /vsettings} do (player data {@code vystorm_core:pref.language}), switches Core's texts and fires
     * {@link PlayerLanguageChangeEvent}. Works while {@code language.force} is on (the choice then applies once force is
     * switched off). Server thread only. Returns {@code false} if Core is not running.
     *
     * @since 0.45.0
     */
    public static boolean setChoice(Player player, String codeOrAuto, PlayerLanguageChangeEvent.Cause cause) {
        Plugin core = plugin;
        if (core == null || player == null) return false;
        Localization service = Localization.get();
        String before = service.language(player);
        String previousChoice = choice(player);
        String code = Localization.normalize(codeOrAuto);
        try {
            if (code.isEmpty() || code.equals(Localization.AUTO)) {
                PlayerPrefs.setText(core, player, PREF, Localization.AUTO);
                service.setPersonalLanguage(player.getUniqueId(), null);
            } else {
                PlayerPrefs.setText(core, player, PREF, code);
                service.setPersonalLanguage(player.getUniqueId(), code);
            }
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] Language choice of " + player.getName() + " not stored: " + failure);
            return false;
        }
        try { Vystorm.vystorm_core._uiMethods.tutorial.CoreOnboarding.markLanguage(core, player); }
        catch (RuntimeException | LinkageError failure) { DebugMessages.low("[Localization] Onboarding not updated: " + failure); }
        String after = service.language(player);
        if (!before.equals(after) || !previousChoice.equals(choice(player))) fire(player, before, after, cause);
        return true;
    }

    static void choose(Plugin core, Player player, String value) {
        setChoice(player, value, PlayerLanguageChangeEvent.Cause.SETTINGS);
    }

    private static void fire(Player player, String before, String after, PlayerLanguageChangeEvent.Cause cause) {
        try {
            Bukkit.getPluginManager().callEvent(new PlayerLanguageChangeEvent(player, before, after, choice(player), cause));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Localization] PlayerLanguageChangeEvent failed: " + failure);
        }
    }

    /**
     * {@code /vadmin language reload}: re-reads Core's {@code language.*} settings and every bundle, refreshes the
     * settings options and fires {@link PlayerLanguageChangeEvent} ({@code RELOAD}) for players whose language changed.
     * Server thread. Returns the number of bundles.
     */
    public static int reload() {
        Localization service = Localization.get();
        Map<UUID, String> before = new HashMap<>();
        List<Player> online = List.of();
        try { online = List.copyOf(Bukkit.getOnlinePlayers()); } catch (RuntimeException ignored) { /* tests */ }
        for (Player p : online) before.put(p.getUniqueId(), service.language(p));
        service.reloadAll();
        refreshSettings();
        for (Player p : online) {
            String after = service.language(p);
            if (!after.equals(before.get(p.getUniqueId()))) fire(p, before.get(p.getUniqueId()), after, PlayerLanguageChangeEvent.Cause.RELOAD);
        }
        return service.bundles().size();
    }

    void load(Player player) {
        try {
            String chosen = PlayerPrefs.text(core, player, PREF, "");
            if (chosen.isBlank()) chosen = legacySetting(player);
            Localization.get().setPersonalLanguage(player.getUniqueId(), chosen);
            Localization.get().rememberClientLocale(player.getUniqueId(), Localization.clientLocale(player));
        } catch (RuntimeException failure) {
            DebugMessages.high("[Localization] Language preference of " + player.getName() + " unreadable: " + failure);
        }
    }

    /** The legacy Core player setting {@code language}, unless it is the shipped default {@code en_US}. */
    @SuppressWarnings("deprecation")
    private static String legacySetting(Player player) {
        try {
            API api = Vystorm.vystorm_core.main.getApi();
            String value = api == null ? null : api.player().getSetting(player.getUniqueId(), "language");
            String code = Localization.normalize(value);
            return code.equals(LEGACY_DEFAULT) ? "" : code.toLowerCase(Locale.ROOT);
        } catch (RuntimeException failure) {
            return "";
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { load(event.getPlayer()); }

    /**
     * The choice and the client locale stay cached after quit: web sessions of offline players use them. The player
     * object reports the new locale only after this event, so the change is compared one tick later.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLocale(org.bukkit.event.player.PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        // From the remembered (old) locale: the player object may already report the new one.
        String before = Localization.get().core().languageFor(player.getUniqueId(), List.of());
        Localization.get().rememberClientLocale(player.getUniqueId(), event.getLocale());
        try {
            core.getServer().getScheduler().runTask(core, () -> {
                if (!player.isOnline()) return;
                Localization.get().rememberClientLocale(player.getUniqueId(), Localization.clientLocale(player));
                String after = Localization.get().language(player);
                if (!after.equals(before)) fire(player, before, after, PlayerLanguageChangeEvent.Cause.CLIENT_LOCALE);
            });
        } catch (RuntimeException failure) {
            DebugMessages.low("[Localization] Locale change not compared: " + failure);
        }
    }

    /** A plugin enabled later (e.g. after a reload) may bring new languages. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) { queueRefresh(); }
}
