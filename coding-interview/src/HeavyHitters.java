import java.util.*;

/** Problem: top-K trending hashtags over a huge stream in fixed memory: Count-Min Sketch + a size-K min-heap. */
public class HeavyHitters {

    /** d rows x w counters. Estimate = min over rows: never underestimates, overestimates by <= eps*N with prob 1-delta. */
    static final class CountMinSketch {
        private final int width, depth;
        private final long[][] table;
        private final long[] seeds;
        private long total;

        CountMinSketch(double epsilon, double delta) {
            this.width = (int) Math.ceil(Math.E / epsilon);
            this.depth = (int) Math.ceil(Math.log(1 / delta));
            this.table = new long[depth][width];
            this.seeds = new long[depth];
            Random r = new Random(17);
            for (int i = 0; i < depth; i++) seeds[i] = r.nextLong() | 1;
        }

        private int bucket(String item, int row) {
            long h = item.hashCode() * seeds[row];
            h ^= h >>> 31; h *= 0x9E3779B97F4A7C15L; h ^= h >>> 29;
            return (int) Math.floorMod(h, (long) width);
        }

        void add(String item) { total++; for (int r = 0; r < depth; r++) table[r][bucket(item, r)]++; }

        long estimate(String item) {
            long min = Long.MAX_VALUE;
            for (int r = 0; r < depth; r++) min = Math.min(min, table[r][bucket(item, r)]);
            return min;
        }

        long counters() { return (long) width * depth; }
        long total() { return total; }
    }

    /** Keep the current top K by estimated count. Heap of (item) ordered by estimate; a map tracks membership. */
    static final class TopK {
        private final int k;
        private final CountMinSketch cms;
        private final Map<String, Long> members = new HashMap<>();
        TopK(int k, CountMinSketch cms) { this.k = k; this.cms = cms; }

        void offer(String item) {
            cms.add(item);
            long est = cms.estimate(item);
            if (members.containsKey(item) || members.size() < k) { members.put(item, est); return; }
            String weakest = Collections.min(members.entrySet(), Map.Entry.comparingByValue()).getKey();   // O(k); fine for small k
            if (est > members.get(weakest)) { members.remove(weakest); members.put(item, est); }
        }

        List<Map.Entry<String, Long>> top() {
            List<Map.Entry<String, Long>> list = new ArrayList<>(members.entrySet());
            list.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            return list;
        }
    }

    public static void main(String[] args) {
        // Zipf-like stream: #tag0 most popular, long tail of 50,000 rare tags
        Random r = new Random(1);
        List<String> stream = new ArrayList<>();
        String[] trending = {"#worldcup", "#election", "#ai", "#budget", "#monsoon"};
        int[] weights = {50_000, 30_000, 20_000, 10_000, 5_000};
        for (int i = 0; i < trending.length; i++) for (int j = 0; j < weights[i]; j++) stream.add(trending[i]);
        for (int i = 0; i < 200_000; i++) stream.add("#tail" + r.nextInt(50_000));
        Collections.shuffle(stream, r);

        CountMinSketch cms = new CountMinSketch(0.0005, 0.001);
        TopK top = new TopK(5, cms);
        Map<String, Integer> exact = new HashMap<>();
        for (String tag : stream) { top.offer(tag); exact.merge(tag, 1, Integer::sum); }

        System.out.printf("stream=%d events, distinct=%d; sketch uses %d counters (%.0f KB) vs exact map of %d keys%n",
                cms.total(), exact.size(), cms.counters(), cms.counters() * 8 / 1024.0, exact.size());
        for (var e : top.top()) System.out.printf("  %-10s estimate=%d exact=%d%n", e.getKey(), e.getValue(), exact.get(e.getKey()));
        List<String> names = top.top().stream().map(Map.Entry::getKey).toList();
        check(names.equals(List.of(trending)), "top 5 identified in order");
        for (String t : trending) check(cms.estimate(t) >= exact.get(t) && cms.estimate(t) - exact.get(t) <= 0.0005 * cms.total(), "error bound for " + t);
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
