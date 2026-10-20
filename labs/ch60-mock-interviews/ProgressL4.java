// L4 — Are my mocks improving, or is it noise? Scores on the 1–4 rubric scale.
// Model (assumption, VERIFY with your own spread): true level starts at 2.4 and improves by `gain` per mock;
// each mock score = true level + prompt difficulty (sd 0.35) + interviewer (sd 0.35) → total noise sd ≈ 0.5.
import java.util.*;

public class ProgressL4 {
    public static void main(String[] a) {
        int trials = 50_000; Random r = new Random(6004);
        System.out.println("How often does a series of N mocks show what is really happening?  (50,000 simulated candidates per cell)\n");
        System.out.printf("%-12s %3s %16s %18s %20s %20s%n", "gain/mock", "N", "last < first", "slope > 0 (90%)", "rolling-3 rose", "true gain over N");
        for (double gain : new double[]{0.0, 0.05, 0.10})
            for (int n : new int[]{4, 6, 8, 12}) {
                int lastLower = 0, sig = 0, roll = 0;
                for (int t = 0; t < trials; t++) {
                    double[] y = new double[n];
                    for (int i = 0; i < n; i++) y[i] = 2.4 + gain * i + r.nextGaussian() * 0.35 + r.nextGaussian() * 0.35;
                    if (y[n - 1] < y[0]) lastLower++;
                    if (tStat(y) > tCrit(n - 2)) sig++;
                    if (avg(y, n - 3, n) > avg(y, 0, 3)) roll++;
                }
                System.out.printf("%-12s %3d %15.0f%% %17.0f%% %19.0f%% %20s%n", String.format("%+.2f", gain), n,
                        100.0 * lastLower / trials, 100.0 * sig / trials, 100.0 * roll / trials, String.format("%+.2f", gain * (n - 1)));
            }
        System.out.println("\nlast < first: the discouraging comparison people make; slope > 0 (90%): one-sided test on the least-squares slope;");
        System.out.println("rolling-3 rose: average of the last 3 mocks above the average of the first 3 (for N=4 the windows overlap by 2 mocks).");
    }
    static double avg(double[] y, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += y[i]; return s / (to - from); }
    static double tStat(double[] y) {
        int n = y.length; double mx = (n - 1) / 2.0, my = avg(y, 0, n), sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sxx += (i - mx) * (i - mx); sxy += (i - mx) * (y[i] - my); }
        double b = sxy / sxx, sse = 0; for (int i = 0; i < n; i++) { double e = y[i] - (my + b * (i - mx)); sse += e * e; }
        return b / Math.sqrt(sse / (n - 2) / sxx);
    }
    // one-sided 90% critical values of Student's t
    static double tCrit(int df) { return switch (df) { case 2 -> 1.886; case 4 -> 1.533; case 6 -> 1.440; case 10 -> 1.372; default -> 1.3; }; }
}
