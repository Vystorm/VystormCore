package Vystorm.vystorm_core.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.combat.CombatRegistry;
import Vystorm.vystorm_core.item.ItemProvider;
import Vystorm.vystorm_core.item.ItemRefs;
import Vystorm.vystorm_core.item.ItemServices;
import Vystorm.vystorm_core.mob.MobService;
import Vystorm.vystorm_core.mob.MobServices;
import Vystorm.vystorm_core.mob.extension.MobExtensions;
import Vystorm.vystorm_core.mob.extension.TriggerSink;
import Vystorm.vystorm_core.progression.DisciplineServices;
import Vystorm.vystorm_core.progression.DisciplineSource;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CoreLifecycleTest {
    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    @Test
    void givenProvidersOfDisabledPlugin_whenReleased_thenAllRegistriesForgetThem() {
        Plugin mobs = plugin("mobs");
        Plugin other = plugin("other");
        TriggerSink sink = mock(TriggerSink.class);
        MobServices.register(mobs, mock(MobService.class));
        ItemServices.register(mobs, mock(ItemProvider.class));
        ItemRefs.register(mobs, "lifecycle_test", (id, amount) -> Optional.empty());
        DisciplineServices.register(mobs, mock(DisciplineSource.class));
        MobExtensions.registerTrigger(mobs, "lifecycle_test_trigger");
        MobExtensions.sink(mobs, sink);
        CombatRegistry.register(other, new CombatRegistry.Policy() {
            public String id() { return "lifecycle-test-other"; }
            public int priority() { return 0; }
            public CombatRegistry.Decision evaluate(CombatRegistry.Context context) { return CombatRegistry.Decision.PASS; }
        });

        CoreLifecycle.releaseAll(mobs);

        assertFalse(MobServices.available());
        assertFalse(ItemServices.available());
        assertFalse(ItemRefs.namespaces().contains("lifecycle_test"));
        assertFalse(DisciplineServices.available());
        assertFalse(MobExtensions.triggerKnown("lifecycle_test_trigger"));
        assertFalse(MobExtensions.hasSink());
        MobExtensions.fireTrigger(UUID.randomUUID(), "x", null);
        verifyNoInteractions(sink);
        assertTrue(CombatRegistry.policies().contains("lifecycle-test-other"), "fremde Registrierungen bleiben");
        CoreLifecycle.releaseAll(other);
        assertFalse(CombatRegistry.policies().contains("lifecycle-test-other"));
    }

    @Test
    void givenSinkOfOtherOwner_whenPluginReleased_thenSinkStays() {
        Plugin owner = plugin("owner");
        TriggerSink sink = mock(TriggerSink.class);
        MobExtensions.sink(owner, sink);
        CoreLifecycle.releaseAll(plugin("unrelated"));
        assertTrue(MobExtensions.hasSink());
        UUID mob = UUID.randomUUID();
        MobExtensions.fireTrigger(mob, "Signal", null);
        verify(sink).fire(mob, "signal", null);
        MobExtensions.sink(owner, null);
        assertFalse(MobExtensions.hasSink());
    }

    @Test
    void givenBlankId_whenLookedUp_thenEmptyInsteadOfException() {
        assertTrue(MobExtensions.mechanic(" ").isEmpty());
        assertFalse(MobExtensions.triggerKnown(""));
    }
}
