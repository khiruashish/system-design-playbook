package com.sdp.ci.p02ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ci.rate-limit")
public record RateLimitProperties(int limit, Duration window, Algorithm algorithm) {
    public enum Algorithm { SLIDING_LOG, SLIDING_COUNTER }

    public RateLimitProperties {
        if (algorithm == null) algorithm = Algorithm.SLIDING_LOG;
    }
}
