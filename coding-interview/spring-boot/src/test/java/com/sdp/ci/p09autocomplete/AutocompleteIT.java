package com.sdp.ci.p09autocomplete;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AutocompleteIT extends RedisIntegrationTest {
    @Autowired AutocompleteService autocomplete;

    @Test
    void topKPerPrefixFromSearchLog() {
        var t = transcript("AutocompleteSpringBoot");
        Map<String, Integer> log = new java.util.LinkedHashMap<>();
        log.put("iphone 15 pro", 8); log.put("india vs australia", 7); log.put("ice cream", 6);
        log.put("iphone 15", 5); log.put("idli", 4); log.put("ipad air", 3); log.put("iphone charger", 2);
        log.forEach((term, times) -> { for (int i = 0; i < times; i++) autocomplete.recordSearch(term); });
        t.note("search log: " + log);

        var i = t.get("/api/v1/autocomplete?q=i");
        assertThat(i.body()).isEqualTo("[\"iphone 15 pro\",\"india vs australia\",\"ice cream\",\"iphone 15\",\"idli\"]");
        assertThat(i.header("Cache-Control")).isEqualTo("max-age=60, public");
        assertThat(t.get("/api/v1/autocomplete?q=IPH").body()).isEqualTo("[\"iphone 15 pro\",\"iphone 15\",\"iphone charger\"]");
        assertThat(t.get("/api/v1/autocomplete?q=iphone%2015%20p").body()).isEqualTo("[\"iphone 15 pro\"]");   // 11 chars: 10-char node + filter

        t.post("/api/v1/autocomplete/searches?term=Ipad%20Air", null);
        for (int n = 0; n < 5; n++) autocomplete.recordSearch("ipad air");
        t.note("'ipad air' searched 6 more times (9 total) -> it enters the top 5 for 'i'");
        assertThat(t.get("/api/v1/autocomplete?q=i").body()).startsWith("[\"ipad air\",\"iphone 15 pro\"");
        assertThat(t.get("/api/v1/autocomplete?q=%20").status()).isEqualTo(400);
        t.pass();
    }
}
