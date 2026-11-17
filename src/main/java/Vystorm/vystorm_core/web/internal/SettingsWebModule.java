package Vystorm.vystorm_core.web.internal;

import Vystorm.vystorm_core._uiMethods.settings.Setting;
import Vystorm.vystorm_core._uiMethods.settings.SettingScope;
import Vystorm.vystorm_core._uiMethods.settings.SettingsInterface;
import Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebRequest;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;

/**
 * Built-in web module {@code settings}: the personal settings every plugin registers through Core's settings API (the
 * ones in {@code /vsettings}, including Language) and, for admins, the server settings, rendered from the registry.
 * Reading and saving go through {@link SettingsRegistry#view} and {@link SettingsRegistry#save} on the server thread, so
 * permissions, validation, setters and {@code afterSave} are exactly those of the in-game dialog. Every save (web or
 * dialog) and {@link SettingsInterface#changed} pushes the live event {@code settings/changed}; the page reloads then.
 * Getters and setters need the online player, so values are only shown and changed while the player is in game.
 * Core-internal.
 *
 * <ul>
 *   <li>{@code GET /api/settings/sections} → {@code {online, supported, sections:[{key, scope, title, description,
 *       settings:[{id, kind, label, description, value, readable, min, max, step, options:[{id,label}], maxLength}]}]}}</li>
 *   <li>{@code POST /api/settings/save} {@code {section, values:{id: value}}} → {@code {changed, applyFailed,
 *       problems:[{id, code, label, message}], section}}; 404 if the section is gone or not allowed</li>
 * </ul>
 *
 * @since 0.24.0
 */
public final class SettingsWebModule {
    /** Module id of the page ({@code /app/settings}). */
    public static final String ID = "settings";
    /** Live event type after a change. */
    public static final String CHANGED = "changed";
    static final int MAX_VALUES = 64;
    private static final Pattern FORMAT = Pattern.compile("(?i)[§&](?:x(?:[§&][0-9a-f]){6}|[0-9a-fk-or])");
    private static volatile Runnable unsubscribe;

    private SettingsWebModule() {}

    /** Production wiring: Core's current settings registry, Bukkit players, live pushes through {@link WebPlatform}. */
    public static WebModule create() {
        return create(() -> Vystorm.vystorm_core.main.getApi() == null ? null : Vystorm.vystorm_core.main.getApi().settings(),
                org.bukkit.Bukkit::getPlayer,
                (player, data) -> { if (player == null) WebPlatform.pushAll(ID, CHANGED, data); else WebPlatform.push(player, ID, CHANGED, data); });
    }

    /**
     * @param settings the registry (may be replaced at runtime through {@code API.overrideSettings}; only Core's own
     *                 {@link SettingsRegistry} is supported)
     * @param players  online player by UUID (server thread)
     * @param push     live push: player (null = everyone connected) and event data
     */
    public static WebModule create(Supplier<SettingsInterface> settings, Function<UUID, Player> players, BiConsumer<UUID, JsonElement> push) {
        Runnable previous = unsubscribe;
        if (previous != null) previous.run();
        unsubscribe = null;
        SettingsInterface current = safe(settings);
        if (current instanceof SettingsRegistry registry) {
            unsubscribe = registry.onChange((player, key) -> {
                JsonObject data = new JsonObject();
                if (key != null) data.addProperty("section", key);
                data.addProperty("scope", player == null ? "server" : "player");
                data.addProperty("at", System.currentTimeMillis());
                try { push.accept(player == null ? null : player.getUniqueId(), data); }
                catch (RuntimeException ignored) { /* the page just misses one refresh */ }
            });
        }
        var lang = Localization.get().core();
        return WebModule.builder(ID, lang.ref("web.module.settings.title"))
                .description(lang.ref("web.module.settings.description"))
                .assets("web/settings/")
                .route(WebRoute.get("/sections", request -> sections(request, settings, players)).rateLimit(90).build())
                .route(WebRoute.post("/save", request -> save(request, settings, players))
                        .requiresOnline().rateLimit(90).maxBody(8 * 1024).build())
                .build();
    }

    private static SettingsInterface safe(Supplier<SettingsInterface> settings) {
        try { return settings.get(); } catch (RuntimeException failure) { return null; }
    }

    private static WebResponse sections(WebRequest request, Supplier<SettingsInterface> settings, Function<UUID, Player> players) {
        JsonObject out = new JsonObject();
        SettingsInterface current = safe(settings);
        out.addProperty("supported", current instanceof SettingsRegistry);
        if (!request.online() || !(current instanceof SettingsRegistry registry)) {
            out.addProperty("online", request.online());
            out.add("sections", new JsonArray());
            return WebResponse.json(out);
        }
        List<SettingsRegistry.SectionView> views = request.sync(() -> {
            Player player = players.apply(request.playerId());
            return player == null || !player.isOnline() ? null : registry.view(player);
        });
        out.addProperty("online", views != null);
        JsonArray list = new JsonArray();
        if (views != null) for (SettingsRegistry.SectionView view : views) list.add(section(view, request.language()));
        out.add("sections", list);
        return WebResponse.json(out);
    }

    private static WebResponse save(WebRequest request, Supplier<SettingsInterface> settings, Function<UUID, Player> players) {
        JsonElement body = request.body();
        if (!body.isJsonObject()) throw WebException.badRequest(WebText.error("bad-json"));
        JsonObject o = body.getAsJsonObject();
        String key = o.get("section") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        if (key == null || key.isEmpty() || key.length() > 128) throw WebException.badRequest(WebText.error("param-missing", "name", "section"));
        if (!(o.get("values") instanceof JsonObject raw) || raw.size() == 0 || raw.size() > MAX_VALUES)
            throw WebException.badRequest(WebText.error("param-missing", "name", "values"));
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : raw.entrySet()) values.put(e.getKey(), value(e.getValue()));
        if (!(safe(settings) instanceof SettingsRegistry registry)) throw new WebException(503, WebText.error("settings-unavailable"));
        record Outcome(SettingsRegistry.SaveResult result, Optional<SettingsRegistry.SectionView> view) {}
        Outcome outcome = request.sync(() -> {
            Player player = players.apply(request.playerId());
            if (player == null || !player.isOnline()) return null;
            SettingsRegistry.SaveResult result = registry.save(player, key, values, "web");
            return new Outcome(result, result.found() ? registry.view(player, key) : Optional.empty());
        });
        if (outcome == null) throw WebException.conflict(WebText.error("must-be-online"));
        if (!outcome.result().found()) throw WebException.notFound(WebText.error("settings-not-found"));
        String language = request.language();
        JsonObject out = new JsonObject();
        out.addProperty("changed", outcome.result().changed());
        out.addProperty("applyFailed", outcome.result().applyFailed());
        JsonArray problems = new JsonArray();
        for (SettingsRegistry.Problem problem : outcome.result().problems()) {
            JsonObject p = new JsonObject();
            p.addProperty("id", problem.settingId());
            p.addProperty("code", problem.kind().name().toLowerCase(java.util.Locale.ROOT));
            p.addProperty("label", text(problem.label(), language));
            if (problem.error() != null) p.addProperty("message", text(problem.error(), language));
            problems.add(p);
        }
        out.add("problems", problems);
        outcome.view().ifPresent(view -> out.add("section", section(view, language)));
        return WebResponse.json(out);
    }

    /** JSON value → Boolean, Double or String; anything else (null, arrays, objects) becomes an invalid marker. */
    static Object value(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return INVALID;
        JsonPrimitive p = element.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }

    /** Never matches a setting kind ({@link SettingsRegistry#coerce} returns null for it). */
    private static final Object INVALID = new Object() { @Override public String toString() { return "invalid"; } };

    static JsonObject section(SettingsRegistry.SectionView view, String language) {
        JsonObject o = new JsonObject();
        o.addProperty("key", view.key());
        o.addProperty("scope", view.section().scope() == SettingScope.SERVER ? "server" : "player");
        o.addProperty("title", text(view.section().title(), language));
        o.addProperty("description", text(view.section().description(), language));
        JsonArray settings = new JsonArray();
        for (SettingsRegistry.SettingView s : view.settings()) settings.add(setting(s, language));
        o.add("settings", settings);
        return o;
    }

    static JsonObject setting(SettingsRegistry.SettingView view, String language) {
        Setting s = view.setting();
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("kind", s.kind().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("label", text(s.label(), language));
        o.addProperty("description", text(s.description(), language));
        o.addProperty("readable", view.readable());
        Object value = view.value();
        switch (s.kind()) {
            case TOGGLE -> o.addProperty("value", Boolean.TRUE.equals(value));
            case NUMBER -> {
                o.addProperty("min", s.min());
                o.addProperty("max", s.max());
                o.addProperty("step", s.step());
                if (value instanceof Number n && Double.isFinite(n.doubleValue())) o.addProperty("value", n.doubleValue());
            }
            case CHOICE -> {
                if (value != null) o.addProperty("value", value.toString());
                JsonArray options = new JsonArray();
                for (Setting.Option option : s.options()) {
                    JsonObject x = new JsonObject();
                    x.addProperty("id", option.id());
                    x.addProperty("label", text(option.label(), language));
                    options.add(x);
                }
                o.add("options", options);
            }
            case TEXT -> {
                o.addProperty("maxLength", s.maxLength());
                o.addProperty("value", value == null ? "" : value.toString());
            }
        }
        return o;
    }

    /** A label/description/error: {@code Lang.ref} resolved in the request language, legacy colour codes removed. */
    static String text(String text, String language) {
        if (text == null || text.isEmpty()) return "";
        String resolved;
        try { resolved = Localization.get().resolveIn(language, text); }
        catch (RuntimeException | LinkageError failure) { resolved = text; }
        return FORMAT.matcher(resolved).replaceAll("");
    }
}
