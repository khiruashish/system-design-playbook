package com.sdp.ci.p10merkle;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Each replica's data is split into BUCKETS Redis hashes by key hash, so a Merkle leaf = one bucket and
 * repairing a leaf only reads that bucket. Values are stored as "version|value" (last-writer-wins).
 */
@Component
public class ReplicaStore {
    public static final int BUCKETS = 16;
    private final StringRedisTemplate redis;

    public ReplicaStore(StringRedisTemplate redis) { this.redis = redis; }

    public void put(String replica, String key, long version, String value) {
        redis.opsForHash().put(bucketKey(replica, bucketOf(key)), key, version + "|" + value);
    }

    /** Sorted so the leaf hash is deterministic regardless of insertion order. */
    public Map<String, String> bucket(String replica, int bucket) {
        Map<String, String> out = new TreeMap<>();
        redis.<String, String>opsForHash().entries(bucketKey(replica, bucket)).forEach(out::put);
        return out;
    }

    public long size(String replica) {
        long n = 0;
        for (int b = 0; b < BUCKETS; b++) n += redis.opsForHash().size(bucketKey(replica, b));
        return n;
    }

    public static int bucketOf(String key) {
        int h = 0;
        for (byte x : key.getBytes(StandardCharsets.UTF_8)) h = 31 * h + x;
        return Math.floorMod(h ^ (h >>> 16), BUCKETS);
    }

    public static long version(String stored) { return Long.parseLong(stored.substring(0, stored.indexOf('|'))); }

    private static String bucketKey(String replica, int bucket) { return "replica:" + replica + ":b" + bucket; }
}
