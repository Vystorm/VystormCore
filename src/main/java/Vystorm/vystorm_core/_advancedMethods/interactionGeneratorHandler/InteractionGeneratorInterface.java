package Vystorm.vystorm_core._advancedMethods.interactionGeneratorHandler;

import org.bukkit.Location;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.UUID;
import java.util.function.Consumer;

public interface InteractionGeneratorInterface {
    Interaction spawn(Plugin owner, Location location, float width, float height,
                      Consumer<Player> rightClick, Consumer<Player> attack);
    Interaction get(UUID id);
    boolean remove(UUID id);
    boolean handle(UUID id, Player player, boolean attack);
    void removeAll(Plugin owner);
    void clear();
}
