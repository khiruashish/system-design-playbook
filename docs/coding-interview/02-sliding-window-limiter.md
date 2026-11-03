# 2. Sliding-Window Rate Limiter (Log vs Counter)

<span class="level l2">L2</span> ★★☆ · Fixed-window burst problem, sliding log, weighted sliding counter

## Problem

> Limit each user to **N requests per rolling minute**. Show why a fixed window allows **2× bursts** at the boundary, then
> implement an **exact sliding log** and a **memory-cheap sliding counter**, and compare them.

## Key idea

**Sliding log** keeps the timestamp of every accepted request: exact, but O(N) memory per user (in Redis, a sorted set).
**Sliding counter** keeps only the current and previous fixed-window counts and estimates
`previous × overlap + current`: O(1) memory and slightly approximate. That's what Cloudflare runs at its scale. (Token bucket, which
allows controlled bursts, is in the microservice playbook.)

```mermaid
flowchart LR
    subgraph Prev["window 0:00–1:00 (count 10)"]
    end
    subgraph Cur["window 1:00–2:00 (count c)"]
    end
    Prev --> F["estimate at 1:15 = 10 × 0.75 + c"]
    Cur --> F
    F --> D{"< limit?"}
```

## Solution

```java title="SlidingWindowRateLimiter.java"
--8<-- "coding-interview/src/SlidingWindowRateLimiter.java"
```

## Walkthrough

| Code | Why |
|---|---|
| `while (log.peekFirst() <= nowMs - windowMs) log.pollFirst()` | Evict expired timestamps before counting. The deque stays sorted because time only increases. |
| `long windowStart = nowMs - Math.floorMod(nowMs, windowMs)` | Align to fixed windows. `floorMod` handles negative timestamps correctly. |
| `s[2] = (windowStart - s[0] == windowMs) ? s[1] : 0` | On rollover, the old current count becomes the previous one, unless a whole window was skipped, in which case the previous count is 0. |
| `double estimated = s[2] * overlap + s[1]` | Weight the previous window by how much of it still falls inside the rolling window. |
| `sliding counter allowed 11` | An honest approximation: at the boundary the previous 10 count as 9.98, so one extra request gets through. It assumes the previous window's traffic was uniform. |

## Complexity

| Approach | Time | Space |
|---|---|---|
| sliding log | O(1) amortised | O(limit) per user |
| sliding counter | O(1) | O(1) per user |
| fixed window | O(1) | O(1) but 2× bursts |

## Run it

```bash
java coding-interview/src/SlidingWindowRateLimiter.java      # JDK 21+ single-file launcher, no build needed
```

```text
--8<-- "coding-interview/outputs/SlidingWindowRateLimiter.txt"
```

## Solve it with Spring Boot 4

The algorithms move into **Redis Lua scripts**, so "count, compare, record" is one atomic step shared by every instance.
A Spring MVC **`HandlerInterceptor`** applies the limit before the controller and returns `429` with `Retry-After` and
`RateLimit-*` headers. The algorithm is a property: `ci.rate-limit.algorithm=SLIDING_LOG | SLIDING_COUNTER`.

```mermaid
sequenceDiagram
    participant C as Client
    participant I as RateLimitInterceptor
    participant L as RedisRateLimiter
    participant R as Redis (Lua)
    participant Q as QuoteController
    C->>I: GET /api/v1/quotes/INFY [X-User-Id: asha]
    I->>L: tryAcquire("asha")
    L->>R: EVALSHA sliding-log.lua (ZREMRANGEBYSCORE, ZCARD, ZADD)
    R-->>L: {allowed, remaining, retryAfterMs}
    alt allowed
      I->>Q: continue
      Q-->>C: 200 + RateLimit-Remaining
    else over the limit
      I-->>C: 429 + Retry-After (ProblemDetail)
    end
```

=== "Sliding log (Lua)"

    ```lua title="sliding-log.lua"
    --8<-- "coding-interview/spring-boot/src/main/resources/scripts/sliding-log.lua"
    ```

=== "Sliding counter (Lua)"

    ```lua title="sliding-counter.lua"
    --8<-- "coding-interview/spring-boot/src/main/resources/scripts/sliding-counter.lua"
    ```

=== "Limiter"

    ```java title="RedisRateLimiter.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p02ratelimit/RedisRateLimiter.java"
    ```

=== "Interceptor"

    ```java title="RateLimitInterceptor.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p02ratelimit/RateLimitInterceptor.java"
    ```

=== "Wiring"

    ```java title="RateLimitConfig.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p02ratelimit/RateLimitConfig.java"
    ```

### Run it as a service

The integration test [`RateLimitIT.java`](https://github.com/khiruashish/system-design-playbook/blob/main/coding-interview/spring-boot/src/test/java/com/sdp/ci/p02ratelimit/RateLimitIT.java) drives the API against a
real Redis. This is its transcript (`>` request, `<` response):

```text
--8<-- "coding-interview/spring-boot/outputs/RateLimitSpringBoot.txt"
```

### What changes from the interview version

| Interview | Production (Spring Boot 4) |
|---|---|
| `Map<String, Deque<Long>>` per JVM | Redis sorted set per user: one limit across **all** instances |
| check then add (racy across servers) | **one Lua script** = atomic check-and-record |
| `System.currentTimeMillis()` | injected `Clock` (tests move time; or use Redis `TIME` inside the script) |
| returns `boolean` | interceptor → `429`, `Retry-After`, `RateLimit-Limit/Remaining` |
| unbounded memory for idle users | `PEXPIRE` on every key: idle users cost nothing |

The two counter keys share the `{userId}` **hash tag** so they hash to the same Redis Cluster slot, a requirement for
multi-key scripts in a cluster. Gateways (Spring Cloud Gateway's `RequestRateLimiter`) ship a Redis token bucket built the same way.

## Common mistakes

* A fixed window. Clients can send 2N within a second around each boundary.
* Read-then-write in Redis without atomicity. Use a Lua script or `MULTI`, or two app servers will each allow the last slot.
* Using the client's clock. Use the server's or Redis's time (`TIME`).
* One global limiter key for all users, which creates a hot key. Shard by user id.

## Follow-up questions

??? question "How do you rate-limit across 50 gateway instances?"
    Use a central store (Redis) with an atomic script per request, or local limiters each with N/instances (cheap, approximate), or local token buckets refilled from a central budget periodically. Choose by how strictly the limit must hold.

??? question "What should the response look like?"
    `429 Too Many Requests` with `Retry-After` and `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` headers, so well-behaved clients back off precisely.
