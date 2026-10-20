// RiskL3.java — run with: java RiskL3.java   (JDK 21, no dependencies)
// PRE-TRADE CREDIT CHECKS. 2,000 wholesale buyers trade on credit; each has a limit (₹50k–₹5 lakh, fixed seed) on the
// value of their open buy orders + unsettled purchases. 200,000 buy orders over 60 s arrive through 4 gateways;
// 50 "aggressive" buyers spray orders across all gateways in bursts. An order may be accepted only if exposure + its
// value ≤ limit. Average load 3,333 orders/s; bursts well above. Three places to check:
//   K1 at each gateway, against an exposure cache refreshed from the engine every 500 ms (fast, local)
//   K2 at a central risk service: a synchronous call per order (~1 ms round trip); the service processes checks one
//      at a time (0.25 ms each, a locked read-modify-write of the exposure) — modelled as a single queue
//   K3 inside the sequenced engine path: exposure kept in memory per account, updated deterministically on accept,
//      fill and cancel (the order is checked in sequence, right before matching)
// Measured: accounts that ended above their limit, the worst overspend, extra latency added per order (p99; K1 and K3
// use assumed in-process costs of 10 µs and 2 µs), and the throughput ceiling the check imposes.
import java.util.*;

public class RiskL3 {
    record Order(double t, int acct, int gateway, long value) {}

    public static void main(String[] args) {
        Random r = new Random(55); int accts = 2000; long[] limit = new long[accts]; for (int a = 0; a < accts; a++) limit[a] = (50_000 + r.nextInt(450_001)) * 100L;
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) { boolean aggr = r.nextDouble() < 0.25; int a = aggr ? r.nextInt(50) : 50 + r.nextInt(accts - 50);
            double t = aggr ? (r.nextInt(60) + r.nextDouble() * 0.2) : r.nextDouble() * 60;                   // aggressive: 200 ms bursts each second
            orders.add(new Order(t, a, r.nextInt(4), (2_000 + r.nextInt(18_000)) * 100L)); }
        orders.sort(Comparator.comparingDouble(Order::t));
        System.out.println("2,000 credit accounts, 200,000 buy orders in 60 s via 4 gateways; 50 accounts burst across all gateways.\n");
        System.out.printf("   %-58s %12s %16s %14s %16s%n", "where the credit check runs", "accounts", "worst overspend", "added p99", "check ceiling");
        System.out.printf("   %-58s %12s %16s %14s %16s%n", "", "over limit", "", "latency", "(orders/s)");
        for (int k = 1; k <= 3; k++) {
            long[] exposure = new long[accts]; long[][] cache = new long[4][accts]; double nextRefresh = 0.5; double busyUntil = 0; List<Double> added = new ArrayList<>();
            Random fill = new Random(7);
            for (Order o : orders) {
                if (k == 1 && o.t() >= nextRefresh) { for (int g = 0; g < 4; g++) cache[g] = exposure.clone(); nextRefresh += 0.5; }
                boolean ok; double extra;
                switch (k) {
                    case 1 -> { ok = cache[o.gateway()][o.acct()] + o.value() <= limit[o.acct()]; if (ok) cache[o.gateway()][o.acct()] += o.value(); extra = 0.00001; }
                    case 2 -> { double start = Math.max(o.t(), busyUntil); busyUntil = start + 0.00025; extra = (start - o.t()) + 0.001; ok = exposure[o.acct()] + o.value() <= limit[o.acct()]; }   // 0.25 ms locked section per check
                    default -> { extra = 0.000002; ok = exposure[o.acct()] + o.value() <= limit[o.acct()]; }
                }
                if (ok) exposure[o.acct()] += o.value();
                if (fill.nextDouble() < 0.02) { int a = fill.nextInt(accts); exposure[a] = Math.max(0, exposure[a] - (long) (limit[a] * 0.1)); }   // settlements free credit
                added.add(extra);
            }
            int over = 0; long worst = 0; for (int a = 0; a < accts; a++) if (exposure[a] > limit[a]) { over++; worst = Math.max(worst, exposure[a] - limit[a]); }
            Collections.sort(added); double p99 = added.get((int) (added.size() * 0.99));
            String[] names = {"", "K1) at each gateway, exposure cache refreshed every 500 ms", "K2) central risk service, synchronous call per order", "K3) in the sequenced engine path, in-memory exposure"};
            String ceiling = k == 2 ? "4,000 (0.25 ms each)" : "not the limit";
            System.out.printf("   %-58s %12d %16s %11s %22s%n", names[k], over, String.format("₹%,.0f", worst / 100.0), fmt(p99), ceiling);
        }
        System.out.println("\n   → a check against a cached number is a check against the past: a buyer spraying orders through four gateways");
        System.out.println("     gets four copies of the same headroom, and every burst between refreshes slips through. A central service is");
        System.out.println("     correct but puts every order behind a network hop and one queue of checks — at a burst it queues, and the");
        System.out.println("     queue IS the latency. Putting the credit check in the sequenced path makes it both exact and cheap: it runs");
        System.out.println("     in the same single thread, in the same order, on in-memory state that the journal can rebuild.");
    }
    static String fmt(double s) { return s < 0.001 ? String.format("%.0f µs", s * 1e6) : s < 1 ? String.format("%.1f ms", s * 1e3) : String.format("%.1f s", s); }
}
