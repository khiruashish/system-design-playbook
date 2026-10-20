// CommitL3.java — run with: java CommitL3.java   (JDK 21, no dependencies)
// HOW MUCH CAPACITY TO COMMIT TO. Cloud providers discount capacity you commit to for a year (reserved instances / savings
// plans) — but you pay for it every hour whether you use it or not; anything above the commitment runs on-demand.
// ILLUSTRATIVE prices: on-demand ₹18 per pod-hour; 1-year commitment ₹10.80 per pod-hour (40% off), billed 24×7.
// The fleet is the whole platform: ~10x checkout traffic plus 3,000 req/s of steady background work (search indexing, async
// consumers, batch), with an HA floor of 24 pods.
// Step 1: against next year's EXPECTED hourly demand, sweep the commitment level. A marginal committed pod pays off only if it
//         would be busy in ≥ 60% of hours (10.80 / 18).
// Step 2: next year's demand level is UNCERTAIN (new cities could add 50%; losing a market could cut 30%). Monte-Carlo it and
//         compare commitment levels on expected cost AND on regret versus the choice you'd make with hindsight.
import java.util.*;

public class CommitL3 {
    static final double OD = 18.0, COMMIT = 10.8;
    static final int FLOOR = 24;

    static int[] histogram(double scale) {               // hours spent at each pod count over the year
        int[] hist = new int[2000];
        for (int h = 0; h < 8760; h++) {
            int day = h / 24; double hour = h % 24 + 0.5;
            double rps = 120 + 900 * Math.exp(-Math.pow((hour - 8.5) / 1.6, 2)) + 1300 * Math.exp(-Math.pow((hour - 19.5) / 1.8, 2))
                       + 350 * Math.exp(-Math.pow((hour - 13) / 2.0, 2));
            if (day % 7 == 5 || day % 7 == 6) rps *= 1.25;
            if (day >= 290 && day < 305 && hour >= 18 && hour < 22) rps *= 3.5;         // festival-season evenings
            double platform = 10 * rps + 3000;                                          // + steady background: indexing, consumers, batch
            hist[Math.max(FLOOR, (int) Math.ceil(platform * scale / 150.0))]++;
        }
        return hist;
    }
    static double cost(int[] hist, int k) {
        double c = k * 8760.0 * COMMIT;
        for (int v = k + 1; v < hist.length; v++) c += (double) (v - k) * hist[v] * OD;
        return c;
    }
    static int optimum(int[] hist) { int best = 0; double bc = Double.MAX_VALUE;
        for (int k = 0; k < 400; k++) { double c = cost(hist, k); if (c < bc) { bc = c; best = k; } } return best; }
    static int pct(int[] hist, double p) { int acc = 0; for (int v = 0; v < hist.length; v++) { acc += hist[v]; if (acc >= p * 8760) return v; } return -1; }
    static double busyShare(int[] hist, int k) { int b = 0; for (int v = k; v < hist.length; v++) b += hist[v]; return (double) b / 8760; }
    static double tri(Random r, double lo, double mode, double hi) {
        double u = r.nextDouble(), f = (mode - lo) / (hi - lo);
        return u < f ? lo + Math.sqrt(u * (hi - lo) * (mode - lo)) : hi - Math.sqrt((1 - u) * (hi - lo) * (hi - mode));
    }

    public static void main(String[] args) {
        int[] exp = histogram(1.0);
        double allOd = cost(exp, 0);
        int best = optimum(exp);
        System.out.printf("Expected demand next year: floor %d, median %d, p90 %d, peak %d pods. All on-demand: ₹%.1f L/yr (illustrative).%n%n",
                pct(exp, 0.0001), pct(exp, 0.5), pct(exp, 0.9), pct(exp, 1.0), allOd / 1e5);
        System.out.printf("   %-12s %14s %14s %28s%n", "commit", "annual cost", "vs on-demand", "hours the marginal pod is busy");
        int[] show = {0, FLOOR, 40, 60, best, 100, 140, pct(exp, 1.0)};
        for (int k : Arrays.stream(show).distinct().sorted().toArray()) {
            double c = cost(exp, k);
            System.out.printf("   %4d pods%s %12.1f L %+13.1f%% %27.0f%%%n", k, k == best ? " ←" : "  ", c / 1e5, 100 * (c - allOd) / allOd, 100 * busyShare(exp, k));
        }
        System.out.printf("   → optimum against EXPECTED demand: commit %d pods (busy %.0f%% of hours, just above the 60%% break-even), saving %.1f%%.%n%n",
                best, 100 * busyShare(exp, best), 100 * (allOd - cost(exp, best)) / allOd);

        // Step 2 — uncertain demand level
        Random r = new Random(5); int TRIALS = 20_000;
        Map<Integer, int[]> hists = new HashMap<>(); Map<Integer, Integer> hindsight = new HashMap<>();
        int[] cand = {FLOOR, (int) Math.round(best * 0.7), (int) Math.round(best * 0.85), best, (int) Math.round(best * 1.2), pct(exp, 0.9)};
        double[][] costs = new double[cand.length][TRIALS], regrets = new double[cand.length][TRIALS];
        for (int t = 0; t < TRIALS; t++) {
            int key = (int) Math.round(tri(r, 0.7, 1.0, 1.5) * 100);            // 1% buckets
            int[] h = hists.computeIfAbsent(key, k -> histogram(k / 100.0));
            int hk = hindsight.computeIfAbsent(key, k -> optimum(hists.get(k)));
            double hc = cost(h, hk);
            for (int i = 0; i < cand.length; i++) { costs[i][t] = cost(h, cand[i]); regrets[i][t] = costs[i][t] - hc; }
        }
        System.out.println("   Demand scale next year ~ triangular(0.70, 1.00, 1.50). 20,000 trials:");
        System.out.printf("   %-29s %12s %12s %24s%n", "commit", "expected cost", "p95 cost", "p95 regret vs hindsight");
        String[] label = {"HA floor only", "0.7 × optimum", "0.85 × optimum", "optimum (expected)", "1.2 × optimum", "p90 of demand"};
        for (int i = 0; i < cand.length; i++) {
            double mean = Arrays.stream(costs[i]).average().orElse(0);
            Arrays.sort(costs[i]); Arrays.sort(regrets[i]);
            System.out.printf("   %4d pods  %-19s %9.1f L %10.1f L %19.1f L%n", cand[i], label[i], mean / 1e5,
                    costs[i][(int) (TRIALS * 0.95)] / 1e5, regrets[i][(int) (TRIALS * 0.95)] / 1e5);
        }
        System.out.println("\n   → against a known curve the rule is crisp: commit up to the level the fleet sits at or above for the break-even share");
        System.out.println("     of hours (60% at a 40% discount) and buy the rest on demand. Committing to the p90 or the peak is the expensive");
        System.out.println("     mistake — most of those pods idle while being paid for, which shows up as the worst expected cost and by far the");
        System.out.println("     largest regret. Under uncertainty two things matter. The cost curve is FLAT around the optimum (0.85x to 1.2x are");
        System.out.println("     within a few percent), so precision matters less than avoiding the tails. And the right hedge follows the SKEW:");
        System.out.println("     here demand is more likely to grow than shrink, so committing a little above the expected-demand optimum does as");
        System.out.println("     well on average and better in the bad tail. In practice: commit to the floor you're confident of, ratchet up each");
        System.out.println("     quarter as growth materialises (commitments are easier to add than to exit), and put only interruptible work");
        System.out.println("     (batch, async consumers) on spot/preemptible capacity — never the checkout path.");
    }
}
