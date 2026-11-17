package Vystorm.vystorm_core.worlds.internal;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.event.*;
    final class ModernVisitSpawnListener implements Listener {
        private final VisitEngine visits;
        ModernVisitSpawnListener(VisitEngine visits) { this.visits = visits; }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
            var fixed = visits.spawnLocation(event.getConnection().getProfile().getId(), event.getSpawnLocation());
            if (fixed != null) event.setSpawnLocation(fixed);
        }
    }

