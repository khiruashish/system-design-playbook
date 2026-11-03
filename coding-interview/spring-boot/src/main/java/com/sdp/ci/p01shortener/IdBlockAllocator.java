package com.sdp.ci.p01shortener;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands out unique ids without a Redis round trip per id: each instance reserves a block of
 * {@code idBlockSize} ids with one atomic INCRBY, then serves them from memory.
 * Ids stay unique across instances; a crash only wastes the unused rest of a block.
 */
@Component
public class IdBlockAllocator {
    static final String KEY = "shortener:id-block";
    private final StringRedisTemplate redis;
    private final int blockSize;
    private long next = 0, end = 0;          // current block is [next, end)
    private int blocksReserved = 0;

    public IdBlockAllocator(StringRedisTemplate redis, ShortenerProperties props) {
        this.redis = redis;
        this.blockSize = props.idBlockSize();
    }

    public synchronized long nextId() {
        if (next >= end) {
            long high = redis.opsForValue().increment(KEY, blockSize);   // atomic across all instances
            next = high - blockSize + 1;
            end = high + 1;
            blocksReserved++;
        }
        return next++;
    }

    public synchronized int blocksReserved() { return blocksReserved; }
}
