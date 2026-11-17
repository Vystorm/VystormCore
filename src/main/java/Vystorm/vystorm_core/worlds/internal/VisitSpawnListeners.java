package Vystorm.vystorm_core.worlds.internal;


import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/** Keep the newer event type out of the always-registered world protection listener. */
final class VisitSpawnListeners {
    private VisitSpawnListeners() {}

    static Listener create(VisitEngine visits) {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent", false,
                    VisitSpawnListeners.class.getClassLoader());
            return new ModernVisitSpawnListener(visits);
        } catch (ClassNotFoundException | LinkageError legacyServer) {
            if (Vystorm.vystorm_core.compat.ServerCompat.hasClass("org.spigotmc.event.player.PlayerSpawnLocationEvent"))
                return new Legacy(visits);
            // Plain CraftBukkit has no pre-spawn override event. WorldsListener.onJoin already performs
            // the durable recovery and eviction at LOWEST priority, including teleport-failure retention.
            return null;
        }
    }

    @SuppressWarnings("removal")
    static final class Legacy implements Listener {
        private final VisitEngine visits;
        Legacy(VisitEngine visits) { this.visits = visits; }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawnLocation(PlayerSpawnLocationEvent event) {
            var fixed = visits.spawnLocation(event.getPlayer().getUniqueId(), event.getSpawnLocation());
            if (fixed != null) event.setSpawnLocation(fixed);
        }
    }
}
