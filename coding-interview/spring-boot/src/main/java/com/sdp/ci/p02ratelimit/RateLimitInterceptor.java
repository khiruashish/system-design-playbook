package com.sdp.ci.p02ratelimit;

import com.sdp.ci.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Runs before the controller: 429 + Retry-After when over the limit, RateLimit-* headers always. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RedisRateLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitInterceptor(RedisRateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        // Key by authenticated user in real systems; the header keeps the demo self-contained.
        String user = req.getHeader("X-User-Id") != null ? req.getHeader("X-User-Id") : req.getRemoteAddr();
        RedisRateLimiter.Decision d = limiter.tryAcquire(user);
        res.setHeader("RateLimit-Limit", String.valueOf(props.limit()));
        res.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            long retrySeconds = Math.max(1, (d.retryAfterMs() + 999) / 1000);
            res.setHeader("Retry-After", String.valueOf(retrySeconds));
            throw Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate limit of " + props.limit() + " per "
                    + props.window().toSeconds() + "s exceeded; retry in " + retrySeconds + "s");
        }
        return true;
    }
}
