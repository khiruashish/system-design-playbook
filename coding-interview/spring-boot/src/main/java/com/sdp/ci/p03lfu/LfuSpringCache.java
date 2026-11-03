package com.sdp.ci.p03lfu;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.cache.support.AbstractValueAdaptingCache;

/**
 * Plugs the interview LFU into Spring's cache abstraction, so any @Cacheable method can use it.
 * One lock guards the structure (a get also mutates frequency buckets, so reads need the lock too).
 */
public class LfuSpringCache extends AbstractValueAdaptingCache {
    private final String name;
    private final LfuCache<Object, Object> lfu;
    private final ReentrantLock lock = new ReentrantLock();

    public LfuSpringCache(String name, int capacity) {
        super(true);                                   // allow caching nulls (as a NullValue marker)
        this.name = name;
        this.lfu = new LfuCache<>(capacity);
    }

    @Override public String getName() { return name; }
    @Override public Object getNativeCache() { return lfu; }

    @Override
    protected Object lookup(Object key) {
        lock.lock();
        try { return lfu.get(key); } finally { lock.unlock(); }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        lock.lock();                                   // load-once semantics for @Cacheable(sync = true)
        try {
            Object stored = lfu.get(key);
            if (stored != null) return (T) fromStoreValue(stored);
            T value = valueLoader.call();
            lfu.put(key, toStoreValue(value));
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        } finally { lock.unlock(); }
    }

    @Override public void put(Object key, Object value) { lock.lock(); try { lfu.put(key, toStoreValue(value)); } finally { lock.unlock(); } }
    @Override public void evict(Object key) { lock.lock(); try { lfu.remove(key); } finally { lock.unlock(); } }
    @Override public void clear() { lock.lock(); try { lfu.clear(); } finally { lock.unlock(); } }

    public long evictions() { lock.lock(); try { return lfu.evictions(); } finally { lock.unlock(); } }
    public Integer frequency(Object key) { lock.lock(); try { return lfu.frequency(key); } finally { lock.unlock(); } }
}
