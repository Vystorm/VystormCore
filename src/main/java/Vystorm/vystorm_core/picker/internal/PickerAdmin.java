package Vystorm.vystorm_core.picker.internal;

import Vystorm.vystorm_core.knownplayers.KnownPlayers;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /vadmin players pick [multi] [tab=online|nearby|recent|search] [search=TEXT] [page=N]} (0.71.0): in game it
 * opens a demo picker (all four tabs; {@code multi}: up to 10 ticks, with a rule that greys out the admin's own row
 * when {@code self} is given) and prints the choice in chat; from the console it prints the chest the picker would
 * show (slot, item, name, lore) with the server's online players and known players, so the layout can be checked
 * without a client. Core-internal.
 */
final class PickerAdmin {
    private PickerAdmin() {}

    static void handle(PickerRuntime rt, CommandSender sender, String[] args) {
        Lang lang = PickerRuntime.lang();
        boolean multi = false, self = false;
        PlayerSource tab = null;
        String search = null;
        int page = 1, tick = 0;
        for (int i = 2; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.equals("multi")) multi = true;
            else if (a.equals("self")) self = true;
            else if (a.startsWith("tab=")) {
                try { tab = PlayerSource.valueOf(a.substring(4).toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException bad) { lang.send(sender, "pickers.demo.usage"); return; }
            } else if (a.startsWith("search=")) search = args[i].substring(7);
            else if (a.startsWith("page=")) {
                try { page = Math.max(1, Integer.parseInt(a.substring(5))); } catch (NumberFormatException bad) { lang.send(sender, "pickers.demo.usage"); return; }
            } else if (a.startsWith("tick=")) {
                try { tick = Math.max(0, Integer.parseInt(a.substring(5))); } catch (NumberFormatException bad) { lang.send(sender, "pickers.demo.usage"); return; }
            } else {
                lang.send(sender, "pickers.demo.usage");
                return;
            }
        }
        if (!rt.available()) {
            lang.send(sender, "pickers.switched-off");
            return;
        }
        if (sender instanceof Player player) {
            rt.players(rt.core, player, demo(player, multi, self), PickReturn.none());
            return;
        }
        dump(rt, sender, multi, tab, search, page, tick);
    }

    static PlayerPick demo(Player admin, boolean multi, boolean self) {
        Lang lang = PickerRuntime.lang();
        PlayerPick.Builder b = PlayerPick.builder(lang.ref("pickers.demo.title")).subtitle(lang.ref("pickers.demo.subtitle"))
                .includeSelf(self)
                .rule((viewer, c) -> self && c.id().equals(viewer.getUniqueId()) ? PickVerdict.deny(lang.ref("pickers.demo.not-yourself")) : PickVerdict.ok())
                .onPick((viewer, picks) -> Result.ok(lang.plain(viewer, "pickers.demo.picked", "players",
                        picks.chosen().stream().map(PickedPlayer::name).collect(Collectors.joining(", ")),
                        "added", picks.added().size(), "removed", picks.removed().size())));
        if (multi) b.multiple(10);
        return b.build();
    }

    /** Console: the chest as text; {@code tick} ticks the first rows of the page (multi), to show the ticked look. */
    static void dump(PickerRuntime rt, CommandSender sender, boolean multi, PlayerSource tab, String search, int page, int tick) {
        Lang lang = PickerRuntime.lang();
        PickerConfig cfg = rt.config();
        UUID console = new UUID(0, 0);
        PickerModel model = new PickerModel(console, List.of(PlayerSource.values()), multi, multi ? 10 : 1, false, cfg.defaultNearby(), Set.of(),
                c -> true, c -> PickVerdict.ok(), new PickerModel.Limits(cfg.searchMinChars(), cfg.searchMaxHits(), PickerConfig.RECENT_LIMIT));
        PickerModel.Sources src = consoleSources(cfg);
        if (search != null) model.query(search);
        model.refresh(src);
        model.select(tab != null ? tab : search != null ? PlayerSource.SEARCH : model.firstTab());
        model.page(page - 1, PickerModel.CHEST_PAGE);
        if (multi) {
            int ticked = 0;
            for (PickerModel.Row r : model.rows(PickerModel.CHEST_PAGE)) if (ticked++ < tick) model.toggle(r.candidate().id());
        }
        List<PickerChestLayout.Slot> slots = PickerChestLayout.layout(model, new PickerChestLayout.Context(true, System.currentTimeMillis(), ZoneId.systemDefault(), null, null));
        lang.send(sender, "pickers.demo.dump-head", "tab", model.tab().name().toLowerCase(Locale.ROOT), "online", model.count(PlayerSource.ONLINE),
                "recent", model.count(PlayerSource.RECENT), "search", model.count(PlayerSource.SEARCH), "known", KnownPlayers.size(), "slots", slots.size());
        for (PickerChestLayout.Slot s : slots) {
            List<String> lore = new ArrayList<>();
            for (PickerText.Line line : s.lore()) lore.add(plain(sender, line));
            sender.sendMessage("slot " + s.slot() + ": " + PickerChest.material(s.icon()).name() + (s.glint() ? "*" : "") + " \"" + plain(sender, s.name()) + "\" " + lore);
        }
    }

    static String plain(CommandSender sender, PickerText.Line line) {
        if (line.key() == null) return String.valueOf(line.raw());
        return PickerRuntime.lang().plain(sender, line.key(), line.args());
    }

    private static PickerModel.Sources consoleSources(PickerConfig cfg) {
        return new PickerModel.Sources() {
            @Override public List<PickerModel.Online> online() {
                List<PickerModel.Online> out = new ArrayList<>();
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) out.add(new PickerModel.Online(p.getUniqueId(), p.getName(), true, -1, PickerRuntime.skin(p.getUniqueId())));
                return out;
            }
            @Override public List<PickerModel.Candidate> recent() {
                return KnownPlayers.recent(java.time.Duration.ofDays(cfg.recentDays()), PickerConfig.RECENT_LIMIT).stream().map(PickerRuntime::offline).toList();
            }
            @Override public List<PickerModel.Candidate> search(String prefix, int limit) { return KnownPlayers.search(prefix, limit).stream().map(PickerRuntime::offline).toList(); }
            @Override public PickerModel.Candidate lookup(UUID id) { return KnownPlayers.get(id).map(PickerRuntime::offline).orElse(null); }
        };
    }
}
