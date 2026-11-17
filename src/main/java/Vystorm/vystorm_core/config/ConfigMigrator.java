package Vystorm.vystorm_core.config;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Versionierte Config-Migration: jede Stufe hebt die Config um eine Version an. Vor einer Änderung wird eine Sicherung
 * {@code config.yml.v<alt>.bak} angelegt. Neue Default-Schlüssel werden ergänzt, ohne Anpassungen zu überschreiben.
 */
public final class ConfigMigrator {
    private final String versionKey;private final int current;
    private final TreeMap<Integer, Consumer<FileConfiguration>> steps = new TreeMap<>();

    public ConfigMigrator(String versionKey, int current) {
        if (current < 1) throw new IllegalArgumentException("version must be >= 1");
        this.versionKey = versionKey;this.current = current;
    }

    /** Stufe, die eine Config von {@code target-1} auf {@code target} hebt. */
    public ConfigMigrator step(int target, Consumer<FileConfiguration> migration) {
        if (target < 2 || target > current) throw new IllegalArgumentException("step target out of range: " + target);
        steps.put(target, migration);
        return this;
    }

    /** Migriert die Config im Speicher; liefert die Ausgangsversion. Fehlende Version zählt als 1. */
    public int migrate(FileConfiguration config) {
        int from = config.getInt(versionKey, 1);
        if (from > current) throw new IllegalStateException("Config version " + from + " is newer than supported " + current);
        if (from < current) {
            for (int target = from + 1; target <= current; target++) {
                Consumer<FileConfiguration> step = steps.get(target);
                // Fehlende Stufen sind erlaubt (Version ohne Strukturänderung), werden aber sichtbar gemacht.
                if (step == null) { DebugMessages.mid("[ConfigMigrator] No step for " + versionKey + " " + (target - 1) + " -> " + target + "; only defaults are added");continue; }
                step.accept(config);
            }
        }
        config.set(versionKey, current);
        return from;
    }

    /** Migriert die Plugin-Config, ergänzt Defaults und speichert (mit Sicherung), wenn sich etwas geändert hat. */
    public int migrate(JavaPlugin plugin) throws IOException { return migrate((org.bukkit.plugin.Plugin) plugin); }

    /** Also supports lifecycle-owned modules with isolated config/resources. */
    public int migrate(org.bukkit.plugin.Plugin plugin) throws IOException {
        plugin.saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        String before = config.saveToString();
        int from = migrate(config);
        config.options().copyDefaults(true);
        if (!config.saveToString().equals(before)) {
            File file = new File(plugin.getDataFolder(), "config.yml");
            if (file.exists()) Files.copy(file.toPath(), new File(plugin.getDataFolder(), "config.yml.v" + from + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.saveConfig();
        }
        return from;
    }
}
