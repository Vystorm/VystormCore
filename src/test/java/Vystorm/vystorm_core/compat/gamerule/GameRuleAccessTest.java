package Vystorm.vystorm_core.compat.gamerule;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.compat.gamerule.internal.GameRuleAccess;
import Vystorm.vystorm_core.compat.gamerule.internal.GameRuleService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The reflective access against stand-ins of the three shapes of {@code org.bukkit.GameRule}: a final class with
 * {@code values()} (Paper 1.20.6), an interface from a registry (Spigot 26.x) and an abstract keyed class from a
 * registry (Paper/Purpur 26.x). Nothing in the access is compiled against any of them.
 */
public class GameRuleAccessTest {
    // ---- shape 1: final class, getName, values() (1.20.6)
    public static final class FinalRule<T> {
        static final List<FinalRule<?>> ALL = List.of(new FinalRule<>("doInsomnia", Boolean.class), new FinalRule<>("disableRaids", Boolean.class),
                new FinalRule<>("snowAccumulationHeight", Integer.class), new FinalRule<>("doFireTick", Boolean.class), new FinalRule<>("someNewRule", Boolean.class));
        private final String name; private final Class<T> type;
        FinalRule(String name, Class<T> type) { this.name = name; this.type = type; }
        public String getName() { return name; }
        public Class<T> getType() { return type; }
        public static FinalRule<?>[] values() { return ALL.toArray(FinalRule[]::new); }
    }

    public static class FinalWorld {
        final Map<String, Object> values = new HashMap<>(Map.of("doInsomnia", true, "disableRaids", false, "snowAccumulationHeight", 1, "doFireTick", true, "someNewRule", false));
        public <T> T getGameRuleValue(FinalRule<T> rule) { return rule.getType().cast(values.get(rule.getName())); }
        public <T> T getGameRuleDefault(FinalRule<T> rule) { return rule.getType().cast(rule.getType() == Integer.class ? (Object) 1 : (Object) !rule.getName().startsWith("disable")); }
        public <T> boolean setGameRule(FinalRule<T> rule, T value) { values.put(rule.getName(), value); return true; }
    }

    // ---- key type shared by the 26.x shapes
    public static final class Key {
        private final String key;
        Key(String key) { this.key = key; }
        public String getKey() { return key; }
    }

    // ---- shape 2: interface from a registry (Spigot 26.x)
    public interface IfaceRule<T> {
        Key getKey();
        String getName();
        Class<T> getType();
    }

    record IfaceImpl<T>(String id, Class<T> type) implements IfaceRule<T> {
        public Key getKey() { return new Key(id); }
        public String getName() { return id; }
        public Class<T> getType() { return type; }
    }

    public static class IfaceWorld {
        final Map<String, Object> values = new HashMap<>(Map.of("spawn_phantoms", true, "raids", true, "max_minecart_speed", 8));
        public <T> T getGameRuleValue(IfaceRule<T> rule) { return rule.getType().cast(values.get(rule.getKey().getKey())); }
        public <T> boolean setGameRule(IfaceRule<T> rule, T value) { values.put(rule.getKey().getKey(), value); return true; }
    }

    // ---- shape 3: abstract keyed class from a registry (Paper/Purpur 26.x)
    public abstract static class AbstractRule<T> {
        public abstract Key getKey();
        public abstract String getName();
        public abstract Class<T> getType();
    }

    static final class AbstractImpl<T> extends AbstractRule<T> {
        private final String id; private final Class<T> type;
        AbstractImpl(String id, Class<T> type) { this.id = id; this.type = type; }
        public Key getKey() { return new Key(id); }
        public String getName() { return "legacy-name-not-used"; }
        public Class<T> getType() { return type; }
    }

    public static class AbstractWorld {
        final Map<String, Object> values = new HashMap<>(Map.of("fire_spread_radius_around_player", 128, "spawn_phantoms", true));
        public <T> T getGameRuleValue(AbstractRule<T> rule) { return rule.getType().cast(values.get(rule.getKey().getKey())); }
        public <T> T getGameRuleDefault(AbstractRule<T> rule) { return rule.getType().cast(rule.getType() == Integer.class ? (Object) 128 : (Object) true); }
        public <T> boolean setGameRule(AbstractRule<T> rule, T value) { values.put(rule.getKey().getKey(), value); return true; }
    }

    @Test
    void givenFinalClassShape_whenRead_thenRenamedAndInvertedRulesUseStableIds() throws Exception {
        GameRuleAccess access = GameRuleAccess.of(FinalRule.class, FinalWorld.class, null);
        assertEquals(GameRuleAccess.SHAPE_FINAL_CLASS, access.shape());
        FinalWorld world = new FinalWorld();
        Map<String, GameRuleInfo> rules = new HashMap<>();
        for (GameRuleInfo i : access.rules(world)) rules.put(i.id(), i);
        assertEquals(5, rules.size());
        GameRuleInfo phantoms = rules.get("spawn_phantoms");
        assertEquals("doInsomnia", phantoms.vanillaId());
        assertEquals(Boolean.TRUE, phantoms.value());
        GameRuleInfo raids = rules.get("raids");
        assertTrue(raids.inverted());
        assertEquals(Boolean.TRUE, raids.value(), "disableRaids=false means raids on");
        assertEquals(Boolean.TRUE, raids.defaultValue());
        assertEquals(Boolean.FALSE, raids.vanillaValue());
        assertEquals(GameRuleKind.BOOLEAN, rules.get("fire_spread_radius_around_player").kind(), "doFireTick is a switch on 1.20.6");
        assertFalse(rules.get("some_new_rule").known());
        assertEquals(GameRuleCategory.OTHER, rules.get("some_new_rule").category());

        GameRuleChange off = access.set(world, "raids", false);
        assertTrue(off.ok(), String.valueOf(off));
        assertEquals(Boolean.TRUE, world.values.get("disableRaids"), "stored inverted");
        assertEquals(Boolean.FALSE, off.after());

        assertEquals("out-of-range", access.set(world, "max_snow_accumulation_height", 9).reason());
        assertTrue(access.set(world, "snowAccumulationHeight", "8").ok());
        assertEquals(8, world.values.get("snowAccumulationHeight"));
        assertEquals("wrong-type", access.set(world, "spawn_phantoms", 3).reason());
        assertEquals("unknown-rule", access.set(world, "pvp", true).reason(), "1.20.6 has no pvp rule");
        assertEquals("/execute in minecraft:the_nether run gamerule disableRaids true",
                GameRuleService.command("minecraft:the_nether", access.rule(world, "raids").orElseThrow(), false));
    }

    @Test
    void givenInterfaceShape_whenReadFromRegistry_thenKeysAreTheIds() throws Exception {
        List<Object> registry = List.of(new IfaceImpl<>("spawn_phantoms", Boolean.class), new IfaceImpl<>("raids", Boolean.class),
                new IfaceImpl<>("max_minecart_speed", Integer.class));
        GameRuleAccess access = GameRuleAccess.of(IfaceRule.class, IfaceWorld.class, () -> registry);
        assertEquals(GameRuleAccess.SHAPE_INTERFACE, access.shape());
        IfaceWorld world = new IfaceWorld();
        GameRuleInfo raids = access.rule(world, "disableRaids").orElseThrow();
        assertEquals("raids", raids.vanillaId());
        assertFalse(raids.inverted(), "26.x raids is not inverted");
        assertNull(raids.defaultValue(), "no getGameRuleDefault on this world");
        GameRuleInfo cart = access.rule(world, "max_minecart_speed").orElseThrow();
        assertEquals(1, cart.min());
        assertEquals(1000, cart.max());
        assertEquals("out-of-range", access.set(world, "max_minecart_speed", 0).reason());
        assertTrue(access.set(world, "max_minecart_speed", 1000).ok());
        assertTrue(access.set(world, "doInsomnia", false).ok());
        assertEquals(Boolean.FALSE, world.values.get("spawn_phantoms"));
    }

    @Test
    void givenAbstractClassShape_whenRead_thenFireIsANumberWithMinusOne() throws Exception {
        List<Object> registry = List.of(new AbstractImpl<>("fire_spread_radius_around_player", Integer.class), new AbstractImpl<>("spawn_phantoms", Boolean.class));
        GameRuleAccess access = GameRuleAccess.of(AbstractRule.class, AbstractWorld.class, () -> registry);
        assertEquals(GameRuleAccess.SHAPE_ABSTRACT_CLASS, access.shape());
        AbstractWorld world = new AbstractWorld();
        GameRuleInfo fire = access.rule(world, "doFireTick").orElseThrow();
        assertEquals(GameRuleKind.INTEGER, fire.kind());
        assertEquals(-1, fire.min());
        assertEquals(128, fire.defaultValue());
        assertTrue(access.set(world, fire.id(), -1).ok());
        assertEquals("out-of-range", access.set(world, fire.id(), -2).reason());
        assertFalse(fire.changed());
    }

    @Test
    void givenNoApi_whenUnavailable_thenNothingAndAReason() {
        GameRuleAccess none = GameRuleAccess.unavailable();
        assertEquals(GameRuleAccess.UNAVAILABLE, none.shape());
        assertTrue(none.rules(new Object()).isEmpty());
        assertEquals("unavailable", none.set(new Object(), "pvp", true).reason());
    }
}
