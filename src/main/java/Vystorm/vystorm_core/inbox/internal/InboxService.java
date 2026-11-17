package Vystorm.vystorm_core.inbox.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.compat.PlatformCompat;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.inbox.Inbox;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the inbox: storage, chat notice for online recipients, join notice, main-menu entry, the dialog and the
 * personal setting. Settings under {@code inbox} in Core's config.yml are read live. Not public API; plugins use
 * {@link Inbox}.
 */
public final class InboxService implements Listener {
    static final String PREF_JOIN = "inbox-join";
    static final int PER_PAGE = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static volatile InboxService active;

    private final Plugin core;
    private final API api;
    final InboxStore store;

    InboxService(Plugin core, API api, InboxStore store) {
        this.core = core;
        this.api = api;
        this.store = store;
    }

    private static Lang lang() { return Localization.get().core(); }

    /** Registers listener, main-menu entry and setting. Call once in Core's {@code onEnable}. */
    public static void start(JavaPlugin core, API api) {
        stop();
        InboxService service = new InboxService(core, api, new InboxStore(core.getDataFolder().toPath().resolve("inbox")));
        core.getServer().getPluginManager().registerEvents(service, core);
        active = service;
        var lang = lang();
        try {
            api.gui().registerMenu(core, "inbox",
                    p -> {
                        InboxService s = active;
                        int unread = s == null || p == null ? 0 : s.unread(p);
                        return unread > 0 ? lang.text(p, "inbox.menu-title-unread", "count", unread) : lang.text(p, "inbox.menu-title");
                    },
                    Material.CHEST_MINECART, p -> active != null && active.enabled() && p.hasPermission(Inbox.PERMISSION),
                    p -> { if (active != null) active.open(p, 0); }, "", p -> lang.text(p, "inbox.menu-description"));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Inbox] Main-menu entry not registered", failure);
        }
        try {
            api.settings().register(core, SettingsSection.player("inbox", lang.ref("inbox.settings.title"), lang.ref("inbox.settings.description"),
                    List.of(PlayerPrefs.toggle(core, PREF_JOIN, lang.ref("inbox.settings.join"), lang.ref("inbox.settings.join-hint"), true))));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[Inbox] Player setting not registered", failure);
        }
        DebugMessages.mid("[Inbox] Inbox ready");
    }

    public static void stop() {
        InboxService s = active;
        if (s != null) HandlerList.unregisterAll(s);
        active = null;
    }

    boolean enabled() { return core.getConfig().getBoolean("inbox.enabled", true); }
    int maxAgeDays() { return Math.clamp(core.getConfig().getInt("inbox.max-age-days", 30), 0, 3650); }
    int maxPerPlayer() { return Math.clamp(core.getConfig().getInt("inbox.max-per-player", 50), 1, 500); }

    int unread(Player player) { return store.unread(player.getUniqueId(), System.currentTimeMillis(), maxAgeDays()); }

    private boolean notifies(Player player) {
        return enabled() && player.isOnline() && player.hasPermission(Inbox.PERMISSION) && PlayerPrefs.bool(core, player, PREF_JOIN, true);
    }

    // ------------------------------------------------------------------ sending

    private boolean store(Plugin plugin, UUID player, String namespace, String key, List<String> args, String text) {
        if (!enabled()) return false;
        InboxStore.Message message = store.add(player, System.currentTimeMillis(), plugin.getName(), namespace, key, args, text, maxPerPlayer(), maxAgeDays());
        DebugMessages.low("[Inbox] " + plugin.getName() + " -> " + player + " #" + message.id());
        Runnable notice = () -> {
            Player online = Bukkit.getPlayer(player);
            if (online == null || active != this || !notifies(online)) return;
            PlatformCompat.sendMessage(online, lang().component(online, "inbox.arrived", "source", message.source())
                    .append(Component.space()).append(render(online, message))
                    .clickEvent(ClickEvent.runCommand("/vmenu inbox")).hoverEvent(lang().component(online, "inbox.hover")));
        };
        if (Bukkit.isPrimaryThread()) notice.run();
        else if (core.isEnabled()) core.getServer().getScheduler().runTask(core, notice);
        return true;
    }

    public static boolean sendKey(Plugin plugin, UUID player, String key, Object... placeholders) {
        InboxService s = active;
        if (s == null) return false;
        List<String> args = new ArrayList<>();
        if (placeholders != null) for (Object o : placeholders) args.add(String.valueOf(o));
        if (args.size() % 2 == 1) args.removeLast();
        return s.store(plugin, player, Localization.get().bundle(plugin).map(Lang::namespace).orElse(plugin.getName().toLowerCase(java.util.Locale.ROOT)),
                key, args, "");
    }

    public static boolean sendText(Plugin plugin, UUID player, String text) {
        InboxService s = active;
        return s != null && s.store(plugin, player, "", "", List.of(), text);
    }

    public static int unreadCount(Player player) {
        InboxService s = active;
        return s == null || player == null || !s.enabled() ? 0 : s.unread(player);
    }

    public static boolean openFor(Player player) {
        InboxService s = active;
        if (s == null || player == null || !s.enabled() || !player.hasPermission(Inbox.PERMISSION)) return false;
        s.open(player, 0);
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || !core.getConfig().getBoolean("inbox.notify-on-join", true)) return;
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (active != this || !notifies(player)) return;
            int unread = unread(player);
            if (unread > 0) PlatformCompat.sendMessage(player, lang().component(player, "inbox.join", "count", unread)
                    .clickEvent(ClickEvent.runCommand("/vmenu inbox")).hoverEvent(lang().component(player, "inbox.hover")));
        }, 120L);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) { store.evict(event.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ dialog

    /** Text of a message for the viewer: from the sender's language bundle, else the plain text or the key. */
    static Component render(Player viewer, InboxStore.Message message) {
        if (message.plain()) return Component.text(message.text(), NamedTextColor.WHITE);
        var bundle = Localization.get().bundle(message.namespace());
        if (bundle.isEmpty() || !bundle.get().has(message.key())) return Component.text(message.key(), NamedTextColor.GRAY);
        return bundle.get().component(viewer, message.key(), message.args().toArray());
    }

    void open(Player player, int requested) {
        if (!enabled() || !player.hasPermission(Inbox.PERMISSION)) { lang().send(player, "inbox.unavailable"); return; }
        UUID id = player.getUniqueId();
        List<InboxStore.Message> messages = store.messages(id, System.currentTimeMillis(), maxAgeDays());
        int pages = Math.max(1, (messages.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<InboxStore.Message> shown = messages.subList(Math.min(messages.size(), page * PER_PAGE), Math.min(messages.size(), (page + 1) * PER_PAGE));
        List<DialogBody> body = new ArrayList<>();
        if (messages.isEmpty()) body.add(DialogBody.plainMessage(lang().component(player, "inbox.empty"), 320));
        for (InboxStore.Message m : shown) {
            String date = Instant.ofEpochMilli(m.time()).atZone(ZoneId.systemDefault()).format(DATE);
            Component line = lang().component(player, m.read() ? "inbox.meta" : "inbox.meta-new", "date", date, "source", m.source())
                    .append(Component.newline()).append(render(player, m));
            body.add(DialogBody.plainMessage(line, 320));
        }
        store.markRead(id);
        List<DialogButton> buttons = new ArrayList<>();
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> open(p, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> open(p, page + 1)));
        if (!messages.isEmpty()) buttons.add(new DialogButton(lang().component(player, "inbox.delete-read"), (p, r) -> {
            int removed = store.deleteRead(p.getUniqueId());
            lang().send(p, "inbox.deleted", "count", removed);
            open(p, 0);
        }));
        Component title = lang().component(player, "inbox.title");
        api.dialogs().open(core, player, new DialogRequest(pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                body, List.of(), buttons, p -> active == this && p.isOnline(), () -> {}, lang().plain(player, "dialog.close"), 2));
    }
}
