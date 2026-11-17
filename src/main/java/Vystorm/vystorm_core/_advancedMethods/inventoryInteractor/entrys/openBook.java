package Vystorm.vystorm_core._advancedMethods.inventoryInteractor.entrys;

import Vystorm.vystorm_core._advancedMethods.inventoryInteractor.InteractorEntry;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;
import java.util.function.Function;

/** Opens the clicked written book, or resolves the tag value through a supplied book lookup. */
public class openBook implements InteractorEntry {
    private final Function<String, ItemStack> lookup;
    public openBook() { this(name -> null); }
    public openBook(Function<String, ItemStack> lookup) { this.lookup = Objects.requireNonNull(lookup); }
    @Override public String getNBT() { return "openBook"; }
    @Override public void leftClick(Player player, Event event, String value) {
        if (!(event instanceof InventoryClickEvent click)) return;
        click.setCancelled(true);
        ItemStack book = lookup.apply(value);
        if (book == null) book = click.getCurrentItem();
        if (book == null || book.getType() != Material.WRITTEN_BOOK || !book.hasItemMeta()) {
            DebugMessages.mid("[openBook] Book opening skipped: no written book available");
            return;
        }
        ItemStack snapshot = book.clone();
        Bukkit.getScheduler().runTask(main.getPlugin(), () -> {
            if (!player.isOnline()) return;
            if (value != null && !value.isBlank() && !player.hasPermission("vcore.bookgui." + value)) {
                DebugMessages.mid("[openBook] Book opening denied by permission");
                return;
            }
            player.openBook(snapshot);
            DebugMessages.low("[openBook] Written book opened");
        });
    }
    @Override public void rightClick(Player player, Event event, String value) { leftClick(player, event, value); }
    @Override public void shiftLeftClick(Player player, Event event, String value) { cancel(event); }
    @Override public void shiftRightClick(Player player, Event event, String value) { cancel(event); }
    @Override public void middleClick(Player player, Event event, String value) { cancel(event); }
    @Override public void dropClick(Player player, Event event) { cancel(event); }
    @Override public void dragEventOld(InventoryDragEvent event, InteractorEntry other) { cancel(event); }
    @Override public void dragEventNew(InventoryDragEvent event) { cancel(event); }
    private void cancel(Event event) { if (event instanceof Cancellable cancellable) cancellable.setCancelled(true); }
}
