package Vystorm.vystorm_core._advancedMethods.inventoryInteractor;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryDragEvent;

public interface InteractorEntry {
    String getNBT();

    void leftClick(Player player, Event event, String value);

    void rightClick(Player player, Event event, String value);

    void shiftLeftClick(Player player, Event event, String value);

    void shiftRightClick(Player player, Event event, String value);

    void middleClick(Player player, Event event, String value);

    void dropClick(Player player, Event event);

    void dragEventOld(InventoryDragEvent event, InteractorEntry entryDrag);

    void dragEventNew(InventoryDragEvent event);

    /**
     * Returns the priority of this InteractorEntry for execution ordering.
     * Higher priority entries are executed first.
     * Default priority is 10.
     * @return Priority value (higher = executed first)
     */
    default int priority() {
        return 10;
    }

    /**
     * Returns whether this entry should block other entries with lower priority
     * from being executed.
     * @return true if this entry blocks lower priority entries, false otherwise
     */
    default boolean blocksOthers() {
        return false;
    }

    /**
     * Called before any click/drag operations to allow setup or filtering.
     * Return false to prevent further processing.
     * @param player The player interacting
     * @return true to continue processing, false to skip
     */
    default boolean canProcess(Player player) {
        return true;
    }
}
