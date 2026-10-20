# Glossary

!!! abstract "About this glossary"
    This glossary has 189 core terms, each with a one-line definition and a link to the chapter that explains it in depth.

    **It is checked against the book.** `GlossaryGen` (in `labs/appendix-tools/`) verifies that every term actually appears in the chapter it links to. Hyphens and spaces count as the same, and there are optional exact-match probes. On the first run, 15 of 159 terms failed:

    - some pointed to the wrong chapter (anycast is covered in Ch 08, not Ch 03; backpressure in Ch 19, not Ch 18);
    - some were phrased differently from the chapter ("outbox pattern" vs "outbox");
    - one described something the chapter doesn't cover. "Fan-out on write vs on read" was reworded to the fan-out Ch 43 actually discusses.

    The current version passes: 189 terms, 0 problems.

    Interview-specific terms are in Chs 57–61. For questions on a term, use the [Full Question Index](qa-index.md).

## 0–9

**2PC (two-phase commit)**
:   Atomic commit protocol: a coordinator asks every participant to prepare, then tells all to commit or all to abort; blocks if the coordinator fails after prepare. → [Ch 13 Distributed Transactions](../02-distributed-core/13-distributed-transactions.md)

**3-2-1 rule**
:   Backup rule of thumb: 3 copies, on 2 different media, 1 off-site (or in another region/account). → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)


## A

**ACID**
:   Atomicity, Consistency, Isolation, Durability — the guarantees of a database transaction. → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)

**ADR (architecture decision record)**
:   A short document recording one decision: context, options, decision, consequences, and when to revisit it. → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)

**Aggregate**
:   A cluster of domain objects changed together inside one transaction, with one root that enforces its invariants. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Anti-corruption layer**
:   A translation layer that stops another context's model leaking into yours. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Anycast**
:   One IP address announced from many locations; the network routes each client to the nearest. → [Ch 08 Load Balancing, Proxies & Gateways](../01-foundations/08-load-balancing-proxies-and-gateways.md)

**API gateway**
:   Edge component that routes, authenticates, rate-limits and shapes requests for backend services. → [Ch 08 Load Balancing, Proxies & Gateways](../01-foundations/08-load-balancing-proxies-and-gateways.md)

**At-least-once delivery**
:   Every message is delivered one or more times; consumers must tolerate duplicates. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**At-most-once delivery**
:   Every message is delivered zero or one time; losses are possible, duplicates are not. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**Availability**
:   The fraction of time (or requests) a system serves correctly, often expressed in "nines". → [Ch 01 What System Design Is](../00-orientation/01-what-system-design-is.md)

**Avro**
:   A binary serialization format with reader and writer schemas resolved at read time. → [Ch 28 Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)


## B

**Back-of-the-envelope estimate**
:   A quick order-of-magnitude calculation of load, storage or bandwidth to guide design choices. → [Ch 02 Back-of-the-Envelope Estimation](../01-foundations/02-back-of-the-envelope-estimation.md)

**Backfill**
:   Populating a new column or store with historical data, usually in batches alongside live writes. → [Ch 28 Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)

**Backpressure**
:   Signalling upstream to slow down when a component cannot keep up, instead of queueing without bound. → [Ch 19 Concurrency at Scale in Java](../02-distributed-core/19-concurrency-at-scale-in-java.md)

**Base62**
:   Encoding numbers with 0–9, a–z and A–Z, used for short URL codes. → [Ch 38 Case Study: URL Shortener & SMS Short Links](../05-case-studies/38-url-shortener-and-short-links.md)

**BFF (backend for frontend)**
:   A backend tailored to one client type (app, web) that aggregates and shapes calls for it. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**Blast radius**
:   How many users or systems one failure or bad change can affect. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Bloom filter**
:   A compact probabilistic set: "definitely not present" or "probably present", with a tunable false-positive rate. → [Ch 53 Case Study: Web Crawler (Competitor Price Monitoring)](../05-case-studies/53-web-crawler.md)

**Blue-green deployment**
:   Running two production environments and switching traffic from the old (blue) to the new (green) at once. → [Ch 26 Deployment & Infrastructure](../03-production/26-deployment-and-infrastructure.md)

**BM25**
:   A standard relevance-scoring function based on term frequency, document frequency and document length. → [Ch 22 Search Systems](../03-production/22-search-systems.md)

**Bounded context**
:   A boundary within which a model and its language are consistent; the unit of ownership in DDD. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Bulkhead**
:   Isolating resources (threads, connections, pools) per dependency so one failure cannot exhaust all. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)


## C

**Cache stampede**
:   Many requests miss the same expired key at once and overload the source; prevented by locking, coalescing or early refresh. → [Ch 07 Caching](../01-foundations/07-caching.md)

**Cache-aside**
:   The application reads the cache, falls back to the database on a miss, and fills the cache itself. → [Ch 07 Caching](../01-foundations/07-caching.md)

**Calibration (interviewers)**
:   Aligning interviewers' scoring through anchored rubrics and shared review so verdicts are consistent. → [Ch 60 Mock Interviews](../07-interview/60-mock-interviews.md)

**Canary release**
:   Sending a small share of traffic to a new version and widening only if its metrics stay healthy. → [Ch 26 Deployment & Infrastructure](../03-production/26-deployment-and-infrastructure.md)

**CAP theorem**
:   During a network partition a distributed store must choose between consistency and availability. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**CDC (change data capture)**
:   Streaming a database's committed changes (usually from its log) to other systems. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)

**CDN (content delivery network)**
:   Edge caches close to users that serve static and cacheable content. → [Ch 52 Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)

**Cell-based architecture**
:   Splitting a system into independent full copies (cells), each serving a subset of users, to bound blast radius. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**Chaos engineering**
:   Deliberately injecting failures in controlled experiments to verify resilience. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Choreography**
:   Services react to each other's events without a central coordinator. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**Circuit breaker**
:   Stops calling a failing dependency for a while after errors exceed a threshold, then probes before resuming. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)

**Clock skew**
:   The difference between clocks on different machines; why wall-clock timestamps cannot order distributed events safely. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Connection pool**
:   A bounded set of reused database connections; its size is a key capacity limit. → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)

**Consistent hashing**
:   Mapping keys and nodes onto a ring so adding or removing a node moves only a small share of keys. → [Ch 11 Partitioning & Sharding](../02-distributed-core/11-partitioning-and-sharding.md)

**Consumer group**
:   Kafka consumers sharing a group id divide a topic's partitions among themselves. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**Consumer lag**
:   How far a consumer is behind the latest message in a partition. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**Contract testing**
:   Verifying that a provider still satisfies the requests and responses its consumers depend on (e.g. Pact). → [Ch 29 Testing Distributed Systems](../03-production/29-testing-distributed-systems.md)

**Coordinated omission**
:   A load-testing error where a stalled system also stalls request sending, hiding the real latency. → [Ch 25 Performance Engineering](../03-production/25-performance-engineering.md)

**Cost per order**
:   A unit-economics metric: infrastructure cost divided by orders served. → [Ch 35 Cost Engineering & FinOps](../04-architect/35-cost-engineering-and-finops.md)

**Count-Min Sketch**
:   A small probabilistic table that estimates item frequencies in a stream, never underestimating. → [Ch 51 Case Study: Real-time Analytics & Top-K (Trending)](../05-case-studies/51-real-time-analytics-and-top-k.md)

**CQRS**
:   Command Query Responsibility Segregation: separate models for writes and for reads. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)

**CRDT**
:   A conflict-free replicated data type whose replicas can merge concurrent updates deterministically. → [Ch 10 Replication](../02-distributed-core/10-replication.md)

**Crypto-shredding**
:   Making data unreadable by deleting its encryption key, used for erasure in backups and logs. → [Ch 36 Compliance, Governance & Data Residency](../04-architect/36-compliance-governance-and-data-residency.md)


## D

**DAG (workflow)**
:   A directed acyclic graph of jobs where each runs after its dependencies succeed. → [Ch 50 Case Study: Distributed Job Scheduler](../05-case-studies/50-distributed-job-scheduler.md)

**Dark store**
:   A delivery-only warehouse serving quick-commerce orders from a small area. → [Ch 46 Case Study: Grocery Checkout & Inventory Reservation](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md)

**Data residency**
:   A requirement that certain data be stored (and sometimes processed) within a given country or region. → [Ch 36 Compliance, Governance & Data Residency](../04-architect/36-compliance-governance-and-data-residency.md)

**Dead-letter queue**
:   A queue that holds messages that repeatedly fail processing, for inspection and replay. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**Deterministic simulation testing**
:   Running a whole distributed system in a simulated, seeded environment so failures can be reproduced exactly. → [Ch 29 Testing Distributed Systems](../03-production/29-testing-distributed-systems.md)

**Disagree and commit**
:   Arguing a position with evidence before a decision, then fully supporting the decision once made. → [Ch 61 Behavioural & Leadership](../07-interview/61-behavioral-and-leadership.md)

**Distributed lock**
:   A lock shared across processes; safe only with leases and fencing tokens. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Distributed monolith**
:   Services that must be deployed or changed together — the costs of microservices without the independence. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**DLQ**
:   See dead-letter queue. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**DNS**
:   The Domain Name System, mapping names to addresses through a hierarchy of cached lookups. → [Ch 03 Networking Essentials](../01-foundations/03-networking-essentials.md)

**Domain event**
:   A record that something meaningful happened in the domain, named in the past tense. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Double-entry ledger**
:   Recording every money movement as equal debits and credits so balances always reconcile. → [Ch 45 Case Study: Payment System](../05-case-studies/45-payment-system.md)

**Durability**
:   Once acknowledged, data survives crashes and failures. → [Ch 01 What System Design Is](../00-orientation/01-what-system-design-is.md)


## E

**Erasure coding**
:   Splitting data into k data and m parity shards so any k of k+m can rebuild it, at lower overhead than replication. → [Ch 52 Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)

**Error budget**
:   The allowed unreliability under an SLO (e.g. 0.1% of requests); spending it slows releases. → [Ch 24 Observability](../03-production/24-observability.md)

**Event sourcing**
:   Storing state as the sequence of events that produced it; current state is derived by replay. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)

**Event storming**
:   A workshop technique that maps a domain by laying out its events on a timeline. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Eventual consistency**
:   Replicas converge to the same value if updates stop; reads may be stale meanwhile. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Exactly-once processing**
:   Each input affects the output once, achieved by combining at-least-once delivery with idempotency or transactions. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)

**Expand/contract**
:   Changing a schema in backward-compatible steps: add the new, migrate, switch, then remove the old. → [Ch 28 Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)


## F

**Failover**
:   Moving service from a failed component (e.g. a primary database) to a standby. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Fan-out**
:   One request or event multiplied into many downstream calls or writes — a feed request into feature lookups, or a post into every follower's feed. → [Ch 43 Case Study: Home Feed & Recommendations](../05-case-studies/43-home-feed-and-recommendations.md)

**Feature flag**
:   A runtime switch that turns code paths on or off without redeploying. → [Ch 26 Deployment & Infrastructure](../03-production/26-deployment-and-infrastructure.md)

**Fencing token**
:   A monotonically increasing number checked by the resource, so a stale lock holder's writes are rejected. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Fitness function**
:   An automated check that an architectural property (dependency rules, latency, cost) still holds. → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)

**Flip point**
:   The weighting or value at which a decision matrix would choose a different option. → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)


## G

**Geohash**
:   Encoding latitude and longitude into a string whose shared prefixes mean nearby cells. → [Ch 47 Case Study: Delivery Dispatch & Live Tracking](../05-case-studies/47-delivery-dispatch-and-live-tracking.md)

**Graceful degradation**
:   Keeping the core function working with reduced features when dependencies fail. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)

**gRPC**
:   A high-performance RPC framework using HTTP/2 and Protocol Buffers. → [Ch 04 API Design](../01-foundations/04-api-design.md)


## H

**Head-of-line blocking**
:   One slow item holding up everything behind it on a connection or queue. → [Ch 03 Networking Essentials](../01-foundations/03-networking-essentials.md)

**Heartbeat**
:   A periodic signal that a node is alive, used for failure detection. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Hedged request**
:   Sending a duplicate request after a delay and using whichever answer returns first, to cut tail latency. → [Ch 25 Performance Engineering](../03-production/25-performance-engineering.md)

**Hexagonal architecture**
:   Ports and adapters: domain logic at the centre, infrastructure plugged in through interfaces. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**Hinted handoff**
:   A node temporarily stores writes for an unavailable replica and forwards them when it returns. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)

**Hot key / hot partition**
:   A key or shard receiving a disproportionate share of traffic. → [Ch 11 Partitioning & Sharding](../02-distributed-core/11-partitioning-and-sharding.md)

**HyperLogLog**
:   A probabilistic structure that estimates the number of distinct items in a few kilobytes. → [Ch 51 Case Study: Real-time Analytics & Top-K (Trending)](../05-case-studies/51-real-time-analytics-and-top-k.md)


## I

**Idempotency key**
:   A client-supplied key that lets the server recognise retries and return the original result. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Idempotent**
:   An operation that has the same effect whether applied once or many times. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Interviewer card**
:   A mock interviewer's sheet: prompt, hidden requirements, follow-up chains, curveball, level anchors. → [Ch 60 Mock Interviews](../07-interview/60-mock-interviews.md)

**Invariant**
:   A rule that must always hold, e.g. "stock never goes below zero". → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Inverted index**
:   A map from each term to the documents containing it; the core structure of full-text search. → [Ch 22 Search Systems](../03-production/22-search-systems.md)

**Isolation level**
:   How much concurrent transactions can see of each other (read committed, repeatable read, serializable). → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)


## J

**Jitter**
:   Randomness added to retry delays so clients don't retry in synchronized waves. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)

**Journey SLO**
:   A reliability objective for an end-to-end user journey (e.g. checkout), composed from the services it touches. → [Ch 56 Capstone: The Grocery Marketplace, End to End](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md)

**JWT**
:   JSON Web Token: a signed, self-contained token carrying claims about a subject. → [Ch 23 Security Architecture](../03-production/23-security-architecture.md)


## K

**Kafka partition**
:   An ordered, append-only log within a topic; the unit of ordering and parallelism. → [Ch 14 Messaging & Event Streaming](../02-distributed-core/14-messaging-and-event-streaming.md)


## L

**L4 / L7 load balancing**
:   Balancing on transport-level connections (L4) versus on application requests such as HTTP (L7). → [Ch 08 Load Balancing, Proxies & Gateways](../01-foundations/08-load-balancing-proxies-and-gateways.md)

**Lamport clock**
:   A logical counter that orders events consistently with causality. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Latency**
:   The time to complete one operation, reported as percentiles (p50, p99). → [Ch 01 What System Design Is](../00-orientation/01-what-system-design-is.md)

**Leader election**
:   Choosing one node to act as coordinator, typically through consensus or a lease. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Leader lease**
:   A time-limited leadership grant; a leader stops acting when its lease may have expired. → [Ch 50 Case Study: Distributed Job Scheduler](../05-case-studies/50-distributed-job-scheduler.md)

**Leaky bucket**
:   A rate limiter that drains requests at a constant rate, smoothing bursts. → [Ch 39 Case Study: Distributed Rate Limiter](../05-case-studies/39-distributed-rate-limiter.md)

**Lease**
:   A time-limited grant (e.g. a lock) that expires unless renewed. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Level expectations (B1–B4)**
:   What an interviewer expects at engineer, senior, staff and principal level in scope, depth and judgement. → [Ch 58 Level Expectations: From Engineer to Principal](../07-interview/58-level-expectations.md)

**Linearizability**
:   Every operation appears to take effect instantly at one point between its start and end, in real-time order. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Little's Law**
:   Items in a system = arrival rate × time in system (L = λW). → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)

**Load shedding**
:   Rejecting some requests early under overload so the rest succeed. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)

**LSM tree**
:   A log-structured merge tree: writes go to memory and immutable sorted files that are merged in the background. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)


## M

**Merkle tree**
:   A hash tree used to find differing ranges between replicas cheaply. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)

**Microservices**
:   Independently deployable services organised around business capabilities. → [Ch 16 Microservices Architecture](../02-distributed-core/16-microservices-architecture.md)

**Modular monolith**
:   One deployable with strictly enforced internal module boundaries. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**mTLS**
:   Mutual TLS: both client and server authenticate with certificates. → [Ch 23 Security Architecture](../03-production/23-security-architecture.md)

**Multi-tenancy**
:   One system serving many customers (tenants) with isolation of data and performance. → [Ch 30 Multi-Tenancy & SaaS Architecture](../03-production/30-multi-tenancy-and-saas-architecture.md)

**Multipart upload**
:   Uploading a large object in independently retried parts that are assembled at the end. → [Ch 52 Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)

**MVCC**
:   Multi-version concurrency control: readers see a snapshot while writers create new row versions. → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)


## N

**Noisy neighbor**
:   A tenant or workload consuming shared resources and degrading others. → [Ch 30 Multi-Tenancy & SaaS Architecture](../03-production/30-multi-tenancy-and-saas-architecture.md)


## O

**OAuth 2.0**
:   A delegation protocol for obtaining access tokens; OpenID Connect adds identity on top. → [Ch 23 Security Architecture](../03-production/23-security-architecture.md)

**Observability**
:   Understanding a system's internal state from its outputs: metrics, logs and traces. → [Ch 24 Observability](../03-production/24-observability.md)

**Optimistic locking**
:   Detecting conflicting updates with a version check at write time instead of locking upfront. → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)

**Orchestration**
:   A central coordinator tells services what to do and tracks the workflow. → [Ch 34 Modern Architecture Styles](../04-architect/34-modern-architecture-styles.md)

**Order book**
:   The list of resting buy and sell orders for an instrument, sorted by price and time. → [Ch 55 Case Study: Order Matching Engine](../05-case-studies/55-order-matching-engine.md)

**Outbox pattern**
:   Writing an event to an outbox table in the same transaction as the business change, then relaying it. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)


## P

**p99 / tail latency**
:   The latency under which 99% of requests complete; the slow tail users notice. → [Ch 25 Performance Engineering](../03-production/25-performance-engineering.md)

**PACELC**
:   Extends CAP: if Partitioned choose Availability or Consistency, Else choose Latency or Consistency. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Pagination (cursor / keyset)**
:   Returning results in pages using a position marker rather than an offset. → [Ch 04 API Design](../01-foundations/04-api-design.md)

**Paved road**
:   The supported, easiest path for building services, adopted because it saves effort. → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)

**Paxos**
:   A family of consensus protocols for agreeing on values despite failures. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**PCI DSS**
:   The card-industry security standard; scope shrinks when card data never touches your systems. → [Ch 36 Compliance, Governance & Data Residency](../04-architect/36-compliance-governance-and-data-residency.md)

**PITR (point-in-time recovery)**
:   Restoring a database to any moment by replaying archived logs onto a base backup. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Politeness (crawler)**
:   Limiting request rate per host so a crawler does not overload the sites it visits. → [Ch 53 Case Study: Web Crawler (Competitor Price Monitoring)](../05-case-studies/53-web-crawler.md)

**Polyglot persistence**
:   Using different datastores for different access patterns within one system. → [Ch 06 NoSQL & Polyglot Persistence](../01-foundations/06-nosql-and-polyglot-persistence.md)

**Postmortem (blameless)**
:   A written incident review focused on systemic causes and actions, not individual blame. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Price-time priority**
:   Matching rule: best price first, and among equal prices, the earliest order first. → [Ch 55 Case Study: Order Matching Engine](../05-case-studies/55-order-matching-engine.md)

**Priority lane**
:   A separate queue and worker pool for urgent traffic so bulk work cannot delay it. → [Ch 40 Case Study: Notification System](../05-case-studies/40-notification-system.md)

**Property-based testing**
:   Tests that check properties over many generated inputs, shrinking failures to minimal cases. → [Ch 29 Testing Distributed Systems](../03-production/29-testing-distributed-systems.md)

**Protobuf**
:   Protocol Buffers: a binary format whose field numbers enable backward-compatible evolution. → [Ch 28 Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)


## Q

**Quorum**
:   The minimum number of replicas (R, W) that must respond; R + W > N gives overlapping reads and writes. → [Ch 10 Replication](../02-distributed-core/10-replication.md)


## R

**Raft**
:   A consensus algorithm based on an elected leader replicating a log to a majority. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Rate limiting**
:   Capping how many requests a client may make per time window. → [Ch 39 Case Study: Distributed Rate Limiter](../05-case-studies/39-distributed-rate-limiter.md)

**Read model (materialized view)**
:   A query-optimised copy of data, kept up to date from events or changes, that serves reads in CQRS. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)

**Read replica**
:   A copy of the database that serves reads, usually asynchronously updated. → [Ch 10 Replication](../02-distributed-core/10-replication.md)

**Read-your-writes**
:   A guarantee that a user sees their own writes immediately. → [Ch 10 Replication](../02-distributed-core/10-replication.md)

**Reconciliation**
:   Comparing records between two systems (e.g. ledger vs settlement file) and resolving differences. → [Ch 45 Case Study: Payment System](../05-case-studies/45-payment-system.md)

**Reed-Solomon**
:   The erasure code used in many storage systems. → [Ch 52 Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)

**Replication lag**
:   How far a replica is behind the primary; bounds stale reads and data loss on failover. → [Ch 10 Replication](../02-distributed-core/10-replication.md)

**Reservation (inventory)**
:   Temporarily holding stock for a checkout until payment succeeds or the hold expires. → [Ch 46 Case Study: Grocery Checkout & Inventory Reservation](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md)

**Retry storm**
:   Retries from many clients amplifying load on an already failing service. → [Ch 18 Resilience Patterns](../02-distributed-core/18-resilience-patterns.md)

**Rightsizing**
:   Matching instance sizes to actual utilisation. → [Ch 35 Cost Engineering & FinOps](../04-architect/35-cost-engineering-and-finops.md)

**Robots.txt**
:   A site's file stating which paths crawlers may fetch; honouring it is part of polite crawling. → [Ch 53 Case Study: Web Crawler (Competitor Price Monitoring)](../05-case-studies/53-web-crawler.md)

**Row-Level Security (RLS)**
:   Database-enforced filtering of rows by tenant or user. → [Ch 30 Multi-Tenancy & SaaS Architecture](../03-production/30-multi-tenancy-and-saas-architecture.md)

**RPO / RTO**
:   Recovery Point Objective (acceptable data loss) and Recovery Time Objective (acceptable downtime). → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Rubric**
:   The scoring dimensions and anchors interviewers use to rate a system design answer. → [Ch 57 The System Design Interview Framework](../07-interview/57-the-system-design-interview-framework.md)


## S

**Saga**
:   A sequence of local transactions with compensating actions instead of a distributed commit. → [Ch 13 Distributed Transactions](../02-distributed-core/13-distributed-transactions.md)

**Saga compensation**
:   The undo step for a completed saga step, run when a later step fails. → [Ch 56 Capstone: The Grocery Marketplace, End to End](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md)

**Schema registry**
:   A service storing message schemas and enforcing compatibility rules. → [Ch 28 Schema & Data Evolution](../03-production/28-schema-and-data-evolution.md)

**Serializable**
:   The strongest isolation level: the result equals some serial order of the transactions. → [Ch 05 Relational Databases Deep Dive](../01-foundations/05-relational-databases-deep-dive.md)

**Service mesh**
:   A layer of sidecar proxies handling traffic, security and telemetry between services. → [Ch 17 Inter-Service Communication](../02-distributed-core/17-inter-service-communication.md)

**Settlement**
:   The payment provider transferring collected funds to the merchant, usually in daily batches with a report. → [Ch 45 Case Study: Payment System](../05-case-studies/45-payment-system.md)

**Sharding**
:   Splitting data across independent database nodes by a shard key. → [Ch 11 Partitioning & Sharding](../02-distributed-core/11-partitioning-and-sharding.md)

**Sidecar**
:   A helper process deployed next to each service instance (proxy, agent). → [Ch 17 Inter-Service Communication](../02-distributed-core/17-inter-service-communication.md)

**Signed URL (presigned URL)**
:   A time-limited URL that lets a client upload or download an object directly from storage. → [Ch 52 Case Study: Object Storage & Media Pipeline](../05-case-studies/52-object-storage-and-media-pipeline.md)

**SLI / SLO / SLA**
:   Indicator (what you measure), objective (the target), agreement (the contractual promise). → [Ch 24 Observability](../03-production/24-observability.md)

**Sliding window (rate limiting)**
:   Counting requests over a rolling time window rather than fixed buckets, avoiding boundary bursts. → [Ch 39 Case Study: Distributed Rate Limiter](../05-case-studies/39-distributed-rate-limiter.md)

**Sloppy quorum**
:   Accepting writes on any reachable nodes during failures, reconciled later. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)

**Snowflake ID**
:   A 64-bit, roughly time-ordered ID built from a timestamp, a worker id and a sequence number. → [Ch 20 Idempotency, IDs & Distributed Locks](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md)

**Spaced repetition**
:   Reviewing material at growing intervals, timed to just before it would be forgotten. → [Ch 59 Interview Q&A Bank](../07-interview/59-interview-qa-bank.md)

**Split brain**
:   Two nodes both believing they are the primary and accepting conflicting writes. → [Ch 27 Reliability & Disaster Recovery](../03-production/27-reliability-and-disaster-recovery.md)

**Spot instance**
:   Discounted spare cloud capacity that can be reclaimed at short notice. → [Ch 35 Cost Engineering & FinOps](../04-architect/35-cost-engineering-and-finops.md)

**SSTable**
:   Sorted String Table: an immutable sorted file of key-value pairs in an LSM tree. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)

**STAR**
:   Situation, Task, Action, Result — the standard structure for behavioural answers (this handbook adds Learning). → [Ch 61 Behavioural & Leadership](../07-interview/61-behavioral-and-leadership.md)

**Story bank**
:   A set of prepared, tagged career stories covering the competencies interviewers probe at your level. → [Ch 61 Behavioural & Leadership](../07-interview/61-behavioral-and-leadership.md)

**Strangler fig**
:   Migrating gradually by routing features one by one from the old system to the new. → [Ch 16 Microservices Architecture](../02-distributed-core/16-microservices-architecture.md)

**Structured concurrency**
:   Treating concurrent subtasks as one unit with a shared lifetime, cancellation and error handling. → [Ch 19 Concurrency at Scale in Java](../02-distributed-core/19-concurrency-at-scale-in-java.md)


## T

**Throughput**
:   Operations completed per unit time. → [Ch 01 What System Design Is](../00-orientation/01-what-system-design-is.md)

**Thundering herd**
:   Many waiting clients woken or retrying at once, overwhelming a resource. → [Ch 07 Caching](../01-foundations/07-caching.md)

**Token bucket**
:   A rate-limiting algorithm that refills tokens at a fixed rate and allows bursts up to bucket size. → [Ch 39 Case Study: Distributed Rate Limiter](../05-case-studies/39-distributed-rate-limiter.md)

**Top-K / heavy hitters**
:   The most frequent items in a stream, often estimated with sketches. → [Ch 51 Case Study: Real-time Analytics & Top-K (Trending)](../05-case-studies/51-real-time-analytics-and-top-k.md)

**Tracing (distributed)**
:   Following a request across services as a tree of timed spans. → [Ch 24 Observability](../03-production/24-observability.md)

**Transactional outbox**
:   See outbox pattern. → [Ch 15 Event-Driven Architecture, CQRS & Event Sourcing](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md)

**Trie**
:   A prefix tree; each path from the root spells a prefix, which makes prefix lookups fast for autocomplete. → [Ch 42 Case Study: Search Autocomplete (Typeahead)](../05-case-studies/42-search-autocomplete.md)

**TTL**
:   Time to live: how long a cached or stored item remains valid. → [Ch 07 Caching](../01-foundations/07-caching.md)

**Two-way door**
:   A reversible decision that can be made quickly; a one-way door deserves more care. → [Ch 31 Architecture Decision Making](../04-architect/31-architecture-decision-making.md)


## U

**Ubiquitous language**
:   The shared vocabulary of a bounded context, used in both conversation and code. → [Ch 33 Domain-Driven Design](../04-architect/33-domain-driven-design.md)

**Unit economics**
:   Cost and value per business unit (per order, per user). → [Ch 35 Cost Engineering & FinOps](../04-architect/35-cost-engineering-and-finops.md)

**Universal Scalability Law**
:   A model of throughput under contention and coherence costs, explaining why adding nodes can reduce throughput. → [Ch 25 Performance Engineering](../03-production/25-performance-engineering.md)


## V

**Vector clock**
:   Per-node counters that detect whether events are causally ordered or concurrent. → [Ch 12 Consistency, Time & Consensus](../02-distributed-core/12-consistency-time-and-consensus.md)

**Virtual node**
:   Many ring positions per physical node in consistent hashing, to even out load. → [Ch 11 Partitioning & Sharding](../02-distributed-core/11-partitioning-and-sharding.md)

**Virtual threads**
:   Lightweight JVM threads (Project Loom) that make blocking I/O cheap at high concurrency. → [Ch 19 Concurrency at Scale in Java](../02-distributed-core/19-concurrency-at-scale-in-java.md)


## W

**WAL (write-ahead log)**
:   Logging changes durably before applying them, so a crash can be recovered by replay. → [Ch 54 Case Study: Distributed Key-Value Store](../05-case-studies/54-distributed-key-value-store.md)

**Watermark**
:   In stream processing, an estimate that no older event times are still expected. → [Ch 21 Data at Scale](../03-production/21-data-at-scale.md)

**Webhook**
:   An HTTP callback from a provider notifying your system of an event. → [Ch 45 Case Study: Payment System](../05-case-studies/45-payment-system.md)

**Write-through / write-behind**
:   Caches that update the store synchronously on write, or asynchronously later. → [Ch 07 Caching](../01-foundations/07-caching.md)


## Z

**Zero-downtime deployment**
:   Releasing a new version without making the service unavailable. → [Ch 26 Deployment & Infrastructure](../03-production/26-deployment-and-infrastructure.md)

