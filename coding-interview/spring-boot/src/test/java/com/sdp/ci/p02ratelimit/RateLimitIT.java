package com.sdp.ci.p02ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class RateLimitIT extends RedisIntegrationTest {

    @Test
    void slidingLogOverHttpThenBoundaryBurstComparison() {
        var t = transcript("RateLimitSpringBoot");
        t.note("limit 10 requests / 60 s per user (sliding log in a Redis Lua script)");
        assertThat(t.get("/api/v1/quotes/INFY", "X-User-Id", "asha").status()).isEqualTo(200);
        for (int i = 0; i < 8; i++) {
            t.quietly(MockMvcRequestBuilders.get("/api/v1/quotes/INFY").header("X-User-Id", "asha"));
        }
        t.note("... 8 more allowed requests ...");
        var tenth = t.get("/api/v1/quotes/INFY", "X-User-Id", "asha");
        assertThat(tenth.header("RateLimit-Remaining")).isEqualTo("0");
        var blocked = t.get("/api/v1/quotes/INFY", "X-User-Id", "asha");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.header("Retry-After")).isEqualTo("60");
        assertThat(t.get("/api/v1/quotes/INFY", "X-User-Id", "ravi").status()).isEqualTo(200);   // other users unaffected

        clock.advanceMillis(30_000);
        t.note("+30 s");
        assertThat(t.get("/api/v1/quotes/INFY", "X-User-Id", "asha").header("Retry-After")).isEqualTo("30");
        clock.advanceMillis(30_001);
        t.note("+30 s: the first requests slide out of the window");
        assertThat(t.get("/api/v1/quotes/INFY", "X-User-Id", "asha").status()).isEqualTo(200);

        // Same burst as the interview solution: 10 requests at 0:59.9 and 10 at 1:00.1.
        var log = new RedisRateLimiter(redis, new RateLimitProperties(10, Duration.ofSeconds(60), RateLimitProperties.Algorithm.SLIDING_LOG), clock);
        var counter = new RedisRateLimiter(redis, new RateLimitProperties(10, Duration.ofSeconds(60), RateLimitProperties.Algorithm.SLIDING_COUNTER), clock);
        int logOk = 0, counterOk = 0;
        for (long at : new long[]{59_900, 60_100}) {
            clock.set(T0.plusMillis(at));
            for (int i = 0; i < 10; i++) {
                if (log.tryAcquire("burst").allowed()) logOk++;
                if (counter.tryAcquire("burst").allowed()) counterOk++;
            }
        }
        t.note("boundary burst, 20 requests in 200 ms: sliding log allowed " + logOk + ", sliding counter allowed " + counterOk);
        assertThat(logOk).isEqualTo(10);
        assertThat(counterOk).isBetween(10, 11);
        t.pass();
    }
}
