// LatencyBudgetL1.java — run with: java LatencyBudgetL1.java   (JDK 21, no dependencies)
// THE CHECKOUT'S LATENCY BUDGET. Place-order goes: api-gateway → checkout → {serviceability, pricing, promotion,
// inventory reserve, slot hold} → payment (external gateway) → order create. Each hop's latency is lognormal with the
// p50/p99 below (from the earlier chapters' measurements and typical values — assumptions, fixed seed), and each
// dependency has a 0.5% chance per call of a slow outlier (GC, retry, noisy neighbour: +300–1,500 ms).
// 1,000,000 simulated checkouts per design:
//   C1 every call in sequence
//   C2 the five pre-payment checks in parallel (they are independent)
//   C3 C2 + per-call timeouts with graceful degradation for the NON-critical ones (promotion: 80 ms → price without
//      the auto-coupon and say so; serviceability: 50 ms → use the cached answer) and one hedged retry for pricing
// Also: how tails compound when one page fans out to many services.
import java.util.*;

public class LatencyBudgetL1 {
    record Dep(String name, double p50, double p99) {}
    static final Dep GW = new Dep("api-gateway", 2, 6), CO = new Dep("checkout logic", 3, 10), PAY = new Dep("payment gateway", 180, 900), ORD = new Dep("order create", 8, 30);
    static final Dep[] PRE = {new Dep("serviceability", 2, 8), new Dep("pricing", 6, 25), new Dep("promotion", 10, 45), new Dep("inventory reserve", 12, 60), new Dep("slot hold", 9, 40)};

    public static void main(String[] args) {
        System.out.println("place-order critical path; per-hop lognormal latencies + 0.5% slow outliers; 1,000,000 checkouts.\n");
        System.out.printf("   %-66s %11s %11s %11s %11s %10s%n", "design", "end-to-end", "end-to-end", "our part", "our part", "degraded");
        System.out.printf("   %-66s %11s %11s %11s %11s %10s%n", "", "p50", "p99", "p99", "p99.9", "");
        String[] names = {"C1) everything sequential", "C2) five pre-payment checks in parallel", "C3) C2 + timeouts/degradation (non-critical) + hedged pricing"};
        for (int d = 0; d < 3; d++) run(d, names[d]);
        System.out.println("\n   why parallelism alone doesn't fix the tail: the slowest of five parallel calls (each with 0.5% outliers):");
        Random r = new Random(1); int n = 1_000_000; double[] x = new double[n];
        for (int i = 0; i < n; i++) { double m = 0; for (Dep p : PRE) m = Math.max(m, lat(r, p)); x[i] = m; }
        Arrays.sort(x); System.out.printf("   max of the five: p50 %.0f ms, p99 %.0f ms — vs inventory alone p99 %.0f ms%n%n", x[n / 2], x[(int) (n * 0.99)], PRE[3].p99());
        System.out.println("   tail amplification: a page that needs ALL of N calls, each 'fast 99% of the time':");
        for (int k : new int[]{1, 5, 10, 30, 100}) System.out.printf("   N = %-4d → %5.1f%% of page loads wait for at least one slow (p99) call%n", k, 100 * (1 - Math.pow(0.99, k)));
        System.out.println("\n   → the external payment gateway is most of the median — budget for it, show progress, and resolve lost answers");
        System.out.println("     asynchronously (L2). The TAIL, though, is mostly ours: with 0.5% outliers on each of our hops, a few percent of");
        System.out.println("     checkouts hit at least one, so our part's p99 is an outlier (~1.2 s) though every hop's own p99 is < 60 ms.");
        System.out.println("     Latency is a sum over sequential steps and a MAX over parallel ones: parallel calls shorten the median, but");
        System.out.println("     the max of five is only as good as the worst of them. Timeouts with an honest degraded answer for OPTIONAL");
        System.out.println("     calls and hedged idempotent reads cut the tail; outliers in critical calls (inventory, slot) remain and must be");
        System.out.println("     fixed at their source.");
    }
    static double lat(Random r, Dep d) {
        double sigma = Math.log(d.p99() / d.p50()) / 2.326; double v = d.p50() * Math.exp(sigma * r.nextGaussian());
        if (r.nextDouble() < 0.005) v += 300 + r.nextDouble() * 1200; return v;
    }
    static void run(int design, String name) {
        Random r = new Random(56); int n = 1_000_000; double[] t = new double[n], ours = new double[n]; long degraded = 0;
        for (int i = 0; i < n; i++) {
            double total = lat(r, GW) + lat(r, CO), pre;
            if (design == 0) { pre = 0; for (Dep d : PRE) pre += lat(r, d); }
            else { pre = 0; boolean deg = false;
                for (int k = 0; k < PRE.length; k++) { double v = lat(r, PRE[k]);
                    if (design == 2) {
                        if (k == 0 && v > 50) { v = 50; deg = true; }                                       // serviceability: cached answer
                        if (k == 2 && v > 80) { v = 80; deg = true; }                                       // promotion: skip auto-coupon
                        if (k == 1) { double h = lat(r, PRE[k]); if (v > 30) v = Math.min(v, 30 + h); }      // pricing: hedge after 30 ms
                    }
                    pre = Math.max(pre, v); }
                if (deg) degraded++; }
            double o = total + pre + lat(r, ORD); ours[i] = o; t[i] = o + lat(r, PAY);
        }
        Arrays.sort(t); Arrays.sort(ours);
        System.out.printf("   %-66s %8.0f ms %8.0f ms %8.0f ms %8.0f ms %9.2f%%%n", name, t[n / 2], t[(int) (n * 0.99)], ours[(int) (n * 0.99)], ours[(int) (n * 0.999)], 100.0 * degraded / n);
    }
}
