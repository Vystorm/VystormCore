package Vystorm.vystorm_core.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * One argument of a command node: a type (parser + default suggestions), an id (the key in {@link CommandContext#get})
 * and whether it is optional. Instances are immutable; {@link #optional()} and {@link #suggests} return copies.
 *
 * <p>Keyword arguments ({@link #choice}, {@link #enumeration}, {@link #bool}) are localized: the English key is always
 * accepted, plus the names of every installed language from {@code commands.<cmd>.values.<key>} in the plugin's bundle
 * or Core's shared {@code command.values.<key>} (on/off, true/false, yes/no, clear, list, all). Tab completion offers
 * the names in the player's language; the handler always gets the English key (or the enum constant / boolean).
 *
 * <p>Display name in usage lines: {@code <node>.args.<id>} in the plugin's bundle, else the id in capitals
 * ({@code NAME}); optional arguments are shown as {@code [NAME]}.
 *
 * @since 0.45.0
 */
public final class Arg<T> {
    /** Built-in argument kinds. */
    public enum Kind { WORD, GREEDY, INTEGER, DECIMAL, PLAYER, PLAYER_NAME, WORLD, CHOICE, BOOLEAN, CUSTOM }

    /** Turns the typed text into a value; throw {@link CommandException} for a localized error. */
    @FunctionalInterface
    public interface Parser<T> { T parse(CommandContext context, String input); }

    private final String id;
    private final Kind kind;
    private final boolean optional;
    private final List<String> keys;
    private final Class<?> enumType;
    private final double min, max;
    private final Parser<T> parser;
    private final Suggester suggester;

    private Arg(String id, Kind kind, boolean optional, List<String> keys, Class<?> enumType, double min, double max, Parser<T> parser, Suggester suggester) {
        this.id = checkId(id);
        this.kind = kind;
        this.optional = optional;
        this.keys = keys;
        this.enumType = enumType;
        this.min = min;
        this.max = max;
        this.parser = parser;
        this.suggester = suggester;
    }

    static String checkId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Argument/command ids are lower-case a-z, 0-9, _ and - (max 32): " + id);
        return id;
    }

    // ------------------------------------------------------------------ factories

    /** One word. */
    public static Arg<String> word(String id) { return new Arg<>(id, Kind.WORD, false, List.of(), null, 0, 0, (c, s) -> s, null); }

    /** The rest of the line (several words); only as the last argument. */
    public static Arg<String> greedy(String id) { return new Arg<>(id, Kind.GREEDY, false, List.of(), null, 0, 0, (c, s) -> s, null); }

    /** A whole number. */
    public static Arg<Integer> integer(String id) { return integer(id, Integer.MIN_VALUE, Integer.MAX_VALUE); }

    /** A whole number in {@code [min, max]}; errors {@code command.error.not-a-number} / {@code number-range}. */
    public static Arg<Integer> integer(String id, int min, int max) {
        return new Arg<>(id, Kind.INTEGER, false, List.of(), null, min, max, (c, s) -> {
            int value;
            try { value = Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { throw new CommandException("command.error.not-a-number", "input", s); }
            if (value < min || value > max) throw new CommandException("command.error.number-range", "input", s, "min", min, "max", max);
            return value;
        }, null);
    }

    /** A decimal number in {@code [min, max]} (dot or comma). */
    public static Arg<Double> decimal(String id, double min, double max) {
        return new Arg<>(id, Kind.DECIMAL, false, List.of(), null, min, max, (c, s) -> {
            double value;
            try { value = Double.parseDouble(s.trim().replace(',', '.')); }
            catch (NumberFormatException e) { throw new CommandException("command.error.not-a-number", "input", s); }
            if (Double.isNaN(value) || value < min || value > max) throw new CommandException("command.error.number-range", "input", s, "min", trim(min), "max", trim(max));
            return value;
        }, null);
    }

    /** An online player the sender can see (exact name, else a unique name prefix); suggests visible players. */
    public static Arg<Player> player(String id) {
        return new Arg<>(id, Kind.PLAYER, false, List.of(), null, 0, 0, (c, s) -> {
            Player found = findPlayer(c, s);
            if (found == null) throw new CommandException("command.error.player-not-found", "input", s);
            return found;
        }, null);
    }

    /** Any player name as text (also offline players); suggests visible online players. */
    public static Arg<String> playerName(String id) { return new Arg<>(id, Kind.PLAYER_NAME, false, List.of(), null, 0, 0, (c, s) -> s, null); }

    /** A loaded world by name; suggests all worlds. */
    public static Arg<World> world(String id) {
        return new Arg<>(id, Kind.WORLD, false, List.of(), null, 0, 0, (c, s) -> {
            World world = Bukkit.getWorld(s);
            if (world == null) for (World w : Bukkit.getWorlds()) if (w.getName().equalsIgnoreCase(s)) world = w;
            if (world == null) throw new CommandException("command.error.world-not-found", "input", s);
            return world;
        }, null);
    }

    /** One of the given English keys (lower case), localized as described in the class doc. Value: the English key. */
    public static Arg<String> choice(String id, String... keys) {
        List<String> list = keys(keys == null ? List.of() : Arrays.asList(keys));
        if (list.isEmpty()) throw new IllegalArgumentException("choice " + id + " needs keys");
        return new Arg<>(id, Kind.CHOICE, false, list, null, 0, 0, null, null);
    }

    /** One of the enum's constants; keys are the lower-case constant names ({@code NORTH_EAST} → {@code north_east}). */
    public static <E extends Enum<E>> Arg<E> enumeration(String id, Class<E> type) {
        Objects.requireNonNull(type, "type");
        List<String> list = new ArrayList<>();
        for (E constant : type.getEnumConstants()) list.add(constant.name().toLowerCase(Locale.ROOT));
        return new Arg<>(id, Kind.CHOICE, false, List.copyOf(list), type, 0, 0, null, null);
    }

    /**
     * Yes/no: accepts on/off, true/false, yes/no (English and localized) and suggests on/off in the player's language.
     * Value: {@code Boolean}.
     */
    public static Arg<Boolean> bool(String id) {
        return new Arg<>(id, Kind.BOOLEAN, false, List.of("on", "off", "true", "false", "yes", "no"), null, 0, 0, null, null);
    }

    /** Own type; combine with {@link #suggests} for completion. */
    public static <T> Arg<T> custom(String id, Parser<T> parser) {
        return new Arg<>(id, Kind.CUSTOM, false, List.of(), null, 0, 0, Objects.requireNonNull(parser, "parser"), null);
    }

    // ------------------------------------------------------------------ modifiers

    /** A copy that may be left out; only trailing arguments can be optional. */
    public Arg<T> optional() { return new Arg<>(id, kind, true, keys, enumType, min, max, parser, suggester); }

    /** A copy with own tab completion (replaces the type's default suggestions). */
    public Arg<T> suggests(Suggester suggester) { return new Arg<>(id, kind, optional, keys, enumType, min, max, parser, Objects.requireNonNull(suggester, "suggester")); }

    /** A copy with fixed suggestions. */
    public Arg<T> suggests(Collection<String> values) {
        List<String> copy = List.copyOf(values);
        return suggests((c, p) -> copy);
    }

    // ------------------------------------------------------------------ accessors

    public String id() { return id; }

    public Kind kind() { return kind; }

    public boolean isOptional() { return optional; }

    /** English keys of a keyword argument (choice, enum, bool); empty otherwise. */
    public List<String> keys() { return keys; }

    public double min() { return min; }

    public double max() { return max; }

    /** The custom suggester, or {@code null} for the type's default. */
    public Suggester suggester() { return suggester; }

    Parser<T> parser() { return parser; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object fromKey(String key) {
        if (kind == Kind.BOOLEAN) return key.equals("on") || key.equals("true") || key.equals("yes");
        if (enumType != null) return Enum.valueOf((Class) enumType, key.toUpperCase(Locale.ROOT));
        return key;
    }

    /** Keys offered in tab completion for keyword arguments (bool: only on/off). */
    List<String> suggestedKeys() { return kind == Kind.BOOLEAN ? List.of("on", "off") : keys; }

    @Override public String toString() { return "Arg[" + id + " " + kind + (optional ? " optional" : "") + "]"; }

    // ------------------------------------------------------------------ helpers

    private static List<String> keys(Collection<String> raw) {
        List<String> out = new ArrayList<>();
        for (String k : raw) {
            String key = checkId(k == null ? null : k.toLowerCase(Locale.ROOT));
            if (!out.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    private static String trim(double d) { return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d); }

    static Player findPlayer(CommandContext context, String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && visible(context, exact)) return exact;
        Player match = null;
        String lower = name.toLowerCase(Locale.ROOT);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!visible(context, p) || !p.getName().toLowerCase(Locale.ROOT).startsWith(lower)) continue;
            if (p.getName().equalsIgnoreCase(name)) return p;
            if (match != null) return null; // ambiguous prefix
            match = p;
        }
        return match;
    }

    static boolean visible(CommandContext context, Player target) {
        return !(context.sender() instanceof Player viewer) || viewer.canSee(target);
    }
}
