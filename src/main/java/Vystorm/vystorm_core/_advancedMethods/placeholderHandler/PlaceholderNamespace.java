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
