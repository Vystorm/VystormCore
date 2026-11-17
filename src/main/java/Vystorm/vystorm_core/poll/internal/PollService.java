package Vystorm.vystorm_core.poll.internal;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.schedule.TaskGroup;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/** Core-owned polls; all state and callbacks stay on the server thread. */
public final class PollService implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Lang lang;
    private final TaskGroup tasks;
    private final java.util.function.LongSupplier clock;
    private Poll active;
    private Poll result;

    public PollService(Plugin plugin) {
        this(plugin, System::currentTimeMillis);
    }

    PollService(Plugin plugin, java.util.function.LongSupplier clock) {
        this.plugin = plugin;
        this.clock = clock;
        mergeDefaults(plugin);
        lang = Localization.get().core();
        tasks = TaskGroup.of(plugin, "polls");
        CoreCommands.register(plugin, lang, CommandSpec.command("vpoll")
                .permission("vystorm_core.poll").executes(c -> show(c.sender()))
                .sub("start", s -> s.permission("vystorm_core.admin")
                        .arg(Arg.integer("seconds", 10, 3600)).arg(Arg.greedy("question"))
                        .executes(c -> start(c.sender(), c.get("seconds"), c.get("question"))))
                .sub("vote", s -> s.permission("vystorm_core.poll").playerOnly()
                        .arg(Arg.word("id")).arg(Arg.integer("option", 1, 10))
                        .executes(c -> {
                            expire();
                            if (active == null || !active.id.equals(c.<String>get("id"))
                                    || !active.vote(c.player().getUniqueId(), c.get("option"), clock.getAsLong()))
                                lang.send(c.sender(), "poll.unavailable");
                            else lang.send(c.sender(), "poll.voted", "option", active.options.get(c.<Integer>get("option") - 1));
                        }))
                .sub("close", s -> s.permission("vystorm_core.admin").executes(c -> {
                    if (active == null) lang.send(c.sender(), "poll.unavailable");
                    else finish();
                }))
                .sub("cancel", s -> s.permission("vystorm_core.admin").executes(c -> {
                    if (active == null) lang.send(c.sender(), "poll.unavailable");
                    else { active = null; broadcast("poll.cancelled"); }
                }))
                .sub("results", s -> s.permission("vystorm_core.poll").executes(c -> results(c.sender())))
                .build());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        tasks.timer(20, 20, this::expire);
    }

    static void mergeDefaults(Plugin plugin) {
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        var config = Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.readYaml(path, plugin.getLogger());
        if (config == null) return;
        boolean changed = false;
        var defaults = java.util.Map.<String, Object>of("enabled", true, "max-duration-seconds", 600,
                "max-options", 6, "max-text-length", 120);
        for (var entry : defaults.entrySet()) {
            String key = "polls." + entry.getKey();
            if (!config.contains(key)) { config.set(key, entry.getValue()); changed = true; }
        }
        if (!changed) return;
        try {
            java.nio.file.Files.copy(path, path.resolveSibling("config.yml.before-polls"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(path, config);
        } catch (java.io.IOException failure) {
            plugin.getLogger().warning("Could not add poll defaults: " + failure.getMessage());
        }
    }

    void start(CommandSender sender, int seconds, String input) {
        expire();
        if (!plugin.getConfig().getBoolean("polls.enabled", true)) { lang.send(sender, "poll.disabled"); return; }
        if (active != null) { lang.send(sender, "poll.running"); return; }
        int duration = Math.clamp(plugin.getConfig().getInt("polls.max-duration-seconds", 600), 10, 3600);
        int options = Math.clamp(plugin.getConfig().getInt("polls.max-options", 6), 2, 10);
        int length = Math.clamp(plugin.getConfig().getInt("polls.max-text-length", 120), 10, 240);
        if (seconds < 10 || seconds > duration) { lang.send(sender, "poll.duration", "max", duration); return; }
        try { active = new Poll(input, clock.getAsLong(), seconds, options, length); }
        catch (IllegalArgumentException invalid) { lang.send(sender, "poll.invalid", "max", options, "length", length); return; }
        for (var player : plugin.getServer().getOnlinePlayers()) if (player.hasPermission("vystorm_core.poll")) show(player);
        show(plugin.getServer().getConsoleSender());
        lang.send(sender, "poll.started");
    }

    private void expire() { if (active != null && clock.getAsLong() >= active.deadline) finish(); }

    private void finish() {
        result = active;
        active = null;
        for (var player : plugin.getServer().getOnlinePlayers()) if (player.hasPermission("vystorm_core.poll")) results(player);
        results(plugin.getServer().getConsoleSender());
    }

    private void show(CommandSender sender) {
        expire();
        if (active == null) { lang.send(sender, "poll.unavailable"); return; }
        lang.send(sender, "poll.question", "question", active.question,
                "seconds", Math.max(0, (active.deadline - clock.getAsLong() + 999) / 1000));
        for (int i = 0; i < active.options.size(); i++) Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, lang.component(sender,
                "poll.option", "number", i + 1, "option", active.options.get(i))
                .clickEvent(ClickEvent.runCommand("/vpoll vote " + active.id + " " + (i + 1))));
    }

    private void results(CommandSender sender) {
        if (result == null) { lang.send(sender, "poll.no-results"); return; }
        lang.send(sender, "poll.results", "question", result.question, "total", result.voters());
        int[] counts = result.counts();
        for (int i = 0; i < counts.length; i++) lang.send(sender, "poll.result-option",
                "option", result.options.get(i), "votes", counts[i]);
    }

    private void broadcast(String key) {
        for (var player : plugin.getServer().getOnlinePlayers()) if (player.hasPermission("vystorm_core.poll")) lang.send(player, key);
        lang.send(plugin.getServer().getConsoleSender(), key);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (active != null && event.getPlayer().hasPermission("vystorm_core.poll")) show(event.getPlayer());
    }

    @Override public void close() {
        tasks.close();
        HandlerList.unregisterAll(this);
        active = null;
        result = null;
    }
}
