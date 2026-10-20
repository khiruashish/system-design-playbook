// L1 — Pacing: where the 45 minutes actually went in six recorded mocks, and what a checkpoint rule changes.
// Phase durations are illustrative, in the pattern typical of practice recordings (not real candidate data).
import java.util.*;

public class PacingL1 {
    static final String[] PH = {"clarify", "estimate", "api", "data", "hld", "deep-dive", "failures", "wrap"};
    static final int[] PLAN = {5, 4, 4, 5, 9, 12, 4, 2};          // sums to 45
    static final boolean[] COMPRESSIBLE = {false, true, true, true, false, false, false, false};
    static final int[] FLOOR = {0, 1, 1, 2, 0, 0, 0, 0};           // minimum minutes if compressed

    record Mock(String name, int[] minutes) {}

    public static void main(String[] a) {
        List<Mock> mocks = List.of(
            new Mock("M1 coupon service (B2)", new int[]{11, 7, 6, 8, 10, 3, 0, 0}),
            new Mock("M2 order notifications (B2)", new int[]{6, 3, 9, 7, 8, 9, 3, 0}),
            new Mock("M3 dark-store inventory (B3)", new int[]{5, 5, 3, 6, 11, 11, 4, 0}),
            new Mock("M4 multi-country (B4)", new int[]{8, 2, 2, 4, 12, 10, 5, 2}),
            new Mock("M5 rate limiter (B2)", new int[]{4, 8, 5, 5, 9, 10, 2, 2}),
            new Mock("M6 search autocomplete (B3)", new int[]{7, 6, 4, 9, 9, 8, 2, 0}));

        System.out.println("Plan (min): " + fmt(PLAN) + "   total 45\n");
        System.out.printf("%-30s %-44s %6s %9s %9s%n", "mock (as recorded)", PH[0] + "/" + PH[1] + "/.../" + PH[7], "@20min", "deep+fail", "overran");
        for (Mock m : mocks) {
            int[] x = cap(m.minutes());
            System.out.printf("%-30s %-44s %6s %6d/16 %9s%n", m.name(), fmt(x), phaseAt(x, 20), x[5] + x[6], overran(x));
        }
        System.out.println("\nCheckpoint rule: at minute 20 you must be drawing the HLD. If you are behind, compress estimate/api/data");
        System.out.println("to their floors (1/1/2 min: state the numbers, name 2 endpoints, list 3 tables) and say so aloud.\n");
        System.out.printf("%-30s %-44s %6s %9s%n", "mock (replayed with rule)", "", "@20min", "deep+fail");
        int before = 0, after = 0;
        for (Mock m : mocks) {
            int[] x = cap(m.minutes()), y = applyRule(m.minutes());
            before += x[5] + x[6]; after += y[5] + y[6];
            System.out.printf("%-30s %-44s %6s %6d/16%n", m.name(), fmt(y), phaseAt(y, 20), y[5] + y[6]);
        }
        System.out.printf("%nDeep-dive + failures, average over 6 mocks: %.1f min as recorded → %.1f min with the rule (plan: 16)%n", before / 6.0, after / 6.0);
        System.out.println("(replay assumes the minutes saved go to deep-dive then failures; clarify is never compressed)");
    }

    // the interview stops at 45 minutes: whatever was not reached gets nothing
    static int[] cap(int[] m) { int[] r = new int[m.length]; int t = 0; for (int i = 0; i < m.length; i++) { r[i] = Math.max(0, Math.min(m[i], 45 - t)); t += r[i]; } return r; }

    static int[] applyRule(int[] m) {
        int[] r = m.clone(); int t = 0; int saved = 0;
        for (int i = 0; i < 4; i++) t += r[i];
        if (t > 20 - 0) {                       // HLD not started by minute 20
            for (int i = 1; i <= 3; i++) if (COMPRESSIBLE[i] && r[i] > FLOOR[i]) {
                int cut = Math.min(r[i] - FLOOR[i], t - 20 + 0); if (cut <= 0) break; r[i] -= cut; saved += cut; t -= cut; }
        }
        int total = Arrays.stream(r).sum();
        int spare = 45 - total;                 // minutes now available before the hard stop
        if (spare > 0) { int addDeep = Math.min(spare, Math.max(0, PLAN[5] - r[5])); r[5] += addDeep; spare -= addDeep;
                         int addFail = Math.min(spare, Math.max(0, PLAN[6] - r[6])); r[6] += addFail; spare -= addFail;
                         r[7] += Math.min(spare, 2); }
        return cap(r);
    }
    static String phaseAt(int[] x, int minute) { int t = 0; for (int i = 0; i < x.length; i++) { t += x[i]; if (t > minute) return PH[i]; } return "done"; }
    static String overran(int[] x) { StringBuilder s = new StringBuilder(); for (int i = 0; i < 4; i++) if (x[i] > PLAN[i] + 2) s.append(s.length() > 0 ? "," : "").append(PH[i]); return s.length() == 0 ? "-" : s.toString(); }
    static String fmt(int[] x) { StringBuilder s = new StringBuilder(); for (int v : x) s.append(String.format("%3d", v)); return s.toString(); }
}
