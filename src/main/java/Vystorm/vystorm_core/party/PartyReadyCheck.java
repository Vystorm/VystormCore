package Vystorm.vystorm_core.party;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable, advisory ready-check snapshot. Expired/changed rosters never authorize a teleport or event. */
public record PartyReadyCheck(UUID id, UUID partyId, long expiresAt, Set<UUID> members, Map<UUID, Boolean> answers) {
    public PartyReadyCheck { members = Set.copyOf(members); answers = Map.copyOf(answers); }
    public boolean allReady(long now) {
        return now < expiresAt && !members.isEmpty() && members.stream().allMatch(id -> Boolean.TRUE.equals(answers.get(id)));
    }
}
