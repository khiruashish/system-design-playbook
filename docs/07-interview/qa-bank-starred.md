# ⭐ Interview Drill Bank

!!! abstract "How to use this page"
    This page lists every high-frequency (⭐) question in the handbook with its **30-second answer**. It is generated from the chapters by `QaBankL1` ([Ch 59](59-interview-qa-bank.md) L1), so it always matches them.

    1. Read the question and **answer aloud** before expanding it.
    2. Compare with the answer. On a miss, open the chapter for the deep answer, trade-offs and follow-ups.
    3. Revisit misses tomorrow, then space the reviews out ([Ch 59](59-interview-qa-bank.md) L4).

    Levels: **B1** engineer · **B2** senior · **B3** staff · **B4** principal. See also the [Full Question Index](../99-appendix/qa-index.md).

### Part 0 · Orientation

**[01. What System Design Is ⭐](../00-orientation/01-what-system-design-is.md)**

??? example "B1 · What is system design, and how is HLD different from LLD?"
    System design is deciding how a software system's parts fit together so that it meets both its functional requirements and its quality requirements (scale, availability, latency, cost) under real-world failures. HLD covers the parts and their interactions: services, datastores, APIs, and data flow. LLD covers how each part is built inside: classes, schemas, algorithms, concurrency.

??? example "B1 · What's the difference between functional and non-functional requirements? Give examples."
    Functional requirements say what the system does ("reject orders to non-serviceable pincodes"). Non-functional requirements say how well it does it, as measurable targets ("p99 ≤ 50 ms", "99.95 % availability", "changes effective within 10 s").

??? example "B2 · Walk me through how you approach a system design problem in 45 minutes."
    Clarify requirements and non-goals (5 min), estimate load and data (5), define APIs (5), data model and storage choice (5), draw the simplest working HLD (10), deep-dive the 2–3 hardest parts (10–15), then cover failure modes and trade-offs (5).

??? example "B2 · Checkout synchronously calls 5 services, each 99.9 % available. What's checkout's availability, and how would you improve it?"
    About 0.999⁵ ≈ 99.5 %, roughly 3.6 hours down per month, assuming independent failures. Improve it by removing synchronous dependencies from the critical path (cache, async, precompute), adding redundancy, degrading instead of failing for non-critical calls, and reducing MTTR.

??? example "B2 · Why aren't microservices always better than a monolith?"
    Microservices trade in-process calls and local transactions for network calls, partial failures, distributed data consistency, and much higher operational cost. They pay off for team autonomy and independent scaling at organizational scale. They cost more than they return for small teams or unclear domains.

??? example "B3 · Our service meets its p99 SLO, but users say the app is slow. What's going on?"
    Likely causes: the SLO is measured in the wrong place (service, not user), the percentile is too low for a fan-out page, averaging across pods hides hot pods, client-side or network time is excluded, or specific segments (a city, low-end devices, one API) are slow while the aggregate looks fine.

??? example "B3 · A cached serviceability answer caused 1,900 undeliverable orders. Walk me through your fix."
    Immediately, shorten the refresh and make checkout re-validate against the source of truth. Structurally, split consistency by use case (browse from snapshot with bounded staleness, checkout fresh and fail-closed), add event-driven refresh via outbox, make staleness a measured SLO, and roll out in shadow mode first.

??? example "B4 · A team proposes 26 microservices for an MVP with 8 engineers. What's your response in the design review?"
    Keep the 26 bounded contexts as the domain model, but deploy them as a modular monolith (or 3–4 deployables) until team count, scaling needs, or change-rate differences justify splitting. Enforce module boundaries in code so extraction stays cheap.

??? example "B4 · What are reversible vs irreversible decisions? Give examples, and how you handle each."
    Reversible (two-way door) decisions are cheap to undo: cache TTLs, library choices inside a service, instance sizes. Irreversible (one-way door) decisions are expensive to undo: service and data boundaries, public APIs, event schemas, partition keys, primary database, tenancy model, data residency. Decide reversible ones fast and measure. Give irreversible ones design docs, alternatives, reviews, and ADRs.


### Part I · Foundations

**[02. Back-of-the-Envelope Estimation ⭐](../01-foundations/02-back-of-the-envelope-estimation.md)**

??? example "B1 · Why do back-of-the-envelope estimates in a system design interview at all?"
    To find out which parts of the design matter. Estimates tell you whether data fits in memory, whether you need caching, sharding or async processing, and where the bottleneck will be. Without numbers, every design choice is a guess.

??? example "B1 · Convert 10 million requests per day into requests per second, and give the peak."
    10 M ÷ 10⁵ ≈ **100 req/s average** (116 exactly). Peak depends on traffic shape. With a typical 3–5× daily peak, that's **300–500 req/s**. A scheduled event can reach 20–40×.

??? example "B2 · How do you derive a peak factor instead of just picking one?"
    From traffic shape: hour-of-day and day-of-week histograms, past campaigns and sale starts, and retry behaviour during incidents. Take the per-second or 10-second maximum over the relevant window, divided by the daily average.

??? example "B2 · Use Little's Law to size a database connection pool."
    Busy connections = request rate × time each request holds a connection. At 850 req/s × 20 ms = 17 busy connections across the fleet. Give 1.5–2× headroom and split across pods: with 10 pods, ~4 per pod minimum, so 10 per pod is generous.

??? example "B3 · Walk me through a capacity estimate for checkout during a festival sale."
    1.5 M orders that day, 20 % in the first hour, first minute at 5× → ~420 orders/s, ~850 checkout attempts/s. That's ~6 pods for checkout. The real risks are ~110 K req/s of catalog reads, hot inventory rows, DB connection budgets, and the 40-second rise time, so pre-scale.

??? example "B3 · We have autoscaling. Why did the sale still fail in the first minutes?"
    Reactive scaling has latency: metrics scrape (~15 s), HPA sync (~15 s), node provisioning (1–4 min), JVM start and warm-up (20–90 s). A spike rising in 40 s outruns all of it. Meanwhile, overload, retries and latency-based liveness probes reduce capacity further.

??? example "B4 · How would you set up capacity planning as an ongoing process for a 26-service platform?"
    Assign ownership (a capacity owner per tier-1 service and a platform owner overall). Keep measured per-pod capacity profiles current through regular load tests. Forecast quarterly and per event with business input. Plan with policy (P90, zone headroom). Check data tiers. Ship plans as GitOps config. Review actual vs forecast after each event.

??? example "B4 · The business expects 10× growth in 2 years. What breaks first and how do you plan?"
    Estimate each component at 10×. Usually the first to break are single-writer databases (write throughput, connections), hot keys, shared infra (Kafka cluster, Redis shard), cost lines like logs and CDN, and org or operational load, before stateless compute. Plan changes in order of "time to break × lead time to fix."


**[03. Networking Essentials](../01-foundations/03-networking-essentials.md)**

??? example "B1 · What happens when you type api.grocery.in/orders/42 into a browser?"
    DNS resolves the name to an IP (cached at several levels). The browser opens a TCP connection (3-way handshake), negotiates TLS 1.3 (1 RTT, certificate check), sends an HTTP GET over it, and the CDN/LB routes it to a server that builds the response. The browser receives, decrypts and renders it. The connection is kept alive for reuse.

??? example "B1 · TCP vs UDP: when would you use each?"
    TCP gives reliable, ordered byte streams with congestion control. It's used for HTTP/1.1–2, databases and Kafka. UDP gives unordered, unreliable datagrams with no connection. It's used for DNS, real-time voice/video, games, and as the base for QUIC (HTTP/3), which adds reliability per stream on top.

??? example "B2 · Compare HTTP/1.1, HTTP/2 and HTTP/3. Which would you use where?"
    HTTP/1.1 handles one request at a time per connection. HTTP/2 multiplexes many streams over one TCP connection with header compression, but TCP-level HOL blocking remains. HTTP/3 runs over QUIC (UDP) with independent streams, faster handshakes and connection migration. Use HTTP/2 and 3 at the edge (mobile benefits from HTTP/3), and HTTP/2 (gRPC) or keep-alive HTTP/1.1 internally.

??? example "B2 · Why does connection reuse matter so much, and how do you configure it?"
    A new connection costs DNS + TCP (1 RTT) + TLS (1 RTT) plus slow start, which is 100+ ms on mobile and meaningful even inside a data centre at high QPS. Reuse via keep-alive and pools, set pool size from Little's Law, and order idle timeouts: client idle TTL < server keep-alive < LB idle timeout.

??? example "B2 · When would you choose SSE over WebSockets? Design live order tracking."
    Tracking is server → client, so use SSE: plain HTTP, HTTP/2 multiplexing, built-in reconnect with `Last-Event-ID`. The design: Kafka events go to a router that writes a per-order Redis Stream (replay) and publishes to a per-order Pub/Sub channel. Stream pods subscribe only for orders they serve, send heartbeats every 15 s, close every ~100 s with a retry hint, and replay on reconnect.

??? example "B3 · Users say live tracking 'freezes' after a minute or two. How do you debug it?"
    Suspect idle timeouts on the path: LB (often 60 s), carrier NAT, proxies, and buffering. Check close-reason metrics and timing (do disconnects cluster at 60 s?), LB and ingress timeouts, whether heartbeats exist and are flushed, whether buffering is on, and whether clients have a watchdog and resume.

??? example "B4 · Design the network topology for the grocery platform in one region."
    CDN + WAF at the edge (TLS 1.3, HTTP/2 and 3). A VPC with public subnets only for LBs and NAT, private app subnets across 3 AZs for Kubernetes, and private data subnets for PostgreSQL, Redis and Kafka. Egress via NAT with fixed IPs for partners, private endpoints for cloud services, mTLS in the mesh with identity-based policies, NetworkPolicies as defense in depth, and non-overlapping CIDRs for future regions.


**[04. API Design ⭐](../01-foundations/04-api-design.md)**

??? example "B1 · How do you model REST resources for an orders system?"
    Use nouns and collections: `/orders`, `/orders/{id}`, `/orders/{id}/items`. Use HTTP methods for their semantics (GET read, POST create, PUT replace, PATCH partial update, DELETE remove), explicit action endpoints for business transitions (`POST /orders/{id}/cancel`), opaque ids, money in minor units with currency, and ISO-8601 UTC timestamps.

??? example "B1 · What's the difference between PUT, POST and PATCH? Which are idempotent?"
    POST creates or triggers an action and is not idempotent by default. PUT replaces a resource at a known URI and is idempotent (same request, same result). PATCH partially updates and isn't necessarily idempotent (for example "increment quantity"). GET, HEAD, PUT and DELETE are idempotent by definition.

??? example "B1 · Offset vs cursor pagination: which and why?"
    Offset is simple and supports "jump to page N," but costs O(offset) and returns duplicates or skips when data changes. Cursor (keyset) pagination seeks from the last item's sort key: O(limit) at any depth, and stable under inserts, but only next/prev. Use cursor for APIs, feeds and history. Use offset for small admin tables.

??? example "B2 · Design an idempotent 'create order' API."
    The client sends an `Idempotency-Key` (a UUID per logical attempt, persisted locally for retries). The server scopes it per customer, stores a fingerprint of the request, claims the key with a unique insert before doing work, completes the key in the same transaction as the order write with the stored response, replays that response on retries, returns 409 + `Retry-After` while in progress, returns 422 on reuse with a different body, and expires keys after 24 h.

??? example "B2 · What problem do ETags solve?"
    Two problems. Caching: `If-None-Match` → 304 saves bandwidth. Concurrency: `If-Match` on writes prevents lost updates, because if the resource changed since the client read it, the server returns 412 and the client re-fetches.

??? example "B2 · How do you version an API, and what counts as a breaking change?"
    Prefer additive evolution within a major version, with URI major versions (`/v1`) for public APIs. Breaking changes: removing or renaming fields, changing types or semantics, making optional fields required, changing status codes, error types, pagination or defaults, and adding enum values that clients switch on exhaustively. Deprecate with headers, metrics and a sunset timeline.

??? example "B2 · REST vs GraphQL vs gRPC: how do you choose?"
    REST/JSON for public and partner APIs: cacheable, universal, easy to document. GraphQL for client-driven aggregation across many services and screens, with batching and cost limits. gRPC for high-performance internal service calls with strict contracts, streaming and deadlines.

??? example "B3 · Your idempotency implementation still produced duplicates under load. What went wrong?"
    Likely causes: check-then-insert without a unique constraint (a race); the key committed only after the work, so duplicates didn't see it; key completion in a different transaction from the business write (crash between the two); keys stored in an evicting cache; keys not scoped (or scoped per pod); or clients generating a new key per retry.

??? example "B3 · Design webhooks from the platform to sellers."
    Produce events from an outbox, and create one delivery row per (event, endpoint). Sign with HMAC-SHA256 over timestamp + raw body, deliver at least once with exponential backoff and jitter for ~72 h, use a stable event id for receiver dedupe, apply short timeouts and per-endpoint concurrency limits, dead-letter with notification, and provide a replay API and a delivery log UI.

??? example "B4 · How would you run API governance across 26 services and several client teams?"
    A lightweight API guild owns a style guide, encoded as Spectral rules. Specs are contract-first in each service repo. CI enforces lint and breaking-change diffs. Shared starters cover Problem Details, idempotency, pagination and correlation ids. Clients and SDKs are generated. There's a deprecation policy with metrics, and reviews only for new public or seller APIs or major versions.

??? example "B4 · You must retire `/v1/orders/history` but 8 % of traffic comes from app versions over a year old. What's your plan?"
    Measure by app version, keep v1 working behind an adapter onto the new implementation, add Deprecation/Sunset headers, ship an in-app force-upgrade prompt via the minimum-supported-version config, run brownouts (scheduled short windows returning 410 with a friendly message), coordinate with product on a sunset date, and only then return 410.


**[05. Relational Databases Deep Dive ⭐](../01-foundations/05-relational-databases-deep-dive.md)**

??? example "B1 · Explain ACID with an example from an e-commerce order."
    Atomicity: order, items, stock reservation and outbox commit together or not at all. Consistency: declared constraints (unique order id, `available >= 0`, foreign keys) hold after commit. Isolation: concurrent transactions don't see each other's partial work, to the degree of the isolation level. Durability: once committed, the order survives a crash (WAL fsync).

??? example "B1 · What is an index, and when does it not help?"
    A sorted structure (a B+ tree) mapping key values to row locations, so lookups and ranges avoid full scans. It doesn't help when the query doesn't use the index's leftmost prefix, when a filter matches a large fraction of rows (a scan is cheaper), when functions are applied to the column without an expression index, or on tiny tables.

??? example "B2 · How do you prevent overselling inventory under high concurrency?"
    Use a single atomic conditional update, `UPDATE stock SET available = available - :q WHERE … AND available >= :q`, and treat 0 rows as sold out. Add `CHECK (available >= 0)`. For multi-item orders, do it in one transaction with lines locked in sorted order, and roll back if any line fails.

??? example "B2 · Optimistic vs pessimistic locking: when do you use each?"
    Optimistic (a version column, 0 rows = conflict) suits low-contention edits and user-facing concurrency (ETags). Pessimistic (`SELECT … FOR UPDATE`) suits high-contention or multi-step logic that can't be one statement, at the cost of holding locks. For counters, an atomic conditional update beats both.

??? example "B2 · What is the N+1 problem, and how do you fix it in JPA?"
    Loading N parents, then lazily loading each parent's association, issues 1 + N queries. Fix it with fetch joins or `@EntityGraph` for single queries, batch fetching (`@BatchSize` or `default_batch_fetch_size`) for collections, DTO projections for read APIs, and a two-query pattern (page parents, then fetch children by IN list) when paginating.

??? example "B2 · How do you size a HikariCP pool?"
    From Little's Law: busy connections = request rate × DB time per request, plus headroom. Split across pods, and check that Σ pods × pool fits the database's connection budget, which should stay near a small multiple of DB cores. Keep `connection-timeout` short, and use a fixed-size pool.

??? example "B3 · You're seeing deadlocks during peak. How do you diagnose and fix them?"
    Read the deadlock report in the PostgreSQL logs (the processes, the waiting locks and the statements). Typical causes are multi-row updates in inconsistent orders, or foreign-key checks and triggers taking locks. Fix by locking in a deterministic order (sort by key), keeping transactions short, avoiding lock upgrades (read then update the same row, which should be a single UPDATE), and adding bounded retries for 40P01.

??? example "B3 · What is write skew, and how do you prevent it? Give a real example."
    Two transactions each read a condition, then write *different* rows, and together they violate an invariant. For example, each rider checks "at least one other rider is on shift at this store" and both go off shift. REPEATABLE READ (snapshot isolation) doesn't prevent it. Prevent it with SERIALIZABLE (and retries), by locking a common row (`SELECT … FOR UPDATE` on the store's shift record), or by materializing the invariant as a constraint or counter updated atomically.

??? example "B3 · When and how do you use read replicas safely?"
    Route read-only, staleness-tolerant queries to replicas (browsing, history, dashboards). Keep decision reads and anything right after a user's write on the primary, or verify the replica has replayed the write's LSN (read-your-writes). Monitor lag, and never run analytics on OLTP replicas.

??? example "B4 · The orders database is growing 2 TB a year and writes are rising. What's your plan?"
    First, measure (top queries, write mix, working set). Then, in order: fix queries and indexes; partition orders by month (pruning, cheap retention); archive cold partitions to object storage or a warehouse; move reads to replicas and read models; reduce write amplification (fewer indexes, batching). Only when a single primary's write capacity is on a runway shorter than the migration lead time, plan sharding (by customer or region) or a distributed SQL database.


**[06. NoSQL & Polyglot Persistence ⭐](../01-foundations/06-nosql-and-polyglot-persistence.md)**

??? example "B1 · SQL vs NoSQL: how do you choose?"
    Start from access patterns and consistency needs, not labels. Relational databases give transactions, constraints, joins and flexible queries, which suit the system of record for orders and money. NoSQL families trade some of that for a specific strength: key-value for sub-ms access, document for self-contained aggregates, wide-column for massive writes by key, graph for traversals, search for relevance.

??? example "B1 · Explain the CAP theorem in practical terms."
    When a network partition splits a distributed system, each side must choose between returning possibly stale or divergent answers (availability) and refusing requests to stay consistent (consistency). Partitions happen, so the choice is really C vs A during partitions, and it can be made per operation.

??? example "B2 · Model rider location history in Cassandra."
    One table per query. For "a rider's path for a day": `PRIMARY KEY ((rider_id, day), recorded_at)` with `recorded_at DESC` clustering, a 7-day TTL, and TWCS compaction. For "path for an order": a second table keyed by `order_id`. Bucketing by day bounds partitions (~1 MB per rider per day).

??? example "B2 · What is tunable consistency, and what does R + W > RF mean?"
    In Dynamo-style stores you choose per operation how many replicas must acknowledge a write (W) and answer a read (R). If R + W > RF, every read overlaps at least one replica that has the latest acknowledged write, giving read-your-writes for that key (absent concurrent writes and failures during repair).

??? example "B2 · How would you find the nearest available riders to a dark store?"
    Keep the latest positions in a geo index (Redis GEO) per city. `GEOSEARCH` by radius ascending with a small count, then filter by availability. Prune riders not seen for 60 s, and guard updates with sequence numbers so out-of-order pings don't regress positions.

??? example "B3 · How do you detect and fix a hot partition?"
    Detect it with uneven node metrics, `nodetool toppartitions`, per-partition latency, or cloud "hot key" metrics. Fix it by changing the partition key to spread load (add a bucket such as time or a hash suffix), splitting hot keys into N sub-keys and merging on read, caching hot reads, or rate-limiting abusive producers.

??? example "B3 · How do you keep multiple stores (DB, cache, search, Cassandra) consistent?"
    Don't dual-write from the app. Write once to the system of record (DB + transactional outbox, or an event log), then feed each derived store from that log with its own consumer, using idempotent writes and replay for recovery. Accept and measure eventual consistency.

??? example "B4 · A team wants a new database for a feature. How do you run the decision?"
    Require a short design doc: access patterns with volumes and SLOs, consistency needs, growth, the system of record, rebuild story, and a weighted decision matrix (fit, consistency, scale, **operability**, cost, ecosystem) including "use an existing store." Prefer existing or managed stores unless the gap is large, and record the deciding factor in an ADR.


**[07. Caching ⭐](../01-foundations/07-caching.md)**

??? example "B1 · What is caching, and how do you measure whether it's working?"
    Keeping copies of frequently read data in faster storage closer to the reader, to cut latency and origin load. Measure it by hit ratio (request-weighted), origin load from misses, latency per layer, and freshness (how stale served data can be).

??? example "B2 · Cache-aside vs read-through vs write-through vs write-behind?"
    Cache-aside: the app loads on a miss and deletes on write. It's simple and common. Read-through: the cache library loads on a miss (enables single-flight). Write-through: write the cache and DB together. Reads are always warm, but writes are slower and cache unread data. Write-behind: write the cache, flush to the DB later. Very fast, but risks data loss, so never for money.

??? example "B2 · What is a cache stampede, and how do you prevent it?"
    Many concurrent misses on the same key (expiry, flush, cold start) all hit the origin at once. Prevent it with single-flight (coalescing), probabilistic early refresh (XFetch), stale-while-revalidate, TTL jitter, warming, distributed locks for the hottest keys, and origin protection (permits and shedding).

??? example "B2 · How do you keep a cache consistent with the database on updates?"
    Write the DB (the SoR), then invalidate the cache after commit, ideally from a change event (outbox or CDC) so no update is missed. Readers repopulate on a miss. Protect against the reader/writer race with versioned conditional sets or leases. Bound the worst case with TTLs, and don't use caches for decisions.

??? example "B3 · Explain the cache-aside race condition and how you'd fix it."
    A reader misses and loads the old value from the DB. A writer updates the DB and deletes the cache key. The reader then writes the old value into the cache, where it stays until the TTL. Fix it with versioned conditional writes: store the SoR version and accept a set only if its version ≥ the stored one, with invalidation writing a tombstone at the new version. Alternatives are leases, CDC-only cache population, or short TTLs as mitigation.

??? example "B3 · How do you handle a hot key in Redis?"
    Detect it (per-shard metrics, `--hotkeys`, client-side sampling). Absorb repeated reads with an in-process L1 cache. Replicate the key under several names on different shards and read a random replica. For writes, shard counters or batch updates. Pre-identify hot keys for known events.

??? example "B3 · Redis goes down during peak. What happens in your design?"
    L1 keeps serving hot keys for its TTL. Misses go to the DB through per-pod single-flight, capped by a permit budget, and excess requests get a fast 503 with Retry-After (low-priority traffic first) rather than piling onto the DB. Once Redis recovers, it warms gradually. Decisions (checkout) never depended on Redis.

??? example "B4 · Design caching for a product catalog serving 190 K req/s at sale start."
    CDN for images and anonymous listings (surrogate keys). A Caffeine L1 per pod (hot 10–50 K keys, 5 s TTL, single-flight). Redis L2 with versioned values, XFetch, TTL jitter, and hot-key replicas for sale SKUs. Outbox-driven invalidation (L2 tombstones, L1 broadcast, CDN purge). Pre-sale warming. DB permits and shedding. Checkout re-prices from the SoR.


**[08. Load Balancing, Proxies & Gateways ⭐](../01-foundations/08-load-balancing-proxies-and-gateways.md)**

??? example "B1 · What's the difference between a load balancer, a reverse proxy and an API gateway?"
    A load balancer distributes traffic across instances. A reverse proxy fronts servers, terminating client connections and forwarding requests (and can balance, cache, compress). An API gateway is a reverse proxy with API-specific policies: authentication, rate limiting, routing by path and version, quotas and transformations.

??? example "B1 · L4 vs L7 load balancing?"
    L4 balances connections based on IP and port, with no HTTP awareness. It's fast and protocol-agnostic. L7 understands HTTP/gRPC and balances individual requests, enabling path routing, retries, header manipulation and auth, at more CPU cost.

??? example "B2 · Why avoid sticky sessions, and when are they acceptable?"
    They create uneven load, lose state on deploys, scale-in or failures, and stop autoscaling from helping existing users. Make services stateless with shared session stores. Affinity is acceptable for performance (cache locality via consistent hashing) when losing it is harmless.

??? example "B2 · Explain consistent hashing and why virtual nodes are needed."
    Keys and servers hash onto a ring, and each key goes to the next server clockwise. Adding or removing a server moves only ~1/n of keys, unlike `hash % n`, which moves almost all of them. Virtual nodes (many ring points per server) smooth the distribution and let you weight servers.

??? example "B2 · How do you configure retries at a gateway safely?"
    Only for idempotent requests (GET, PUT, DELETE, or POST with an idempotency key), on a different instance, at most once or twice with jittered backoff, within a fleet-wide retry budget, at a single layer, and never beyond the caller's deadline. Pair with circuit breakers.

??? example "B3 · One of twelve pods is slow but passes health checks, and 40 % of checkouts fail. Explain and fix it."
    Each checkout makes ~6 calls, so P(at least one hits the bad pod) ≈ 1 − (11/12)⁶ ≈ 41 %. Round-robin keeps sending it 1/12 of traffic, and shallow health checks don't notice. Fix it with latency-aware balancing (P2C with EWMA), passive outlier ejection, readiness reflecting real capacity, idempotent-only retries on different instances, and per-instance latency alerts.

??? example "B3 · How do you prevent identity spoofing between the gateway and services?"
    The gateway strips client-supplied identity and internal headers, validates the token, and sets identity headers itself. Services are unreachable except through the gateway or mesh (NetworkPolicy + mTLS identities). Services still check resource ownership. Alternatively, forward the original JWT and have services validate it.

??? example "B4 · Design global traffic management for an India-focused platform with DR."
    Anycast CDN/WAF with Indian PoPs terminates TLS near users and routes to the primary region's L7 LB (Mumbai) based on health, with a health-checked failover origin (Hyderabad) for DR. DNS points at the CDN, so failover doesn't depend on DNS TTLs. Regional stacks are three-AZ.


**[09. Scalability Fundamentals ⭐](../01-foundations/09-scalability-fundamentals.md)**

??? example "B1 · Vertical vs horizontal scaling: what's the difference, and when would you choose each?"
    Vertical means a bigger machine: no code change, but it has a ceiling, a single failure domain, and superlinear cost at the top. Horizontal means more machines: near-linear capacity and better fault isolation, but it needs stateless services or partitioned state and brings coordination costs. Scale stateless tiers horizontally from day one, and scale databases vertically until measurements say you must partition.

??? example "B1 · What does 'stateless service' mean, and why does it matter for scaling?"
    Any instance can serve any request, and losing an instance loses only in-flight work, because state lives in stores designed for it (a database, Redis, object storage, tokens). That enables load balancing without stickiness, autoscaling, rolling deploys and failure recovery.

??? example "B2 · Walk me through scaling a grocery app from 1 K to 1 M daily users."
    One box, then a separate managed database, then stateless app instances behind a load balancer with sessions and uploads externalized, then caching and a CDN, then queues for async work and read replicas, then splitting hot capabilities (search, tracking, payments), then multi-AZ with pre-scaling and city partitioning for hot write paths. Each step is triggered by a measured limit.

??? example "B2 · How do you make scheduled jobs safe when a service runs on 40 pods?"
    Ensure one execution per interval with a job lock (ShedLock, Redis `SET NX PX` with an owner token, or a DB lock) or leader election, and make the work idempotent, because leases can expire while a pod is paused. For large jobs, partition the work so every pod takes a disjoint slice (`SKIP LOCKED`, Kafka partitions).

??? example "B2 · Why is storing a cart in the HTTP session a problem at scale?"
    The whole session is rewritten on each change, so concurrent updates from two tabs or devices overwrite each other (last write wins). It can't be merged atomically on login, its lifetime is tied to the session, it can't be queried (abandoned-cart jobs), and it can't be partitioned meaningfully.

??? example "B3 · We doubled the pods and throughput went down. Explain and fix it."
    Retrograde scaling: shared costs grew faster than capacity. Contention (σ): a global lock, a hot key, one database primary. Coherency (κ): all-to-all broadcasts, cluster membership chatter, cross-shard coordination. Plus connection overruns. The USL models it: C(N) = N / (1 + σ(N−1) + κN(N−1)) peaks at √((1−σ)/κ). Fix the sources, then cap replicas.

??? example "B3 · Design exactly-once guest-to-customer cart merge across many pods."
    Store both carts under the same hash tag (same city slot) in Redis and run one Lua script that checks a merge marker, merges lines with limits (capped quantities, max lines), deletes the guest cart, and sets the marker, all atomically. Retries on any pod see the marker and return the current cart.

??? example "B4 · How do you choose a partition (Z-axis) key for a grocery platform?"
    Choose the key that keeps most operations inside one partition and spreads load: city (or zone within big cities) for carts, inventory, orders-in-flight, riders and stores, because they rarely cross cities. Check skew (Bengaluru vs Mysuru), and give big cities dedicated partitions while packing small ones together. Customer-level views across cities go to read models.


### Part II · Distributed Core

**[10. Replication ⭐](../02-distributed-core/10-replication.md)**

??? example "B1 · Why do we replicate databases?"
    Durability (survive disk, node or AZ loss), availability (promote a copy when the leader fails), read scaling (serve reads from followers), latency (copies near users) and isolation (analytics on a separate copy). Each benefit comes with a consistency cost: copies can be stale or disagree.

??? example "B1 · Synchronous vs asynchronous replication?"
    Async: the leader commits locally and ships changes later. It's fast, but a failover can lose the lag. Sync: the commit waits until standbys have the change, so there's no loss on failover, but writes are slower and can block if standbys are unavailable. Quorum sync (`ANY 1` of 2) balances the two.

??? example "B2 · How do you guarantee read-your-writes and monotonic reads with read replicas?"
    Keep a session token equal to the highest log position (LSN) the session has written **or read**. Route a read to a replica only if it has replayed up to the token, otherwise use the leader. After each read, advance the token to that replica's position.

??? example "B2 · Explain quorums: why R + W > N, and what it doesn't guarantee."
    With N replicas, if writes wait for W acks and reads query R replicas with R + W > N, every read overlaps at least one replica with the latest acknowledged write. It doesn't give linearizability (concurrent reads can disagree), it doesn't resolve concurrent writes, and sloppy quorums can break the overlap.

??? example "B2 · Why is last-write-wins dangerous?"
    It silently discards all but one concurrent write, and it picks the winner by timestamp, so clock skew can make an older write win. Users lose edits with no error.

??? example "B3 · A failover lost 4 seconds of committed payments. How do you fix it for the future and for now?"
    Now: reconcile captured payments at the provider against the new primary, re-apply or recreate what's missing, refund where impossible, and communicate. Future: quorum synchronous replication for money-class data so the promoted node has every acknowledged commit, `maximum_lag_on_failover`, fencing, and an automatic reconciliation on every failover.

??? example "B3 · What happens if a client times out while committing on a synchronous-replication primary?"
    The transaction may already be committed locally, waiting for standbys. PostgreSQL even warns "already committed locally, but might not have been replicated". The client must treat the outcome as unknown: retry idempotently or check status, never report failure or re-execute non-idempotently.

??? example "B4 · Design replication for the grocery platform's data classes."
    Money and orders: single-leader PostgreSQL, quorum sync across AZs, fenced failover, reconciliation. Catalog and profiles: async replicas for reads, with session tokens. Carts and sessions: Redis async replicas plus DB snapshots. Location history: leaderless Cassandra `LOCAL_QUORUM`. Events: Kafka `acks=all`, `min.insync.replicas=2`. DR: async cross-region, with RPO seconds for region loss.


**[11. Partitioning & Sharding ⭐](../02-distributed-core/11-partitioning-and-sharding.md)**

??? example "B1 · What's the difference between partitioning, sharding and replication?"
    Partitioning splits a dataset into disjoint pieces. Sharding is partitioning across servers, so each server holds and writes only part of the data. Replication copies the same data to several servers. Replication scales reads and survives failures, and only partitioning scales writes and storage. Real systems do both: each shard is replicated.

??? example "B1 · Range vs hash partitioning?"
    Range partitioning keeps contiguous key ranges together, so range scans are cheap and old ranges drop easily, but monotonic keys (time) send all writes to one partition. Hash partitioning spreads keys evenly, including monotonic ones, but any range query or query without the key touches every partition.

??? example "B2 · How do you choose a shard key?"
    From access patterns. List queries and transactions by volume and latency need, and choose the key that keeps the most important ones on one shard, with high cardinality, low skew and spread-out writes. Then plan explicitly for every query that doesn't use it.

??? example "B2 · Why is `hash mod N` a bad sharding function?"
    Changing N remaps almost every key: a key stays only if `h mod N == h mod (N+1)`. L1 measured 80.1 % of keys moving from 4 to 5 shards. Consistent hashing or fixed buckets move only ~1/N.

??? example "B3 · Design sharding for an orders database that must grow from 4 to 16 shards."
    Hash `customer_id` into 1,024 fixed buckets, keep a versioned bucket → shard map, co-locate orders/items/payments by bucket, put the bucket in order ids, and build an online mover (copy, catch up, fence, verify, flip, delete later). Store queries use an event-fed read model. Each shard is a replicated cluster.

??? example "B3 · How do you route a request that only has an order id?"
    Encode the bucket in the id at creation (`ord_214_…` → bucket 532), so any service can compute the shard without a lookup. Alternatives are a global id → key lookup table (another store to keep consistent and highly available) or a scatter-gather (expensive).

??? example "B3 · How do you handle a hot key like a mega store or a flash-sale product?"
    First identify it (per-key metrics). For write-heavy keys, split it into N salted sub-keys and read all salts. For read-heavy keys, cache and replicate it. For a hot bucket, move it to a dedicated shard. For counters, use sharded counters.

??? example "B4 · How do you move a bucket to a new shard without losing writes?"
    Bulk copy while writable. Catch up deltas until they're small. Fence the source for the bucket so writes there are refused. Copy the final delta, verify with count + checksum, flip the versioned map, and delete from the source after a grace period. Stale writers get `BUCKET_MOVED`, reload the map and retry.


**[12. Consistency, Time & Consensus ⭐](../02-distributed-core/12-consistency-time-and-consensus.md)**

??? example "B1 · What's the difference between strong and eventual consistency?"
    Strong (linearizable) consistency makes a replicated system behave like one copy: once a write completes, every later read sees it. Eventual consistency only promises that replicas converge if writes stop, so reads in between may be stale or out of order.

??? example "B1 · Why can't we trust timestamps from different servers to order events?"
    Every machine's clock drifts and is corrected by NTP only to within milliseconds, sometimes much worse, and clocks can jump. Two events a few ms apart on different machines can get timestamps in the wrong order. L1: a 90 ms skew timestamped the effect 87 ms before its cause.

??? example "B2 · Linearizability vs serializability?"
    Serializability is a transaction isolation guarantee: concurrent transactions behave as if run one at a time in some order, not necessarily real-time order. Linearizability is a recency guarantee on single objects: each operation appears to happen instantly between its start and end, respecting real time. Both together is strict serializability.

??? example "B2 · Lamport clocks vs vector clocks?"
    A Lamport clock is a single counter that guarantees cause < effect, but a smaller timestamp doesn't prove causality, so it can't detect concurrency. A vector clock keeps a counter per process and can tell "happened-before" from "concurrent", at the cost of size that grows with the number of processes.

??? example "B3 · Walk me through Raft leader election and log replication."
    Followers that time out (randomized) become candidates, increment the term and ask for votes. A node grants one vote per term, only to candidates whose log is at least as up-to-date. A majority makes a leader. The leader appends commands and replicates them with a consistency check on the previous entry. An entry from the current term is committed once it's on a majority, and committed entries are applied in order.

??? example "B3 · Why can't a Raft leader commit an entry from an earlier term just because a majority has it?"
    Because a node with a higher last term, but missing that entry, can still win a later election and overwrite it. That's the Raft paper's Figure 8. A leader counts replicas only for entries of its current term. Earlier entries become committed indirectly.

??? example "B4 · How does Spanner achieve external consistency, and what do databases without atomic clocks do?"
    TrueTime returns an interval guaranteed to contain real time. A transaction picks commit timestamp `TT.now().latest` and waits until `TT.now().earliest` passes it (commit wait, about 2ε) before becoming visible. So later transactions always get larger timestamps. CockroachDB uses HLC with a max clock offset: reads that find values inside their uncertainty window restart at a higher timestamp, and nodes beyond the offset bound shut down.


**[13. Distributed Transactions ⭐](../02-distributed-core/13-distributed-transactions.md)**

??? example "B1 · Why can't a `@Transactional` method make a microservice checkout atomic?"
    It only controls the local database connection. Calls to other services, payment gateways and brokers happen outside that transaction and aren't rolled back when it rolls back. The result is charged-without-order, stock taken without an order, or events for orders that don't exist.

??? example "B1 · What's the dual-write problem?"
    A service updates its database and notifies another system (a broker, cache or index) as two separate writes. A crash or failure between them leaves them inconsistent: commit-then-publish loses events, and publish-then-commit creates phantom events.

??? example "B2 · Explain the transactional outbox pattern end to end."
    Write the business change and an outbox row describing the event in one local transaction. A relay, by polling with `FOR UPDATE SKIP LOCKED` or tailing the WAL with CDC, publishes outbox rows to the broker and marks or deletes them. Delivery is at least once, so consumers dedup.

??? example "B2 · How do you make a Kafka consumer's effect exactly once?"
    Accept at-least-once delivery and make the effect idempotent: record the message id in an inbox table (or use a natural key) in the same database transaction as the effect, and commit the offset afterwards. Redeliveries then find the id and skip.

??? example "B3 · Design the checkout saga for a grocery app."
    An orchestrated saga in order-service: create the order PENDING and the saga row, reserve stock (a TCC hold with TTL), capture payment with the saga id as the idempotency key (the pivot), confirm the hold, then confirm the order and emit the event through the outbox. On a decline: refund if needed, release, cancel. State is persisted per transition with conditional updates, and a recovery worker resumes stuck sagas.

??? example "B3 · How do you handle a payment call that times out?"
    Treat it as an unknown outcome, not a failure. Retry with the same idempotency key, and the provider returns the original result if it happened. If it stays unknown, park the saga and let reconciliation against the provider's records decide. Never compensate or retry without the key.

??? example "B3 · What's TCC, and how do you prevent overselling with holds that expire?"
    Try-Confirm-Cancel: try reserves resources, confirm makes them final, cancel releases them, and an expiry releases unconfirmed tries automatically. To prevent overselling, confirm and expiry must be conditional updates in the same database: confirm only if the hold is still HELD and not expired, and release only if it's still HELD.

??? example "B4 · When is 2PC the right choice?"
    When all participants are resource managers you control, in one failure domain, with a replicated or highly available coordinator, and blocking during coordinator recovery is acceptable. For example, the multi-shard transactions inside a distributed SQL database, which runs 2PC over Raft groups. Not across microservices or external APIs.


**[14. Messaging & Event Streaming ⭐](../02-distributed-core/14-messaging-and-event-streaming.md)**

??? example "B1 · Queue vs pub/sub log: what's the difference?"
    In a queue, each message is processed by one consumer and removed when acknowledged. Consumers compete, and there's no history. In a log such as Kafka, messages are appended and kept for a retention period. Each consumer group tracks its own offset, so many independent consumers can read the same events at their own pace and replay them.

??? example "B1 · What do at-most-once, at-least-once and exactly-once mean?"
    At-most-once: a message is processed zero or one time (can be lost). At-least-once: one or more times (can be duplicated). Exactly-once: once in effect. Where you commit or acknowledge decides it: before processing gives at-most-once, after processing gives at-least-once, and at-least-once plus an idempotent effect gives effectively-once.

??? example "B2 · How does Kafka guarantee ordering, and how can you break it?"
    Order is guaranteed within a partition, and the key picks the partition (murmur2 mod N). So all events for one key are ordered if you key by the entity. You break it with no or wrong keys, by changing the partition count (keys remap), with non-idempotent retries with several in-flight requests, with retry topics, and with parallel processing inside a partition.

??? example "B2 · How many partitions should a topic have?"
    Enough for peak consumer parallelism (and producer throughput) with headroom for 2–3 years, because you can't shrink partitions and growing them remaps keys. Compute it from the slowest consumer's per-record time and the peak rate, then multiply by about 2.

??? example "B3 · Explain ISR, high watermark, acks and min.insync.replicas together."
    Followers fetch from the leader. Those caught up within `replica.lag.time.max.ms` form the ISR. The high watermark is the highest offset all ISR members have, and consumers read only up to it. `acks=all` waits for the current ISR. `min.insync.replicas` makes the leader refuse writes when the ISR is too small, so `acks=all` can't silently degrade to one copy.

??? example "B3 · How do you handle poison messages in Kafka?"
    Classify failures. Non-retryable ones (bad data, deserialization) go to a DLT immediately. Retryable ones get bounded retries, either in place for strictly ordered consumers (with a small budget) or through delayed retry topics so the partition keeps flowing, then the DLT. Every DLT has an owner, an alert and a replay tool.

??? example "B3 · Consumer lag is growing. What do you do?"
    Measure lag in time per partition to see whether it's all partitions (capacity) or one (hot key or poison message). For one partition: check for a stuck record or DLT flow, or a hot key. For all partitions: scale consumers up to the partition count, speed up processing (batching, async I/O, a slow dependency?), and shed non-critical work. Watch retention: lag must never reach it.

??? example "B4 · Kafka, RabbitMQ, Pulsar, NATS, SQS: how do you choose?"
    From the model you need and the operations you can afford. Kafka for a durable, replayable event backbone with a big ecosystem. RabbitMQ for work queues with rich routing and per-message ack. Pulsar for multi-tenant, geo-replicated streaming with tiered storage. NATS JetStream for lightweight low-latency streams with Raft replication. SQS/SNS for zero-ops cloud queues without replay.


**[15. Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)**

??? example "B1 · What is event sourcing, in one paragraph?"
    Instead of storing an entity's current state and overwriting it, you store every change as an immutable event (`Credited ₹500`, `Debited ₹320`). The current state is computed by replaying the events in order. You get a full audit trail, time travel and the ability to build new views from history. You pay with complexity in versioning, projections and concurrency.

??? example "B1 · What is CQRS?"
    Command Query Responsibility Segregation separates the model that handles writes (commands, validated against an aggregate) from the models that serve reads (read models or projections shaped for each query). Read models are updated asynchronously from the write side's changes or events.

??? example "B2 · How do you handle concurrent writes to the same aggregate in an event store?"
    Optimistic concurrency. Load the stream (version N), decide, and append with "expected version N". The store rejects the append if the stream is no longer at N. The caller reloads, re-decides against the new state (which may now reject the command) and retries.

??? example "B2 · Why can a projector miss events, and how do you prevent it?"
    If the global position comes from a database sequence, it's assigned at insert, but the row becomes visible at commit. Transactions commit out of order, so a projector that checkpoints "the highest position seen" can move past a position that commits later and never read it. Prevent it by reading only up to what can't change anymore (PostgreSQL: transaction ids below the snapshot's `xmin`), or by using a commit-ordered log.

??? example "B3 · How do you evolve event schemas in an event-sourced system?"
    Never rewrite history. Make additive changes where possible, version every event, and convert old versions on read with upcasters (pure, exact, deterministic functions, chained v1 → v2 → v3). Use a new event type for semantic changes, and copy-and-transform migrations only as a last resort.

??? example "B3 · How do you rebuild a read model without downtime?"
    Blue/green projections. Build `order_tracking_v2` alongside v1 from position 0, in parallel by stream hash if needed. When v2 has caught up with the live position, switch queries to v2 (a feature flag or view swap), then retire v1.

??? example "B4 · Should the grocery platform event-source orders?"
    Probably not. The business needs the current order state and a timeline, which tables + outbox events + a CQRS timeline read model provide. Event-source where history is the product: the wallet ledger (audit, disputes) and slot bookings. Keep orders in tables with state transitions guarded by versions (Ch 12).


**[16. Microservices Architecture ⭐](../02-distributed-core/16-microservices-architecture.md)**

??? example "B1 · What are microservices, and why would a company use them?"
    An architecture where the system is split into small services, each owning a business capability and its data, deployed independently and communicating over the network. The main reason is organizational: many teams can change and release their parts without coordinating, and services can scale and fail independently.

??? example "B1 · What does 'database per service' mean, and why?"
    Each service owns its tables, and no other service reads or writes them directly. Others go through its API or its events. It keeps schema changes local, lets the owner enforce invariants, and prevents one service's load from locking another's data.

??? example "B2 · How do you decide service boundaries?"
    By business capability and bounded context: what changes together, who owns the data and its invariants, where the language is consistent, and which team will own it. Check that typical features touch one service and that invariants don't need cross-service transactions.

??? example "B2 · How do services find each other?"
    Through service discovery. Either the platform does it (Kubernetes Services with DNS and endpoints tracking ready pods), or clients query a registry (Eureka, Consul) and load-balance. Instances must be removed quickly when unhealthy, and callers retry idempotent requests on another instance.

??? example "B3 · How do you prevent cascading failures in a microservice graph?"
    Keep the synchronous graph small (no cycles, shallow depth, few dependencies per request), put timeouts on every call, isolate resources per dependency (bulkheads), use circuit breakers and load shedding, degrade optional features, and move facts to events so failures don't propagate synchronously.

??? example "B3 · How do you detect and fix a distributed monolith?"
    Measure it: % of releases touching more than one service, shared tables, synchronous cycles and depth from traces, calls per request, and services with high synchronous fan-in. Then fix the worst coupling first: split shared databases, replace callbacks with events, merge services that always change together, and add fitness functions so it doesn't regress.

??? example "B4 · Modular monolith or microservices for a new grocery startup?"
    Start with a modular monolith (Spring Boot + Spring Modulith, one database with a schema per module, in-process events) while the domain and team are small. Enforce boundaries from day one. Extract services when team count, scaling differences (search, tracking) or isolation (payments/PCI) justify the fixed cost per service.


**[17. Inter-Service Communication](../02-distributed-core/17-inter-service-communication.md)**

??? example "B1 · When do you call another service synchronously vs asynchronously?"
    Synchronously when the caller needs the answer to continue right now (a price, a stock check, a payment result). Asynchronously when the work can happen later or is a fact others react to (order placed → invoice, notification). Synchronous calls couple availability and latency, and asynchronous calls add eventual consistency.

??? example "B1 · Why must every remote call have a timeout?"
    Without one, a slow dependency holds the caller's threads and connections indefinitely. The caller then runs out of capacity and fails even for requests that don't need that dependency, which is a cascade.

??? example "B2 · How do you choose timeout values?"
    Start from the user-facing budget and split it across the call graph. Parallel calls share a slice, and sequential calls add up. Each call's timeout is min(remaining deadline − margin, dependency cap), where the cap is about the dependency's p99.9 plus headroom. Review with real latency histograms.

??? example "B2 · What is deadline propagation?"
    Passing the remaining time budget along with each request, so every hop limits its own work and downstream timeouts to what's left, and refuses to start work that can't finish. gRPC carries it in `grpc-timeout`. For HTTP, use a remaining-milliseconds header.

??? example "B3 · Design the calling conventions for a 26-service platform."
    Rules for sync vs async. REST at the edge, gRPC internally for hot paths. A platform client library that propagates deadlines, applies per-dependency timeouts, bulkheads and metrics, and declares fallbacks. Retries at one layer with budgets. Contract checks in CI. mTLS via the mesh.

??? example "B4 · A dependency's latency spiked and your whole platform went down. Postmortem and fixes?"
    The chain: no timeouts → pool exhaustion in callers → unrelated endpoints failing → retries at every layer amplifying load on the slow dependency → orphan work continuing after users left. Fixes: timeouts from budgets, bulkheads, deadline propagation, single-layer retries with budgets, circuit breakers, fallbacks for optional features, and dashboards per dependency.


**[18. Resilience Patterns ⭐](../02-distributed-core/18-resilience-patterns.md)**

??? example "B1 · What is a circuit breaker?"
    A wrapper around calls to a dependency that tracks failures. When the failure (or slow-call) rate crosses a threshold, it "opens" and immediately fails further calls, usually returning a fallback, without calling the dependency. After a wait it lets a few test calls through ("half-open") and closes again if they succeed.

??? example "B1 · Why add jitter to retries?"
    Clients that fail together will otherwise retry together, creating waves of load that keep the dependency overloaded. Jitter randomizes the wait, spreading retries out so the dependency can recover.

??? example "B2 · Compare token bucket, leaky bucket and sliding window."
    Token bucket: tokens refill at rate r up to capacity b, and each request spends one, so it allows bursts up to b and averages r. Leaky bucket (as a queue): requests leave at a constant rate, which shapes traffic by delaying it and rejects only when the queue is full. Sliding window: counts requests in the last window (exact log or weighted counter) for strict "N per window". Fixed windows allow ~2x at boundaries.

??? example "B2 · How do you tune a circuit breaker?"
    From traffic and failure data: a window big enough to avoid noise but small enough to react within a second or two, a minimum number of calls, a failure-rate threshold (≈ 50 %) plus slow-call thresholds, an open duration tied to how fast the dependency typically recovers, and a few half-open probes. Exclude business errors from failures.

??? example "B3 · What is load shedding, and why is an unbounded queue dangerous?"
    Load shedding rejects excess work early and cheaply when demand exceeds capacity, so the capacity serves requests that can still succeed. An unbounded queue accepts everything, requests wait longer than their callers, and the server spends its capacity on responses nobody reads. Goodput collapses while CPU stays at 100 %.

??? example "B3 · Explain metastable failures."
    A state where a system stays overloaded after the trigger (a failover, a spike) has gone, because a feedback loop (retries, queue buildup, cache misses) keeps load above capacity. Recovery needs removing load, not fixing the original fault.

??? example "B4 · A 25-second database failover caused a 41-minute outage. How do you make sure it can't happen again?"
    Remove the amplifiers: single-layer retries with jitter and budgets, circuit breakers with slow-call detection, bulkheads per dependency, bounded and deadline-aware ingress queues, fallbacks for degradable features, and headroom. Add documented load-drop levers and alerts on "trigger gone but goodput low". Then verify with a game day that repeats the failover under peak-like load.


**[19. Concurrency at Scale in Java](../02-distributed-core/19-concurrency-at-scale-in-java.md)**

??? example "B1 · What is the difference between a process and a thread, and why do servers use threads?"
    A process has its own memory space. Threads are units of execution inside a process that share its memory. Servers use threads so many requests can be handled at the same time, and while one request waits on a database or network call, others can make progress.

??? example "B1 · What are virtual threads?"
    Lightweight threads (JDK 21+) managed by the JVM rather than the OS. When a virtual thread blocks on I/O, the JVM parks it and frees the underlying OS thread (carrier) to run another one. You can have hundreds of thousands, so you can keep writing simple blocking code and still handle huge concurrency.

??? example "B2 · How do you size a thread pool?"
    For CPU-bound work, about the number of cores. For work that waits, `cores × (1 + wait/compute)`, and then verify with a load test, because the real limits are usually downstream (connections, dependency capacity). L1: CPU-bound peaked at 2 threads on 2 cores (967/s). I/O-heavy (0.5 ms CPU + 10 ms wait) peaked at the formula's 42 (3,604/s vs 1,489/s at 16).

??? example "B2 · What is pinning, and how do you avoid it?"
    A virtual thread is pinned when it can't unmount from its carrier while blocked. On JDK 21–23 that happens inside `synchronized` and in native calls. With only a few carriers, a pinned blocking call blocks a whole carrier, so concurrency collapses to the core count. L2: 200 × 50 ms of blocking inside `synchronized` took 5,031 ms on JDK 21, 54 ms on JDK 25, and 53 ms with `ReentrantLock`.

??? example "B2 · Why does `ThreadLocal` behave differently with virtual threads?"
    `ThreadLocal`s still work, but a common idiom breaks: caching an expensive object per thread assumes a few long-lived pooled threads. With a virtual thread per request, every request creates its own copy. L2: a 16 KB buffer per thread cost 1 MB with 200 pooled threads and **349 MB** with 20,000 virtual threads in flight.

??? example "B3 · What is structured concurrency, and what problems does it solve?"
    Treating the subtasks a piece of code starts as one unit with a lexical scope: they're forked inside a block, the block waits for them, a failure or timeout cancels the siblings, and nothing survives the block. It fixes leaked tasks, lost errors, missing cancellation and unreadable thread dumps. In Java it's `StructuredTaskScope` (preview in JDK 25, JEP 505).

??? example "B3 · What changed when you switched to virtual threads, beyond the config flag?"
    Four things: pinning (on JDK 21–23), `ThreadLocal` caches, the loss of the implicit concurrency limit that the thread pool provided, and the realization that connection pools and dependencies are now the bottleneck. The fixes: JDK 24+/`ReentrantLock`, pools or `ScopedValue`, explicit semaphores per dependency, and pool sizes derived from downstream capacity.

??? example "B3 · How do you apply backpressure in a Kafka-consuming service?"
    Don't pull faster than you can process. Keep in-memory hand-off queues bounded, pause partitions when full and resume when drained, and tune `max.poll.records` so a batch finishes within `max.poll.interval.ms`. The backlog then stays in Kafka as consumer lag, durable and visible, rather than in the heap. Scale out with more partitions and consumers when lag grows.

??? example "B4 · Design the concurrency model for the grocery platform's Java services."
    - JDK 25 with virtual threads for request handling (Spring MVC).
    - Explicit concurrency limits per dependency, and connection pools sized from downstream capacity.
    - Bounded platform pools for CPU-heavy work.
    - Structured concurrency, behind a platform wrapper, for fan-out.
    - `ScopedValue` for request context; per-thread caches banned.
    - WebFlux only for streaming and the gateway.
    - Backpressure with bounded queues and Kafka lag.

??? example "B4 · Cancelling a subtask in structured concurrency: what does it actually stop?"
    It interrupts the subtask's thread, so our blocking HTTP call returns and our resources (thread, connection slot) are freed. It doesn't stop the remote service's work. L3 showed 0 client tasks alive after the response, but 276 server-side handlers still completed after the client had moved on.


**[20. Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)**

??? example "B1 · What does idempotent mean, and why does it matter in distributed systems?"
    An operation is idempotent if doing it several times has the same effect as doing it once. It matters because networks lose responses: a client that times out can't tell whether the server processed the request, so it retries. If the operation isn't idempotent, the retry duplicates the effect (a second charge, a second order).

??? example "B1 · What is an idempotency key?"
    A unique value the client generates for one logical operation and sends with every attempt, usually in an `Idempotency-Key` header. The server remembers the keys it has processed and, for a repeat, returns the stored result instead of doing the work again.

??? example "B2 · Implement idempotency for POST /payments. Walk me through the table and the flow."
    - **The table:** `idempotency_record(scope, key, fingerprint, status, status_code, response, created_at)` with PK `(scope, key)`.
    - **The flow:** in one transaction, `insert … on conflict do nothing`. If inserted, create the charge and store the response, then commit. If not, compare the fingerprint (422 if different) and replay the stored status and body.
    - **Retention:** daily partitions dropped after 7 days.

??? example "B2 · How do you make a Kafka consumer idempotent?"
    Record the event id in an inbox table in the same database transaction as the effect: `insert into inbox(consumer, event_id) on conflict do nothing`, and apply the effect only if the row was inserted. Commit offsets after the transaction. Redeliveries and producer duplicates then become no-ops.

??? example "B2 · How do you implement a lock in Redis correctly?"
    Acquire with `SET lock:<name> <unique-owner-id> NX PX <ttl>`. Release with a Lua script that deletes only if the value is still your owner id. Set the TTL well above the critical section's worst case, and renew it for long jobs.

??? example "B3 · The payment call goes to an external gateway. How do you keep it idempotent end to end?"
    Split it into recoverable phases:

??? example "B3 · What is a fencing token, and why isn't a lock with a TTL enough?"
    A number that increases with every lock grant, sent with every operation on the protected resource. The resource rejects operations carrying a token older than the newest it has seen. It's needed because a holder can pause (GC, VM steal) beyond its TTL without knowing. Another process gets the lock, and the paused one wakes up and writes anyway.

??? example "B4 · When do you actually need a distributed lock? What would you use instead?"
    Rarely for correctness. First try:

??? example "B4 · Design the platform standard for idempotency across 26 services."
    - **Public APIs:** every non-idempotent public operation accepts an `Idempotency-Key` scoped per principal.
    - **Service side:** a platform library claims the key in the service's own database in the same transaction as the effect, fingerprints requests, replays responses, and returns 422/409.
    - **Consumers:** every consumer with side effects uses an inbox.
    - **External calls:** carry deterministic downstream keys, with reconcilers.
    - **Proof:** chaos tests (lost responses, duplicates, crashes) in CI.

??? example "B4 · A customer reports a double charge. How do you investigate and what do you change?"
    - **Find the duplicate:** two charges for one order in the ledger (`group by order_id having count > 1`). Check whether they share an idempotency key:
    - Different keys → the client generated a key per attempt, or the app reinstalled.
    - Same key → a server-side race or split transactions.
    - No key → a path without idempotency.
    - **Check the gateway side** for duplicates under different merchant references.
    - **Refund, then fix the root cause, then add a chaos test** that reproduces it.


### Part III · Production

**[21. Data at Scale](../03-production/21-data-at-scale.md)**

??? example "B1 · Why store images in object storage instead of the database?"
    Object storage is built for large, immutable blobs: it's cheap per GB, extremely durable, scales without limits, and serves bytes over HTTP, directly or through a CDN. Blobs in the database bloat backups, replication and the buffer cache, and every download goes through your application servers. Store the key and metadata in the DB and the bytes in object storage.

??? example "B1 · What is a CDN and when does it help?"
    A network of caches close to users that serve copies of content (images, scripts, videos, cacheable API responses) so requests don't travel to the origin. It helps with latency (nearby), origin load (offload) and resilience (stale content during outages). It helps most for content that many users request and that changes rarely.

??? example "B2 · How do presigned URLs work, and what are the security considerations?"
    The server signs a URL with its credentials (for S3, Signature V4): the method, bucket/key, expiry and selected headers go into an HMAC-signed string. Anyone with the URL can perform exactly that operation until it expires, without credentials. Keep expiries short, scope to one key, fix the content type, never log the URLs, and authorise the user before signing.

??? example "B2 · What goes into a CDN cache key, and how do you get a high hit ratio?"
    The key identifies which cached copy answers a request: typically the host, path and query string, plus any `Vary` headers. For a high hit ratio:

??? example "B2 · Explain row vs columnar storage and why Parquet is so much smaller."
    Row storage keeps all columns of a row together, which is ideal for reading or writing whole records by key. Columnar storage keeps each column's values together, which is ideal for scanning a few columns across many rows. Similar values sit next to each other, so encodings (dictionary, run-length, delta) and compression (zstd, snappy) work very well.

??? example "B3 · Warehouse, lake or lakehouse: how do you choose?"
    - **Warehouse** (BigQuery, Snowflake, Redshift): managed, SQL-first, fast. Good when the team is SQL-centric and wants low ops.
    - **Lake** (files on object storage): cheap, open, flexible, but without a table format it becomes a swamp.
    - **Lakehouse** (Iceberg/Delta/Hudi over Parquet on object storage): open storage with ACID commits, schema evolution, deletes and time travel, queried by many engines.

??? example "B3 · Event time vs processing time, watermarks and late data: explain with an example."
    Event time is when something happened (the order was placed at 19:42:10). Processing time is when the stream processor saw it (19:44:30, because the phone was in a basement). Windows must use event time to be correct. A watermark estimates how far event time has progressed ("no events before 19:42:05 are still expected"), and a window fires when the watermark passes its end. Late events are dropped or, with allowed lateness, update the result.

??? example "B4 · Lambda vs Kappa: which would you use for the grocery platform and why?"
    - **Lambda:** a batch layer (complete, recomputed) plus a speed layer (fresh, approximate), merged. Robust, but two codebases.
    - **Kappa:** one streaming pipeline, and reprocessing means replaying the log through a new job version.

??? example "B4 · Design the image and media pipeline for 400K SKUs from 2,000 sellers."
    1. Sellers get presigned upload URLs (a staging bucket, a 5-min expiry, content type fixed).
    2. An object-created event triggers validation (type, size, malware) and resizing into standard renditions (WebP/AVIF, 3 sizes).
    3. Renditions are stored under content-addressed keys, and the catalog references the hashes.
    4. The CDN serves `/img/<hash>-<size>.webp` with 1-year `immutable` caching and a normalised key.


**[22. Search Systems](../03-production/22-search-systems.md)**

??? example "B1 · What is an inverted index?"
    A map from each term to the list of documents that contain it (a postings list), often with term frequencies and positions. To search, you look up each query term's postings and combine them, instead of scanning every document. It's how search engines answer queries over millions of documents in milliseconds.

??? example "B1 · What is an analyzer, and why does it matter?"
    The pipeline that turns text into index terms: normalise characters, split into tokens, then filter (lowercase, stem, synonyms, remove stop words, transliterate). The same analysis must happen to documents at index time and to queries at search time, or they won't match. It's the biggest single lever on search quality.

??? example "B2 · How would you support Hindi, Hinglish and typos in grocery search?"
    - Normalise to a common form at both index and query time: Unicode normalisation, Devanagari→Latin transliteration, and a phonetic normaliser for Hinglish spellings (aa→a, oo→u, collapse double letters).
    - Add cross-language synonyms (milk↔doodh) keyed on the normalised forms, plus index-time aliases per product type.
    - Use guarded fuzziness (≥ 5 characters, penalised) for typos.
    - Measure each step on judged queries.

??? example "B2 · How do you keep a search index in sync with the database?"
    Treat the index as a derived read model. The service writes the entity and an outbox row in one transaction. CDC or a relay publishes events to Kafka keyed by product id. An indexer consumes them and sends bulk upserts/deletes to the engine, preserving per-product order (or using external versions so older writes are rejected). A periodic reconciliation compares counts and checksums, and full rebuilds go to a new index behind an alias.

??? example "B3 · How do you evaluate and improve search relevance systematically?"
    - **Offline:** a judged query set sampled from logs (head, torso, tail, zero-result), graded judgments, and metrics (nDCG@10, precision@k, zero-result rate) run in CI on every change.
    - **Online:** CTR, add-to-cart from search, reformulation rate, with A/B tests or interleaving.
    - **Loop:** a weekly review of zero-result and low-CTR queries feeds synonyms, aliases, analyzer changes and training data.

??? example "B3 · Explain how HNSW works and how you tune it."
    A multi-layer proximity graph. Upper layers are sparse, long-range links, and layer 0 has all nodes. A search greedily walks from the top entry point down, then does a best-first search with a candidate list of size efSearch on layer 0. Parameters:

??? example "B3 · Should product search results reflect real-time stock at the customer's dark store? How?"
    Yes: showing unavailable items first loses orders. But don't reindex product documents on every stock change across hundreds of stores. Options:

??? example "B4 · Design product search for a grocery platform with 400K SKUs in 12 cities."
    - **Engine:** OpenSearch, an index alias per city (assortment), ~5M docs, 3 primaries × 2 replicas.
    - **Analyzer:** transliteration + Hinglish phonetics + units + light stemming, plus an aliases field and a type field. Query-time synonyms as reviewed config.
    - **Ranking:** a multi_match with field boosts, a function score for popularity, and per-store stock applied at query time.
    - **Sync:** outbox → Kafka keyed by product id → a bulk indexer with external versions.
    - **Relevance CI** on judged queries; hybrid vectors in phase 2.
    - **Targets:** p99 < 150 ms, lag < 5 s.

??? example "B4 · Lexical, vector or hybrid: how do you decide, and how do you fuse results?"
    Lexical (BM25) wins on exact tokens: brands, sizes, SKUs, rare words. Vectors win on intent and paraphrase. Most product search needs both. Run both retrievers and fuse with reciprocal rank fusion (k ≈ 60), weighted by retriever, or normalise scores and blend. Optionally re-rank the fused top 100 with a cross-encoder or LTR model. Decide weights on held-out judged queries and confirm online.


**[23. Security Architecture ⭐](../03-production/23-security-architecture.md)**

??? example "B1 · What's the difference between authentication and authorization?"
    Authentication proves who you are (login, OTP, certificate). Authorization decides what you're allowed to do (roles, ownership, policy). AuthN happens once per session at the edge; authZ must happen on every request and every object. Confusing them is the root of IDOR: the server checks you're logged in but not that the thing you're accessing is yours.

??? example "B1 · How do you validate a JWT safely?"
    Use a vetted library, and:

??? example "B2 · Explain OAuth 2.0 Authorization Code + PKCE and why PKCE matters."
    The app redirects the user to the authorization server, which (after login) returns a one-time code to a registered redirect URI; the app exchanges the code plus a client secret (or, for public clients, PKCE) for tokens. PKCE: the app sends the hash of a random verifier up front (`code_challenge`), and presents the verifier at exchange. An intercepted code is useless without the verifier, which never left the device.

??? example "B2 · How do refresh tokens work, and what is reuse detection?"
    Access tokens are short-lived; a longer-lived refresh token gets new access tokens without re-login. With rotation, each refresh issues a new refresh token and invalidates the old one. If an already-used refresh token is presented again, that means someone has a stolen copy — reuse detection revokes the whole token family and forces re-login.

??? example "B3 · Design authorization for the grocery platform. Which model(s), and how enforced?"
    Layer them: RBAC for coarse org roles (customer, rider, picker, store/city manager, finance), ABAC for conditions (ownership, store/city scope, refund limits, purpose), and ReBAC for object relationships and hierarchy (this order belongs to this store in this city). Centralize policy (OPA/Cedar + OpenFGA/SpiceDB), enforce locally in each service on every object, default deny, and test with a conformance suite.

??? example "B3 · How do you encrypt PII so a database breach is useless, and still be able to query and delete it?"
    Envelope-encrypt sensitive fields (AES-256-GCM + KMS-wrapped DEKs) so a DB or backup dump is ciphertext. For querying, keep searchable non-sensitive attributes in the clear or use deterministic encryption / blind indexes for equality lookups (with care — they leak equality). For deletion, use per-customer keys and crypto-shred (destroy the key).

??? example "B4 · Present a defense-in-depth architecture and threat model for the grocery platform."
    Layers, each failing closed:

??? example "B4 · Design PII protection and 'delete my data' for DPDP/GDPR across DB, backups, logs and the data lake."
    - **Minimize and tokenize:** collect less; replace PII with tokens where possible, keeping the mapping in a vault.
    - **Encrypt** remaining PII with per-customer keys (envelope encryption).
    - **Erasure = crypto-shredding:** destroy the customer's key, making PII in backups, replicas and the lake unrecoverable, plus direct deletes where feasible.
    - **Lake:** use a table format with row deletes (Ch 21) and short snapshot retention; pseudonymize in analytical layers.
    - **Logs:** redact PII; short retention.


**[24. Observability ⭐](../03-production/24-observability.md)**

??? example "B1 · Metrics, logs and traces — what's each for?"
    Metrics are cheap numeric time series (rates, gauges, latency histograms) for "is it broken and how much," and drive dashboards and alerts. Logs are detailed structured events for "what exactly happened." Traces follow one request across services for "where did the time or error go." You need all three, sharing a trace id so you can pivot between them.

??? example "B1 · Why percentiles instead of averages?"
    Averages hide the tail. If most requests are fast but 1% take seconds, the average looks fine while some users have a terrible experience — and those are often the ones who abandon. p99/p99.9 show what the worst-served users actually get. L2: a 74 ms average alongside a 1,545 ms p99.

??? example "B2 · How does a Prometheus histogram work, and why are tail quantiles approximate?"
    A histogram counts observations into cumulative buckets (`le`), plus `_sum` and `_count`. `histogram_quantile(q, …)` finds the bucket containing the q-th observation and interpolates linearly within it. Accuracy depends on bucket width: where buckets are wide (usually the tail), the estimate is coarse.

??? example "B2 · How do you keep logs useful and affordable at scale?"
    Structure them (JSON/logfmt) so they're queryable by field; put a trace id on every line; sample by trace (keep a request's lines together) not by line; keep all errors; redact PII; and set retention by class. L1: sampling 1% of INFO traces cut volume to 3% while keeping every error.

??? example "B3 · Design multi-window multi-burn-rate alerting for a 99.9% checkout SLO."
    Burn rate = (error ratio in a window) / error budget. Page when a fast and a slow signal agree:

??? example "B3 · How do you use tracing to find why p99 latency is high?"
    Take slow traces and compute the **critical path** — the chain of spans that determines end-to-end time. Optimize only what's on it. Then aggregate many slow traces to see which service or operation is on the critical path most often at the tail. That names the p99 driver, which per-service metrics can't reveal.

??? example "B4 · Design the observability strategy for the grocery platform."
    - **Instrument once** with OpenTelemetry + Micrometer; export metrics (Prometheus/Mimir), logs (Loki), traces (Tempo) to Grafana.
    - **Metrics:** RED per service, USE per resource, SLO-aligned histogram buckets, controlled cardinality.
    - **Logs:** structured JSON with trace id, trace-based sampling, PII redaction.
    - **Traces:** context propagation, tail sampling keeping all errors.
    - **SLOs** per critical journey with multi-window burn-rate pages; everything else is a ticket.
    - **Exemplars** connect metrics → traces → logs.

??? example "B4 · How do you control observability cost without going blind?"
    - **Metrics:** bound cardinality (the main cost driver), drop risky labels at ingest, downsample old data.
    - **Logs (usually the biggest bill):** structure, sample by trace, keep errors, short retention, archive.
    - **Traces:** tail-sample keeping errors/slow, cap attributes.
    - **Ownership:** per-team cost dashboards so cost has an owner, and review new high-cardinality metrics.


**[25. Performance Engineering](../03-production/25-performance-engineering.md)**

??? example "B1 · Why measure p99, not the average latency?"
    The average hides the tail. Most requests can be fast while a slice are terrible, and the average looks fine — but those slow requests are real users, and in a fan-out system they're amplified. p99/p99.9 show what the worst-served users experience, which is what SLOs and user perception track.

??? example "B1 · What is a profiler and why use one before optimizing?"
    A profiler samples where the program actually spends time (CPU) and allocates memory, so you optimize by evidence instead of intuition — which is usually wrong. In Java, JFR (built in, ~1% overhead) and async-profiler (flame graphs) are the standard tools.

??? example "B2 · Explain tail-at-scale and how to reduce a fan-out request's tail."
    When a request fans out to N services and waits for all, the slowest sets the latency, so the request's tail is worse than any single service's. Reduce it by: fewer/coarser calls (less fan-out), tight timeouts, hedged/backup requests for the slow ones, caching, and fixing the specific slow dependency found via tracing.

??? example "B2 · How do you profile a JVM in production?"
    Run JDK Flight Recorder continuously (low overhead) with a profiling settings template, and pull the `.jfr` when you need it — analyze in JMC/IntelliJ or with `jfr print`. For a live flame graph, attach async-profiler to the PID for CPU, allocation or lock profiling. Correlate with the SLO/trace to profile the right service.

??? example "B3 · Walk through diagnosing a latency regression end to end."
    1. Start from the SLO/percentile that regressed (Ch 24).
    2. Trace to the critical-path service (don't optimize off it).
    3. Profile that service (JFR/async-profiler) for the hot method or allocation.
    4. Check the platform: GC pauses, CPU throttling, lock contention, pool waits.
    5. Fix the biggest thing, re-measure, one change at a time.
    6. Guard it with an open-model load test in CI and an SLO alert.

??? example "B3 · Explain the Universal Scalability Law and what you do with it."
    USL models throughput vs concurrency as `X(N) = N·X₁ / (1 + α(N−1) + βN(N−1))`. α is contention (a serial section, Amdahl), β is coherency (crosstalk between workers). Because of β, throughput can peak and then **retrograde**. You fit it to a few measured load points and it predicts the peak and *why*, so you can plan capacity and target the fix.

??? example "B4 · Design the performance-engineering practice for the platform."
    - **SLOs** define the target (Ch 24); latency is measured at the tail.
    - **Continuous JFR** in production; async-profiler on demand.
    - **Low-pause GC** (generational ZGC) for latency services, G1 for batch.
    - **Open-model, percentile load tests** in CI with regression budgets.
    - **Capacity from USL + Little's law**, running at ≤ 70% of peak.
    - **A method** (SLO → trace → profile → platform → fix → guard) everyone follows.

??? example "B4 · Latency vs cost vs utilization: how do you decide where to run?"
    Utilization is the dial between cost and latency. Low utilization is safe but expensive (more instances); high utilization is cheap until the queueing knee, where latency explodes (L1 C). Pick a target (~60–70% of measured peak) that keeps you off the cliff with headroom for a spike and a lost node, then autoscale around it.


**[26. Deployment & Infrastructure](../03-production/26-deployment-and-infrastructure.md)**

??? example "B1 · What is a container, and how is it different from a VM?"
    A container is an isolated process on the host, using Linux namespaces (its own filesystem/network/PID view) and cgroups (CPU/memory limits). Unlike a VM, there's no guest OS kernel — containers share the host kernel, so they're light and start in milliseconds. The image is immutable and runs identically everywhere.

??? example "B1 · What are readiness and liveness probes, and why keep them separate?"
    Readiness answers "can I serve right now?" — on failure the load balancer stops routing to the pod (but doesn't restart it). Liveness answers "am I alive/not deadlocked?" — on failure the pod is restarted. They're separate because the responses differ: a starting or dependency-blipped pod should be taken out of rotation, not killed; a hung pod should be killed.

??? example "B2 · Design a safe production deployment for a payments-adjacent service."
    Immutable image by digest; deploy as a canary (10% → 25% → 50% → 100%) with automated analysis of error rate, latency and business metrics, and automatic rollback on any SLO breach. readiness/liveness/startup probes gate traffic and restarts. Keep the previous version for instant rollback, and gate risky behavior behind feature flags. For schema-touching changes, expand/contract migrations (Ch 28).

??? example "B2 · How do you run the JVM correctly in a container?"
    Size the heap as a percentage of the memory limit (`-XX:MaxRAMPercentage`), not a fixed `-Xmx`, and leave headroom for non-heap memory — or the kernel OOM-kills the process (exit 137). Verify CPU awareness (`availableProcessors` reflects the quota, sizing GC threads and pools), and be cautious with hard CPU limits on latency services because throttling spikes the p99.

??? example "B3 · Design an autoscaling setup for a service with a daily peak and occasional flash sales."
    - HPA on a leading metric (in-flight requests / RED, not just CPU), target ~70%.
    - Scale up fast, scale down with a stabilization window (avoid thrashing).
    - Model warm-up: count a new replica at partial capacity for its boot time, or scale on a signal that leads utilization.
    - Pre-scale known peaks (sale start) on a schedule; for unpredictable spikes, keep headroom + load shedding (Ch 18) to cover the seconds until capacity arrives.
    - Cluster autoscaler for nodes underneath.

??? example "B3 · Walk through what happens when a pod is OOM-killed vs throws a Java OOM."
    If the JVM's total memory (heap + metaspace + threads + direct buffers) exceeds the cgroup memory limit, the **kernel** OOM-killer sends SIGKILL — the process dies instantly with **exit 137**, no exception, no stack trace, looking like a random crash. If the JVM's heap is capped *below* the limit, it throws a **Java OutOfMemoryError** you can catch, log, and alert on (and with `ExitOnOutOfMemoryError`, exit cleanly, code 3).

??? example "B4 · Design the deployment platform for 26 services across multiple regions."
    - **Package:** immutable containers, deployed by digest.
    - **Orchestrate:** Kubernetes per region, desired state in Git (GitOps/Argo CD).
    - **Deploy:** canary with automated analysis + rollback (Argo Rollouts); blue-green for risky migrations.
    - **Health:** readiness/liveness/startup on every service, with the liveness rule.
    - **Scale:** HPA on RED/util with stabilization + warm-up awareness; cluster autoscaler.
    - **Config/secrets:** ConfigMaps + secrets manager, 12-factor.
    - **Infra:** Terraform modules per region + policy-as-code.
    - **Multi-region:** per-region clusters, global routing with health-based failover; data replication/failover is the hard part (Ch 27).

??? example "B4 · How do requests and limits interact with the scheduler, the kernel and the JVM?"
    *Requests* are what the scheduler reserves and bin-packs on (guaranteed capacity); *limits* are hard caps the kernel enforces via cgroups. Memory over the limit → kernel OOM-kill (137). CPU over the limit → throttling. The JVM reads these cgroup limits to size its heap and thread pools, so the JVM flags must be set relative to the limits (`MaxRAMPercentage`), or the platform and the app disagree — mysterious crashes or throttling.


**[27. Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)**

??? example "B1 · What are RPO and RTO?"
    RPO (Recovery Point Objective) is the maximum data loss you'll accept, in time — "at most 5 minutes of orders." RTO (Recovery Time Objective) is the maximum time to restore service — "back within 30 minutes." RPO is set by backup frequency and replication; RTO by restore speed and failover automation. You tier them per data type.

??? example "B1 · Why isn't 'we have backups' enough?"
    Because a backup you've never restored may not be restorable — it can be silently truncated, corrupted, encrypted the wrong way, or missing the data it references, and the backup job still reports success. Only a restore drill proves recoverability. And a nightly full backup alone means losing up to a day; you also need point-in-time recovery.

??? example "B2 · Design a backup strategy for the orders database."
    Continuous WAL archiving + periodic base backups → point-in-time recovery, so you can recover to any second within the retention window. Store copies per 3-2-1 (3 copies, 2 media, 1 off-site), encrypted, with at least one immutable/air-gapped copy against ransomware. Retention ≥ the recovery window and compliance needs. And — non-negotiable — automated restore drills that verify and measure RTO.

??? example "B2 · Walk through a database failover and how you avoid split-brain."
    Detect the primary is down (via consensus/quorum, not a single probe, to avoid false positives); **fence** the old primary so it can't accept writes; promote a replica to primary; repoint clients/DNS to it; let caches/pools re-warm. Split-brain — two primaries both writing — is the danger, and fencing (or consensus-based leadership) is what prevents it.

??? example "B3 · Design DR for the grocery platform with tiered RPO/RTO."
    Tier by data criticality. Payments/orders: synchronous replication (RPO ~0) + automated fenced failover (RTO < 2 min) + PITR for corruption. Inventory/catalog: async replication + PITR (RPO < 1 min, RTO < 5 min). Analytics: nightly backups, rebuild from source (RPO < 1 h). Whole-region loss: async cross-region replicas promoted on failover. Prove all of it with automated restore and failover drills.

??? example "B3 · Your async replica is your DR copy. What can go wrong and how do you guard it?"
    Three things: (1) it can lag, silently widening your RPO — monitor lag in time and bytes and alert; (2) it faithfully replicates a bad `DELETE` or corruption, so it's not a substitute for PITR; (3) failover can hit split-brain if the old primary isn't fenced. Guard with lag alerts, PITR alongside replication, and fenced/consensus failover.

??? example "B4 · How do you design for a full region outage?"
    Run a second region and pick a pattern by RPO/RTO/cost: backup-restore (hours), pilot light (minimal standby, scale up on failover), warm standby (scaled-down running copy), or active-active (both serve, near-zero RTO). Replicate data cross-region asynchronously (sync is too slow across regions), route with GeoDNS/global LB with health-based failover, and respect data-residency limits. Then drill the region failover.

??? example "B4 · Replication gives fast failover; PITR gives point-in-time recovery. Why do you need both?"
    They defend against different failures. Replication protects against **infrastructure** failure — a dead primary or zone — with a fast failover to a live copy (short RTO). But a replica faithfully copies **logical** damage: a bad `DELETE`, an app bug, corruption. PITR protects against those by recovering to a point *before* the damage. A replica can't undo a dropped table; PITR can't fail over in milliseconds. You need both.


**[28. Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)**

??? example "B1 · What is a zero-downtime schema migration, and why can't you just run `ALTER TABLE`?"
    Zero-downtime means the schema changes while the system keeps serving traffic and while code is mid-deploy. You can't just `ALTER` because (a) many `ALTER`s take a strong lock and rewrite the whole table, stalling every query for the duration, and (b) during a rolling deploy old and new code run at once, so a rename or type change breaks whichever version doesn't expect it. You change the schema in small, backward-compatible steps instead.

??? example "B1 · Explain backward vs forward compatibility."
    Backward compatible = new code can read data written by the old schema (deploy readers first). Forward compatible = old code can read data written by the new schema (deploy writers first). Full = both, which is what you need during a rolling deploy because you can't control which version reads a given row or message.

??? example "B2 · Which PostgreSQL DDL operations block, and how do you avoid it?"
    Blocking: volatile-default `ADD COLUMN`, most `ALTER COLUMN TYPE`, naive `SET NOT NULL`, `ADD CHECK`, and plain `CREATE INDEX` — they take a strong lock and do a full-table operation under it. Safe: nullable/constant-default `ADD COLUMN` (metadata-only), `CREATE INDEX CONCURRENTLY`, and `ADD CONSTRAINT ... NOT VALID` then `VALIDATE CONSTRAINT` (which doesn't block writes).

??? example "B2 · How do you rename a column with no downtime?"
    You don't rename — you expand/contract. Add the new column, dual-write both, backfill history in batches, switch reads to the new column (still dual-writing), then drop the old column once nothing uses it. A rename in place would break every not-yet-upgraded pod the instant it runs.

??? example "B3 · Walk through migrating `orders.total` (bigint paise) to `amount` (numeric rupees) on a 40M-row live table."
    Expand/contract. (1) `ADD COLUMN amount numeric(12,2)` nullable — metadata-only. (2) Deploy dual-write: writes set both `total` and `amount`. (3) Batched backfill `amount = total/100.0` over PK ranges, committing each batch. (4) `ADD CONSTRAINT amount_present CHECK (amount IS NOT NULL) NOT VALID` then `VALIDATE`. (5) Deploy read-new (reads `amount`, still dual-writes). (6) After a stable release, `DROP COLUMN total`. Never rename/retype in place.

??? example "B3 · How do you migrate a dataset from one datastore to another with no downtime?"
    Dual-write to old and new (ideally via an outbox/CDC so you avoid a distributed transaction), backfill history in batches, continuously **reconcile** old vs new (counts, checksums, sampled diffs) and fix the dual-write bugs it finds, switch reads to the new store behind a flag with a canary, keep the old store as fallback, and decommission only after reads are stable.

??? example "B4 · Design an org-wide standard so schema/message/API changes never cause an outage."
    Codify: (1) online-DDL rules with enforced `lock_timeout` and a CI linter that rejects rewriting DDL; (2) expand/contract for all column changes; (3) batched backfills only; (4) Protobuf/Avro on the bus under a registry set to FULL, checked in CI; (5) additive-first APIs with `/vN` only for breaks and Sunset headers; (6) forward-only versioned migrations run as a pipeline step before deploy; (7) store migrations via dual-write + reconcile. Gate destructive steps on usage telemetry.


**[29. Testing Distributed Systems](../03-production/29-testing-distributed-systems.md)**

??? example "B1 · What is the test pyramid, and why that shape?"
    Many fast, isolated unit tests at the base; fewer integration tests in the middle; a few slow end-to-end tests at the top. The shape follows cost and signal: unit tests are milliseconds and pinpoint the failure; e2e tests are slow, flaky, and only say "something broke." You want most confidence to come from the cheap, precise layers. The inverted version — mostly e2e — is the "ice-cream cone" anti-pattern.

??? example "B1 · Mocks vs stubs vs fakes — and the risk of mocking?"
    A stub returns canned values; a mock also asserts on interactions (was this called?); a fake is a working lightweight implementation (in-memory repo); a spy wraps a real object and records calls. The risk: a double encodes *your assumption* about the dependency, which can silently diverge from reality — a mocked service response with the wrong field shape passes your test and breaks in production.

??? example "B2 · What is property-based testing and when do you reach for it?"
    Instead of hand-picked examples, you state an invariant that must hold for *all* inputs and let the framework throw hundreds of random inputs at it, shrinking any failure to the minimal counterexample. Reach for it when a property is easy to state but the input space is large: money math, serialization round-trips, idempotency, ordering-independence, or a fast-vs-reference oracle.

??? example "B2 · Explain consumer-driven contract testing."
    The consumer declares exactly the requests it makes and the response fields it depends on; that contract is published; the provider is verified against it in isolation, with no consumer running. If the provider changes something a consumer relies on, its build fails immediately, naming the fields. It gives cross-service correctness at unit-test speed and lets teams deploy independently.

??? example "B3 · What is linearizability and how do you *test* for it?"
    Linearizability means every operation appears to take effect atomically at some instant between its invocation and its response, consistent with real time and the object's semantics (a read returns the latest write). You can't assert it by example; you **record a history** — each op's real-time window and result — and run a checker that searches for a single sequential order respecting real time and semantics. If none exists, the system violated linearizability.

??? example "B3 · How does Jepsen find consistency bugs that survive normal testing?"
    Jepsen drives a real distributed database with randomized concurrent operations while a **nemesis** injects real faults — network partitions, process pauses, clock skew — records the exact real-time history, and checks it with a linearizability/anomaly checker. Normal tests never combine concurrency *with* faults *with* an automated consistency oracle, so they miss the bugs that only appear in that combination.

??? example "B4 · Design a testing strategy for a payments-critical microservices platform."
    Pyramid with a strong contract layer. Unit + property tests for all money/serialization/idempotency logic (money conservation as a property). Consumer-driven contracts gating every cross-service deploy. Integration tests on real datastores with fault injection. Jepsen/linearizability checks for the ledger and inventory (anything claiming strong consistency), run nightly under a nemesis. Mutation score as the merge gate. Canary/synthetic journeys and SLO monitoring in prod. Invest proportionally to risk — payments get everything, static content gets the base.


**[30. Multi-Tenancy & SaaS Architecture](../03-production/30-multi-tenancy-and-saas-architecture.md)**

??? example "B1 · What is multi-tenancy and why do SaaS products use it?"
    Multi-tenancy means one running system serves many isolated customers (tenants — usually whole organizations), sharing infrastructure while keeping each tenant's data and performance separate. SaaS uses it because sharing one codebase and few deployments across many customers is what makes the unit economics work; the hard part is enforcing isolation so tenants never see or affect each other.

??? example "B1 · Describe the three data isolation models."
    (1) **Shared table** — one table with a `tenant_id` column; cheapest, weakest isolation (a forgotten filter leaks). (2) **Schema-per-tenant** — one schema per tenant in a shared database; medium isolation and cost. (3) **Database-per-tenant (silo)** — a separate database per tenant; strongest isolation, easy per-tenant backup/residency, highest cost. Most SaaS pools small tenants and siloes big ones.

??? example "B2 · What is Row-Level Security and how does it help multi-tenancy?"
    RLS is a database feature that attaches a policy to a table so the engine automatically adds a predicate to every query — for multi-tenancy, `tenant_id = current_tenant`. Then even a query that forgets the tenant filter returns only the current tenant's rows, a cross-tenant update matches nothing, and with no tenant set it returns nothing (fails closed). It's the backstop that makes a forgotten filter a non-event instead of a breach.

??? example "B2 · How do you implement per-tenant rate limiting and tiered plans?"
    A token bucket per tenant, sized by plan (capacity = allowed burst, refill rate = sustained rate). Enforce it centrally and atomically — a Redis Lua script — so the limit holds across all app instances; return `429` with `Retry-After` when the bucket is empty. Separately meter each tenant's billable usage into a per-period counter for usage-based billing and quota checks.

??? example "B3 · Design the data layer for a SaaS with 50,000 small tenants and 50 enterprise tenants."
    Two tiers. The 50,000 small tenants go **pool + RLS**, sharded across several databases by `tenant_id` (no single DB holds all), with a tenant→shard routing map and a connection pooler. The 50 enterprise tenants get **silo** — a dedicated database each (residency, backup, blast radius, custom SLAs). Both share the app tier, with per-tenant limits. Consider cells to cap blast radius for the pooled tenants.

??? example "B3 · How do you prevent cross-tenant access at every layer?"
    Defense in depth: (1) derive tenant from the authenticated JWT at the edge, never from input; (2) include the tenant in every authorization check (object-level ownership); (3) enforce it at the data layer with RLS (pool) or physical separation (silo); (4) prefix caches, scope search, stamp messages; (5) test isolation explicitly for every resource. No single layer is trusted alone.

??? example "B4 · What is cell-based architecture and when do you need it?"
    You run many independent **cells**, each a complete stack (app + data) serving a subset of tenants. A cell is a blast-radius boundary: a bad deploy, an incident, or a noisy tenant is contained to one cell instead of taking down all tenants. You scale by adding cells and route each tenant to its cell via a thin routing layer. It's how the largest SaaS platforms get pool economics with silo-like fault isolation.


### Part IV · Architect

**[31. Architecture Decision Making](../04-architect/31-architecture-decision-making.md)**

??? example "B1 · What is an Architecture Decision Record, and why write one?"
    An ADR is a short document recording one significant architectural decision: the context and drivers, the decision, the options considered and why they were rejected, the consequences (good and bad), and ideally what would make you revisit it. You write it because architectural decisions are expensive to reverse and outlive the people who made them — without a record, future engineers either blindly preserve a decision whose reasons no longer hold or re-litigate it without knowing what was already evaluated.

??? example "B1 · What are quality attributes and why do they drive architecture?"
    Quality attributes are the non-functional properties — performance, scalability, availability, security, modifiability, operability, cost, time-to-market. They drive architecture more than features do: two systems with identical features can need completely different architectures if one serves 10 req/s at 99.5% and the other 10,000 req/s at 99.99%. They also conflict, so prioritizing them is the core of architectural trade-offs.

??? example "B2 · How do you run a weighted decision matrix without it becoming theatre?"
    Agree criteria and weights before anyone scores; have several people score independently; discuss divergent scores (that's where facts are missing); calculate; then run a sensitivity analysis to see how robust the winner is. Treat the matrix as a tool for making reasoning visible and arguable, not as an oracle — and replace any score you could calculate with the calculation.

??? example "B2 · What is sensitivity analysis and what does it tell you?"
    You vary the inputs — usually the weights, sometimes the scores — and see whether the winner changes. If a small, plausible change flips it, the decision is fragile and you need more information before committing. If only an extreme change flips it, the decision is robust. It also tells you which assumptions actually matter, so you know what to monitor as a revisit trigger.

??? example "B3 · Monolith, modular monolith, or microservices — how would you decide?"
    By drivers, not fashion. Key questions: how many teams need independent deploys? Do components have genuinely different scaling or availability needs? How much operational maturity (observability, CI/CD, on-call) exists? What's the business stage? For most organizations under ~50 engineers without divergent scaling needs, a modular monolith with enforced boundaries wins; extract services where a real driver exists. I'd make this a weighted matrix with sensitivity, and state the revisit trigger.

??? example "B3 · How do you use queueing theory in architecture decisions?"
    To predict latency and capacity before building. Key facts: Little's Law (concurrency = throughput × latency), and M/M/1 response time R = S/(1−ρ) — latency grows non-linearly with utilization and goes vertical near 100%. Practically: sequential calls add latencies, parallel calls take the max, and each service needs headroom (target ~50–70% utilization) or its tail latency explodes.

??? example "B4 · You inherit a system whose major decisions were never documented. What do you do?"
    Reconstruct before redesigning. For each major decision, recover the context (interview people, read history, look at the metrics of the time), list the options that plausibly existed, infer the drivers, and write retroactive ADRs marked as such. Then check each against current drivers — often you'll find the reasons no longer hold. Only then propose changes, as new ADRs with options, sensitivity and revisit triggers.


**[32. Design Documents & Architecture Communication](../04-architect/32-design-documents-and-architecture-communication.md)**

??? example "B1 · What goes into a good design document?"
    A summary; the problem with evidence; goals and non-goals; measurable requirements (NFRs with numbers) and constraints; scale assumptions with derived figures; the proposed design at the right level (context and container diagrams, key flows, data ownership); the key decisions with alternatives considered; trade-offs, risks and failure modes; security; observability; rollout and rollback; cost; and open questions. It leads with what the reader must decide on.

??? example "B1 · What is the C4 model?"
    A way to draw software architecture at four levels of zoom: System Context (your system, its users and external systems), Container (the deployable units and how they talk), Component (the building blocks inside one container), and Code. Each diagram stays at one level, and every element and arrow is labeled with name, type/technology and purpose. Most designs need Levels 1 and 2 plus a few sequence diagrams.

??? example "B2 · How do you run a design review that produces useful feedback?"
    Lint first so reviewers don't waste time on missing sections. Name reviewers and give each a specific ask. Run it asynchronously with a deadline for written comments, and point people at the open decisions. Hold a meeting only for disagreements the comments surfaced. Resolve every substantive comment explicitly, and end with a recorded outcome: approved, approved with changes, another round, or rejected.

??? example "B2 · Why generate diagrams from a model instead of drawing them?"
    Hand-drawn diagrams drift: context, container, slides and wiki get edited separately and start contradicting each other and the system. With one model, every view is generated, so they can't disagree, renames happen once, and the model can be validated and queried — which container has no owner, which element is orphaned, which arrows lack a protocol.

??? example "B3 · How do you keep architecture documentation from rotting?"
    Keep docs next to code and change them in the same PR; generate what can be generated (API references, schema docs, diagrams from the model); detect drift automatically by recovering the actual architecture from code or traces and diffing it against the documented model; make rules executable as fitness functions; and treat design docs as point-in-time, keeping the living truth in the model and ADRs.

??? example "B3 · How do you pitch the same architecture to executives and to engineers?"
    Same facts, different altitude. Executives: business problem, options framed as business trade-offs (time, cost, risk, scalability limits), one context diagram, the ask. Engineers: requirements with numbers, container-level design, key decisions and alternatives, failure modes, contracts, rollout. Layer the document so both can stop reading at the right point.

??? example "B4 · How would you set up architecture documentation for a 200-engineer organization?"
    A small set of formats with clear purposes: one-pagers, design docs (one template), ADRs, RFCs for cross-cutting change. Everything as code in the owning repos. A C4 model per domain as the single source of diagrams, rendered in CI into a docs portal. Automated checks: doc lint, estimation consistency, model validation, drift against code and traces. Async reviews with named asks and recorded outcomes. Living truth in model + ADRs + generated docs; design docs point-in-time.


**[33. Domain-Driven Design](../04-architect/33-domain-driven-design.md)**

??? example "B1 · What is a bounded context?"
    A boundary within which a particular domain model and its language are consistent — inside it, each term means exactly one thing. "Product" in Catalog is a listing with images; in Inventory it's stock of a SKU in a dark store; in Fulfilment it's a physical item with a weight. Each context has its own model and data, and they integrate through explicit relationships.

??? example "B1 · Entity vs value object?"
    An entity has an identity that persists through changes — an Order is the same order from draft to delivered; equality is by ID. A value object is defined only by its attributes, is immutable and has no identity — Money(2850 paise), Pincode("560034"); equality is by value. Value objects are ideal for validating values once and carrying behavior.

??? example "B2 · What is an aggregate and what are the rules for designing one?"
    A cluster of entities and value objects changed as one unit, with a root that is the only entry point, whose job is to enforce invariants. Rules: protect true invariants inside the boundary through intention-revealing methods; keep aggregates small; reference other aggregates by ID; modify one aggregate per transaction and use eventual consistency (events, sagas) between aggregates.

??? example "B2 · What is an anti-corruption layer and when do you need one?"
    A translation layer between your bounded context and an external or legacy model, so the external concepts don't leak into your domain. It converts their data into your ubiquitous language, validates it, and rejects or quarantines what it can't translate. You need one whenever you integrate with a legacy system, a third party whose model you don't control, or another context whose model differs significantly.

??? example "B3 · How do you find bounded contexts in an existing system?"
    Combine signals: run event storming with domain experts to find where language changes and where flows hand off; identify invariants that must be consistent together; look at change coupling in git history; analyze which entities use cases touch together; and consider team ownership. Draft contexts with experts, then use data to challenge the draft.

??? example "B3 · How do you size an aggregate?"
    As small as the true invariants allow. Ask which data must be consistent immediately, in one transaction, for a rule to hold — that's the aggregate. Everything else is referenced by ID and made eventually consistent. Then check concurrency: the aggregate is the unit of locking, so its write contention sets your throughput ceiling.

??? example "B4 · How do bounded contexts map to microservices and teams?"
    A bounded context is the natural unit of ownership: one team per context (a team can own several small ones). Services should not straddle contexts. Start with contexts as modules in a modular monolith and extract a context into its own service when there's a real driver — independent scaling, release cadence, availability or team boundary. A large context may be several services, but they share its language and team.


**[34. Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)**

??? example "B1 · What is hexagonal (ports and adapters) architecture?"
    A way of structuring an application so the domain is at the center and depends on nothing; it defines ports — interfaces for what it needs (store orders, charge payments) and what it offers (use cases). Adapters implement those ports for specific technologies (JPA, Razorpay HTTP, REST controllers, Kafka listeners). Dependencies point inward, so the business logic can be tested without infrastructure and technologies can be swapped by writing new adapters.

??? example "B1 · What is a modular monolith?"
    A single deployable application divided internally into modules with enforced boundaries — typically one per bounded context — that communicate only through published module APIs or events, never through each other's internals or tables. It gives most of the modularity benefits of microservices with in-process calls, local transactions and one pipeline.

??? example "B2 · Layered vs hexagonal architecture — what's the actual difference?"
    The direction of the dependency at the bottom. In layered architecture the domain/service layer depends on the persistence layer, so business logic is shaped around the database. In hexagonal architecture the domain defines the persistence port and infrastructure depends on the domain. That inversion makes the domain testable without a database and technology-independent.

??? example "B2 · What does converting a module into a microservice actually cost?"
    Latency (a microsecond call becomes a network call; serial calls add up), tail latency (each hop adds its own p99), availability (required synchronous dependencies multiply unavailability), operations (pipelines, observability, on-call, versioning, contract tests), and the loss of local transactions. You pay it to get independent deployment, independent scaling, team autonomy and fault isolation.

??? example "B3 · How do you decide cell size and the partition key?"
    Choose a key that keeps nearly all traffic within one cell — tenant, city group or customer ID — so cross-cell calls are rare. Size cells so losing one is tolerable (e.g., ≤ 15% of customers), so the largest tenant fits in one cell with headroom, and so the number of cells is operationally manageable. Keep cells identical in shape.

??? example "B3 · Why didn't cells protect against latent bugs in L3, and how do you fix that?"
    Because a latent bug only triggers hours after deploy, and a 90-minute rollout has already put it into every cell by then — so the worst incident is still 100%. The fixes: space deploy waves by longer than typical latent-bug trigger times (at least for risky changes), use per-cell feature flags so behavior can be switched off where it misbehaves, and bake in the canary cell before continuing.

??? example "B4 · Design the architecture-style mix for a large grocery platform."
    Per level: hexagonal inside every deployable; bounded contexts as modules in a modular monolith where they change together (Ordering, Catalog), extracted into services where there's a driver (Inventory, Fulfilment for flash-sale scaling); events between contexts; BFF per client; serverless for bursty side jobs; and, at large scale, cells by city group with a replicated router and a separate control plane, deployed in waves.


**[35. Cost Engineering & FinOps](../04-architect/35-cost-engineering-and-finops.md)**

??? example "B1 · What is FinOps?"
    The practice of managing cloud cost as a shared responsibility between engineering, finance and product: make spend visible and owned (inform), reduce waste and buy capacity smartly (optimise), and keep it that way with budgets, anomaly alerts and cost in design reviews (operate). The goal is maximum business value per rupee, not the lowest bill.

??? example "B1 · What is unit economics and why does it matter?"
    Expressing cost per unit of business value — per order, per active customer, per tenant — instead of as a total. It shows whether growth is efficient: a bill that doubles with cost per order flat is healthy; cost per order rising means efficiency is getting worse. It also makes costs comparable to revenue and margin per unit.

??? example "B2 · How do you allocate shared costs like Kafka, observability and networking?"
    By usage drivers that reflect who causes the cost: Kafka by bytes produced (or partitions), observability by log/metric/trace volume, NAT and egress by gigabytes out, cluster overhead by resource requests or requests served, non-prod proportional to production footprint. An even split is simple but shifts cost to low-usage teams and removes incentives.

??? example "B2 · How do you rightsize a service?"
    Compare what's provisioned with what's used: CPU/memory requests vs actual usage, replica counts vs demand over time, instance shape vs workload profile. Set a target utilisation that respects latency (often 50–70% for latency-sensitive services), autoscale to it, pre-scale for known events, and fix shape mismatches (memory-heavy on compute-optimised). Re-check after every major release.

??? example "B3 · How much capacity should you commit to for a year?"
    Commit to the floor, not the peak: a marginal committed unit pays off only if it would be busy in at least (commit price ÷ on-demand price) of the hours — 60% at a 40% discount. Against a forecast, sweep the commitment level and pick the minimum-cost point; under uncertainty, check expected cost and regret across plausible futures, and ratchet up quarterly as the floor rises.

??? example "B3 · A service is the most expensive line on the bill. Do you cut it?"
    Not without its value. Measure what it contributes — an A/B test, attributed revenue, cost avoided, or its role in reliability — and compare with its fully allocated cost. If value clearly exceeds cost, optimise it (cheaper model, caching, rightsizing) rather than cut it. If it doesn't, challenge it.

??? example "B4 · Design a FinOps operating model for a 200-engineer organisation."
    Engineers own their services' cost; a small central FinOps function owns tooling, commitments and policy. Every resource tagged at provisioning (policy as code); shared costs allocated by drivers; cost per order and per service on every team dashboard; budgets and daily anomaly alerts per team; commitments purchased centrally against the confident floor and reviewed quarterly; retention and tiering defaults per data class; cost sections in design docs; and a monthly review of unit economics with product and finance.


**[36. Compliance, Governance & Data Residency](../04-architect/36-compliance-governance-and-data-residency.md)**

??? example "B1 · How do regulations become architecture requirements?"
    Laws state outcomes — protect personal data, use it only for its purpose, erase it when no longer needed, keep tax records for a period, store payment data in-country, report breaches quickly. The architect translates each into a testable engineering requirement and a mechanism: crypto-shredding for erasure, policy-as-code for residency, a tamper-evident log for accountability, scanners for data leakage. Then automate the evidence.

??? example "B1 · What is data residency / localisation?"
    A requirement that certain data be stored (and sometimes processed) only within a country or region. For an Indian payments-related platform, RBI's 2018 direction requires payment system data to be stored only in India; transient processing abroad is allowed if the data is deleted there quickly. Architecturally it constrains where replicas, backups, DR regions, analytics and vendors can be.

??? example "B2 · Design the right-to-erasure feature for a platform with CQRS, search, Kafka, backups and a warehouse."
    Encrypt personal fields at write time with a per-customer key from a KMS, in every store and message, and keep only pseudonymous IDs in search and the warehouse. On a verified request: check legal holds and retention; destroy the customer key (all copies become unreadable, including backups); tidy up live stores (delete rows, index documents, cache entries, compact topics); keep legally required records under a separate retention key with minimal fields; write an erasure log without personal data; confirm to the customer.

??? example "B2 · How do you find personal data where it shouldn't be?"
    Scan the sinks — logs, exports, object storage, warehouses, vendor feeds — with detectors for the identifiers you care about (phones, emails, Aadhaar-format numbers, PAN, card numbers), validate candidates with each identifier's rules (Verhoeff, Luhn + issuer prefix, PAN structure) and use structure (field names/keys) to exclude known non-PII fields. Redact at the pipeline, alert the owning team, and fix the source.

??? example "B3 · Design DR for a platform whose payment data must stay in India."
    Primary in one Indian region, DR in another Indian region, with replication, backups and snapshots staying in-country; anything leaving the country is transient processing (deleted promptly), anonymised/aggregated, or stripped of payment data. Encode this as policy-as-code over the architecture's data flows so a foreign replica fails CI.

??? example "B3 · How do you make search and analytics work with encrypted personal data?"
    Don't index plaintext. Use pseudonymous tokens (keyed HMAC of identifiers) as join keys and for equality search ("blind indexes"); keep analytics on pseudonymised or aggregated data; decrypt only in the service that needs the value, for the user who's allowed to see it. For free-text search over personal data (support tools), use a separate, access-controlled, audited index whose entries are encrypted under the subject key so crypto-shredding covers it.

??? example "B4 · Design a compliance architecture for a new Indian quick-commerce platform."
    Data classes and a catalog from day one; field-level encryption with per-customer and legal-retention keys (crypto-shredding for erasure); payments via hosted pages and tokens (no card data); structured logging allow-lists plus PII scanning of sinks; residency and purpose rules as code over a data-flow manifest; in-country DR; retention schedule enforced by lifecycle; hash-chained audit log anchored externally; consent recorded as data; breach runbook; evidence collected automatically.


**[37. Leading Architecture at Scale](../04-architect/37-leading-architecture-at-scale.md)**

??? example "B1 · What is Conway's law?"
    Organisations design systems that mirror their communication structures. If teams are organised by technical layer, the system grows layer-shaped seams and every feature crosses teams; if teams own business capabilities end to end, the system tends toward capability-shaped services. The "inverse Conway manoeuvre" uses this deliberately: design the teams you need for the architecture you want.

??? example "B1 · What are the four team types in Team Topologies?"
    Stream-aligned teams own a slice of the value stream end to end (the default); platform teams provide internal self-service capabilities that reduce stream teams' cognitive load; enabling teams coach other teams to adopt new capabilities and then step away; complicated-subsystem teams own parts needing deep specialist knowledge.

??? example "B2 · How do you decide team boundaries?"
    Start from how change flows: which services change together (feature and git data), which bounded contexts exist, and which capabilities are shared. Group services so most features need one team, within cognitive-load limits (a few services per team, balanced change and on-call load). Move shared capabilities to a platform team as self-service. Validate with domain language and skills, then keep teams stable.

??? example "B2 · What is team cognitive load and why does it matter for architecture?"
    The total mental effort a team needs to understand, change and operate what it owns — domains, services, technologies, on-call. When it exceeds capacity, changes slow, quality drops and on-call becomes unsustainable. Architecture should be split so each team's share fits; platforms exist to remove undifferentiated load (infrastructure, observability, messaging).

??? example "B3 · How would you lead a Spring Boot 2 → 3 migration across 100 services?"
    Make the case (support dates, CVEs), map dependencies, fund the enablers first (shared libraries migrated, OpenRewrite recipes, a reference migration by one team), then let each team own its services with an enabling team available. Track a fitness function (% of services on Boot 3) on a dashboard, announce an end date for the old path, and plan the long tail.

??? example "B3 · Your target architecture keeps drifting. How do you diagnose and fix it?"
    Check the socio-technical causes first: do team boundaries match the architecture's boundaries (Conway)? Are standards defaults or documents? Are rules enforced by guardrails? Measure coordination (multi-team features), drift (fitness functions, dependency drift), and adoption. Then fix structurally: realign teams to change flow, strengthen the paved road, add guardrails.

??? example "B4 · Design the engineering organisation for a 150-engineer grocery platform."
    15–18 stream-aligned teams of 6–9 grouped around value streams (checkout, orders and payments, catalog and discovery, store operations, inventory, delivery, customer), boundaries validated with change data and cognitive-load limits; a platform group (developer platform, data platform, messaging/identity/notification as services); one or two enabling teams; complicated-subsystem teams for routing and search ranking. Govern with principles, paved roads, guardrails and an advice process; measure DORA, coordination and adoption.


### Part V · Case Studies

**[38. Case Study: URL Shortener & SMS Short Links](../05-case-studies/38-url-shortener-and-short-links.md)**

??? example "B1 · Design a URL shortener — what are the core components?"
    A write API that validates the long URL, mints a unique short code and stores code → URL. A redirect endpoint that looks up the code, through caches, and returns a 302 to the long URL. A database as the source of truth, a cache for the hot working set, and an asynchronous pipeline for click analytics. Plus expiry, abuse controls and monitoring.

??? example "B1 · 301 or 302 — which would you use and why?"
    302 (or 307) by default. A 301 says the redirect is permanent, so browsers and proxies may cache it — repeat clicks never reach you, analytics undercount, and you can't revoke or change the destination for anyone who has the cached redirect. A 302 means every click reaches the service, so the read path has to be cheap and well-cached.

??? example "B2 · Compare ways to generate short codes."
    Hash-and-truncate needs no coordination but collides (birthday bound) and still needs check-and-retry. Random codes are unguessable but need a uniqueness check per insert. A counter is unique and short with no lookup but is enumerable. A counter through a keyed permutation keeps the counter's uniqueness and no-lookup property while hiding the order.

??? example "B2 · How would you cache redirects?"
    Three tiers: a short-TTL in-process near-cache on each redirect node, a distributed cache (Redis) for the working set, and the database for cold misses — with per-key miss coalescing and brief negative caching of unknown codes. Popularity is skewed, so a small cache captures most traffic; the tail always reaches the database.

??? example "B3 · A campaign sends 20M SMS with one link. What breaks and how do you design for it?"
    All clicks hit one key: one Redis shard, one DB row if you count synchronously, and possibly a shared connection pool. Sharding doesn't help because it spreads keys, not load. Defend with a near-cache on every node (at most one lookup per node per TTL), pre-warming, edge caching for campaign codes, async click counting, and a separate deployment/pool for redirects so transactional links are unaffected.

??? example "B3 · How do you build click analytics without slowing redirects?"
    The redirect only emits an event (buffered, async) to Kafka. A stream processor aggregates per link and time bucket into an analytics store, and raw events go to the lake. Unique visitors use HyperLogLog sketches, which merge across nodes and windows. Bots and link-preview crawlers are flagged and counted separately.

??? example "B4 · Design the short-link service for a national-scale grocery platform, end to end."
    Internal-only creation with allow-listed destinations and idempotent create; 7-char codes from counter blocks through a keyed permutation; 302 redirects; a separate redirect deployment with near-cache, Redis and replicas; PostgreSQL partitioned by expiry; click events to Kafka, stream-aggregated with HLL uniques; hot-key detection, pre-warming and edge caching for campaigns; abuse kill switch; SLOs per region.


**[39. Case Study: Distributed Rate Limiter](../05-case-studies/39-distributed-rate-limiter.md)**

??? example "B2 · Design a distributed rate limiter — where do you start?"
    With what each limit protects and who it limits: fairness per user, contracts per API key, fraud controls per phone and destination, capacity per service, vendor TPS. That decides the key, the accuracy needed and the failure mode. Then the algorithm (GCRA/token bucket by default), where state lives (Redis, local, leased), multi-node and multi-region enforcement, response headers, configuration with shadow mode, and observability.

??? example "B2 · How do you enforce one global limit across many gateway nodes?"
    Options: split the limit per node (free, wrong under skew and autoscaling); check a central store per request (exact, a round-trip each time); or lease tokens from the central store in small batches sized to each node's rate, giving unused tokens back and caching denials (near-exact with far fewer calls). Or route each key to one limiter node by consistent hashing.

??? example "B3 · Design OTP rate limiting that stops SMS pumping without locking out customers."
    Layered keys: per phone (3/10 min, 5/hour, exact), per destination range and country budgets learned from baseline, a global international budget, attested-device checks with a challenge on failure, loose per-ASN limits for known-abusive networks. Monitor sends vs verified logins per range. Treat it as a fraud control with a cost on both sides; shadow new rules.

??? example "B3 · The limiter's Redis is down. What happens?"
    Whatever each rule declares. Fairness and partner limits fail open (other layers protect capacity). Fraud controls fall back to local, conservative shares of their budgets. Fail closed only for narrowly scoped rules where allowing is worse than an outage. Limiter calls have millisecond timeouts and a breaker; fallback mode alerts.

??? example "B4 · Design the rate-limiting platform for a national grocery app."
    Edge volumetric limits; gateway limits per user and API key with GCRA state in regional Redis and adaptive leasing; service-level exact checks for fraud controls (OTP per phone, range budgets, attestation); egress shaping for vendors; rules as versioned data with shadow mode; per-rule failure modes with local fallback; cross-region share rebalancing; observability including fraud metrics (sends vs verifications).


**[40. Case Study: Notification System](../05-case-studies/40-notification-system.md)**

??? example "B1 · Design a notification system — what are the main components?"
    An intake API/topic where services request notifications with a template id, variables, recipient, class and a dedupe key; a policy engine for preferences, consent, timing and channel choice; a renderer with versioned, localised templates; per-class, per-channel queues; channel workers with vendor adapters (FCM/APNs, SMS vendors, WhatsApp, email); a delivery tracker consuming receipts; and a message log and in-app inbox.

??? example "B1 · Why separate queues for OTPs and marketing?"
    They differ by orders of magnitude in urgency and volume. In a shared FIFO queue, OTPs wait behind millions of campaign messages and expire. Separate lanes with priority (and ideally a separate promotional route) keep OTPs at seconds while campaigns drain.

??? example "B2 · How do you avoid sending duplicate notifications?"
    Producers supply a dedupe key per logical notification; intake stores it with a unique constraint so redelivered events are dropped. Workers claim jobs with leases and retry on failure. For unknown outcomes (timeouts, crashes after send), pass an idempotency key the vendor dedupes on. Where impossible, pick duplicate-vs-loss per class and word messages idempotently.

??? example "B3 · An SMS vendor is returning 200 but messages aren't arriving. How does your system detect and react?"
    Track DLR-based delivery rate per vendor (and per operator/circle) over a sliding window, judging each send after its deadline. When it drops below a threshold, route to the next-best healthy vendor, keep a small probe share on the sick one to detect recovery, alert, and resend recent undelivered OTPs through another vendor.

??? example "B3 · How do you run a 20M-recipient campaign without hurting transactional traffic?"
    Separate promotional lane, workers and vendor route; resolve the audience in batch with opt-outs and caps; release in throttled chunks within the legal window; coordinate with capacity for the surge the campaign creates; brownout hooks to pause; kill switch; measure outcomes.

??? example "B4 · Design the notification platform for a national grocery app end to end."
    Message classes with lanes and routes; intake with dedupe keys; a policy engine for consent, windows, caps, collapse and escalation; a template registry with DLT ids; channel workers with leases and vendor idempotency; multi-vendor routing on DLR health with OTP resends; a delivery tracker, message log and inbox; campaigns with throttled release; observability on latency per class, DLR rates and spend.


**[41. Case Study: Chat — Customer–Rider Messaging](../05-case-studies/41-chat-customer-rider-messaging.md)**

??? example "B1 · Design a chat between a customer and a delivery rider — the main components?"
    Mobile apps with WebSocket connections to stateless chat gateways; a chat service that assigns each conversation's sequence numbers, dedupes by client message id and stores messages; pub/sub (or a registry) to route messages to the gateway holding the other participant; push notifications when an app is backgrounded; presence in a TTL cache; object storage for photos; masked calling; retention.

??? example "B1 · How do you keep messages in the right order?"
    The server assigns a monotonically increasing sequence number per conversation when it stores each message, and every client displays by that sequence. Client clocks are unreliable. Each sender's own order is kept with a per-sender sequence the server enforces.

??? example "B2 · How do messages reach a user connected to a different server?"
    After storing a message, the chat service publishes it to a pub/sub channel for the conversation (or looks up the recipient's node in a registry and forwards it). The node holding the recipient's socket delivers it. If the recipient isn't connected, a push notification goes out. On reconnect the phone syncs from its last sequence.

??? example "B3 · Walk through what makes delivery exactly-once from the user's point of view."
    Client message ids make sends idempotent at the server; the server assigns one sequence per stored message; delivery to phones is at-least-once (push, pub/sub, replay) and phones dedupe by sequence; gaps in the sequence trigger fetches, so nothing is silently lost. Result: each message appears once, in order, on every device.

??? example "B3 · How do you stop riders and customers exchanging personal phone numbers?"
    Make it unnecessary — masked calling for the order window — and mask contact details in chat: normalise text (separators, digits in any script, number words in the languages used), detect numbers/UPI/emails, apply context rules so OTPs, order ids and AWBs aren't masked, look across consecutive messages, and log for trust & safety. Close chats after delivery.

??? example "B4 · Design WhatsApp-scale messaging — what changes from order chat?"
    Millions of concurrent connections per region (Erlang/Netty gateways, session registry); per-device offline queues deleted after acknowledgement; group chats with fan-out strategies by size; end-to-end encryption, so the server routes ciphertext; media via CDN; multi-region routing; and far larger storage with a wide-column store. The core — client ids, sequences, sync by watermark — stays.


**[42. Case Study: Search Autocomplete (Typeahead)](../05-case-studies/42-search-autocomplete.md)**

??? example "B1 · Design search autocomplete — the core idea?"
    Build a trie of candidate queries from search logs, with the top-k most popular completions precomputed at every node. On each keystroke, walk to the prefix node and return its list. Build offline, serve from memory, cache short prefixes at the edge, and update daily plus hourly for trending.

??? example "B2 · How do you keep suggestions fresh without losing stability?"
    Score with exponential time decay (a short half-life, e.g., 2 days), add a trending boost when recent volume exceeds the trailing average, rebuild nightly with an hourly trending overlay, and use a merchandising calendar for known events — pinned during, removed after.

??? example "B2 · How do you stop bad suggestions?"
    A filtering stage in the pipeline: minimum distinct users, blocklists, PII detection on normalised text, and removing queries that return zero results. Plus build-time validation, a daily review of new suggestions, and serving-time overrides to suppress anything immediately.

??? example "B2 · What should the client do?"
    Tag requests with a sequence number and render only newer responses; cancel superseded requests; cache recent prefix responses; reuse a complete list (fewer than k results) for longer prefixes locally; debounce lightly on slow networks; show cached suggestions instantly on backspace.

??? example "B3 · How do you personalise suggestions?"
    Fetch global candidates for the prefix (top 20–50), then re-rank per request with the user's history (recent searches and purchases from a KV store) and constraints (store availability, language). Blend scores, e.g., global share + w × personal share, tuned by A/B tests.

??? example "B4 · Design autocomplete for a multi-city, multilingual grocery app end to end."
    Logs to Kafka; a nightly pipeline normalises, filters, decays and scores per city and language, with hourly trending and an event calendar; a builder precomputes top-30 per node and validates; versioned artefacts are hot-swapped into in-memory services; edge caches for short prefixes; clients with sequence numbers and caches; request-time re-ranking by store availability and user history; overrides; evaluation on keystrokes saved and conversion.


**[43. Case Study: Home Feed & Recommendations](../05-case-studies/43-home-feed-and-recommendations.md)**

??? example "B2 · Design a recommendation feed — what are the stages?"
    Retrieve a few hundred candidates from several cheap sources (history, co-purchase, popular, embeddings); filter by availability and eligibility; fetch features; rank with a learned model; re-rank for diversity, business rules and exploration; render with impression logging. Offline pipelines build candidates, features and models; streaming keeps fast features fresh.

??? example "B2 · What candidate sources would you use for grocery?"
    Buy-again (history with due-ness), item-to-item co-purchase from the cart and recent orders, popular and seasonal by city/store, embedding similarity, and new arrivals — blended with explicit quotas.

??? example "B2 · How do you keep feature fetch within the latency budget?"
    Batch every read a request needs into as few calls as possible (MGET/HMGET, pipelining, a feature service that fans in), co-locate the store, cache slow-changing item features in process, precompute where possible, and never do per-candidate calls.

??? example "B3 · Your model is trained on its own feed's clicks. What goes wrong and how do you fix it?"
    Position and selection bias: items shown higher are clicked more because they're higher, and items never shown have no labels, so the model reinforces its predecessor. Fix by logging position and propensity, modelling position in training (set to a fixed value at serving) or inverse-propensity weighting, and exploring to get labels for unshown items.

??? example "B3 · How do new products get a fair chance?"
    Explicit exploration: Thompson sampling or UCB slots that explore in proportion to uncertainty, content features to borrow priors from similar items, a new-arrivals carousel for a launch window, and guardrails so exploration doesn't show clearly poor items for long.

??? example "B4 · Design the home feed for a 300-store grocery platform end to end."
    Event logging with positions and propensities; a lake with daily training and candidate generation; streaming features and stock; an online feature store; a home feed service doing retrieval with quotas, availability filtering, batched feature fetch, ranking, re-ranking with diversity, rules and exploration; fallback feeds; experiments and guardrails.


**[44. Case Study: Distributed Cache](../05-case-studies/44-distributed-cache.md)**

??? example "B2 · Design a distributed cache — the main components?"
    Cache servers holding a hash table in memory with TTLs and an eviction policy; a partitioning scheme (hash slots or consistent hashing) so keys spread across nodes; replicas per shard with automatic failover; cluster-aware clients or a proxy for routing; and monitoring of hit ratio, latency, memory and replication lag.

??? example "B2 · LRU or LFU?"
    LRU adapts quickly to changing popularity but is fooled by scans and one-hit wonders. LFU keeps frequently used keys but lags when popularity shifts unless counts decay. Admission-based policies like W-TinyLFU combine a recency window with frequency-based admission and usually win, especially for small caches.

??? example "B2 · How do you partition a cache, and what happens when you add a node?"
    Avoid modulo hashing, which remaps most keys. Use consistent hashing with virtual nodes (≈1/(N+1) of keys move) or hash slots (Redis Cluster: 16,384 slots mapped to nodes; you move only chosen slots, gradually). Clients must route by the current map and follow redirects during moves.

??? example "B3 · What can you lose when a cache primary fails, and how do you limit it?"
    With asynchronous replication, any write acknowledged but not yet replicated to the promoted replica — plus writes accepted by a partitioned old primary. Limit it with WAIT (clients know which writes are replicated), `min-replicas-to-write` (primary stops accepting writes without replicas), AOF for restarts, and — most importantly — keeping the truth in a database so the cache can be rebuilt.

??? example "B4 · Design the caching platform for a national grocery app end to end."
    Four clusters by loss tolerance (catalog LFU, sessions noeviction + durable source, rate limits volatile-ttl, features LRU); Redis Cluster with replicas and automatic failover; cluster-aware clients with pipelining; near-caches for hot keys; carts and sessions backed by PostgreSQL; ACLs, TTLs and budgets; dashboards for hit ratio, latency, memory and lag; resharding and failover runbooks tested in game days.


**[45. Case Study: Payment System](../05-case-studies/45-payment-system.md)**

??? example "B2 · Design a payment system for an e-commerce app — the main components?"
    A payment service with a state machine per payment (created → captured → refunded), integrating one or more gateways via hosted checkout; a webhook receiver that verifies and dedupes events; a status poller for unknown outcomes; a double-entry ledger; an outbox to notify checkout and fulfilment; daily reconciliation against settlement files; and security controls (tokenisation, signatures, audit).

??? example "B2 · How do you prevent double charges?"
    Idempotency at every hop: an idempotency key per checkout attempt on our API; our payment id as the gateway order's receipt so the gateway dedupes order creation; one gateway order per checkout, reused on retries; payment attempts only through the gateway's checkout for that order; and refunds for any duplicate captures found by reconciliation.

??? example "B2 · How do you handle webhooks correctly?"
    Verify the signature, store the event id with a unique constraint and process in the same transaction as the state change, apply only allowed transitions (guarded UPDATE), treat events as at-least-once and unordered, respond 200 quickly (queue heavy work), and never rely on webhooks alone — poll non-terminal payments.

??? example "B2 · A payment's outcome is unknown after a timeout. What do you do?"
    Don't guess and don't blindly retry. Record it as pending, query the gateway's status by our reference (with backoff), and apply the result through the same guarded transition. If still unknown after the window, mark it NEEDS_ATTENTION and let reconciliation resolve it; show the customer "payment processing".

??? example "B3 · How do you keep a ledger correct under concurrency?"
    Append-only balanced entries; a balance row per account updated in the same transaction under a row lock (SELECT … FOR UPDATE) with checks (non-negative); serializable isolation only where contention is low; hot internal accounts split into sub-accounts or aggregated asynchronously; nightly invariant checks.

??? example "B4 · Design the payments platform for a national grocery marketplace end to end."
    Payment service with guarded state machines for payments and refunds; multi-gateway router with health per (gateway, method, bank); hosted checkout and tokenisation; verified, deduped webhooks plus status polling; double-entry ledger with locked projections; outbox to checkout and fulfilment; wallet as ledger accounts; daily three-way reconciliation with an exception queue; vendor payouts; audit, approvals and fraud controls; observability by segment.


**[46. Case Study: Grocery Checkout & Inventory Reservation](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md)**

??? example "B2 · How do you prevent overselling stock?"
    Make the check and the decrement one atomic operation — `UPDATE stock SET qty = qty − 1 WHERE sku = ? AND qty > 0` — and treat zero rows updated as "not available", with a `CHECK (qty >= 0)` as a safety net. Record the reservation in the same transaction. Never read, check in code, then write.

??? example "B2 · What is a stock reservation and why does it expire?"
    A lease: stock set aside for a checkout until payment completes or the lease expires. It prevents selling the same unit twice while the customer pays, and expiry returns stock from abandoned checkouts. The TTL is a business choice between stranded stock and slow payers.

??? example "B2 · How do you handle a single SKU with thousands of reservations per second?"
    Split its stock into buckets (N rows) so locks spread; cache a sold-out flag; put a Redis decrement-if-positive gate in front to reject losers without touching the database; or queue reservations per SKU to a single consumer. Keep the reservation transaction tiny and isolate the SKU's traffic.

??? example "B3 · Design admission control for a flash sale."
    A waiting room in front of checkout: customers get signed tickets in arrival order (with a randomised pre-queue for those who arrive before start), are admitted at a rate the backend sustains (adjusted from latency), poll with server-suggested intervals, and check out idempotently when admitted. Add per-customer limits and bot protection.

??? example "B4 · Design checkout and inventory reservation for a 300-store quick-commerce platform end to end."
    Inventory per dark store with atomic conditional updates; buckets and a Redis gate for hot SKUs; reservation leases with short TTLs extended on payment start; a checkout saga with idempotency, outbox and explicit late-capture handling; per-SKU buffers and picker feedback; waiting rooms and bulkheads for flash sales; observability on contention, leases, shortfalls and fairness.


**[47. Case Study: Delivery Dispatch & Live Tracking](../05-case-studies/47-delivery-dispatch-and-live-tracking.md)**

??? example "B2 · Design the delivery tracking and dispatch system — main components?"
    Rider apps stream GPS to a gateway into Kafka; consumers maintain a live geo index (Redis GEO), feed tracking (SSE to customers) and history; a serviceability index answers which store serves an address; dispatch assigns orders to riders using the live index and an ETA service; the ETA service combines learned speeds, door times and live progress.

??? example "B2 · How do you find the nearest available riders quickly?"
    A spatial index: Redis GEO (geohash in a sorted set) with GEOSEARCH by radius, or an in-memory grid/H3 index with expanding rings; filter by availability; then rank the few candidates by road ETA. Keep positions fresh and prune stale riders.

??? example "B2 · How do you ingest GPS pings reliably?"
    Gateway → Kafka keyed by rider id; consumers batch updates to the live index with an atomic, timestamp-guarded write (Lua) so late pings don't move riders backwards; thin updates by movement; write raw pings to history; handle GPS noise (snapping, jump filtering).

??? example "B3 · Design the dispatch algorithm."
    Every few seconds per store cluster, solve an assignment problem between orders (packed and soon to be packed) and riders (at store and returning soon), with costs from predicted delivery times, promise-breach penalties and batch detours; allow batches of 2–3 compatible orders; send offers with timeouts; re-plan continuously.

??? example "B4 · Design delivery for a 300-store quick-commerce platform end to end."
    Polygon serviceability compiled to cell indexes; telemetry via Kafka to a timestamp-safe, batched, thinned Redis live index plus history; dispatch per store cluster with predicted availability, batching and matching; ETA service with learned speeds, door times and live pace; SSE tracking with privacy; models trained daily from history; SLOs on freshness, promise-hit rate and ETA error.


**[48. Case Study: Delivery Slot Booking & Capacity](../05-case-studies/48-delivery-slot-booking-and-capacity.md)**

??? example "B2 · Design a delivery slot booking system — the main components?"
    A slot service that owns slot templates and capacity; a booking path that holds capacity atomically during checkout and confirms on payment; expiry of abandoned holds; a read model and cache for fast listings; events to dispatch and stores; capacity fed by rosters and cost estimates per order.

??? example "B2 · How do you prevent overbooking?"
    Make check-and-take atomic: `UPDATE slot SET used = used + m WHERE id = ? AND used + m <= cap` (row lock serialises, a failed condition means full), or optimistic versioned appends where a unique key rejects concurrent writers. Never read, check in code, and write back.

??? example "B2 · How do holds work, and how long should they last?"
    A hold reserves the order's minutes at checkout; payment confirms it; a TTL expires abandoned holds. Keep the TTL short (a few minutes) and extend it when payment actually starts, so abandoned carts don't block slots but slow payers aren't punished.

??? example "B3 · Conditional update, SERIALIZABLE, or optimistic versioning — which would you choose?"
    A conditional update for a single-row capacity check (simplest, no retries, best p99 in L2). Optimistic versioning when the aggregate is event-sourced or the invariant spans more than one column. SERIALIZABLE when the invariant spans multiple rows and rewriting is hard, accepting aborts.

??? example "B4 · Design slot-service for 1,500 zones end to end."
    Capacity in minutes from rosters and picking; event-sourced slots with version-guarded appends and async snapshots; projections to a Redis listing cache; holds with lease TTLs; outbox to Kafka for dispatch, stores and notifications; ETA-based costs; recommendations and fees for demand; audits and rebuild tooling.


**[49. Case Study: Pricing, Promotions & Coupons Engine](../05-case-studies/49-pricing-promotions-and-coupons.md)**

??? example "B2 · How should money be represented in a pricing system?"
    As integers in the smallest unit (paise) in a `long`, or as `BigDecimal` with an explicit scale and rounding mode, never `double`. Rounding rules (mode, level, order of discount and tax) are explicit and shared by every service.

??? example "B2 · Design a coupon system — the main parts?"
    Coupon rules as data (conditions, benefit, limits, schedule, targeting); codes; validation and quoting in the pricing engine; atomic redemption against per-customer limits and budgets at checkout; release on cancellation; abuse controls; reporting with holdouts.

??? example "B2 · How does a promotion engine find applicable offers fast?"
    Compile rules into inverted indexes (SKU → offers, category → offers, brand → offers) plus a short list of cart/payment offers; for a cart, look up each line's candidates, filter by schedule and targeting, then combine. Keep indexes in memory and hot-swap them on rule changes.

??? example "B3 · How do you stop a campaign budget from running out in hours?"
    Pace it: a controller adjusts the probability of showing the offer every few minutes so spend follows the expected traffic curve (with known events included), while the atomic budget counter enforces the hard limit.

??? example "B4 · Design pricing and promotions for a large grocery platform."
    Price books with effective dates and MRP; promotion rules as versioned data with groups, floors and budgets; a rules compiler feeding in-memory indexes in stateless quote nodes; deterministic combination; checkout reprice and immutable snapshots with per-line allocations; pacing and uplift targeting for campaigns; markdown optimiser; audit and governance.


**[50. Case Study: Distributed Job Scheduler](../05-case-studies/50-distributed-job-scheduler.md)**

??? example "B2 · How do you make a scheduled job run on only one of many instances?"
    Acquire a lease (a lock with a TTL) before running: a DB row lock or lease, etcd/ZooKeeper, or Redis `SET NX PX` with an owner token and compare-and-delete release. Because leases can expire under paused processes, also fence the effect (a token the resource checks) or make the job idempotent.

??? example "B2 · How would you store millions of delayed jobs?"
    A durable table (or log) keyed by due time: `(id, due_at, status, lease_until, attempts, payload, tenant)`, indexed or partitioned by due time; loaders lease upcoming jobs in batches into in-memory timers; workers run them and write results back in batches; leases expire if nodes die.

??? example "B2 · What is a fencing token and why is it needed?"
    A monotonically increasing number issued with each lease; the protected resource records the highest token it has seen and rejects operations with older ones. Needed because a lease holder can pause past expiry and act on stale authority.

??? example "B3 · Cron chaining vs a DAG engine for batch pipelines?"
    Cron chaining encodes duration guesses; slow nights and failures make downstream tasks run on missing inputs. A DAG engine starts tasks when inputs are ready, retries automatically, records runs, supports backfills, and can prioritise the critical path.

??? example "B4 · Design a distributed job scheduler end to end."
    API and definitions; delayed-job store partitioned by due time; sharded lookahead loaders with leases and timing wheels; fair per-tenant dispatch with retry lanes; idempotent/fenced handlers with heartbeats; DAG engine for workflows; misfire policies; run history; observability and quotas.


**[51. Case Study: Real-time Analytics & Top-K (Trending)](../05-case-studies/51-real-time-analytics-and-top-k.md)**

??? example "B2 · Design 'top 10 most viewed products in the last hour'."
    Stream view events through Kafka keyed by product (or city:product); a stream processor keeps per-minute counts per key and maintains a top-K per window by merging the last 60 buckets; results are written to a Redis sorted set that the app reads. For huge key spaces, use Space-Saving or Count-Min + heap to bound memory.

??? example "B2 · What is a Count-Min sketch?"
    A d × w array of counters with d hash functions; each event increments one counter per row; the estimate is the minimum across rows. It never under-estimates, over-estimates by at most εN with probability 1−δ (w = e/ε, d = ln 1/δ), and sketches merge by adding.

??? example "B2 · How do you merge top-K results from many partitions?"
    Local top-K lists don't merge correctly. Either partition by the ranking key so each item lives in one partition (then merging disjoint local top-Ks is exact), or use a threshold algorithm like TPUT that fetches all items above a bound, or merge mergeable sketches.

??? example "B3 · Design ad-click aggregation that finance can bill on."
    Signed impression and click ids; collectors → Kafka; dedup by click id in keyed state with TTL; bot/fraud filtering; windowed exact counts per campaign; exactly-once sink (transactions or idempotent upserts by window and campaign); OLAP for reporting; daily batch recomputation from raw logs and reconciliation with explained diffs.

??? example "B4 · Design the real-time analytics platform end to end."
    Collectors → Kafka (keyed by aggregation keys) → Flink with event-time windows and sketch state → Redis for app-facing lists, OLAP for dashboards, exactly-once billing path; raw to the lake; batch warehouse as truth; reconciliation; governance of metric definitions.


**[52. Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)**

??? example "B2 · Design an image upload and hosting service — main components?"
    An API that authorises uploads and issues pre-signed URLs; clients upload directly (multipart) to object storage; an event-driven pipeline validates, scans, strips metadata and makes variants; the catalogue stores versioned URLs; a CDN with an origin shield serves images; private objects use signed URLs.

??? example "B2 · How do large uploads survive bad networks?"
    Multipart uploads: split into parts, upload in parallel, retry failed parts only, verify per-part and final checksums, assemble on complete; persist the upload id to resume after app restarts; abort abandoned uploads with lifecycle rules.

??? example "B2 · Replication vs erasure coding?"
    Replication stores full copies (3× for two failures) and repairs by copying one replica; erasure coding stores k data + m parity shards (1.4× for RS(10,4), surviving four failures) but repairs by reading k shards. Use replication for small/hot/fresh data, erasure coding for large/colder data.

??? example "B3 · Design an object store like S3."
    A front-end API tier (auth, routing); a strongly consistent sharded metadata service (bucket/key/version → placement, checksums, ACL); data nodes storing chunks, replicated then erasure-coded across racks and zones; background repair, rebalancing, scrubbing and lifecycle; multipart upload support; events for notifications.

??? example "B4 · Design the media platform for a marketplace end to end."
    Managed erasure-coded object storage with versioning; pre-signed multipart uploads into quarantine; event-driven pipeline (scan, strip, dedup, variants, transcode); versioned URLs in the catalogue; CDN with shield and on-the-fly transforms; private classes with KMS and signed URLs; lifecycle tiers; observability of uploads, pipeline and delivery.


**[53. Case Study: Web Crawler (Competitor Price Monitoring)](../05-case-studies/53-web-crawler.md)**

??? example "B2 · Design a web crawler — main components?"
    Seed URLs and sitemaps; a frontier (priority + per-host queues with politeness); fetchers with DNS caching and robots.txt handling; parsers and link extraction; URL canonicalisation and dedup; content storage and extraction; re-crawl scheduler; monitoring; all partitioned by host across nodes.

??? example "B2 · How do you make a crawler polite?"
    Per-host queues and rate limits (crawl-delay, conservative defaults), a scheduler that only hands out hosts whose next-allowed time has come, backoff on 429/503 with Retry-After, connection limits, robots.txt compliance, an identifying user agent, and circuit breakers.

??? example "B2 · How do you know if you've seen a URL before, at scale?"
    Canonicalise, then store 64-bit fingerprints in a partitioned hash set (exact for practical purposes, ~8 B each); Bloom filters are smaller but their false positives are URLs never fetched — fine for web-scale discovery, wrong for must-see pages.

??? example "B3 · How should re-crawl frequency be decided?"
    Estimate each page's change rate for the extracted fields (correcting for missed changes), allocate the budget to maximise freshness (optimal allocation, not proportional), add floors so no page starves, weight by business value, use priors for new pages, keep re-estimating.

??? example "B4 · Design a price-monitoring crawler end to end."
    Site configs with legal approval; sitemap/robots ingestion; Mercator frontier partitioned by host; canonical fingerprint dedup; fetchers with conditional GETs; JSON-LD-first extraction with validation and quarantine; price history and change events; product matching; re-crawl scheduling with change-rate estimates, floors and business weights; monitoring and kill switches.


**[54. Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)**

??? example "B2 · Design a distributed key-value store — main components?"
    Consistent-hash partitioning with virtual nodes; N replicas per key across racks/AZs; coordinators that apply R/W consistency levels; hinted handoff, read repair and Merkle anti-entropy; per-node LSM storage (commit log, memtable, SSTables, Bloom filters, compaction); gossip membership and failure detection; conflict resolution by LWW, vector clocks or CRDTs.

??? example "B2 · How does an LSM tree work?"
    Writes append to a commit log and update an in-memory sorted memtable; full memtables flush to immutable sorted SSTables; reads check memtable then SSTables newest-first, skipping via Bloom filters; deletes are tombstones; compaction merges SSTables, dropping old versions and expired tombstones.

??? example "B2 · When is a write durable?"
    When it's on stable storage (fsynced) on as many machines as your durability promise requires. Process buffers die with the process, the OS page cache dies with power, fsync survives both; replication spreads risk across machines. Ack only after the promised level.

??? example "B3 · How do you choose consistency levels per operation?"
    By what a stale or lost read/write costs: carts and order status need read-your-writes (QUORUM/QUORUM); sessions and counters tolerate ONE; compare-and-set needs consensus; consider tail latency (quorums inherit the second-slowest replica), availability during failures, and hedging/speculative retry for tails.

??? example "B4 · Design a Dynamo-style key-value store end to end."
    Ring with vnodes, rack-aware replicas, coordinator with per-request R/W, sloppy quorum + hinted handoff, read repair, Merkle anti-entropy, vector clocks/LWW/CRDT conflict handling, LSM storage with group-committed WAL, Bloom filters and tuned compaction, gossip membership with phi detectors, monitoring and repair scheduling.


**[55. Case Study: Order Matching Engine](../05-case-studies/55-order-matching-engine.md)**

??? example "B2 · Design an order matching engine — main components?"
    Gateways (auth, validation, rate limits) → a sequencer that assigns a global order and writes a replicated journal → a single-threaded matching engine per instrument group with in-memory books and risk → output events for acks, trades and market data → sequenced market-data feed with retransmit and snapshots → post-trade settlement; a hot standby replays the same journal.

??? example "B2 · What data structure do you use for the order book?"
    Per side, price levels indexed by tick (array within a price band, tree fallback), each level a FIFO of orders with intrusive doubly-linked lists; an id→order map for O(1) cancel; best-price pointers. Insert O(1)/O(log n), cancel O(1), match O(1) per fill.

??? example "B3 · Design a stock exchange end to end."
    Gateways, sequencer with replicated journal, deterministic single-threaded engines partitioned by instrument, in-path risk, market data with sequenced deltas/retransmit/snapshots, hot standbys with fenced failover, opening/closing auctions, post-trade clearing and settlement, audit trail and surveillance, latency engineering.


### Part VI · Capstone

**[56. Capstone: The Grocery Marketplace, End to End](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md)**

??? example "B2 · Walk me through the architecture of a quick-commerce grocery platform."
    Edge (CDN, WAF, gateway with JWT checks and rate limits), discovery services (catalog, search, pricing, promotions, recommendations), a transaction layer in cells (cart, checkout saga, inventory, slots, payments, orders), an event backbone (outbox → Kafka) feeding fulfilment, dispatch, notifications, invoices and audit, per-service databases plus read models, and a lake for analytics.

??? example "B2 · How do you make sure no customer is charged without getting an order?"
    Idempotent saga steps keyed by order id; treat payment timeouts as unknown and resolve via the gateway's status API; a saga log; a sweeper that completes or compensates abandoned sagas; customer notification on late completion; daily reconciliation with gateway settlement files.

??? example "B3 · How would you derive per-service SLOs from journey SLOs?"
    Start from the journey target (checkout 99.95%), list its hard dependencies (including infrastructure), allocate the unavailability budget across them (e.g. 10 critical services at ~99.995%), reduce the list where the math doesn't work, and verify with measured journey availability.

??? example "B4 · Design the entire quick-commerce platform in 45 minutes."
    Clarify journeys and numbers; sketch layers; deep-dive checkout (saga, inventory, slots, payments) with failure handling; show dependency graph and degraded modes; cells and SLO arithmetic; data and events; security and compliance; cost and evolution.


### Part VII · Interview

**[57. The System Design Interview Framework](../07-interview/57-the-system-design-interview-framework.md)**

??? example "B2 · How do you structure a 45-minute system design interview?"
    Clarify (5 min), estimate (3), API and data model (6), high-level design (6), deep dive (15), failures and scaling (6), wrap-up (4) — timeboxed out loud, with requirements and numbers on the board and check-ins at each transition.

??? example "B2 · Which questions do you ask first?"
    The ones that change the architecture most: scale (orders of magnitude) and latency/consistency needs, plus the core use cases; then state assumptions for the rest and invite correction.

??? example "B2 · How do you justify a technology choice?"
    Tie it to requirements and numbers, state its costs, and name the condition under which you'd choose the alternative: "Kafka because we need replay and per-key ordering at 50k/s; it costs operational complexity; for 1k/s without replay, a database queue would do."

??? example "B3 · What distinguishes a senior answer from a mid-level one?"
    Completeness and correctness under realistic constraints: explicit requirements and numbers, a coherent design, one deep dive with code-level precision (atomic operations, state machines), named trade-offs, and failure handling on the critical path.

??? example "B4 · What distinguishes a staff/principal answer?"
    Driving ambiguity (scoping, phasing), trade-offs argued with flip conditions, failure handling and operability as first-class design (blast radius, SLOs, cost), organisational awareness (teams, migration), and knowing what not to build.


**[58. Level Expectations: From Engineer to Principal](../07-interview/58-level-expectations.md)**

??? example "B1 · What's expected of a junior engineer in system design?"
    A reasonable single-service design that works on the happy path, basic knowledge of databases, caching and APIs, clear communication, and willingness to take hints; growing toward concurrency and failure thinking.

??? example "B2 · What makes an answer senior-level?"
    Independent, complete, correct under concurrency and load: requirements and numbers, clean API/data model, coherent design, one deep dive with atomic operations and idempotency, trade-offs named, basic failure handling.

??? example "B3 · What distinguishes staff from senior in a design interview?"
    Staff candidates find the real problem and its failure modes before being asked — ordering, loss, contention, degradation — argue trade-offs with flip conditions, and treat operability and cost as part of the design; seniors produce a correct design when the problem is defined.

??? example "B4 · What distinguishes principal from staff?"
    Horizon and scope: designing for years of change and loss (reconciliation, migrations, evolution), across domains and organisations, with economics and people in the design; reframing problems and choosing what not to build.


**[59. Interview Q&A Bank](../07-interview/59-interview-qa-bank.md)**

??? example "B1 · A product page is slow. Walk through where the time could go."
    DNS/TLS and network to the edge, CDN miss to origin, API gateway, service latency (DB queries, cache misses, downstream calls), payload size and rendering; measure with tracing before guessing.

??? example "B2 · How do idempotency, retries and timeouts fit together?"
    Timeouts bound waiting; retries recover transient failures; idempotency makes retries safe because the operation may have succeeded; add backoff with jitter and treat timeouts on side-effecting calls as unknown outcomes.

??? example "B3 · Walk a payment through every consistency mechanism it touches."
    Idempotency key at the API; state machine with guarded transitions in the DB; outbox to events; webhook dedup and forward-only updates; status polling for unknowns; double-entry ledger; daily reconciliation with settlement files.

??? example "B4 · Connect DDD, team topology and microservice boundaries."
    Bounded contexts define language and consistency boundaries; teams own contexts end to end (Conway); services follow contexts where independent deployment and scaling pay off; start as modules and extract when load, team size or change rate demands.


**[60. Mock Interviews](../07-interview/60-mock-interviews.md)**

??? example "B1 · What makes a mock interview useful rather than just practice?"
    Realistic conditions (clock, stranger-like interviewer, hidden requirements, follow-ups), a level-anchored scorecard with evidence, a recording, and a debrief that ends in two specific behavioural changes — tracked across mocks.

??? example "B2 · You're 20 minutes in and still haven't drawn the architecture. What do you do?"
    Say it aloud, compress estimates/API/data to one line each ("~1,000 validations/s; validate and redeem endpoints; coupon and redemption tables"), draw the HLD now, and protect the deep-dive time.

??? example "B2 · The interviewer asks 'why not use X instead?' How do you respond?"
    Restate the requirement that drove your choice, compare X honestly on it, then either keep your choice with the reason or switch with the reason — both are fine; flip-flopping without reasons isn't.

??? example "B2 · How do you make a claim like 'a single database row can handle this' credible?"
    Give the number and its basis — measured or well-known — and the condition: e.g., a conditional UPDATE on one row did ~4,400/s from one connection but ~2,000/s with a 77 ms p99 at 32 concurrent callers on a small box; then say what you'd do if the load is higher (buckets, batching, a gate).

??? example "B3 · In the dark-store inventory mock, how do you prevent selling items that aren't physically there?"
    Separate on-hand from reservable stock with a per-SKU safety buffer derived from count drift; reserve at checkout against the source of truth; show cached availability in the app; and every missing-at-pick triggers a count and feeds buffer recalculation — measured by missing-at-pick rate.

??? example "B4 · How do you approach 'launch in a second country' in a principal-level interview?"
    Frame it as product, org and architecture; name hard constraints (residency, payments, cost) and one-way doors; propose country cells from the same code with market configuration as data; estimate the cell's fixed cost; define ownership; sequence reversible steps with metrics and rollback; and say what you won't build yet.


**[61. Behavioural & Leadership](../07-interview/61-behavioral-and-leadership.md)**

??? example "B1 · Tell me about yourself."
    Ninety seconds: current role and scope, two or three highlights with numbers that match the job, what you're looking for next and why this role — a trailer, not your CV.

??? example "B1 · Tell me about a time you failed."
    A real failure where you made the call, with consequences; what you did immediately; the systemic fix; and what you do differently now — owned in the first person.

??? example "B2 · Tell me about a disagreement with a colleague."
    A real technical disagreement; how you understood their view; the evidence you brought; how it was decided; the outcome; and the relationship afterwards — at your level, ideally with someone senior or another team.

??? example "B3 · Tell me about a time you influenced teams you had no authority over."
    Make the cost and the deadline visible, make the path easy (libraries, office hours), agree dated milestones with each lead, escalate early with a proposed fix, and report the outcome.

??? example "B3 · How do you know the impact you claim is real?"
    Define the metric and percentile up front, compare like with like over enough time, repeat measurements, report ranges, separate your change from others, and prefer production metrics over one benchmark.

??? example "B4 · Tell me about a time you changed how your organisation works."
    A system-of-work problem you chose (with data), the mechanism you introduced, how you got adoption across teams and leadership, measured outcomes, and what you'd change.


### Part VIII · Industry Designs

**[I01. Amazon-Style Global Marketplace](../08-industry/i01-amazon-style-global-marketplace.md)**

??? example "B1 · What are the main services of an e-commerce marketplace?"
    Catalog, offers/pricing, search, product page aggregation, cart, checkout, inventory, payments, order workflow, fulfilment and shipping, seller portal, reviews, recommendations — each owned by a team with its own data.

??? example "B2 · Design the shopping cart for availability."
    A leaderless or multi-leader KV store with sloppy quorums and hinted handoff so writes always succeed; version vectors detect concurrent versions; merge with an OR-Set so adds and removes both survive; re-validate everything at checkout.

??? example "B2 · How do you choose and serve the winning offer for a product?"
    Score offers (landed price, promise, seller performance, stock), maintain the winner per product on every offer change in the same transaction under a per-product lock, serve it from that row, and re-validate at checkout.

??? example "B3 · Two sellers reprice the same product at the same moment. What can go wrong and how do you prevent it?"
    Each transaction recomputes the winner from a snapshot that can't see the other's uncommitted change; the later commit can install a wrong winner that persists until the next change. Serialize per product (advisory lock or locking a product row), or recompute asynchronously per product, or use SERIALIZABLE with retry.

??? example "B4 · Explain shuffle sharding and when it's worth it."
    Give each customer a random small combination of k of n workers; a poison customer only kills its own combination, and the chance another customer shares all k is 1/C(n,k) — for 5 of 80, one in 24 million versus 6.3% with fixed shards. It needs client retries within the shard because more customers are partially affected.


**[I02. Flipkart-Style Sale-Day E-Commerce](../08-industry/i02-flipkart-style-sale-day-ecommerce.md)**

??? example "B1 · What makes a big Indian sale day different from a normal day?"
    Traffic spikes of an order of magnitude or more concentrated at deal unlocks, limited-quantity deals, heavy COD and slow networks outside metros, bank offers and EMI at checkout, and dependencies (banks, SMS, couriers) that also peak.

??? example "B2 · Design the deal-unlock flow for a million concurrent shoppers."
    Deal pages and assets from the CDN before unlock; server-time sync on load and a millisecond re-sync from an edge time endpoint at T−10 s; local enable at unlock; Buy goes to a waiting room that admits to checkout at a sustainable rate; the server rechecks time and per-customer limits; bucketed stock reservation.

??? example "B3 · A deal says '2 per customer'. How do you stop reseller rings without blocking families?"
    Enforce limits on the delivery household, not the account: normalise addresses into structured keys (tower, flat, pincode) and combine with device, payment and phone signals; use fuzzy matching only for unparsed leftovers; explain household limits to customers.

??? example "B4 · Plan the engineering readiness for a festive sale."
    Forecast journeys and dependency peaks, capacity plan with headroom, full-journey load and chaos tests, degradation switches, change freeze, pre-scaling and cache warming, war room with owners and comms, then a retrospective feeding next year's forecast.


**[I03. Core Banking & UPI Payments](../08-industry/i03-core-banking-and-upi.md)**

??? example "B1 · What is double-entry bookkeeping and why do banks use it?"
    Every transaction is recorded as equal debits and credits across accounts, so money is only ever moved, never created or lost; the books always balance, which makes errors detectable and audits possible.

??? example "B2 · How do you make postings idempotent?"
    Every posting carries the upstream reference (UPI transaction id, request id) with a unique constraint; a retry finds the existing posting and returns its result; reversals reference the original posting and are unique too.

??? example "B3 · A merchant account receives 3,000 credits per second. How do you design its balance?"
    Credits become inserts into an append-only journal (or spread over sub-accounts); a roll-up folds committed rows into the balance frequently; debits take a per-account lock and check balance plus unrolled rows; roll-up marks rows rather than using a sequence high-water mark.

??? example "B3 · The credit leg of a UPI payment times out. What does the remitter bank do?"
    Keep the debit in PENDING — neither reverse nor finalise; run jittered status checks with backoff; resolve from status or settlement files, whichever comes first; reverse at the next settlement cycle if still unknown, well before the TAT deadline; tell the customer it's processing.

??? example "B4 · How would you modernise a legacy core banking system?"
    Strangler approach: a payments hub and API layer in front of the old core, new products on a new ledger, migrate account cohorts with dual-run reconciliation, move batch work to streams, and retire old modules only after sustained parity — with regulator engagement throughout.


**[I04. Hospital Information System, EHR & Telemedicine](../08-industry/i04-hospital-ehr-and-telemedicine.md)**

??? example "B1 · What are the main systems in a hospital, and how do they connect?"
    Patient administration (registration, ADT), scheduling, EHR with order entry, lab (LIS), radiology (RIS/PACS), pharmacy and eMAR, OT, billing and insurance, inventory, telemedicine and a patient app — connected by an integration engine (HL7 v2, FHIR) and an event bus, with ADT events telling every department where the patient is.

??? example "B2 · Why use FHIR as the internal clinical data model?"
    It's an international standard with well-defined resources (Patient, Encounter, Observation, MedicationRequest…), versioning and search, used for national exchange (ABDM) and apps (SMART on FHIR) — so exchange becomes mapping and the ecosystem of tools applies.

??? example "B3 · Design the master patient index."
    Blocking keys to limit comparisons; field comparators (Jaro–Winkler and phonetic codes for names, DOB variants, mobile, ABHA); Fellegi–Sunter weights from reviewed pairs; auto-link above an upper threshold, human review in the grey zone, new record below; audited merge/unmerge events that downstream systems follow.

??? example "B3 · How would you implement early-warning alerts from bedside monitors?"
    Device gateway converts monitor data to Observations; a stream processor keeps per-patient state and computes the approved score (e.g. NEWS2) on each vital set; threshold crossings trigger escalation with suppression windows; stale data is surfaced, not treated as normal; alert burden is measured.

??? example "B4 · Design the data platform for a hospital chain."
    Outbox events and CDC into Kafka; an Iceberg lakehouse with raw and conformed zones (codes mapped, patients linked); Kimball star-schema marts for operations, quality and finance; OMOP de-identified store for research; feature tables and an ML platform with governance; BI with facility-level row security; all in-country.


**[I05. Ride-Hailing Platform](../08-industry/i05-ride-hailing.md)**

??? example "B1 · Walk me through what happens when a rider requests a ride."
    Fare estimate (route, ETA, price with multiplier) → request creates a trip with the quote → matching assigns a driver in the next batch, locking the driver → offer to driver → accept → navigation to pickup, arrival, PIN to start → live tracking → end and final fare → payment, ledger, ratings.

??? example "B2 · How would you design surge pricing?"
    Forecast demand and supply per zone for the next minutes, map the imbalance to a multiplier with a monotonic curve, smooth over time and space, apply caps (including regulatory ones) and emergency overrides, show it in the upfront fare, and evaluate with switchback experiments.

??? example "B3 · Why batch matching instead of nearest driver, and how does it work?"
    Greedy nearest-driver is locally optimal but globally poor; batching requests and drivers every few seconds and solving a min-cost bipartite matching (Hungarian or min-cost flow) on ETA plus penalties lowers average pickup times; include drivers finishing trips nearby; tune the window per city.

??? example "B3 · How do you compute accurate ETAs?"
    A road graph with turn restrictions, live segment speeds from driver traces blended with historical profiles, fast shortest paths (contraction hierarchies), and an ML model that corrects the routing ETA using features like time, weather and pickup type — monitored for error drift.

??? example "B4 · Design the data platform for a ride-hailing company."
    Kafka for all events; Flink for real-time features and aggregates feeding marketplace engines and a real-time OLAP store (Pinot) for ops dashboards; a Hudi/Iceberg lakehouse for trips, offers, quotes and payments with late updates; Presto/Trino and Spark for analysis; a feature store and ML platform; strict governance for location data.


**[I06. Video Streaming & Live Cricket](../08-industry/i06-video-streaming-and-live-cricket.md)**

??? example "B1 · What happens between pressing Play and seeing video?"
    The app asks the playback service, which checks entitlement, rights and device; returns a device-specific manifest, signed CDN URLs and a DRM licence token; the player gets a licence, downloads the first segments at a safe bitrate, starts playback, and adapts bitrate while sending telemetry.

??? example "B2 · How do you build the encoding ladder for a title?"
    Encode sample points across resolutions and bitrates, score them with a perceptual metric like VMAF, take the convex hull of quality vs bitrate, and pick rungs along it — per title or per shot, so simple content uses fewer bits.

??? example "B3 · How would you handle 40 million concurrent viewers for a cricket final?"
    Data plane: redundant contribution and encoding, CMAF packaging, origin shield with request coalescing, multi-CDN plus ISP caches with reserved capacity, bitrate caps under stress. Control plane: forecast joins, pre-scale, static cacheable pages, edge token validation, degradation switches, client backoff with jitter, and a war room steering CDNs by real-time QoE.

??? example "B4 · Design the telemetry and QoE analytics platform."
    Players batch events to edge collectors → Kafka → Flink computing per-minute QoE aggregates with sketches (HLL, t-digest) into a real-time OLAP store for dashboards and CDN steering; raw events into an Iceberg lakehouse for analysis, ML and experiments; sampling and retention tiers to control cost.


**[I07. Messaging & Social Feed](../08-industry/i07-messaging-and-social-feed.md)**

??? example "B1 · How does a message get from one phone to another?"
    The sender's app encrypts it for each recipient device and sends it over a persistent connection; the server stores it in the recipient's queue and acknowledges (sent); if the recipient is online the server forwards it, otherwise a push notification wakes the app to fetch it; the recipient acknowledges (delivered) and later sends a read receipt.

??? example "B2 · How do group messages stay efficient with end-to-end encryption?"
    Sender keys: each member distributes a group sender key once via pairwise encrypted sessions; each message is then encrypted once and the server fans out a single ciphertext; keys rotate when membership changes; MLS improves this for very large groups.

??? example "B2 · Push or pull for building feeds?"
    Push writes post IDs to followers' inboxes (fast reads, heavy writes for celebrities); pull merges followed accounts' recent posts at read time (no write amplification, heavy reads for users following many); production systems use a hybrid with a follower threshold, and ranking reduces the need for complete inboxes.

??? example "B3 · Explain X3DH and the Double Ratchet at a system-design level."
    X3DH lets Alice establish a shared secret with offline Bob using his published identity, signed prekey and one-time prekey plus her ephemeral key; the Double Ratchet then derives a new key per message (forward secrecy) and mixes fresh Diffie–Hellman values on replies (post-compromise security). Systems-wise: a key directory, prekey replenishment, per-device sessions, and safety numbers for verification.

??? example "B3 · Design the ranking system for the home feed."
    Multiple candidate sources (followed, recommended via embeddings, friends' engagement, ads), filtering, a light early ranker, a heavy multi-task model predicting several actions, a value model with weights tuned for long-term retention, diversity and integrity adjustments, and continuous A/B testing.

??? example "B4 · How would you scale a messaging service to hundreds of millions of concurrent connections?"
    A large tier of connection servers optimised for many idle sockets (Erlang/epoll), a routing registry for device-to-server mapping, stateless reconnection anywhere, per-device replicated queues, push notifications for offline devices, regional presence with cross-region routing, and graceful rolling deploys that drain sockets gradually.


**[I08. Stockbroking Platform](../08-industry/i08-stockbroking-platform.md)**

??? example "B1 · What happens when you place a stock order through a broker app?"
    The app sends it to the broker's API; the OMS validates it; the RMS checks and blocks margin; the exchange gateway sends it to the exchange; the exchange acknowledges and later matches it; trade confirmations update the order, positions and margin; the client sees updates over WebSocket; end-of-day processing generates the contract note and settlement.

??? example "B2 · Design the order management system's state machine."
    Received → (rejected | pending new) → open → partially filled → filled; with pending modify/cancel states; journal every transition; accept exchange messages in any valid order and trust exchange quantities; idempotent client order IDs; recover by journal replay and reconcile with the exchange's order book.

??? example "B3 · How would you compute F&O margins in real time?"
    Load the exchange's daily risk parameter files, precompute scenario loss arrays per instrument, and for each order compute portfolio margin with and without the order — worst-case scenario loss per underlying plus spread charges, net option value adjustments and exposure margin — so hedges reduce margin.

??? example "B3 · Design the ticker plant for 3 million concurrent clients."
    Feed handlers with gap recovery, normalisation to compact binary, in-memory latest snapshots, internal pub/sub to a sharded WebSocket fleet with per-connection subscription maps, per-client conflation with a flush interval, modes by detail level, slow-client handling, and pre-scaling for the market open.

??? example "B4 · Design a broker platform end to end."
    Client apps and APIs behind a gateway; sharded in-memory OMS/RMS with journals; exchange gateways with rate limits and reconciliation; a ticker plant with conflated WebSocket fan-out; GTT and alerts engines on the tick stream; funds and a double-entry client ledger; a back office for contract notes, obligations and corporate actions; depository integration for holdings and pledges; Kafka to warehouse, surveillance and regulatory reporting.


**[I09. Insurance Policy & Claims Platform](../08-industry/i09-insurance-policy-and-claims.md)**

??? example "B1 · What are the core systems of an insurance company?"
    Product configuration and rating, distribution channels, underwriting, policy administration (issuance, endorsements, renewals), billing and collections, claims management, fraud, reinsurance, actuarial and finance, customer service, and a data platform for reporting and pricing.

??? example "B2 · Why use a bitemporal data model for policies?"
    Changes can apply from a past or future date (effective time) and are recorded at a different time (transaction time); claims need "what cover applied on the loss date" and audits need "what did we know when we decided" — both require keeping both timelines.

??? example "B3 · How would you price motor insurance with data?"
    Frequency (Poisson GLM) × severity (Gamma GLM) with log links giving multiplicative factors, credibility smoothing for sparse cells, ML to discover interactions then encoded in explainable tables, plus expense and profit loadings, within filed ranges — and monitor actual vs expected.

??? example "B3 · How do you detect insurance fraud rings?"
    Entity resolution across people, vehicles, providers, phones, bank accounts and addresses; build a graph; find communities and hubs; combine graph features with rules and supervised models; route high scores to the SIU; feed outcomes back as labels.

??? example "B4 · Design a digital insurer's platform end to end."
    Channel APIs for app, agents and aggregators; product configurator driving rating, underwriting, policy and claims engines; bitemporal policy administration; billing with mandates; claims workflows with STP, fraud scoring and SIU; reinsurance; Kafka to a lakehouse with actuarial marts, finance and regulatory reporting; ML for pricing, underwriting, triage, fraud and images.


**[I10. Travel & Railway Ticketing](../08-industry/i10-travel-and-railway-ticketing.md)**

??? example "B1 · What makes train ticketing harder than flight ticketing?"
    A train serves many overlapping journeys along its route, so each berth's occupancy must be tracked per segment; plus quotas, RAC, waitlists that confirm on cancellations, charting, and demand spikes like Tatkal.

??? example "B2 · How do you represent and allocate berths along a route?"
    Each berth has a bitset over route segments; a journey is a mask of its segments; a berth fits if AND is zero; book by OR, cancel by clearing; choose among fitting berths with best-fit heuristics that reduce fragmentation and honour preferences and quotas.

??? example "B3 · Design the system for the Tatkal minute."
    Pre-scale; CDN for static content; a waiting room and server-synced countdown; anti-bot and per-user limits; an admission controller routing requests FIFO into single-writer train-date partitions; short holds with payment spread across gateways; cached availability and degraded search; fast "not available" answers; post-hoc bot cancellations.

??? example "B4 · Design a national railway reservation system."
    Timetable and train master; in-memory inventory per train-date with segment bitsets and quotas, owned by single-writer partitions with journals and standbys; booking with holds and payment orchestration; waitlist, RAC and charting jobs; cached availability; cancellation and refund rules; admission control and anti-bot for Tatkal; Kafka to a warehouse for forecasting, waitlist prediction and fraud.


**[I11. Telecom Billing & Charging](../08-industry/i11-telecom-billing-and-charging.md)**

??? example "B1 · How does a prepaid phone's data get charged in real time?"
    The network gateway asks the online charging system for a quota before letting data flow; the OCS reserves some of the subscriber's balance and grants it; the gateway reports usage when the quota is used or a timer expires; the OCS commits the usage, releases leftovers and grants more — or denies and the gateway redirects to a recharge page.

??? example "B2 · How would you size data quotas in the OCS?"
    Adaptively: a fraction of remaining balance, larger for heavy users and smaller near exhaustion, adjusted for concurrent sessions and service type, with validity timers and a final-unit indication near zero — balancing request load against overuse and stranded reservations.

??? example "B3 · Design an OCS for 400 million subscribers."
    In-memory, partitioned by subscriber, single writer per partition, synchronous replica in-site and async across sites; Diameter/HTTP2 front ends; reservation-based quotas with adaptive sizing; bucket consumption rules; threshold events to policy and notifications; lazy daily resets; shared pools via owner partitions; failure handling agreed with the network.

??? example "B4 · Design the BSS of a large mobile operator."
    CRM and self-care, a versioned product catalogue feeding OCS and billing, order management with provisioning orchestration to HSS/UDM, policy and OCS, converged real-time charging, recharges and payments, mediation, postpaid rating and billing, collections, interconnect and roaming settlement, revenue assurance and fraud, and a usage data platform — aligned with TM Forum models and APIs.


**[I12. Logistics & Supply Chain](../08-industry/i12-logistics-and-supply-chain.md)**

??? example "B1 · Walk through a parcel's journey in an express network."
    Booking via API (AWB, label, promised date) → pickup scan → first hub inbound → sortation into bags by next destination → line-haul trips between hubs → destination delivery centre → rider out for delivery → delivered with OTP and COD, or failed attempt → NDR → reattempt or RTO; every step is a scan event.

??? example "B2 · Why model shipments as event streams?"
    Every physical action is a fact with a time, place and actor; an append-only log gives a complete custody chain for disputes and analytics, supports offline devices with idempotent events, and lets different projections (status, timeline, facility inventory) serve different needs.

??? example "B3 · How do you design a hub's sort plan?"
    Map destinations to chutes: dedicated chutes for high-volume destinations, grouped chutes for low-volume ones sharing a next hop, balanced chute loads, physical constraints near outbound docks — optimised per shift from forecasts, versioned and pushed to the edge for activation at shift change.

??? example "B4 · Design an express logistics platform end to end."
    Client APIs for booking with serviceability, TAT and address intelligence; offline-first field apps and hub edge nodes producing idempotent scan events; an event store with projections for tracking, facility inventory and exceptions; TMS for trips and e-way bills; last-mile dispatch; NDR/RTO and COD workflows; planning (network, sort, trips, forecasts) offline; a control tower from streaming aggregates; a lakehouse for analytics and ML.


**[I13. Online Exams & Live Classes](../08-industry/i13-online-exams-and-live-classes.md)**

??? example "B1 · How would you stream a live class to 100,000 students?"
    The teacher sends video over WebRTC to an SFU; it's composed, encoded into an ABR ladder and packaged as low-latency HLS served by a CDN to the audience with 2–5 s latency; only on-stage students use WebRTC; whiteboard and polls travel as small events over WebSockets.

??? example "B2 · How do you handle 500,000 students starting a test at the same second?"
    Pre-download the encrypted paper from the CDN over the preceding 30 minutes, then release a small decryption key and start token at start time from a scaled key service; record server-side start times; late joiners get remaining time.

??? example "B3 · How do you normalise scores across multiple exam shifts?"
    Compute percentile scores within each shift — 100 × candidates with score ≤ yours ÷ total in shift — to high precision and rank on those, as some national agencies do (VERIFY); alternatives are equipercentile or IRT equating with anchor items; make it reproducible from stored responses and final keys.

??? example "B4 · Design a national computer-based test system."
    Encrypted papers pre-positioned on hardened local centre servers with just-in-time key release, locked-down terminals, continuous local response saving with audit and replication, signed upload after shifts, provisional keys and challenges, scoring, percentile normalisation across shifts, and CDN-served results — with readiness monitoring and contingencies.


**[I14. National Digital ID & Vaccination-Scale Registration](../08-industry/i14-national-digital-id-and-vaccination-registration.md)**

??? example "B1 · What is the difference between 1:1 authentication and 1:N identification?"
    1:1 authentication compares a biometric against the templates of one claimed identity — "is this person who they say they are?" — and is cheap. 1:N identification searches the whole gallery — "is this person already enrolled?" — which at a billion people needs massive parallel matching, pruning and strict thresholds.

??? example "B2 · How would you handle 50 million people trying to book 1 million vaccine slots at 4 pm?"
    Serve search from cached availability snapshots via CDN, protect booking with rate limits, CAPTCHA and OTP tokens, decrement capacity atomically per session, stagger releases, and consider a randomised waiting room so fast connections and bots don't win.

??? example "B3 · Design biometric de-duplication for a billion people."
    Extract templates for fingers, irises and face; shard the gallery in memory across many nodes; broadcast each query, compare in parallel with pruning, fuse multi-modal scores, use strict high/low thresholds with manual adjudication in between, and run multiple vendors for accuracy and resilience — all as a queued batch pipeline.

??? example "B4 · Design a national digital identity system from scratch."
    Minimal data, random IDs, certified offline-capable enrolment with signed encrypted packets, multi-modal multi-vendor biometric de-dup with adjudication, a yes/no authentication service via licensed gateways and certified devices, eKYC with consent, VIDs and tokens for unlinkability, resident controls and audit logs, legal safeguards and inclusion fallbacks.


**[I15. Multiplayer Gaming & Leaderboards](../08-industry/i15-multiplayer-gaming-and-leaderboards.md)**

??? example "B1 · Why do competitive online games use authoritative dedicated servers?"
    The server holds the true game state and validates every input, so clients can't cheat by sending fake positions or damage; it also removes host advantage and NAT problems of peer-to-peer.

??? example "B2 · Design matchmaking for a 5v5 game."
    Tickets (solo or party) with ratings and region pings go into regional pools; every second a match function forms groups within skill windows that widen with wait time, oldest first, then balances teams by total skill; the allocator assigns a warm server and players get join tokens.

??? example "B3 · Explain lag compensation and its trade-offs."
    The server keeps a short history of hitboxes, and on a shot rewinds other players to where the shooter saw them (now − latency − interpolation delay), casts the ray, then restores; it makes aiming fair for the shooter but causes "died behind cover" for the victim, so cap the rewind window.

??? example "B4 · Design the backend for a battle royale game with 3M concurrent players."
    Accounts, social and presence services; regional matchmakers with latency probing and skill windows; an allocator over Agones-style fleets with warm buffers in many regions; authoritative 30 Hz servers with prediction, lag compensation and interest management; async match-result pipeline to ratings, progression, ledger, leaderboards and anti-cheat; telemetry to Kafka and a lakehouse; live ops config.


**[I16. Smart Metering & IoT Telemetry](../08-industry/i16-smart-metering-and-iot-telemetry.md)**

??? example "B1 · What are the HES and the MDM in a smart metering system?"
    The Head-End System talks to meters over the network (DLMS), schedules reads, sends commands and receives events; the Meter Data Management system validates, estimates and stores the data, maintains meter-consumer-network relationships, and feeds billing, prepaid and analytics.

??? example "B2 · How do you detect outages from smart meters without creating a million tickets?"
    Ingest last-gasp events into Kafka, group them by time window and network topology from GIS, infer DT- or feeder-level outages when a threshold fraction of child meters fails, confirm with pings and SCADA, create one outage ticket, and close it on restore events, detecting nested outages.

??? example "B3 · How would you find electricity theft using smart meter data?"
    Compute daily DT energy balances (DT meter minus consumers minus estimated technical loss) to find high-loss areas, then score consumers under them using tamper events, consumption trend breaks, peer comparison and profile anomalies with rules plus unsupervised and supervised models, and prioritise inspections, feeding results back.

??? example "B4 · Design an AMI platform for 25 million prepaid meters."
    Mixed RF mesh and cellular network, HES with DLMS, HSM keys, scheduler and command manager; Kafka as the backbone; MDM with VEE, TSDB and lakehouse; a prepaid engine with ledger, policy-driven disconnects and fast reconnects; OMS with topology correlation; device management and FOTA; analytics for losses, theft and forecasting — with safety guardrails on commands.


**[I17. Hotel & Homestay Booking](../08-industry/i17-hotel-and-homestay-booking.md)**

??? example "B1 · How would you represent room availability for a hotel?"
    Per property, room type and date store inventory and sold counts, and per rate plan and date store prices by occupancy and restrictions (min stay, closed to arrival/departure); keep them as compact date-indexed arrays so a stay check is a min over the nights.

??? example "B2 · Design hotel search that answers in under 500 ms."
    Stage 1: geo and attribute index retrieves candidates; stage 2: scatter-gather to an in-memory availability/price cache sharded by property for stay checks and lead prices; stage 3: pricing (taxes, discounts) and ML ranking; cache popular queries briefly; log impressions.

??? example "B3 · How would you build ranking for a stays marketplace?"
    Learning-to-rank on impression logs with bookings as positives and position-bias correction; features from listing quality, price competitiveness, reviews, query and user context; listing embeddings from click sessions for personalisation; online A/B tests on conversion with guardrails; business rules for sponsored labels, diversity and new-listing exploration.

??? example "B4 · Design a hotel and homestay booking platform end to end."
    Supply onboarding and content; ARI store with range updates fed by channel managers, extranet and hosts; two-stage search with geo index and in-memory availability/price cache; ML ranking and pricing; booking service with atomic holds, live checks where needed and idempotent payments; supplier delivery; payouts via ledger; reviews and trust; Kafka to a lakehouse for analytics and experiments.


**[I18. Food Delivery](../08-industry/i18-food-delivery.md)**

??? example "B1 · What are the steps of a food delivery order?"
    Order placed and paid, restaurant accepts with a prep time, a rider is assigned timed to food readiness, rider picks up, delivers with live tracking, and the order is rated and settled — each step is a state transition event consumed by notifications, tracking, ETA and settlements.

??? example "B2 · How would you predict delivery ETA?"
    Decompose it into acceptance, dispatch, max(rider-to-restaurant, prep remaining), pickup, travel to customer and last mile; model each with ML (quantile regression) using features like restaurant load, travel time matrices, weather and building history; promise a conservative quantile and update after each event.

??? example "B3 · Design dispatch for food delivery."
    Per zone every 10–30 s, collect unassigned orders and available or soon-available riders, compute costs from travel, rider wait, food wait, promise delay, fairness and acceptance probability, delay assignment until near food-ready, solve min-cost matching, evaluate batching via insertion cost, offer to riders and re-pool on rejection; fall back to greedy under load.

??? example "B4 · Design a food delivery platform for 3 million orders a day."
    Hex and zone geo model; precomputed serviceability; personalised feed and search; cart and pricing with offers; event-sourced order management; prep and ETA models; batch dispatch with delayed assignment and batching; live tracking; graduated peak levers; ledgers for settlements; Kafka, Flink features and a lakehouse with switchback experiments.

