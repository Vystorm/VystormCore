package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * What a ranking is, declared with {@link PlaceholderNamespace#ranking(String, RankingInfo, RankingSource)}: its label
 * and description (language references such as {@code lang.ref("rankings.mined.label")} or plain text), which end
 * of the list is best ({@link Order}) and how values are written ({@link Format}). Immutable; every {@code with}
 * method returns a copy.
 *
 * <pre>{@code
 * RankingInfo info = RankingInfo.of(lang.ref("rankings.sled-best.label"), lang.ref("rankings.sled-best.description"))
 *         .order(RankingInfo.Order.ASCENDING)
 *         .format(RankingInfo.Format.DURATION_MILLIS);
 * }</pre>
 *
 * @since 0.67.0
 */
public final class RankingInfo {
    /** Which end of the list is best. */
    public enum Order {
        /** Highest value first (most blocks mined). The default. */
        DESCENDING,
        /** Lowest value first (fastest time). */
        ASCENDING
    }

    /**
     * How a value is written in {@code %vystorm_<ns>_top_<key>_<n>_value%}, {@code ..._value_<key>%} and
     * {@code /vadmin placeholders rankings}. Thousands and decimal separators follow the viewer's language
     * (English {@code 1,234.50}, German {@code 1.234,50}, French {@code 1 234,50} ...).
     */
    public enum Format {
        /** A whole number with thousands separators: {@code 12,345}. The default. */
        NUMBER,
        /** Two decimals: {@code 1,234.50}. */
        DECIMAL,
        /** Milliseconds as a time: {@code 1:05.250}, from one hour {@code 1:02:03.004}. */
        DURATION_MILLIS,
        /** Server ticks (20 a second) as a time, written like {@link #DURATION_MILLIS}. */
        DURATION_TICKS,
        /** Blocks as a distance: {@code 850 m}, from 1000 blocks {@code 1.25 km}. */
        DISTANCE_BLOCKS;

        /** The value in English style. */
        public String format(double value) { return format(value, "en"); }

        /**
         * The value for a language code ({@code en}, {@code de}, {@code fr}, {@code it}, {@code pt_br}, {@code ru},
         * {@code zh_cn}; others are written in English style). A value that is not finite is written as {@code -}.
         */
        public String format(double value, String language) {
            if (!Double.isFinite(value)) return "-";
            Separators separators = Separators.of(language);
            return switch (this) {
                case NUMBER -> grouped(Math.round(value), separators);
                case DECIMAL -> decimal(value, separators);
                case DURATION_MILLIS -> duration(Math.round(value), separators);
                case DURATION_TICKS -> duration(Math.round(value * 50.0), separators);
                case DISTANCE_BLOCKS -> distance(value, separators);
            };
        }
    }

    private record Separators(String group, String decimal) {
        static final Separators ENGLISH = new Separators(",", ".");
        static final Separators DOT_COMMA = new Separators(".", ",");
        static final Separators SPACE_COMMA = new Separators(" ", ",");

        static Separators of(String language) {
            String code = language == null ? "" : language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            int cut = code.indexOf('_');
            String base = cut < 0 ? code : code.substring(0, cut);
            return switch (base) {
                case "de", "it", "pt", "es", "nl", "tr", "id" -> DOT_COMMA;
                case "fr", "ru", "pl", "cs", "sk", "uk", "sv", "fi", "nb", "no" -> SPACE_COMMA;
                default -> ENGLISH;
            };
        }
    }

    private static String grouped(long value, Separators separators) {
        if (value == Long.MIN_VALUE) return Long.toString(value);
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder(digits.length() + 8);
        if (value < 0) out.append('-');
        int first = digits.length() % 3;
        if (first == 0) first = 3;
        out.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) out.append(separators.group()).append(digits, i, i + 3);
        return out.toString();
    }

    private static String decimal(double value, Separators separators) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        BigDecimal magnitude = rounded.abs();
        long whole = magnitude.longValue();
        int cents = magnitude.subtract(BigDecimal.valueOf(whole)).movePointRight(2).intValue();
        return (negative ? "-" : "") + grouped(whole, separators) + separators.decimal() + (cents < 10 ? "0" : "") + cents;
    }

    private static String duration(long millis, Separators separators) {
        if (millis < 0) return "-" + duration(millis == Long.MIN_VALUE ? Long.MAX_VALUE : -millis, separators);
        long hours = millis / 3_600_000L;
        long minutes = millis / 60_000L % 60;
        long seconds = millis / 1000L % 60;
        long rest = millis % 1000L;
        String tail = two(seconds) + "." + (rest < 10 ? "00" : rest < 100 ? "0" : "") + rest;
        return hours > 0 ? hours + ":" + two(minutes) + ":" + tail : minutes + ":" + tail;
    }

    private static String two(long value) { return value < 10 ? "0" + value : Long.toString(value); }

    private static String distance(double blocks, Separators separators) {
        if (Math.abs(blocks) < 999.5) return grouped(Math.round(blocks), separators) + " m";
        return decimal(blocks / 1000.0, separators) + " km";
    }

    private final String label;
    private final String description;
    private final Order order;
    private final Format format;
    private final String unit;

    private RankingInfo(String label, String description, Order order, Format format, String unit) {
        this.label = label;
        this.description = description;
        this.order = order;
        this.format = format;
        this.unit = unit;
    }

    /**
     * A ranking with this label and description, highest value first, written as a whole number.
     *
     * @param label short name ("Ore mined"), plain text or a language reference; required
     * @param description one line about what is counted; plain text or a language reference; may be null
     */
    public static RankingInfo of(String label, String description) {
        Objects.requireNonNull(label, "label");
        return new RankingInfo(label, description == null ? "" : description, Order.DESCENDING, Format.NUMBER, "");
    }

    /** Which end of the list is best; {@code null} gives {@link Order#DESCENDING}. */
    public RankingInfo order(Order order) {
        return new RankingInfo(label, description, order == null ? Order.DESCENDING : order, format, unit);
    }

    /** Same as {@code order(Order.ASCENDING)}: lowest value first. */
    public RankingInfo ascending() { return order(Order.ASCENDING); }

    /** How values are written; {@code null} gives {@link Format#NUMBER}. */
    public RankingInfo format(Format format) {
        return new RankingInfo(label, description, order, format == null ? Format.NUMBER : format, unit);
    }

    /**
     * An optional unit label ("blocks", "photos"; plain text or a language reference) that consumers may show next to
     * the value. Placeholders never add it: {@code ..._value%} is the formatted number alone.
     */
    public RankingInfo unit(String unit) {
        return new RankingInfo(label, description, order, format, unit == null ? "" : unit);
    }

    public String label() { return label; }
    /** The description, or an empty string. */
    public String description() { return description; }
    public Order order() { return order; }
    public Format format() { return format; }
    /** The unit label, or an empty string. */
    public String unit() { return unit; }

    @Override public boolean equals(Object other) {
        return other instanceof RankingInfo that && label.equals(that.label) && description.equals(that.description)
                && order == that.order && format == that.format && unit.equals(that.unit);
    }

    @Override public int hashCode() { return Objects.hash(label, description, order, format, unit); }

    @Override public String toString() { return "RankingInfo[" + label + ", " + order + ", " + format + "]"; }
}
