package Vystorm.vystorm_core.menus.internal;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * The texts of a file menu for one viewer. A text is MiniMessage with the tags the format allows (colours, bold,
 * italic, underlined, strikethrough, reset); every other tag stays visible as written, so a file cannot carry click
 * or hover events. The template is parsed first and the values of {@code {player}}, {@code {page}}, {@code {pages}}
 * and of the placeholders ({@code api.placeholders()}) are put in as plain text, so a value never becomes formatting.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public interface MenuText {
    /** MiniMessage limited to the tags of the format. */
    MiniMessage MINI = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(),
            StandardTags.decorations(TextDecoration.BOLD), StandardTags.decorations(TextDecoration.ITALIC),
            StandardTags.decorations(TextDecoration.UNDERLINED), StandardTags.decorations(TextDecoration.STRIKETHROUGH),
            StandardTags.reset())).build();

    /** The text as a component for this viewer. */
    Component component(String text);

    /** The text without formatting for this viewer. */
    default String plain(String text) {
        return text == null || text.isEmpty() ? "" : PlainTextComponentSerializer.plainText().serialize(component(text));
    }

    /**
     * Texts for a viewer called {@code name} on {@code page} of {@code pages}; {@code placeholders} may be null (then
     * {@code %...%} stays as written) and {@code viewer} may be null (tests, the console).
     */
    static MenuText of(Player viewer, String name, int page, int pages, PlaceholderInterface placeholders) {
        TagResolver own = TagResolver.resolver(
                Placeholder.unparsed("vystorm_menu_player", name == null ? "" : name),
                Placeholder.unparsed("vystorm_menu_page", Integer.toString(page)),
                Placeholder.unparsed("vystorm_menu_pages", Integer.toString(pages)));
        return text -> {
            if (text == null || text.isEmpty()) return Component.empty();
            String template = text;
            TagResolver values = TagResolver.empty();
            if (placeholders != null && (template.indexOf('%') >= 0 || template.indexOf('{') >= 0)) {
                try {
                    PlaceholderInterface.Prepared prepared = placeholders.miniMessage(viewer, null, template);
                    template = prepared.text();
                    values = prepared.resolver();
                } catch (RuntimeException | LinkageError failed) {
                    // the text is shown with its placeholders as written
                }
            }
            template = template.replace("{player}", "<vystorm_menu_player>").replace("{page}", "<vystorm_menu_page>").replace("{pages}", "<vystorm_menu_pages>");
            return MINI.deserialize(template, TagResolver.resolver(values, own));
        };
    }
}
