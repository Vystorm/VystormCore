package Vystorm.vystorm_core.commands;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code /vadmin commands}: every command tree registered through Core with its mount, fixed aliases, localized root
 * aliases (state, collisions, clickable on/off) and subcommand name conflicts; {@code mode all|default|none} and
 * {@code alias <alias> on|off} change {@code config.yml} {@code commands.*} and apply at once.
 *
 * <p>Core internal (called by the admin dashboard; permission checked there).
 *
 * @since 0.45.0
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class CommandsAdmin {
    /** Subcommands of {@code /vadmin commands} for tab completion. */
    public static final List<String> SUBCOMMANDS = List.of("mode", "alias");

    private CommandsAdmin() {}

    /** {@code args} as typed after {@code /vadmin}: {@code commands ...}. */
    public static void handle(CommandSender sender, JavaPlugin core, String[] args) {
        Lang lang = Localization.get().core();
        if (args.length >= 3 && args[1].equalsIgnoreCase("mode")) {
            AliasPolicy policy = AliasPolicy.parse(args[2], null);
            if (policy == null) { lang.send(sender, "command.admin.usage"); return; }
            if (Vystorm.vystorm_core.config.YamlProblems.refusedSave(sender, core)) return;
            core.getConfig().set("commands.localized-aliases", policy.key());
            save(core);
            lang.send(sender, "command.admin.mode-set", "mode", policy.key());
            return;
        }
        if (args.length >= 4 && args[1].equalsIgnoreCase("alias")) {
            String alias = args[2].toLowerCase(Locale.ROOT);
            boolean on = AliasPolicy.parse(args[3], AliasPolicy.NONE) == AliasPolicy.ALL;
            if (!known(alias)) { lang.send(sender, "command.admin.alias-unknown", "alias", alias); return; }
            if (Vystorm.vystorm_core.config.YamlProblems.refusedSave(sender, core)) return;
            Set<String> disabled = new LinkedHashSet<>(core.getConfig().getStringList("commands.disabled-aliases"));
            disabled.removeIf(s -> s.equalsIgnoreCase(alias) || s.toLowerCase(Locale.ROOT).endsWith(":" + alias));
            if (!on) disabled.add(alias);
            core.getConfig().set("commands.disabled-aliases", new ArrayList<>(disabled));
            save(core);
            CommandRegistration.AliasStatus status = statusOf(alias);
            lang.send(sender, "command.admin.alias-set", "alias", alias, "state", lang.plain(sender, "command.admin.state-" + status.name().toLowerCase(Locale.ROOT), "owner", ""));
            return;
        }
        int page = 1;
        if (args.length >= 2) {
            if (!args[1].matches("\\d{1,4}")) { lang.send(sender, "command.admin.usage"); return; }
            page = Integer.parseInt(args[1]);
        }
        list(sender, core, page);
    }

    private static void save(JavaPlugin core) {
        core.saveConfig();
        CoreCommands.configure(core.getConfig().getConfigurationSection("commands"));
    }

    private static boolean known(String alias) {
        for (CommandRegistration r : CoreCommands.registrations())
            for (CommandRegistration.Alias a : r.localizedAliases()) if (a.alias().equals(alias)) return true;
        return false;
    }

    private static CommandRegistration.AliasStatus statusOf(String alias) {
        for (CommandRegistration r : CoreCommands.registrations())
            for (CommandRegistration.Alias a : r.localizedAliases()) if (a.alias().equals(alias)) return a.status();
        return CommandRegistration.AliasStatus.DISABLED;
    }

    static void list(CommandSender sender, JavaPlugin core, int requested) {
        Lang lang = Localization.get().core();
        List<CommandRegistration> all = CoreCommands.registrations();
        int size = 6;
        int pages = Math.max(1, (all.size() + size - 1) / size);
        int page = Math.max(1, Math.min(requested, pages));
        lang.send(sender, "command.admin.header", "count", all.size(), "page", page, "pages", pages);
        Component mode = lang.component(sender, "command.admin.mode", "mode", Settings.current().policy().key(), "language", Registry.INSTANCE.serverLanguage.get());
        for (AliasPolicy p : AliasPolicy.values())
            mode = mode.append(Component.space()).append(lang.component(sender, "command.admin.mode-option", "mode", p.key())
                    .clickEvent(ClickEvent.runCommand("/vadmin commands mode " + p.key()))
                    .hoverEvent(HoverEvent.showText(lang.component(sender, "command.admin.mode-hover", "mode", p.key()))));
        Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, mode);
        for (CommandRegistration r : all.subList((page - 1) * size, Math.min(all.size(), page * size))) {
            String fixed = r.fixedAliases().isEmpty() ? "" : lang.plain(sender, "command.admin.fixed", "aliases", "/" + String.join(", /", r.fixedAliases()));
            lang.send(sender, "command.admin.line", "plugin", r.plugin().getName(), "command", r.label(), "mount", r.mount().name().toLowerCase(Locale.ROOT), "fixed", fixed);
            if (r.labelConflict() != null)
                lang.send(sender, "command.admin.label-conflict", "command", r.label(), "owner", r.labelConflict(), "prefix", r.plugin().getName().toLowerCase(Locale.ROOT));
            r.fixedAliasConflicts().forEach((alias, owner) ->
                    lang.send(sender, "command.admin.fixed-conflict", "alias", alias, "owner", owner));
            for (CommandRegistration.Alias a : r.localizedAliases()) {
                Component state = lang.component(sender, "command.admin.state-" + a.status().name().toLowerCase(Locale.ROOT), "owner", a.detail() == null ? "?" : a.detail());
                Component line = lang.component(sender, "command.admin.alias", "language", a.language(), "alias", a.alias(), "state", state);
                if (a.status() != CommandRegistration.AliasStatus.OFF_BY_POLICY) {
                    boolean off = a.status() == CommandRegistration.AliasStatus.DISABLED;
                    String value = off ? "on" : "off";
                    line = line.append(Component.space()).append(lang.component(sender, off ? "command.admin.turn-on" : "command.admin.turn-off")
                            .clickEvent(ClickEvent.runCommand("/vadmin commands alias " + a.alias() + " " + value))
                            .hoverEvent(HoverEvent.showText(lang.component(sender, "command.admin.toggle-hover", "alias", a.alias(), "value", value))));
                }
                Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, line);
            }
            for (String conflict : r.conflicts()) lang.send(sender, "command.admin.conflict", "conflict", conflict);
        }
        if (page == pages) {
            List<String> other = new ArrayList<>();
            for (Plugin p : CommandOverview.plugins(core)) {
                Map<String, Map<String, Object>> declared;
                try { declared = p.getDescription().getCommands(); } catch (RuntimeException e) { continue; }
                if (declared == null) continue;
                for (String name : declared.keySet()) {
                    boolean registered = CoreCommands.registrations(p).stream().anyMatch(r -> r.label().equalsIgnoreCase(name));
                    if (!registered) other.add(p.getName() + ":" + name);
                }
            }
            if (!other.isEmpty()) lang.send(sender, "command.admin.other", "commands", String.join(", ", other));
        }
    }

    /** Tab completion after {@code /vadmin commands}. */
    public static List<String> complete(String[] args) {
        if (args.length == 2) return List.of("mode", "alias");
        if (args.length == 3 && args[1].equalsIgnoreCase("mode")) return List.of("all", "default", "none");
        if (args.length == 3 && args[1].equalsIgnoreCase("alias")) {
            List<String> out = new ArrayList<>();
            for (CommandRegistration r : CoreCommands.registrations()) for (CommandRegistration.Alias a : r.localizedAliases()) out.add(a.alias());
            return out;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("alias")) return List.of("on", "off");
        return List.of();
    }
}
