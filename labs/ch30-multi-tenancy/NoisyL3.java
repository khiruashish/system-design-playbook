// NoisyL3.java — run with: java NoisyL3.java   (JDK 21, no dependencies)
// THE NOISY-NEIGHBOR PROBLEM and its fix. Three tenants share ONE worker (a thread pool / DB connection / CPU). The
// "noisy" tenant dumps a 300-task burst at t=0; two "quiet" tenants submit a few tasks each, spread over time. We run the
// same workload under two scheduling policies and measure each tenant's WAIT time (completion tick - arrival tick):
//   FIFO  — one shared queue in arrival order. The burst lands first, so the quiet tenants' tasks sit behind 300 others.
//   FAIR  — one queue PER TENANT, served round-robin (weighted fair queuing). Each tenant gets a turn, so a quiet tenant
//           waits at most a few ticks regardless of how much the noisy one submits. The noisy tenant still gets its full
//           share — it just can't steal everyone else's. This is a deterministic discrete-time simulation (reproducible).
import java.util.*;

public class NoisyL3 {
    record Task(String tenant, int arrival) { }
    record Metrics(String tenant, double mean, long max, int n) { }

    // one unit of service per tick; returns per-tenant wait stats
    static Map<String,Metrics> simulate(boolean fair, List<Task> arrivals) {
        // group arrivals by tick for admission
        Map<Integer,List<Task>> byTick = new TreeMap<>();
        int lastArrival = 0;
        for (Task t : arrivals) { byTick.computeIfAbsent(t.arrival(), k -> new ArrayList<>()).add(t); lastArrival = Math.max(lastArrival, t.arrival()); }

        Deque<Task> fifo = new ArrayDeque<>();
        Map<String,Deque<Task>> perTenant = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();               // tenant round-robin order
        Map<String,List<Long>> waits = new LinkedHashMap<>();

        int tick = 0, pending = arrivals.size(), done = 0, rr = 0;
        while (done < pending) {
            // admit everything that arrived at this tick
            List<Task> arriving = byTick.get(tick);
            if (arriving != null) for (Task t : arriving) {
                if (fair) {
                    perTenant.computeIfAbsent(t.tenant(), k -> { order.add(k); return new ArrayDeque<>(); }).add(t);
                } else fifo.add(t);
                waits.computeIfAbsent(t.tenant(), k -> new ArrayList<>());
            }
            // serve one task this tick
            Task served = null;
            if (fair) {
                for (int i = 0; i < order.size(); i++) {
                    String cand = order.get((rr + i) % order.size());
                    Deque<Task> q = perTenant.get(cand);
                    if (q != null && !q.isEmpty()) { served = q.poll(); rr = (order.indexOf(cand) + 1) % order.size(); break; }
                }
            } else {
                served = fifo.poll();
            }
            if (served != null) { waits.get(served.tenant()).add((long)(tick - served.arrival())); done++; }
            tick++;
            if (tick > 100000) break;                          // safety
        }
        Map<String,Metrics> out = new LinkedHashMap<>();
        for (var e : waits.entrySet()) {
            List<Long> w = e.getValue();
            double mean = w.stream().mapToLong(Long::longValue).average().orElse(0);
            long max = w.stream().mapToLong(Long::longValue).max().orElse(0);
            out.put(e.getKey(), new Metrics(e.getKey(), mean, max, w.size()));
        }
        return out;
    }

    public static void main(String[] args) {
        // build the workload: noisy tenant bursts 300 tasks at t=0; quiet tenants submit 1 task every 10 ticks
        List<Task> arrivals = new ArrayList<>();
        for (int i = 0; i < 300; i++) arrivals.add(new Task("noisy", 0));
        for (int i = 0; i < 20; i++) { arrivals.add(new Task("quietA", i * 10)); arrivals.add(new Task("quietB", i * 10 + 3)); }
        arrivals.sort(Comparator.comparingInt(Task::arrival));

        System.out.println("Noisy neighbor: 'noisy' bursts 300 tasks at once; 'quietA'/'quietB' submit 20 tasks each, spread out.");
        System.out.println("One shared worker. Wait time = ticks a task waited before being served.\n");

        report("FIFO (one shared queue, arrival order)", simulate(false, arrivals));
        report("FAIR (per-tenant queues, round-robin)", simulate(true, arrivals));

        System.out.println("   → under FIFO the burst monopolizes the worker, so the quiet tenants — who barely use the system — suffer huge");
        System.out.println("     waits purely because of a neighbor. Fair queuing gives each tenant its own line served in turn, so a quiet");
        System.out.println("     tenant's wait stays tiny no matter how much the noisy one submits; the noisy tenant simply takes longer to drain");
        System.out.println("     its own burst. Same idea in production: per-tenant connection-pool caps, per-tenant thread limits, weighted fair");
        System.out.println("     queuing, and per-tenant rate limits (L4). Isolation of THROUGHPUT, not just of DATA (L1/L2).");
    }

    static void report(String label, Map<String,Metrics> m) {
        System.out.println("   " + label + ":");
        for (Metrics x : m.values())
            System.out.printf("        %-7s tasks=%-3d  mean wait=%7.1f ticks  max wait=%6d ticks%n", x.tenant(), x.n(), x.mean(), x.max());
        System.out.println();
    }
}
