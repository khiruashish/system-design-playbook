package com.sdp.ci.support;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full application context against a REAL Redis (no mocks): localhost:6379 by default, or REDIS_HOST/REDIS_PORT.
 * CI starts redis:7 as a service container; locally run `docker run -d -p 6379:6379 redis:7` or `redis-server`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisIntegrationTest.ClockConfig.class)
public abstract class RedisIntegrationTest {
    public static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired protected MockMvc mvc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; });
        clock.set(T0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ClockConfig {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(T0); }
    }

    protected Transcript transcript(String name) { return new Transcript(name, mvc); }

}
