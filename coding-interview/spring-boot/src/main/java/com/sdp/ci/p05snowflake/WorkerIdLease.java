package com.sdp.ci.p05snowflake;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The part the single-file solution hard-codes: WHICH worker id does this instance own?
 * Each instance claims a free id 0..1023 with SET NX PX (a lease), renews it while alive and
 * releases it on shutdown. If an instance dies, its lease expires and the id becomes reusable.
 */
public class WorkerIdLease {
    public static final int MAX_WORKERS = 1 << 10;   // 10 bits
    private static final RedisScript<Long> RENEW = RedisScript.of(new ClassPathResource("scripts/lease-renew.lua"), Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("scripts/lease-release.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final String owner = UUID.randomUUID().toString();
    private final int workerId;

    public WorkerIdLease(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
        this.workerId = claim();
    }

    private int claim() {
        for (int id = 0; id < MAX_WORKERS; id++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id), owner, ttl))) return id;
        }
        throw new IllegalStateException("all " + MAX_WORKERS + " worker ids are leased");
    }

    /** Called on a schedule well inside the TTL. Returns false if we lost the lease (e.g. after a long GC pause). */
    public boolean renew() {
        return redis.execute(RENEW, List.of(key(workerId)), owner, String.valueOf(ttl.toMillis())) == 1L;
    }

    public void release() { redis.execute(RELEASE, List.of(key(workerId)), owner); }

    public int workerId() { return workerId; }

    private static String key(int id) { return "snowflake:worker:" + id; }
}
