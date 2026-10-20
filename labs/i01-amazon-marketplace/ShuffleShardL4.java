// L4 — Shuffle sharding (as described in the AWS Builders' Library): each customer gets a small random subset of
// workers. A "poison" customer whose requests crash every worker they touch takes out only its own subset. How many
// OTHER customers lose all of their workers (full outage) or some (degraded)? Exact combinatorics + simulation.
import java.util.*;

public class ShuffleShardL4 {
    public static void main(String[] a) {
        int customers = 10_000; Random r = new Random(404);
        System.out.printf("%,d customers; one poison customer crashes every worker it is routed to%n%n", customers);
        System.out.printf("%-44s %9s %16s %14s %14s%n", "layout", "workers", "possible shards", "full outage", "degraded");
        row("no sharding (everyone on every worker)", 8, 8, false, customers, r);
        row("4 fixed shards of 2 workers", 8, 2, false, customers, r);
        row("shuffle shards of 2 of 8 workers", 8, 2, true, customers, r);
        row("shuffle shards of 4 of 8 workers", 8, 4, true, customers, r);
        row("16 fixed shards of 5 workers", 80, 5, false, customers, r);
        row("shuffle shards of 5 of 80 workers", 80, 5, true, customers, r);
        System.out.printf("%nexact chance another customer shares ALL workers with the poison one, shuffle k of n = 1 / C(n,k):%n");
        for (int[] nk : new int[][]{{8, 2}, {8, 4}, {80, 5}}) System.out.printf("  C(%d,%d) = %,d  → %.6f%%%n", nk[0], nk[1], choose(nk[0], nk[1]), 100.0 / choose(nk[0], nk[1]));
    }

    static void row(String name, int n, int k, boolean shuffle, int customers, Random r) {
        int trials = 200; double full = 0, degraded = 0;
        for (int t = 0; t < trials; t++) {
            List<Set<Integer>> assign = new ArrayList<>();
            for (int c = 0; c < customers; c++) assign.add(shuffle ? randomSubset(n, k, r) : fixedShard(n, k, r.nextInt(n / k)));
            Set<Integer> dead = assign.get(0);                                   // customer 0 is the poison pill
            for (int c = 1; c < customers; c++) {
                long lost = assign.get(c).stream().filter(dead::contains).count();
                if (lost == assign.get(c).size()) full++; else if (lost > 0) degraded++;
            }
        }
        double others = (double) trials * (customers - 1);
        String shards = shuffle ? String.format("%,d", choose(n, k)) : String.valueOf(n / k);
        System.out.printf("%-44s %9d %16s %13.3f%% %13.2f%%%n", name, n, shards, 100 * full / others, 100 * degraded / others);
    }
    static Set<Integer> fixedShard(int n, int k, int shard) { Set<Integer> s = new HashSet<>(); for (int i = 0; i < k; i++) s.add(shard * k + i); return s; }
    static Set<Integer> randomSubset(int n, int k, Random r) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); Collections.shuffle(l, r); return new HashSet<>(l.subList(0, k)); }
    static long choose(int n, int k) { long c = 1; for (int i = 0; i < k; i++) c = c * (n - i) / (i + 1); return c; }
}
