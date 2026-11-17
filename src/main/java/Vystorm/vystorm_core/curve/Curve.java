package Vystorm.vystorm_core.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eine eindimensionale Balancing-Kurve {@code x → y}. Kurven sind unveränderlich und werden aus Konfiguration
 * ({@link #fromMap}) oder Code erzeugt; die Auswertung ist frei von Seiteneffekten und threadsicher.
 */
public sealed interface Curve permits Curve.Linear, Curve.Power, Curve.SoftCap, Curve.Logistic, Curve.Logarithmic, Curve.Points {
    double apply(double x);

    /** Kurzbeschreibung für Debug-Ausgaben. */
    String describe();

    /** {@code offset + slope·x} */
    record Linear(double slope, double offset) implements Curve {
        public double apply(double x) { return offset + slope * x; }
        public String describe() { return "linear(" + slope + "·x + " + offset + ")"; }
    }

    /** {@code scale·max(0,x)^exponent + offset} */
    record Power(double scale, double exponent, double offset) implements Curve {
        public double apply(double x) { return scale * Math.pow(Math.max(0, x), exponent) + offset; }
        public String describe() { return "power(" + scale + "·x^" + exponent + " + " + offset + ")"; }
    }

    /** Sättigung {@code max·x/(x+half)}: bei {@code x = half} die Hälfte von {@code max}, nie darüber. */
    record SoftCap(double max, double half) implements Curve {
        public SoftCap { if (half <= 0) throw new IllegalArgumentException("half must be > 0"); }
        public double apply(double x) { double v = Math.max(0, x); return max * v / (v + half); }
        public String describe() { return "softcap(max " + max + ", half " + half + ")"; }
    }

    /** S-Kurve {@code min + (max-min)/(1+e^(-steepness·(x-mid)))}. */
    record Logistic(double min, double max, double mid, double steepness) implements Curve {
        public double apply(double x) { return min + (max - min) / (1 + Math.exp(-steepness * (x - mid))); }
        public String describe() { return "logistic(" + min + ".." + max + ", mid " + mid + ", k " + steepness + ")"; }
    }

    /** {@code scale·ln(1 + max(0,x)/divisor)} */
    record Logarithmic(double scale, double divisor) implements Curve {
        public Logarithmic { if (divisor <= 0) throw new IllegalArgumentException("divisor must be > 0"); }
        public double apply(double x) { return scale * Math.log1p(Math.max(0, x) / divisor); }
        public String describe() { return "log(" + scale + "·ln(1+x/" + divisor + "))"; }
    }

    /** Stückweise lineare Interpolation zwischen Stützpunkten, außerhalb konstant auf den Randwerten. */
    record Points(double[] xs, double[] ys) implements Curve {
        public Points {
            if (xs.length == 0 || xs.length != ys.length) throw new IllegalArgumentException("points need matching x/y");
            xs = xs.clone();ys = ys.clone();
            for (int i = 1; i < xs.length; i++) if (!(xs[i] > xs[i - 1])) throw new IllegalArgumentException("x values must increase");
        }
        public double apply(double x) {
            if (x <= xs[0]) return ys[0];
            int last = xs.length - 1;
            if (x >= xs[last]) return ys[last];
            int low = 0, high = last;
            while (high - low > 1) { int mid = (low + high) >>> 1; if (xs[mid] <= x) low = mid; else high = mid; }
            double t = (x - xs[low]) / (xs[high] - xs[low]);
            return ys[low] + t * (ys[high] - ys[low]);
        }
        public String describe() { return "points(" + xs.length + ")"; }
    }

    /**
     * Liest eine Kurve aus einer Konfigurations-Map, z. B. {@code {type: softcap, max: 0.6, half: 2.0}} oder
     * {@code {type: points, points: [[0,0],[1,0.2],[2,0.5]]}}.
     */
    static Curve fromMap(Map<?, ?> raw) {
        String type = String.valueOf(raw.containsKey("type") ? raw.get("type") : "linear").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "linear" -> new Linear(number(raw, "slope", 1), number(raw, "offset", 0));
            case "power" -> new Power(number(raw, "scale", 1), number(raw, "exponent", 1), number(raw, "offset", 0));
            case "softcap" -> new SoftCap(number(raw, "max", 1), number(raw, "half", 1));
            case "logistic" -> new Logistic(number(raw, "min", 0), number(raw, "max", 1), number(raw, "mid", 1), number(raw, "steepness", 1));
            case "log", "logarithmic" -> new Logarithmic(number(raw, "scale", 1), number(raw, "divisor", 1));
            case "points" -> {
                Object list = raw.get("points");
                if (!(list instanceof List<?> points) || points.isEmpty()) throw new IllegalArgumentException("points curve needs 'points'");
                List<double[]> parsed = new ArrayList<>();
                for (Object point : points) {
                    if (!(point instanceof List<?> pair) || pair.size() != 2) throw new IllegalArgumentException("point must be [x, y]");
                    parsed.add(new double[]{toDouble(pair.get(0)), toDouble(pair.get(1))});
                }
                double[] xs = new double[parsed.size()], ys = new double[parsed.size()];
                for (int i = 0; i < parsed.size(); i++) { xs[i] = parsed.get(i)[0];ys[i] = parsed.get(i)[1]; }
                yield new Points(xs, ys);
            }
            default -> throw new IllegalArgumentException("unknown curve type: " + type);
        };
    }

    private static double number(Map<?, ?> raw, String key, double fallback) {
        Object value = raw.get(key);
        return value == null ? fallback : toDouble(value);
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("not a number: " + value); }
    }
}
