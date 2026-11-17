package Vystorm.vystorm_core.worlds;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class RestoreOutcomeTest {
    @Test
    void givenLegacyService_whenTypedRestoreAdded_thenDefaultMethodPreservesImplementors() throws Exception {
        Class<?> outcome = Class.forName("Vystorm.vystorm_core.worlds.RestoreOutcome");
        assertTrue(outcome.isEnum());
        var method = VisitService.class.getMethod("restoreOutcome", Player.class);
        assertTrue(method.isDefault());
        assertEquals(outcome, method.getReturnType());
        assertTrue(VisitService.class.getMethod("restore", Player.class).isAnnotationPresent(Deprecated.class));
    }
}
