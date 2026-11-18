package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * One value of a namespace: its key, a one-line description for admins, and how it is answered. Immutable; the
 * {@code with}-style methods ({@link #description}, {@link #asyncSafe}, {@link #formatted} ...) return a changed copy.
 *
 * <pre>{@code
 * namespace.add(PlaceholderValue.viewer("balance", player -> bank.balance(player.getUniqueId()))
 *         .description("XP in the player's account"));
 * namespace.add(PlaceholderValue.of("top", context -> ranking.name(context.intArgument(0, 1)))
 *         .arguments("<rank>").description("Name of the player on this rank").asyncSafe());
 * }</pre>
 *
 * @since 0.65.0
 */
public final class PlaceholderValue {
    private final String key;
    private final PlaceholderResolver resolver;
    private final String description;
    private final String arguments;
    private final boolean asyncSafe;
    private final boolean formatted;
    private final int cacheTicks;

    private PlaceholderValue(String key, PlaceholderResolver resolver, String description, String arguments,
                             boolean asyncSafe, boolean formatted, int cacheTicks) {
        this.key = key;
        this.resolver = resolver;
        this.description = description;
        this.arguments = arguments;
        this.asyncSafe = asyncSafe;
        this.formatted = formatted;
        this.cacheTicks = cacheTicks;
    }

    /**
     * A value answered by a resolver that sees the whole context (viewer, second player, arguments).
     *
     * @param key lower-case letters, digits, {@code _}, {@code .} and {@code -}; 1 to 64 characters, starting and
     *            ending with a letter or digit. Upper case is folded.
     * @throws IllegalArgumentException for another key
     */
    public static PlaceholderValue of(String key, PlaceholderResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return new PlaceholderValue(checkKey(key), resolver, "", "", false, false, 0);
    }

    /** A value that is the same for everyone (players online, server time). */
    public static PlaceholderValue global(String key, Supplier<?> value) {
        Objects.requireNonNull(value, "value");
        return of(key, context -> value.get());
    }

    /** A value of the viewer, online or not. Without a viewer the placeholder stays as written. */
    public static PlaceholderValue viewer(String key, Function<? super OfflinePlayer, ?> value) {
        Objects.requireNonNull(value, "value");
        return of(key, context -> context.viewer() == null ? null : value.apply(context.viewer()));
    }

    /** A value of the viewer that needs him online (world, position). Otherwise the placeholder stays as written. */
    public static PlaceholderValue player(String key, Function<? super Player, ?> value) {
        Objects.requireNonNull(value, "value");
        return of(key, context -> {
            Player online = context.player();
            return online == null ? null : value.apply(online);
        });
    }

    /**
     * A relational value: how the viewer (first argument, online) sees the other player (second argument). Without
     * both players the placeholder stays as written.
     */
    public static PlaceholderValue relational(String key, BiFunction<? super Player, ? super Player, ?> value) {
        Objects.requireNonNull(value, "value");
        return of(key, context -> {
            Player online = context.player();
            return online == null || context.other() == null ? null : value.apply(online, context.other());
        });
    }

    static String checkKey(String key) {
        Objects.requireNonNull(key, "key");
        String folded = key.toLowerCase(Locale.ROOT);
        if (!validKey(folded)) throw new IllegalArgumentException("invalid placeholder key: " + key);
        return folded;
    }

    static boolean validKey(String key) {
        int length = key.length();
        if (length == 0 || length > 64) return false;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            boolean word = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (word) continue;
            if ((c != '_' && c != '.' && c != '-') || i == 0 || i == length - 1) return false;
        }
        return true;
    }

    /**
     * One line that says what the value is, shown by {@code /vadmin placeholders}. A language reference
     * ({@code lang.ref("...")}) is shown in the admin's language.
     */
    public PlaceholderValue description(String text) {
        return new PlaceholderValue(key, resolver, text == null ? "" : text, arguments, asyncSafe, formatted, cacheTicks);
    }

    /** A hint for the arguments, shown after the key in lists: {@code "<world>"}, {@code "<board> <option>"}. */
    public PlaceholderValue arguments(String hint) {
        return new PlaceholderValue(key, resolver, description, hint == null ? "" : hint, asyncSafe, formatted, cacheTicks);
    }

    /**
     * The resolver may be called from any thread: it only reads data that is immutable, volatile or concurrent and
     * calls nothing that needs the server thread. Other values are left untouched when a text is resolved off the
     * server thread.
     */
    public PlaceholderValue asyncSafe() {
        return new PlaceholderValue(key, resolver, description, arguments, true, formatted, cacheTicks);
    }

    /**
     * The value is MiniMessage markup written by the plugin and may be parsed as such ({@code <red>12</red>}). Never
     * mark a value as formatted that contains text typed by players (names, chat, sign or guestbook lines): without
     * this mark a value is always inserted literally.
     */
    public PlaceholderValue formatted() {
        return new PlaceholderValue(key, resolver, description, arguments, asyncSafe, true, cacheTicks);
    }

    /**
     * Keeps the answer for this many server ticks per viewer and arguments, for a value that is expensive to compute
     * and asked many times a tick. 0 (the default) switches the cache off.
     */
    public PlaceholderValue cacheTicks(int ticks) {
        return new PlaceholderValue(key, resolver, description, arguments, asyncSafe, formatted, Math.max(0, ticks));
    }

    public String key() { return key; }
    public PlaceholderResolver resolver() { return resolver; }
    /** The description, or an empty string. */
    public String description() { return description; }
    /** The argument hint, or an empty string. */
    public String arguments() { return arguments; }
    public boolean isAsyncSafe() { return asyncSafe; }
    public boolean isFormatted() { return formatted; }
    public int cacheTicks() { return cacheTicks; }

    @Override public String toString() { return "PlaceholderValue[" + key + "]"; }
}
