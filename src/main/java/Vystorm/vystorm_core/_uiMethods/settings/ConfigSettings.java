package Vystorm.vystorm_core._uiMethods.settings;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds a SERVER settings section bound to keys of a plugin configuration. Values are written into the
 * configuration; after a save the file is written once and {@code reload} applies the new values.
 * <pre>
 * api.settings().register(this, ConfigSettings.of(this)
 *         .toggle("feedback.sound", "Sounds", "Ton beim Ernten")
 *         .integer("fields.max-size", "Max. Feldgröße", "Blöcke pro Feld", 16, 4096, 16)
 *         .section("server", "VystormFarms", "Felder und Ernte", "vystormfarms.admin", p -> reloadSettings()));
 * </pre>
 */
public final class ConfigSettings {
    private final Supplier<FileConfiguration> config;
    private final Runnable save;
    /** 0.66.1: the plugin whose config.yml {@link #of(Plugin)} saves; a broken file is then never saved over. */
    private org.bukkit.plugin.Plugin owner;
    private final List<Setting> settings = new ArrayList<>();

    private ConfigSettings(Supplier<FileConfiguration> config, Runnable save) {
        this.config = Objects.requireNonNull(config); this.save = Objects.requireNonNull(save);
    }

    /** Binds to the plugin's config.yml ({@code getConfig()} / {@code saveConfig()}). */
    public static ConfigSettings of(Plugin plugin) {
        ConfigSettings settings = new ConfigSettings(plugin::getConfig, plugin::saveConfig);
        settings.owner = plugin;
        return settings;
    }

    /** Binds to any configuration, e.g. a secondary YAML file, with its own save action. */
    public static ConfigSettings of(Supplier<FileConfiguration> config, Runnable save) { return new ConfigSettings(config, save); }

    private static Vystorm.vystorm_core.lang.Lang lang() { return Vystorm.vystorm_core.lang.Localization.get().core(); }

    public static String id(String path) { return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"); }

    public ConfigSettings toggle(String path, String label, String description) {
        settings.add(Setting.toggle(id(path), label, description, p -> config.get().getBoolean(path),
                (p, v) -> { config.get().set(path, v); return null; }));
        return this;
    }

    public ConfigSettings integer(String path, String label, String description, int min, int max, int step) {
        settings.add(Setting.number(id(path), label, description, min, max, step, p -> (double) config.get().getInt(path),
                (p, v) -> { config.get().set(path, (int) Math.round(v)); return null; }));
        return this;
    }

    public ConfigSettings decimal(String path, String label, String description, double min, double max, double step) {
        settings.add(Setting.number(id(path), label, description, min, max, step, p -> config.get().getDouble(path),
                (p, v) -> { config.get().set(path, round(v)); return null; }));
        return this;
    }

    /** Options map stored value to display label. */
    public ConfigSettings choice(String path, String label, String description, LinkedHashMap<String, String> options) {
        settings.add(Setting.choice(id(path), label, description, options, p -> config.get().getString(path),
                (p, v) -> { config.get().set(path, v); return null; }));
        return this;
    }

    public ConfigSettings text(String path, String label, String description, int maxLength) {
        settings.add(Setting.text(id(path), label, description, maxLength, p -> config.get().getString(path, ""),
                (p, v) -> { config.get().set(path, v); return null; }));
        return this;
    }

    /** Whole number typed as text, for ranges too large for a slider. */
    public ConfigSettings integerText(String path, String label, String description, long min, long max) {
        settings.add(Setting.text(id(path), label, description, 20, p -> Long.toString(config.get().getLong(path)), (p, v) -> {
            long value;
            try { value = Long.parseLong(v.trim()); } catch (NumberFormatException e) { return lang().text(p, "settings.not-integer"); }
            if (value < min || value > max) return lang().text(p, "settings.range", "min", min, "max", max);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) config.get().set(path, (int) value); else config.get().set(path, value);
            return null;
        }));
        return this;
    }

    /** Decimal number typed as text (comma or dot), for exact values a slider cannot hit. */
    public ConfigSettings decimalText(String path, String label, String description, double min, double max) {
        settings.add(Setting.text(id(path), label, description, 24, p -> Double.toString(config.get().getDouble(path)), (p, v) -> {
            double value;
            try { value = Double.parseDouble(v.trim().replace(',', '.')); } catch (NumberFormatException e) { return lang().text(p, "settings.not-number"); }
            if (!Double.isFinite(value) || value < min || value > max) return lang().text(p, "settings.range", "min", min, "max", max);
            config.get().set(path, value);
            return null;
        }));
        return this;
    }

    /** Text with a validator returning an error message or null. */
    public ConfigSettings text(String path, String label, String description, int maxLength, java.util.function.Function<String, String> validator) {
        settings.add(Setting.text(id(path), label, description, maxLength, p -> config.get().getString(path, ""), (p, v) -> {
            String error = validator.apply(v);
            if (error != null) return error;
            config.get().set(path, v);
            return null;
        }));
        return this;
    }

    /** Adds a custom setting (e.g. with validation) to the same section. */
    public ConfigSettings add(Setting setting) { settings.add(Objects.requireNonNull(setting)); return this; }

    public SettingsSection section(String id, String title, String description, String permission, Consumer<Player> reload) {
        Consumer<Player> apply = reload == null ? p -> {} : reload;
        return SettingsSection.server(id, title, description, permission, settings, p -> {
            // 0.66.1: a config.yml with a YAML error loaded as almost empty; saving it would replace the admin's file
            if (owner != null && Vystorm.vystorm_core.config.YamlProblems.refusedSave(p, owner)) return;
            save.run();
            apply.accept(p);
        });
    }

    private static double round(double v) { return Math.round(v * 1_000_000d) / 1_000_000d; }
}
