package Vystorm.vystorm_core._advancedMethods.placeholderHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo.Format;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo.Order;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The value types of rankings and how each {@link Format} writes a value. */
class RankingInfoTest {
    @Test
    void givenNewInfo_whenNotChanged_thenDescendingNumberWithoutUnit() {
        RankingInfo info = RankingInfo.of("Ore mined", null);
        assertEquals(Order.DESCENDING, info.order());
        assertEquals(Format.NUMBER, info.format());
        assertEquals("", info.description());
        assertEquals("", info.unit());
        assertThrows(NullPointerException.class, () -> RankingInfo.of(null, "x"));
    }

    @Test
    void givenWithers_whenChained_thenCopiesAndOriginalUnchanged() {
        RankingInfo base = RankingInfo.of("Best run", "Fastest sled run");
        RankingInfo changed = base.ascending().format(Format.DURATION_MILLIS).unit("s");
        assertEquals(Order.DESCENDING, base.order());
        assertEquals(Order.ASCENDING, changed.order());
        assertEquals(Format.DURATION_MILLIS, changed.format());
        assertEquals("s", changed.unit());
        assertEquals(changed, base.order(Order.ASCENDING).format(Format.DURATION_MILLIS).unit("s"));
        assertNotEquals(base, changed);
        assertEquals(Order.DESCENDING, changed.order(null).order());
        assertEquals(Format.NUMBER, changed.format(null).format());
    }

    @Test
    void givenNumber_whenFormatted_thenGroupedWholeNumberPerLanguage() {
        assertEquals("0", Format.NUMBER.format(0));
        assertEquals("999", Format.NUMBER.format(999));
        assertEquals("1,000", Format.NUMBER.format(1000));
        assertEquals("1,234,568", Format.NUMBER.format(1234567.6));
        assertEquals("-12,345", Format.NUMBER.format(-12345));
        assertEquals("1.234.567", Format.NUMBER.format(1234567, "de"));
        assertEquals("1.234.567", Format.NUMBER.format(1234567, "pt_br"));
        assertEquals("1.234.567", Format.NUMBER.format(1234567, "it"));
        assertEquals("1 234 567", Format.NUMBER.format(1234567, "fr"));
        assertEquals("1 234 567", Format.NUMBER.format(1234567, "ru"));
        assertEquals("1,234,567", Format.NUMBER.format(1234567, "zh_cn"));
        assertEquals("1,234,567", Format.NUMBER.format(1234567, null));
        assertEquals("-", Format.NUMBER.format(Double.NaN));
        assertEquals("-", Format.NUMBER.format(Double.POSITIVE_INFINITY));
    }

    @Test
    void givenDecimal_whenFormatted_thenTwoDecimalsGrouped() {
        assertEquals("0.00", Format.DECIMAL.format(0));
        assertEquals("1,234.50", Format.DECIMAL.format(1234.5));
        assertEquals("1.234,50", Format.DECIMAL.format(1234.5, "de"));
        assertEquals("0.07", Format.DECIMAL.format(0.065));
        assertEquals("-3.10", Format.DECIMAL.format(-3.1));
        assertEquals("1,000.00", Format.DECIMAL.format(999.999));
    }

    @Test
    void givenMillis_whenFormatted_thenMinutesBelowAnHourAndHoursFromThen() {
        assertEquals("0:00.000", Format.DURATION_MILLIS.format(0));
        assertEquals("0:05.250", Format.DURATION_MILLIS.format(5250));
        assertEquals("1:05.007", Format.DURATION_MILLIS.format(65007));
        assertEquals("59:59.999", Format.DURATION_MILLIS.format(3_599_999));
        assertEquals("1:00:00.000", Format.DURATION_MILLIS.format(3_600_000));
        assertEquals("1:02:03.004", Format.DURATION_MILLIS.format(3_723_004));
        assertEquals("25:00:00.000", Format.DURATION_MILLIS.format(90_000_000));
        assertEquals("-0:01.500", Format.DURATION_MILLIS.format(-1500));
    }

    @Test
    void givenTicks_whenFormatted_thenTwentyTicksAreOneSecond() {
        assertEquals("0:01.000", Format.DURATION_TICKS.format(20));
        assertEquals("1:00.050", Format.DURATION_TICKS.format(1201));
        assertEquals("1:00:00.000", Format.DURATION_TICKS.format(72_000));
    }

    @Test
    void givenBlocks_whenFormatted_thenMetresBelowAThousandAndKilometresFromThen() {
        assertEquals("0 m", Format.DISTANCE_BLOCKS.format(0));
        assertEquals("850 m", Format.DISTANCE_BLOCKS.format(850.4));
        assertEquals("999 m", Format.DISTANCE_BLOCKS.format(999.4));
        assertEquals("1.00 km", Format.DISTANCE_BLOCKS.format(999.6));
        assertEquals("1.25 km", Format.DISTANCE_BLOCKS.format(1250));
        assertEquals("12,345.68 km", Format.DISTANCE_BLOCKS.format(12_345_678));
        assertEquals("1,25 km", Format.DISTANCE_BLOCKS.format(1250, "de"));
    }

    @Test
    void givenRow_whenNameMissing_thenEmptyNameAndPlayerRequired() {
        UUID id = UUID.randomUUID();
        assertEquals("", new RankingSource.Row(id, null, 1).name());
        assertThrows(NullPointerException.class, () -> new RankingSource.Row(null, "Alex", 1));
    }
}
