package com.sdp.ci.p03lfu;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two caches side by side:
 *  - "products": the hand-written LFU (what the interview asks you to build)
 *  - "short-urls": Caffeine, whose W-TinyLFU policy is what you would actually run in production
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {
    @Bean
    CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new LfuSpringCache("products", 3),
                new CaffeineCache("short-urls", Caffeine.newBuilder()
                        .maximumSize(100_000)
                        .expireAfterAccess(Duration.ofHours(1))
                        .recordStats()
                        .build())));
        return manager;
    }
}
