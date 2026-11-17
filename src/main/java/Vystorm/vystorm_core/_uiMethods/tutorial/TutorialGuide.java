package Vystorm.vystorm_core._uiMethods.tutorial;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.lang.Localization;

/**
 * Read-only plugin guides; menu ownership and dialog lifecycle remain in Core. Content: {@code tutorial.yml} in the
 * plugin jar (default) plus optional translations {@code tutorial_<code>.yml} (e.g. {@code tutorial_en.yml}). A player
 * gets the file of their language (see {@code lang.Localization}); without one, {@code tutorial.yml}, except that
 * players whose language Core does not know (English fallback) get {@code tutorial_en.yml} when it exists.
 */
public final class TutorialGuide {
    private static final int TOPICS_PER_PAGE = 12;
    /** {@code gif}: optional build animation in the plugin jar (path), shown on map tiles in the screen viewer (0.59.0). */
    record Page(String title, String text, String permission, String gif) {
        Page(String title, String text, String permission) { this(title, text, permission, null); }
    }
    record Content(String title, String permission, List<Page> pages) {}
    private final API api;
    private final Plugin owner;
    private final Content content;
    private final Map<String, Content> translations;

    private TutorialGuide(API api, Plugin owner, Content content, Map<String, Content> translations) {
        this.api = api;
        this.owner = owner;
        this.content = content;
        this.translations = translations;
    }

    private static Lang lang() { return Localization.get().core(); }

    /** The content for the viewer's language. */
    Content content(Player player) {
        if (translations.isEmpty()) return content;
        return pick(translations, content, Localization.get().wantedLanguages(player), Localization.get().language(player));
    }

    static Content pick(Map<String, Content> translations, Content fallback, List<String> wanted, String coreLanguage) {
        for (String code : wanted) {
            Content exact = translations.get(code);
            if (exact != null) return exact;
            Content part = translations.get(Localization.languagePart(code));
            if (part != null) return part;
        }
        if (Localization.FALLBACK.equals(coreLanguage) && translations.containsKey(Localization.FALLBACK)) return translations.get(Localization.FALLBACK);
        return fallback;
    }

    public static void register(API api, Plugin owner) {
        try (var resource = owner.getResource("tutorial.yml")) {
            if (resource == null) throw new IllegalArgumentException("tutorial.yml is missing");
            Content base = read(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Content> translations = new java.util.LinkedHashMap<>();
            for (String code : TRANSLATION_CODES) {
                try (var translated = owner.getResource("tutorial_" + code + ".yml")) {
                    if (translated == null) continue;
                    try { translations.put(code, read(new String(translated.readAllBytes(), StandardCharsets.UTF_8))); }
                    catch (Exception invalid) { DebugMessages.high("[TutorialGuide] tutorial_" + code + ".yml of " + owner.getName() + " ignored: " + invalid.getMessage()); }
                }
            }
            TutorialGuide guide = new TutorialGuide(api, owner, base, Map.copyOf(translations));
            api.gui().registerMenu(owner, "tutorial", p -> guide.contentTitle(p, guide.content(p)), Material.BOOK,
                    p -> guide.allowed(p) && !guide.visible(p).isEmpty(), p -> guide.index(p, 0), "Tutorials", p -> "");
            DebugMessages.mid("[TutorialGuide] Guide registered: " + owner.getName()
                    + "; topics=" + guide.content.pages().size() + "; translations=" + translations.keySet());
        } catch (Exception failure) {
            throw new IllegalArgumentException("Tutorial of " + owner.getName() + " is invalid", failure);
        }
    }

    static Content read(String yaml) throws org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration data = new YamlConfiguration();
        data.loadFromString(yaml);
        String title = required(data.get("title"), "title");
        String permission = data.getString("permission", "");
        List<Page> pages = new ArrayList<>();
        for (Map<?, ?> page : data.getMapList("pages")) {
            pages.add(new Page(required(page.get("title"), "page.title"),
                    required(page.get("text"), "page.text"),
                    page.get("permission") instanceof String value ? value : "",
                    page.get("gif") instanceof String gif && !gif.isBlank() ? gif.strip() : null));
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("tutorial has no topics");
        return new Content(title, permission, List.copyOf(pages));
    }

    private static String required(Object value, String key) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("tutorial: " + key + " is missing");
        return text.strip();
    }
    /** Probed {@code tutorial_<code>.yml} resources. */
    static final List<String> TRANSLATION_CODES = List.of("en", "de", "fr", "es", "it", "nl", "pl", "pt", "pt_br", "ru", "tr",
            "cs", "sv", "da", "fi", "no", "hu", "uk", "ja", "ko", "zh_cn", "zh_tw");

    private String contentTitle(Player player, Content content) { return lang().plain(player, "tutorial.menu-title", "title", content.title()); }
    private boolean allowed(Player player) {
        return owner.isEnabled() && player.isOnline() && permits(player, content(player).permission());
    }
    private static boolean permits(Player player, String permission) {
        return permission.isBlank() || player.hasPermission(permission);
    }
    private List<Page> visible(Player player) {
        return content(player).pages().stream().filter(p -> permits(player, p.permission())).toList();
    }

    /** Title of the guide in the viewer's language. */
    String title(Player player) { return content(player).title(); }

    /** The Tutorials category of the main menu. */
    void directory(Player player) { api.gui().openDirectory(player, 0, "Tutorials"); }

    /** Screens first (0.59.0): the topics with their build GIFs; false = use the dialogs. */
    private boolean screen(Player player, int position) {
        try {
            if (!Vystorm.vystorm_core.main.getPlugin().getConfig().getBoolean("screens.tutorials", true)) return false;
            List<Page> visible = visible(player);
            if (visible.isEmpty()) return false;
            return new TutorialScreen(Vystorm.vystorm_core.main.getPlugin(), owner, this).open(player, visible, Math.max(0, Math.min(position, visible.size() - 1)));
        } catch (RuntimeException | LinkageError failure) {
            DebugMessages.high("[TutorialGuide] screen failed, using the dialog: " + failure);
            return false;
        }
    }

    private void index(Player player, int requested) {
        if (requested == 0 && allowed(player) && screen(player, 0)) return;
        if (!allowed(player)) return;
        List<Page> visible = visible(player);
        if (visible.isEmpty()) return;
        int pages = (visible.size() + TOPICS_PER_PAGE - 1) / TOPICS_PER_PAGE;
        int page = Math.max(0, Math.min(requested, pages - 1));
        List<DialogButton> buttons = new ArrayList<>();
        for (int i = page * TOPICS_PER_PAGE; i < Math.min(visible.size(), (page + 1) * TOPICS_PER_PAGE); i++) {
            Page topic = visible.get(i);
            buttons.add(new DialogButton(Component.text(topic.title()), (p, response) -> topic(p, topic)));
        }
        if (page > 0) buttons.add(new DialogButton(lang().component(player, "tutorial.topics-back"), (p, r) -> index(p, page - 1)));
        if (page + 1 < pages) buttons.add(new DialogButton(lang().component(player, "tutorial.topics-more"), (p, r) -> index(p, page + 1)));
        buttons.add(new DialogButton(lang().component(player, "tutorial.all-tutorials"), (p, r) -> api.gui().openDirectory(p, 0, "Tutorials")));
        api.dialogs().open(owner, player, new DialogRequest(Component.text(contentTitle(player, content(player))),
                List.of(DialogBody.plainMessage(lang().component(player, "tutorial.choose", "page", page + 1, "pages", pages))),
                List.of(), buttons, this::allowed, () -> api.gui().openDirectory(player, 0, "Tutorials")));
    }

    private void topic(Player player, Page topic) {
        if (!allowed(player) || !permits(player, topic.permission())) return;
        List<Page> visible = visible(player);
        int position = visible.indexOf(topic);
        if (position < 0) return;
        List<DialogButton> buttons = new ArrayList<>();
        if (position > 0) {
            Page previous = visible.get(position - 1);
            buttons.add(new DialogButton(lang().component(player, "common.previous"), (p, r) -> topic(p, previous)));
        }
        if (position + 1 < visible.size()) {
            Page next = visible.get(position + 1);
            buttons.add(new DialogButton(lang().component(player, "common.next"), (p, r) -> topic(p, next)));
        }
        buttons.add(new DialogButton(lang().component(player, "tutorial.all-topics"), (p, r) -> index(p, position / TOPICS_PER_PAGE)));
        buttons.add(new DialogButton(lang().component(player, "tutorial.all-tutorials"), (p, r) -> api.gui().openDirectory(p, 0, "Tutorials")));
        api.dialogs().open(owner, player, new DialogRequest(Component.text(content(player).title() + " | " + topic.title()),
                List.of(DialogBody.plainMessage(Component.text(topic.text()))), List.of(), buttons,
                p -> allowed(p) && permits(p, topic.permission()), () -> index(player, position / TOPICS_PER_PAGE)));
    }
}
