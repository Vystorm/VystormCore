package Vystorm.vystorm_core.lifecycle;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Asks SpigotMC which version of each installed Vystorm plugin is current and tells admins about newer ones
 * ({@code /vadmin updates}, and once per check on join). Only the public version endpoint is contacted; no server data
 * is sent. Disable with {@code update-check.enabled: false}.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class UpdateChecker implements Listener {
    private static final Pattern NUMBERS = Pattern.compile("^\\D*(\\d+(?:\\.\\d+)*)");
    private static final Pattern SAFE = Pattern.compile("[0-9A-Za-z._+\\- ]{1,64}");
    public record Result(String plugin, int resource, String installed, String latest) {
        public boolean newer() { return latest != null && UpdateChecker.newer(installed, latest); }
        public String url() { return "https://www.spigotmc.org/resources/" + resource + "/updates"; }
    }

    private static volatile UpdateChecker instance;
    private final JavaPlugin core;
    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private final Set<UUID> told = ConcurrentHashMap.newKeySet();
    private volatile long checked;
    private BukkitTask task;

    private UpdateChecker(JavaPlugin core) { this.core = core; }

    public static UpdateChecker get() { return instance; }

    public static void start(JavaPlugin core) {
        stop();
        ConfigurationSection config = core.getConfig().getConfigurationSection("update-check");
        if (config != null && !config.getBoolean("enabled", true)) return;
        UpdateChecker checker = new UpdateChecker(core);
        long hours = config == null ? 6 : Math.clamp(config.getLong("interval-hours", 6), 1, 168);
        checker.task = core.getServer().getScheduler().runTaskTimerAsynchronously(core, checker::check, 20L * 60, 20L * 3600 * hours);
        core.getServer().getPluginManager().registerEvents(checker, core);
        instance = checker;
    }

    public static void stop() {
        UpdateChecker old = instance;
        instance = null;
        if (old != null && old.task != null) old.task.cancel();
        if (old != null) org.bukkit.event.HandlerList.unregisterAll(old);
    }

    /** Numeric version prefix: "1.29.1-26.2" and "v1.29.1" both give [1, 29, 1]; empty when there is none. */
    static int[] numbers(String version) {
        if (version == null) return new int[0];
        Matcher matcher = NUMBERS.matcher(version.trim());
        if (!matcher.find()) return new int[0];
        return Arrays.stream(matcher.group(1).split("\\.")).mapToInt(part -> {
            try { return Integer.parseInt(part); } catch (NumberFormatException tooLong) { return Integer.MAX_VALUE; }
        }).toArray();
    }

    /** Whether {@code latest} has a higher numeric version than {@code installed} (suffixes like "-26.2" are ignored). */
    public static boolean newer(String installed, String latest) {
        int[] a = numbers(installed), b = numbers(latest);
        if (a.length == 0 || b.length == 0) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return y > x;
        }
        return false;
    }

    /** Installed plugins to check: those registered in {@link UpdateSources} plus {@code update-check.extra} (name: resource id). */
    Map<String, Integer> targets() {
        Map<String, Integer> targets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        targets.putAll(UpdateSources.all());
        ConfigurationSection extra = core.getConfig().getConfigurationSection("update-check.extra");
        if (extra != null) for (String name : extra.getKeys(false)) if (extra.getInt(name) > 0) targets.put(name, extra.getInt(name));
        targets.keySet().removeIf(name -> core.getServer().getPluginManager().getPlugin(name) == null);
        return targets;
    }

    /** Runs off the main thread: one small request per installed plugin. */
    public void check() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        for (var target : targets().entrySet()) {
            if (!core.isEnabled()) return;
            Plugin plugin = core.getServer().getPluginManager().getPlugin(target.getKey());
            if (plugin == null) continue;
            String installed = plugin.getDescription().getVersion();
            String latest = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + target.getValue()))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", "VystormCore-UpdateCheck").GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body() == null ? "" : response.body().trim();
                if (response.statusCode() == 200 && SAFE.matcher(body).matches()) latest = body;
            } catch (java.io.IOException failure) {
                Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages.low("[UpdateChecker] " + target.getKey() + ": " + failure.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            results.put(plugin.getName(), new Result(plugin.getName(), target.getValue(), installed, latest));
        }
        checked = System.currentTimeMillis();
        told.clear();
        List<Result> updates = updates();
        if (!updates.isEmpty())
            core.getLogger().info("Updates available: " + String.join(", ", updates.stream().map(r -> r.plugin() + " " + r.latest() + " (installed " + r.installed() + ")").toList()));
    }

    public List<Result> results() { return results.values().stream().sorted(Comparator.comparing(Result::plugin, String.CASE_INSENSITIVE_ORDER)).toList(); }
    /** Newer versions that are not silenced by {@code update-check.ignore}. */
    public List<Result> updates() { return results().stream().filter(r -> r.newer() && !ignored(r)).toList(); }
    /** Newer versions silenced by {@code update-check.ignore}; still listed in {@code /vadmin updates}. @since 0.42.0 */
    public List<Result> ignoredUpdates() { return results().stream().filter(r -> r.newer() && ignored(r)).toList(); }
    public long lastCheck() { return checked; }

    private boolean ignored(Result result) {
        return ignored(core.getConfig().getStringList("update-check.ignore"), result.plugin(), result.latest());
    }

    /**
     * Whether an {@code update-check.ignore} rule matches: {@code "Plugin"} silences every version of the plugin,
     * {@code "Plugin 1.4.0"} only that version (compared numerically, so "1.4.0-26.2" matches; later versions are announced
     * again). Names are case-insensitive.
     */
    static boolean ignored(List<String> rules, String plugin, String latest) {
        if (rules == null || plugin == null) return false;
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) continue;
            String name = rule.trim(), version = null;
            int space = name.lastIndexOf(' ');
            if (space > 0 && numbers(name.substring(space + 1)).length > 0) {
                version = name.substring(space + 1);
                name = name.substring(0, space).trim();
            }
            if (!name.equalsIgnoreCase(plugin)) continue;
            if (version == null) return true;
            if (latest != null && numbers(latest).length > 0 && !newer(version, latest) && !newer(latest, version)) return true;
        }
        return false;
    }

    /** Whole minutes since {@code since} (never negative). */
    static long minutesAgo(long since, long now) { return Math.max(0, (now - since) / 60_000L); }

    /** {@code /vadmin updates [check]}. */
    public void send(CommandSender sender, boolean recheck) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (recheck) {
            lang.send(sender, "updates.checking");
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                check();
                if (core.isEnabled()) core.getServer().getScheduler().runTask(core, () -> send(sender, false));
            });
            return;
        }
        if (checked == 0) { lang.send(sender, "updates.not-yet"); return; }
        List<Result> all = results();
        List<Result> silenced = ignoredUpdates();
        for (Result result : all) {
            String key = result.latest() == null ? "updates.line-unknown" : result.newer() ? "updates.line-newer" : "updates.line-current";
            net.kyori.adventure.text.Component line = lang.component(sender, key, "plugin", result.plugin(), "installed", result.installed(),
                    "latest", result.latest() == null ? "?" : result.latest());
            if (result.newer()) line = line.append(net.kyori.adventure.text.Component.space()).append(lang.component(sender, "updates.download")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(result.url()))
                    .hoverEvent(net.kyori.adventure.text.Component.text(result.url())));
            if (silenced.contains(result)) line = line.append(net.kyori.adventure.text.Component.space()).append(lang.component(sender, "updates.ignored"));
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line);
        }
        List<Result> updates = updates();
        lang.send(sender, updates.isEmpty() ? "updates.all-current" : "updates.summary", "count", updates.size());
        lang.send(sender, "updates.last-check", "minutes", minutesAgo(checked, System.currentTimeMillis()));
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("vystorm_core.admin") || !core.getConfig().getBoolean("update-check.notify-on-join", true)) return;
        core.getServer().getScheduler().runTaskLater(core, () -> {
            List<Result> updates = updates();
            if (updates.isEmpty() || !player.isOnline() || !told.add(player.getUniqueId())) return;
            var lang = Vystorm.vystorm_core.lang.Localization.get().core();
            // The whole notice is clickable and opens the full list with the download links.
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, lang.component(player, "updates.join", "count", updates.size(),
                    "list", String.join(", ", updates.stream().map(r -> r.plugin() + " " + r.latest()).toList()))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/vadmin updates"))
                    .hoverEvent(lang.component(player, "updates.join-hover")));
        }, 60L);
    }
}
