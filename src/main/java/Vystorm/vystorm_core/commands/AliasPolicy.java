package Vystorm.vystorm_core.commands;

import java.util.Locale;

/**
 * Which localized root aliases ({@code commands.<cmd>.aliases} in the plugins' language files) Core registers. Command
 * labels are global on a server, so this is a server-wide choice ({@code config.yml} {@code commands.localized-aliases},
 * {@code /vadmin commands mode}). The English label and the fixed aliases are always registered.
 *
 * @since 0.45.0
 */
public enum AliasPolicy {
    /** Aliases of every installed language. */
    ALL,
    /** Only aliases of the server language ({@code language.default}, or {@code language.server} when that is auto). */
    DEFAULT,
    /** No localized root aliases. */
    NONE;

    /** Parses {@code all}, {@code default}, {@code none} (case-insensitive); anything else is {@code fallback}. */
    public static AliasPolicy parse(String text, AliasPolicy fallback) {
        if (text == null) return fallback;
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "all", "true", "on" -> ALL;
            case "default", "server" -> DEFAULT;
            case "none", "off", "false" -> NONE;
            default -> fallback;
        };
    }

    public String key() { return name().toLowerCase(Locale.ROOT); }
}
