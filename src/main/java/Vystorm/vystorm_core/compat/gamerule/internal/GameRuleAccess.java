package Vystorm.vystorm_core.compat.gamerule.internal;

import Vystorm.vystorm_core.compat.gamerule.GameRuleCatalog;
import Vystorm.vystorm_core.compat.gamerule.GameRuleCategory;
import Vystorm.vystorm_core.compat.gamerule.GameRuleChange;
import Vystorm.vystorm_core.compat.gamerule.GameRuleInfo;
import Vystorm.vystorm_core.compat.gamerule.GameRuleKind;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Game rules on every server shape without a single compiled call site on {@code org.bukkit.GameRule}:
 * <ul>
 * <li>Paper 1.20.6: {@code GameRule} is a final class, rules from {@code GameRule.values()};</li>
 * <li>Spigot 26.x: an interface, rules from {@code Registry.GAME_RULE};</li>
 * <li>Paper/Purpur 26.x: an abstract class (legacy constants over {@code org.bukkit.GameRules}), rules from
 * {@code Registry.GAME_RULE} (the legacy constants such as {@code DISABLE_RAIDS} are not used).</li>
 * </ul>
 * Code compiled against one shape throws {@code IncompatibleClassChangeError} on another, so every call on a rule and
 * the {@code World} calls that take one go through {@link Method#invoke}, looked up once. World calls are server-thread
 * only (as Bukkit's). Core-internal.
 */
public final class GameRuleAccess {
    public static final String SHAPE_FINAL_CLASS = "final-class", SHAPE_INTERFACE = "interface", SHAPE_ABSTRACT_CLASS = "abstract-class",
            UNAVAILABLE = "unavailable";

    private final String shape;
    private final Supplier<Iterable<?>> source;
    private final Method name, key, keyPart, type, getValue, getDefault, setValue;
    private volatile Map<String, Object> rules;

    private GameRuleAccess(String shape, Supplier<Iterable<?>> source, Method name, Method key, Method keyPart, Method type,
                           Method getValue, Method getDefault, Method setValue) {
        this.shape = shape;
        this.source = source;
        this.name = name;
        this.key = key;
        this.keyPart = keyPart;
        this.type = type;
        this.getValue = getValue;
        this.getDefault = getDefault;
        this.setValue = setValue;
    }

    /** The access for the running server ({@code org.bukkit.GameRule}, {@code org.bukkit.World}, {@code Registry.GAME_RULE}). */
    public static GameRuleAccess server() {
        try {
            Class<?> ruleClass = Class.forName("org.bukkit.GameRule");
            Class<?> registry = Class.forName("org.bukkit.Registry");
            Supplier<Iterable<?>> registryRules = null;
            try {
                Field field = registry.getField("GAME_RULE");
                registryRules = () -> {
                    try { return (Iterable<?>) field.get(null); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                };
            } catch (NoSuchFieldException olderServer) { /* 1.20.6: no registry */ }
            return of(ruleClass, Class.forName("org.bukkit.World"), registryRules);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return unavailable();
        }
    }

    /** An access that knows no rules (servers without any game-rule API). */
    public static GameRuleAccess unavailable() {
        return new GameRuleAccess(UNAVAILABLE, List::of, null, null, null, null, null, null, null);
    }

    /**
     * Builds the access for a rule type and a world type. {@code registryRules} is the registry of rules (26.x) or
     * {@code null}, then the static {@code values()} of the rule type is used.
     */
    public static GameRuleAccess of(Class<?> ruleClass, Class<?> worldClass, Supplier<Iterable<?>> registryRules) throws ReflectiveOperationException {
        String shape = ruleClass.isInterface() ? SHAPE_INTERFACE : Modifier.isFinal(ruleClass.getModifiers()) ? SHAPE_FINAL_CLASS : SHAPE_ABSTRACT_CLASS;
        Supplier<Iterable<?>> source = registryRules;
        if (source == null) {
            Method values = ruleClass.getMethod("values");
            source = () -> {
                try { return List.of((Object[]) values.invoke(null)); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            };
        }
        Method name = optional(ruleClass, "getName");
        Method key = optional(ruleClass, "getKey");
        Method keyPart = null;
        if (key != null) keyPart = key.getReturnType().getMethod("getKey");
        Method type = ruleClass.getMethod("getType");
        Method getValue = worldClass.getMethod("getGameRuleValue", ruleClass);
        Method getDefault = optional(worldClass, "getGameRuleDefault", ruleClass);
        Method setValue = worldClass.getMethod("setGameRule", ruleClass, Object.class);
        if (name == null && key == null) throw new NoSuchMethodException("GameRule has neither getName nor getKey");
        return new GameRuleAccess(shape, source, name, key, keyPart, type, getValue, getDefault, setValue);
    }

    private static Method optional(Class<?> owner, String method, Class<?>... parameters) {
        try { return owner.getMethod(method, parameters); } catch (NoSuchMethodException absent) { return null; }
    }

    /** {@link #SHAPE_FINAL_CLASS}, {@link #SHAPE_INTERFACE}, {@link #SHAPE_ABSTRACT_CLASS} or {@link #UNAVAILABLE}. */
    public String shape() { return shape; }

    /** The server's rules by vanilla id, read once. */
    Map<String, Object> rules() {
        Map<String, Object> current = rules;
        if (current != null) return current;
        Map<String, Object> out = new LinkedHashMap<>();
        if (getValue != null) {
            for (Object rule : source.get()) {
                String id = vanillaId(rule);
                if (id != null && !id.isEmpty()) out.putIfAbsent(id, rule);
            }
        }
        rules = out;
        return out;
    }

    /** The id the server uses: the key's value part (26.x), else {@code getName()} (1.20.6). */
    String vanillaId(Object rule) {
        try {
            if (key != null) {
                Object k = key.invoke(rule);
                if (k != null) return String.valueOf(keyPart.invoke(k));
            }
            return name == null ? null : String.valueOf(name.invoke(rule));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** All rules of a world, sorted by stable id. A rule the server refuses to read is left out. */
    public List<GameRuleInfo> rules(Object world) {
        List<GameRuleInfo> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : rules().entrySet()) info(world, e.getKey(), e.getValue()).ifPresent(out::add);
        out.sort(Comparator.comparing(GameRuleInfo::id));
        return out;
    }

    /** One rule by stable id, vanilla id or 1.20.6 name. */
    public Optional<GameRuleInfo> rule(Object world, String anyId) {
        String vanilla = vanillaIdOf(anyId);
        Object rule = vanilla == null ? null : rules().get(vanilla);
        return rule == null ? Optional.empty() : info(world, vanilla, rule);
    }

    /** The server's id for a stable id, vanilla id or 1.20.6 name, or null when this server has no such rule. */
    public String vanillaIdOf(String anyId) {
        if (anyId == null) return null;
        String s = anyId.trim();
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        Map<String, Object> all = rules();
        if (all.containsKey(s)) return s;
        Optional<GameRuleCatalog.Entry> entry = GameRuleCatalog.resolve(s);
        if (entry.isPresent()) {
            if (all.containsKey(entry.get().id())) return entry.get().id();
            String legacy = entry.get().legacyName();
            if (legacy != null && all.containsKey(legacy)) return legacy;
        }
        for (String id : all.keySet()) if (GameRuleCatalog.snake(id).equals(s.toLowerCase(Locale.ROOT))) return id;
        return null;
    }

    private Optional<GameRuleInfo> info(Object world, String vanilla, Object rule) {
        try {
            Class<?> t = (Class<?>) type.invoke(rule);
            GameRuleKind kind = t == Boolean.class || t == boolean.class ? GameRuleKind.BOOLEAN : t == Integer.class || t == int.class ? GameRuleKind.INTEGER : null;
            if (kind == null) return Optional.empty();
            Optional<GameRuleCatalog.Entry> entry = GameRuleCatalog.resolve(vanilla);
            boolean legacyMatch = entry.isPresent() && vanilla.equalsIgnoreCase(entry.get().legacyName()) && !vanilla.equals(entry.get().id());
            boolean inverted = legacyMatch && entry.get().invertedLegacy() && kind == GameRuleKind.BOOLEAN;
            String id = entry.map(GameRuleCatalog.Entry::id).orElseGet(() -> GameRuleCatalog.snake(vanilla));
            int min = 0, max = 1;
            if (kind == GameRuleKind.INTEGER) {
                min = entry.filter(e -> e.kind() == GameRuleKind.INTEGER || e.legacyKind() == GameRuleKind.INTEGER).map(GameRuleCatalog.Entry::min).orElse(0);
                max = entry.filter(e -> e.kind() == GameRuleKind.INTEGER || e.legacyKind() == GameRuleKind.INTEGER).map(GameRuleCatalog.Entry::max).orElse(Integer.MAX_VALUE);
            }
            Object value = stable(getValue.invoke(world, rule), inverted);
            Object def = getDefault == null ? null : stable(invokeQuietly(getDefault, world, rule), inverted);
            GameRuleCategory category = entry.map(GameRuleCatalog.Entry::category).orElse(GameRuleCategory.OTHER);
            String legacy = entry.map(GameRuleCatalog.Entry::legacyName).orElse(null);
            return Optional.of(new GameRuleInfo(id, vanilla, legacy, kind, min, max, value, def, category, inverted, entry.isPresent()));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static Object invokeQuietly(Method m, Object target, Object... args) {
        try { return m.invoke(target, args); } catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }

    private static Object stable(Object v, boolean inverted) { return inverted && v instanceof Boolean b ? !b : v; }

    /**
     * Sets a rule to a value in the stable meaning ({@link Boolean} or {@link Integer}; a number for a switch, text
     * {@code true}/{@code false} or digits are accepted) and reads it back.
     */
    public GameRuleChange set(Object world, String anyId, Object value) {
        String vanilla = vanillaIdOf(anyId);
        String stableId = GameRuleCatalog.stableId(anyId == null ? "" : anyId);
        if (getValue == null) return GameRuleChange.failed(stableId, null, "unavailable");
        if (vanilla == null) return GameRuleChange.failed(stableId, null, "unknown-rule");
        Optional<GameRuleInfo> before = info(world, vanilla, rules().get(vanilla));
        if (before.isEmpty()) return GameRuleChange.failed(stableId, null, "unknown-rule");
        GameRuleInfo info = before.get();
        Object parsed = parse(info, value);
        if (parsed == null) return GameRuleChange.failed(info.id(), info.value(), "wrong-type");
        if (parsed instanceof Integer n && (n < info.min() || n > info.max())) return GameRuleChange.failed(info.id(), info.value(), "out-of-range");
        Object raw = info.inverted() && parsed instanceof Boolean b ? !b : parsed;
        boolean accepted;
        try {
            accepted = Boolean.TRUE.equals(setValue.invoke(world, rules().get(vanilla), raw));
        } catch (InvocationTargetException refused) {
            return GameRuleChange.failed(info.id(), info.value(), "refused");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return GameRuleChange.failed(info.id(), info.value(), "refused");
        }
        Object after = rule(world, vanilla).map(GameRuleInfo::value).orElse(null);
        boolean ok = accepted && parsed.equals(after);
        return new GameRuleChange(ok, info.id(), info.value(), after, ok ? null : "refused");
    }

    /** The value as the rule's kind, or null when it does not fit. */
    static Object parse(GameRuleInfo info, Object value) {
        if (value == null) return null;
        if (info.kind() == GameRuleKind.BOOLEAN) {
            if (value instanceof Boolean b) return b;
            String s = value.toString().trim().toLowerCase(Locale.ROOT);
            return s.equals("true") ? Boolean.TRUE : s.equals("false") ? Boolean.FALSE : null;
        }
        if (value instanceof Integer n) return n;
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) <= Integer.MAX_VALUE) return n.intValue();
        try { return Integer.parseInt(value.toString().trim()); } catch (NumberFormatException notNumber) { return null; }
    }
}
