package com.sdp.ci.p05snowflake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sdp.ci.support.MutableClock;
import com.sdp.ci.support.RedisIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SnowflakeIT extends RedisIntegrationTest {
    static final long EPOCH = java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    @Test
    void leasedWorkerIdsAndUniqueIdsUnderConcurrency() throws Exception {
        var t = transcript("SnowflakeSpringBoot");

        // Three instances start: each leases a different worker id from Redis.
        var lease1 = new WorkerIdLease(redis, Duration.ofSeconds(30));
        var lease2 = new WorkerIdLease(redis, Duration.ofSeconds(30));
        t.note("instance-1 leased worker " + lease1.workerId() + ", instance-2 leased worker " + lease2.workerId());
        assertThat(lease1.workerId()).isNotEqualTo(lease2.workerId());
        lease1.release();
        var lease3 = new WorkerIdLease(redis, Duration.ofSeconds(30));
        t.note("instance-1 shut down and released its id; instance-3 leased worker " + lease3.workerId() + " (reused)");
        assertThat(lease3.workerId()).isEqualTo(lease1.workerId());
        redis.opsForValue().set("snowflake:worker:" + lease2.workerId(), "someone-else");      // lease expired and was taken over
        t.note("instance-2 renew after its lease was taken over: " + lease2.renew() + " -> it must stop issuing ids");
        assertThat(lease2.renew()).isFalse();
        assertThat(lease3.renew()).isTrue();

        // Ids from the generator the app uses, on a fixed test clock, decoded through the REST API.
        var gen = new SnowflakeIdGenerator(EPOCH, lease3.workerId(), clock);
        long id = gen.nextId();
        t.get("/api/v1/ids/" + id);
        assertThat(gen.decode(id).workerId()).isEqualTo(lease3.workerId());
        var batch = t.quietly(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/ids?count=3"));
        t.note("> GET /api/v1/ids?count=3\n< " + batch.status() + "  [3 ids as JSON strings, e.g. \"" + id + "\"]");
        assertThat(batch.body()).matches("\\[\"\\d+\",\"\\d+\",\"\\d+\"]");
        assertThat(t.get("/api/v1/ids?count=5000").status()).isEqualTo(400);

        // 200,000 ids from 8 virtual threads on the real clock: all unique.
        var real = new SnowflakeIdGenerator(EPOCH, 5, Clock.systemUTC());
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int th = 0; th < 8; th++) pool.submit(() -> { for (int i = 0; i < 25_000; i++) seen.add(real.nextId()); });
        }
        t.note("200,000 ids from 8 virtual threads: " + seen.size() + " unique");
        assertThat(seen).hasSize(200_000);

        // Clock jumps back 10 ms (bigger than the 5 ms we are willing to wait): refuse rather than risk duplicates.
        var jumpy = new MutableClock(T0);
        var gen2 = new SnowflakeIdGenerator(EPOCH, 6, jumpy);
        gen2.nextId();
        jumpy.advanceMillis(-10);
        assertThatThrownBy(gen2::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("clock moved back 10 ms");
        t.note("clock moved back 10 ms -> IllegalStateException (no duplicate ids)");
        t.pass();
    }
}
