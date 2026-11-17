package Vystorm.vystorm_core.pack;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Zentrale Asset-Registry (Items §20): Plugins registrieren Pack-Module mit Dateien unter eigenen Namensräumen.
 * Globale IDs werden deterministisch vergeben – keine manuelle CustomModelData-Vergabe durch Plugins.
 * Item-Modelle laufen über die {@code item_model}-Komponente ({@link #itemModel}); für Altsysteme liefert
 * {@link #customModelData} eine stabile, kollisionsgeprüfte Zahl.
 */
public final class AssetRegistry {
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** Ein registriertes Modul: Besitzer, erlaubte Namensräume, Dateien (Pfad im Pack → Inhalt). */
    public record Module(String id, Plugin owner, List<String> namespaces, Map<String, byte[]> files, int priority) {}

    private final Map<String, Module> modules = new TreeMap<>();
    private final Map<String, String> namespaceOwner = new TreeMap<>();
    private final Map<Integer, String> modelDataOwner = new TreeMap<>();
    private final Map<String, Integer> modelData = new TreeMap<>();
    private long revision;

    /**
     * Registriert/ersetzt ein Pack-Modul. Dateien müssen unter {@code assets/<namespace>/} eines eigenen Namensraums
     * liegen (Ausnahme: {@code assets/minecraft/} nur für Overrides, niedrigste Priorität gewinnt nicht).
     */
    public synchronized void register(Plugin owner, String moduleId, List<String> namespaces, Map<String, byte[]> files, int priority) {
        Objects.requireNonNull(owner, "owner");
        String id = moduleId.toLowerCase(Locale.ROOT);
        for (String namespace : namespaces) {
            if (!NAMESPACE.matcher(namespace).matches() || namespace.equals("minecraft"))
                throw new IllegalArgumentException("Invalid asset namespace: " + namespace);
            String current = namespaceOwner.get(namespace);
            if (current != null && !current.equals(id)) throw new IllegalStateException("Namespace " + namespace + " already belongs to module " + current);
        }
        for (String path : files.keySet()) validatePath(path, namespaces);
        Module previous = modules.put(id, new Module(id, owner, List.copyOf(namespaces), Collections.unmodifiableMap(new TreeMap<>(files)), priority));
        if (previous != null) previous.namespaces().forEach(namespaceOwner::remove);
        for (String namespace : namespaces) namespaceOwner.put(namespace, id);
        revision++;
    }

    private static void validatePath(String path, List<String> namespaces) {
        if (!PATH.matcher(path).matches() || path.contains("..")) throw new IllegalArgumentException("Invalid pack path: " + path);
        if (!path.startsWith("assets/")) throw new IllegalArgumentException("Pack files must be under assets/: " + path);
        String rest = path.substring(7);
        int slash = rest.indexOf('/');
        String namespace = slash < 0 ? rest : rest.substring(0, slash);
        if (!namespace.equals("minecraft") && !namespaces.contains(namespace))
            throw new IllegalArgumentException("Path " + path + " is outside the registered namespaces " + namespaces);
    }

    public synchronized void unregisterAll(Plugin owner) {
        modules.values().removeIf(module -> {
            if (module.owner() != owner) return false;
            module.namespaces().forEach(namespaceOwner::remove);
            return true;
        });
        revision++;
    }

    public synchronized List<Module> modules() { return List.copyOf(modules.values()); }

    public synchronized boolean hasModule(String id) { return modules.containsKey(id.toLowerCase(Locale.ROOT)); }

    public synchronized long revision() { return revision; }

    /** Item-Modell-Schlüssel für die {@code item_model}-Komponente, z. B. {@code vystorm:item/swords/iron_01}. */
    public static NamespacedKey itemModel(String assetKey) {
        NamespacedKey key = NamespacedKey.fromString(assetKey.toLowerCase(Locale.ROOT));
        if (key == null) throw new IllegalArgumentException("Invalid asset key: " + assetKey);
        return key;
    }

    /**
     * Stabile CustomModelData-Zahl für einen Asset-Schlüssel (deterministischer Hash im Bereich 100000–9999999,
     * lineares Sondieren bei Kollision; die Zuordnung bleibt für die Laufzeit stabil).
     */
    public synchronized int customModelData(String assetKey) {
        String key = assetKey.toLowerCase(Locale.ROOT);
        Integer known = modelData.get(key);
        if (known != null) return known;
        int value = 100_000 + Math.floorMod(key.hashCode(), 9_900_000);
        while (modelDataOwner.containsKey(value) && !modelDataOwner.get(value).equals(key)) value = value >= 9_999_999 ? 100_000 : value + 1;
        modelDataOwner.put(value, key);
        modelData.put(key, value);
        return value;
    }

    public synchronized Optional<String> assetForModelData(int value) { return Optional.ofNullable(modelDataOwner.get(value)); }
}
