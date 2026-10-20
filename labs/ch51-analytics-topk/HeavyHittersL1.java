// HeavyHittersL1.java — run with: java -Xmx2g HeavyHittersL1.java   (JDK 21, no dependencies)
// "WHAT ARE THE 100 MOST-VIEWED PRODUCTS RIGHT NOW?" 20,000,000 product-view events over 1,000,000 SKUs, Zipf-distributed
// (s = 1.05, fixed seed) — a few staples get most views, a long tail gets a handful. One counting node, four methods:
//   H1 exact: a hash map of every SKU's count
//   H2 Count-Min sketch (4 rows × 65,536 counters) + a 100-entry heap of candidates by estimated count
//   H3 Space-Saving with 1,000 counters (when full, the smallest counter is evicted and its count inherited)
//   H4 1% sampling, counts scaled × 100
// Measured: recall of the true top-100, mean relative error of the reported counts for those items, memory for the
// counting state, and real throughput.
import java.util.*;

public class HeavyHittersL1 {
    static final int SKUS = 1_000_000, EVENTS = 20_000_000, K = 100;

    public static void main(String[] args) {
        double[] cdf = new double[SKUS]; double sum = 0;
        for (int i = 0; i < SKUS; i++) { sum += 1 / Math.pow(i + 1, 1.05); cdf[i] = sum; }
        for (int i = 0; i < SKUS; i++) cdf[i] /= sum;
        int[] perm = new int[SKUS]; for (int i = 0; i < SKUS; i++) perm[i] = i;
        Random r = new Random(51); for (int i = SKUS - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = perm[i]; perm[i] = perm[j]; perm[j] = t; }   // popular SKUs get random ids
        int[] stream = new int[EVENTS];
        for (int e = 0; e < EVENTS; e++) { int k = Arrays.binarySearch(cdf, r.nextDouble()); if (k < 0) k = -k - 1; stream[e] = perm[Math.min(k, SKUS - 1)]; }
        // ground truth
        int[] truth = new int[SKUS]; for (int s : stream) truth[s]++;
        Integer[] ids = new Integer[SKUS]; for (int i = 0; i < SKUS; i++) ids[i] = i;
        Arrays.sort(ids, (a, b) -> Integer.compare(truth[b], truth[a]));
        Set<Integer> top = new HashSet<>(); for (int i = 0; i < K; i++) top.add(ids[i]);
        int distinct = 0; for (int c : truth) if (c > 0) distinct++;
        System.out.printf("%,d events, %,d distinct SKUs seen; the top-100 hold %.1f%% of views; #100 has %,d views, #101 has %,d.%n%n",
                EVENTS, distinct, 100.0 * Arrays.stream(ids, 0, K).mapToLong(i -> truth[i]).sum() / EVENTS, truth[ids[K - 1]], truth[ids[K]]);
        System.out.printf("   %-52s %12s %16s %14s %14s%n", "method", "top-100", "count error", "state size", "throughput");
        System.out.printf("   %-52s %12s %16s %14s %14s%n", "", "recall", "(mean, top-100)", "", "(M events/s)");
        for (int m = 0; m < 4; m++) {
            long t0 = System.nanoTime(); Map<Integer, Long> est; long bytes;
            switch (m) {
                case 0 -> { HashMap<Integer, Long> h = new HashMap<>(); for (int s : stream) h.merge(s, 1L, Long::sum); est = topOf(h); bytes = (long) h.size() * 80; }  // ~80 B per boxed entry
                case 1 -> { CountMin cm = new CountMin(4, 1 << 16); TopHeap th = new TopHeap(K); for (int s : stream) { long e = cm.add(s); th.offer(s, e); } est = th.map(); bytes = 4L * (1 << 16) * 8 + K * 48; }
                case 2 -> { SpaceSaving ss = new SpaceSaving(1000); for (int s : stream) ss.add(s); est = ss.top(K); bytes = 1000L * 48; }
                default -> { HashMap<Integer, Long> h = new HashMap<>(); Random sr = new Random(7); for (int s : stream) if (sr.nextInt(100) == 0) h.merge(s, 100L, Long::sum); est = topOf(h); bytes = (long) h.size() * 80; }
            }
            double secs = (System.nanoTime() - t0) / 1e9;
            int hit = 0; double err = 0; for (int id : top) { if (est.containsKey(id)) hit++; }
            for (int i = 0; i < K; i++) { int id = ids[i]; long e = m == 1 ? est.getOrDefault(id, 0L) : est.getOrDefault(id, 0L); err += Math.abs(e - truth[id]) / (double) truth[id]; }
            String[] names = {"H1) exact hash map of every SKU", "H2) Count-Min 4×65,536 + top-100 heap", "H3) Space-Saving, 1,000 counters", "H4) 1% sample × 100"};
            System.out.printf("   %-52s %11d%% %15.2f%% %14s %14.1f%n", names[m], hit, 100 * err / K, human(bytes), EVENTS / secs / 1e6);
        }
        System.out.println("\n   → exact counting needs state for every distinct key ever seen in the window (state size for H1 is an estimate:");
        System.out.println("     ~80 bytes per boxed map entry). Sketches keep a small, fixed state: Count-Min only over-estimates (collisions");
        System.out.println("     add), and Space-Saving keeps just the candidates that could be heavy. On a skewed stream like this both find");
        System.out.println("     the true top-100 exactly or nearly so — heavy hitters sit far above the noise. Their errors live in the tail,");
        System.out.println("     where Space-Saving's bound is N/1,000 = 20,000 views, larger than item #100's whole count. Sampling is the");
        System.out.println("     fastest but noisy near the cut. (An item missing from a method's list counts as 100% error.)");
    }

    static Map<Integer, Long> topOf(Map<Integer, Long> h) {
        return h.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).limit(K).collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);
    }
    static String human(long b) { return b >= 1 << 20 ? String.format("%.1f MB", b / 1048576.0) : String.format("%.0f KB", b / 1024.0); }

    static final class CountMin {
        final long[][] t; final int w; final int[] seeds;
        CountMin(int d, int w) { this.w = w; t = new long[d][w]; seeds = new int[d]; Random r = new Random(99); for (int i = 0; i < d; i++) seeds[i] = r.nextInt() | 1; }
        long add(int key) { long min = Long.MAX_VALUE; for (int i = 0; i < t.length; i++) { int h = mix(key * seeds[i] + i) & (w - 1); long v = ++t[i][h]; if (v < min) min = v; } return min; }
        static int mix(int x) { x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16; return x; }
    }
    // indexed min-heap keyed by count, size-bounded: holds the K largest estimates seen
    static final class TopHeap {
        final int cap; final int[] key; final long[] val; int n; final HashMap<Integer, Integer> pos = new HashMap<>();
        TopHeap(int cap) { this.cap = cap; key = new int[cap]; val = new long[cap]; }
        void offer(int k, long v) {
            Integer p = pos.get(k);
            if (p != null) { val[p] = v; down(p); return; }
            if (n < cap) { key[n] = k; val[n] = v; pos.put(k, n); up(n++); return; }
            if (v > val[0]) { pos.remove(key[0]); key[0] = k; val[0] = v; pos.put(k, 0); down(0); }
        }
        void up(int i) { while (i > 0) { int p = (i - 1) / 2; if (val[p] <= val[i]) break; swap(i, p); i = p; } }
        void down(int i) { while (true) { int l = 2 * i + 1, rr = l + 1, m = i; if (l < n && val[l] < val[m]) m = l; if (rr < n && val[rr] < val[m]) m = rr; if (m == i) return; swap(i, m); i = m; } }
        void swap(int a, int b) { int k = key[a]; key[a] = key[b]; key[b] = k; long v = val[a]; val[a] = val[b]; val[b] = v; pos.put(key[a], a); pos.put(key[b], b); }
        Map<Integer, Long> map() { Map<Integer, Long> m = new HashMap<>(); for (int i = 0; i < n; i++) m.put(key[i], val[i]); return m; }
    }
    static final class SpaceSaving {
        final TopHeap h;                                                                        // reuse: min-heap of counters
        SpaceSaving(int k) { h = new TopHeap(k); }
        void add(int key) {
            Integer p = h.pos.get(key);
            if (p != null) { h.val[p]++; h.down(p); return; }
            if (h.n < h.cap) { h.key[h.n] = key; h.val[h.n] = 1; h.pos.put(key, h.n); h.up(h.n++); return; }
            h.pos.remove(h.key[0]); h.key[0] = key; h.val[0] = h.val[0] + 1; h.pos.put(key, 0); h.down(0);   // evict min, inherit its count
        }
        Map<Integer, Long> top(int k) { return topOf(h.map()); }
    }
}
