package com.sdp.ci.p12hll;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import java.time.LocalDate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HyperLogLogIT extends RedisIntegrationTest {
    @Autowired UniqueVisitorService visitors;

    @Test
    void dailyUniqueVisitorsAndMultiDayUnion() {
        var t = transcript("HyperLogLogSpringBoot");
        LocalDate d1 = LocalDate.of(2026, 1, 14), d2 = d1.plusDays(1);
        addRange("home", d1, 0, 100_000);               // users 0..99,999 visit on day 1
        addRange("home", d2, 70_000, 130_000);          // users 70,000..129,999 on day 2 (30,000 overlap)

        long day1 = visitors.unique("home", d1, d1), both = visitors.unique("home", d1, d2);
        double err1 = Math.abs(day1 - 100_000) / 1000.0, errBoth = Math.abs(both - 130_000) / 1300.0;
        t.note(String.format("day 1: exact 100,000, HLL %,d (%.2f%% error); days 1-2: exact 130,000, HLL %,d (%.2f%% error)", day1, err1, both, errBoth));
        t.note("size of day 1's HLL in Redis: " + visitors.bytes("home", d1) + " bytes (an exact set of 100,000 ids needs megabytes)");
        assertThat(err1).isLessThan(2.5);                // 3 sigma of Redis HLL's 0.81% standard error
        assertThat(errBoth).isLessThan(2.5);
        assertThat(visitors.bytes("home", d1)).isLessThan(16_000);

        t.post("/api/v1/pages/pricing/visits", "{\"visitorId\":\"u-1\"}");
        t.quietly(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/pages/pricing/visits")
                .contentType("application/json").content("{\"visitorId\":\"u-1\"}"));
        t.post("/api/v1/pages/pricing/visits", "{\"visitorId\":\"u-2\"}");
        t.note("(u-1 visited twice)");
        LocalDate today = LocalDate.now(clock);   // test clock: 2026-01-15
        var unique = t.get("/api/v1/pages/pricing/unique-visitors?from=" + today + "&to=" + today);
        assertThat(unique.body()).contains("\"uniqueVisitors\":2");
        t.pass();
    }

    private void addRange(String page, LocalDate day, int from, int to) {
        for (int start = from; start < to; start += 1_000) {                    // PFADD is variadic: 1,000 ids per call
            visitors.visit(page, day, IntStream.range(start, Math.min(to, start + 1_000)).mapToObj(i -> "user-" + i).toArray(String[]::new));
        }
    }
}
