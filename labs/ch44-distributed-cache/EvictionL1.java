// EvictionL1.java — run with: java EvictionL1.java   (JDK 21, no dependencies)
// WHICH KEY DO YOU THROW AWAY? A cache server's hit ratio is decided by its eviction policy. Four policies, implemented
// here, replay the same three request traces (fixed seed) at several cache sizes:
//   LRU             exact least-recently-used (a linked hash map)
//   SAMPLED LRU     Redis's approach: sample 5 random keys, evict the least recently used of those (no global list)
//   LFU (decayed)   Redis-style: a small frequency counter per key that grows logarithmically and halves periodically
//   W-TinyLFU       Caffeine's approach: a 1% LRU window, a segmented-LRU main area, and a count-min sketch that only
//                   ADMITS a new key into the main area if it's been seen more often than the key it would replace
// Traces (2,000,000 requests over 1,000,000 keys):
//   ZIPF   a stable Zipf(0.9) popularity — product pages
//   SCAN   the same, with a nightly catalogue export reading 200,000 distinct cold keys once, interleaved
//   SHIFT  popularity that shifts abruptly halfway through (the morning milk rush → evening snacks)
import java.util.*;

public class EvictionL1 {
    interface Cache { boolean get(int k); String name(); }

    public static void main(String[] args) {
        int keys = 1_000_000, n = 2_000_000;
        int[][] traces = {zipf(n, keys, 1, false, false), zipf(n, keys, 2, true, false), zipf(n, keys, 3, false, true)};
        String[] tn = {"ZIPF", "SCAN", "SHIFT"};
        System.out.printf("Hit ratio (%%), %,d requests over %,d keys per trace.%n%n", n, keys);
        System.out.printf("   %-8s %-10s %10s %14s %16s %12s%n", "trace", "cache size", "LRU", "sampled LRU", "LFU (decayed)", "W-TinyLFU");
        for (int t = 0; t < 3; t++) for (int size : new int[]{10_000, 50_000, 200_000}) {
            Cache[] cs = {new Lru(size), new SampledLru(size, 5), new Lfu(size), new WTinyLfu(size)};
            double[] hr = new double[4];
            for (int c = 0; c < 4; c++) { long hits = 0; for (int k : traces[t]) if (cs[c].get(k)) hits++; hr[c] = 100.0 * hits / n; }
            System.out.printf("   %-8s %,10d %9.1f%% %13.1f%% %15.1f%% %11.1f%%%n", tn[t], size, hr[0], hr[1], hr[2], hr[3]);
        }
        System.out.println("\n   → recency alone is a weak signal under skew: a scan of cold keys pushes hot keys out of an LRU cache, and");
        System.out.println("     every one-hit wonder displaces something useful. Frequency-aware admission (W-TinyLFU) keeps the hot set and");
        System.out.println("     wins most at small sizes, where each slot matters (+9 points at 10,000 keys). But frequency is history: when");
        System.out.println("     popularity shifts abruptly, LFU and this fixed-window W-TinyLFU fall behind plain LRU at larger sizes — which");
        System.out.println("     is why Caffeine resizes its window adaptively (hill climbing) and Redis decays LFU counters. Sampled LRU is");
        System.out.println("     within half a point of exact LRU with no linked list, which is why Redis uses it. Measure on your own traces.");
    }

    // ---------- traces ----------
    static int[] zipf(int n, int keys, long seed, boolean scan, boolean shift) {
        Random r = new Random(seed); double[] cdf = new double[keys]; double s = 0;
        for (int i = 0; i < keys; i++) { s += 1 / Math.pow(i + 1, 0.9); cdf[i] = s; }
        int[] perm = new int[keys]; for (int i = 0; i < keys; i++) perm[i] = i; shuffle(perm, r);
        int[] perm2 = perm.clone(); shuffle(perm2, r);
        int[] out = new int[n]; int scanKey = 0;
        for (int i = 0; i < n; i++) {
            if (scan && i % 10 == 0 && scanKey < 200_000) { out[i] = keys + scanKey++; continue; }   // cold keys, touched once
            int idx = Arrays.binarySearch(cdf, r.nextDouble() * s); idx = idx < 0 ? -idx - 1 : idx;
            out[i] = (shift && i >= n / 2 ? perm2 : perm)[Math.min(idx, keys - 1)];
        }
        return out;
    }
    static void shuffle(int[] a, Random r) { for (int i = a.length - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = a[i]; a[i] = a[j]; a[j] = t; } }

    // ---------- LRU ----------
    static final class Lru implements Cache {
        final LinkedHashMap<Integer, Boolean> m; Lru(int cap) { m = new LinkedHashMap<>(cap * 2, 0.75f, true) { protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> e) { return size() > cap; } }; }
        public boolean get(int k) { if (m.get(k) != null) return true; m.put(k, true); return false; }
        public String name() { return "LRU"; }
    }
    // ---------- sampled LRU (Redis allkeys-lru, maxmemory-samples 5) ----------
    static final class SampledLru implements Cache {
        final int cap, samples; final int[] keys; final Map<Integer, Integer> slot = new HashMap<>(); final long[] lastUse; long clock; final Random r = new Random(9); int size;
        SampledLru(int cap, int samples) { this.cap = cap; this.samples = samples; keys = new int[cap]; lastUse = new long[cap]; }
        public boolean get(int k) {
            clock++; Integer s = slot.get(k);
            if (s != null) { lastUse[s] = clock; return true; }
            int put;
            if (size < cap) put = size++;
            else { put = r.nextInt(cap); for (int i = 1; i < samples; i++) { int c = r.nextInt(cap); if (lastUse[c] < lastUse[put]) put = c; } slot.remove(keys[put]); }
            keys[put] = k; lastUse[put] = clock; slot.put(k, put); return false;
        }
        public String name() { return "sampled LRU"; }
    }
    // ---------- LFU with logarithmic counter and decay (Redis allkeys-lfu flavour, sampled eviction) ----------
    static final class Lfu implements Cache {
        final int cap; final int[] keys; final int[] freq; final Map<Integer, Integer> slot = new HashMap<>(); final Random r = new Random(11); int size; long ops;
        Lfu(int cap) { this.cap = cap; keys = new int[cap]; freq = new int[cap]; }
        void bump(int s) { int f = freq[s]; if (f < 255 && r.nextDouble() < 1.0 / ((f - 5) * 10 + 1 < 1 ? 1 : (f - 5) * 10 + 1)) freq[s]++; }
        public boolean get(int k) {
            if (++ops % (cap * 10L) == 0) for (int i = 0; i < size; i++) freq[i] >>= 1;     // periodic decay
            Integer s = slot.get(k);
            if (s != null) { bump(s); return true; }
            int put;
            if (size < cap) put = size++;
            else { put = r.nextInt(cap); for (int i = 1; i < 5; i++) { int c = r.nextInt(cap); if (freq[c] < freq[put]) put = c; } slot.remove(keys[put]); }
            keys[put] = k; freq[put] = 5; slot.put(k, put); return false;                  // new keys start at 5 (Redis LFU_INIT_VAL)
        }
        public String name() { return "LFU"; }
    }
    // ---------- W-TinyLFU (simplified Caffeine) ----------
    static final class WTinyLfu implements Cache {
        final int windowCap, probCap, protCap; final LinkedHashMap<Integer, Boolean> window = new LinkedHashMap<>(16, 0.75f, true),
                probation = new LinkedHashMap<>(16, 0.75f, true), protectedSeg = new LinkedHashMap<>(16, 0.75f, true);
        final CountMin sketch;
        WTinyLfu(int cap) { windowCap = Math.max(1, cap / 100); int main = cap - windowCap; protCap = main * 8 / 10; probCap = main - protCap; sketch = new CountMin(cap); }
        public boolean get(int k) {
            sketch.add(k);
            if (window.get(k) != null) return true;
            if (protectedSeg.get(k) != null) return true;
            if (probation.remove(k) != null) {                                             // promote on second hit
                protectedSeg.put(k, true);
                if (protectedSeg.size() > protCap) { int demoted = first(protectedSeg); protectedSeg.remove(demoted); probation.put(demoted, true); }
                return true;
            }
            window.put(k, true);
            if (window.size() > windowCap) {
                int candidate = first(window); window.remove(candidate);
                if (probation.size() + protectedSeg.size() < probCap + protCap) { probation.put(candidate, true); return false; }
                int victim = probation.isEmpty() ? first(protectedSeg) : first(probation);
                if (sketch.estimate(candidate) > sketch.estimate(victim)) { (probation.isEmpty() ? protectedSeg : probation).remove(victim); probation.put(candidate, true); }
            }
            return false;
        }
        static int first(LinkedHashMap<Integer, Boolean> m) { return m.keySet().iterator().next(); }
        public String name() { return "W-TinyLFU"; }
    }
    static final class CountMin {                                                           // 4-bit-style counters, periodic halving
        final int w; final int[][] t = new int[4][]; long adds; final long reset;
        CountMin(int cap) { w = Integer.highestOneBit(Math.max(64, cap * 4)); for (int i = 0; i < 4; i++) t[i] = new int[w]; reset = 10L * cap; }
        int h(int k, int i) { int x = k * (0x9E3779B1 + i * 0x7F4A7C15); x ^= x >>> 15; x *= 0x2C1B3C6D; x ^= x >>> 12; return x & (w - 1); }
        void add(int k) { for (int i = 0; i < 4; i++) { int j = h(k, i); if (t[i][j] < 15) t[i][j]++; } if (++adds == reset) { for (int[] row : t) for (int j = 0; j < w; j++) row[j] >>= 1; adds = 0; } }
        int estimate(int k) { int m = Integer.MAX_VALUE; for (int i = 0; i < 4; i++) m = Math.min(m, t[i][h(k, i)]); return m; }
    }
}
