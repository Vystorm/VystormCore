package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.Objects;
import org.bukkit.plugin.Plugin;

/**
 * A declared ranking as {@link PlaceholderInterface#rankings()} lists it: the namespace and key that make its id
 * {@code <namespace>:<key>}, what it is and the plugin that declared it.
 *
 * @param namespace the placeholder namespace, lower case
 * @param key the ranking key, lower case (letters, digits and {@code -})
 * @param info label, description, order and format
 * @param owner the plugin that owns the namespace
 * @since 0.67.0
 */
public record PlaceholderRanking(String namespace, String key, RankingInfo info, Plugin owner) {
    public PlaceholderRanking {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(owner, "owner");
    }

    /** {@code <namespace>:<key>}, the id that {@link PlaceholderInterface#top(String, int)} and Boards take. */
    public String id() { return namespace + ":" + key; }

    /** {@code %vystorm_<namespace>_top_<key>_<n>_<field>%}, field {@code name}, {@code value}, {@code raw} or {@code uuid}. */
    public String topPlaceholder(int n, String field) {
        return "%" + PlaceholderInterface.IDENTIFIER + "_" + namespace + "_top_" + key + "_" + n + "_" + field + "%";
    }

    /** {@code %vystorm_<namespace>_rank_<key>%}: the viewer's rank. */
    public String rankPlaceholder() { return "%" + PlaceholderInterface.IDENTIFIER + "_" + namespace + "_rank_" + key + "%"; }

    /** {@code %vystorm_<namespace>_value_<key>%}: the viewer's value, formatted. */
    public String valuePlaceholder() { return "%" + PlaceholderInterface.IDENTIFIER + "_" + namespace + "_value_" + key + "%"; }
}
