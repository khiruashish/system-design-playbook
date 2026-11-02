import java.util.*;

/** Problem: LFU cache with O(1) get/put: evict the least frequently used key; ties -> least recently used among them. */
public class LfuCache<K, V> {

    private final int capacity;
    private final Map<K, V> values = new HashMap<>();
    private final Map<K, Integer> counts = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();   // freq -> keys in LRU order
    private int minFreq;

    public LfuCache(int capacity) { this.capacity = capacity; }

    public V get(K key) {
        if (!values.containsKey(key)) return null;
        touch(key);
        return values.get(key);
    }

    public void put(K key, V value) {
        if (capacity == 0) return;
        if (values.containsKey(key)) { values.put(key, value); touch(key); return; }
        if (values.size() == capacity) {
            K victim = buckets.get(minFreq).iterator().next();     // oldest key in the lowest-frequency bucket
            buckets.get(minFreq).remove(victim);
            values.remove(victim);
            counts.remove(victim);
        }
        values.put(key, value);
        counts.put(key, 1);
        buckets.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(key);
        minFreq = 1;                                               // a new key always has the minimum frequency
    }

    /** Move key from bucket f to f+1; if f was the min bucket and is now empty, min becomes f+1. */
    private void touch(K key) {
        int f = counts.get(key);
        counts.put(key, f + 1);
        LinkedHashSet<K> bucket = buckets.get(f);
        bucket.remove(key);
        if (bucket.isEmpty()) { buckets.remove(f); if (minFreq == f) minFreq = f + 1; }
        buckets.computeIfAbsent(f + 1, x -> new LinkedHashSet<>()).add(key);
    }

    String debug() {
        Map<Integer, LinkedHashSet<K>> sorted = new TreeMap<>(buckets);
        return "freq buckets " + sorted + ", min=" + minFreq;
    }

    public static void main(String[] args) {
        LfuCache<String, Integer> lfu = new LfuCache<>(3);
        lfu.put("a", 1); lfu.put("b", 2); lfu.put("c", 3);
        lfu.get("a"); lfu.get("a"); lfu.get("b");
        System.out.println("after gets  : " + lfu.debug());
        lfu.put("d", 4);                                   // evicts c (freq 1)
        System.out.println("put d       : " + lfu.debug() + "  (c evicted)");
        check(lfu.get("c") == null, "least frequent evicted");
        lfu.put("e", 5);                                   // d is freq 1 -> evicted
        System.out.println("put e       : " + lfu.debug() + "  (d evicted)");
        check(lfu.get("d") == null && lfu.get("a") == 1, "a survives: frequently used");

        // Scan resistance vs LRU: a one-off scan of 100 keys doesn't flush the hot key
        LfuCache<String, Integer> hot = new LfuCache<>(2);
        hot.put("hot", 0);
        for (int i = 0; i < 5; i++) hot.get("hot");
        for (int i = 0; i < 100; i++) hot.put("scan-" + i, i);
        System.out.println("after a 100-key scan, hot key still cached: " + (hot.get("hot") != null));
        check(hot.get("hot") != null, "scan resistance");
        System.out.println("PASS");
    }

    static void check(boolean ok, String what) { if (!ok) { System.out.println("FAILED: " + what); System.exit(1); } }
}
