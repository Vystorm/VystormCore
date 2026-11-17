package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One editable value in a {@link SettingsSection}. The owning plugin supplies getter and setter, so the
 * value stays in the owner's storage; Core only renders it as a native dialog input.
 * <p>
 * Values are {@link Boolean} (toggle), {@link Double} (number), or {@link String} (choice id / text).
 * The setter returns {@code null} on success or a short error message shown to the player.
 * For server settings the player is the editing admin.
 */
public record Setting(String id, Kind kind, String label, String description, String permission,
                      double min, double max, double step, List<Option> options, int maxLength,
                      Function<Player, Object> getter, BiFunction<Player, Object, String> setter) {
    public enum Kind { TOGGLE, NUMBER, CHOICE, TEXT }

    public record Option(String id, String label) {
        public Option {
            if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,64}")) throw new IllegalArgumentException("invalid option id: " + id);
            Objects.requireNonNull(label, "label");
        }
    }

    public Setting {
        if (id == null || !id.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid setting id: " + id);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        description = description == null ? "" : description;
        permission = permission == null ? "" : permission;
        options = List.copyOf(options == null ? List.of() : options);
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(setter, "setter");
        if (kind == Kind.NUMBER && !(min < max && step > 0 && Double.isFinite(min) && Double.isFinite(max)))
            throw new IllegalArgumentException("number setting needs min < max and step > 0: " + id);
        if (kind == Kind.CHOICE && options.isEmpty()) throw new IllegalArgumentException("choice setting needs options: " + id);
        if (new HashSet<>(options.stream().map(Option::id).toList()).size() != options.size())
            throw new IllegalArgumentException("duplicate option ids: " + id);
        if (kind == Kind.TEXT && (maxLength < 1 || maxLength > 256)) throw new IllegalArgumentException("text length 1..256: " + id);
    }

    public static Setting toggle(String id, String label, String description,
                                 Function<Player, Boolean> get, BiFunction<Player, Boolean, String> set) {
        return new Setting(id, Kind.TOGGLE, label, description, "", 0, 0, 0, null, 0,
                p -> get.apply(p), (p, v) -> set.apply(p, (Boolean) v));
    }

    /** A slider; whole-number steps deliver whole {@link Double} values. */
    public static Setting number(String id, String label, String description, double min, double max, double step,
                                 Function<Player, Double> get, BiFunction<Player, Double, String> set) {
        return new Setting(id, Kind.NUMBER, label, description, "", min, max, step, null, 0,
                p -> get.apply(p), (p, v) -> set.apply(p, (Double) v));
    }

    /** Options map id to display label, shown in insertion order. */
    public static Setting choice(String id, String label, String description, LinkedHashMap<String, String> options,
                                 Function<Player, String> get, BiFunction<Player, String, String> set) {
        List<Option> list = options.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList();
        return new Setting(id, Kind.CHOICE, label, description, "", 0, 0, 0, list, 0,
                p -> get.apply(p), (p, v) -> set.apply(p, (String) v));
    }

    public static Setting text(String id, String label, String description, int maxLength,
                               Function<Player, String> get, BiFunction<Player, String, String> set) {
        return new Setting(id, Kind.TEXT, label, description, "", 0, 0, 0, null, maxLength,
                p -> get.apply(p), (p, v) -> set.apply(p, (String) v));
    }

    /** Restricts this single setting to players holding {@code node}. */
    public Setting permission(String node) {
        return new Setting(id, kind, label, description, node, min, max, step, options, maxLength, getter, setter);
    }

    boolean allowed(Player player) { return permission.isBlank() || player.hasPermission(permission); }
}
