package com.sdp.ci.p01shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.ci.support.RedisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

class ShortenerIT extends RedisIntegrationTest {
    @Autowired IdBlockAllocator ids;
    @Autowired CacheManager caches;

    @Test
    void shortenRedirectAliasAndIdBlocks() {
        caches.getCache("short-urls").clear();
        var t = transcript("ShortenerSpringBoot");

        var first = t.post("/api/v1/urls", "{\"url\":\"https://shop.example.com/sale/diwali?utm=mail\"}");
        assertThat(first.status()).isEqualTo(201);
        String code = first.body().replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
        assertThat(Base62.decode(code)).isPositive();

        assertThat(t.post("/api/v1/urls", "{\"url\":\"https://docs.example.com/a/very/long/path\"}").status()).isEqualTo(201);
        assertThat(t.post("/api/v1/urls", "{\"url\":\"https://shop.example.com/\",\"alias\":\"big-sale\"}").status()).isEqualTo(201);
        assertThat(t.post("/api/v1/urls", "{\"url\":\"https://other.example.com/\",\"alias\":\"big-sale\"}").status()).isEqualTo(409);
        assertThat(t.post("/api/v1/urls", "{\"url\":\"ftp://nope\"}").status()).isEqualTo(400);

        var redirect = t.get("/s/" + code);
        assertThat(redirect.status()).isEqualTo(302);
        assertThat(redirect.header("Location")).isEqualTo("https://shop.example.com/sale/diwali?utm=mail");
        assertThat(t.get("/s/zzzzzz").status()).isEqualTo(404);

        // The redirect path is cached: delete the key in Redis and the cached copy still serves it.
        redis.delete(ShortenerService.CODE_KEY + code);
        assertThat(t.get("/s/" + code).status()).isEqualTo(302);
        t.note("(Redis key deleted, redirect still served from the Caffeine cache: links are immutable, so this is safe)");

        // Block allocation: 2,500 more ids cost only 3 Redis INCRBY calls in total (block size 1,000).
        for (int i = 0; i < 2_500; i++) ids.nextId();
        t.note("2,502 ids allocated with " + ids.blocksReserved() + " Redis INCRBY calls (block size 1,000)");
        assertThat(ids.blocksReserved()).isEqualTo(3);
        t.pass();
    }
}
