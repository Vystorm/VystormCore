package Vystorm.vystorm_core.mob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobGroupHandle {
    UUID groupId();

    List<MobHandle> members();

    Optional<MobHandle> leader();

    /** Squad-Befehl (FOCUS_TARGET, SPREAD_OUT, RETREAT, FLANK, DEFEND_POSITION, PUSH, HOLD, BREACH). */
    void order(String order, Objective objective);

    double morale();
}
