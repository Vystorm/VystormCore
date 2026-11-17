package Vystorm.vystorm_core.web;

import Vystorm.vystorm_core.web.internal.ClientPlatform;
import Vystorm.vystorm_core.web.internal.ClientUiHub;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Native elements of the optional Vystorm client mod (channel {@code vystorm:ui}): HUD boxes, bars, skill bars with
 * cooldown, world markers, toasts and tooltip lines. One namespace per plugin: {@code ClientUi.of(plugin)} prefixes
 * all IDs with {@code <plugin>:}, cleans up when the plugin is disabled ({@code CoreLifecycle}) and reports rejections
 * by the client ({@link VystormClientUiRejectEvent}, throttled in the log).
 *
 * <p>Everything is an extra: for players without the mod or without the capability nothing happens (return value
 * {@code false}); every display needs a vanilla way (boss bar, action bar, chat). Core checks every element with the
 * same schema as the client, batches PATCHes (at most one per element per {@code web.client.ui.patch-interval-ticks},
 * unchanged fields are not sent) and limits the rate. A good place for the first {@link #put} is
 * {@link VystormClientReadyEvent}.
 *
 * <pre>{@code
 * ClientUi ui = ClientUi.of(this);
 * if (ui.available(player, ClientServices.CAP_HUD)) bossBar.removeViewer(player);
 * if (!ui.patch(player, ClientElement.bar("xp").value(xp).label(label)))   // unknown/expired → create again
 *     ui.put(player, xpBar(player));
 * }</pre>
 *
 * Server thread only.
 *
 * <p>Preferred access: {@code api.web().clientUi()}.
 *
 * @since 0.20.0
 */
public final class ClientUi {
    private static final Pattern LOCAL_ID = Pattern.compile("[a-z0-9][a-z0-9_.:/-]{0,63}");
    private static final Map<String, ClientUi> BY_NAMESPACE = new ConcurrentHashMap<>();

    private final Plugin owner;
    private final String namespace;
    private final String prefix;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private ClientUi(Plugin owner, String namespace) {
        this.owner = owner;
        this.namespace = namespace;
        this.prefix = namespace + ":";
    }

    /** Access for a plugin (cached). Namespace = plugin name in lower case, characters other than {@code [a-z0-9_.-]} → {@code _}. */
    public static ClientUi of(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        String ns = namespace(owner.getName());
        return BY_NAMESPACE.compute(ns, (key, old) -> old != null && old.owner == owner ? old : new ClientUi(owner, ns));
    }

    /** Namespace from a plugin name (e.g. {@code VystormSkills} → {@code vystormskills}). */
    public static String namespace(String pluginName) {
        String n = pluginName == null ? "" : pluginName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (n.isEmpty() || !Character.isLetterOrDigit(n.charAt(0))) n = "p" + n;
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    /** Plugin of a full element ID ({@code ns:name}), if it has registered. */
    public static Optional<Plugin> ownerOf(String elementId) {
        if (elementId == null) return Optional.empty();
        int colon = elementId.indexOf(':');
        if (colon <= 0) return Optional.empty();
        ClientUi ui = BY_NAMESPACE.get(elementId.substring(0, colon));
        return ui == null ? Optional.empty() : Optional.of(ui.owner);
    }

    /** Local ID from a full ID (without the namespace). */
    public static String localId(String elementId) {
        int colon = elementId == null ? -1 : elementId.indexOf(':');
        return colon < 0 ? elementId : elementId.substring(colon + 1);
    }

    public Plugin owner() { return owner; }

    public String namespace() { return namespace; }

    /** Full ID as the client sees it ({@code xp} → {@code vystormskills:xp}). */
    public String fullId(String localId) {
        return localId != null && localId.startsWith(prefix) ? localId : prefix + localId;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Whether the player accepts native elements with this capability (mod with {@code capability}, {@code web.client.ui}
     * on). {@code capability} from {@link ClientServices} ({@code CAP_HUD}, {@code CAP_TOASTS}, {@code CAP_MARKERS},
     * {@code CAP_TOOLTIPS}).
     */
    public boolean available(Player player, int capability) {
        return player != null && hub().map(h -> h.supports(player.getUniqueId(), capability)).orElse(false);
    }

    /**
     * Whether the player currently sees native elements: mod with {@code CAP_HUD}, {@code web.client.ui} on and not
     * hidden with the H key ({@link ClientServices#uiHidden}). {@link #available} deliberately ignores hiding:
     * hidden elements stay stored on the client and keep being updated (PUT/PATCH take effect, after H they are
     * back at once – Core does not have to send anything again). Only toasts that arrive while hidden are dropped by the
     * client, and tooltip lines are missing meanwhile. So for important messages: {@code visible(p) ? toast : chat}.
     *
     * @since 0.26.4
     */
    public boolean visible(Player player) {
        return available(player, ClientServices.CAP_HUD) && !ClientServices.uiHidden(player);
    }

    /** Whether the element exists for the player and has not been removed/expired/rejected. */
    public boolean has(Player player, String localId) {
        return player != null && hub().map(h -> h.has(player.getUniqueId(), fullId(localId))).orElse(false);
    }

    // ------------------------------------------------------------------ sending

    /** Creates an element or replaces it completely. @return false without mod/capability or for an invalid element (warning in the log) */
    public boolean put(Player player, ClientElement.Element<?> element) {
        return element != null && put(player, element.toJson());
    }

    /** Like {@link #put(Player, ClientElement.Element)} with raw JSON (schema v1); {@code id} local or full. */
    public boolean put(Player player, JsonObject element) {
        thread("put");
        if (player == null || element == null) return false;
        String id = idOf(element);
        if (id == null) return invalid("put", "id missing or invalid");
        JsonObject copy = element.deepCopy();
        copy.addProperty("id", fullId(id));
        return report("put", id, hub().map(h -> h.put(player, copy)).orElse(null));
    }

    /**
     * Changes only the set fields of an existing element (flat merge, e.g. {@code bar("xp").value(3)}).
     * Batched per tick/element, unchanged fields are not sent.
     *
     * @return false if the element does not exist (any more) – then call {@link #put}
     */
    public boolean patch(Player player, ClientElement.Element<?> fields) {
        return fields != null && patch(player, fields.id(), fields.toJson());
    }

    /** Like {@link #patch(Player, ClientElement.Element)} with raw fields. */
    public boolean patch(Player player, String localId, JsonObject fields) {
        thread("patch");
        if (player == null || fields == null || !validLocal(localId)) return false;
        JsonObject copy = fields.deepCopy();
        copy.remove("id");
        ClientUiHub.Result result = hub().map(h -> h.patch(player, fullId(localId), copy)).orElse(null);
        if (result != null && result.status() == ClientUiHub.Status.UNKNOWN_ID) return false;
        return report("patch", localId, result);
    }

    /** Removes elements. */
    public boolean remove(Player player, String... localIds) {
        thread("remove");
        if (player == null || localIds == null || localIds.length == 0) return false;
        List<String> ids = new ArrayList<>(localIds.length);
        for (String id : localIds) if (validLocal(id)) ids.add(fullId(id));
        return report("remove", "", hub().map(h -> h.remove(player, ids)).orElse(null));
    }

    /** Removes all elements of this plugin for the player. */
    public boolean clear(Player player) {
        thread("clear");
        return player != null && report("clear", "", hub().map(h -> h.clear(player, prefix)).orElse(null));
    }

    /**
     * Removes all elements of this plugin for the player whose local ID starts with {@code localPrefix} (e.g.
     * {@code "event/"}), without tracking the IDs yourself. Empty prefix = {@link #clear(Player)}.
     *
     * @since 0.26.4
     */
    public boolean clear(Player player, String localPrefix) {
        thread("clear");
        if (player == null || localPrefix == null) return false;
        return report("clear", localPrefix, hub().map(h -> h.clear(player, prefix + localPrefix)).orElse(null));
    }

    /** Removes all elements of this plugin for all players. */
    public void clearAll() {
        thread("clearAll");
        hub().ifPresent(h -> h.releasePrefix(prefix));
    }

    /** One-time message ({@code CAP_TOASTS}). A {@link ClientElement.Toast#action} label is left out here. */
    public boolean toast(Player player, ClientElement.Toast toast) {
        if (toast == null) return false;
        JsonObject json = toast.toJson();
        json.remove("action");
        return toast(player, json);
    }

    /** Like {@link #toast(Player, ClientElement.Toast)} with raw JSON (an {@code action} field needs {@code CAP_UI_EXTENSIONS}). */
    public boolean toast(Player player, JsonObject toast) {
        thread("toast");
        if (player == null || toast == null) return false;
        return report("toast", "", hub().map(h -> h.toast(player, toast)).orElse(null));
    }

    /**
     * Toast with an action: the player triggers it with the client key "accept notification" (default Y, shown on the toast
     * as {@code "[Y] <label>"}, label through {@link ClientElement.Toast#action(String)}) while the toast is
     * visible. Core then calls {@code onAction} on the server thread – <b>at most once</b>, only for this
     * player, only within {@code 3 × display time + 15 s} after sending, rate-limited (2/s); unknown, duplicate and
     * expired actions are silently ignored by Core. At most 16 actions stay open per player (the oldest are dropped); on
     * quit and when the plugin is disabled they expire.
     *
     * <p><b>Security:</b> the client only sends back the action ID assigned by Core – a modified client can
     * therefore at most "press Y" without the toast having been visible. The handler must check everything again itself
     * (invitation still valid? player still allowed? place, money, state?), as if the command came from the chat.
     *
     * <pre>{@code
     * boolean shown = ui.toast(target, ClientElement.toast("Invitation from " + leader.getName()).action("Join")
     *         .duration(10_000), p -> parties.acceptInvite(p, partyId));   // checks invitation, size, permissions
     * if (!shown) target.sendMessage(clickableInvite(partyId));               // vanilla way
     * }</pre>
     *
     * @param onAction handler (server thread); exceptions are reported in the plugin's log
     * @return false without a mod with {@code CAP_TOASTS} and {@link ClientServices#CAP_UI_EXTENSIONS} (client &lt; 0.9.0), for
     *         an invalid toast or a full queue – then take the vanilla way (e.g. a clickable chat message)
     * @since 0.26.4
     */
    public boolean toast(Player player, ClientElement.Toast toast, Consumer<Player> onAction) {
        thread("toast");
        if (player == null || toast == null || onAction == null) return false;
        Consumer<Player> guarded = p -> {
            try {
                onAction.accept(p);
            } catch (RuntimeException | LinkageError failure) {
                Logger logger = Bukkit.getLogger();
                try { logger = owner.getLogger(); } catch (RuntimeException ignored) { }
                logger.log(java.util.logging.Level.WARNING, "[ClientUi] toast action handler failed", failure);
            }
        };
        return report("toast", "", hub().map(h -> h.toast(player, toast.toJson(), prefix, guarded)).orElse(null));
    }

    // ------------------------------------------------------------------ lifecycle

    /** When a plugin is disabled, removes its elements for all players (through {@code CoreLifecycle}). */
    public static void release(Plugin owner) {
        if (owner == null) return;
        String ns = namespace(owner.getName());
        ClientUi ui = BY_NAMESPACE.get(ns);
        if (ui == null || ui.owner != owner) return;
        BY_NAMESPACE.remove(ns, ui);
        ClientPlatform.uiHub().ifPresent(h -> h.releasePrefix(ui.prefix));
    }

    // ------------------------------------------------------------------ internal

    private static Optional<ClientUiHub> hub() { return ClientPlatform.uiHub(); }

    private static void thread(String method) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ClientUi." + method + ": server thread only");
    }

    private static boolean validLocal(String id) { return id != null && LOCAL_ID.matcher(id).matches(); }

    private static String idOf(JsonObject element) {
        JsonElement id = element.get("id");
        if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) return null;
        return validLocal(id.getAsString()) ? id.getAsString() : null;
    }

    private boolean invalid(String method, String detail) {
        warnOnce(method + ": " + detail);
        return false;
    }

    private boolean report(String method, String id, ClientUiHub.Result result) {
        if (result == null) return false;
        switch (result.status()) {
            case OK, UNCHANGED -> { return true; }
            case INVALID -> warnOnce(method + " " + (id.isEmpty() ? "" : fullId(id) + " ") + "invalid: " + result.detail());
            case TOO_LARGE -> warnOnce(method + " " + fullId(id) + ": element too large");
            case LIMIT -> warnOnce(method + " " + fullId(id) + ": client limit reached (32 HUD, 64 markers, 128 tooltips)");
            case QUEUE_FULL -> warnOnce(method + ": send queue full, dropped");
            default -> { }
        }
        return false;
    }

    private void warnOnce(String message) {
        if (warned.size() > 64 || !warned.add(message)) return;
        Logger logger = Bukkit.getLogger();
        try { logger = owner.getLogger(); } catch (RuntimeException ignored) { }
        logger.warning("[ClientUi] " + message);
    }
}
