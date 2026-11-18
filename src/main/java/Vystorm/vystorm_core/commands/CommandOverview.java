package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /vhelp [plugin] [page]}: every command of every Vystorm plugin the sender may use, grouped by plugin, in the
 * sender's language, each line clickable (help of a command tree, or typed into the chat). Commands not registered
 * through Core are listed from plugin.yml; their description comes from {@code commands.<name>.description} of the
 * plugin's language bundle if present (so plugins can localize the overview before migrating).
 *
 * <p>Core internal (registered by Core in onEnable).
 *
 * @since 0.45.0
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CommandOverview {
    /** One line of the overview. */
    public record Entry(String plugin, String label, List<String> aliases, String description, boolean tree) {}

    private CommandOverview() {}

    /** Core: registers {@code /vhelp}. */
    public static CommandRegistration register(Plugin core) {
        return CoreCommands.register(core, Localization.get().core(), spec(core));
    }

    static CommandSpec spec(Plugin core) {
        return CommandSpec.command("vhelp")
                .description("All commands you may use, by plugin")
                .arg(Arg.word("filter").optional().suggests((ctx, prefix) -> {
                    List<String> out = new ArrayList<>();
                    for (Plugin p : plugins(core)) if (!entries(ctx.sender(), List.of(p)).isEmpty()) out.add(p.getName());
                    return out;
                }))
                .arg(Arg.integer("page", 1, 9999).optional())
                .executes(ctx -> {
                    String filter = ctx.get("filter");
                    Integer page = ctx.get("page");
                    if (filter != null && filter.matches("\\d{1,4}") && page == null) { page = Integer.parseInt(filter); filter = null; }
                    send(ctx.sender(), core, filter, page == null ? 1 : page);
                })
                .build();
    }

    static List<Plugin> plugins(Plugin core) {
        try {
            PluginManager manager = Bukkit.getPluginManager();
            return Vystorm.vystorm_core._uiMethods.admin.PermissionList.plugins(manager, core);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Sends the overview page. */
    public static void send(CommandSender sender, Plugin core, String plugin, int page) {
        Lang lang = Localization.get().core();
        List<Plugin> plugins = plugins(core);
        if (plugin != null) {
            plugins = plugins.stream().filter(p -> p.getName().equalsIgnoreCase(plugin)).toList();
            if (plugins.isEmpty()) { lang.send(sender, "command.overview.unknown-plugin", "plugin", plugin); return; }
        }
        List<Entry> entries = entries(sender, plugins);
        if (entries.isEmpty()) { lang.send(sender, "command.overview.empty"); return; }
        int size = Settings.current().helpPageSize() * 2;
        int pages = Math.max(1, (entries.size() + size - 1) / size);
        int current = Math.max(1, Math.min(page, pages));
        lang.send(sender, "command.overview.header", "page", current, "pages", pages);
        String shown = null;
        for (Entry e : entries.subList((current - 1) * size, Math.min(entries.size(), current * size))) {
            if (!e.plugin().equals(shown)) { lang.send(sender, "command.overview.plugin", "plugin", e.plugin()); shown = e.plugin(); }
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line(sender, lang, e));
        }
        if (pages > 1) {
            String base = "/vhelp " + (plugin == null ? "" : plugin + " ");
            Component nav = Component.empty();
            if (current > 1) nav = nav.append(lang.component(sender, "command.help.previous").clickEvent(ClickEvent.runCommand(base + (current - 1))));
            if (current > 1 && current < pages) nav = nav.append(Component.text("  "));
            if (current < pages) nav = nav.append(lang.component(sender, "command.help.next").clickEvent(ClickEvent.runCommand(base + (current + 1))));
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, nav);
        }
        if (plugin == null && current == 1) lang.send(sender, "command.overview.footer");
    }

    static Component line(CommandSender sender, Lang lang, Entry e) {
        Component also = e.aliases().isEmpty() ? Component.empty() : lang.component(sender, "command.overview.also", "aliases", String.join(", /", e.aliases()));
        Component line = lang.component(sender, "command.overview.line", "command", e.label(), "also", also, "description", e.description());
        String action = lang.plain(sender, e.tree() ? "command.overview.action-help" : "command.overview.action-type");
        return line.hoverEvent(HoverEvent.showText(lang.component(sender, "command.overview.hover", "description", e.description(), "action", action)))
                .clickEvent(e.tree() ? ClickEvent.runCommand("/" + e.label() + " help") : ClickEvent.suggestCommand("/" + e.label() + " "));
    }

    /** Lines for the sender: Core registrations first per plugin, then plugin.yml commands not registered through Core. */
    public static List<Entry> entries(CommandSender sender, List<Plugin> plugins) {
        List<Entry> out = new ArrayList<>();
        for (Plugin p : plugins) {
            Set<String> done = new LinkedHashSet<>();
            for (CommandRegistration r : CoreCommands.registrations(p)) {
                done.add(r.label());
                if (!Engine.allowed(sender, r.spec()) || r.entries(sender).isEmpty() && !(r.spec().children().isEmpty())) continue;
                if (!r.spec().visibleTo(sender)) continue;
                String language = r.lang().language(sender);
                List<String> aliases = new ArrayList<>();
                for (CommandRegistration.Alias a : r.localizedAliases())
                    if (a.status() == CommandRegistration.AliasStatus.ACTIVE && Localization.languagePart(a.language()).equals(Localization.languagePart(language))) aliases.add(a.alias());
                out.add(new Entry(p.getName(), r.label(), aliases, r.description(sender, r.spec()), r.spec().helpEnabled()));
            }
            Map<String, Map<String, Object>> declared;
            try { declared = p.getDescription().getCommands(); } catch (RuntimeException e) { declared = Map.of(); }
            if (declared == null) continue;
            for (Map.Entry<String, Map<String, Object>> c : declared.entrySet()) {
                String name = c.getKey();
                if (done.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
                PluginCommand command = p instanceof JavaPlugin jp ? jp.getCommand(name) : null;
                if (command == null || command.getPlugin() != p) continue;
                // 0.65.0: Core's switched plugin.yml commands leave the list while their switch is off
                if ("vystorm_core".equals(p.getName()) && Vystorm.vystorm_core.lifecycle.CoreSwitches.commandOff(name)) continue;
                if (!command.testPermissionSilent(sender)) continue;
                String description = command.getDescription();
                var bundle = Localization.get().bundle(p);
                if (bundle.isPresent() && bundle.get().has("commands." + name + ".description"))
                    description = bundle.get().plain(sender, "commands." + name + ".description");
                out.add(new Entry(p.getName(), command.getName(), List.of(), description == null ? "" : description, false));
            }
        }
        return out;
    }
}
