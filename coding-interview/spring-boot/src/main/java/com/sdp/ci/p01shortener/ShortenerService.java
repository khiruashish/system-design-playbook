package com.sdp.ci.p01shortener;

import com.sdp.ci.common.Problems;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ShortenerService {
    static final String CODE_KEY = "shortener:code:";
    private final StringRedisTemplate redis;
    private final IdBlockAllocator ids;

    public ShortenerService(StringRedisTemplate redis, IdBlockAllocator ids) {
        this.redis = redis;
        this.ids = ids;
    }

    /** Custom alias: SET NX makes "claim this alias" atomic, so two users can't both get it. */
    public String shorten(String longUrl, String alias) {
        if (alias != null) {
            Boolean claimed = redis.opsForValue().setIfAbsent(CODE_KEY + alias, longUrl);
            if (!Boolean.TRUE.equals(claimed)) throw Problems.of(HttpStatus.CONFLICT, "alias '" + alias + "' is taken");
            return alias;
        }
        String code = Base62.encode(ids.nextId());
        redis.opsForValue().set(CODE_KEY + code, longUrl);
        return code;
    }

    /** Hot read path: served from the local Caffeine cache ("short-urls"), then Redis. Links are immutable, so caching is safe. */
    @Cacheable("short-urls")
    public String resolve(String code) {
        String url = redis.opsForValue().get(CODE_KEY + code);
        if (url == null) throw Problems.of(HttpStatus.NOT_FOUND, "no link for code '" + code + "'");
        return url;
    }
}
