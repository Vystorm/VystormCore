package Vystorm.vystorm_core.curve;

/**
 * Verhältnisbasierte Auflösung zweier Werte (z. B. Angreifer-STR gegen Ziel-Stabilität): das Verhältnis
 * {@code source/target} läuft durch eine registrierte Kurve und wird auf {@code [min, max]} begrenzt.
 */
public final class RelativeStat {
    /** Unterhalb davon gilt ein Wert als 0, damit {@code x/0} nicht unendlich wird. */
    public static final double EPSILON = 1.0e-6;

    private RelativeStat() {}

    public record Input(double source, double target, String curveId, double min, double max, double contextFactor) {
        public Input(double source, double target, String curveId, double min, double max) { this(source, target, curveId, min, max, 1.0); }
    }

    public record Result(double ratio, double curveOutput, double finalValue, boolean capped, String breakdown) {}

    public static Result resolve(Input input) {
        double ratio = Math.max(0, input.source()) / Math.max(EPSILON, input.target());
        var curve = CurveRegistry.get(input.curveId());
        double output = curve.map(c -> c.apply(ratio)).orElse(ratio);
        double scaled = output * input.contextFactor();
        double bounded = Math.max(input.min(), Math.min(input.max(), scaled));
        boolean capped = bounded != scaled;
        String breakdown = String.format(java.util.Locale.ROOT,
                "source %.3f / target %.3f = ratio %.4f → %s = %.4f × context %.3f = %.4f → [%s, %s] = %.4f%s",
                input.source(), input.target(), ratio, curve.map(Curve::describe).orElse("identity (" + input.curveId() + " missing)"),
                output, input.contextFactor(), scaled, input.min(), input.max(), bounded, capped ? " (capped)" : "");
        return new Result(ratio, output, bounded, capped, breakdown);
    }
}
