package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The rows of one ranking, declared with {@link PlaceholderNamespace#ranking(String, RankingInfo, RankingSource)}.
 * Usually a lambda: {@code n -> store.top(n)}.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Core calls a source on the server thread only.</li>
 *   <li>A source answers from memory: no file or database read, no chunk loading, no player-file reads in the call.
 *       It also lists offline players it knows.</li>
 *   <li>{@code n} is 1 to 100. A source returns at most the best {@code n} rows; Core sorts what it gets by the
 *       ranking's {@link RankingInfo.Order} (stable), drops {@code null} rows and values that are not finite, and cuts
 *       the list to {@code n}.</li>
 *   <li>A source that throws is logged once per ranking and server run, and the ranking counts as empty.</li>
 * </ul>
 *
 * @since 0.67.0
 */
@FunctionalInterface
public interface RankingSource {
    /**
     * One line of a ranking.
     *
     * @param player the player's UUID (for heads and highlighting the viewer)
     * @param name the last known name; {@code null} is shown as an empty name
     * @param value the raw value (count, milliseconds, ticks, blocks)
     */
    record Row(UUID player, String name, double value) {
        public Row {
            Objects.requireNonNull(player, "player");
            name = name == null ? "" : name;
        }
    }

    /** The best {@code n} rows (1 to 100), best first. */
    List<Row> top(int n);

    /**
     * The value of one player, also when he is not among the best 100. Empty (the default) lets Core look the player
     * up in {@code top(100)}.
     */
    default OptionalDouble value(UUID player) { return OptionalDouble.empty(); }

    /**
     * The rank of one player (1 = best), also when he is not among the best 100. Empty (the default) lets Core look
     * the player up in {@code top(100)}.
     */
    default OptionalInt rank(UUID player) { return OptionalInt.empty(); }
}
