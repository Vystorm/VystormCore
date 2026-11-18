package Vystorm.vystorm_core._advancedMethods.placeholderHandler.internal;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderBridge;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The one expansion Core registers with PlaceholderAPI: identifier {@code vystorm}, answering every placeholder of
 * Core's registry ({@code %vystorm_<namespace>_<key>%}, and {@code %rel_vystorm_<namespace>_<key>%} between two
 * players).
 * <p>
 * This is the only class of Core that refers to PlaceholderAPI's types. It is loaded by name from
 * {@link PlaceholderApiBridge} after that found the plugin enabled, so Core runs unchanged on servers without it.
 * It stores nothing: no file and no configuration in PlaceholderAPI's folder.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
public final class PlaceholderApiExpansion extends PlaceholderExpansion implements Relational {
    private final String identifier;
    private final String author;
    private final String version;
    private final PlaceholderBridge.Source source;

    public PlaceholderApiExpansion(String identifier, String author, String version, PlaceholderBridge.Source source) {
        this.identifier = identifier;
        this.author = author;
        this.version = version;
        this.source = source;
    }

    @Override public String getIdentifier() { return identifier; }
    @Override public String getAuthor() { return author; }
    @Override public String getVersion() { return version; }

    /** Stay registered when PlaceholderAPI reloads its own expansions ({@code /papi reload}). */
    @Override public boolean persist() { return true; }

    @Override public boolean canRegister() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) { return source.request(player, null, params); }

    /** Entry of PlaceholderAPI versions that do not call {@link #onRequest}. */
    @Override public String onPlaceholderRequest(Player player, String params) { return source.request(player, null, params); }

    @Override public String onPlaceholderRequest(Player one, Player two, String params) { return source.request(one, two, params); }
}
