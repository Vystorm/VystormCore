package Vystorm.vystorm_core.actions.internal;

import Vystorm.vystorm_core.actions.ActionLogEntry;
import Vystorm.vystorm_core.actions.ActionLogInterface;
import Vystorm.vystorm_core.actions.LogQuery;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * {@code /vadmin actions [plugin] [page] [filter]}: the audit logs of the action runner, newest first, also from the
 * console. Without a plugin: the plugins with an audit log in this server run. Core-internal.
 */
public final class ActionsAdmin {
    static final int PAGE = 10;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private ActionsAdmin() {}

    public static List<String> plugins() { return ActionRunner.plugins(); }

    public static void handle(CommandSender sender, String[] args) {
        var lang = Vystorm.vystorm_core.lang.Localization.get().core();
        if (args.length < 2) {
            List<String> names = plugins();
            lang.send(sender, "actions.admin.plugins", "plugins", names.isEmpty() ? "-" : String.join(", ", names));
            lang.send(sender, "actions.admin.usage");
            return;
        }
        ActionLogInterface log = ActionRunner.existing(args[1]);
        if (log == null) { lang.send(sender, "actions.admin.unknown", "plugin", args[1]); return; }
        if (!log.readNode().isEmpty() && !ActionRunner.allowed(sender, log.readNode())) { lang.send(sender, "actions.admin.denied", "node", log.readNode()); return; }
        int page = 1;
        String filter = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].matches("\\d{1,5}")) page = Integer.parseInt(args[i]);
            else filter = filter == null ? args[i] : filter + " " + args[i];
        }
        int shownPage = page;
        String plugin = args[1];
        log.query(new LogQuery(filter, page, PAGE), entries -> {
            lang.send(sender, "actions.admin.header", "plugin", plugin, "page", shownPage);
            if (entries.isEmpty()) lang.send(sender, "actions.admin.empty");
            for (String line : lines(entries)) sender.sendMessage(line);
        });
    }

    /** Plain console lines; values are already escaped by the log format (no colour codes, no line breaks). */
    static List<String> lines(List<ActionLogEntry> entries) {
        List<String> out = new ArrayList<>();
        for (ActionLogEntry e : entries) {
            StringBuilder b = new StringBuilder(WHEN.format(e.time())).append("  ").append(ActionLogFormat.escape(e.actorName())).append("  ")
                    .append(ActionLogFormat.escape(e.action()));
            if (!e.world().isEmpty()) b.append(" [").append(ActionLogFormat.escape(e.world())).append(']');
            if (!e.target().isEmpty()) b.append(" -> ").append(ActionLogFormat.escape(e.target()));
            if (!e.before().isEmpty() || !e.after().isEmpty()) b.append("  ").append(ActionLogFormat.escape(e.before())).append(" -> ").append(ActionLogFormat.escape(e.after()));
            b.append("  ").append(ActionLogFormat.escape(e.result()));
            if (!e.message().isEmpty()) b.append(" (").append(ActionLogFormat.escape(e.message())).append(')');
            if (!e.command().isEmpty()) b.append("  ").append(ActionLogFormat.escape(e.command()));
            out.add(b.toString());
        }
        return out;
    }
}
