// ContentionL2.java — run with: java ContentionL2.java   (JDK 21, no dependencies)
// AGGREGATE SIZE vs CONTENTION. An aggregate is the unit of consistency AND the unit of concurrency: each transaction
// loads one aggregate, changes it, and saves it with an optimistic version check. Two concurrent transactions on the SAME
// aggregate → one wins, the other gets a version conflict and retries. So how you draw the aggregate boundary decides how
// many writes can proceed in parallel. We process 20,000 stock reservations for a dark store (300 SKUs, realistic Zipf
// skew — a few SKUs like milk and bread get most orders) with 16 transactions in flight at a time, under three designs:
//   A) ONE aggregate for the whole store's inventory        ("StoreInventory" — the tempting OO model)
//   B) ONE aggregate per SKU                                 (the invariant "stock ≥ 0" is per SKU, so this is enough)
//   C) per SKU, with the 5 hottest SKUs' stock split into 8 sub-aggregates each (stock buckets) — for the hot-spot tail
// Deterministic discrete simulation: each round, up to 16 pending commands run concurrently; per aggregate, one commits
// and the rest conflict and go back to the head of the queue.
import java.util.*;
import java.util.function.*;

public class ContentionL2 {
    record Cmd(int id, int sku, int bucket) {}

    static int[] zipfSkus(int n, int skus, double s, Random r) {
        double[] cdf = new double[skus]; double sum = 0;
        for (int i = 0; i < skus; i++) { sum += 1.0 / Math.pow(i + 1, s); cdf[i] = sum; }
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            double u = r.nextDouble() * sum; int lo = 0, hi = skus - 1;
            while (lo < hi) { int mid = (lo + hi) / 2; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
            out[k] = lo;
        }
        return out;
    }

    // returns {rounds, conflicts}
    static long[] run(List<Cmd> cmds, Function<Cmd, String> aggregateOf, int inFlight) {
        Deque<Cmd> queue = new ArrayDeque<>(cmds);
        long rounds = 0, conflicts = 0;
        while (!queue.isEmpty()) {
            rounds++;
            List<Cmd> batch = new ArrayList<>();
            for (int i = 0; i < inFlight && !queue.isEmpty(); i++) batch.add(queue.pollFirst());
            Set<String> committed = new HashSet<>();
            List<Cmd> retry = new ArrayList<>();
            for (Cmd c : batch) {
                if (committed.add(aggregateOf.apply(c))) continue;    // first writer to this aggregate commits
                conflicts++; retry.add(c);                            // version conflict → retry
            }
            for (int i = retry.size() - 1; i >= 0; i--) queue.addFirst(retry.get(i));
        }
        return new long[]{rounds, conflicts};
    }

    public static void main(String[] args) {
        int N = 20_000, SKUS = 300, IN_FLIGHT = 16, HOT = 5, BUCKETS = 8;
        Random r = new Random(33);
        int[] skus = zipfSkus(N, SKUS, 1.1, r);
        List<Cmd> cmds = new ArrayList<>();
        for (int i = 0; i < N; i++) cmds.add(new Cmd(i, skus[i], r.nextInt(BUCKETS)));
        long hotShare = Arrays.stream(skus).filter(s -> s < HOT).count();

        System.out.printf("%,d stock reservations over %d SKUs (Zipf s=1.1: top %d SKUs = %.0f%% of orders), %d transactions in flight.%n%n",
                N, SKUS, HOT, 100.0 * hotShare / N, IN_FLIGHT);
        System.out.printf("   %-52s %9s %11s %12s %14s%n", "aggregate design", "rounds", "conflicts", "wasted work", "vs ideal");
        long ideal = (N + IN_FLIGHT - 1) / IN_FLIGHT;
        String[] names = { "A) one StoreInventory aggregate", "B) one aggregate per SKU",
                           "C) per SKU + hot SKUs split into 8 stock buckets" };
        List<Function<Cmd, String>> designs = List.of(
            c -> "store",
            c -> "sku-" + c.sku(),
            c -> c.sku() < HOT ? "sku-" + c.sku() + "#" + c.bucket() : "sku-" + c.sku());
        for (int d = 0; d < designs.size(); d++) {
            long[] res = run(cmds, designs.get(d), IN_FLIGHT);
            double wasted = 100.0 * res[1] / (res[1] + N);
            System.out.printf("   %-52s %,9d %,11d %11.1f%% %13.1fx%n", names[d], res[0], res[1], wasted, (double) res[0] / ideal);
        }
        System.out.printf("   %-52s %,9d %11s%n", "(ideal: 16 commits every round)", ideal, "");

        System.out.println("\n   → A serializes everything: the store-wide aggregate lets exactly ONE reservation commit per round, so throughput");
        System.out.println("     collapses to 1/16 of capacity and almost all concurrent work is thrown away as version conflicts. There is no");
        System.out.println("     invariant spanning SKUs that needed that boundary — 'stock ≥ 0' is per SKU. B draws the boundary around the");
        System.out.println("     invariant and is 5x faster than A, yet still runs at only ~31% of capacity: with 43% of orders on 5 SKUs, milk and");
        System.out.println("     bread keep colliding. C splits only those hot SKUs' stock into buckets (each bucket enforces stock ≥ 0 on its");
        System.out.println("     share), paying for it with allocation logic (rebalance when a bucket runs dry) and reaching ~91% of capacity.");
        System.out.println("     Rule: make aggregates as SMALL as the invariants allow, reference other aggregates by ID, and let rules that");
        System.out.println("     span aggregates be eventually consistent via domain events.");
    }
}
