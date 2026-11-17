package Vystorm.vystorm_core.curve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CurveTest {
    @Test
    void parsesAllTypes() {
        assertEquals(5, Curve.fromMap(Map.of("type", "linear", "slope", 2, "offset", 1)).apply(2), 1e-9);
        assertEquals(8, Curve.fromMap(Map.of("type", "power", "exponent", 3)).apply(2), 1e-9);
        assertEquals(0.5, Curve.fromMap(Map.of("type", "softcap", "max", 1, "half", 4)).apply(4), 1e-9);
        assertEquals(0.5, Curve.fromMap(Map.of("type", "logistic", "mid", 3)).apply(3), 1e-9);
        assertEquals(Math.log(2), Curve.fromMap(Map.of("type", "log", "divisor", 5)).apply(5), 1e-9);
        Curve points = Curve.fromMap(Map.of("type", "points", "points", List.of(List.of(0, 0), List.of(1, 10), List.of(3, 20))));
        assertEquals(0, points.apply(-1), 1e-9);
        assertEquals(5, points.apply(0.5), 1e-9);
        assertEquals(15, points.apply(2), 1e-9);
        assertEquals(20, points.apply(9), 1e-9);
    }

    @Test
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Curve.fromMap(Map.of("type", "nope")));
        assertThrows(IllegalArgumentException.class, () -> Curve.fromMap(Map.of("type", "softcap", "half", 0)));
        assertThrows(IllegalArgumentException.class, () -> Curve.fromMap(Map.of("type", "points", "points", List.of(List.of(1, 0), List.of(1, 1)))));
    }

    @Test
    void relativeStatCapsAndExplains() {
        CurveRegistry.register("test.ratio", new Curve.Linear(2, 0));
        var result = RelativeStat.resolve(new RelativeStat.Input(30, 10, "test.ratio", 0, 5));
        assertEquals(3, result.ratio(), 1e-9);
        assertEquals(5, result.finalValue(), 1e-9);
        assertTrue(result.capped());
        assertTrue(result.breakdown().contains("capped"));
        assertEquals(1e6 * 0, RelativeStat.resolve(new RelativeStat.Input(0, 0, "missing", 0, 1)).finalValue(), 1e-9);
        CurveRegistry.unregisterPrefix("test.");
        assertTrue(CurveRegistry.get("test.ratio").isEmpty());
    }
}
