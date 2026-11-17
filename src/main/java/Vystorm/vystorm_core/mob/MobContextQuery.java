package Vystorm.vystorm_core.mob;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Anfrage an Kontextprovider: wo steht welcher Mob. {@code mobId} darf bei Vorschau-Anfragen fehlen. */
public record MobContextQuery(UUID worldId, int x, int y, int z, UUID mobId, String definitionId, String faction, Set<String> tags) {
    public MobContextQuery {
        Objects.requireNonNull(worldId, "worldId");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
