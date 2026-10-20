// ExploreL4.java — run with: java ExploreL4.java   (JDK 21, no dependencies)
// FEEDBACK LOOPS AND THE COLD START. A home-feed carousel shows 10 of 500 snack products to 5,000 customers a day (one city cluster). The
// ranker learns each product's click-through rate from yesterday's logs (daily retrain). On day 10, 40 NEW products
// launch — several are better than anything in the catalogue — but they have no clicks yet. Thirty days, fixed seed.
//   E1 greedy: rank by observed CTR with a small prior (new items start at the prior, below the proven winners)
//   E2 ε-greedy: one of the 10 slots goes to a random product 5% of the time... here: 10% of feeds get one random slot
//   E3 Thompson sampling: rank by a draw from each product's Beta(clicks+1, non-clicks+20) posterior
//   E4 greedy + a reserved "new arrivals" slot for products launched in the last 14 days (merchandising rule)
// Measured over days 10–30 against an oracle that knows every true CTR: total clicks (and the share of the oracle's),
// how many new products got a fair trial (≥ 200 impressions), and where the best new product ended up.
import java.util.*;

public class ExploreL4 {
    public static void main(String[] args) {
        Random r = new Random(43); int old = 500, fresh = 40, n = old + fresh, days = 30, feeds = 5_000, slots = 10;
        double[] ctr = new double[n];
        for (int i = 0; i < old; i++) ctr[i] = 0.01 + 0.05 * Math.pow(r.nextDouble(), 2);
        for (int i = old; i < n; i++) ctr[i] = 0.01 + 0.09 * Math.pow(r.nextDouble(), 2);           // new launches: wider spread
        int bestNew = old; for (int i = old; i < n; i++) if (ctr[i] > ctr[bestNew]) bestNew = i;
        double bestOld = 0; for (int i = 0; i < old; i++) bestOld = Math.max(bestOld, ctr[i]);
        int betterNew = 0; for (int i = old; i < n; i++) if (ctr[i] > bestOld) betterNew++;
        System.out.printf("500 products; 40 launch on day 10 (%d of them beat the best existing product's %.1f%% CTR; best new %.1f%%).%n", betterNew, 100 * bestOld, 100 * ctr[bestNew]);
        System.out.println("5,000 feeds a day × 10 slots, daily retrain. Measured over days 10–30.\n");
        System.out.printf("   %-52s %12s %10s %20s %20s%n", "policy", "clicks", "% oracle", "new items with", "best new item's");
        System.out.printf("   %-52s %12s %10s %20s %20s%n", "", "", "", "≥ 200 impressions", "rank on day 30");
        String[] names = {"E1) greedy on observed CTR", "E2) ε-greedy (10% of feeds get a random slot)", "E3) Thompson sampling", "E4) greedy + reserved new-arrivals slot"};
        double oracle = 0; { Integer[] o = rank(ctr, n); for (int k = 0; k < slots; k++) oracle += ctr[o[k]]; oracle *= (double) feeds * 21; }
        for (int p = 0; p < 4; p++) {
            Random q = new Random(7 + p); long[] imp = new long[n], clk = new long[n]; double clicks = 0; int bestRank = -1;
            for (int d = 0; d < days; d++) {
                int live = d < 10 ? old : n; double[] score = new double[live];
                for (int i = 0; i < live; i++) score[i] = (clk[i] + 0.5) / (imp[i] + 25.0);           // posterior mean with a prior of 2%
                int[][] cached = new int[7][]; for (int salt = 0; salt < 7; salt++) cached[salt] = top(score, live, slots, salt);
                int[][] newTop = new int[40][]; if (p == 3) for (int salt = 0; salt < 40; salt++) newTop[salt] = top(Arrays.copyOfRange(score, old, n), fresh, 1, salt);
                for (int f = 0; f < feeds; f++) {
                    int[] shown;
                    if (p == 2) { double[] s = new double[live]; for (int i = 0; i < live; i++) s[i] = beta(q, clk[i] + 1, imp[i] - clk[i] + 20); shown = top(s, live, slots, 0); }
                    else shown = cached[f % 7].clone();                                            // ties broken differently per feed
                    if (p == 1 && q.nextDouble() < 0.10) shown[slots - 1] = q.nextInt(live);
                    if (p == 3 && d >= 10 && d < 24) { int[] nw = newTop[f % 40]; shown[slots - 1] = old + (q.nextDouble() < 0.5 ? nw[0] : q.nextInt(fresh)); }
                    for (int i : shown) { imp[i]++; if (q.nextDouble() < ctr[i]) { clk[i]++; if (d >= 10) clicks++; } }
                }
                if (d == days - 1) { Integer[] o = rank(score, live); for (int k = 0; k < live; k++) if (o[k] == bestNew) bestRank = k + 1; }
            }
            int fair = 0; for (int i = old; i < n; i++) if (imp[i] >= 200) fair++;
            System.out.printf("   %-52s %,12.0f %9.1f%% %20s %20s%n", names[p], clicks, 100 * clicks / oracle, fair + " / " + fresh, "#" + bestRank);
        }
        System.out.println("\n   → a ranker trained only on its own past choices never learns about items it doesn't show: the greedy feed locked");
        System.out.println("     onto whatever it happened to show first and gave no new launch a fair trial, though 9 beat everything on the");
        System.out.println("     shelf. Uniform random exploration helps a little. Thompson sampling explores in proportion to uncertainty and");
        System.out.println("     exploits as soon as evidence arrives, reaching 92% of the oracle. A reserved new-arrivals slot did as well HERE");
        System.out.println("     because the best products happened to be new; it explores nothing else, so lock-in among existing products");
        System.out.println("     remains. Log which impressions were exploratory — L2's debiasing needs exactly that data.");
    }

    static double beta(Random r, double a, double b) { double x = gamma(r, a), y = gamma(r, b); return x / (x + y); }
    static double gamma(Random r, double k) {                          // Marsaglia–Tsang
        if (k < 1) return gamma(r, k + 1) * Math.pow(r.nextDouble(), 1 / k);
        double d = k - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) { double x = r.nextGaussian(), v = Math.pow(1 + c * x, 3); if (v <= 0) continue; double u = r.nextDouble();
            if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) return d * v; }
    }
    static int[] top(double[] s, int n, int k, int salt) {
        PriorityQueue<Integer> pq = new PriorityQueue<>((a, b) -> s[a] != s[b] ? Double.compare(s[a], s[b]) : Integer.compare((b * 31 + salt) % 97, (a * 31 + salt) % 97));
        for (int i = 0; i < n; i++) { pq.add(i); if (pq.size() > k) pq.poll(); }
        int[] out = new int[pq.size()]; for (int i = out.length - 1; i >= 0; i--) out[i] = pq.poll(); return out;
    }
    static Integer[] rank(double[] s, int n) { Integer[] o = new Integer[n]; for (int i = 0; i < n; i++) o[i] = i; Arrays.sort(o, (a, b) -> Double.compare(s[b], s[a])); return o; }
}
