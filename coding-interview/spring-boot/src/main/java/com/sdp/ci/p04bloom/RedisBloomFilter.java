package com.sdp.ci.p04bloom;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A Bloom filter stored as a Redis bitmap, shared by every app instance. Each add/check is one Lua
 * call (one round trip, atomic). With the RedisBloom module you would use BF.RESERVE / BF.ADD / BF.EXISTS instead.
 */
public class RedisBloomFilter {
    private static final RedisScript<Long> ADD = RedisScript.of(new ClassPathResource("scripts/bloom-add.lua"), Long.class);
    private static final RedisScript<Long> CHECK = RedisScript.of(new ClassPathResource("scripts/bloom-check.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final String key;
    private final long bits;
    private final int hashes;

    public RedisBloomFilter(StringRedisTemplate redis, String key, long expectedItems, double falsePositiveRate) {
        this.redis = redis;
        this.key = key;
        this.bits = BloomMath.bits(expectedItems, falsePositiveRate);
        this.hashes = BloomMath.hashes(bits, expectedItems);
    }

    public void add(String item) { redis.execute(ADD, List.of(key), args(item)); }

    public boolean mightContain(String item) { return redis.execute(CHECK, List.of(key), args(item)) == 1L; }

    public long bits() { return bits; }
    public int hashes() { return hashes; }

    private Object[] args(String item) {
        return Arrays.stream(BloomMath.positions(item, hashes, bits)).mapToObj(String::valueOf).toArray();
    }
}
