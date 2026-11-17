package Vystorm.vystorm_core._coreMethods.eventRegulator;

import Vystorm.vystorm_core._coreMethods.dataStorage.dataType.DataEntry;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.main;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;

public class NBTHandler implements NBTInterface {
    private final Plugin plugin;
    private final String prefix;
    private final boolean itemsEnabled;
    private final boolean blocksEnabled;
    private final boolean entitiesEnabled;
    private final DataEntry blockStorage;
    private final HashMap<String, NBTEntity> actions = new HashMap<>();
    private final HashMap<Location, HashMap<String, String>> registeredBlocks = new HashMap<>();

    public NBTHandler() {
        this(main.getPlugin(), loadEntry("config.yml", true), loadEntry("registeredBlocks.yml", false));
    }

    NBTHandler(Plugin plugin, DataEntry settings, DataEntry blockStorage) {
        this.plugin = Objects.requireNonNull(plugin);
        this.blockStorage = Objects.requireNonNull(blockStorage);
        var configuration = settings.getEntry();
        String configured = configuration.getString("NBT_Tag", configuration.getString("NBT_TAG", "vcore_"));
        // Historical defaults contained brackets/capitals, which are not valid Bukkit keys.
        String normalized = configured.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "");
        prefix = normalized.isEmpty() ? "vcore_" : normalized;
        itemsEnabled = configuration.getBoolean("NBT_Items", true);
        blocksEnabled = configuration.getBoolean("NBT_Blocks", true);
        entitiesEnabled = configuration.getBoolean("NBT_Entities", true);
        DebugMessages.mid("[NBTHandler] Unified NBT service initialized; items=" + itemsEnabled
                + "; blocks=" + blocksEnabled + "; entities=" + entitiesEnabled);
    }

    @SuppressWarnings("deprecation")
    private static DataEntry loadEntry(String filename, boolean resource) {
        Plugin plugin = main.getPlugin();
        Path relative = Path.of("Nbt", filename);
        try {
            if (!main.getApi().data().loader().fileExists(plugin, relative)) {
                if (resource) plugin.saveResource("Nbt/" + filename, false);
                else main.getApi().data().creator().createFile(filename, plugin.getDataFolder().toPath().resolve("Nbt"));
            }
            main.getApi().data().loader().autoLoad(plugin, relative);
            return Objects.requireNonNull(main.getApi().data().dataEntry(plugin, "Nbt", null, filename));
        } catch (Exception failure) {
            DebugMessages.high("[NBTHandler] NBT storage initialization failed; file=" + filename);
            throw new IllegalStateException("Cannot load NBT storage: " + relative, failure);
        }
    }

    private NamespacedKey getKey(String key) {
        return new NamespacedKey(plugin, prefix + Objects.requireNonNull(key, "key").toLowerCase(Locale.ROOT));
    }

    private String normalizedKey(String key) {
        return getKey(key).getKey().substring(prefix.length());
    }

    private boolean owns(NamespacedKey key) {
        return key.getNamespace().equals(plugin.getName().toLowerCase(java.util.Locale.ROOT)) && key.getKey().startsWith(prefix);
    }

    @Override
    public ItemStack setNBT(ItemStack item, String key, String value) {
        if (!itemsEnabled) return item;
        return setTag(item, getKey(key), PersistentDataType.STRING, value);
    }

    @Override
    public String getNBT(ItemStack item, String key) {
        return itemsEnabled ? getTag(item, getKey(key), PersistentDataType.STRING) : null;
    }

    @Override
    public String removeNBT(ItemStack item, String key) {
        if (getNBT(item, key) == null) return null;
        setNBT(item, key, null);
        return key;
    }

    @Override
    public HashMap<String, String> getAllNBT(ItemStack item) {
        if (!itemsEnabled || item == null || !item.hasItemMeta()) return new HashMap<>();
        return all(item.getItemMeta().getPersistentDataContainer());
    }

    @Override
    public ItemStack clearNBT(ItemStack item) {
        if (!itemsEnabled || item == null || !item.hasItemMeta()) return item;
        ItemMeta meta = item.getItemMeta();
        clear(meta.getPersistentDataContainer());
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public Entity setNBT(Entity entity, String key, String value) {
        if (!entitiesEnabled) return entity;
        var data = Objects.requireNonNull(entity).getPersistentDataContainer();
        if (value == null) data.remove(getKey(key));
        else data.set(getKey(key), PersistentDataType.STRING, value);
        DebugMessages.low("[NBTHandler] Entity tag updated; key=" + normalizedKey(key));
        return entity;
    }

    @Override
    public void setNBT(Player player, String key, String value) { setNBT((Entity) player, key, value); }

    @Override
    public String getNBT(Entity entity, String key) {
        return !entitiesEnabled || entity == null ? null
                : entity.getPersistentDataContainer().get(getKey(key), PersistentDataType.STRING);
    }

    @Override
    public String getNBT(Player player, String key) { return getNBT((Entity) player, key); }

    @Override
    public String removeNBT(Entity entity, String key) {
        if (getNBT(entity, key) == null) return null;
        setNBT(entity, key, null);
        return key;
    }

    @Override
    public String removeNBT(Player player, String key) { return removeNBT((Entity) player, key); }

    @Override
    public HashMap<String, String> getAllNBT(Entity entity) {
        return !entitiesEnabled || entity == null ? new HashMap<>() : all(entity.getPersistentDataContainer());
    }

    @Override
    public HashMap<String, String> getAllNBT(Player player) { return getAllNBT((Entity) player); }

    @Override
    public Entity clearNBT(Entity entity) {
        if (entitiesEnabled && entity != null) clear(entity.getPersistentDataContainer());
        return entity;
    }

    private HashMap<String, String> all(PersistentDataContainer container) {
        HashMap<String, String> values = new HashMap<>();
        for (NamespacedKey key : container.getKeys()) {
            if (owns(key) && container.has(key, PersistentDataType.STRING)) {
                values.put(key.getKey().substring(prefix.length()), container.get(key, PersistentDataType.STRING));
            }
        }
        return values;
    }

    private void clear(PersistentDataContainer container) {
        for (NamespacedKey key : new ArrayList<>(container.getKeys())) {
            if (owns(key)) container.remove(key);
        }
        DebugMessages.low("[NBTHandler] Cleared tags owned by configured NBT namespace/prefix");
    }

    private String blockPath(Block block) {
        return "blocks." + block.getWorld().getUID() + "." + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    /** Lesender Zugriff: Blöcke ohne Tags werden nicht im Cache angelegt (sonst wächst er mit jedem Block-Event). */
    private HashMap<String, String> blockDataIfPresent(Block block) {
        HashMap<String, String> cached = registeredBlocks.get(block.getLocation());
        if (cached != null) return cached;
        HashMap<String, String> loaded = loadBlock(block, block.getLocation());
        if (loaded.isEmpty()) return loaded;
        registeredBlocks.put(block.getLocation(), loaded);
        return loaded;
    }

    private HashMap<String, String> blockData(Block block) {
        return registeredBlocks.computeIfAbsent(block.getLocation(), location -> loadBlock(block, location));
    }

    private HashMap<String, String> loadBlock(Block block, Location location) {
        {
            HashMap<String, String> values = new HashMap<>();
            var section = blockStorage.getSection(blockPath(block));
            if (section == null) section = blockStorage.getSection(location.toString());
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String value = section.getString(key);
                    if (value != null) values.put(normalizedKey(key), value);
                }
            }
            return values;
        }
    }

    private void saveBlock(Block block, HashMap<String, String> values) {
        blockStorage.getEntry().set(blockPath(block), values.isEmpty() ? null : values);
        if (values.isEmpty()) registeredBlocks.remove(block.getLocation());
        // Remove the old Location.toString-based representation once this block is updated.
        blockStorage.removeEntry(block.getLocation().toString());
        blockStorage.saveEntry();
        DebugMessages.low("[NBTHandler] Block tags saved; position=" + blockPath(block));
    }

    @Override
    public void setNBT(Block block, String key, String value) {
        if (!blocksEnabled) return;
        if (value == null) { removeNBT(block, key); return; }
        HashMap<String, String> values = blockData(block);
        values.put(normalizedKey(key), value);
        saveBlock(block, values);
    }

    @Override
    public String getNBT(Block block, String key) {
        return !blocksEnabled || block == null ? null : blockDataIfPresent(block).get(normalizedKey(key));
    }

    @Override
    public String removeNBT(Block block, String key) {
        if (!blocksEnabled || block == null) return null;
        var values = blockDataIfPresent(block);
        if (values.remove(normalizedKey(key)) == null) return null;
        saveBlock(block, values);
        return key;
    }

    @Override
    public HashMap<String, String> getAllNBT(Block block) {
        return !blocksEnabled || block == null ? new HashMap<>() : new HashMap<>(blockDataIfPresent(block));
    }

    @Override
    public Block clearNBT(Block block) {
        if (blocksEnabled && block != null) {
            saveBlock(block, new HashMap<>());
            registeredBlocks.remove(block.getLocation());
        }
        return block;
    }

    @Override
    public NBTEntity getEntityNBT(String key) { return actions.get(normalizedKey(key)); }

    @Override
    public NBTEntity setEntityNBT(NBTEntity entity, String key) {
        actions.put(normalizedKey(key), entity);
        DebugMessages.mid("[NBTHandler] NBT event action registered; key=" + normalizedKey(key));
        return entity;
    }

    @Override
    public HashMap<String, NBTEntity> getEntityNBTHashMap() { return actions; }
}
