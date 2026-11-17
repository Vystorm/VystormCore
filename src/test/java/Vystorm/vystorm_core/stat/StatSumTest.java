package Vystorm.vystorm_core.stat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static Vystorm.vystorm_core.stat.StatSum.Operation.*;

class StatSumTest {
    @Test void givenEquipmentAndBonuses_whenResolved_thenAllThreeStagesApply() {
        StatSum sum = new StatSum().put("head", ADD, 3).put("chest", ADD, 8)
                .put("skill", MULTIPLY, .1).put("buff", MULTIPLY, .2)
                .put("world", MULTIPLY_TOTAL, 1.1).put("event", MULTIPLY_TOTAL, 1.2);
        assertEquals(18.876, sum.value(), 1e-10);
        sum.put("head", ADD, 5);
        assertEquals(22.308, sum.value(), 1e-10);
        assertTrue(sum.remove("chest"));
        assertEquals(8.58, sum.value(), 1e-10);
    }

    @Test void givenSnapshot_whenOwnerChanges_thenSnapshotRemainsIndependent() {
        StatSum sum = new StatSum().put("head", ADD, 3);
        var snapshot = sum.contributions();
        sum.clear();
        assertEquals(0, sum.value());
        assertEquals(3, new StatSum(snapshot).value());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test void givenInvalidOrOverflowingContributions_whenResolved_thenFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new StatSum().put("", ADD, 1));
        assertThrows(IllegalArgumentException.class, () -> new StatSum().put("bad", ADD, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new StatSum().put("bad", MULTIPLY, Double.POSITIVE_INFINITY));
        StatSum sum = new StatSum().put("base", ADD, Double.MAX_VALUE).put("boost", MULTIPLY_TOTAL, 2);
        assertThrows(ArithmeticException.class, sum::value);
    }

    @Test void givenDebuffAndZeroFactor_whenResolved_thenNegativeAndZeroValuesAreSupported() {
        StatSum sum = new StatSum().put("base", ADD, 100).put("penalty", ADD, -20).put("slow", MULTIPLY, -.25);
        assertEquals(60, sum.value());
        sum.put("disabled", MULTIPLY_TOTAL, 0);
        assertEquals(0, sum.value());
    }

    @Test void givenDifferentInsertionOrders_whenResolved_thenValuesMatch() {
        StatSum first = new StatSum().put("a", ADD, 1e16).put("b", ADD, -1e16).put("c", ADD, 1);
        StatSum second = new StatSum().put("c", ADD, 1).put("b", ADD, -1e16).put("a", ADD, 1e16);
        assertEquals(first.value(), second.value());
    }
}
