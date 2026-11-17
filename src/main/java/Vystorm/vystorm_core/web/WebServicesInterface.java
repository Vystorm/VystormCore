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
 * Manager view of {@link WebServices}, reached through {@code api.web()}.
 * <p>
 * Every method has a default body that delegates to the static method of the same name, so behaviour, thread
 * rules and failure handling are the ones documented on {@link WebServices}. An own implementation of the owning
 * manager may return a replacement that overrides single methods (tests, special servers).
 *
 * @since 0.64.0
 */
public interface WebServicesInterface {
    /** The delegating default. Plugins use {@code api.web()}. */
    WebServicesInterface DEFAULT = new WebServicesInterface() {};

    /**
     * Registers a module and loads its files from the jar of {@code owner}. Registering the same ID again
     * by the same owner replaces the module.
     *
     * @throws IllegalStateException if another active plugin holds the ID (or {@code core})
     * @throws IllegalArgumentException if the files cannot be loaded
     */
    default void register(Plugin owner, WebModule module) { WebServices.register(owner, module); }

    /** Removes all modules of the plugin; their pages and routes are gone at once. */
    default void unregister(Plugin owner) { WebServices.unregister(owner); }

    /** Removes a module if it belongs to {@code owner}. */
    default boolean unregister(Plugin owner, String moduleId) { return WebServices.unregister(owner, moduleId); }

    /** Whether the platform is running (switched on in the configuration and started). */
    default boolean available() { return WebServices.available(); }

    /** Whether an active module with this ID is registered. */
    default boolean registered(String moduleId) { return WebServices.registered(moduleId); }

    /** IDs of all active modules. */
    default Set<String> modules() { return WebServices.modules(); }

    /**
     * Creates a one-time login link ({@code <public-url>/login#<token>}, valid for about 60 s) for the player; after the
     * login the browser lands on the module (or on the overview for {@code null}). Server thread only.
     *
     * @return empty if the platform is off, the module is missing or the player lacks the permissions
     */
    default Optional<String> loginLink(Player player, String moduleId) { return WebServices.loginLink(player, moduleId); }

    /**
     * Issues a one-time login without writing anything into the chat: for own interfaces or other channels
     * (e.g. a client mod that gets base URL and token by plugin message). Checks {@code vystorm.web.use} and the
     * module permission. Server thread only. The token is secret: give it to exactly this player only, never log it.
     *
     * @param issuer short name of the channel for the audit log, e.g. {@code "client-mod"}
     * @return empty if the platform is off, the module is missing or the player lacks the permissions
     */
    default Optional<WebLogin> issueLogin(Player player, String moduleId, String issuer) { return WebServices.issueLogin(player, moduleId, issuer); }

    /**
     * Sends the player a clickable login link in the chat. Server thread only.
     *
     * @param label text of the link, e.g. {@code Component.text("Open the skill tree")}; null = Core's default label
     * @return false (with a hint to the player) if no link could be created
     */
    default boolean sendLink(Player player, String moduleId, Component label) { return WebServices.sendLink(player, moduleId, label); }

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
    default boolean push(UUID player, String moduleId, String type, JsonElement data) { return WebServices.push(player, moduleId, type, data); }

    /** Like {@link #push(UUID, String, String, JsonElement)}. @since 0.18.0 */
    default boolean push(Player player, String moduleId, String type, JsonElement data) { return WebServices.push(player, moduleId, type, data); }

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
    default String publishData(Plugin owner, String moduleId, String name, JsonElement data) { return WebServices.publishData(owner, moduleId, name, data); }

    /** Versioned URL of published game data. @since 0.18.0 */
    default Optional<String> dataUrl(String moduleId, String name) { return WebServices.dataUrl(moduleId, name); }

    /** Public base URL of the web interface (without a trailing slash) if the platform is running. */
    default Optional<String> publicUrl() { return WebServices.publicUrl(); }

    /** Ends all web sessions of a player (e.g. after password/account problems); returns the count. */
    default int revokeSessions(UUID player) { return WebServices.revokeSessions(player); }

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
    default WebOpenResult openOrLink(Player player, String moduleId, String subPath, Runnable fallback) {
        return WebServices.openOrLink(player, moduleId, subPath, fallback);
    }

    /**
     * Like {@link #openOrLink(Player, String, String, Runnable)}, but the chat link shows {@code label} (e.g. "Open the
     * forge in the browser") instead of the module title. {@code null} keeps the module title. Server thread only.
     *
     * @since 0.26.4
     */
    default WebOpenResult openOrLink(Player player, String moduleId, String subPath, Component label, Runnable fallback) {
        return WebServices.openOrLink(player, moduleId, subPath, label, fallback);
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
    default WebOpenResult openOrElse(Player player, String moduleId, String subPath, Runnable fallback) {
        return WebServices.openOrElse(player, moduleId, subPath, fallback);
    }

    /**
     * The overlay page for a module and sub-path: "" for the module start page, else {@code /app/<id>/<sub>} or
     * {@code /app/<id>#frag}.
     *
     * @throws IllegalArgumentException for an invalid module id or sub-path
     * @since 0.24.0
     */
    default String overlayPage(String moduleId, String subPath) { return WebServices.overlayPage(moduleId, subPath); }

    /**
     * Sends a small live event to every player with an open web page/overlay (only those allowed to open the module get
     * it), e.g. server-wide state that changed. Any thread.
     *
     * @return number of players with an open live connection
     * @since 0.24.0
     */
    default int broadcast(String moduleId, String type, JsonElement data) { return WebServices.broadcast(moduleId, type, data); }

    /** Reserved module ID of the built-in profile module. */
    default String coreModuleId() { return WebServices.coreModuleId(); }
}
