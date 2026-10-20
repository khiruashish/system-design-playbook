// FairnessL4.java — run with: java FairnessL4.java   (JDK 21, no dependencies)
// ONE JOB PLATFORM, MANY TEAMS. 50 workers shared by five tenants for one hour (fixed seed, 50 ms time steps):
//   payments   20 jobs/s × 0.2 s  (critical: payment-status checks)      slots      5 jobs/s × 0.5 s  (critical: cut-offs)
//   search     10 jobs/s × 1 s    (normal: re-index)                      analytics  2 jobs/s × 5 s    (normal)
//   marketing  200,000 jobs × 0.3 s dumped at 10:00 (bulk campaign)
// Steady load uses ~53% of the workers. At 40:00 a bug makes payments enqueue 30,000 retries at once (still "critical").
//   F1 one FIFO queue for everything
//   F2 strict priority classes: critical before normal before bulk
//   F3 fair queuing across tenants (deficit round robin, equal weights), FIFO within a tenant
//   F4 F3, but retries go to a separate lane ("payments-retries") that competes as its own tenant, and bulk lanes
//      (marketing, retries) get half the weight of critical and normal tenants
// Measured: p99 wait (enqueue → start) per tenant for normal traffic, when the marketing campaign finished, and the
// p99 wait of the retry-storm jobs themselves.
import java.util.*;

public class FairnessL4 {
    static final String[] T = {"payments", "slots", "search", "analytics", "marketing", "payments-retries"};
    static final double[] RATE = {20, 5, 10, 2, 0, 0}, DUR = {0.2, 0.5, 1.0, 5.0, 0.3, 0.2};
    static final int[] CLASS = {0, 0, 1, 1, 2, 2};                                                // 0 critical, 1 normal, 2 bulk

    public static void main(String[] args) {
        System.out.println("50 workers, 1 hour; marketing dumps 200,000 jobs at 10:00; payments bug enqueues 30,000 retries at 40:00.\n");
        System.out.printf("   %-48s %11s %11s %11s %11s %13s %12s%n", "queueing", "payments", "slots", "search", "analytics", "marketing", "payments");
        System.out.printf("   %-48s %11s %11s %11s %11s %13s %12s%n", "", "p99 wait", "p99 wait", "p99 wait", "p99 wait", "finished at", "retry storm");
        String[] names = {"F1) one FIFO queue", "F2) strict priority classes", "F3) fair queuing across tenants", "F4) F3 + retry lane, bulk lanes at half weight"};
        for (int p = 0; p < 4; p++) run(p, names[p]);
        System.out.println("\n   → with one FIFO queue, one tenant's bulk dump becomes everyone's 22-minute outage. Priority classes protect");
        System.out.println("     everyone from the marketing dump — until the payments bug floods the critical class, and every tenant behind");
        System.out.println("     it waits minutes. Fair queuing isolates tenants from each other, but not a tenant from");
        System.out.println("     itself: payments' normal checks queue behind its own 30,000 retries. Giving retries their own lane, and bulk");
        System.out.println("     lanes half the weight, keeps every first attempt fast while the campaign and the storm drain in the background.");
        System.out.println("     The price: bulk finishes later than under FIFO (which gave it everything first) — which is what 'bulk' means.");
    }

    static void run(int policy, String name) {
        Random r = new Random(50); double dt = 0.05; int steps = (int) (3600 / dt);
        List<ArrayDeque<double[]>> q = new ArrayList<>(); for (int i = 0; i < T.length; i++) q.add(new ArrayDeque<>());   // {enqueueTime, duration}
        ArrayDeque<double[]> fifo = new ArrayDeque<>();                                          // {enqueue, dur, tenant}
        List<List<Double>> waits = new ArrayList<>(); for (int i = 0; i < T.length; i++) waits.add(new ArrayList<>());
        PriorityQueue<double[]> running = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0])); int[] runningPer = new int[T.length]; List<Double> stormWait = new ArrayList<>();
        double[] deficit = new double[T.length]; boolean[] visited = new boolean[T.length]; int rr = 0; double mktDone = -1; int mktLeft = 200_000;
        for (int s = 0; s < steps; s++) {
            double now = s * dt;
            while (!running.isEmpty() && running.peek()[0] <= now) { double[] j = running.poll(); runningPer[(int) j[1]]--; if ((int) j[1] == 4 && --mktLeft == 0) mktDone = j[0]; }
            for (int t = 0; t < 5; t++) {
                int n = poisson(r, RATE[t] * dt);
                if (t == 4 && s == (int) (600 / dt)) n = 200_000;
                int storm = (t == 0 && s == (int) (2400 / dt)) ? 30_000 : 0;
                for (int k = 0; k < n + storm; k++) { double d = DUR[t] * Math.exp(0.3 * r.nextGaussian()); double tag = k >= n ? 1 : 0; if (policy == 0) fifo.add(new double[]{now, d, t, tag}); else q.get(policy == 3 && tag == 1 ? 5 : t).add(new double[]{now, d, tag}); }
            }
            int free = 50 - running.size();
            while (free > 0) {
                int t = -1; double[] job = null;
                if (policy == 0) { job = fifo.poll(); if (job != null) t = (int) job[2]; }
                else if (policy == 1) { for (int c = 0; c <= 2 && t < 0; c++) { double best = Double.MAX_VALUE; for (int i = 0; i < T.length; i++) if (CLASS[i] == c && !q.get(i).isEmpty() && q.get(i).peek()[0] < best) { best = q.get(i).peek()[0]; t = i; } } if (t >= 0) job = q.get(t).poll(); }
                else {                                                                        // deficit round robin
                    boolean any = false; for (int i = 0; i < T.length; i++) if (!q.get(i).isEmpty()) any = true;
                    if (!any) break;
                    for (int tries = 0; tries < 10_000 && t < 0; tries++) {
                        int i = rr; boolean eligible = !q.get(i).isEmpty();
                        if (eligible) {
                            if (!visited[i]) { deficit[i] += (policy == 3 && CLASS[i] < 2 ? 2 : 1); visited[i] = true; }
                            if (deficit[i] >= q.get(i).peek()[1]) { deficit[i] -= q.get(i).peek()[1]; t = i; break; }
                        } else if (q.get(i).isEmpty()) deficit[i] = 0;
                        visited[i] = false; rr = (rr + 1) % T.length;                                // quantum used up: next tenant
                    }
                    if (t >= 0) job = q.get(t).poll();
                }
                if (job == null) break;
                if (job[job.length - 1] == 0) waits.get(t).add(now - job[0]);                   // storm retries are reported separately
                else stormWait.add(now - job[0]); running.add(new double[]{now + job[1], t}); runningPer[t]++; free--;
            }
        }
        String[] p99 = new String[4];
        for (int t = 0; t < 4; t++) { List<Double> w = waits.get(t); Collections.sort(w); double v = w.isEmpty() ? 0 : w.get((int) (w.size() * 0.99)); p99[t] = v < 60 ? String.format("%.1f s", v) : String.format("%.0f min", v / 60); }
        String mk = mktDone < 0 ? String.format("%.0f%% by 60:00", 100.0 * (200_000 - mktLeft) / 200_000) : String.format("%02d:%02d", (int) mktDone / 60, (int) mktDone % 60);
        Collections.sort(stormWait); double sw = stormWait.isEmpty() ? 0 : stormWait.get((int) (stormWait.size() * 0.99));
        System.out.printf("   %-48s %11s %11s %11s %11s %13s %12s%n", name, p99[0], p99[1], p99[2], p99[3], mk, sw < 60 ? String.format("%.1f s", sw) : String.format("%.0f min", sw / 60));
    }
    static int poisson(Random r, double mu) { double l = Math.exp(-mu), p = 1; int k = 0; do { k++; p *= r.nextDouble(); } while (p > l); return k - 1; }
}
