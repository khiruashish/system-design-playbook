package com.sdp.ci.p11quorum;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** N replicas, write quorum W, read quorum R. R + W > N guarantees a read overlaps the latest write. */
@ConfigurationProperties("ci.quorum")
public record QuorumProperties(int n, int w, int r, Duration timeout, List<String> replicas) {
    public QuorumProperties {
        if (replicas == null || replicas.size() != n) throw new IllegalArgumentException("need exactly n replica URLs");
        if (w < 1 || r < 1 || w > n || r > n) throw new IllegalArgumentException("1 <= w, r <= n");
    }

    public boolean strong() { return r + w > n; }
}
