package com.sdp.ci.p05snowflake;

import java.time.Clock;

/**
 * 64-bit id = 41 bits ms since epoch | 10 bits worker | 12 bits sequence. Sortable by time, unique per worker,
 * no coordination per id. synchronized is enough: 4,096 ids/ms per instance is far above typical needs.
 */
public class SnowflakeIdGenerator {
    public record Parts(long timestampMs, long workerId, long sequence) {}

    private static final int WORKER_BITS = 10, SEQ_BITS = 12;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;
    private static final long MAX_BACKWARDS_WAIT_MS = 5;

    private final long epochMs;
    private final long workerId;
    private final Clock clock;
    private long lastMs = -1, sequence = 0;

    public SnowflakeIdGenerator(long epochMs, long workerId, Clock clock) {
        if (workerId < 0 || workerId >= (1L << WORKER_BITS)) throw new IllegalArgumentException("worker id out of range");
        this.epochMs = epochMs;
        this.workerId = workerId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long now = clock.millis();
        if (now < lastMs) {                                     // clock moved backwards (NTP step)
            if (lastMs - now > MAX_BACKWARDS_WAIT_MS) {
                throw new IllegalStateException("clock moved back " + (lastMs - now) + " ms; refusing to issue ids");
            }
            now = waitUntil(lastMs);                           // small step: wait it out
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) now = waitUntil(lastMs + 1);    // 4,096 ids used this ms: wait for the next ms
        } else {
            sequence = 0;
        }
        lastMs = now;
        return ((now - epochMs) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    }

    public Parts decode(long id) {
        return new Parts((id >>> (WORKER_BITS + SEQ_BITS)) + epochMs, (id >>> SEQ_BITS) & ((1L << WORKER_BITS) - 1), id & MAX_SEQ);
    }

    private long waitUntil(long targetMs) {
        long now = clock.millis();
        while (now < targetMs) { Thread.onSpinWait(); now = clock.millis(); }
        return now;
    }
}
