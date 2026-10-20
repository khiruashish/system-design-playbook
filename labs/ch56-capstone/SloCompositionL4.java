// SloCompositionL4.java — run with: java SloCompositionL4.java   (JDK 21, no dependencies)
// CAN CHECKOUT PROMISE 99.95%? Each service on its own meets 99.95% (≈ 22 min of outage a month): outages arrive
// randomly (mean 30 min time-to-repair, fixed seed) and independently — except where services share infrastructure.
// A journey is down whenever ANY of its hard dependencies is down (the dependency sets are L3's). One simulated year,
// minute by minute:
//   A1 AS-BUILT checkout: 16 hard dependencies (L3), each an independent 99.95% service
//   A2 DESIGNED checkout: 10 hard dependencies (L3)
//   A3 A2, but all 10 share ONE PostgreSQL cluster (99.95%) — the "independent" services aren't
//   A4 A2 split into 4 cells (city groups), each with its own copy of every service and database: an outage hits one
//      cell's customers (traffic-weighted availability)
//   A5 / A6 A2 with each critical service engineered to 99.99% / 99.995%
// Measured: availability, outage minutes per month, months that broke a 99.95% SLO (21.6 min monthly budget), and the
// largest share of customers down at the same moment.
import java.util.*;

public class SloCompositionL4 {
    static final int MIN_PER_YEAR = 365 * 24 * 60;

    public static void main(String[] args) {
        System.out.println("each service 99.95% on its own; outages random, mean 30 min; one simulated year; 99.95% budget = 21.6 min/month.\n");
        System.out.printf("   %-56s %13s %12s %14s %18s%n", "checkout journey", "availability", "outage", "months over", "most customers");
        System.out.printf("   %-56s %13s %12s %14s %18s%n", "", "(traffic-", "min/month", "99.95% budget", "down at once");
        System.out.printf("   %-56s %13s %12s %14s %18s%n", "", "weighted)", "", "(of 12)", "");
        run("A1) as-built: 16 hard dependencies", 16, false, 1, 0.9995);
        run("A2) designed: 10 hard dependencies", 10, false, 1, 0.9995);
        run("A3) designed, but all 10 share one PostgreSQL cluster", 10, true, 1, 0.9995);
        run("A4) designed, 4 independent cells", 10, false, 4, 0.9995);
        run("A5) designed, each critical service at 99.99%", 10, false, 1, 0.9999);
        run("A6) designed, each critical service at 99.995%", 10, false, 1, 0.99995);
        System.out.printf("%n   rule of thumb: n independent hard dependencies at availability a → a^n: 0.9995^16 = %.4f%%, 0.9995^10 = %.4f%%%n", 100 * Math.pow(0.9995, 16), 100 * Math.pow(0.9995, 10));
        System.out.println("\n   → a journey's availability is at best the PRODUCT of its hard dependencies': neither sixteen nor ten services at");
        System.out.println("     99.95% can make a 99.95% checkout — every month blows the budget. Cutting hard edges (L3) helps; sharing one");
        System.out.println("     database quietly adds a dependency to every journey. Cells don't make outages rarer (same availability) — they");
        System.out.println("     make each one smaller: a quarter of customers per outage, half at worst when two cells failed together, never");
        System.out.println("     all. Even each critical service at 99.995% left this simulated year at 99.94% with half the months over budget:");
        System.out.println("     meeting 99.95% end to end takes both an extra nine on every critical service AND fewer of them — which is why");
        System.out.println("     the critical set must be small, and why the SLO for a journey has to be set with this arithmetic in hand.");
    }

    static void run(String name, int deps, boolean sharedDb, int cells, double avail) {
        Random r = new Random(56); double downWeighted = 0; double[] monthDown = new double[12]; int[] cellsDown = new int[MIN_PER_YEAR];
        for (int c = 0; c < cells; c++) {
            boolean[] down = new boolean[MIN_PER_YEAR];
            int comps = deps + (sharedDb ? 1 : 0);
            for (int k = 0; k < comps; k++) {                                                       // alternating up/down renewal process per component
                double mttr = 30, mtbf = mttr * (avail / (1 - avail)); double t = r.nextDouble() * mtbf;
                while (t < MIN_PER_YEAR) { double rep = -Math.log(1 - r.nextDouble()) * mttr; for (int m = (int) t; m < Math.min(MIN_PER_YEAR, t + rep); m++) down[m] = true; t += rep - Math.log(1 - r.nextDouble()) * mtbf; }
            }
            for (int m = 0; m < MIN_PER_YEAR; m++) if (down[m]) { cellsDown[m]++; downWeighted += 1.0 / cells; monthDown[Math.min(11, m / (MIN_PER_YEAR / 12))] += 1.0 / cells; }
        }
        int over = 0; for (double d : monthDown) if (d > 21.6) over++; int worst = 0; for (int x : cellsDown) worst = Math.max(worst, x);
        System.out.printf("   %-56s %12.3f%% %12.1f %14d %17.0f%%%n", name, 100 * (1 - downWeighted / MIN_PER_YEAR), downWeighted / 12, over, 100.0 * worst / cells);
    }
}
