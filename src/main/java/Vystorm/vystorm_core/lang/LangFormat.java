package Vystorm.vystorm_core.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a template into a component: legacy {@code &x}/{@code §x} codes become MiniMessage tags, named placeholders
 * {@code {name}} become literal values, then MiniMessage parses the result.
 */
final class LangFormat {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.\\-]{1,64})}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Map<Character, String> LEGACY = new HashMap<>();

    static {
        String[] colors = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
                "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        String hex = "0123456789abcdef";
        for (int i = 0; i < 16; i++) LEGACY.put(hex.charAt(i), "<reset><" + colors[i] + ">");
        LEGACY.put('k', "<obfuscated>");
        LEGACY.put('l', "<bold>");
        LEGACY.put('m', "<strikethrough>");
        LEGACY.put('n', "<underlined>");
        LEGACY.put('o', "<italic>");
        LEGACY.put('r', "<reset>");
    }

    private LangFormat() {}

    /** Converts {@code &c}/{@code §c} codes to MiniMessage tags (a color resets formatting, as in legacy text). */
    static String legacyToMini(String text) {
        if (text == null || (text.indexOf('&') < 0 && text.indexOf('§') < 0)) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                String tag = LEGACY.get(Character.toLowerCase(text.charAt(i + 1)));
                if (tag != null) { out.append(tag); i++; continue; }
            }
            out.append(c);
        }
        String converted = out.toString();
        return converted.startsWith("<reset>") ? converted.substring("<reset>".length()) : converted;
    }

    /** @param placeholders name/value pairs; an odd trailing element is ignored */
    static Component format(String template, Object[] placeholders) {
        String text = legacyToMini(template == null ? "" : template);
        List<TagResolver> resolvers = new ArrayList<>();
        if (placeholders != null && placeholders.length >= 2) {
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i + 1 < placeholders.length; i += 2)
                if (placeholders[i] != null) values.put(String.valueOf(placeholders[i]), placeholders[i + 1]);
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            Map<String, String> tags = new HashMap<>();
            while (m.find()) {
                String name = m.group(1);
                if (!values.containsKey(name)) { m.appendReplacement(out, Matcher.quoteReplacement(m.group())); continue; }
                String tag = tags.computeIfAbsent(name, n -> {
                    String t = "vph" + tags.size();
                    resolvers.add(resolver(t, values.get(n)));
                    return t;
                });
                m.appendReplacement(out, Matcher.quoteReplacement("<" + tag + ">"));
            }
            m.appendTail(out);
            text = out.toString();
        }
        try {
            return resolvers.isEmpty() ? MINI.deserialize(text) : MINI.deserialize(text, TagResolver.resolver(resolvers));
        } catch (RuntimeException failure) {
            return Component.text(template == null ? "" : template);
        }
    }

    private static TagResolver resolver(String tag, Object value) {
        if (value instanceof ComponentLike component) return Placeholder.component(tag, component);
        String text = value == null ? "" : String.valueOf(value);
        if (text.indexOf('§') >= 0) return Placeholder.component(tag, LegacyComponentSerializer.legacySection().deserialize(text));
        return Placeholder.unparsed(tag, text);
    }
}
