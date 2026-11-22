package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Placeholders of all Vystorm plugins in one registry, reached through {@code api.placeholders()}.
 * <p>
 * A plugin registers a namespace and declares its values; every text that is resolved here, and every plugin that
 * uses PlaceholderAPI, can then show them.
 *
 * <pre>{@code
 * core.placeholders().register(this, "xpvault")
 *         .value("balance", "XP in the player's account", player -> bank.balance(player.getUniqueId()));
 *
 * String line = core.placeholders().resolve(player, "Balance: %vystorm_xpvault_balance%");
 * }</pre>
 *
 * <h2>Syntax</h2>
 * <ul>
 *   <li>{@code %vystorm_<namespace>_<key>%}, with arguments {@code %vystorm_<namespace>_<key>_<arg>_<arg>%}. This is
 *       PlaceholderAPI's form, so the same text works in every plugin that uses PlaceholderAPI (Core registers the
 *       expansion {@code vystorm} there). A key may contain underscores: the longest declared key wins, the rest are
 *       arguments.</li>
 *   <li>{@code {<namespace>:<key>}}, with arguments {@code {<namespace>:<key>:<arg>:<arg>}}: the short form for
 *       Vystorm texts, where named placeholders are written with braces. Only braces with a registered namespace
 *       before the colon are placeholders; {@code {player}} and {@code {lang:...}} are never touched.</li>
 *   <li>Namespace: a letter followed by letters and digits, at most 32 characters, lower case. Key: letters, digits,
 *       {@code _ . -}. Namespace and key are matched without regard to case; arguments keep their case.</li>
 *   <li>A backslash directly before a placeholder ({@code \%vystorm_core_online%}, {@code \{core:online}}) shows it
 *       as written, without the backslash.</li>
 *   <li>A placeholder nobody answers stays in the text as written. Other {@code %identifier_params%} placeholders
 *       are passed to the bridges (PlaceholderAPI), one placeholder at a time.</li>
 * </ul>
 * A text is read once from left to right. What a value returns is never read again, so a value that contains
 * {@code %vystorm_...%} or {@code {core:online}} is shown as it is.
 *
 * <h2>Formatting and safety</h2>
 * {@link #resolve} returns plain text for String-based places. For MiniMessage texts use {@link #component} or
 * {@link #miniMessage}: there a value is inserted literally and its {@code <tags>} are not parsed, unless the
 * registrant marked the value as {@link PlaceholderValue#formatted() formatted}. Do not pass the result of
 * {@link #resolve} to MiniMessage: values can contain what players typed.
 *
 * <h2>Threads</h2>
 * A resolver runs on the server thread, unless its value was declared {@link PlaceholderValue#asyncSafe()
 * async-safe}. Resolving a text from another thread is allowed: then only async-safe values (and async-safe
 * bridges) are asked, and every other placeholder stays in the text as written. Core never moves a call to the
 * server thread on its own and never blocks waiting for it. Registering and unregistering is allowed from any
 * thread.
 *
 * <h2>Lifecycle and failures</h2>
 * A namespace belongs to one plugin. Its values are removed when the plugin is disabled, and they are not asked while
 * the plugin is disabled. A resolver that throws is reported (at most once a minute per value) and its placeholder
 * stays as written.
 * <p>
 * Every method has a default body that uses Core's registry, so a replacement manager overrides only what it
 * changes. Capability {@code CoreApi.Capability.PLACEHOLDERS}.
 *
 * @since 0.65.0
 */
public interface PlaceholderInterface {
    /** The identifier in the percent form and of the PlaceholderAPI expansion: {@code %vystorm_...%}. */
    String IDENTIFIER = "vystorm";

    /**
     * A MiniMessage text whose resolved placeholders were replaced by private tags, and the resolver that fills
     * them: {@code MiniMessage.miniMessage().deserialize(prepared.text(), prepared.resolver())}.
     *
     * @param text the text to parse
     * @param resolver the values; {@link TagResolver#empty()} if nothing was replaced
     * @param replaced how many placeholders were replaced
     */
    record Prepared(String text, TagResolver resolver, int replaced) {}

    // ------------------------------------------------------------------ registry

    /**
     * Registers the namespace of a plugin, or returns the one this plugin registered before.
     *
     * @param namespace a letter followed by letters and digits, at most 32 characters; upper case is folded.
     *                  Reserved, because braces with these names mean something else already: {@code lang},
     *                  {@code data}, {@code rel}, {@code vystorm}, {@code countdown}, {@code votes}, {@code vote}
     *                  and {@code guestbook}.
     * @throws IllegalArgumentException for an invalid or reserved name
     * @throws IllegalStateException if another enabled plugin owns the namespace
     */
    default PlaceholderNamespace register(Plugin owner, String namespace) {
        return PlaceholderHandler.shared().register(owner, namespace);
    }

    /**
     * Registers a namespace that one resolver answers: it gets every key that is not declared as a value
     * ({@link PlaceholderNamespace#fallback(PlaceholderResolver)}). Runs on the server thread.
     */
    default PlaceholderNamespace register(Plugin owner, String namespace, PlaceholderResolver resolver) {
        return register(owner, namespace).fallback(resolver);
    }

    /** Declares one value of the viewer in the plugin's namespace: {@code value(this, "xpvault", "balance", p -> ...)}. */
    default PlaceholderNamespace value(Plugin owner, String namespace, String key, Function<? super OfflinePlayer, ?> value) {
        return register(owner, namespace).value(key, value);
    }

    /** Removes one namespace of the plugin. */
    default boolean unregister(Plugin owner, String namespace) { return PlaceholderHandler.shared().unregister(owner, namespace); }

    /** Removes every namespace and bridge of the plugin. Core does this when the plugin is disabled. */
    default int unregisterAll(Plugin owner) { return PlaceholderHandler.shared().unregisterAll(owner); }

    /** The registered namespaces, sorted by name. */
    default List<PlaceholderNamespace> namespaces() { return PlaceholderHandler.shared().namespaces(); }

    /** A registered namespace by name (any case). */
    default Optional<PlaceholderNamespace> namespace(String name) { return PlaceholderHandler.shared().namespace(name); }

    // ------------------------------------------------------------------ resolving

    /**
     * Replaces the placeholders in a plain text. A text without {@code %} and <code>{</code>, and a text in which
     * nothing was replaced, is returned as the same instance. A formatted value is inserted with legacy {@code §}
     * colour codes.
     *
     * @param viewer the player the text is for; {@code null} for the console (values of a player then stay as written)
     * @param text the text; {@code null} gives {@code null}
     */
    default String resolve(OfflinePlayer viewer, String text) { return resolve(viewer, null, text); }

    /** Like {@link #resolve(OfflinePlayer, String)} with a second player for relational values. */
    default String resolve(OfflinePlayer viewer, Player other, String text) {
        return PlaceholderHandler.shared().resolve(viewer, other, text);
    }

    /** {@link #resolve(OfflinePlayer, String)} for a command sender: a player is the viewer, the console is none. */
    default String resolveFor(CommandSender sender, String text) {
        return resolve(sender instanceof OfflinePlayer player ? player : null, text);
    }

    /** Parses a MiniMessage text with its placeholders; values are inserted literally unless marked as formatted. */
    default Component component(OfflinePlayer viewer, String miniMessage) { return component(viewer, null, miniMessage); }

    /** Like {@link #component(OfflinePlayer, String)} with a second player for relational values. */
    default Component component(OfflinePlayer viewer, Player other, String miniMessage) {
        return PlaceholderHandler.parse(miniMessage(viewer, other, miniMessage));
    }

    /**
     * Prepares a MiniMessage text for parsing by the caller, for example together with tag resolvers of its own:
     * every resolved placeholder becomes a private tag.
     */
    default Prepared miniMessage(OfflinePlayer viewer, Player other, String text) {
        return PlaceholderHandler.shared().miniMessage(viewer, other, text);
    }

    /**
     * Answers one placeholder given as {@code <namespace>_<key>[_<arguments>]}, the part after {@code vystorm_}. This
     * is what a bridge calls for the other plugin.
     *
     * @return the value as plain text, or {@code null} if nothing answers
     */
    default String request(OfflinePlayer viewer, Player other, String params) {
        return PlaceholderHandler.shared().request(viewer, other, params);
    }

    /**
     * Answers one value directly, without writing a text.
     *
     * @return the value as plain text, or {@code null} if nothing answers
     */
    default String value(OfflinePlayer viewer, String namespace, String key, String... arguments) {
        return PlaceholderHandler.shared().value(viewer, namespace, key, arguments);
    }

    // ------------------------------------------------------------------ rankings (0.67.0)

    /** Most rows a ranking gives; a larger {@code n} is capped to this. @since 0.67.0 */
    int RANKING_MAX_ROWS = 100;

    /**
     * Every declared ranking ({@link PlaceholderNamespace#ranking}), by namespace and then in declaration order.
     * Any thread. Capability {@code placeholder-rankings}. @since 0.67.0
     */
    default List<PlaceholderRanking> rankings() { return PlaceholderHandler.shared().rankings(); }

    /** A declared ranking by id {@code <namespace>:<key>} (any case). Any thread. @since 0.67.0 */
    default Optional<PlaceholderRanking> ranking(String id) { return PlaceholderHandler.shared().ranking(id); }

    /**
     * The best {@code n} rows of a ranking, best first, with the players' UUIDs; {@code n} is capped at
     * {@link #RANKING_MAX_ROWS}. An unknown id, a disabled plugin or a source that throws gives an empty list.
     *
     * @throws IllegalStateException off the server thread: use {@link #topAsync}
     * @since 0.67.0
     */
    default List<RankingSource.Row> top(String id, int n) { return PlaceholderHandler.shared().top(id, n); }

    /**
     * {@link #top} for callers off the server thread: the work runs on the server thread (in the next tick) and the
     * future completes there. On the server thread it is complete at once. @since 0.67.0
     */
    default CompletableFuture<List<RankingSource.Row>> topAsync(String id, int n) { return PlaceholderHandler.shared().topAsync(id, n); }

    /**
     * The rank of a player (1 = best): the source's own answer, else his place in the best 100. Server thread.
     * @since 0.67.0
     */
    default OptionalInt rank(String id, UUID player) { return PlaceholderHandler.shared().rank(id, player); }

    /** The raw value of a player in a ranking: the source's own answer, else from the best 100. Server thread. @since 0.67.0 */
    default OptionalDouble rankingValue(String id, UUID player) { return PlaceholderHandler.shared().rankingValue(id, player); }

    // ------------------------------------------------------------------ bridges

    /**
     * Adds a connection to another placeholder plugin and connects it at once if that plugin is there. It is removed
     * when {@code owner} is disabled. Adding a bridge with the name of an existing one replaces that one.
     */
    default void bridge(Plugin owner, PlaceholderBridge bridge) { PlaceholderHandler.shared().bridge(owner, bridge); }

    /** Removes a bridge and disconnects it. */
    default boolean removeBridge(PlaceholderBridge bridge) { return PlaceholderHandler.shared().removeBridge(bridge); }

    /** The bridges in the order they are asked. */
    default List<PlaceholderBridge> bridges() { return PlaceholderHandler.shared().bridges(); }

    /**
     * Lets every bridge look again whether its plugin is there ({@link PlaceholderBridge#provide}). Core calls this
     * when a plugin is enabled or disabled. Server thread.
     */
    default void refreshBridges() { PlaceholderHandler.shared().refreshBridges(); }
}
