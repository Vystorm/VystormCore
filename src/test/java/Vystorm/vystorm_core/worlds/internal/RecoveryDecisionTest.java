package Vystorm.vystorm_core.worlds.internal;

import static Vystorm.vystorm_core.worlds.internal.RecoveryDecision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Review C2: nur zurückspielen, wenn die geladenen Spielerdaten im Besuchsstand sind – sonst doppelte Items. */
class RecoveryDecisionTest {
    @Test
    void givenSnapshotAndVisitState_whenJoining_thenRestore() {
        assertEquals(RESTORE, decide(true, true, false, false, false), "marker in player data");
        assertEquals(RESTORE, decide(true, false, true, false, false), "visit tools (also legacy key)");
        assertEquals(RESTORE, decide(true, false, false, true, false), "standing in a managed world");
        assertEquals(RESTORE, decide(true, false, false, false, true), "redirected out of a managed world at login");
    }

    @Test
    void givenSnapshotButPlayerDataAlreadyOutside_whenJoining_thenDiscardWithoutRestoring() {
        assertEquals(DISCARD, decide(true, false, false, false, false));
    }

    @Test
    void givenVisitStateWithoutSnapshot_whenJoining_thenEvict() {
        assertEquals(EVICT, decide(false, true, false, false, false));
        assertEquals(EVICT, decide(false, false, true, false, false));
        assertEquals(EVICT, decide(false, false, false, true, false));
    }

    @Test
    void givenNormalPlayer_whenJoining_thenNothing() {
        assertEquals(NOTHING, decide(false, false, false, false, false));
        assertEquals(NOTHING, decide(false, false, false, false, true));
    }
}
