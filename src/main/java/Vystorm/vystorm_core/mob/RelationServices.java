package Vystorm.vystorm_core.mob;

import Vystorm.vystorm_core.party.PartyServices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;

/**
 * Relation-Register: Plugins (Arena, Cities, Factions, Pets …) registrieren {@link RelationProvider}; der erste
 * Provider mit Aussage nach absteigender Priorität gewinnt. Ohne Aussage gilt eine neutrale Grundregel
 * (Party/Owner/Fraktion/Team verbündet, feindliche Mobs gegen Spieler/Pets/NPCs feindlich, Mob-Fraktionen untereinander neutral).
 * <p>Preferred access: {@code api.mobs().relations()}.
 */
public final class RelationServices {
    private record Registered(Plugin owner, RelationProvider provider) {}

    private static final Map<String, Registered> PROVIDERS = new ConcurrentHashMap<>();
    private static volatile List<Registered> ORDERED = List.of();
    private static final RelationService SERVICE = RelationServices::resolve;

    private RelationServices() {}

    public static RelationService get() { return SERVICE; }

    public static synchronized void register(Plugin owner, RelationProvider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        Registered previous = PROVIDERS.get(provider.id());
        if (previous != null && previous.owner() != owner) throw new IllegalStateException("Relation provider already registered: " + provider.id());
        PROVIDERS.put(provider.id(), new Registered(owner, provider));
        reorder();
    }

    public static synchronized void unregisterAll(Plugin owner) {
        PROVIDERS.values().removeIf(entry -> entry.owner() == owner);
        reorder();
    }

    public static List<String> providers() { return ORDERED.stream().map(entry -> entry.provider().id()).toList(); }

    private static void reorder() {
        List<Registered> list = new ArrayList<>(PROVIDERS.values());
        list.sort(Comparator.comparingInt((Registered entry) -> entry.provider().priority()).reversed());
        ORDERED = List.copyOf(list);
    }

    private static Relation resolve(EntityRef source, EntityRef target) {
        if (source.id().equals(target.id())) return Relation.ALLY;
        for (Registered entry : ORDERED) {
            if (!entry.owner().isEnabled()) continue;
            try {
                Optional<Relation> relation = entry.provider().relation(source, target);
                if (relation != null && relation.isPresent()) return relation.get();
            } catch (RuntimeException failure) {
                // defekter Provider darf das Targeting nicht blockieren
                Vystorm.vystorm_core.lifecycle.ProviderFailures.report("RelationServices", entry.provider().id(), "relation", failure);
            }
        }
        return fallback(source, target);
    }

    /** Grundregel ohne Provider-Aussage; öffentlich für Tests und Diagnose. */
    public static Relation fallback(EntityRef source, EntityRef target) {
        if (source.team() != null && source.team().equals(target.team())) return Relation.ALLY;
        // Explizite Teams (Arena, Events) gehen vor Fraktionen: verschiedene Teams sind Gegner
        if (source.team() != null && target.team() != null) return Relation.HOSTILE;
        if (source.owner() != null && source.owner().equals(target.id())) return Relation.ALLY;
        if (target.owner() != null && target.owner().equals(source.id())) return Relation.ALLY;
        if (source.owner() != null && source.owner().equals(target.owner())) return Relation.ALLY;
        if (source.kind() == EntityRef.Kind.PLAYER && target.kind() == EntityRef.Kind.PLAYER)
            return safeSameParty(source, target) ? Relation.ALLY : Relation.NEUTRAL;
        if (source.faction() != null && source.faction().equals(target.faction())) return Relation.ALLY;
        boolean sourceHostile = isHostileMob(source);
        boolean targetHostile = isHostileMob(target);
        if (sourceHostile && (target.kind() == EntityRef.Kind.PLAYER || target.kind() == EntityRef.Kind.PET || target.kind() == EntityRef.Kind.NPC)) return Relation.HOSTILE;
        if (targetHostile && (source.kind() == EntityRef.Kind.PLAYER || source.kind() == EntityRef.Kind.PET || source.kind() == EntityRef.Kind.NPC)) return Relation.HOSTILE;
        // Feindschaft zwischen Mob-Fraktionen wird nie angenommen, sondern von Providern deklariert (Factions, relations.yml)
        return Relation.NEUTRAL;
    }

    private static boolean isHostileMob(EntityRef ref) {
        return (ref.kind() == EntityRef.Kind.MOB && ref.owner() == null && !ref.hasTag("PASSIVE")) || ref.kind() == EntityRef.Kind.VANILLA_HOSTILE;
    }

    private static boolean safeSameParty(EntityRef a, EntityRef b) {
        try { return PartyServices.sameParty(a.id(), b.id()); } catch (RuntimeException | LinkageError failure) { return false; }
    }
}
