package Vystorm.vystorm_core.lang;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.NonNull;

/**
 * Fired on the server thread after the language Core uses for a player changed or the player made a new choice:
 * {@code /language}, {@code /vsettings} → Language, an admin's {@code /language <player> <code>}, a changed Minecraft
 * language while the choice is "Automatic", or {@code /vadmin language reload}. Core has already stored the choice and
 * switched its own texts; listen to rebuild things you rendered once per player (scoreboards, boss bars, holograms,
 * open menus, cached item lore):
 *
 * <pre>{@code
 * @EventHandler
 * public void onLanguage(PlayerLanguageChangeEvent event) {
 *     boards.redraw(event.getPlayer());          // lang.text(player, …) now returns the new language
 * }
 * }</pre>
 *
 * Not cancellable. Capability {@code CoreApi.Capability.LANGUAGE_SELECTION}.
 *
 * @since 0.45.0
 */
public final class PlayerLanguageChangeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    /** What triggered the change. */
    public enum Cause {
        /** The player used {@code /language}. */
        COMMAND,
        /** The player changed it in {@code /vsettings} → Language. */
        SETTINGS,
        /** An admin used {@code /language <player> <code>}. */
        ADMIN,
        /** The player's Minecraft language changed while their choice is "Automatic". */
        CLIENT_LOCALE,
        /** {@code /vadmin language reload} (files or {@code language.*} settings changed). */
        RELOAD,
        /** Another plugin through {@link CoreLanguage#setChoice}. */
        API
    }

    private final String previousLanguage;
    private final String language;
    private final String choice;
    private final Cause cause;

    public PlayerLanguageChangeEvent(@NonNull Player player, String previousLanguage, String language, String choice, Cause cause) {
        super(player);
        this.previousLanguage = previousLanguage == null ? Localization.FALLBACK : previousLanguage;
        this.language = language == null ? Localization.FALLBACK : language;
        this.choice = choice == null || choice.isBlank() ? Localization.AUTO : choice;
        this.cause = cause == null ? Cause.API : cause;
    }

    /** Language code Core's texts used before ({@code en}, {@code de}, {@code zh_cn}, …). */
    public String getPreviousLanguage() { return previousLanguage; }

    /** Language code Core's texts use now. Your own bundle may resolve differently: use {@code lang.language(player)}. */
    public String getLanguage() { return language; }

    /** The player's stored choice: a code or {@code auto} (follow the Minecraft language). */
    public String getChoice() { return choice; }

    public Cause getCause() { return cause; }

    /** Whether the language actually differs (false when, e.g., the choice moved from {@code auto} to the same code). */
    public boolean isLanguageChanged() { return !previousLanguage.equals(language); }

    @Override public @NonNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
