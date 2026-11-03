package com.sdp.ci.p04bloom;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * "Is this username available?" The Bloom filter answers most checks without touching the database:
 * "definitely not present" means available. Only "maybe present" goes to the database.
 */
@Service
public class UsernameService {
    private final RedisBloomFilter bloom;
    private final Set<String> database = ConcurrentHashMap.newKeySet();   // stands in for the users table
    private final AtomicInteger databaseLookups = new AtomicInteger();

    public UsernameService(StringRedisTemplate redis) {
        this.bloom = new RedisBloomFilter(redis, "bloom:usernames", 10_000, 0.01);
    }

    public void register(String username) {
        database.add(username);
        bloom.add(username);                          // write-through: the filter must never miss a real user
    }

    public boolean isAvailable(String username) {
        if (!bloom.mightContain(username)) return true;     // no false negatives: safe to skip the DB
        databaseLookups.incrementAndGet();
        return !database.contains(username);                // false positive or real user: ask the source of truth
    }

    public int databaseLookups() { return databaseLookups.get(); }
    public RedisBloomFilter filter() { return bloom; }
}
