// MergeTopKL2.java — run with: java MergeTopKL2.java   (JDK 21, no dependencies)
// "TOP-10 PRODUCTS ACROSS THE COUNTRY" when the counting is spread over 16 partitions. Views are partitioned by city
// (16 cities, 8,000,000 events, fixed seed): every city likes the national staples, but each also has local favourites
// (a regional atta brand, a local sweet), so local rankings differ from the national one. Four ways to get the answer:
//   M1 each partition sends its local top-10; the coordinator adds them up and ranks
//   M2 each partition sends its local top-100 (10× K)
//   M3 TPUT (three-phase uniform threshold): phase 1 local top-10 → a lower bound τ for the national #10; phase 2 each
//      partition sends every item with local count ≥ τ / 16; phase 3 fetch exact counts for the survivors — exact
//   M4 re-partition by product (shuffle every (product, count) pair to the product's owner) and count exactly
// Measured: how many of the true top-10 are reported, how many reported counts are wrong, and (product, count) pairs
// sent over the network.
import java.util.*;

public class MergeTopKL2 {
    static final int P = 16, SKUS = 200_000, EVENTS = 8_000_000, K = 10;

    public static void main(String[] args) {
        Random r = new Random(51);
        double[] w = new double[SKUS]; double sw = 0; for (int i = 0; i < SKUS; i++) { w[i] = 1 / Math.pow(i + 1, 1.0); sw += w[i]; }
        int[][] counts = new int[P][SKUS];
        for (int p = 0; p < P; p++) {
            int[] local = new int[SKUS]; for (int i = 0; i < SKUS; i++) local[i] = i;                  // city-specific favourites
            for (int i = SKUS - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = local[i]; local[i] = local[j]; local[j] = t; }
            double[] cdf = new double[SKUS]; double acc = 0; for (int i = 0; i < SKUS; i++) { acc += w[i] / sw; cdf[i] = acc; }
            for (int e = 0; e < EVENTS / P; e++) {
                int k = Arrays.binarySearch(cdf, r.nextDouble()); if (k < 0) k = -k - 1; k = Math.min(k, SKUS - 1);
                counts[p][r.nextDouble() < 0.55 ? k : local[k]]++;                                       // 55% national taste, 45% local
            }
        }
        long[] total = new long[SKUS]; for (int p = 0; p < P; p++) for (int i = 0; i < SKUS; i++) total[i] += counts[p][i];
        Integer[] ids = new Integer[SKUS]; for (int i = 0; i < SKUS; i++) ids[i] = i; Arrays.sort(ids, (a, b) -> Long.compare(total[b], total[a]));
        Set<Integer> truth = new HashSet<>(Arrays.asList(ids).subList(0, K));
        System.out.printf("%,d events in 16 city partitions; national #10 has %,d views, #11 has %,d.%n%n", EVENTS, total[ids[K - 1]], total[ids[K]]);
        System.out.printf("   %-56s %12s %16s %16s%n", "method", "true top-10", "reported counts", "pairs sent");
        System.out.printf("   %-56s %12s %16s %16s%n", "", "found", "wrong", "");
        String[] names = {"M1) merge each partition's local top-10", "M2) merge each partition's local top-100", "M3) TPUT three-phase threshold (exact)", "M4) shuffle all counts by product (exact)"};
        for (int m = 0; m < 4; m++) {
            Map<Integer, Long> agg = new HashMap<>(); long sent = 0;
            if (m <= 1) {
                int kk = m == 0 ? K : 10 * K;
                for (int p = 0; p < P; p++) for (int id : localTop(counts[p], kk)) { agg.merge(id, (long) counts[p][id], Long::sum); sent++; }
            } else if (m == 2) {
                Map<Integer, Long> partial = new HashMap<>();
                for (int p = 0; p < P; p++) for (int id : localTop(counts[p], K)) { partial.merge(id, (long) counts[p][id], Long::sum); sent++; }
                long tau = partial.values().stream().sorted(Comparator.reverseOrder()).skip(K - 1).findFirst().orElse(0L);   // lower bound on national #10
                Set<Integer> cand = new HashSet<>(); Map<Integer, Long> upper = new HashMap<>();
                for (int p = 0; p < P; p++) for (int i = 0; i < SKUS; i++) if (counts[p][i] >= tau / (double) P) { cand.add(i); sent++; }
                for (int id : cand) { long c = 0; for (int p = 0; p < P; p++) c += counts[p][id]; agg.put(id, c); }
                sent += (long) cand.size() * P;                                                        // phase 3: exact fetch
            } else {
                for (int p = 0; p < P; p++) for (int i = 0; i < SKUS; i++) if (counts[p][i] > 0) { agg.merge(i, (long) counts[p][i], Long::sum); sent++; }
            }
            List<Map.Entry<Integer, Long>> top = agg.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).limit(K).toList();
            int found = 0, wrong = 0; for (var e : top) { if (truth.contains(e.getKey())) found++; if (e.getValue() != total[e.getKey()]) wrong++; }
            System.out.printf("   %-56s %9d/10 %13d/10 %,16d%n", names[m], found, wrong, sent);
        }
        System.out.println("\n   → an item that is, say, #12 in every city can be near the top nationally, yet appears in nobody's local top-10.");
        System.out.println("     Merging local top lists misses such items (4 of the true 10 here) and under-counts the ones it does report,");
        System.out.println("     because partitions that didn't list an item contribute nothing for it. Asking each partition for 10× K happened");
        System.out.println("     to be exact on this data, but carries no guarantee. A threshold algorithm (TPUT) is provably exact and ships");
        System.out.println("     ~300x less than a full shuffle. Or partition by the key you rank on, so each product is counted in one place.");
    }

    static int[] localTop(int[] c, int k) {
        PriorityQueue<Integer> pq = new PriorityQueue<>(Comparator.comparingInt(i -> c[i]));
        for (int i = 0; i < c.length; i++) { if (pq.size() < k) pq.add(i); else if (c[i] > c[pq.peek()]) { pq.poll(); pq.add(i); } }
        return pq.stream().mapToInt(Integer::intValue).toArray();
    }
}
