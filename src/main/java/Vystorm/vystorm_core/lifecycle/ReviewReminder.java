package Vystorm.vystorm_core.lifecycle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Once a Vystorm plugin with a SpigotMC page ({@link UpdateSources}) has been installed for {@code after-days}, the next
 * admin who joins gets one short message asking for a review, with a link. Every plugin asks only once. When several
 * are due at the same time, Core sends a single message that lists them all. Off with {@code review-reminder.enabled}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ReviewReminder implements Listener {
    static final long DAY = 24L * 60 * 60 * 1000;
    private static ReviewReminder instance;

    private final JavaPlugin core;
    private final File file;
    private final YamlConfiguration data;
    private final boolean broken;
    private final long after;

    ReviewReminder(JavaPlugin core, long after) {
        this.core = core;
        this.after = after;
        this.file = new File(core.getDataFolder(), "reviews.yml");
        this.data = new YamlConfiguration();
        boolean unreadable = false;
        if (file.isFile()) {
            try { data.load(file); }
            catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
                // Never overwrite an unreadable file with an empty one: keep it aside and stop saving.
                unreadable = true;
                java.nio.file.Path aside = Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.moveAside(file.toPath());
                core.getLogger().severe("reviews.yml could not be read (" + failure.getMessage() + "). It was kept as " + aside.getFileName()
                        + " and is not saved until the next restart.");
            }
        }
        this.broken = unreadable;
    }

    public static void start(JavaPlugin core) {
        stop();
        ConfigurationSection config = core.getConfig().getConfigurationSection("review-reminder");
        if (config != null && !config.getBoolean("enabled", true)) return;
        long days = config == null ? 7 : Math.clamp(config.getLong("after-days", 7), 1, 365);
        ReviewReminder reminder = new ReviewReminder(core, days * DAY);
        core.getServer().getPluginManager().registerEvents(reminder, core);
        // Plugins register their SpigotMC page while enabling, after Core: note them once the server is up.
        core.getServer().getScheduler().runTaskLater(core, () -> reminder.note(System.currentTimeMillis()), 20L);
        instance = reminder;
    }

    /** Whether the reminder is running (review-reminder.enabled at the last start or reload). @since 0.65.0 */
    public static boolean running() { return instance != null; }

    public static void stop() {
        if (instance != null) HandlerList.unregisterAll(instance);
        instance = null;
    }

    /** Remembers when each installed plugin with a SpigotMC page was first seen. */
    void note(long now) {
        boolean changed = false;
        for (String name : installed().keySet()) {
            String key = "plugins." + name;
            if (!data.contains(key + ".first-seen")) { data.set(key + ".first-seen", now); changed = true; }
        }
        if (changed) save();
    }

    /** Installed plugins whose review question is due, Core first: name to SpigotMC resource id. */
    Map<String, Integer> due(long now) {
        Map<String, Integer> due = new java.util.LinkedHashMap<>();
        Map<String, Integer> installed = installed();
        Integer coreId = installed.remove(core.getName());
        if (coreId != null) installed = withFirst(core.getName(), coreId, installed);
        for (var entry : installed.entrySet()) {
            String key = "plugins." + entry.getKey();
            long first = data.getLong(key + ".first-seen", now);
            if (!data.getBoolean(key + ".asked") && now - first >= after) due.put(entry.getKey(), entry.getValue());
        }
        return due;
    }

    private static Map<String, Integer> withFirst(String name, int id, Map<String, Integer> rest) {
        Map<String, Integer> ordered = new java.util.LinkedHashMap<>();
        ordered.put(name, id);
        ordered.putAll(rest);
        return ordered;
    }

    private Map<String, Integer> installed() {
        Map<String, Integer> installed = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var entry : UpdateSources.all().entrySet()) {
            var plugin = core.getServer().getPluginManager().getPlugin(entry.getKey());
            if (plugin != null) installed.put(plugin.getName(), entry.getValue());
        }
        return installed;
    }

    void asked(java.util.Collection<String> plugins) {
        for (String name : plugins) data.set("plugins." + name + ".asked", true);
        save();
    }

    static String reviewUrl(int resource) { return "https://www.spigotmc.org/resources/" + resource + "/reviews"; }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("vystorm_core.admin")) return;
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline() || instance != this) return;
            long now = System.currentTimeMillis();
            note(now);
            Map<String, Integer> due = due(now);
            if (due.isEmpty()) return;
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, message(player, due));
            asked(due.keySet());
        }, 200L);
    }

    private Component message(Player player, Map<String, Integer> due) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (due.size() == 1) {
            var only = due.entrySet().iterator().next();
            return lang.component(player, "review.one", "plugin", only.getKey(), "days", after / DAY)
                    .append(Component.space()).append(link(player, only.getKey(), only.getValue()));
        }
        List<Component> links = new ArrayList<>();
        for (var entry : due.entrySet()) links.add(link(player, entry.getKey(), entry.getValue()));
        return lang.component(player, "review.many", "count", due.size()).append(Component.space())
                .append(Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.space()), links));
    }

    private static Component link(Player player, String plugin, int resource) {
        String url = reviewUrl(resource);
        return Vystorm.vystorm_core.lang.Localization.get().core().component(player, "review.link", "plugin", plugin)
                .clickEvent(ClickEvent.openUrl(url)).hoverEvent(Component.text(url));
    }

    private void save() {
        if (broken) return;
        try { Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(file.toPath(), data); }
        catch (IOException failure) { core.getLogger().warning("Could not save reviews.yml: " + failure.getMessage()); }
    }
}
