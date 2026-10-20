// TailL1.java — run with: java TailL1.java      (JDK 17+, no dependencies)
// Why tail latency dominates at scale. A. fan-out amplification: how a service's p99 becomes a request's p50 once one
// request touches N backends (Dean & Barroso, "The Tail at Scale"). B. hedged requests as the fix. C. queueing theory:
// wait time vs utilisation on a real bounded queue (the knee near 100%). D. coordinated omission — why naive load tests lie.
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TailL1 {
    static final Random R = new Random(25);
    // a per-backend latency: mostly fast, with a realistic slow tail (5% are 10-30x slower)
    static double backendLatency() {
        double base = Math.exp(R.nextGaussian() * 0.4 + Math.log(10));   // ~median 10ms
        return R.nextDouble() < 0.05 ? base + 100 + R.nextDouble() * 200 : base;
    }
    static double pct(double[] a, double q) { double[] c = a.clone(); Arrays.sort(c); return c[Math.min(c.length - 1, (int) Math.ceil(q * c.length) - 1)]; }

    public static void main(String[] args) {
        int N = 200_000;
        // baseline: one backend call
        double[] single = new double[N];
        for (int i = 0; i < N; i++) single[i] = backendLatency();
        System.out.println("A. Fan-out amplification — one slow backend makes the whole request slow");
        System.out.printf("   a single backend call: p50 %.0f ms, p95 %.0f ms, p99 %.0f ms  (5%% of calls are slow)%n", pct(single,.50), pct(single,.95), pct(single,.99));
        System.out.printf("   %-38s %8s %8s %8s%n", "request that waits for ALL of N backends", "p50", "p95", "p99");
        for (int fan : new int[]{1, 5, 20, 100}) {
            double[] req = new double[N];
            for (int i = 0; i < N; i++) { double max = 0; for (int j = 0; j < fan; j++) max = Math.max(max, backendLatency()); req[i] = max; }
            double pSlow = 1 - Math.pow(0.95, fan);
            System.out.printf("   fan-out %-30d %6.0f ms %6.0f ms %6.0f ms   (P[≥1 slow] = %.0f%%)%n", fan, pct(req,.50), pct(req,.95), pct(req,.99), pSlow*100);
        }
        System.out.println("   → at fan-out 100, ~99% of requests hit at least one slow backend: the backend's rare tail becomes the request's NORM.");
        System.out.println("     This is why one slow dependency (Ch 24 L3) wrecks a fan-out page, and why you must cut the tail, not the average.");

        System.out.println("\nB. Hedging: send a 2nd request if the 1st is slow, take the first to answer (Ch 17)");
        double[] hedged = new double[N]; long extra = 0;
        double p95single = pct(single, 0.95);
        for (int i = 0; i < N; i++) {
            double a = backendLatency();
            if (a > p95single) { double b = backendLatency(); hedged[i] = Math.min(a, b + p95single); extra++; }   // 2nd sent after p95 delay
            else hedged[i] = a;
        }
        System.out.printf("   fan-out 20 without hedge: p99 %.0f ms; single-call hedge after p95: p99 %.0f ms, at %.0f%% extra load%n",
                fanoutP99(20), pct(hedged, .99), 100.0 * extra / N);
        System.out.println("   → hedging trades a few % extra requests for a much shorter tail; cap it (only hedge above p95) so it can't double load.");

        System.out.println("\nC. Queueing theory: on a real single queue, wait time explodes as utilisation → 100% (M/M/1)");
        System.out.printf("   %-14s %-14s %-16s %-16s%n", "utilisation", "measured wait", "M/M/1 predicts", "vs 50%% util");
        double serviceMs = 5.0;
        double base = -1;
        for (double rho : new double[]{0.5, 0.7, 0.8, 0.9, 0.95, 0.99}) {
            double meanWait = measureQueueWait(serviceMs, rho, 200_000);
            double predicted = serviceMs * rho / (1 - rho);          // M/M/1 mean wait in queue = S·ρ/(1-ρ)
            if (base < 0) base = meanWait;
            System.out.printf("   %-14.0f %-14.1f %-16.1f %-16s%n", rho*100, meanWait, predicted, String.format("%.0fx", meanWait/base));
        }
        System.out.println("   → past ~80% utilisation the wait time turns a corner; running hot to \"save money\" buys a latency cliff.");
        System.out.println("     Capacity planning targets ~60-70% so a traffic spike or a lost node doesn't push you over the knee.");

        System.out.println("\nD. Coordinated omission: a load test that pauses when the server stalls hides the tail");
        // Open model: 1 request scheduled every 1 ms (1000 req/s). Normal service 0.2 ms (20% utilisation, stable).
        // At one instant the server freezes for 500 ms (one stuck request). Requests scheduled during the freeze must wait.
        int stalls = 50_000; double[] naive = new double[stalls], corrected = new double[stalls];
        double freeAt2 = 0;
        for (int i = 0; i < stalls; i++) {
            double service = (i == 20_000) ? 500.0 : 0.15 + R.nextDouble() * 0.1;   // one 500 ms freeze
            long scheduled = i;                                                     // due at ms i
            double startService = Math.max(scheduled, freeAt2);
            freeAt2 = startService + service;
            naive[i] = service;                                                     // naive tool: only the service time it observed
            corrected[i] = (startService - scheduled) + service;                   // real user-perceived: queueing delay + service
        }
        System.out.printf("   naive (per-call service time):   p99 %.0f ms, p999 %.0f ms  → \"looks fine\"%n", pct(naive,.99), pct(naive,.999));
        System.out.printf("   corrected (from scheduled time): p99 %.0f ms, p999 %.0f ms  → the stall is visible%n", pct(corrected,.99), pct(corrected,.999));
        System.out.println("   → measure latency from when a request SHOULD have started; tools that back off during a stall under-report the tail.");
    }

    static double fanoutP99(int fan) {
        double[] req = new double[100_000];
        for (int i = 0; i < req.length; i++) { double max = 0; for (int j = 0; j < fan; j++) max = Math.max(max, backendLatency()); req[i] = max; }
        return pct(req, 0.99);
    }

    /** Simulate an M/M/1 queue: Poisson arrivals at rate λ = ρ/S, exponential service mean S; return mean time waiting in queue. */
    static double measureQueueWait(double serviceMs, double rho, int n) {
        double lambda = rho / serviceMs;                 // arrivals per ms
        double t = 0, freeAt = 0, totalWait = 0;
        for (int i = 0; i < n; i++) {
            t += -Math.log(1 - R.nextDouble()) / lambda;                 // next arrival (exponential inter-arrival)
            double service = -Math.log(1 - R.nextDouble()) * serviceMs;  // exponential service time
            double startService = Math.max(t, freeAt);
            totalWait += startService - t;                               // time spent waiting in the queue
            freeAt = startService + service;
        }
        return totalWait / n;
    }
}
