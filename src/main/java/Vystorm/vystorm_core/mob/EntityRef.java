package Vystorm.vystorm_core.mob;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

/**
 * Neutrale Referenz auf eine Entity für Relation- und Targeting-Abfragen. Enthält nur Daten, die ohne
 * Weltzugriff ausgewertet werden können (Fraktion, Team, Owner, Tags).
 */
public record EntityRef(UUID id, Kind kind, String faction, String team, UUID owner, Set<String> tags) {
    public enum Kind { PLAYER, MOB, NPC, PET, VANILLA_HOSTILE, VANILLA_PASSIVE, OTHER }

    public EntityRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        faction = faction == null || faction.isBlank() ? null : faction;
        team = team == null || team.isBlank() ? null : team;
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public static EntityRef player(UUID id) { return new EntityRef(id, Kind.PLAYER, null, null, null, Set.of()); }

    public static EntityRef mob(UUID id, String faction, String team, UUID owner, Set<String> tags) {
        return new EntityRef(id, Kind.MOB, faction, team, owner, tags);
    }

    /**
     * Beschreibt eine beliebige Bukkit-Entity. Vystorm-Mobs werden über den registrierten {@link MobService}
     * aufgelöst (Fraktion/Team), sonst grob nach Vanilla-Kategorie. Nur auf dem Server-Thread aufrufen.
     */
    public static EntityRef of(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof Player player) return player(player.getUniqueId());
        Optional<EntityRef> managed = MobServices.service().flatMap(service -> service.describe(entity));
        if (managed.isPresent()) return managed.get();
        UUID owner = entity instanceof Tameable tameable && Vystorm.vystorm_core.compat.GameplayCompat.getOwnerUniqueId(tameable) != null ? Vystorm.vystorm_core.compat.GameplayCompat.getOwnerUniqueId(tameable) : null;
        if (owner != null) return new EntityRef(entity.getUniqueId(), Kind.PET, null, null, owner, Set.of());
        Kind kind = entity instanceof Monster ? Kind.VANILLA_HOSTILE : Kind.VANILLA_PASSIVE;
        return new EntityRef(entity.getUniqueId(), kind, null, null, null, Set.of());
    }

    public boolean hasTag(String tag) { return tags.contains(tag); }
}
