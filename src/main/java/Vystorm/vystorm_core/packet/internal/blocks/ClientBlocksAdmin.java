package Vystorm.vystorm_core.packet.internal.blocks;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.packet.ClientBlocksStats;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/**
 * {@code /vadmin packets [sets]}: client block counts per plugin, the sets that declared collision changes and (with
 * {@code sets}) every open set. Called by the admin dashboard (permission checked there). Server thread.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class ClientBlocksAdmin {
    static final List<String> SUBCOMMANDS = List.of("sets", "view");
    private static final int MAX_ROWS = 40;

    private ClientBlocksAdmin() {}

    private static Lang lang() { return Localization.get().core(); }

    /** {@code args} as typed after {@code /vadmin}: {@code packets ...}. */
    public static void handle(CommandSender sender, String[] args) {
        Lang lang = lang();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        ClientBlocksRuntime rt = ClientBlocksRuntime.get();
        ClientBlocksStats s = rt.stats();
        if (!sub.isEmpty() && !sub.equals("sets")) {
            lang.send(sender, "packets.usage");
            return;
        }
        lang.send(sender, "packets.status", "sets", s.sets(), "viewers", s.viewers(), "cells", s.cells(),
                "budget", rt.viewerBudget(), "total", rt.totalBudget());
        lang.send(sender, "packets.counters", "sent", s.cellsSent(), "batches", s.batches(), "singles", s.singleBatches(),
                "restores", s.restores(), "repairs", s.repairs(), "blocked", s.blocked(), "refused", s.refused());
        if (s.owners().isEmpty()) {
            lang.send(sender, "packets.none");
            return;
        }
        for (ClientBlocksStats.Owner o : s.owners())
            lang.send(sender, o.collisionSets() > 0 ? "packets.owner-collision" : "packets.owner", "plugin", o.plugin(),
                    "sets", o.sets(), "cells", o.cells(), "viewers", o.viewers(), "collision", o.collisionSets());
        List<String[]> rows = rt.setRows();
        int shown = 0;
        for (String[] r : rows) {
            boolean collision = r[7].equals("collision");
            if (!sub.equals("sets") && !collision) continue;
            if (shown++ == MAX_ROWS) {
                lang.send(sender, "packets.more", "count", rows.size() - MAX_ROWS);
                break;
            }
            lang.send(sender, collision ? "packets.set-collision" : "packets.set", "plugin", r[0], "name", r[1],
                    "priority", r[2], "cells", r[3], "viewers", r[4], "pending", r[5],
                    "kind", lang.text(sender, "packets.kind-" + r[6]));
        }
    }

    public static List<String> complete(String[] args) {
        if (args.length == 2) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(p)).toList();
        }
        return List.of();
    }
}
