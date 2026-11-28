package Vystorm.vystorm_core.knownplayers.internal;

import Vystorm.vystorm_core.lang.Lang;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * {@code /vadmin players [find <prefix> | forget <name|uuid> | pick [multi]]} (0.71.0). {@code find} lists up to 20
 * known players whose name starts with the prefix, with UUID, exact last-seen time and a mark for players who are not
 * findable by others; {@code forget} removes one (privacy request); {@code pick} opens the player picker as a demo
 * (in game) or prints the chest it would show (console). Works from the console. Core-internal.
 */
public final class KnownPlayersAdmin {
    public static final List<String> SUBCOMMANDS = List.of("find", "forget", "pick");
    static final int FIND_LINES = 20;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** The picker part of {@code /vadmin players pick}; set by the picker runtime (null = not available). */
    public static volatile java.util.function.BiConsumer<CommandSender, String[]> pickCommand;

    private KnownPlayersAdmin() {}

    public static void handle(CommandSender sender, Plugin core, String[] args) {
        Lang lang = KnownPlayersRuntime.lang();
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("pick")) {
            var pick = pickCommand;
            if (pick == null) lang.send(sender, "known-players.admin.pick-off");
            else pick.accept(sender, args);
            return;
        }
        KnownPlayerStore store = KnownPlayersRuntime.store();
        if (store == null) {
            lang.send(sender, "known-players.admin.store-off");
            return;
        }
        switch (sub) {
            case "find" -> {
                if (args.length < 3 || args[2].isBlank()) {
                    lang.send(sender, "known-players.admin.usage");
                    return;
                }
                find(sender, lang, store, args[2]);
            }
            case "forget" -> {
                if (args.length < 3) {
                    lang.send(sender, "known-players.admin.usage");
                    return;
                }
                Optional<KnownPlayerStore.Entry> target = resolve(store, args[2]);
                if (target.isEmpty()) lang.send(sender, "known-players.admin.unknown", "player", args[2]);
                else if (store.forget(target.get().id())) lang.send(sender, "known-players.admin.forgotten", "player", target.get().name(), "uuid", target.get().id());
            }
            default -> {
                lang.send(sender, "known-players.admin.status", "count", store.size(),
                        "state", lang.plain(sender, KnownPlayersRuntime.loaded() ? "known-players.admin.loaded" : "known-players.admin.loading"));
                lang.send(sender, "known-players.admin.usage");
            }
        }
    }

    static void find(CommandSender sender, Lang lang, KnownPlayerStore store, String prefix) {
        List<KnownPlayerStore.Entry> hits = store.search(prefix, FIND_LINES + 1, true);
        boolean more = hits.size() > FIND_LINES;
        if (more) hits = hits.subList(0, FIND_LINES);
        lang.send(sender, "known-players.admin.find-head", "prefix", prefix, "count", hits.size() + (more ? "+" : ""), "total", store.size());
        for (KnownPlayerStore.Entry e : hits) {
            lang.send(sender, "known-players.admin.find-line", "player", e.name(), "uuid", e.id(), "seen", when(e.lastSeen()),
                    "hidden", e.hidden() ? lang.plain(sender, "known-players.admin.hidden") : "");
        }
        if (hits.isEmpty()) lang.send(sender, "known-players.admin.none", "prefix", prefix);
    }

    static Optional<KnownPlayerStore.Entry> resolve(KnownPlayerStore store, String nameOrId) {
        try {
            return store.get(UUID.fromString(nameOrId));
        } catch (IllegalArgumentException notAnId) {
            return store.byName(nameOrId);
        }
    }

    static String when(long epochMs) { return WHEN.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())); }

    /** Tab completion after {@code /vadmin players}. */
    public static List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) out.addAll(SUBCOMMANDS);
        else if (args.length == 3 && args[1].equalsIgnoreCase("pick")) out.add("multi");
        else if (args.length == 3 && args[1].equalsIgnoreCase("forget")) {
            KnownPlayerStore store = KnownPlayersRuntime.store();
            if (store != null && args[2].length() >= 1) store.search(args[2], 20, true).forEach(e -> out.add(e.name()));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
