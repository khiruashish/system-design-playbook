# Ch 18 labs — resilience patterns (Java 21, no dependencies)

```bash
java BackoffL1.java                                   # retry schedules vs congestion collapse (simulation)
java BreakerL2.java                                   # circuit breaker vs a slowly failing HTTP dependency (~26 s)
redis-server --port 6391 --save "" --daemonize yes    # for part C of L3
java RateLimitL3.java                                 # five limiters + a global token bucket in Redis (Lua)
java SheddingL4.java                                  # 2x overload: unbounded vs bounded vs deadline-aware shedding (~31 s)
redis-cli -p 6391 shutdown nosave
```
