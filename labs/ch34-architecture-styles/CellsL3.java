// CellsL3.java — run with: java CellsL3.java   (JDK 21, no dependencies)
// CELL-BASED ARCHITECTURE: containing the blast radius. The platform either runs as ONE global deployment (every city
// shares the same services, databases, caches and brokers) or as 8 CELLS — complete, independent copies of the stack,
// each serving a fixed set of cities (12.5% of customers), with deploys rolled out cell by cell in waves.
// We Monte-Carlo 10,000 simulated years of the incidents a real platform has:
//   • BAD DEPLOYS that slip past tests (12/yr). 70% break immediately; 30% are LATENT — they only trigger hours later
//     (a cache expiry, a nightly job, the first festival-size cart) — which is what a canary bake cannot catch.
//   • INFRASTRUCTURE FAILURES (6/yr): a database primary, a cache cluster, a broker. 30–90 min to recover.
//   • OVERLOAD from one city's flash sale or a poison tenant (4/yr): saturates the shared resources it lands on. 20–60 min.
// Global: every incident hits everyone. Cells: infra/overload incidents hit one cell; a bad deploy hits the cells that
// already have it when it triggers. Wave plan: cell 1 at t=0 (canary), cell 2 at +30 min, cells 3–4 at +60, 5–8 at +90.
import java.util.*;

public class CellsL3 {
    static final int CELLS = 8;
    static final double[] FAST_WAVES = {0, 30, 60, 60, 90, 90, 90, 90};            // minutes: whole rollout in 90 min
    static final double[] SLOW_WAVES = {0, 720, 1440, 1440, 2160, 2160, 2160, 2160}; // minutes: waves 12 h apart (36 h)

    // returns {customerMinutesLost (in units of 'whole-platform minutes'), worstIncidentShare}
    static double[] year(Random r, double[] waves) {
        boolean cells = waves != null;
        double lost = 0, worst = 0;
        // bad deploys
        for (int i = 0; i < 12; i++) {
            boolean latent = r.nextDouble() < 0.30;
            double trigger = latent ? 120 + r.nextDouble() * 480 : r.nextDouble() * 10;   // minutes after deploy start
            double detect = 5 + r.nextDouble() * 20, rollback = 15;
            double share;
            if (!cells) share = 1.0;
            else { int hit = 0; for (double w : waves) if (w <= trigger) hit++; share = hit / (double) CELLS; }
            // detection stops the rollout: cells not yet deployed never receive the bad version
            lost += share * (detect + rollback); worst = Math.max(worst, share);
        }
        // infrastructure failures
        for (int i = 0; i < 6; i++) {
            double dur = 30 + r.nextDouble() * 60, share = cells ? 1.0 / CELLS : 1.0;
            lost += share * dur; worst = Math.max(worst, share);
        }
        // overload from one city's flash sale / poison tenant
        for (int i = 0; i < 4; i++) {
            double dur = 20 + r.nextDouble() * 40, share = cells ? 1.0 / CELLS : 1.0;
            lost += share * dur; worst = Math.max(worst, share);
        }
        return new double[]{lost, worst};
    }

    public static void main(String[] args) {
        int Y = 10_000; double minutesPerYear = 525_600;
        System.out.printf("Blast radius: one global deployment vs %d cells with wave deploys. %,d simulated years each.%n%n", CELLS, Y);
        System.out.printf("   %-34s %18s %14s %18s %16s%n", "architecture", "platform-min lost", "p95 year", "effective avail.", "worst incident");
        String[] names = {"one global deployment", "8 cells, waves over 90 min", "8 cells, waves over 36 h"};
        double[][] plans = {null, FAST_WAVES, SLOW_WAVES};
        for (int d = 0; d < 3; d++) {
            Random r = new Random(2026);
            double[] lost = new double[Y]; double worst = 0;
            for (int y = 0; y < Y; y++) { double[] res = year(r, plans[d]); lost[y] = res[0]; worst = Math.max(worst, res[1]); }
            Arrays.sort(lost);
            double mean = Arrays.stream(lost).average().orElse(0);
            System.out.printf("   %-34s %14.0f min %10.0f min %17.4f%% %14.1f%%%n", names[d], mean, lost[(int) (Y * 0.95)],
                    100 * (1 - mean / minutesPerYear), worst * 100);
        }
        for (int d = 1; d < 3; d++) {
            Random r = new Random(7); int latentN = 0, allCells = 0; double share = 0;
            for (int i = 0; i < 200_000; i++) {
                if (r.nextDouble() >= 0.30) { r.nextDouble(); continue; }
                double trigger = 120 + r.nextDouble() * 480; int hit = 0;
                for (double w : plans[d]) if (w <= trigger) hit++;
                latentN++; if (hit == CELLS) allCells++; share += hit / (double) CELLS;
            }
            System.out.printf("%n   %s: latent bugs had reached all %d cells in %.0f%% of cases; mean share hit %.1f%%",
                    names[d], CELLS, 100.0 * allCells / latentN, 100 * share / latentN);
        }
        System.out.println();

        System.out.println("\n   → cells cut expected customer impact by ~4x because the most frequent large incidents — infrastructure failures and");
        System.out.println("     one city's overload — are confined to one cell (12.5% of customers), and an immediately-breaking deploy is caught in");
        System.out.println("     the canary cell. But a fast 90-minute rollout gives NO protection against latent bugs: by the time one triggers,");
        System.out.println("     every cell has it, so the worst incident is still 100%. Spacing the waves 12 h apart turns latent bugs into");
        System.out.println("     one-cell incidents too — the cost is slower delivery (a change takes 1.5 days to reach everyone), so reserve slow");
        System.out.println("     waves for risky changes and use per-cell feature flags for the rest. Cells are not free: duplicated infrastructure,");
        System.out.println("     a routing layer, cross-cell reporting, and customers who move between cities — they pay off at large scale.");
    }
}
