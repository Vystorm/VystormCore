package Vystorm.vystorm_core.worlds;

import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Werkzeuge, die ein Besucher statt seines Inventars bekommt (Slot 0–40 → Item). Core markiert jedes Item als
 * Besuchs-Werkzeug ({@link VisitService#isTool}) und als nicht handelbar ({@code item.ItemRestrictions}); es wird beim
 * Ende des Besuchs, beim Join und nach Absturz überall entfernt. Wird beim Betreten, nach dem Respawn und bei
 * {@link VisitService#regiveTools} aufgerufen (Server-Thread).
 *
 * @since 0.26.0
 */
@FunctionalInterface
public interface ToolKit {
    ToolKit NONE = player -> Map.of();

    Map<Integer, ItemStack> items(Player player);
}
