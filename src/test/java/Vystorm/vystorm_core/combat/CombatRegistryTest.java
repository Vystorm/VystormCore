package Vystorm.vystorm_core.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CombatRegistryTest {
    private final Plugin owner = plugin();
    private final Player a = mock(Player.class), b = mock(Player.class);

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("owner");
        return plugin;
    }

    private static CombatRegistry.Policy policy(String id, int priority, CombatRegistry.Decision decision) {
        return new CombatRegistry.Policy() {
            public String id() { return id; }
            public int priority() { return priority; }
            public CombatRegistry.Decision evaluate(CombatRegistry.Context context) { return decision; }
        };
    }

    @AfterEach
    void clear() { CombatRegistry.clear(); }

    @Test
    void givenPolicies_whenEvaluated_thenHighestPriorityNonPassWins() {
        CombatRegistry.register(owner, policy("low", 1, CombatRegistry.Decision.ALLOW));
        CombatRegistry.register(owner, policy("high", 10, CombatRegistry.Decision.DENY));
        CombatRegistry.register(owner, policy("pass", 20, CombatRegistry.Decision.PASS));
        assertEquals(List.of("pass", "high", "low"), CombatRegistry.policies());
        assertEquals(CombatRegistry.Decision.DENY, CombatRegistry.evaluate(a, b));
        assertTrue(CombatRegistry.unregister(owner, "HIGH"));
        assertEquals(CombatRegistry.Decision.ALLOW, CombatRegistry.evaluate(a, b));
        CombatRegistry.unregisterAll(owner);
        assertEquals(CombatRegistry.Decision.PASS, CombatRegistry.evaluate(a, b));
    }

    @Test
    void givenFailingPolicy_whenEvaluated_thenSkipped() {
        CombatRegistry.register(owner, new CombatRegistry.Policy() {
            public String id() { return "broken"; }
            public int priority() { return 100; }
            public CombatRegistry.Decision evaluate(CombatRegistry.Context context) { throw new IllegalStateException("boom"); }
        });
        CombatRegistry.register(owner, policy("allow", 1, CombatRegistry.Decision.ALLOW));
        assertEquals(CombatRegistry.Decision.ALLOW, CombatRegistry.evaluate(a, b));
    }

    @Test
    void givenForeignOwner_whenSameIdRegistered_thenRejected() {
        CombatRegistry.register(owner, policy("x", 1, CombatRegistry.Decision.PASS));
        assertThrows(IllegalStateException.class, () -> CombatRegistry.register(plugin(), policy("X", 1, CombatRegistry.Decision.PASS)));
        assertFalse(CombatRegistry.unregister(plugin(), "x"));
    }

    @Test
    void givenKind_whenEvaluated_thenPolicySeesItAndTwoArgumentContextIsHarm() {
        CombatRegistry.register(owner, new CombatRegistry.Policy() {
            public String id() { return "team"; }
            public int priority() { return 5; }
            public CombatRegistry.Decision evaluate(CombatRegistry.Context context) {
                return context.harmful() ? CombatRegistry.Decision.DENY : CombatRegistry.Decision.PASS;
            }
        });
        assertEquals(CombatRegistry.Decision.DENY, CombatRegistry.evaluate(a, b));
        assertEquals(CombatRegistry.Decision.DENY, CombatRegistry.evaluate(a, b, CombatRegistry.Kind.HARM));
        assertEquals(CombatRegistry.Decision.PASS, CombatRegistry.evaluate(a, b, CombatRegistry.Kind.SUPPORT));
        assertEquals(CombatRegistry.Kind.HARM, new CombatRegistry.Context(a, b, null).kind());
    }

    @Test
    void givenEffectCategories_whenClassified_thenOnlyNonHarmfulIsSupport() {
        var beneficial = org.bukkit.potion.PotionEffectTypeCategory.BENEFICIAL;
        var harmful = org.bukkit.potion.PotionEffectTypeCategory.HARMFUL;
        var neutral = org.bukkit.potion.PotionEffectTypeCategory.NEUTRAL;
        assertEquals(CombatRegistry.Kind.SUPPORT, CombatRegistry.kindOfCategories(List.of(beneficial, neutral)));
        assertEquals(CombatRegistry.Kind.HARM, CombatRegistry.kindOfCategories(List.of(beneficial, harmful)));
        assertEquals(CombatRegistry.Kind.HARM, CombatRegistry.kindOfCategories(List.of()));
        assertEquals(CombatRegistry.Kind.HARM, CombatRegistry.kindOf(null));
    }
}
