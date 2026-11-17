package Vystorm.vystorm_core.screen.internal;

import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Spawns the per-viewer entities of a screen (since 0.57.0). Every entity is non-persistent, hidden by default, shown
 * only to the viewer and tagged {@code vystorm_core:screen} so leftovers can be removed after a crash.
 */
public interface ScreenEntities {
    Entity mount(Player viewer, Location at);
    TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup);
    org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup);
    Interaction interaction(Player viewer, Location at, float width, float height);
    void remove(Entity entity);

    static NamespacedKey tag(Plugin core) { return NamespacedKey.fromString("vystorm_core:screen"); }

    /** Live implementation on a real server. */
    final class Bukkit implements ScreenEntities {
        private final Plugin core;
        private final NamespacedKey tag;

        public Bukkit(Plugin core) { this.core = core; this.tag = tag(core); }

        private <T extends Entity> void prepare(T entity, Player viewer) {
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            entity.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        }

        @Override public Entity mount(Player viewer, Location at) {
            BlockDisplay mount = at.getWorld().spawn(at, BlockDisplay.class, e -> { prepare(e, viewer); e.setBlock(Material.AIR.createBlockData()); });
            viewer.showEntity(core, mount);
            return mount;
        }

        @Override public TextDisplay text(Player viewer, Location at, Consumer<TextDisplay> setup) {
            TextDisplay text = at.getWorld().spawn(at, TextDisplay.class, e -> {
                prepare(e, viewer);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setShadowed(false);
                e.setDefaultBackground(false);
                setup.accept(e);
            });
            viewer.showEntity(core, text);
            return text;
        }

        @Override public org.bukkit.entity.ItemDisplay item(Player viewer, Location at, Consumer<org.bukkit.entity.ItemDisplay> setup) {
            org.bukkit.entity.ItemDisplay item = at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, e -> {
                prepare(e, viewer);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GUI);
                setup.accept(e);
            });
            viewer.showEntity(core, item);
            return item;
        }

        @Override public Interaction interaction(Player viewer, Location at, float width, float height) {
            Interaction box = at.getWorld().spawn(at, Interaction.class, e -> {
                prepare(e, viewer);
                e.setInteractionWidth(width);
                e.setInteractionHeight(height);
                e.setResponsive(false);
            });
            viewer.showEntity(core, box);
            return box;
        }

        @Override public void remove(Entity entity) { if (entity != null && entity.isValid()) entity.remove(); }
    }
}
