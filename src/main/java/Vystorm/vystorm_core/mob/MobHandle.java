package Vystorm.vystorm_core.mob;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.LivingEntity;

/**
 * Stabile Außensicht auf einen laufenden Vystorm-Mob. {@link #id()} ist die Mob-ID (nicht zwingend die
 * Entity-UUID; nach Persistenz-Promotion überdauert sie Entity-Wechsel). Mutierende Methoden nur auf dem Server-Thread.
 */
public interface MobHandle {
    UUID id();

    String definitionId();

    Optional<LivingEntity> entity();

    boolean valid();

    int level();

    MobTier tier();

    Set<String> affixes();

    Optional<String> role();

    Optional<UUID> squadId();

    Optional<String> team();

    Optional<UUID> currentTarget();

    ThreatQuote threat();

    EntityRef ref();

    /** Aktuelle AI-Stufe (0 dormant … 3 combat), für Diagnose. */
    int aiTier();

    void setTarget(UUID target);

    void addObjective(Objective objective);

    void clearObjective(String objectiveId);

    /** Zusätzlicher Kontext für genau diesen Mob (z. B. Arena-Welle), ergänzt die Provider-Ebenen. */
    void pushContext(ContextLayer layer);

    void removeContext(String layerId);

    boolean persistent();

    /** Macht den Mob persistent (Nemesis-Hook) und liefert die dauerhafte ID. */
    UUID promoteToPersistent();

    void despawn(DespawnReason reason);
}
