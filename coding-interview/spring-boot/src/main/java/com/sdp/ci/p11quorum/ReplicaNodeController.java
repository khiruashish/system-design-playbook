package com.sdp.ci.p11quorum;

import com.sdp.ci.common.Problems;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The storage-node side. In production every replica is a separate service; here one app hosts nodes a/b/c
 * on different paths so a single process can show real HTTP fan-out, timeouts and failures.
 */
@RestController
@RequestMapping("/internal/replicas/{node}")
public class ReplicaNodeController {
    private final Map<String, Map<String, Versioned>> data = new ConcurrentHashMap<>();
    private final Map<String, Boolean> down = new ConcurrentHashMap<>();

    @GetMapping("/kv/{key}")
    public ResponseEntity<Versioned> get(@PathVariable String node, @PathVariable String key) {
        failIfDown(node);
        Versioned v = store(node).get(key);
        return v == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(v);
    }

    /** Only accept newer versions, so retries and read repair are idempotent. */
    @PutMapping("/kv/{key}")
    public Versioned put(@PathVariable String node, @PathVariable String key, @RequestBody Versioned value) {
        failIfDown(node);
        return store(node).merge(key, value, (old, neu) -> neu.version() >= old.version() ? neu : old);
    }

    /** Failure injection for demos and tests. */
    @PutMapping("/down")
    public void setDown(@PathVariable String node, @RequestParam boolean value) { down.put(node, value); }

    private Map<String, Versioned> store(String node) { return data.computeIfAbsent(node, n -> new ConcurrentHashMap<>()); }

    private void failIfDown(String node) {
        if (down.getOrDefault(node, false)) throw Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "replica " + node + " is down");
    }
}
