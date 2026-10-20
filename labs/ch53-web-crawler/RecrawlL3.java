// RecrawlL3.java — run with: java RecrawlL3.java   (JDK 21, no dependencies)
// HOW OFTEN TO RE-FETCH EACH COMPETITOR PRICE PAGE? 100,000 product pages; each page's PRICE changes as a Poisson
// process with its own rate (fixed seed): 5% flash-deal pages ~every 2 h, 25% ~daily, 50% ~weekly, 20% ~monthly. Every
// page's HTML also changes on every fetch for reasons that don't matter (timestamps, "12 people viewing", ad slots).
// Budget: 400,000 fetches a day (4 per page on average). Days 1–3: fetch every page every 6 h and learn change rates;
// days 4–10: each policy sets a fixed re-fetch interval per page.
//   R1 uniform: every page every 6 hours
//   R2 proportional to change rate estimated from the whole-page hash
//   (R2–R4 first give every page one fetch per 72 h, then allocate the rest of the budget by the policy)
//   R3 proportional to change rate estimated from the extracted PRICE
//   R4 freshness-optimal allocation (Cho & Garcia-Molina) using the price-based estimates
//   R5 R4 with the TRUE change rates — the ceiling that better estimates could reach
// Measured on days 4–10 (sampled every 15 min): share of pages whose stored price is current, mean age of a wrong
// price, and freshness of the fast-changing (flash-deal) pages.
import java.util.*;

public class RecrawlL3 {
    public static void main(String[] args) {
        int N = 100_000; double budgetPerHour = 400_000 / 24.0; Random r = new Random(53);
        double[] lambda = new double[N]; int[] cls = new int[N];                                   // price changes per hour
        for (int i = 0; i < N; i++) { double x = r.nextDouble(); cls[i] = x < 0.05 ? 0 : x < 0.30 ? 1 : x < 0.80 ? 2 : 3;
            double mean = cls[i] == 0 ? 2 : cls[i] == 1 ? 24 : cls[i] == 2 ? 168 : 720; lambda[i] = 1 / (mean * Math.exp(0.5 * r.nextGaussian())); }
        double H = 240; List<double[]> changes = new ArrayList<>();
        for (int i = 0; i < N; i++) { List<Double> c = new ArrayList<>(); double t = 0; while ((t += -Math.log(1 - r.nextDouble()) / lambda[i]) < H) c.add(t); changes.add(c.stream().mapToDouble(Double::doubleValue).toArray()); }
        // learning (days 1–3): fetch every 6 h, 12 fetches; count intervals in which the price changed / the page hash changed
        double[] estPrice = new double[N], estHash = new double[N]; int n = 12; double I = 6;
        for (int i = 0; i < N; i++) { int x = 0; double[] c = changes.get(i); for (int k = 0; k < n; k++) { double a = k * I, b = (k + 1) * I; for (double t : c) if (t > a && t <= b) { x++; break; } }
            estPrice[i] = -Math.log((n - x + 0.5) / (n + 0.5)) / I;                                  // Cho's estimator
            estHash[i] = -Math.log((n - n + 0.5) / (n + 0.5)) / I; }                                   // whole-page hash: "changed" every time
        System.out.println("100,000 price pages, 400,000 fetches/day; learn rates on days 1–3, evaluate days 4–10.\n");
        System.out.printf("   %-60s %16s %16s %18s%n", "re-crawl policy", "prices current", "mean age of a", "flash-deal pages");
        System.out.printf("   %-60s %16s %16s %18s%n", "", "(time-avg)", "wrong price", "current");
        String[] names = {"R1) uniform: every page every 6 h", "R2) proportional to change rate from whole-page hash", "R3) proportional to change rate from the extracted price", "R4) freshness-optimal allocation (price-based estimates)", "R5) freshness-optimal allocation with the TRUE rates (oracle)"};
        for (int p = 0; p < 5; p++) {
            double[] freq = new double[N];                                                          // fetches per hour
            if (p == 0) Arrays.fill(freq, budgetPerHour / N);
            else {                                                                                   // every page at least every 72 h (a page that showed no change
                double floor = 1 / 72.0, rest = budgetPerHour - N * floor;                           // while learning is not a page that never changes), rest by policy
                if (p == 1 || p == 2) { double[] e = p == 1 ? estHash : estPrice; double s = 0; for (double v : e) s += v; for (int i = 0; i < N; i++) freq[i] = floor + rest * e[i] / s; }
                else { freq = optimal(p == 4 ? lambda : estPrice, rest); for (int i = 0; i < N; i++) freq[i] += floor; }
            }
            double fresh = 0, cnt = 0, ageSum = 0, ageCnt = 0, flashFresh = 0, flashCnt = 0; Random ph = new Random(1);
            for (int i = 0; i < N; i++) {
                double interval = 1 / Math.max(freq[i], 1e-6), phase = ph.nextDouble() * interval; double[] c = changes.get(i);
                for (double t = 72; t < H; t += 0.25) {
                    double lastFetch = t < 72 + phase ? 72 : 72 + phase + Math.floor((t - 72 - phase) / interval) * interval;   // last learning fetch was at hour 72
                    double lastChange = -1; int lo = 0, hi = c.length - 1; while (lo <= hi) { int m = (lo + hi) >>> 1; if (c[m] <= t) { lastChange = c[m]; lo = m + 1; } else hi = m - 1; }
                    boolean ok = lastChange < lastFetch; fresh += ok ? 1 : 0; cnt++;
                    if (!ok) { ageSum += t - lastChange; ageCnt++; }
                    if (cls[i] == 0) { flashFresh += ok ? 1 : 0; flashCnt++; }
                }
            }
            System.out.printf("   %-60s %15.1f%% %13.1f h %17.1f%%%n", names[p], 100 * fresh / cnt, ageSum / Math.max(1, ageCnt), 100 * flashFresh / flashCnt);
        }
        System.out.println("\n   → a whole-page hash says every page changes on every visit (timestamps, counters, ads), so 'learning' from it");
        System.out.println("     changes nothing (R2 = R1). Learning from the extracted price does move the budget: proportional allocation");
        System.out.println("     lifts flash-deal pages from 34% to 86% current, but three days of observations can't tell a weekly page from");
        System.out.println("     a monthly one, slow pages drop to the 72-hour floor, and overall freshness FALLS below uniform. The optimal");
        System.out.println("     allocation beats uniform only with good rate estimates (R5: 94.9%). So: extract the field, keep re-estimating");
        System.out.println("     continuously (with priors from similar pages), and weight pages by business value explicitly — a uniform");
        System.out.println("     schedule is a stronger baseline than it looks.");
    }

    // maximise Σ F(λ_i, f_i) with F = (f/λ)(1 − e^{−λ/f}) subject to Σ f_i = B: equalise ∂F/∂f = μ (bisection on μ)
    static double[] optimal(double[] lam, double B) {
        int n = lam.length; double[] f = new double[n]; double lo = 1e-12, hi = 1e3;
        for (int it = 0; it < 80; it++) { double mu = Math.sqrt(lo * hi), s = 0; for (int i = 0; i < n; i++) { f[i] = fFor(lam[i], mu); s += f[i]; } if (s > B) lo = mu; else hi = mu; }
        return f;
    }
    static double dF(double l, double f) { double x = l / f; return (1 - Math.exp(-x)) / l - Math.exp(-x) / f; }  // derivative of F wrt f
    static double fFor(double l, double mu) {
        if (l < 1e-6) return 1e-6; double lo = 1e-7, hi = 1e3;                                    // dF decreasing in f; if dF(lo) < mu → 0
        if (dF(l, lo) < mu) return 0; for (int it = 0; it < 60; it++) { double m = Math.sqrt(lo * hi); if (dF(l, m) > mu) lo = m; else hi = m; } return lo;
    }
}
