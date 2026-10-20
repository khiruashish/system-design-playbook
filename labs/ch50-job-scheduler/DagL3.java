// DagL3.java — run with: java DagL3.java   (JDK 21, no dependencies)
// THE NIGHTLY FINANCE PIPELINE. 40 tasks (settlement files → ledger postings → reconciliation → GST reports → seller
// payouts → dashboards) with dependencies, run every night from 00:00 on 6 worker slots, for 60 nights (fixed seed).
// Durations vary night to night (lognormal, σ 0.35) and on heavy nights (month-end, sales: 1 in 6) everything takes 60%
// longer; each attempt fails 4% of the time (a flaky SFTP drop, a lock timeout) after running half its time.
// Payouts must be done by 06:00.
//   D1 cron: each task has a fixed start time = the planned schedule from AVERAGE durations; it starts then (or when a
//      slot frees) whether or not its inputs are ready; a failed task is not retried until someone notices in the morning
//   D2 cron with 50% padding between planned starts (the usual fix)
//   D3 a DAG scheduler: a task starts when all its inputs have succeeded; failed attempts retry after 5 minutes (max 3);
//      free slots go to ready tasks in FIFO order
//   D4 D3, but free slots go to the ready task with the longest remaining critical path
// Measured: task runs that started on missing/incomplete inputs, nights where payouts had to be re-run by hand, average
// and p95 finish time, and nights whose payouts were correct and done by 06:00.
import java.util.*;

public class DagL3 {
    static final int N = 40, SLOTS = 6;
    static int[][] deps = new int[N][]; static double[] base = new double[N];

    public static void main(String[] args) {
        Random r = new Random(50);
        for (int i = 0; i < N; i++) {                                                        // layered DAG, 1–3 deps from earlier layers
            base[i] = 8 + r.nextInt(30); int layer = i / 6; List<Integer> d = new ArrayList<>();
            if (layer > 0) { int k = 1 + r.nextInt(3); for (int j = 0; j < k; j++) { int dep = Math.max(0, (layer - 1) * 6 - r.nextInt(3) * 6) + r.nextInt(6); if (dep < i && !d.contains(dep)) d.add(dep); } }
            deps[i] = d.stream().mapToInt(Integer::intValue).toArray();
        }
        double[] crit = new double[N];                                                       // longest remaining path (by base) incl. self
        for (int i = N - 1; i >= 0; i--) { double m = 0; for (int j = i + 1; j < N; j++) for (int d : deps[j]) if (d == i) m = Math.max(m, crit[j]); crit[i] = base[i] + m; }
        double cp = 0; for (double c : crit) cp = Math.max(cp, c); double work = Arrays.stream(base).sum();
        System.out.printf("40 tasks, 6 slots; critical path %.0f min, total work %.0f min on an average night; deadline 06:00 (360 min).%n%n", cp, work);
        System.out.printf("   %-52s %14s %12s %11s %11s %13s%n", "scheduling", "runs on bad", "nights with", "avg finish", "p95 finish", "correct by");
        System.out.printf("   %-52s %14s %12s %11s %11s %13s%n", "", "inputs", "manual rerun", "", "", "06:00 (of 60)");
        String[] names = {"D1) cron times from average durations", "D2) cron times padded 50%", "D3) DAG: start when inputs succeed, auto-retry", "D4) D3 + longest critical path first"};
        for (int p = 0; p < 4; p++) run(p, names[p], crit);
        System.out.println("\n   → time-based chaining encodes a guess about durations. It 'finishes' early because it doesn't wait: on any");
        System.out.println("     night where a task runs long or fails, downstream tasks run on missing inputs, and with 40 tasks and no retries");
        System.out.println("     that is every night — someone re-runs payouts by hand each morning. Padding cuts the bad runs and still fails.");
        System.out.println("     Declaring dependencies and retrying automatically removes the bad runs; the pipeline then takes as long as the");
        System.out.println("     work really takes, so heavy nights can still miss 06:00. With limited slots, feeding the critical path first");
        System.out.println("     shortens the night. What's left is capacity planning for the heavy nights.");
    }

    static void run(int policy, String name, double[] crit) {
        Random r = new Random(5050); int badRuns = 0, manual = 0, correct = 0; double[] finish = new double[60];
        // planned start times from average durations (earliest start respecting deps, unlimited slots)
        double[] plan = new double[N];
        for (int i = 0; i < N; i++) { double s = 0; for (int d : deps[i]) s = Math.max(s, plan[d] + base[d] * (policy == 1 ? 1.5 : 1.0)); plan[i] = s; }
        for (int night = 0; night < 60; night++) {
            boolean heavy = r.nextInt(6) == 0; boolean nightBad; double[] dur = new double[N]; boolean[][] fails = new boolean[N][3];
            for (int i = 0; i < N; i++) { dur[i] = base[i] * Math.exp(0.35 * r.nextGaussian()) * (heavy ? 1.6 : 1); for (int a = 0; a < 3; a++) fails[i][a] = r.nextDouble() < 0.04; }
            double[] end = new double[N]; boolean[] ok = new boolean[N], bad = new boolean[N]; double[] slot = new double[SLOTS];
            if (policy <= 1) {
                Integer[] order = new Integer[N]; for (int i = 0; i < N; i++) order[i] = i; Arrays.sort(order, Comparator.comparingDouble(i -> plan[i]));
                for (int i : order) {
                    int sl = 0; for (int k = 1; k < SLOTS; k++) if (slot[k] < slot[sl]) sl = k;
                    double start = Math.max(plan[i], slot[sl]);
                    boolean inputsBad = false; for (int d : deps[i]) if (!ok[d] || end[d] > start || bad[d]) inputsBad = true;
                    if (inputsBad) { badRuns++; bad[i] = true; }
                    if (fails[i][0]) { end[i] = start + dur[i] / 2; ok[i] = false; } else { end[i] = start + dur[i]; ok[i] = true; }
                    slot[sl] = end[i];
                }
                boolean rerun = false; for (int i = 0; i < N; i++) if (bad[i] || !ok[i]) rerun = true; if (rerun) manual++; nightBad = rerun;
            } else {
                int[] attempts = new int[N]; boolean[] started = new boolean[N]; double[] readyAt = new double[N]; Arrays.fill(readyAt, -1);
                int done = 0; double now = 0; PriorityQueue<double[]> running = new PriorityQueue<>(Comparator.comparingDouble(x -> x[0])); int free = SLOTS;
                boolean gaveUp = false;
                while (done < N && !gaveUp) {
                    List<Integer> ready = new ArrayList<>();
                    for (int i = 0; i < N; i++) if (!ok[i] && !started[i] && readyAt[i] <= now + 1e-9) { boolean all = true; for (int d : deps[i]) if (!ok[d]) all = false; if (all) { if (readyAt[i] < 0) readyAt[i] = now; ready.add(i); } }
                    if (policy == 3) ready.sort((a, b) -> Double.compare(crit[b], crit[a])); else ready.sort(Comparator.comparingDouble((Integer i) -> readyAt[i]).thenComparingInt(i -> i));
                    while (free > 0 && !ready.isEmpty()) { int i = ready.remove(0); started[i] = true; free--;
                        boolean f = fails[i][attempts[i]]; running.add(new double[]{now + (f ? dur[i] / 2 : dur[i]), i, f ? 1 : 0}); }
                    if (running.isEmpty()) { double nxt = Double.MAX_VALUE; for (int i = 0; i < N; i++) if (!ok[i] && !started[i] && readyAt[i] > now) nxt = Math.min(nxt, readyAt[i]); if (nxt == Double.MAX_VALUE) { gaveUp = true; break; } now = nxt; continue; }
                    double[] ev = running.poll(); now = ev[0]; free++; int i = (int) ev[1];
                    if (ev[2] == 1) { attempts[i]++; started[i] = false; if (attempts[i] >= 3) { gaveUp = true; } else readyAt[i] = now + 5; }
                    else { ok[i] = true; end[i] = now; done++; }
                }
                if (gaveUp) manual++; nightBad = gaveUp;
            }
            double fin = 0; for (int i = 0; i < N; i++) fin = Math.max(fin, end[i]); finish[night] = fin; if (fin <= 360 && !nightBad) correct++;
        }
        double[] f = finish.clone(); Arrays.sort(f); double avg = Arrays.stream(f).average().orElse(0);
        System.out.printf("   %-52s %,14d %12d %8s %11s %13d%n", name, badRuns, manual, hhmm(avg), hhmm(f[56]), correct);
    }
    static String hhmm(double min) { return String.format("%02d:%02d", (int) min / 60, (int) min % 60); }
}
