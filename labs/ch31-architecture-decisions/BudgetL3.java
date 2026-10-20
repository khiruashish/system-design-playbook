// BudgetL3.java — run with: java BudgetL3.java   (JDK 21, no dependencies)
// A QUANTITATIVE architecture decision: does the design meet the latency SLO at target load? Architects argue "sync vs
// async" with adjectives; here we DECIDE it with numbers. Checkout needs data from 4 downstream services. We compare two
// designs against a 300 ms p99 SLO at 800 requests/sec:
//   A) SEQUENTIAL — call the 4 services one after another. End-to-end time = SUM of their latencies (+ queueing).
//   B) PARALLEL   — fan out the 4 independent calls at once. End-to-end time = MAX of their latencies (+ queueing).
// Latency isn't just service time: under load each service is a queue, so we add M/M/1 queueing delay, which EXPLODES as
// utilization ρ = arrival/capacity approaches 1. That queueing term is the thing back-of-envelope guesses always miss.
public class BudgetL3 {
    // per-service: base service time (ms) and capacity (max req/s this service instance can handle)
    static final String[] SVC = { "catalog", "inventory", "pricing", "promotions" };
    static final double[] SVC_MS  = { 20, 35, 15, 25 };        // service time with no queueing
    static final double[] SVC_CAP = { 2000, 2500, 3000, 2000 }; // capacity (req/s)

    // M/M/1 mean response time at arrival rate λ against capacity μ: R = 1/(μ-λ). Returns ms; ∞ if overloaded.
    static double responseMs(double serviceMs, double capRps, double arrivalRps) {
        double mu = capRps;                       // service rate (req/s)
        if (arrivalRps >= mu) return Double.POSITIVE_INFINITY;   // ρ>=1 → unbounded queue
        double rho = arrivalRps / mu;
        // M/M/1 mean time in system = serviceTime / (1 - ρ). p99 ≈ serviceTime * ln(100)/(1-ρ)/... — use a tail factor.
        double meanMs = serviceMs / (1 - rho);
        double p99Ms  = serviceMs + (meanMs - serviceMs) * 4.6; // exponential tail: p99 ≈ mean-queue * ln(100)
        return p99Ms;
    }

    public static void main(String[] args) {
        double SLO = 300, TARGET_RPS = 800;
        System.out.printf("Decision: which checkout design meets a %.0f ms p99 SLO at %.0f req/s? Four downstream services.%n%n", SLO, TARGET_RPS);

        System.out.printf("   %-11s %8s %8s %6s %10s%n", "service", "svc(ms)", "cap(r/s)", "ρ", "p99(ms)");
        double[] p99 = new double[SVC.length];
        for (int i = 0; i < SVC.length; i++) {
            p99[i] = responseMs(SVC_MS[i], SVC_CAP[i], TARGET_RPS);
            System.out.printf("   %-11s %8.0f %8.0f %6.2f %10s%n", SVC[i], SVC_MS[i], SVC_CAP[i], TARGET_RPS / SVC_CAP[i],
                    Double.isInfinite(p99[i]) ? "OVERLOAD" : String.format("%.1f", p99[i]));
        }

        double seq = 0, par = 0;
        for (double x : p99) { seq += x; par = Math.max(par, x); }
        System.out.printf("%n   A) SEQUENTIAL  end-to-end p99 = sum  = %6.1f ms  →  %s%n", seq, seq <= SLO ? "MEETS SLO" : "BREACHES SLO");
        System.out.printf("   B) PARALLEL    end-to-end p99 = max  = %6.1f ms  →  %s%n", par, par <= SLO ? "MEETS SLO" : "BREACHES SLO");

        // now the capacity question: which service saturates first (lowest capacity), and how much headroom at target?
        double minCap = Double.MAX_VALUE; String bottleneck = "";
        for (int i = 0; i < SVC.length; i++) if (SVC_CAP[i] < minCap) { minCap = SVC_CAP[i]; bottleneck = SVC[i]; }
        System.out.printf("%n   Bottleneck: '%s' (capacity %.0f r/s), headroom at %.0f r/s = %.0f%%. Beyond capacity, ANY design's latency → ∞.%n",
                bottleneck, minCap, TARGET_RPS, (1 - TARGET_RPS/minCap) * 100);

        // show the queueing cliff: p99 as utilization ρ rises toward 1 (independent of which service)
        System.out.println("\n   The queueing cliff (a 35 ms service's p99 as its utilization ρ rises toward 1):");
        for (double rho : new double[]{0.5, 0.8, 0.9, 0.95, 0.99}) {
            double r = responseMs(35, 1000, rho * 1000);
            System.out.printf("        ρ=%.2f: p99 ≈ %s%n", rho, Double.isInfinite(r) ? "OVERLOAD" : String.format("%.0f ms", r));
        }

        System.out.println("\n   → the numbers decide it: SEQUENTIAL breaches the 300 ms SLO (the four p99s add up), PARALLEL meets it comfortably");
        System.out.println("     (end-to-end ≈ the slowest single call). But the deeper lesson is the queueing cliff: neither design saves you once a");
        System.out.println("     service nears its capacity — p99 rises non-linearly as ρ→1 and goes vertical (196 ms → 16 s from ρ=0.5 to 0.99). So");
        System.out.println("     the real architectural decisions are 'fan out the independent calls' AND 'keep every service's utilization well below");
        System.out.println("     1' (headroom, autoscaling). This is the arithmetic an ADR should contain — not the adjective 'scalable'.");
    }
}
