package com.sdp.ci.p09autocomplete;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * The interview trie stores "top-K completions" on every node. In Redis each trie node becomes a small
 * sorted set per prefix holding only its top K terms, so a lookup is one ZREVRANGE: read-optimised,
 * with the work done on write (exactly the trade-off the interview answer argues for).
 */
@Service
public class AutocompleteService {
    static final int K = 5, MAX_PREFIX = 10;
    private static final RedisScript<Double> RECORD =
            RedisScript.of(new ClassPathResource("scripts/autocomplete-record.lua"), Double.class);

    private final StringRedisTemplate redis;

    public AutocompleteService(StringRedisTemplate redis) { this.redis = redis; }

    /** Called when a user submits/selects a search (in production: from the query log, in batches). */
    public void recordSearch(String rawTerm) {
        String term = normalise(rawTerm);
        List<Object> args = new ArrayList<>(List.of(term, String.valueOf(K)));
        for (int len = 1; len <= Math.min(MAX_PREFIX, term.length()); len++) args.add(term.substring(0, len));
        redis.execute(RECORD, List.of("ac:counts"), args.toArray());
    }

    public List<String> suggest(String rawPrefix) {
        String prefix = normalise(rawPrefix);
        if (prefix.isEmpty()) return List.of();
        String key = "ac:prefix:" + prefix.substring(0, Math.min(MAX_PREFIX, prefix.length()));
        var terms = redis.opsForZSet().reverseRange(key, 0, K - 1);
        if (terms == null) return List.of();
        // Prefixes longer than MAX_PREFIX reuse the 10-char node and filter.
        return terms.stream().filter(t -> t.startsWith(prefix)).toList();
    }

    static String normalise(String s) { return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
}
