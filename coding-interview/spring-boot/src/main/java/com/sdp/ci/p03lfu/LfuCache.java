package com.sdp.ci.p03lfu;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** The interview O(1) LFU: frequency buckets of LinkedHashSet (LRU tie-break) + minFreq. Not thread-safe by itself. */
final class LfuCache<K, V> {
    private final int capacity;
    private final Map<K, V> values = new HashMap<>();
    private final Map<K, Integer> freqOf = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
    private int minFreq = 0;
    private long evictions = 0;

    LfuCache(int capacity) { this.capacity = capacity; }

    V get(K key) {
        if (!values.containsKey(key)) return null;
        touch(key);
        return values.get(key);
    }

    void put(K key, V value) {
        if (capacity <= 0) return;
        if (values.containsKey(key)) { values.put(key, value); touch(key); return; }
        if (values.size() >= capacity) {
            K victim = buckets.get(minFreq).iterator().next();      // least frequent, oldest among ties
            buckets.get(minFreq).remove(victim);
            values.remove(victim); freqOf.remove(victim);
            evictions++;
        }
        values.put(key, value); freqOf.put(key, 1);
        buckets.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    void remove(K key) {
        Integer f = freqOf.remove(key);
        if (f == null) return;
        values.remove(key);
        buckets.get(f).remove(key);
    }

    void clear() { values.clear(); freqOf.clear(); buckets.clear(); minFreq = 0; }
    int size() { return values.size(); }
    long evictions() { return evictions; }
    Integer frequency(K key) { return freqOf.get(key); }

    private void touch(K key) {
        int f = freqOf.get(key);
        buckets.get(f).remove(key);
        if (f == minFreq && buckets.get(f).isEmpty()) minFreq = f + 1;
        freqOf.put(key, f + 1);
        buckets.computeIfAbsent(f + 1, x -> new LinkedHashSet<>()).add(key);
    }
}
