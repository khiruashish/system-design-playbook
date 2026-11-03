package com.sdp.ci.p03lfu;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class LfuCacheIT extends RedisIntegrationTest {
    @Autowired ProductService products;
    @Autowired CacheManager caches;

    @Test
    void cacheableMethodBackedByTheInterviewLfu() {
        var cache = (LfuSpringCache) caches.getCache("products");
        cache.clear();
        int base = products.databaseReads();
        var t = transcript("LfuCacheSpringBoot");
        t.note("@Cacheable(\"products\") backed by the O(1) LFU, capacity 3");

        for (String id : new String[]{"a", "a", "a", "b", "b", "c"}) t.quietly(MockMvcRequestBuilders.get("/api/v1/products/" + id));
        t.note("GET a x3, b x2, c x1 -> database reads: " + (products.databaseReads() - base)
                + ", frequencies a=" + cache.frequency("a") + " b=" + cache.frequency("b") + " c=" + cache.frequency("c"));
        assertThat(products.databaseReads() - base).isEqualTo(3);

        assertThat(t.get("/api/v1/products/d").status()).isEqualTo(200);
        t.note("GET d (miss) evicts the least-frequently used entry: c present? " + (cache.frequency("c") != null));
        assertThat(cache.frequency("c")).isNull();
        assertThat(cache.frequency("a")).isEqualTo(3);

        t.get("/api/v1/products/a");
        t.note("GET a (hit) -> database reads still " + (products.databaseReads() - base));
        assertThat(products.databaseReads() - base).isEqualTo(4);

        products.updated("b");
        t.get("/api/v1/products/b");
        t.note("@CacheEvict on update, then GET b -> reloaded, database reads " + (products.databaseReads() - base)
                + ", evictions so far " + cache.evictions());
        assertThat(products.databaseReads() - base).isEqualTo(5);
        t.pass();
    }
}
