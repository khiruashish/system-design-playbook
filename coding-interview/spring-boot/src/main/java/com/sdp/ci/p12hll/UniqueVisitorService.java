package com.sdp.ci.p12hll;

import java.time.LocalDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Unique visitors per page per day with Redis HyperLogLog (PFADD / PFCOUNT): ~12 KB per key whatever the
 * cardinality, 0.81% standard error. Multi-day counts union the daily HLLs on the fly (PFCOUNT k1 k2 ...).
 */
@Service
public class UniqueVisitorService {
    private final StringRedisTemplate redis;

    public UniqueVisitorService(StringRedisTemplate redis) { this.redis = redis; }

    public void visit(String page, LocalDate day, String... visitorIds) {
        redis.opsForHyperLogLog().add(key(page, day), visitorIds);
    }

    public long unique(String page, LocalDate from, LocalDate to) {
        String[] keys = from.datesUntil(to.plusDays(1)).map(d -> key(page, d)).toArray(String[]::new);
        return redis.opsForHyperLogLog().size(keys);                       // union across days, no double counting
    }

    /** An HLL is a Redis string: STRLEN is its size (sparse encoding for small sets, ~12 KB dense at most). */
    public long bytes(String page, LocalDate day) {
        Long size = redis.opsForValue().size(key(page, day));
        return size == null ? 0 : size;
    }

    static String key(String page, LocalDate day) { return "hll:{" + page + "}:" + day; }   // hash tag: same cluster slot
}
