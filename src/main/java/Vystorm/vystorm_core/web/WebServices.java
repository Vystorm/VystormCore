package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.lifecycle.ProviderFailures;
import Vystorm.vystorm_core.web.internal.ModuleRegistry;
import Vystorm.vystorm_core.web.internal.WebDispatcher;
import Vystorm.vystorm_core.web.internal.WebPlatform;
import com.google.gson.JsonElement;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Web platform for all Vystorm plugins: plugins register {@link WebModule}s with static pages and API routes;
 * Core serves them (embedded HTTP server or external gateway) and takes care of login, sessions, CSRF, permissions,
 * rate limits and audit. Browser and client are never the authority; every change goes through a {@link WebHandler} on
 * the server.
 *
 * <pre>{@code
 * boolean web;
 * try { web = CoreApi.has(CoreApi.Capability.WEB); } catch (LinkageError tooOld) { web = false; }
 * if (web) {
 *     WebServices.register(this, WebModule.builder("skills", "Skill tree")
 *             .assets("web/skills/")
 *             .route(WebRoute.get("/tree", req -> WebResponse.json(tree(req.playerId()))).build())
 *             .route(WebRoute.post("/allocate", this::allocate).requiresOnline().rateLimit(30).build())
 *             .build());
 * }
 * // in the command (server thread):
 * WebServices.sendLink(player, "skills", Component.text("Open the skill tree"));
 * }</pre>
 *
 * <p>Registering always works, even if the platform is switched off in the configuration ({@link #available()} = false);
 * the modules are then simply not served. When the plugin is disabled, {@code CoreLifecycle} removes its
 * modules. Thread-safe; {@link #loginLink} and {@link #sendLink} on the server thread only.
 *
 * <p>Preferred access: {@code api.web()}.
 *
 * @since 0.17.0
 */
public final class WebServices {
    /** Base permission for the web interface (default: everyone). */
    public static final String USE_PERMISSION = WebDispatcher.USE_PERMISSION;

    private WebServices() {}

    /**
     * Registers a module and loads its files from the jar of {@code owner}. Registering the same ID again
     * by the same owner replaces the module.
     *
     * @throws IllegalStateException if another active plugin holds the ID (or {@code core})
     * @throws IllegalArgumentException if the files cannot be loaded
     */
    public static void register(Plugin owner, WebModule module) { WebPlatform.modules().register(owner, module); }

    /** Removes all modules of the plugin; their pages and routes are gone at once. */
    public static void unregister(Plugin owner) { if (owner != null) WebPlatform.modules().unregister(owner); }

    /** Removes a module if it belongs to {@code owner}. */
    public static boolean unregister(Plugin owner, String moduleId) { return owner != null && WebPlatform.modules().unregister(owner, moduleId); }

    /** Whether the platform is running (switched on in the configuration and started). */
    public static boolean available() { return WebPlatform.running(); }

    /** Whether an active module with this ID is registered. */
    public static boolean registered(String moduleId) { return WebPlatform.modules().get(moduleId).isPresent(); }

    /** IDs of all active modules. */
    public static Set<String> modules() { return WebPlatform.modules().ids(); }

    /**
     * Creates a one-time login link ({@code <public-url>/login#<token>}, valid for about 60 s) for the player; after the
     * login the browser lands on the module (or on the overview for {@code null}). Server thread only.
     *
     * @return empty if the platform is off, the module is missing or the player lacks the permissions
     */
    public static Optional<String> loginLink(Player player, String moduleId) { return issueLogin(player, moduleId, "player").map(WebLogin::url); }

    /**
     * Issues a one-time login without writing anything into the chat: for own interfaces or other channels
     * (e.g. a client mod that gets base URL and token by plugin message). Checks {@code vystorm.web.use} and the
     * module permission. Server thread only. The token is secret: give it to exactly this player only, never log it.
     *
     * @param issuer short name of the channel for the audit log, e.g. {@code "client-mod"}
     * @return empty if the platform is off, the module is missing or the player lacks the permissions
     */
    public static Optional<WebLogin> issueLogin(Player player, String moduleId, String issuer) {
        if (player == null) return Optional.empty();
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("WebServices.issueLogin: server thread only");
        if (!WebPlatform.running() || !player.hasPermission(USE_PERMISSION)) return Optional.empty();
        if (moduleId != null) {
            var module = WebPlatform.modules().get(moduleId);
            if (module.isEmpty()) return Optional.empty();
            if (module.get().module().permission().map(node -> !player.hasPermission(node)).orElse(false)) return Optional.empty();
        }
        try {
            String source = issuer == null || issuer.isBlank() ? "api" : issuer;
            return WebPlatform.issueLogin(player.getUniqueId(), player.getName(), moduleId, source);
        } catch (RuntimeException failure) {
            ProviderFailures.report("WebServices", "core", "loginLink", failure);
            return Optional.empty();
        }
    }

    /**
     * Sends the player a clickable login link in the chat. Server thread only.
     *
     * @param label text of the link, e.g. {@code Component.text("Open the skill tree")}; null = Core's default label
     * @return false (with a hint to the player) if no link could be created
     */
    public static boolean sendLink(Player player, String moduleId, Component label) {
        Optional<WebLogin> login = issueLogin(player, moduleId, "chat");
        Optional<String> link = login.map(WebLogin::url);
        if (link.isEmpty()) {
            if (player != null) Vystorm.vystorm_core.lang.Localization.get().core().send(player,
                    !WebPlatform.running() ? "web.not-active" : "web.not-for-you");
            return false;
        }
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        Component text = label == null ? lang.component(player, "web.open-label") : Vystorm.vystorm_core.lang.Localization.get().render(player, label);
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, Component.text("[", NamedTextColor.GREEN).append(text.color(text.color() == null ? NamedTextColor.GREEN : text.color()))
                .append(Component.text("]", NamedTextColor.GREEN))
                .hoverEvent(HoverEvent.showText(lang.component(player, "web.link-hover")
                        .append(Component.newline()).append(lang.component(player, "web.link-warning"))))
                .clickEvent(ClickEvent.openUrl(link.get()))
                .append(lang.component(player, "web.link-suffix", "seconds",
                        Math.max(1, java.time.Duration.between(java.time.Instant.now(), login.get().expiresAt()).toSeconds()))));
        return true;
    }

    /**
     * Sends a small, typed live event to all open browser tabs and overlays of the player (server-sent
     * events through {@code /core/live}), e.g. {@code push(uuid, "skills", "character", {level, xp, points})}. The same
     * module+type is briefly coalesced per player (only the last value counts); clients keep the last
     * value per type. It is delivered only with a valid session and module permission. Without an open connection nothing
     * happens (cheap, so feel free to call it on every change). Thread-safe, from any thread.
     *
     * @param type {@code [a-z0-9][a-z0-9._:-]{0,63}}
     * @param data JSON, serialized at most {@code web.live.max-event-bytes} (default 16 KiB)
     * @return whether the player currently has a live connection
     * @throws IllegalArgumentException for an invalid type or an event that is too large
     * @since 0.18.0
     */
    public static boolean push(UUID player, String moduleId, String type, JsonElement data) {
        return WebPlatform.push(player, moduleId, type, data);
    }

    /** Like {@link #push(UUID, String, String, JsonElement)}. @since 0.18.0 */
    public static boolean push(Player player, String moduleId, String type, JsonElement data) {
        return player != null && push(player.getUniqueId(), moduleId, type, data);
    }

    /**
     * Publishes static game data of an own module as JSON, e.g. the skill tree layout. Served under
     * {@code /data/<module>/<name>.<version>.json} (version = content hash, cacheable without limit) and
     * {@code /data/<module>/<name>.json} (current, by ETag). {@code /core/session} lists the versioned URLs; on
     * a new version open live channels get {@code {"kind":"data",…}}. If the module has a permission, fetching
     * also needs a session with this permission, otherwise the data is public. Thread-safe.
     *
     * @param name {@code [a-z0-9][a-z0-9-]{0,47}}
     * @return the versioned URL (path), e.g. {@code /data/skills/tree.Ab12Cd34Ef56.json}
     * @throws IllegalStateException if the module is not registered by {@code owner}
     * @throws IllegalArgumentException for an invalid name or more than 8 MiB
     * @since 0.18.0
     */
    public static String publishData(Plugin owner, String moduleId, String name, JsonElement data) {
        return WebPlatform.modules().publishData(owner, moduleId, name, data).url();
    }

    /** Versioned URL of published game data. @since 0.18.0 */
    public static Optional<String> dataUrl(String moduleId, String name) {
        return WebPlatform.modules().data(moduleId, name).map(d -> d.url());
    }

    /** Public base URL of the web interface (without a trailing slash) if the platform is running. */
    public static Optional<String> publicUrl() { return WebPlatform.publicUrl(); }

    /** Ends all web sessions of a player (e.g. after password/account problems); returns the count. */
    public static int revokeSessions(UUID player) { return player == null ? 0 : WebPlatform.revoke(player, null, "api"); }

    /**
     * Opens a web page the best way for this player: in the in-game overlay if the player has the Vystorm client mod and
     * trusts this server's web origin ({@link ClientServices#show}), otherwise as a clickable one-time login link in the
     * chat ({@link #sendLink}); if no link is possible either (platform off, no permission, module missing) the
     * {@code fallback} runs, or – without fallback – the player gets Core's hint ("web interface not active"/"not for
     * you"). Server thread only.
     *
     * <pre>{@code
     * // /trees web
     * WebServices.openOrLink(player, "customtrees", "", null);
     * // a button in an own menu that shows one tree species in the overlay (link: module page)
     * WebServices.openOrLink(player, "customtrees", "species/oak", () -> openSpeciesMenu(player, "oak"));
     * }</pre>
     *
     * @param moduleId registered module id, or "" for the overview
     * @param subPath  page inside the module for the overlay: "" (module start page), {@code "tab/x"} (→
     *                 {@code /app/<id>/tab/x}) or {@code "#node/12"} (fragment); ignored for the chat link, which opens the
     *                 module page. Letters, digits and {@code /_=&.,:~+-} plus at most one {@code #fragment}; no
     *                 {@code ..} or {@code //}; at most about 150 characters.
     * @param fallback runs when neither overlay nor link is possible; may be null
     * @throws IllegalArgumentException for an invalid module id or sub-path
     * @since 0.24.0
     */
    public static WebOpenResult openOrLink(Player player, String moduleId, String subPath, Runnable fallback) {
        return openOrLink(player, moduleId, subPath, null, fallback);
    }

    /**
     * Like {@link #openOrLink(Player, String, String, Runnable)}, but the chat link shows {@code label} (e.g. "Open the
     * forge in the browser") instead of the module title. {@code null} keeps the module title. Server thread only.
     *
     * @since 0.26.4
     */
    public static WebOpenResult openOrLink(Player player, String moduleId, String subPath, Component label, Runnable fallback) {
        if (player == null) return WebOpenResult.NONE;
        String page = overlayPage(moduleId, subPath);
        if (ClientServices.show(player, moduleId == null ? "" : moduleId, page)) return WebOpenResult.OVERLAY;
        Component text = label != null ? label : linkLabel(player, moduleId);
        if (fallback == null) return sendLink(player, emptyToNull(moduleId), text) ? WebOpenResult.LINK : WebOpenResult.NONE;
        if (issuable(player, emptyToNull(moduleId)) && sendLink(player, emptyToNull(moduleId), text)) return WebOpenResult.LINK;
        runFallback(fallback, moduleId);
        return WebOpenResult.FALLBACK;
    }

    /**
     * Opens the page in the in-game overlay if possible (client mod present, origin trusted), otherwise runs
     * {@code fallback} – no chat link. The usual pattern for a command or item that normally opens a chest/dialog menu.
     * Server thread only.
     *
     * <pre>{@code
     * WebServices.openOrElse(player, "xpvault", "", () -> openVaultMenu(player));
     * }</pre>
     *
     * @see #openOrLink(Player, String, String, Runnable)
     * @since 0.24.0
     */
    public static WebOpenResult openOrElse(Player player, String moduleId, String subPath, Runnable fallback) {
        if (player == null) return WebOpenResult.NONE;
        String page = overlayPage(moduleId, subPath);
        if (ClientServices.show(player, moduleId == null ? "" : moduleId, page)) return WebOpenResult.OVERLAY;
        if (fallback == null) return WebOpenResult.NONE;
        runFallback(fallback, moduleId);
        return WebOpenResult.FALLBACK;
    }

    /**
     * The overlay page for a module and sub-path: "" for the module start page, else {@code /app/<id>/<sub>} or
     * {@code /app/<id>#frag}.
     *
     * @throws IllegalArgumentException for an invalid module id or sub-path
     * @since 0.24.0
     */
    public static String overlayPage(String moduleId, String subPath) {
        String id = moduleId == null ? "" : moduleId;
        if (!id.isEmpty() && !WebModule.ID.matcher(id).matches()) throw new IllegalArgumentException("invalid module id: " + id);
        String sub = subPath == null ? "" : subPath.trim();
        if (sub.isEmpty()) return "";
        if (id.isEmpty()) throw new IllegalArgumentException("a sub-path needs a module id");
        while (sub.startsWith("/")) sub = sub.substring(1);
        String page = "/app/" + id + (sub.startsWith("#") ? sub : "/" + sub);
        if (!Vystorm.vystorm_core.web.internal.clientproto.ModuleIds.validPage(page))
            throw new IllegalArgumentException("invalid sub-path: " + subPath);
        return page;
    }

    /**
     * Sends a small live event to every player with an open web page/overlay (only those allowed to open the module get
     * it), e.g. server-wide state that changed. Any thread.
     *
     * @return number of players with an open live connection
     * @since 0.24.0
     */
    public static int broadcast(String moduleId, String type, JsonElement data) { return WebPlatform.pushAll(moduleId, type, data); }

    private static boolean issuable(Player player, String moduleId) {
        if (!WebPlatform.running() || !player.hasPermission(USE_PERMISSION)) return false;
        if (moduleId == null) return true;
        var module = WebPlatform.modules().get(moduleId);
        return module.isPresent() && module.get().module().permission().map(player::hasPermission).orElse(true);
    }

    private static Component linkLabel(Player player, String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) return null;
        return WebPlatform.modules().get(moduleId).map(r -> Vystorm.vystorm_core.lang.Localization.get().component(player, r.module().title())).orElse(null);
    }

    private static String emptyToNull(String id) { return id == null || id.isEmpty() ? null : id; }

    private static void runFallback(Runnable fallback, String moduleId) {
        try { fallback.run(); }
        catch (RuntimeException failure) { ProviderFailures.report("WebServices", String.valueOf(moduleId), "openFallback", failure); }
    }

    /** Reserved module ID of the built-in profile module. */
    public static String coreModuleId() { return ModuleRegistry.CORE_MODULE; }
}
