package Vystorm.vystorm_core.worlds.internal;

import Vystorm.vystorm_core.worlds.*;
import java.util.*;
import org.bukkit.entity.Player;

/** Server-thread visibility state; restores both directions when a visit ends. */
final class VisitVisibility {
    private final VisitEnv env;
    private final WorldLookup worlds;
    private final Map<UUID, VisitImpl> visits;
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    VisitVisibility(VisitEnv env, WorldLookup worlds, Map<UUID, VisitImpl> visits) {
        this.env = env;this.worlds = worlds;this.visits = visits;
    }

    public void refreshVisibility(String worldId) {
        ManagedWorldImpl world = worlds.byId(worldId);
        if (world == null) return;
        List<Player> inside = new ArrayList<>();
        for (VisitImpl visit : visits.values()) {
            if (!visit.worldId().equals(worldId) || visit.phase != Visit.Phase.ACTIVE) continue;
            Player p = env.player(visit.player());
            if (p != null) inside.add(p);
        }
        for (Player viewer : inside) {
            Set<UUID> hiddenByViewer = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            for (Player other : inside) {
                if (viewer == other) continue;
                boolean see = VisibilityRules.visible(world.spec().visibility(), viewer.getUniqueId(), other.getUniqueId(), env::sameParty);
                if (see && hiddenByViewer.remove(other.getUniqueId())) env.show(viewer, other);
                else if (!see && hiddenByViewer.add(other.getUniqueId())) env.hide(viewer, other);
            }
        }
    }

    public void refreshAllVisibility() {
        for (ManagedWorldImpl world : worlds.all()) if (world.spec().visibility() != Visibility.ALL) refreshVisibility(world.id());
    }

    /** Beim Ende eines Besuchs: in beide Richtungen wieder sichtbar. */
    void showAll(Player player) {
        UUID id = player.getUniqueId();
        Set<UUID> mine = hidden.remove(id);
        if (mine != null) for (UUID other : mine) { Player p = env.player(other);if (p != null) env.show(player, p); }
        for (Map.Entry<UUID, Set<UUID>> entry : hidden.entrySet()) {
            if (!entry.getValue().remove(id)) continue;
            Player viewer = env.player(entry.getKey());
            if (viewer != null) env.show(viewer, player);
        }
    }

}
