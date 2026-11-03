package com.sdp.ci.p04bloom;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BloomFilterIT extends RedisIntegrationTest {
    @Autowired UsernameService usernames;

    @Test
    void redisBloomFilterSkipsTheDatabaseForNewNames() {
        var t = transcript("BloomFilterSpringBoot");
        var f = usernames.filter();
        t.note("sized for n = 10,000 at p = 1%: m = " + f.bits() + " bits (" + f.bits() / 8 / 1024 + " KB in Redis), k = " + f.hashes());

        assertThat(t.post("/api/v1/usernames/asha", null).status()).isEqualTo(201);
        assertThat(t.get("/api/v1/usernames/asha/availability").body()).contains("\"available\":false");
        assertThat(t.get("/api/v1/usernames/brand-new-name/availability").body()).contains("\"available\":true");

        for (int i = 0; i < 9_999; i++) usernames.register("user" + i);
        int before = usernames.databaseLookups();
        int takenSeen = 0;
        for (int i = 0; i < 9_999; i++) if (!usernames.isAvailable("user" + i)) takenSeen++;
        t.note(String.format("10,000 registered names checked: %,d reported taken (no false negatives)", takenSeen + 1));
        assertThat(takenSeen).isEqualTo(9_999);

        before = usernames.databaseLookups();
        int available = 0;
        for (int i = 0; i < 10_000; i++) if (usernames.isAvailable("new-" + i)) available++;
        int lookups = usernames.databaseLookups() - before;
        t.note(String.format("10,000 unused names checked: %,d available, database queried only ", available) + lookups
                + " times (false positive rate " + String.format("%.2f", lookups / 100.0) + "%, target 1%)");
        assertThat(available).isEqualTo(10_000);
        assertThat(lookups).isLessThan(200);
        t.pass();
    }
}
