package Vystorm.vystorm_core.mob.extension;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Ein Ziel einer Fähigkeit: Entity (mit Position) oder reine Position. */
public record SkillTarget(Entity entity, Location location) {
    public static SkillTarget of(Entity entity) { return new SkillTarget(entity, entity.getLocation()); }

    public static SkillTarget at(Location location) { return new SkillTarget(null, location.clone()); }

    public Optional<Entity> asEntity() { return Optional.ofNullable(entity); }

    public Location position() { return entity != null ? entity.getLocation() : location.clone(); }
}
