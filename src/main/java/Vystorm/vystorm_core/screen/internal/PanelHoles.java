package Vystorm.vystorm_core.screen.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Screen canvas, 26.3 experiment (C0): the panel background cut into rectangles that leave the map widgets free.
 * 26.3 clients draw map frames before see-through text, so anything see-through drawn over a map covers it; with no
 * panel geometry over a map the map stays visible (the depth-tested backdrop behind it loses against the nearer map).
 * Pure. Rectangles are {x0, y0, x1, y1} with x0 &lt; x1, y0 &lt; y1 in any one coordinate frame.
 */
final class PanelHoles {
    private PanelHoles() { }

    /**
     * {@code outer} minus every hole (holes are clipped to {@code outer}), as horizontal bands: one rectangle per free
     * x-run of each band between hole edges; vertically neighbouring bands with the same run are joined.
     */
    static List<double[]> pieces(double[] outer, List<double[]> holes) {
        List<double[]> clipped = new ArrayList<>();
        for (double[] h : holes) {
            double x0 = Math.max(outer[0], h[0]), y0 = Math.max(outer[1], h[1]), x1 = Math.min(outer[2], h[2]), y1 = Math.min(outer[3], h[3]);
            if (x1 > x0 && y1 > y0) clipped.add(new double[] { x0, y0, x1, y1 });
        }
        if (clipped.isEmpty()) return List.of(outer.clone());
        TreeSet<Double> ys = new TreeSet<>();
        ys.add(outer[1]);
        ys.add(outer[3]);
        for (double[] h : clipped) { ys.add(h[1]); ys.add(h[3]); }
        List<double[]> out = new ArrayList<>();
        List<double[]> open = new ArrayList<>();   // rectangles of the band below that the next band can extend
        Double low = null;
        for (double y : ys) {
            if (low == null) { low = y; continue; }
            double mid = (low + y) / 2;
            List<double[]> covered = new ArrayList<>();
            for (double[] h : clipped) if (h[1] <= mid && mid <= h[3]) covered.add(new double[] { h[0], h[2] });
            covered.sort((a, b) -> Double.compare(a[0], b[0]));
            List<double[]> runs = new ArrayList<>();
            double x = outer[0];
            for (double[] c : covered) {
                if (c[0] > x) runs.add(new double[] { x, c[0] });
                x = Math.max(x, c[1]);
            }
            if (x < outer[2]) runs.add(new double[] { x, outer[2] });
            List<double[]> next = new ArrayList<>();
            for (double[] run : runs) {
                double[] joined = null;
                for (double[] r : open) if (r[0] == run[0] && r[2] == run[1] && r[3] == low) { joined = r; break; }
                if (joined != null) { joined[3] = y; next.add(joined); }
                else { double[] r = { run[0], low, run[1], y }; out.add(r); next.add(r); }
            }
            open = next;
            low = y;
        }
        return out;
    }
}
