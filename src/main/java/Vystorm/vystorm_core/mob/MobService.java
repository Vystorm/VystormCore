package Vystorm.vystorm_core.mob;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;

/**
 * Öffentlicher Mob-Service. Einzige Integrationsschicht für Spawn Director, Arena, Cities, Factions, Events und
 * Dungeons. Implementiert von Vystorm-Mobs; Zugriff über {@link MobServices}. Spawn-Methoden nur auf dem Server-Thread.
 */
public interface MobService {
    /** Spawnt einen Mob; wirft {@link IllegalArgumentException} bei unbekannter Definition, liefert leer bei Abbruch. */
    Optional<MobHandle> spawn(MobSpawnRequest request);

    Optional<MobGroupHandle> spawnGroup(MobGroupRequest request);

    Optional<MobHandle> getMob(UUID mobId);

    Optional<MobHandle> byEntity(Entity entity);

    Collection<MobHandle> getMobs(MobQuery query);

    Optional<MobDefinitionView> getDefinition(String id);

    Collection<MobDefinitionView> getDefinitions(MobQuery query);

    ThreatQuote quoteThreat(MobSpawnRequest request);

    Optional<MobPreview> preview(MobSpawnRequest request);

    void despawn(UUID mobId, DespawnReason reason);

    /** Relations-Referenz für eine verwaltete Entity; leer bei fremden Entities. */
    Optional<EntityRef> describe(Entity entity);

    Optional<MobGroupHandle> group(UUID groupId);

    /** Natürliche Spawnregeln aller Definitionen, die in der offenen Welt spawnen sollen (seit 0.15.0). */
    default Collection<MobSpawnRule> spawnRules() { return java.util.List.of(); }

    /** Ändert sich bei jedem Neuladen der Spawnregeln; Planer bauen ihren Katalog dann neu (seit 0.15.0). */
    default long spawnRulesVersion() { return 0L; }
}
