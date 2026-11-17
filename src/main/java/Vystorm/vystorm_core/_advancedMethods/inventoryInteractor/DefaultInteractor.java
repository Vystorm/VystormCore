package Vystorm.vystorm_core._advancedMethods.inventoryInteractor;

import Vystorm.vystorm_core._coreMethods.eventRegulator.NBTInterface;
import Vystorm.vystorm_core.main;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;


public class DefaultInteractor implements InteractorInterface {
    private final HashMap<String, InteractorEntry> interactorEntries = new LinkedHashMap<>();
    private NBTInterface getNBTInterface() {
        return main.getApi().nbt();
    }

    private String entryKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    @Override
    public void register(InteractorEntry interactor) {
       if (interactorEntries.containsKey(entryKey(interactor.getNBT()))) {
           DebugMessages.mid("[DefaultInteractor] InteractorEntry already registered: " + interactor.getNBT());
           return;
       }
       interactorEntries.put(entryKey(interactor.getNBT()), interactor);
       DebugMessages.mid("[DefaultInteractor] Registered InteractorEntry: " + interactor.getNBT());
    }

    @Override
    public void unregister(InteractorEntry interactor) {
       if (!interactorEntries.containsKey(entryKey(interactor.getNBT()))) {
           DebugMessages.mid("[DefaultInteractor] InteractorEntry not registered: " + interactor.getNBT());
           return;
       }
       interactorEntries.remove(entryKey(interactor.getNBT()));
       DebugMessages.mid("[DefaultInteractor] Unregistered InteractorEntry: " + interactor.getNBT());
    }

    @Override
    public InteractorEntry getByName(String name) {
       return interactorEntries.get(entryKey(name));
    }

    private ArrayList<InteractorEntry> getPrioritySortedEntries(ArrayList<String> nbtKeys) {
       ArrayList<InteractorEntry> entries = new ArrayList<>();
       java.util.Set<String> keys = new java.util.HashSet<>();
       for (String key : nbtKeys) keys.add(entryKey(key));
       for (var registered : interactorEntries.entrySet()) {
           if (keys.contains(registered.getKey())) entries.add(registered.getValue());
       }
       entries.sort((o1, o2) -> Integer.compare(getPriority(o2), getPriority(o1)));
       return entries;
    }

    private int getPriority(InteractorEntry entry) {
       return entry.priority();
    }

    public ArrayList<InteractorEntry> getAllMatchingEntries(ItemStack item) {
       if (item == null || !item.hasItemMeta()) {
           return new ArrayList<>();
       }
       HashMap<String, String> nbtData = getNBTInterface().getAllNBT(item);
       ArrayList<String> keys = new ArrayList<>(nbtData.keySet());
       return getPrioritySortedEntries(keys);
    }

    @Override
    public InteractorEntry getNBTForMatch(ItemStack item) {
       ArrayList<InteractorEntry> entries = getAllMatchingEntries(item);
       return entries.isEmpty() ? null : entries.get(0);
    }
    public void handleEvent(InventoryClickEvent event) {
        handle(event);
    }

    @Override
    public ItemStack setNBTforItem(ItemStack item, String name, String value) {
       return getNBTInterface().setNBT(item, name, value);
    }

    @Override
    public String getNBTforItem(ItemStack itemInUse) {
       InteractorEntry entry = getNBTForMatch(itemInUse);
       return entry == null ? null : entryKey(entry.getNBT());
    }

    @Override
    public ArrayList<String> getAllNamespacedKeys(ItemStack itemInUse) {
       if (itemInUse == null || !itemInUse.hasItemMeta()) {
           return new ArrayList<>();
       }
       HashMap<String, String> nbtData = getNBTInterface().getAllNBT(itemInUse);
       return new ArrayList<>(nbtData.keySet());
    }

    @Override
    public String getValueForItem(ItemStack itemInHand, String key) {
       if (itemInHand == null) {
           return null;
       }
       return getNBTInterface().getNBT(itemInHand, key);
    }

    public boolean hasNBTTag(ItemStack item, String tag) {
       if (item == null) {
           return false;
       }
       return getNBTInterface().getNBT(item, tag) != null;
    }

    public void removeNBTTag(ItemStack item, String tag) {
       if (item != null) {
           getNBTInterface().removeNBT(item, tag);
       }
    }

    public void clearAllNBT(ItemStack item) {
       if (item != null) {
           getNBTInterface().clearNBT(item);
       }
    }

    private record DragEntry(InteractorEntry entry, boolean isOld) {}

    private ArrayList<DragEntry> getMergedDragEntries(ArrayList<InteractorEntry> oldEntries, ArrayList<InteractorEntry> newEntries) {
        ArrayList<DragEntry> merged = new ArrayList<>();
        for (InteractorEntry e : oldEntries) merged.add(new DragEntry(e, true));
        for (InteractorEntry e : newEntries) {
            if (!oldEntries.contains(e)) merged.add(new DragEntry(e, false));
        }
        merged.sort((a, b) -> Integer.compare(getPriority(b.entry()), getPriority(a.entry())));
        return merged;
    }

    @Override
    public void handleEvent(InventoryDragEvent event) {
        handle(event);
    }


    @Override
    public boolean handle(InventoryClickEvent event) {
       if (event.isCancelled()) return false;
       if (!(event.getWhoClicked() instanceof Player player)) {
           return false;
       }
       if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) {
           return false;
       }

       ArrayList<InteractorEntry> entries = getAllMatchingEntries(event.getCurrentItem());
       if (entries.isEmpty()) {
           return false;
       }

       boolean handled = false;
       for (InteractorEntry entry : entries) {
           if (!entry.canProcess(player)) {
               continue;
           }

           String value = getValueForItem(event.getCurrentItem(), entry.getNBT());

           DebugMessages.low("[DefaultInteractor] Dispatching prioritized click; type=" + event.getClick());
           boolean processed = true;
           switch (event.getClick()) {
               case LEFT -> entry.leftClick(player, event, value);
               case RIGHT -> entry.rightClick(player, event, value);
               case SHIFT_LEFT -> entry.shiftLeftClick(player, event, value);
               case SHIFT_RIGHT -> entry.shiftRightClick(player, event, value);
               case MIDDLE -> entry.middleClick(player, event, value);
               default -> processed = false;
           }
           handled |= processed;

           if (processed && entry.blocksOthers()) {
               break;
           }
       }
       return handled;
    }

    @Override
    public boolean handle(InventoryDragEvent event) {
       if (event.isCancelled()) return false;
       if (!(event.getWhoClicked() instanceof Player player)) {
           return false;
       }

       ArrayList<InteractorEntry> oldEntries = getAllMatchingEntries(event.getOldCursor());
       ArrayList<InteractorEntry> newEntries = getAllMatchingEntries(event.getCursor());

       boolean handled = false;
       for (DragEntry drag : getMergedDragEntries(oldEntries, newEntries)) {
           if (!drag.entry().canProcess(player)) {
               continue;
           }
           DebugMessages.low("[DefaultInteractor] Dispatching prioritized inventory drag");
           if (drag.isOld()) {
               InteractorEntry dragEntry = newEntries.isEmpty() ? null : newEntries.get(0);
               drag.entry().dragEventOld(event, dragEntry);
           } else {
               drag.entry().dragEventNew(event);
           }
           handled = true;

           if (drag.entry().blocksOthers()) {
               break;
           }
       }
       return handled;
    }

    @Override
    public boolean handle(PlayerDropItemEvent event) {
       if (event.isCancelled()) return false;
       Player player = event.getPlayer();
       ArrayList<InteractorEntry> entries = getAllMatchingEntries(event.getItemDrop().getItemStack());
       if (entries.isEmpty()) {
           return false;
       }

       boolean handled = false;
       for (InteractorEntry entry : entries) {
           if (!entry.canProcess(player)) {
               continue;
           }
           DebugMessages.low("[DefaultInteractor] Dispatching prioritized item drop");
           entry.dropClick(player, event);
           handled = true;

           if (entry.blocksOthers()) {
               break;
           }
       }
       return handled;
    }

    @Override
    public void handleEvent(PlayerDropItemEvent event) {
        handle(event);
    }
}


