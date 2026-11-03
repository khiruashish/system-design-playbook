package com.sdp.ci.p07trending;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TrendingIT extends RedisIntegrationTest {
    @Autowired TrendingService trending;

    @Test
    void countMinSketchTopKMatchesExactCounts() {
        var t = transcript("HeavyHittersSpringBoot");
        int items = 500, events = 20_000;
        double[] cumulative = new double[items];                        // Zipf(1.1): a few items get most views
        double sum = 0;
        for (int i = 0; i < items; i++) { sum += 1 / Math.pow(i + 1, 1.1); cumulative[i] = sum; }
        Random rnd = new Random(42);
        Map<String, Long> exact = new HashMap<>();
        long maxError = 0;
        for (int e = 0; e < events; e++) {
            double x = rnd.nextDouble() * sum;
            int idx = Arrays.binarySearch(cumulative, x);
            String item = "product-" + (idx < 0 ? -idx - 1 : idx);
            long count = exact.merge(item, 1L, Long::sum);
            maxError = Math.max(maxError, trending.record(item) - count);
        }
        long bound = (long) Math.ceil(Math.E / TrendingService.WIDTH * events);
        t.note(String.format("%,d", events) + " views of " + items + " products (Zipf 1.1); sketch " + TrendingService.DEPTH + " x " + TrendingService.WIDTH
                + " counters; max overcount " + maxError + " (bound e/w * N = " + bound + ")");
        assertThat(maxError).isLessThanOrEqualTo(bound);

        var top = t.get("/api/v1/trending?k=5");
        List<String> exactTop = exact.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5).map(Map.Entry::getKey).toList();
        t.note("exact top 5: " + exactTop.stream().map(k -> k + "=" + exact.get(k)).collect(Collectors.joining(", ")));
        for (String k : exactTop) assertThat(top.body()).contains("\"" + k + "\"");
        assertThat(t.post("/api/v1/trending/product-0", null).status()).isEqualTo(202);
        t.pass();
    }
}
