package Vystorm.vystorm_core.news.internal;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefs;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;
import Vystorm.vystorm_core.news.News;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.TextDialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Runs the news board: join notice, announcement of new posts, the news dialog with write/delete for writers,
 * {@code /vmenu news}, {@code /vadmin news} and the personal setting in {@code /vsettings}. Settings under {@code news}
 * in Core's config.yml are read live. Server thread unless noted. Not public API; plugins use {@link News}.
 */
public final class NewsService implements Listener {
    /** {@code /vadmin news} subcommands. */
    public static final List<String> ADMIN_SUBCOMMANDS = List.of("list", "write", "post", "edit", "delete");
    /** Player preferences: highest post id the player has seen; whether the join notice is shown. */
    static final String PREF_READ = "news-read";
    static final String PREF_JOIN = "news-join";
    static final int PER_PAGE = 3;
    static final int JOIN_TITLES = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static volatile NewsService active;

    private final Plugin core;
    private final API api;
    private final NewsBoard board;

    NewsService(Plugin core, API api, NewsBoard board) {
        this.core = core;
        this.api = api;
        this.board = board;
    }

    private static Lang lang() { return Localization.get().core(); }

    /** Loads {@code news.yml}, registers the join listener, the main-menu entry and the personal setting. Call once in Core's {@code onEnable}. */
    public static void start(JavaPlugin core, API api) {
        stop();
        NewsService service = new NewsService(core, api, new NewsBoard(core.getDataFolder().toPath().resolve("news.yml")));
        core.getServer().getPluginManager().registerEvents(service, core);
        active = service;
        var lang = lang();
        try {
            api.gui().registerMenu(core, "news",
                    p -> {
                        NewsService s = active;
                        int unread = s == null || p == null ? 0 : s.unread(p).size();
                        return unread > 0 ? lang.text(p, "news.menu-title-unread", "count", unread) : lang.text(p, "news.menu-title");
                    },
                    Material.WRITABLE_BOOK, p -> active != null && active.enabled() && p.hasPermission(News.PERMISSION),
                    p -> { if (active != null) active.open(p, 0); }, "", p -> lang.text(p, "news.menu-description"));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[News] Main-menu entry not registered", failure);
        }
        try {
            api.settings().register(core, SettingsSection.player("news", lang.ref("news.settings.title"), lang.ref("news.settings.description"),
                    List.of(PlayerPrefs.toggle(core, PREF_JOIN, lang.ref("news.settings.join"), lang.ref("news.settings.join-hint"), true))));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[News] Player setting not registered", failure);
        }
        DebugMessages.mid("[News] News board ready");
    }

    public static void stop() {
        NewsService s = active;
        if (s != null) HandlerList.unregisterAll(s);
        active = null;
    }

    // ------------------------------------------------------------------ settings and state

    boolean enabled() { return core.getConfig().getBoolean("news.enabled", true); }
    int maxAgeDays() { return Math.clamp(core.getConfig().getInt("news.max-age-days", 30), 0, 3650); }
    int maxPosts() { return Math.clamp(core.getConfig().getInt("news.max-posts", 20), 1, 200); }

    /** Highest post id the player has seen; ids above the board's latest (news.yml was deleted) count as nothing read. */
    int lastRead(Player player) {
        int read = (int) PlayerPrefs.number(core, player, PREF_READ, 0);
        return read > board.latestId() ? 0 : Math.max(0, read);
    }

    List<News.Post> unread(Player player) { return board.unread(lastRead(player), System.currentTimeMillis(), maxAgeDays()); }

    void markRead(Player player) { PlayerPrefs.setNumber(core, player, PREF_READ, board.latestId()); }

    /** Whether the player wants and may get notices about new posts. */
    boolean notifies(Player player) {
        return enabled() && player.isOnline() && player.hasPermission(News.PERMISSION) && PlayerPrefs.bool(core, player, PREF_JOIN, true);
    }

    // ------------------------------------------------------------------ notices

    @EventHandler
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || !core.getConfig().getBoolean("news.notify-on-join", true)) return;
        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (active != this || !notifies(player)) return;
            List<News.Post> unread = unread(player);
            if (!unread.isEmpty()) Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, joinNotice(player, unread));
        }, 100L);
    }

    private Component joinNotice(Player player, List<News.Post> unread) {
        return lang().component(player, "news.join", "count", unread.size(), "titles", titles(unread, JOIN_TITLES))
                .clickEvent(ClickEvent.runCommand("/vmenu news")).hoverEvent(lang().component(player, "news.hover"));
    }

    /** The first {@code limit} titles, comma-separated, "…" if there are more. */
    static String titles(List<News.Post> posts, int limit) {
        List<String> titles = new ArrayList<>();
        for (News.Post post : posts.subList(0, Math.min(limit, posts.size()))) titles.add("\"" + post.title() + "\"");
        return String.join(", ", titles) + (posts.size() > limit ? ", …" : "");
    }

    /** Tells online players about a new post (if {@code news.announce-new-posts}). */
    private void announce(News.Post post) {
        if (!core.getConfig().getBoolean("news.announce-new-posts", true)) return;
        for (Player player : core.getServer().getOnlinePlayers()) {
            if (!notifies(player)) continue;
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(player, lang().component(player, "news.announce", "title", post.title(), "author", post.author())
                    .clickEvent(ClickEvent.runCommand("/vmenu news")).hoverEvent(lang().component(player, "news.hover")));
        }
    }

    // ------------------------------------------------------------------ dialogs

    /** The news dialog, {@link #PER_PAGE} posts per page, newest first; opening it marks every post as read. */
    void open(Player player, int requested) {
        if (!enabled() || !player.hasPermission(News.PERMISSION)) { lang().send(player, "news.unavailable"); return; }
        List<News.Post> posts = board.visible(System.currentTimeMillis(), maxAgeDays());
        int pages = Math.max(1, (posts.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<News.Post> shown = posts.subList(Math.min(posts.size(), page * PER_PAGE), Math.min(posts.size(), (page + 1) * PER_PAGE));
        markRead(player);
        boolean writer = player.hasPermission(News.WRITE_PERMISSION);
        List<DialogBody> body = new ArrayList<>();
        if (posts.isEmpty()) body.add(DialogBody.plainMessage(lang().component(player, writer ? "news.empty-writer" : "news.empty"), 320));
        for (News.Post post : shown) body.add(DialogBody.plainMessage(render(player, post), 320));
        List<DialogButton> buttons = new ArrayList<>();
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> open(p, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> open(p, page + 1)));
        if (writer) {
            buttons.add(new DialogButton(lang().component(player, "news.write-button"), (p, r) -> write(p, "", "")));
            for (News.Post post : shown) {
                buttons.add(new DialogButton(lang().component(player, "news.edit-button", "id", post.id()),
                        (p, r) -> write(p, post.title(), post.text(), post.id())));
                buttons.add(new DialogButton(lang().component(player, "news.delete-button", "id", post.id()),
                        lang().component(player, "news.delete-tooltip", "title", post.title()), (p, r) -> confirmDelete(p, post)));
            }
        }
        Component title = lang().component(player, "news.title");
        api.dialogs().open(core, player, new DialogRequest(pages > 1 ? title.append(Component.text(" " + (page + 1) + "/" + pages)) : title,
                body, List.of(), buttons, p -> active == this && p.isOnline(), () -> {}, lang().plain(player, "dialog.close"), 2));
    }

    private Component render(Player player, News.Post post) {
        String date = Instant.ofEpochMilli(post.time()).atZone(ZoneId.systemDefault()).format(DATE);
        Component out = lang().component(player, "news.post-title", "title", post.title()).append(Component.newline())
                .append(lang().component(player, "news.post-meta", "date", date, "author", post.author()));
        if (!post.text().isEmpty()) out = out.append(Component.newline()).append(Component.text(post.text(), NamedTextColor.WHITE));
        return out;
    }

    /** Dialog to write a post; {@code title}/{@code text} prefill it after a rejected attempt. */
    void write(Player player, String title, String text) {
        write(player, title, text, 0);
    }

    private void write(Player player, String title, String text, int id) {
        if (!enabled()) { lang().send(player, "news.disabled"); return; }
        if (!player.hasPermission(News.WRITE_PERMISSION)) { lang().send(player, "news.no-permission"); return; }
        DialogInput titleInput = DialogInput.text("title", lang().component(player, "news.input-title"))
                .initial(title).maxLength(NewsBoard.MAX_TITLE).width(300).build();
        DialogInput textInput = DialogInput.text("text", lang().component(player, "news.input-text"))
                .initial(text).maxLength(NewsBoard.MAX_TEXT).width(300).multiline(TextDialogInput.MultilineOptions.create(12, 140)).build();
        api.dialogs().open(core, player, new DialogRequest(lang().component(player, id == 0 ? "news.write-title" : "news.edit-title"),
                List.of(DialogBody.plainMessage(lang().component(player, id == 0 ? "news.write-text" : "news.edit-hint"), 300)), List.of(titleInput, textInput),
                List.of(new DialogButton(lang().component(player, id == 0 ? "news.publish" : "news.save"), (p, r) -> {
                    String t = r.getText("title"), body = r.getText("text");
                    if (t == null || NewsBoard.clean(t, NewsBoard.MAX_TITLE, false).isEmpty()) {
                        lang().send(p, "news.title-missing");
                        write(p, "", body == null ? "" : body, id);
                        return;
                    }
                    if (id == 0) publish(p, p.getName(), t, body);
                    else edit(p, id, t, body);
                    open(p, 0);
                })),
                p -> active == this && p.isOnline() && p.hasPermission(News.WRITE_PERMISSION), () -> {}, lang().plain(player, "dialog.cancel"), 1));
    }

    private void confirmDelete(Player player, News.Post post) {
        api.dialogs().open(core, player, new DialogRequest(lang().component(player, "news.delete-title"),
                List.of(DialogBody.plainMessage(lang().component(player, "news.delete-question", "id", post.id(), "title", post.title()), 300)),
                List.of(), List.of(new DialogButton(lang().component(player, "dialog.confirm"), (p, r) -> {
                    delete(p, post.id());
                    open(p, 0);
                })), p -> active == this && p.isOnline() && p.hasPermission(News.WRITE_PERMISSION), () -> open(player, 0),
                lang().plain(player, "dialog.cancel"), 2));
    }

    // ------------------------------------------------------------------ actions

    /** Posts and announces; messages go to {@code sender} (null for plugin posts). */
    Optional<News.Post> publish(CommandSender sender, String author, String title, String text) {
        if (!enabled()) { if (sender != null) lang().send(sender, "news.disabled"); return Optional.empty(); }
        News.Post post;
        try { post = board.post(author, title, text, System.currentTimeMillis(), maxPosts(), maxAgeDays()); }
        catch (IllegalArgumentException empty) { if (sender != null) lang().send(sender, "news.title-missing"); return Optional.empty(); }
        DebugMessages.mid("[News] " + author + " posted #" + post.id() + " \"" + post.title() + "\"");
        if (sender != null) lang().send(sender, "news.posted", "id", post.id(), "title", post.title());
        announce(post);
        // The author has seen their own post.
        if (sender instanceof Player p && lastRead(p) == post.id() - 1) markRead(p);
        return Optional.of(post);
    }

    void edit(CommandSender sender, int id, String title, String text) {
        if (!sender.hasPermission(News.WRITE_PERMISSION)) { lang().send(sender, "news.no-permission"); return; }
        if (!enabled()) { lang().send(sender, "news.disabled"); return; }
        try {
            Optional<News.Post> updated = board.edit(id, title, text);
            if (updated.isEmpty()) { lang().send(sender, "news.unknown", "id", id); return; }
            DebugMessages.mid("[News] " + sender.getName() + " edited #" + id);
            lang().send(sender, "news.edited", "id", id, "title", updated.get().title());
        } catch (IllegalArgumentException empty) { lang().send(sender, "news.title-missing"); }
    }

    private void delete(CommandSender sender, int id) {
        Optional<News.Post> post = board.get(id);
        if (post.isEmpty() || !board.remove(id)) { lang().send(sender, "news.unknown", "id", id); return; }
        DebugMessages.mid("[News] " + sender.getName() + " deleted #" + id);
        lang().send(sender, "news.deleted", "id", id, "title", post.get().title());
    }

    /** {@code /vadmin news [list|write|post <title> | <text>|edit <id> [title | text]|delete <id>]}; caller checked admin. */
    public static void adminCommand(CommandSender sender, String[] args) {
        NewsService s = active;
        if (s == null || !s.enabled()) { lang().send(sender, "news.disabled"); return; }
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (sub) {
            case "write" -> {
                if (sender instanceof Player p) s.write(p, "", ""); else lang().send(sender, "news.usage");
            }
            case "post" -> {
                String[] parts = splitPost(String.join(" ", Arrays.copyOfRange(args, Math.min(2, args.length), args.length)));
                if (parts == null) { lang().send(sender, "news.usage"); return; }
                s.publish(sender, sender instanceof Player ? sender.getName() : "Server", parts[0], parts[1]);
            }
            case "edit" -> {
                if (!sender.hasPermission(News.WRITE_PERMISSION)) { lang().send(sender, "news.no-permission"); return; }
                if (args.length < 3 || !args[2].matches("#?\\d{1,9}")) { lang().send(sender, "news.edit-usage"); return; }
                int id = Integer.parseInt(args[2].replace("#", ""));
                Optional<News.Post> post = s.board.get(id);
                if (post.isEmpty()) { lang().send(sender, "news.unknown", "id", id); return; }
                if (args.length == 3 && sender instanceof Player p) {
                    s.write(p, post.get().title(), post.get().text(), id);
                    return;
                }
                String[] parts = splitPost(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                if (parts == null) { lang().send(sender, "news.edit-usage"); return; }
                s.edit(sender, id, parts[0], parts[1]);
            }
            case "delete", "remove" -> {
                if (args.length < 3 || !args[2].matches("#?\\d{1,9}")) { lang().send(sender, "news.usage"); return; }
                s.delete(sender, Integer.parseInt(args[2].replace("#", "")));
            }
            case "list" -> s.list(sender);
            default -> lang().send(sender, "news.usage");
        }
    }

    private void list(CommandSender sender) {
        List<News.Post> posts = board.visible(System.currentTimeMillis(), maxAgeDays());
        if (posts.isEmpty()) { lang().send(sender, "news.list-empty"); return; }
        lang().send(sender, "news.list-header", "count", posts.size());
        for (News.Post post : posts) {
            String date = Instant.ofEpochMilli(post.time()).atZone(ZoneId.systemDefault()).format(DATE);
            Vystorm.vystorm_core.compat.PlatformCompat.sendMessage(sender, lang().component(sender, "news.list-line", "id", post.id(), "date", date, "author", post.author(), "title", post.title())
                    .append(Component.space()).append(lang().component(sender, "news.list-delete")
                            .clickEvent(ClickEvent.suggestCommand("/vadmin news delete " + post.id()))
                            .hoverEvent(lang().component(sender, "news.list-delete-hover"))));
        }
    }

    /** {@code "Title | text"} → {title, text}; text may be empty. Null if there is no title. */
    static String[] splitPost(String raw) {
        if (raw == null) return null;
        int bar = raw.indexOf('|');
        String title = (bar < 0 ? raw : raw.substring(0, bar)).trim();
        String text = bar < 0 ? "" : raw.substring(bar + 1).trim().replace("\\n", "\n");
        return title.isEmpty() ? null : new String[] {title, text};
    }

    // ------------------------------------------------------------------ static access for News

    /** Any thread: the post is stored at once; the announcement runs on the server thread. */
    public static Optional<News.Post> postFromPlugin(Plugin plugin, String title, String text) {
        NewsService s = active;
        if (s == null || !s.enabled()) return Optional.empty();
        if (Bukkit.isPrimaryThread()) return s.publish(null, plugin.getName(), title, text);
        News.Post post;
        try { post = s.board.post(plugin.getName(), title, text, System.currentTimeMillis(), s.maxPosts(), s.maxAgeDays()); }
        catch (IllegalArgumentException empty) { throw new IllegalArgumentException("title is blank"); }
        if (s.core.isEnabled()) s.core.getServer().getScheduler().runTask(s.core, () -> s.announce(post));
        return Optional.of(post);
    }

    public static List<News.Post> currentPosts() {
        NewsService s = active;
        return s == null || !s.enabled() ? List.of() : s.board.visible(System.currentTimeMillis(), s.maxAgeDays());
    }

    public static int unreadCount(Player player) {
        NewsService s = active;
        return s == null || player == null || !s.enabled() ? 0 : s.unread(player).size();
    }

    public static boolean openFor(Player player) {
        NewsService s = active;
        if (s == null || player == null || !s.enabled() || !player.hasPermission(News.PERMISSION)) return false;
        s.open(player, 0);
        return true;
    }
}
