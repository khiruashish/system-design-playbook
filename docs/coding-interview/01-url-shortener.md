# 1. URL Shortener Core: Base62 & Id Allocation

<span class="level l1">L1</span> ★★☆ · Base62 encode/decode, id allocation, custom aliases, overflow safety

## Problem

> Implement the core of a URL shortener: turn a numeric id into a short **base62** code and back, deduplicate long URLs,
> support **custom aliases** without collisions, and resolve codes for redirects.

## Key idea

Don't hash and hope for no collisions. **Allocate a unique id** (a DB sequence, or per-node ranges from a counter service), then
**base62-encode** it. Seven characters cover 62⁷ ≈ 3.5 trillion URLs. Custom aliases live in the same keyspace and are claimed
atomically (`putIfAbsent` here; `INSERT ... ON CONFLICT` in a DB). Reads dominate (about 100:1), so `resolve` is a key lookup
behind a cache.

```mermaid
flowchart LR
    C[POST /shorten] --> ID["id allocator<br/>(range per node)"] --> E["base62(id) → 6LAze"] --> DB[(code → url)]
    R[GET /6LAze] --> CA{cache} -->|miss| DB
    CA -->|hit| RD[301/302 redirect]
```

## Solution

```java title="UrlShortener.java"
--8<-- "coding-interview/src/UrlShortener.java"
```

## Walkthrough

| Code | Why |
|---|---|
| `sb.append(ALPHABET.charAt((int) (id % 62))); id /= 62; ... reverse()` | Repeated division gives the digits least significant first, so reverse at the end. |
| `Math.multiplyExact(id, 62) + digit` | Decoding user-supplied codes must detect overflow. A 20-character code would wrap around silently with plain `*`. |
| `new Shortener(100_000_000L)` | Starting high avoids 1–2 character codes. Sequential codes are guessable, so if that matters, encode `id XOR secret` or shuffle the bits. |
| `urlToCode.computeIfAbsent(url, ...)` | The same long URL returns the same code (optional, and it needs a reverse index). Some products deliberately create a new code per user for analytics. |
| `codeToUrl.putIfAbsent(alias, url) != null → taken` | An atomic claim of the custom alias. |

## Complexity

| Approach | Time | Space |
|---|---|---|
| encode / decode | O(code length) | O(1) |
| shorten / resolve | O(1) map ops | O(URLs) |

## Run it

```bash
java coding-interview/src/UrlShortener.java      # JDK 21+ single-file launcher, no build needed
```

```text
--8<-- "coding-interview/outputs/UrlShortener.txt"
```

## Solve it with Spring Boot 4

The counter becomes **Redis `INCRBY` in blocks** so every instance can mint ids without a round trip per link, custom
aliases are claimed with an atomic **`SET NX`**, and the redirect path is served from a **Caffeine `@Cacheable`** cache.

```mermaid
sequenceDiagram
    participant C as Client
    participant A as ShortenerController
    participant S as ShortenerService
    participant I as IdBlockAllocator
    participant R as Redis
    C->>A: POST /api/v1/urls {url}
    A->>S: shorten(url, alias)
    S->>I: nextId()
    I-->>R: INCRBY shortener:id-block 1000 (once per 1,000 ids)
    S->>R: SET shortener:code:{base62(id)} url
    A-->>C: 201 Location: https://sho.rt/{code}
    C->>A: GET /s/{code}
    A->>S: resolve(code) [@Cacheable short-urls]
    S->>R: GET shortener:code:{code} (cache miss only)
    A-->>C: 302 Location: long URL
```

=== "Service"

    ```java title="ShortenerService.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p01shortener/ShortenerService.java"
    ```

=== "Id blocks"

    ```java title="IdBlockAllocator.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p01shortener/IdBlockAllocator.java"
    ```

=== "Controller"

    ```java title="ShortenerController.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p01shortener/ShortenerController.java"
    ```

=== "Base62"

    ```java title="Base62.java"
    --8<-- "coding-interview/spring-boot/src/main/java/com/sdp/ci/p01shortener/Base62.java"
    ```

### Run it as a service

The integration test [`ShortenerIT.java`](https://github.com/khiruashish/system-design-playbook/blob/main/coding-interview/spring-boot/src/test/java/com/sdp/ci/p01shortener/ShortenerIT.java) drives the API against a
real Redis. This is its transcript (`>` request, `<` response):

```text
--8<-- "coding-interview/spring-boot/outputs/ShortenerSpringBoot.txt"
```

### What changes from the interview version

| Interview | Production (Spring Boot 4) |
|---|---|
| `long counter++` in one JVM | `INCRBY` id **blocks**: unique across instances, 1 Redis call per 1,000 links |
| `HashMap` alias check then put | `SET NX`: claim-the-alias is atomic, so two users can't both win |
| lookup in a map | Caffeine cache in front of Redis (links are immutable, so no invalidation needed) |
| return a string | `201 Created` + `Location`, `302` redirects, `ProblemDetail` for 400 / 404 / 409 |

**Trade-off to mention:** sequential ids are guessable. Shuffle them (e.g. multiply by a constant modulo 62^7) or use
random 7-character codes with `SET NX` retry if links must not be enumerable.

## Common mistakes

* Using a hash of the URL (MD5 truncated to 7 characters) without handling collisions. With billions of URLs, birthday collisions are certain.
* A single global counter as a bottleneck. Hand each node a block of ids (for example 1,000) at a time.
* Not validating the target URL, which turns the service into an open-redirect and phishing tool.
* Always returning 301. Browsers cache it permanently, so you lose click analytics. Use 302 if you need to count clicks.

## Follow-up questions

??? question "How do you scale id generation across regions?"
    Give each region or node its own range (a range allocator in ZooKeeper or a DB), or use Snowflake-style ids (problem 5). Base62 of a 64-bit Snowflake id is about 11 characters, which is longer but needs no coordination.

??? question "How would you expire links and clean up?"
    Store `expires_at`. The redirect path checks it (and the cache TTL is capped at it). A background job or DynamoDB/Cassandra TTL deletes expired rows. Don't reuse codes for a long time after they expire, to avoid confusion.
