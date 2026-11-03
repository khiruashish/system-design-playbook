package com.sdp.ci.p03lfu;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    public record Product(String id, String name) {}

    private final AtomicInteger databaseReads = new AtomicInteger();

    /** The cache sits in front of the slow source; sync = true means concurrent misses load once. */
    @Cacheable(cacheNames = "products", sync = true)
    public Product find(String id) {
        databaseReads.incrementAndGet();                 // stands in for a DB / remote call
        return new Product(id, "Product " + id.toUpperCase());
    }

    @CacheEvict(cacheNames = "products")
    public void updated(String id) {}

    public int databaseReads() { return databaseReads.get(); }
}
