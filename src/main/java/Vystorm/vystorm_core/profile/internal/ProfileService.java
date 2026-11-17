package Vystorm.vystorm_core.profile.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.profile.Profiles;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Opens profile cards and registers the main-menu and player-menu entries. Not public API; plugins use {@link Profiles}. */
public final class ProfileService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static volatile JavaPlugin core;
    private static volatile API api;

    private ProfileService() {}

    private static Lang lang() { return Localization.get().core(); }

    public static void start(JavaPlugin plugin, API coreApi) {
        core = plugin;
        api = coreApi;
        var lang = lang();
        try {
            coreApi.gui().registerMenu(plugin, "profile", lang.ref("profile.menu-title"), Material.PLAYER_HEAD,
                    p -> enabled() && p.hasPermission(Profiles.PERMISSION), p -> open(p, p), "", lang.ref("profile.menu-description"));
        } catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Profile] Main-menu entry not registered", failure); }
        try {
            Vystorm.vystorm_core.playermenu.PlayerMenu.register(plugin, Vystorm.vystorm_core.playermenu.PlayerMenuEntry.builder("profile").priority(200)
                    .icon((viewer, target) -> {
                        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
                        lang().name(item, viewer, "profile.player-menu", "player", target.getName());
                        return item;
                    })
                    .visibleWhen((viewer, target) -> enabled() && viewer.hasPermission(Profiles.PERMISSION))
                    .onClick((viewer, target, click) -> { open(viewer, target); return null; }).build());
        } catch (RuntimeException | LinkageError failure) { DebugMessages.high("[Profile] Player-menu entry not registered", failure); }
    }

    static boolean enabled() { return core == null || core.getConfig().getBoolean("profile.enabled", true); }

    /** {@code /vmenu profile [name]}. */
    public static void command(Player viewer, String name) {
        if (!enabled() || !viewer.hasPermission(Profiles.PERMISSION)) { lang().send(viewer, "profile.unavailable"); return; }
        if (name == null || name.isBlank()) { open(viewer, viewer); return; }
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Vystorm.vystorm_core.compat.PlatformCompat.getOfflinePlayerIfCached(name);
        if (target == null || !target.hasPlayedBefore() && !target.isOnline()) { lang().send(viewer, "profile.unknown", "player", name); return; }
        open(viewer, target);
    }

    public static void open(Player viewer, OfflinePlayer target) {
        if (api == null || core == null) return;
        var lang = lang();
        String name = target.getName() == null ? "?" : target.getName();
        List<DialogBody> body = new ArrayList<>();
        Component basics = lang.component(viewer, target.isOnline() ? "profile.online" : "profile.offline", "player", name);
        if (target.getFirstPlayed() > 0) basics = basics.append(Component.newline()).append(lang.component(viewer, "profile.first-join", "date", date(target.getFirstPlayed())));
        if (!target.isOnline() && target.getLastPlayed() > 0) basics = basics.append(Component.newline()).append(lang.component(viewer, "profile.last-seen", "date", date(target.getLastPlayed())));
        if (target instanceof Player online) {
            try {
                long hours = online.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 3600L;
                basics = basics.append(Component.newline()).append(lang.component(viewer, "profile.playtime", "hours", hours));
            } catch (RuntimeException unsupported) { /* statistic not available */ }
        }
        body.add(DialogBody.plainMessage(basics, 320));
        var sections = Profiles.sections(viewer, target);
        if (sections.isEmpty()) body.add(DialogBody.plainMessage(lang.component(viewer, "profile.no-sections"), 320));
        for (var section : sections) {
            Component text = Component.text(section.getKey(), NamedTextColor.GOLD);
            for (Component line : section.getValue()) text = text.append(Component.newline()).append(line.colorIfAbsent(NamedTextColor.GRAY));
            body.add(DialogBody.plainMessage(text, 320));
        }
        api.dialogs().open(core, viewer, new DialogRequest(lang.component(viewer, "profile.title", "player", name), body, List.of(), List.of(),
                p -> p.isOnline(), () -> {}, lang.plain(viewer, "dialog.close"), 1));
    }

    private static String date(long millis) { return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE); }
}
