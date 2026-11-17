package Vystorm.vystorm_core.mob;

import java.util.List;

/**
 * Gruppen-Spawn: Mitglieder plus Formation. {@code leaderIndex < 0} = Mob-System wählt den Anführer
 * (COMMANDER-Rolle bzw. höchster Threat).
 */
public record MobGroupRequest(List<MobSpawnRequest> members, String formation, int leaderIndex, List<Objective> objectives) {
    public MobGroupRequest {
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("group without members");
        members = List.copyOf(members);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        formation = formation == null || formation.isBlank() ? "LOOSE" : formation;
    }
}
