// MonteCarloL4.java — run with: java MonteCarloL4.java   (JDK 21, no dependencies)
// A DECISION UNDER UNCERTAINTY. We must choose the compute model for a new service for the next year:
//   A) SERVERLESS — pay per request. Cheap at low volume, but cost grows linearly with usage (no ceiling).
//   B) OWNED VMs  — provision a fleet sized for peak. High fixed cost, but flat regardless of actual usage.
// The catch: next year's traffic is UNCERTAIN. A naive comparison plugs in the expected volume and declares a winner —
// hiding the risk. Instead we run a Monte Carlo: sample thousands of plausible volumes, compute each option's annual cost
// per trial, and look at the whole DISTRIBUTION — means, p10/p90, and the probability each option is cheaper. The point
// estimate and the distribution can disagree, and the distribution is the one that tells you what you're risking.
import java.util.*;

public class MonteCarloL4 {
    // cost models (annual, USD)
    static double serverlessCost(double monthlyRequestsM) {   // $ per million requests, x12 months
        return monthlyRequestsM * 12 * 16.0;                  // $16 per million requests (invocations + GB-seconds of compute)
    }
    static double vmCost(double monthlyRequestsM) {
        // provision VMs for PEAK: peak ≈ 3x average req rate; each VM handles 50 req/s of this workload; $300/VM/month;
        // +30% for ops (patching, on-call, capacity mgmt); HA floor of 6 VMs (2 per AZ x 3 AZs) even at low traffic
        double avgRps = monthlyRequestsM * 1e6 / (30.0 * 24 * 3600);
        double peakRps = avgRps * 3.0;
        int vms = Math.max(6, (int) Math.ceil(peakRps / 50.0));
        return vms * 300.0 * 12 * 1.30;                       // fixed: you pay for the fleet whether used or not
    }

    // triangular sample: min, mode, max
    static double triangular(Random r, double lo, double mode, double hi) {
        double u = r.nextDouble(), f = (mode - lo) / (hi - lo);
        return u < f ? lo + Math.sqrt(u * (hi - lo) * (mode - lo))
                     : hi - Math.sqrt((1 - u) * (hi - lo) * (hi - mode));
    }

    public static void main(String[] args) {
        Random r = new Random(7);
        int N = 200_000;
        // uncertain monthly volume (millions of requests): pessimistic 40, likely 120, optimistic 400 (adoption unknown)
        double lo = 40, mode = 120, hi = 400;

        System.out.println("Decision under uncertainty: Serverless vs owned VMs for next year. Monthly volume is UNCERTAIN");
        System.out.printf("   (triangular: pessimistic %.0fM, likely %.0fM, optimistic %.0fM requests/month).%n%n", lo, mode, hi);

        // 1) the naive POINT estimate at the expected (mode) volume
        double pe = mode;
        System.out.printf("   Naive point estimate at the 'likely' %.0fM/mo:  serverless $%,.0f  vs  VMs $%,.0f  →  %s cheaper%n%n",
                pe, serverlessCost(pe), vmCost(pe), serverlessCost(pe) < vmCost(pe) ? "SERVERLESS" : "VMs");

        // 2) the Monte Carlo over the uncertain volume
        double[] sCost = new double[N], vCost = new double[N]; int sWins = 0;
        double crossover = crossoverVolume();
        for (int i = 0; i < N; i++) {
            double vol = triangular(r, lo, mode, hi);
            sCost[i] = serverlessCost(vol); vCost[i] = vmCost(vol);
            if (sCost[i] < vCost[i]) sWins++;
        }
        Arrays.sort(sCost); Arrays.sort(vCost);
        System.out.println("   Monte Carlo (" + String.format("%,d", N) + " trials over the uncertain volume):");
        System.out.printf("        %-12s %12s %12s %12s%n", "option", "mean $", "p10 $", "p90 $");
        System.out.printf("        %-12s %12s %12s %12s%n", "serverless", money(mean(sCost)), money(pct(sCost,10)), money(pct(sCost,90)));
        System.out.printf("        %-12s %12s %12s %12s%n", "owned VMs", money(mean(vCost)), money(pct(vCost,10)), money(pct(vCost,90)));
        System.out.printf("        P(serverless cheaper) = %.0f%%   |   P(VMs cheaper) = %.0f%%%n", 100.0*sWins/N, 100.0*(N-sWins)/N);
        System.out.printf("        break-even volume ≈ %.0fM requests/month (below → serverless wins, above → VMs win)%n%n", crossover);

        boolean pointSaysServerless = serverlessCost(pe) < vmCost(pe);
        boolean mcSaysServerless = mean(sCost) < mean(vCost);
        double pS = 100.0 * sWins / N;
        System.out.printf("   → the point estimate at the 'likely' volume says %s. The Monte Carlo says %s in expectation, and%n",
                pointSaysServerless ? "SERVERLESS" : "VMs", mcSaysServerless ? "SERVERLESS" : "VMs");
        System.out.printf("     serverless is cheaper in only %.0f%% of plausible futures. Why they disagree: the volume distribution is%n", pS);
        System.out.println("     RIGHT-SKEWED (the 'likely' 120M is below the mean, because upside adoption scenarios stretch to 400M), and");
        System.out.println("     serverless cost grows linearly with volume with no ceiling while the VM fleet has a high HA floor but a much");
        System.out.println("     lower marginal cost. Plugging in the mode ignores the fat upper tail — exactly where serverless gets expensive");
        System.out.printf("     (compare the p90s). Decision: prefer VMs, OR start serverless with a pre-agreed migration trigger at ~%.0fM/mo.%n", crossover);
        System.out.println("     Either is defensible; what is NOT defensible is the single-number comparison that hid the skew and the break-even.");
    }

    static double crossoverVolume() {   // solve serverlessCost(v) == vmCost(v) numerically
        for (double v = 1; v < 2000; v += 0.5) if (serverlessCost(v) >= vmCost(v)) return v;
        return -1;
    }
    static double mean(double[] a) { double s = 0; for (double x : a) s += x; return s / a.length; }
    static double pct(double[] sorted, int p) { return sorted[(int) Math.min(sorted.length - 1, (long) (p / 100.0 * sorted.length))]; }
    static String money(double v) { return String.format("$%,.0f", v); }
}
