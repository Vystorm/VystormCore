package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The namespace of one plugin, returned by {@link PlaceholderInterface#register}: the place where it declares its
 * values. All methods return the namespace, so declarations can be chained:
 *
 * <pre>{@code
 * core.placeholders().register(this, "xpvault")
 *         .value("balance", "XP in the player's account", player -> bank.balance(player.getUniqueId()))
 *         .global("accounts", "Number of accounts", bank::accounts);
 * }</pre>
 *
 * Declaring a key again replaces it. Declaring and removing is allowed from any thread.
 *
 * @since 0.65.0
 */
public interface PlaceholderNamespace {
    /** The name, lower case. */
    String name();

    /** The plugin that registered the namespace. */
    Plugin owner();

    /** Declares a value; an existing value with the same key is replaced. */
    PlaceholderNamespace add(PlaceholderValue value);

    /**
     * Answers every key of this namespace that is not declared as a value: the resolver gets the first part after the
     * namespace as {@link PlaceholderContext#key()}, the rest as arguments and the unsplit text as
     * {@link PlaceholderContext#raw()}. It runs on the server thread only. {@code null} removes it.
     */
    PlaceholderNamespace fallback(PlaceholderResolver resolver);

    /** Like {@link #fallback(PlaceholderResolver)}; with {@code asyncSafe = true} it is also called off the server thread. */
    PlaceholderNamespace fallback(PlaceholderResolver resolver, boolean asyncSafe);

    /** Removes a declared value. */
    boolean remove(String key);

    /** The declared values in the order they were declared. */
    List<PlaceholderValue> values();

    /** A declared value by key. */
    Optional<PlaceholderValue> value(String key);

    /** Whether a namespace-wide resolver answers undeclared keys. */
    boolean hasFallback();

    /** Whether the namespace is still registered (false after its plugin was disabled). */
    boolean registered();

    /**
     * Declares a ranking: a family of placeholders in this namespace, answered from the source, and an entry in
     * {@link PlaceholderInterface#rankings()} under the id {@code <namespace>:<key>}.
     *
     * <pre>{@code
     * core.placeholders().register(this, "veins")
     *         .ranking("mined", RankingInfo.of(lang.ref("rankings.mined.label"), lang.ref("rankings.mined.description")),
     *                 n -> stats.topMined(n));
     * }</pre>
     *
     * It declares these values ({@code <key>} as given, lower case):
     * <ul>
     *   <li>{@code top_<key>} with arguments {@code <n> <field>}: {@code %vystorm_<ns>_top_<key>_<n>_name%},
     *       {@code ..._value%} (formatted by {@link RankingInfo#format()} in the viewer's language), {@code ..._raw%}
     *       (the plain number) and {@code ..._uuid%}; {@code n} is 1 to 100; a row that does not exist is empty.</li>
     *   <li>{@code rank_<key>} and {@code value_<key>}: the viewer's rank and formatted value, {@code -} when he has
     *       none.</li>
     * </ul>
     * A value of the plugin with one of these names is kept, a shorter one that matches ({@code top} with arguments)
     * and the plugin's {@link #fallback fallback} are asked first; declaring a value with such a name later replaces
     * the ranking's. Declaring the same key again replaces the ranking. Allowed from any thread; the source is only
     * called on the server thread.
     *
     * @param key lower-case letters, digits and {@code -}, 1 to 32 characters, starting and ending with a letter or
     *            digit (no underscore: it separates the parts of the placeholder name)
     * @throws IllegalArgumentException for another key
     * @throws UnsupportedOperationException on a namespace that is not Core's
     * @since 0.67.0
     */
    default PlaceholderNamespace ranking(String key, RankingInfo info, RankingSource source) {
        throw new UnsupportedOperationException("Rankings need Core's placeholder registry");
    }

    /** Removes a ranking and the placeholders made for it. @since 0.67.0 */
    default boolean removeRanking(String key) { return false; }

    /** The rankings of this namespace in the order they were declared. @since 0.67.0 */
    default List<PlaceholderRanking> rankings() { return List.of(); }

    /** A value of the viewer, online or not; see {@link PlaceholderValue#viewer}. */
    default PlaceholderNamespace value(String key, Function<? super OfflinePlayer, ?> value) {
        return add(PlaceholderValue.viewer(key, value));
    }

    /** A value of the viewer with its description. */
    default PlaceholderNamespace value(String key, String description, Function<? super OfflinePlayer, ?> value) {
        return add(PlaceholderValue.viewer(key, value).description(description));
    }

    /** A value of the viewer that needs him online; see {@link PlaceholderValue#player}. */
    default PlaceholderNamespace player(String key, String description, Function<? super Player, ?> value) {
        return add(PlaceholderValue.player(key, value).description(description));
    }

    /** A value that is the same for everyone; see {@link PlaceholderValue#global}. */
    default PlaceholderNamespace global(String key, String description, Supplier<?> value) {
        return add(PlaceholderValue.global(key, value).description(description));
    }

    /** A value between the viewer and a second player; see {@link PlaceholderValue#relational}. */
    default PlaceholderNamespace relational(String key, String description, BiFunction<? super Player, ? super Player, ?> value) {
        return add(PlaceholderValue.relational(key, value).description(description));
    }

    /** A value with arguments: the resolver reads them from the context ({@code context.argument(0)}, {@code intArgument}). */
    default PlaceholderNamespace resolver(String key, String description, String arguments, PlaceholderResolver resolver) {
        return add(PlaceholderValue.of(key, resolver).description(description).arguments(arguments));
    }
}
