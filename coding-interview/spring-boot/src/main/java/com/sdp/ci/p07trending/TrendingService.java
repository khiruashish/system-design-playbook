package com.sdp.ci.p07trending;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * "Trending in the last hour": a Count-Min Sketch (fixed memory, never undercounts) plus a top-K sorted set,
 * kept per hourly window in Redis. Width w and depth d give error <= (e / w) * N with probability 1 - e^-d.
 */
@Service
public class TrendingService {
    public record Trend(String item, long estimate) {}

    static final int WIDTH = 2_000, DEPTH = 5, TOP_K = 10;
    private static final RedisScript<Long> RECORD = RedisScript.of(new ClassPathResource("scripts/cms-topk.lua"), Long.class);
    private static final Duration WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public TrendingService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public long record(String item) {
        String window = currentWindow();
        List<Object> args = new ArrayList<>(List.of(item, String.valueOf(TOP_K), String.valueOf(WINDOW.multipliedBy(2).toMillis())));
        for (int row = 0; row < DEPTH; row++) args.add(String.valueOf(column(item, row)));
        return redis.execute(RECORD, List.of("cms:{" + window + "}", "topk:{" + window + "}"), args.toArray());
    }

    public List<Trend> top(int k) {
        var tuples = redis.opsForZSet().reverseRangeWithScores("topk:{" + currentWindow() + "}", 0, k - 1);
        if (tuples == null) return List.of();
        return tuples.stream().map((TypedTuple<String> t) -> new Trend(t.getValue(), t.getScore().longValue())).toList();
    }

    private String currentWindow() { return String.valueOf(clock.millis() / WINDOW.toMillis()); }

    /** Pairwise-independent-enough hash per row: seed the FNV-1a hash with the row number. */
    static int column(String item, int row) {
        long h = 0xcbf29ce484222325L ^ (row * 0x9E3779B97F4A7C15L);
        for (byte b : item.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xff); h *= 0x100000001b3L; }
        h ^= (h >>> 33);
        return (int) Math.floorMod(h, (long) WIDTH);
    }
}
