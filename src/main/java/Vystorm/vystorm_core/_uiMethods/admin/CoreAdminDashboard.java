package Vystorm.vystorm_core._uiMethods.admin;

import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Curated dialog adapter. It invokes only the original owner-verified PluginCommand as its player. */
public final class CoreAdminDashboard implements TabExecutor {
    private static final int PER_PAGE = 12;
    private final JavaPlugin core;
    private final AdminInterface registry;
    private final List<AdminModule> catalog;

    public CoreAdminDashboard(JavaPlugin core, AdminInterface registry) {
        this.core = core;
        this.registry = registry;
        catalog = Catalog.modules();
    }

    private static Vystorm.vystorm_core.lang.Lang lang() {
        return Vystorm.vystorm_core.lang.Localization.get().core();
    }

    private static Vystorm.vystorm_core.lang.Localization i18n() {
        return Vystorm.vystorm_core.lang.Localization.get();
    }

    public void registerMenu() {
        CoreFeatureDiagnostics.register(core);
        Vystorm.vystorm_core.main.getApi().gui().registerMenu(
                core, "admin", lang().ref("admin.menu-title"), Material.COMPASS, this::valid, p -> open(p, 0),
                Vystorm.vystorm_core._uiMethods.menu.MenuCategory.ADMIN.group(), lang().ref("admin.menu-description"));
        Vystorm.vystorm_core.main.getApi().gui().registerMenu(
                core, "features", lang().ref("features.entry"), Material.LEVER,
                p -> !Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(p).isEmpty(),
                Vystorm.vystorm_core._uiMethods.settings.ConfigEditor::openFeatures,
                Vystorm.vystorm_core._uiMethods.menu.MenuCategory.ADMIN.group(), lang().ref("features.entry-tooltip"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vystorm_core.admin")) {
            lang().send(sender, "admin.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player p) open(p, 0);
            else lang().send(sender, "admin.usage");
            return true;
        }
        if (args[0].equalsIgnoreCase("migration")) {
            var keys = Vystorm.vystorm_core.storage.MigrationPreviews.keys();
            if (args.length != 2 || !keys.contains(args[1])) {
                lang().send(sender, "migration.available", "ids", String.join(", ", keys));
                return true;
            }
            var report = Vystorm.vystorm_core.storage.MigrationPreviews.run(args[1]);
            lang().send(
                    sender, "migration.result", "id", args[1], "before", report.beforeCount(), "after",
                    report.afterCount(), "before-sums", report.beforeSums(), "after-sums", report.afterSums(),
                    "problems", report.problems());
            lang().send(sender, report.conserved() ? "migration.conserved" : "migration.review");
            return true;
        }
        if (args[0].equalsIgnoreCase("diagnostics")) {
            diagnostics(sender, args.length > 1 ? args[1] : null);
            return true;
        }
        if (args[0].equalsIgnoreCase("updates")) {
            var checker = Vystorm.vystorm_core.lifecycle.UpdateChecker.get();
            if (checker == null) lang().send(sender, "updates.disabled");
            else checker.send(sender, args.length > 1 && args[1].equalsIgnoreCase("check"));
            return true;
        }
        if (args[0].equalsIgnoreCase("config") && args.length > 1 && args[1].equalsIgnoreCase("history")) {
            configHistory(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("config") && args.length > 1 && args[1].equalsIgnoreCase("undo")) {
            configUndo(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("config")) {
            config(sender, args.length > 1 ? args[1] : null);
            return true;
        }
        if (args[0].equalsIgnoreCase("features")) {
            features(sender, args.length > 1 ? args[1] : null);
            return true;
        }
        if (args[0].equalsIgnoreCase("screens")) {
            screens(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("setup")) {
            if (sender instanceof Player p)
                Vystorm.vystorm_core._uiMethods.settings.FirstRunSetup.open(p, args.length > 1 ? args[1] : null);
            else lang().send(sender, "admin.ingame-only");
            return true;
        }
        if (args[0].equalsIgnoreCase("news")) {
            Vystorm.vystorm_core.news.internal.NewsService.adminCommand(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("loot")) {
            Vystorm.vystorm_core.loot.internal.LootAdminCommand.execute(core, sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("commands")) {
            Vystorm.vystorm_core.commands.CommandsAdmin.handle(sender, core, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("trace")) {
            trace(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("permissions")) {
            int page = 1;
            if (args.length > 2) {
                try {
                    page = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    lang().send(sender, "permissions.usage");
                    return true;
                }
            }
            PermissionList.send(
                    sender, core.getServer().getPluginManager(), core, args.length > 1 ? args[1] : null, page);
            return true;
        }
        if (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("status")) {
            visible(sender).forEach(
                    m -> lang().send(
                            sender, "admin.list-line", "id", m.id(), "title", i18n().resolve(sender, m.title()),
                            "state", state(m, sender)));
            return true;
        }
        if (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("lang")) {
            Vystorm.vystorm_core.lang.internal.LanguageAdmin.execute(core, sender, args);
            return true;
        }
        AdminModule m = find(args[0], sender);
        if (m == null) {
            lang().send(sender, "admin.unknown-module");
            return true;
        }
        if (!(sender instanceof Player p)) {
            lang().send(sender, "admin.ingame-only");
            return true;
        }
        openModule(p, m, 0);
        return true;
    }

    /** {@code /vadmin config [plugin]}: the plugin's config sections, or the overview of all editable configs. */
    /** {@code /vadmin features [plugin]} (0.60.0): the feature switches of the plugins as a Vystorm screen. */
    /** 0.62.0: {@code /vadmin screens [all on|off] | [on|off <name>]}: the master switch and the list of menus that keep their fallback. */
    private void screens(CommandSender sender, String[] args) {
        var config = core.getConfig();
        java.util.List<String> off = new java.util.ArrayList<>(config.getStringList("screens.off"));
        if (args.length >= 3 && (
                args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("all"))) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
            if (args[1].equalsIgnoreCase("all")) {
                if (!name.equalsIgnoreCase("on") && !name.equalsIgnoreCase("off")) {
                    lang().send(sender, "screens-admin.usage");
                    return;
                }
                config.set("screens.enabled", name.equalsIgnoreCase("on"));
                core.saveConfig();
                lang().send(sender, name.equalsIgnoreCase("on") ? "screens-admin.all-on" : "screens-admin.all-off");
                return;
            }
            boolean had = off.removeIf(entry -> entry.equalsIgnoreCase(name));
            if (args[1].equalsIgnoreCase("off")) off.add(name);
            else if (!had) {
                lang().send(sender, "screens-admin.not-listed", "name", name);
                return;
            }
            config.set("screens.off", off);
            core.saveConfig();
            lang().send(
                    sender,
                    args[1].equalsIgnoreCase("off") ? "screens-admin.switched-off" : "screens-admin.switched-on",
                    "name", name);
            return;
        }
        if (args.length > 1) {
            lang().send(sender, "screens-admin.usage");
            return;
        }
        lang().send(
                sender,
                config.getBoolean("screens.enabled", true) ? "screens-admin.status-on" : "screens-admin.status-off");
        lang().send(sender, "screens-admin.listed", "names", off.isEmpty() ? "-" : String.join(", ", off));
        var seen = Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().seen();
        lang().send(sender, "screens-admin.seen", "names", seen.isEmpty() ? "-" : String.join(", ", seen.keySet()));
        lang().send(sender, "screens-admin.usage");
    }

    private void features(CommandSender sender, String plugin) {
        if (!(sender instanceof Player p)) {
            lang().send(
                    sender, "features.ingame-only", "plugins",
                    String.join(", ", Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(sender)));
            return;
        }
        if (plugin == null) Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.openFeatures(p);
        else if (!Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.openFeatures(p, plugin))
            lang().send(p, "features.unknown-plugin", "plugin", plugin);
    }

    private void config(CommandSender sender, String plugin) {
        if (!(sender instanceof Player p)) {
            lang().send(
                    sender, "config.ingame-only", "plugins",
                    String.join(", ", Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(sender)));
            return;
        }
        if (plugin == null) Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.openOverview(p);
        else if (!Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.open(p, plugin))
            lang().send(p, "config.unknown-plugin", "plugin", plugin);
    }

    /** {@code /vadmin config history [plugin|*] [page]}: what was changed in the config editor, newest first, with undo links. */
    private void configHistory(CommandSender sender, String[] args) {
        String plugin = null;
        int page = 1;
        for (int i = 2; i < args.length && i < 4; i++) {
            if (args[i].matches("\\d{1,6}")) page = Integer.parseInt(args[i]);
            else if (!args[i].equals("*")) plugin = args[i];
        }
        Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.history(sender, plugin, page);
    }

    /** {@code /vadmin config undo <id> [force]}. */
    private void configUndo(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[2].matches("#?\\d{1,9}")) {
            lang().send(sender, "config-history.usage");
            return;
        }
        Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.undo(
                sender, Integer.parseInt(args[2].replace("#", "")),
                args.length > 3 && args[3].equalsIgnoreCase("force"));
    }

    /** {@code /vadmin trace [player]}: toggles the event trace (which plugin cancels breaks, places, clicks; message sources). */
    private void trace(CommandSender sender, String[] args) {
        // /vadmin trace [player] [off | break place damage interact messages ...]
        int i = 1;
        Player target = null;
        if (args.length > 1 && !args[1].equalsIgnoreCase("off")
                && Vystorm.vystorm_core.trace.EventTrace.Kind.parse(args[1]).isEmpty()) {
            target = core.getServer().getPlayerExact(args[1]);
            if (target == null) {
                lang().send(sender, "menu.access.online-only");
                return;
            }
            i = 2;
        } else if (sender instanceof Player p) target = p;
        if (target == null) {
            lang().send(sender, "trace.usage");
            return;
        }
        java.util.EnumSet<Vystorm.vystorm_core.trace.EventTrace.Kind> kinds =
                java.util.EnumSet.noneOf(Vystorm.vystorm_core.trace.EventTrace.Kind.class);
        for (; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("off")) {
                Vystorm.vystorm_core.trace.EventTrace.stop(target);
                lang().send(sender, "trace.off", "player", target.getName());
                return;
            }
            var kind = Vystorm.vystorm_core.trace.EventTrace.Kind.parse(args[i]);
            if (kind.isEmpty()) {
                lang().send(sender, "trace.usage");
                return;
            }
            kinds.add(kind.get());
        }
        if (kinds.isEmpty()) {
            boolean on = Vystorm.vystorm_core.trace.EventTrace.toggle(sender, target);
            lang().send(sender, on ? "trace.on" : "trace.off", "player", target.getName());
            return;
        }
        Vystorm.vystorm_core.trace.EventTrace.trace(sender, target, kinds);
        lang().send(
                sender, "trace.filter", "player", target.getName(), "kinds",
                String.join(", ", kinds.stream().map(Vystorm.vystorm_core.trace.EventTrace.Kind::id).toList()));
    }

    private void diagnostics(CommandSender viewer, String name) {
        Player target = name == null ? (viewer instanceof Player p ? p : null) : core.getServer().getPlayerExact(name);
        if (name != null && target == null) {
            lang().send(viewer, "menu.access.online-only");
            return;
        }
        for (var status : Vystorm.vystorm_core.lifecycle.FeatureDiagnostics.inspect(target))
            lang().send(
                    viewer, "diagnostics.line", "feature", status.feature(), "provider", status.provider(), "state",
                    lang().text(viewer, "diagnostics.state-" + status.state().name().toLowerCase(Locale.ROOT)),
                    "reason", i18n().resolve(viewer, status.reason()));
    }

    /** Every subcommand and argument of {@code /vadmin}; nothing without {@code vystorm_core.admin}. */
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("vystorm_core.admin") || a.length == 0) return List.of();
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("loot") && a.length >= 2) return Vystorm.vystorm_core.loot.internal.LootAdminCommand.complete(a);
        if (sub.equals("commands") && a.length >= 2) {
            String p = a[a.length - 1].toLowerCase(Locale.ROOT);
            return Vystorm.vystorm_core.commands.CommandsAdmin.complete(a).stream()
                    .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(p)).toList();
        }
        if ((sub.equals("language") || sub.equals("lang")) && a.length >= 2)
            return Vystorm.vystorm_core.lang.internal.LanguageAdmin.complete(core, a);
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBCOMMANDS);
            visible(s).stream().map(AdminModule::id).forEach(out::add);
        } else if (a.length == 2) {
            switch (sub) {
                case "language", "lang" -> {
                    return Vystorm.vystorm_core.lang.internal.LanguageAdmin.complete(core, a);
                }
                case "updates" -> out.add("check");
                case "diagnostics" -> core.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "trace" -> {
                    core.getServer().getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    out.add("off");
                    for (var kind : Vystorm.vystorm_core.trace.EventTrace.Kind.values()) out.add(kind.id());
                }
                case "migration" -> out.addAll(Vystorm.vystorm_core.storage.MigrationPreviews.keys());
                case "config" -> {
                    out.addAll(List.of("history", "undo"));
                    out.addAll(Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(s));
                }
                case "features" -> out.addAll(Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(s));
                case "screens" -> {
                    if (a.length == 2) out.addAll(List.of("all", "on", "off"));
                    else if (a.length == 3 && a[1].equalsIgnoreCase("all")) out.addAll(List.of("on", "off"));
                    else if (a.length == 3 && a[1].equalsIgnoreCase("on"))
                        out.addAll(core.getConfig().getStringList("screens.off"));
                    else if (a.length == 3) {
                        out.addAll(Vystorm.vystorm_core.screen.internal.ScreenRuntime.get().seen().keySet());
                        out.addAll(List.of("panel", "inventory"));
                        for (var pl : core.getServer().getPluginManager().getPlugins()) out.add(pl.getName());
                    }
                }
                case "news" -> out.addAll(Vystorm.vystorm_core.news.internal.NewsService.ADMIN_SUBCOMMANDS);
                case "permissions" -> PermissionList.plugins(core.getServer().getPluginManager(), core)
                        .forEach(p -> out.add(p.getName()));
                default -> {}
            }
        } else if (a.length >= 3 && sub.equals("trace")) {
            out.add("off");
            for (var kind : Vystorm.vystorm_core.trace.EventTrace.Kind.values()) out.add(kind.id());
        } else if (a.length == 3 && sub.equals("permissions")) {
            var plugin = PermissionList.plugins(core.getServer().getPluginManager(), core).stream()
                    .filter(p -> p.getName().equalsIgnoreCase(a[1])).findFirst();
            if (plugin.isPresent()) {
                int pages = Math.max(
                        1,
                        (
                                PermissionList.nodes(plugin.get(), core.getServer().getPluginManager()).size()
                                        + PermissionList.PER_PAGE - 1) / PermissionList.PER_PAGE);
                for (int i = 1; i <= pages; i++) out.add(Integer.toString(i));
            }
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().toList();
    }

    /** Subcommands handled in {@link #onCommand} besides module ids. */
    static final List<String> SUBCOMMANDS = List.of(
            "list", "status", "language", "lang", "diagnostics", "updates", "migration", "config", "features",
            "screens", "setup", "permissions", "news", "loot", "commands", "trace");

    private void open(Player p, int page) {
        if (!valid(p)) return;
        List<AdminModule> ms = visible(p);
        int pages = Math.max(1, (ms.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        List<DialogButton> bs = new ArrayList<>();
        for (AdminModule m : ms.subList(page * PER_PAGE, Math.min(ms.size(), (page + 1) * PER_PAGE)))
            bs.add(
                    new DialogButton(
                            Component.text(i18n().resolve(p, m.title()) + " — " + state(m, p)),
                            (x, r) -> openModule(x, m, 0)));
        if (!Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(p).isEmpty())
            bs.add(
                    new DialogButton(
                            lang().component(p, "config.entry"), lang().component(p, "config.entry-tooltip"),
                            (x, r) -> {
                                if (valid(x)) Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.openOverview(x);
                            }));
        bs.add(new DialogButton(lang().component(p, "diagnostics.title"), (x, r) -> {
            if (valid(x)) diagnostics(x, null);
        }));
        navigation(p, bs, page, pages, n -> open(p, n));
        Vystorm.vystorm_core.main.getApi().dialogs().open(
                core, p,
                new DialogRequest(
                        lang().component(p, "admin.title"),
                        List.of(
                                DialogBody.plainMessage(
                                        lang().component(p, "admin.page", "page", page + 1, "pages", pages))),
                        List.of(), bs, this::valid, () -> {}));
    }

    private void openModule(Player p, AdminModule m, int page) {
        if (!allowed(p, m)) return;
        List<AdminAction> as = m.actions().stream().filter(a -> p.hasPermission(a.permission())).toList();
        int pages = Math.max(1, (as.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        List<DialogButton> bs = new ArrayList<>();
        if (!m.nativeEntry().isBlank())
            bs.add(new DialogButton(lang().component(p, "admin.native"), (x, r) -> nativeEntry(x, m)));
        for (String key : settingsKeys(p, m))
            bs.add(new DialogButton(lang().component(p, "admin.server-settings"), (x, r) -> {
                if (!Vystorm.vystorm_core.main.getApi().settings().openSection(x, key))
                    lang().send(x, "admin.settings-unavailable");
            }));
        if (Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.plugins(p).stream()
                .anyMatch(n -> n.equalsIgnoreCase(m.plugin())))
            bs.add(new DialogButton(lang().component(p, "config.plugin-button", "plugin", m.plugin()), (x, r) -> {
                if (!Vystorm.vystorm_core._uiMethods.settings.ConfigEditor.open(x, m.plugin()))
                    lang().send(x, "admin.settings-unavailable");
            }));
        for (AdminAction a : as.subList(page * PER_PAGE, Math.min(as.size(), (page + 1) * PER_PAGE)))
            bs.add(new DialogButton(Component.text(label(p, m, a)), (x, r) -> prompt(x, m, a)));
        bs.add(new DialogButton(lang().component(p, "admin.overview"), (x, r) -> open(x, 0)));
        bs.add(new DialogButton(lang().component(p, "diagnostics.title"), (x, r) -> {
            if (valid(x)) diagnostics(x, null);
        }));
        navigation(p, bs, page, pages, n -> open(p, n));
        Vystorm.vystorm_core.main.getApi().dialogs().open(
                core, p,
                new DialogRequest(
                        i18n().component(p, m.title()),
                        List.of(
                                DialogBody.plainMessage(
                                        lang().component(
                                                p, "admin.module-info", "version", version(m), "command",
                                                m.command()))),
                        List.of(), bs, x -> allowed(x, m), () -> {}));
    }

    private void navigation(Player p, List<DialogButton> bs, int page, int pages, java.util.function.IntConsumer go) {
        if (page > 0) bs.add(new DialogButton(lang().component(p, "common.previous"), (x, r) -> go.accept(page - 1)));
        if (page + 1 < pages)
            bs.add(new DialogButton(lang().component(p, "common.next"), (x, r) -> go.accept(page + 1)));
    }

    /** Built-in catalog labels come from Core's language files ({@code admin.catalog.<module>.<action>}); registered modules may use {@code Lang.ref}. */
    private String label(Player p, AdminModule m, AdminAction a) {
        if (catalog.contains(m)) {
            String key = "admin.catalog." + m.id() + "." + a.id();
            if (lang().has(key)) return lang().plain(p, key);
        }
        return i18n().resolve(p, a.label());
    }

    private void nativeEntry(Player p, AdminModule m) {
        if (!allowed(p, m)) return;
        String[] bits = m.nativeEntry().trim().split(" +");
        if (bits.length == 0 || bits[0].isBlank()) return;
        executeCommand(p, m, bits[0], Arrays.copyOfRange(bits, 1, bits.length));
    }

    private void prompt(Player p, AdminModule m, AdminAction a) {
        if (!allowed(p, m) || !p.hasPermission(a.permission())) return;
        if (a.argumentHint().isBlank()) {
            confirm(p, m, a, "");
            return;
        }
        DialogInput in =
                DialogInput.text("arguments", Component.text(a.argumentHint())).initial("").maxLength(192).build();
        Vystorm.vystorm_core.main.getApi().dialogs().open(
                core, p,
                new DialogRequest(
                        Component.text(label(p, m, a)),
                        List.of(
                                DialogBody.plainMessage(
                                        lang().component(
                                                p, "admin.syntax", "command", m.command(), "fixed", a.fixedArguments(),
                                                "hint", a.argumentHint(), "example", a.example()))),
                        List.of(in),
                        List.of(
                                new DialogButton(
                                        lang().component(p, "admin.next"),
                                        (x, r) -> confirm(x, m, a, r.getText("arguments")))),
                        x -> allowed(x, m) && x.hasPermission(a.permission()), () -> {}));
    }

    private void confirm(Player p, AdminModule m, AdminAction a, String raw) {
        if (!allowed(p, m) || !p.hasPermission(a.permission()) || !safe(raw)) {
            lang().send(p, "admin.invalid-input");
            return;
        }
        String joined = (a.fixedArguments() + " " + raw.trim()).trim();
        String shown = "/" + m.command() + (joined.isBlank() ? "" : " " + joined);
        Runnable run = () -> execute(p, m, a, raw.trim());
        if (a.mutating())
            Vystorm.vystorm_core.main.getApi().dialogs().confirm(
                    core, p, lang().ref("admin.confirm-title"), shown, run, () -> {},
                    x -> allowed(x, m) && x.hasPermission(a.permission()));
        else run.run();
    }

    private void execute(Player p, AdminModule m, AdminAction a, String raw) {
        if (!allowed(p, m) || !p.hasPermission(a.permission())) {
            lang().send(p, "admin.action-gone");
            return;
        }
        String joined = (a.fixedArguments() + " " + raw).trim();
        executeCommand(p, m, m.command(), joined.isBlank() ? new String[0] : joined.split(" +"));
    }

    private void executeCommand(Player p, AdminModule m, String command, String[] args) {
        OwnedCommand pc = commandFor(m, command);
        if (pc == null) {
            lang().send(p, "admin.wrong-owner");
            return;
        }
        pc.run(p, command, args);
    }

    /** The owner's command: its plugin.yml PluginCommand, or (since 0.45.0) a tree it registered through {@code CoreCommands}. */
    private OwnedCommand commandFor(AdminModule m, String command) {
        Plugin owner = owner(m);
        if (!(owner instanceof JavaPlugin javaOwner) || !owner.isEnabled()) return null;
        PluginCommand pc = javaOwner.getCommand(command);
        if (pc != null) return pc.getPlugin() == owner ? pc::execute : null;
        String label = command.toLowerCase(java.util.Locale.ROOT);
        for (Vystorm.vystorm_core.commands.CommandRegistration r : Vystorm.vystorm_core.commands.CoreCommands.registrations(
                owner))
            if (r.label().equals(label) || r.fixedAliases().contains(label)) return r::execute;
        return null;
    }

    @FunctionalInterface
    private interface OwnedCommand {
        void run(Player player, String label, String[] args);
    }

    private Plugin owner(AdminModule m) {
        return registry.owner(m.id()).orElseGet(() -> core.getServer().getPluginManager().getPlugin(m.plugin()));
    }

    private boolean available(AdminModule m) {
        Plugin owner = owner(m);
        return owner != null && owner.isEnabled() && owner.getName().equals(m.plugin())
                && commandFor(m, m.command()) != null;
    }

    private boolean valid(Player p) {
        return core.isEnabled() && p.isOnline() && p.hasPermission("vystorm_core.admin");
    }

    private boolean allowed(Player p, AdminModule m) {
        return valid(p) && p.hasPermission(m.permission()) && available(m);
    }

    private List<AdminModule> visible(CommandSender s) {
        return all().stream().filter(m -> s.hasPermission(m.permission()) && available(m)).toList();
    }

    private String state(AdminModule m, CommandSender viewer) {
        return available(m)
                ? lang().plain(viewer, "admin.active", "version", version(m))
                : lang().plain(viewer, "admin.unavailable");
    }

    private String version(AdminModule m) {
        Plugin p = owner(m);
        return p == null ? "—" : p.getDescription().getVersion();
    }

    private AdminModule find(String id, CommandSender s) {
        return visible(s).stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    private List<AdminModule> all() {
        LinkedHashMap<String, AdminModule> out = new LinkedHashMap<>();
        for (AdminModule m : catalog) out.put(m.id(), m);
        for (AdminModule m : registry.modules()) out.put(m.id(), m);
        return List.copyOf(out.values());
    }

    private List<String> settingsKeys(Player p, AdminModule m) {
        String prefix = m.plugin().toLowerCase(Locale.ROOT) + ":";
        return Vystorm.vystorm_core.main.getApi().settings()
                        .sections(p, Vystorm.vystorm_core._uiMethods.settings.SettingScope.SERVER).isEmpty()
                ? List.of()
                : Vystorm.vystorm_core.main.getApi().settings().keys(p).stream().filter(k -> k.startsWith(prefix))
                        .filter(k -> isServer(p, k)).filter(k -> !grouped(k)).toList();
    }

    private static boolean grouped(String key) {
        return Vystorm.vystorm_core.main.getApi()
                        .settings() instanceof Vystorm.vystorm_core._uiMethods.settings.SettingsRegistry r
                && !r.group(key).isEmpty();
    }

    private boolean isServer(Player p, String key) {
        return Vystorm.vystorm_core.main.getApi().settings()
                .sections(p, Vystorm.vystorm_core._uiMethods.settings.SettingScope.SERVER).stream()
                .anyMatch(s -> key.endsWith(":" + s.id()));
    }

    static boolean safe(String v) {
        if (v == null || v.length() > 192) return false;
        String t = v.stripLeading();
        return !t.startsWith("/") && t.chars().noneMatch(c -> Character.isISOControl(c));
    }
}
