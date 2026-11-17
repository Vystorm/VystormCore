package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

public class PriorityHandler implements PriorityInterface{
    private NBTInterface nbt;

    private void dispatchLog(ArrayList<NBTEntity> actions, int line) {
        if (!actions.isEmpty()) {
            DebugMessages.low("[PriorityHandler " + line + "] Dispatching NBT actions; count=" + actions.size());
        }
    }

    private void loadNbt() {
        nbt = main.getApi().nbt();
    }

    private ArrayList<NBTEntity> sorter(HashMap<String, String> first, HashMap<String, String> second) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (first != null) keys.addAll(first.keySet());
        if (second != null) keys.addAll(second.keySet());
        return sorter(keys);
    }

    private ArrayList<NBTEntity> sorter(Iterable<String> keys) {
        LinkedHashSet<NBTEntity> matched = new LinkedHashSet<>();
        for (String key : keys) {
            NBTEntity action = nbt.getEntityNBT(key);
            if (action != null) matched.add(action);
        }
        ArrayList<NBTEntity> sorted = new ArrayList<>(matched);
        sorted.sort((first, second) -> Integer.compare(second.priority(), first.priority()));
        return sorted;
    }

    @Override
    public void blockBreakEvent(BlockBreakEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getBlock());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 49);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runBlock(event.getBlock());
                if (nbtEntity.cancelsEvent()) {
                    event.setCancelled(true);
                }
            }
            nbtEntity.blockBreakEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }

    }

    @Override
    public void blockPlaceEvent(BlockPlaceEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getBlock());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 73);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runBlock(event.getBlock());
                if (nbtEntity.cancelsEvent()) {
                    event.setCancelled(true);
                }
            }
            nbtEntity.blockPlaceEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void entityDamageListener(EntityDamageEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getEntity());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getDamageSource().getCausingEntity());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 96);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runEntity(event.getEntity());
                if (nbtEntity.cancelsEvent()) {
                    event.setCancelled(true);
                }
            }
            nbtEntity.entityDamageListener(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void entityDeathListener(EntityDeathEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getEntity());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getDamageSource().getCausingEntity());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 118);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runEntity(event.getEntity());
                if (event.getDamageSource().getCausingEntity() != null) {
                    nbtEntity.runEntity(event.getDamageSource().getCausingEntity());
                }
            }
            nbtEntity.entityDeathListener(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void itemUseEvent(PlayerInteractEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getItem());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 140);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runItem(event.getItem());
                if (nbtEntity.cancelsEvent()) {
                    event.setCancelled(true);
                }
            }
            nbtEntity.itemUseEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void changeItemEvent(PlayerItemHeldEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getPlayer().getInventory().getItem(event.getNewSlot()));
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 163);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runItem(event.getPlayer().getInventory().getItem(event.getNewSlot()));
            }
            nbtEntity.changeItemEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void itemDamageEvent(PlayerItemDamageEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getItem());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 183);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runItem(event.getItem());
                if (nbtEntity.cancelsEvent()) {
                    event.setCancelled(true);
                }
            }
            nbtEntity.itemDamageEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void interactAtEntityEvent(PlayerInteractAtEntityEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getRightClicked());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 206);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runEntity(event.getRightClicked());
            }
            nbtEntity.interactAtEntityEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }

    @Override
    public void playerHarvestBlockEvent(PlayerHarvestBlockEvent event) {
        loadNbt();
        HashMap<String, String> keys = nbt.getAllNBT(event.getPlayer());
        HashMap<String, String> keys2 = nbt.getAllNBT(event.getHarvestedBlock());
        ArrayList<NBTEntity> nbts = sorter(keys, keys2);
        dispatchLog(nbts, 226);
        for (NBTEntity nbtEntity : nbts) {
            if (!nbtEntity.requiresEvent()) {
                nbtEntity.runPlayer(event.getPlayer());
                nbtEntity.runBlock(event.getHarvestedBlock());
            }
            nbtEntity.playerHarvestBlockEvent(event);
            if (nbtEntity.blocksEvent()) {
                DebugMessages.low("[PriorityHandler] Lower-priority event actions blocked");
                break;
            }
        }
    }


    @Override
    public void run(ArrayList<String> keys, Player player) {
        loadNbt();
        ArrayList<NBTEntity> nbts = sorter(keys);
        dispatchLog(nbts, 245);
        for (NBTEntity nbtEntity : nbts) {
            nbtEntity.runPlayer(player);
        }
    }

    @Override
    public void run(ArrayList<String> keys, Block block) {
        loadNbt();
        ArrayList<NBTEntity> nbts = sorter(keys);
        dispatchLog(nbts, 255);
        for (NBTEntity nbtEntity : nbts) {
            nbtEntity.runBlock(block);
        }
    }

    @Override
    public void run(ArrayList<String> keys, Entity entity) {
        loadNbt();
        ArrayList<NBTEntity> nbts = sorter(keys);
        dispatchLog(nbts, 265);
        for (NBTEntity nbtEntity : nbts) {
            nbtEntity.runEntity(entity);
        }
    }
}
