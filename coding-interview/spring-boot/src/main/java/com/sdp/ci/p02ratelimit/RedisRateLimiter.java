package com.sdp.ci.p02ratelimit;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The interview algorithms moved into Redis Lua scripts, so check-and-increment is ONE atomic step
 * shared by every app instance (no read-then-write race between servers).
 */
@Component
public class RedisRateLimiter {
    public record Decision(boolean allowed, long remaining, long retryAfterMs) {}

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SLIDING_LOG =
            RedisScript.of(new ClassPathResource("scripts/sliding-log.lua"), List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SLIDING_COUNTER =
            RedisScript.of(new ClassPathResource("scripts/sliding-counter.lua"), List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties props, Clock clock) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Decision tryAcquire(String userId) {
        long now = clock.millis();
        long window = props.window().toMillis();
        String limit = String.valueOf(props.limit());
        List<Long> r = switch (props.algorithm()) {
            case SLIDING_LOG -> redis.execute(SLIDING_LOG, List.of("rl:log:" + userId),
                    String.valueOf(now), String.valueOf(window), limit, now + "-" + seq.incrementAndGet());
            case SLIDING_COUNTER -> {
                long windowStart = now - Math.floorMod(now, window);
                // Both keys share the {userId} hash tag, so they land on the same Redis Cluster slot.
                yield redis.execute(SLIDING_COUNTER,
                        List.of("rl:cnt:{" + userId + "}:" + windowStart, "rl:cnt:{" + userId + "}:" + (windowStart - window)),
                        String.valueOf(now), String.valueOf(window), limit);
            }
        };
        return new Decision(r.get(0) == 1L, r.get(1), r.get(2));
    }
}
