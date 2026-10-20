# Lab Catalogue

!!! abstract "About this catalogue"
    Every chapter from 01 to 61 solves one problem at four levels (L1–L4) in its §5. This page lists all **244 labs** with their band, the programs they show, and what each needs to run.

    **How it's built.** `LabCatalog` (in `labs/appendix-tools/`) generates this page from the chapters. It detects the "Needs" column from the code itself: JDBC URLs, Redis clients, `HttpServer`/`HttpClient`, Spring and Testcontainers imports, and child-JVM launches.

    **What that detection means.** A lab marked "JDK only" runs with `java File.java`. A lab needing Spring or Testcontainers is a project-style example: it needs a build and, for Testcontainers, Docker.

    **Where the code lives.**

    - **Chapters 01–09:** the labs appear only in the chapter text.
    - **Chapters 10–61:** each also has a `labs/chNN-*` folder with the runnable programs, their recorded outputs and a README.

    **Services used when recording:**

    - PostgreSQL 16 on port 5581 (database `shop`);
    - Redis on port 6390;
    - JDK 21 on a 2-CPU sandbox.

    Adjust the connection settings in each program for your machine.

    **Totals by requirement** (a lab can need several): {HTTP (local)=33, JDK only=141, Kafka client=5, PostgreSQL=36, Redis=25, Spring=43, Testcontainers=14, child JVM=4}.

## Part 0 · Orientation

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [01](../00-orientation/01-what-system-design-is.md) | **L1** Concept (plain Java) | B1 | — | JDK only |
| [01](../00-orientation/01-what-system-design-is.md) | **L2** Spring Implementation (four layers) | B2 | — | Spring |
| [01](../00-orientation/01-what-system-design-is.md) | **L3** Production-Grade | B3 | — | PostgreSQL, Spring, Testcontainers |
| [01](../00-orientation/01-what-system-design-is.md) | **L4** Enterprise / Scale | B4 | — | HTTP (local), Spring, Kafka client |

## Part I · Foundations

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [02](../01-foundations/02-back-of-the-envelope-estimation.md) | **L1** Concept (plain Java) | B1 | — | JDK only |
| [02](../01-foundations/02-back-of-the-envelope-estimation.md) | **L2** Spring Implementation (four layers) | B2 | — | Spring |
| [02](../01-foundations/02-back-of-the-envelope-estimation.md) | **L3** Production-Grade Planner | B3 | — | Spring |
| [02](../01-foundations/02-back-of-the-envelope-estimation.md) | **L4** Enterprise: Plan from Real Traffic, Ship as GitOps | B4 | — | HTTP (local), Spring |
| [03](../01-foundations/03-networking-essentials.md) | **L1** Concept (plain JDK) | B1 | — | HTTP (local) |
| [03](../01-foundations/03-networking-essentials.md) | **L2** Spring Implementation (four layers) | B2 | — | Spring |
| [03](../01-foundations/03-networking-essentials.md) | **L3** Production-Grade (one pod) | B3 | — | HTTP (local), Spring |
| [03](../01-foundations/03-networking-essentials.md) | **L4** Enterprise: Many Pods, Kafka → Redis Fan-out, No Stickiness | B4 | — | Redis, Spring, Testcontainers |
| [04](../01-foundations/04-api-design.md) | **L1** Concept (plain Java) | B1 | — | JDK only |
| [04](../01-foundations/04-api-design.md) | **L2** Spring Implementation (four layers) | B2 | — | Spring |
| [04](../01-foundations/04-api-design.md) | **L3** Production-Grade | B3 | — | HTTP (local), Spring, Testcontainers |
| [04](../01-foundations/04-api-design.md) | **L4** Enterprise: Contract-First, Governed Evolution, Signed Webhooks | B4 | — | Spring |
| [05](../01-foundations/05-relational-databases-deep-dive.md) | **L1** Concept (plain Java) | B1 | — | JDK only |
| [05](../01-foundations/05-relational-databases-deep-dive.md) | **L2** Spring Data JPA, the "usual" way | B2 | — | Spring, Testcontainers |
| [05](../01-foundations/05-relational-databases-deep-dive.md) | **L3** Production-Grade PostgreSQL | B3 | — | PostgreSQL, Spring, Testcontainers |
| [05](../01-foundations/05-relational-databases-deep-dive.md) | **L4** Enterprise: Replica Routing with Read-Your-Writes, Partitioning, Online Migrations | B4 | — | Spring |
| [06](../01-foundations/06-nosql-and-polyglot-persistence.md) | **L1** Concept: a mini LSM tree (plain Java) | B1 | — | JDK only |
| [06](../01-foundations/06-nosql-and-polyglot-persistence.md) | **L2** Spring Data Redis: latest position + nearby riders | B2 | — | Redis, Spring, Testcontainers |
| [06](../01-foundations/06-nosql-and-polyglot-persistence.md) | **L3** Production-Grade: atomic batched Redis + Cassandra history | B3 | — | Redis, Spring, Testcontainers |
| [06](../01-foundations/06-nosql-and-polyglot-persistence.md) | **L4** Enterprise: Kafka as the log, independent idempotent sinks | B4 | — | Spring, Kafka client |
| [07](../01-foundations/07-caching.md) | **L1** Concept (plain Java) | B1 | — | JDK only |
| [07](../01-foundations/07-caching.md) | **L2** Spring Cache + Redis | B2 | — | Spring, Testcontainers |
| [07](../01-foundations/07-caching.md) | **L3** Production-Grade: two-level cache, versioned writes, stampede control | B3 | — | Redis, Spring, Testcontainers |
| [07](../01-foundations/07-caching.md) | **L4** Enterprise: event-driven invalidation, hot keys, CDN, warming | B4 | — | Spring |
| [08](../01-foundations/08-load-balancing-proxies-and-gateways.md) | **L1** Concept: simulating balancing algorithms (plain Java) | B1 | — | JDK only |
| [08](../01-foundations/08-load-balancing-proxies-and-gateways.md) | **L2** Spring Cloud Gateway: routes, discovery, correlation | B2 | — | Spring |
| [08](../01-foundations/08-load-balancing-proxies-and-gateways.md) | **L3** Production-Grade Gateway | B3 | — | Redis, Spring, Testcontainers |
| [08](../01-foundations/08-load-balancing-proxies-and-gateways.md) | **L4** Enterprise: latency-aware P2C, outlier ejection, zones, canaries | B4 | — | Spring |
| [09](../01-foundations/09-scalability-fundamentals.md) | **L1** Concept: Amdahl, USL and fitting a load test (plain Java) | B1 | — | JDK only |
| [09](../01-foundations/09-scalability-fundamentals.md) | **L2** Stateless pods with externalized sessions | B2 | — | HTTP (local), Spring, Testcontainers |
| [09](../01-foundations/09-scalability-fundamentals.md) | **L3** Carts as first-class, atomically updated Redis state | B3 | — | Redis, Spring, Testcontainers |
| [09](../01-foundations/09-scalability-fundamentals.md) | **L4** Z-axis: carts partitioned by city, moved online | B4 | — | Redis, Spring |

## Part II · Distributed Core

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [10](../02-distributed-core/10-replication.md) | **L1** Concept: quorums, LWW and an OR-Set (plain Java) | B1 | — | JDK only |
| [10](../02-distributed-core/10-replication.md) | **L2** Session-consistent reads across several replicas | B2 | — | Spring |
| [10](../02-distributed-core/10-replication.md) | **L3** Quorum synchronous replication with per-transaction durability | B3 | — | PostgreSQL, Spring, Testcontainers |
| [10](../02-distributed-core/10-replication.md) | **L4** Failover without split brain, then reconcile | B4 | — | Spring |
| [11](../02-distributed-core/11-partitioning-and-sharding.md) | **L1** Concept: comparing partitioning schemes (plain Java) | B1 | — | JDK only |
| [11](../02-distributed-core/11-partitioning-and-sharding.md) | **L2** PostgreSQL declarative partitioning behind Spring | B2 | — | Spring |
| [11](../02-distributed-core/11-partitioning-and-sharding.md) | **L3** Application-level sharding with logical buckets | B3 | — | Redis, Spring |
| [11](../02-distributed-core/11-partitioning-and-sharding.md) | **L4** Online moves without lost writes, and hot-key splitting | B4 | — | JDK only |
| [12](../02-distributed-core/12-consistency-time-and-consensus.md) | **L1** Concept: wall, Lamport, vector and hybrid clocks (plain Java) | B1 | — | JDK only |
| [12](../02-distributed-core/12-consistency-time-and-consensus.md) | **L2** Checking linearizability, and keeping order status monotonic | B2 | — | Spring |
| [12](../02-distributed-core/12-consistency-time-and-consensus.md) | **L3** Raft from scratch, with faults and safety checks | B3 | — | JDK only |
| [12](../02-distributed-core/12-consistency-time-and-consensus.md) | **L4** Fenced leadership in production, and reads that are safe | B4 | — | Spring |
| [13](../02-distributed-core/13-distributed-transactions.md) | **L1** Concept: the dual-write problem (plain Java) | B1 | — | JDK only |
| [13](../02-distributed-core/13-distributed-transactions.md) | **L2** Transactional outbox, `SKIP LOCKED` relay and inbox consumer (real PostgreSQL) | B2 | — | PostgreSQL, Spring, Kafka client |
| [13](../02-distributed-core/13-distributed-transactions.md) | **L3** Orchestrated checkout saga across three databases (real PostgreSQL) | B3 | — | PostgreSQL |
| [13](../02-distributed-core/13-distributed-transactions.md) | **L4** TCC holds with expiry for a flash sale (real PostgreSQL) | B4 | — | PostgreSQL |
| [14](../02-distributed-core/14-messaging-and-event-streaming.md) | **L1** Concept: queue vs log (plain Java) | B1 | — | JDK only |
| [14](../02-distributed-core/14-messaging-and-event-streaming.md) | **L2** Designing a topic with the real Kafka client library | B2 | — | Spring, Kafka client |
| [14](../02-distributed-core/14-messaging-and-event-streaming.md) | **L3** Replication: which settings lose acknowledged events? (simulation) | B3 | — | JDK only |
| [14](../02-distributed-core/14-messaging-and-event-streaming.md) | **L4** Consumer semantics and poison messages | B3 | — | Redis, Spring, Kafka client |
| [15](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md) | **L1** Concept: three ways to share a change (plain Java) | B2 | — | JDK only |
| [15](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md) | **L2** An event store with optimistic concurrency (real PostgreSQL) | B3 | — | PostgreSQL, Spring |
| [15](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md) | **L3** Projections that don't skip events, and rebuilds (real PostgreSQL) | B3 | — | PostgreSQL, Spring |
| [15](../02-distributed-core/15-event-driven-architecture-cqrs-and-event-sourcing.md) | **L4** Snapshots and upcasting (real PostgreSQL) | B3 | — | PostgreSQL |
| [16](../02-distributed-core/16-microservices-architecture.md) | **L1** Concept: the arithmetic of splitting a request (plain Java) | B2 | — | JDK only |
| [16](../02-distributed-core/16-microservices-architecture.md) | **L2** Fitness functions for the service graph | B3 | — | JDK only |
| [16](../02-distributed-core/16-microservices-architecture.md) | **L3** A modular monolith with compiler-enforced boundaries (real `javac`) | B3 | — | JDK only |
| [16](../02-distributed-core/16-microservices-architecture.md) | **L4** Discovery, composition and the strangler fig over real HTTP | B3 | — | HTTP (local) |
| [17](../02-distributed-core/17-inter-service-communication.md) | **L1** Concept: Little's law with real threads | B2 | — | JDK only |
| [17](../02-distributed-core/17-inter-service-communication.md) | **L2** Timeouts, deadlines and retry placement over real HTTP | B3 | — | HTTP (local) |
| [17](../02-distributed-core/17-inter-service-communication.md) | **L3** REST vs gRPC payloads and schema evolution (real `protoc`) | B3 | — | JDK only |
| [17](../02-distributed-core/17-inter-service-communication.md) | **L4** Making it the default: Spring clients, deadlines, gRPC and the mesh | B3 | — | HTTP (local), Spring |
| [18](../02-distributed-core/18-resilience-patterns.md) | **L1** Why retries without jitter never recover (simulation) | B2 | — | JDK only |
| [18](../02-distributed-core/18-resilience-patterns.md) | **L2** A circuit breaker in front of a slowly failing dependency (real HTTP) | B3 | — | HTTP (local) |
| [18](../02-distributed-core/18-resilience-patterns.md) | **L3** Rate limiting algorithms and a global limit in Redis | B3 | — | Redis |
| [18](../02-distributed-core/18-resilience-patterns.md) | **L4** Load shedding under 2x overload (real HTTP) | B3 | — | HTTP (local), Spring |
| [19](../02-distributed-core/19-concurrency-at-scale-in-java.md) | **L1** Thread-per-request limits and pool sizing | B2 | `ThreadsL1.java` | JDK only |
| [19](../02-distributed-core/19-concurrency-at-scale-in-java.md) | **L2** Pinning (JDK 21 vs 25) and ThreadLocal vs ScopedValue | B3 | `PinningL2.java` `ThreadLocalL2.java` | JDK only |
| [19](../02-distributed-core/19-concurrency-at-scale-in-java.md) | **L3** Product page fan-out: sequential vs CompletableFuture vs StructuredTaskScope | B3 | `FanOutL3.java` | HTTP (local) |
| [19](../02-distributed-core/19-concurrency-at-scale-in-java.md) | **L4** Backpressure for tracking ingestion | B3 | `BackpressureL4.java` | JDK only |
| [20](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md) | **L1** An idempotent payment API | B2 | `IdempotencyL1.java` | PostgreSQL, HTTP (local) |
| [20](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md) | **L2** Idempotent consumers: naive vs Redis marker vs inbox | B2 | `ConsumerL2.java` | PostgreSQL, Redis |
| [20](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md) | **L3** ID schemes: index cost and generator properties | B3 | `IdsL3.java` | PostgreSQL |
| [20](../02-distributed-core/20-idempotency-ids-and-distributed-locks.md) | **L4** Distributed locks under pauses: TTL, fencing, advisory locks, CAS | B4 | `LocksL4.java` | PostgreSQL, Redis |

## Part III · Production

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [21](../03-production/21-data-at-scale.md) | **L1** Object storage over the S3 API, presigned URLs, multipart | B2 | `ObjectStoreL1.java` | HTTP (local) |
| [21](../03-production/21-data-at-scale.md) | **L2** A CDN edge: cache keys, coalescing, invalidation, stale-if-error | B3 | `CdnL2.java` | HTTP (local) |
| [21](../03-production/21-data-at-scale.md) | **L3** OLTP vs OLAP: ELT into a Parquet lake, PostgreSQL vs DuckDB | B3 | `exp-olap.sh` | JDK only |
| [21](../03-production/21-data-at-scale.md) | **L4** Restartable batch and event-time streaming | B4 | `BatchStreamL4.java` | PostgreSQL |
| [22](../03-production/22-search-systems.md) | **L1** Inverted index, BM25 and analyzers, measured | B2 | `SearchL1.java` | JDK only |
| [22](../03-production/22-search-systems.md) | **L2** Search inside PostgreSQL | B2 | `exp-pgsearch.sh` | JDK only |
| [22](../03-production/22-search-systems.md) | **L3** A real engine, kept in sync from PostgreSQL | B3 | `MeiliL3.java` | PostgreSQL, HTTP (local) |
| [22](../03-production/22-search-systems.md) | **L4** Vector search: HNSW from scratch, and hybrid retrieval | B4 | `VectorL4.java` | JDK only |
| [23](../03-production/23-security-architecture.md) | **L1** JWT validation, key rotation and refresh-token reuse detection | B2 | `TokensL1.java` | JDK only |
| [23](../03-production/23-security-architecture.md) | **L2** Login control conformance: OTP and OAuth Code + PKCE | B2 | `LoginL2.java` | HTTP (local) |
| [23](../03-production/23-security-architecture.md) | **L3** Authorization models: RBAC vs ABAC vs ReBAC, and IDOR | B3 | `AuthzL3.java` | JDK only |
| [23](../03-production/23-security-architecture.md) | **L4** Envelope encryption, rotation and crypto-shredding with a real KMS | B4 | `CryptoL4.java` | HTTP (local) |
| [24](../03-production/24-observability.md) | **L1** Structured logs, correlation IDs, sampling and cardinality | B2 | `LogsL1.java` | JDK only |
| [24](../03-production/24-observability.md) | **L2** RED metrics and histograms, scraped by a real Prometheus | B2 | `MetricsL2.java` | HTTP (local) |
| [24](../03-production/24-observability.md) | **L3** Distributed tracing: critical path, p99 driver, sampling | B3 | `TracingL3.java` | JDK only |
| [24](../03-production/24-observability.md) | **L4** SLOs, error budgets and burn-rate alerting | B4 | `SloL4.java` | JDK only |
| [25](../03-production/25-performance-engineering.md) | **L1** Tail latency: compounding, hedging, the queueing knee, coordinated omission | B2 | `TailL1.java` | JDK only |
| [25](../03-production/25-performance-engineering.md) | **L2** Garbage collectors on the same workload (real GC logs) | B3 | `GcWorkload.java` | JDK only |
| [25](../03-production/25-performance-engineering.md) | **L3** Profiling with JFR: find the hotspot, then fix it | B2 | `HotspotL3.java` | JDK only |
| [25](../03-production/25-performance-engineering.md) | **L4** Load testing, USL and capacity planning | B4 | `LoadL4.java` | JDK only |
| [26](../03-production/26-deployment-and-infrastructure.md) | **L1** Deployment strategies against real instances | B3 | `DeployL1.java` | HTTP (local) |
| [26](../03-production/26-deployment-and-infrastructure.md) | **L2** Health probes: readiness, liveness, startup | B2 | `HealthL2.java` | HTTP (local) |
| [26](../03-production/26-deployment-and-infrastructure.md) | **L3** Autoscaling as a control loop | B3 | `AutoscaleL3.java` | JDK only |
| [26](../03-production/26-deployment-and-infrastructure.md) | **L4** Container resource limits with real cgroups | B4 | `cg.sh` | JDK only |
| [27](../03-production/27-reliability-and-disaster-recovery.md) | **L1** Point-in-time recovery on real PostgreSQL | B3 | `exp-pitr.sh` | JDK only |
| [27](../03-production/27-reliability-and-disaster-recovery.md) | **L2** Streaming replication, RPO, and failover | B3 | `exp-replication.sh` | JDK only |
| [27](../03-production/27-reliability-and-disaster-recovery.md) | **L3** Chaos engineering: does resilience actually hold? | B3 | `ChaosL3.java` | HTTP (local) |
| [27](../03-production/27-reliability-and-disaster-recovery.md) | **L4** Restore drills and graceful degradation | B4 | `exp-restoredrill.sh` `DegradeL4b.java` | JDK only |
| [28](../03-production/28-schema-and-data-evolution.md) | **L1** Which DDL blocks a live table | B2 | `SchemaL1.java` | PostgreSQL |
| [28](../03-production/28-schema-and-data-evolution.md) | **L2** Zero-downtime column change: expand/contract | B3 | `ExpandL2.java` | PostgreSQL |
| [28](../03-production/28-schema-and-data-evolution.md) | **L3** Message schema compatibility with real protoc | B3 | `compat.py` | JDK only |
| [28](../03-production/28-schema-and-data-evolution.md) | **L4** Backfilling a huge table: one big UPDATE vs batched | B4 | `BackfillL4.java` | PostgreSQL |
| [29](../03-production/29-testing-distributed-systems.md) | **L1** Property-based testing that finds an edge case | B2 | `PropertyL1.java` | JDK only |
| [29](../03-production/29-testing-distributed-systems.md) | **L2** Consumer-driven contract testing | B3 | `ContractL2.java` | HTTP (local) |
| [29](../03-production/29-testing-distributed-systems.md) | **L3** Linearizability checking (the idea behind Jepsen) | B4 | `LinearL3.java` | JDK only |
| [29](../03-production/29-testing-distributed-systems.md) | **L4** Integration testing on real PostgreSQL, with fault injection | B3 | `IntegrationL4.java` | PostgreSQL, Testcontainers |
| [30](../03-production/30-multi-tenancy-and-saas-architecture.md) | **L1** The three data isolation models, and a real leak | B2 | `IsolationL1.java` | PostgreSQL |
| [30](../03-production/30-multi-tenancy-and-saas-architecture.md) | **L2** Row-Level Security makes the leak impossible | B3 | `RlsL2.java` | PostgreSQL |
| [30](../03-production/30-multi-tenancy-and-saas-architecture.md) | **L3** The noisy neighbor, and fair queuing | B3 | `NoisyL3.java` | JDK only |
| [30](../03-production/30-multi-tenancy-and-saas-architecture.md) | **L4** Per-tenant rate limiting and tiered plans | B3 | `exp-ratelimit.sh` | Redis |

## Part IV · Architect

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [31](../04-architect/31-architecture-decision-making.md) | **L1** Weighted trade-off matrix with sensitivity analysis | B3 | `DecisionL1.java` | JDK only |
| [31](../04-architect/31-architecture-decision-making.md) | **L2** Architecture fitness functions | B3 | `FitnessL2.java` | Spring |
| [31](../04-architect/31-architecture-decision-making.md) | **L3** Deciding with queueing math: does the design meet the SLO? | B4 | `BudgetL3.java` | JDK only |
| [31](../04-architect/31-architecture-decision-making.md) | **L4** Decision under uncertainty: Monte Carlo | B4 | `MonteCarloL4.java` | JDK only |
| [32](../04-architect/32-design-documents-and-architecture-communication.md) | **L1** A design-doc linter | B2 | `DocLintL1.java` | JDK only |
| [32](../04-architect/32-design-documents-and-architecture-communication.md) | **L2** C4 diagrams generated from one model | B3 | `C4ModelL2.java` | JDK only |
| [32](../04-architect/32-design-documents-and-architecture-communication.md) | **L3** Are the doc's numbers consistent with its own assumptions? | B3 | `EstimateL3.java` | JDK only |
| [32](../04-architect/32-design-documents-and-architecture-communication.md) | **L4** Detecting drift between the doc and the code | B4 | `src/checkout/CheckoutService.java` `DriftL4.java` | JDK only |
| [33](../04-architect/33-domain-driven-design.md) | **L1** Rich aggregate vs anemic model | B2 | `AggregateL1.java` | JDK only |
| [33](../04-architect/33-domain-driven-design.md) | **L2** Aggregate size is the unit of concurrency | B3 | `ContentionL2.java` | JDK only |
| [33](../04-architect/33-domain-driven-design.md) | **L3** An anti-corruption layer for the legacy ERP | B3 | `AclL3.java` | JDK only |
| [33](../04-architect/33-domain-driven-design.md) | **L4** Finding bounded contexts from usage | B4 | `ContextsL4.java` | JDK only |
| [34](../04-architect/34-modern-architecture-styles.md) | **L1** Hexagonal architecture: one domain, two sets of adapters | B2 | `HexagonalL1.java` | HTTP (local), Spring |
| [34](../04-architect/34-modern-architecture-styles.md) | **L2** What a module boundary costs when it becomes a network boundary | B3 | `HopsL2.java` | HTTP (local) |
| [34](../04-architect/34-modern-architecture-styles.md) | **L3** Cells and blast radius | B4 | `CellsL3.java` | JDK only |
| [34](../04-architect/34-modern-architecture-styles.md) | **L4** A real cell router, and moving a city between cells | B4 | `CellRouterL4.java` | HTTP (local) |
| [35](../04-architect/35-cost-engineering-and-finops.md) | **L1** Unit economics from a cloud bill | B2 | `UnitCostL1.java` | JDK only |
| [35](../04-architect/35-cost-engineering-and-finops.md) | **L2** Rightsizing and autoscaling, costed | B3 | `ScalingL2.java` | JDK only |
| [35](../04-architect/35-cost-engineering-and-finops.md) | **L3** How much capacity to commit to | B3 | `CommitL3.java` | JDK only |
| [35](../04-architect/35-cost-engineering-and-finops.md) | **L4** Storage and data transfer: measure, then rank | B3 | `StorageL4.java` | JDK only |
| [36](../04-architect/36-compliance-governance-and-data-residency.md) | **L1** The right to erasure, across every copy | B2 | `ErasureL1.java` | JDK only |
| [36](../04-architect/36-compliance-governance-and-data-residency.md) | **L2** Finding personal data in logs | B3 | `PiiScanL2.java` | JDK only |
| [36](../04-architect/36-compliance-governance-and-data-residency.md) | **L3** Residency and transfer rules as policy-as-code | B3 | `ResidencyL3.java` | JDK only |
| [36](../04-architect/36-compliance-governance-and-data-residency.md) | **L4** A tamper-evident audit log | B4 | `AuditLogL4.java` | JDK only |
| [37](../04-architect/37-leading-architecture-at-scale.md) | **L1** Conway's law, measured | B3 | `ConwayL1.java` | JDK only |
| [37](../04-architect/37-leading-architecture-at-scale.md) | **L2** Deriving teams from change data, within cognitive-load limits | B3 | `TeamsL2.java` | JDK only |
| [37](../04-architect/37-leading-architecture-at-scale.md) | **L3** The paved road, and the drift away from it | B3 | `PavedRoadL3.java` | JDK only |
| [37](../04-architect/37-leading-architecture-at-scale.md) | **L4** A fleet-wide migration, planned | B4 | `MigrationL4.java` | JDK only |

## Part V · Case Studies

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [38](../05-case-studies/38-url-shortener-and-short-links.md) | **L1** Four ways to mint a short code | B2 | `CodeGenL1.java` | JDK only |
| [38](../05-case-studies/38-url-shortener-and-short-links.md) | **L2** A real shortener on PostgreSQL | B2 | `ShortenerL2.java` | PostgreSQL, HTTP (local) |
| [38](../05-case-studies/38-url-shortener-and-short-links.md) | **L3** The read path: cache sizing and the campaign spike | B3 | `ReadPathL3.java` | JDK only |
| [38](../05-case-studies/38-url-shortener-and-short-links.md) | **L4** Counting clicks without hurting redirects | B3 | `AnalyticsL4.java` | PostgreSQL |
| [39](../05-case-studies/39-distributed-rate-limiter.md) | **L1** State per key, and how accurate it is | B2 | `LimiterStateL1.java` | Redis |
| [39](../05-case-studies/39-distributed-rate-limiter.md) | **L2** One global limit from eight nodes | B3 | `GlobalLimitL2.java` | Redis |
| [39](../05-case-studies/39-distributed-rate-limiter.md) | **L3** OTP SMS pumping: choosing the key | B3 | `OtpAbuseL3.java` | JDK only |
| [39](../05-case-studies/39-distributed-rate-limiter.md) | **L4** Regions, partitions and the limiter's own outage | B4 | `RegionsFailuresL4.java` | JDK only |
| [40](../05-case-studies/40-notification-system.md) | **L1** Priority lanes on a shared SMS pipe | B2 | `PriorityLanesL1.java` | JDK only |
| [40](../05-case-studies/40-notification-system.md) | **L2** Exactly-once for the customer, on real PostgreSQL | B3 | `DeliveryL2.java` | PostgreSQL |
| [40](../05-case-studies/40-notification-system.md) | **L3** Routing around a silently failing vendor | B3 | `VendorRoutingL3.java` | JDK only |
| [40](../05-case-studies/40-notification-system.md) | **L4** What not to send | B3 | `PolicyEngineL4.java` | JDK only |
| [41](../05-case-studies/41-chat-customer-rider-messaging.md) | **L1** Order, duplicates and gaps | B2 | `OrderingL1.java` | JDK only |
| [41](../05-case-studies/41-chat-customer-rider-messaging.md) | **L2** A real multi-node WebSocket chat | B3 | `WsChatL2.java` | PostgreSQL, Redis, HTTP (local) |
| [41](../05-case-studies/41-chat-customer-rider-messaging.md) | **L3** Presence that doesn't flap | B3 | `PresenceL3.java` | JDK only |
| [41](../05-case-studies/41-chat-customer-rider-messaging.md) | **L4** Keeping phone numbers out of the chat | B3 | `ContactLeakL4.java` | JDK only |
| [42](../05-case-studies/42-search-autocomplete.md) | **L1** The data structure | B2 | `TrieTopKL1.java` | JDK only |
| [42](../05-case-studies/42-search-autocomplete.md) | **L2** From query logs to suggestions | B3 | `SuggestPipelineL2.java` | JDK only |
| [42](../05-case-studies/42-search-autocomplete.md) | **L3** Keystroke economics | B2 | `KeystrokeL3.java` | JDK only |
| [42](../05-case-studies/42-search-autocomplete.md) | **L4** Personalisation and availability | B3 | `PersonalL4.java` | JDK only |
| [43](../05-case-studies/43-home-feed-and-recommendations.md) | **L1** Candidate generation | B2 | `World.java` `CandidatesL1.java` | JDK only |
| [43](../05-case-studies/43-home-feed-and-recommendations.md) | **L2** Learning to rank from biased logs | B3 | `RankingL2.java` | JDK only |
| [43](../05-case-studies/43-home-feed-and-recommendations.md) | **L3** The feature-fetch fan-out | B3 | `FeatureFetchL3.java` | Redis |
| [43](../05-case-studies/43-home-feed-and-recommendations.md) | **L4** Feedback loops and cold start | B4 | `ExploreL4.java` | JDK only |
| [44](../05-case-studies/44-distributed-cache.md) | **L1** Eviction policies | B2 | `EvictionL1.java` | JDK only |
| [44](../05-case-studies/44-distributed-cache.md) | **L2** Build a cache server, benchmark it like Redis | B3 | `MiniCacheL2.java` | Redis, child JVM |
| [44](../05-case-studies/44-distributed-cache.md) | **L3** Live resharding of a real Redis Cluster | B3 | `cluster-setup.sh` `ClusterL3.java` | Redis, child JVM |
| [44](../05-case-studies/44-distributed-cache.md) | **L4** What failover loses | B4 | `FailoverL4.java` | Redis, child JVM |
| [45](../05-case-studies/45-payment-system.md) | **L1** Money under concurrency | B2 | `LedgerL1.java` | PostgreSQL |
| [45](../05-case-studies/45-payment-system.md) | **L2** A real payment flow with webhooks | B3 | `PaymentFlowL2.java` | PostgreSQL, HTTP (local) |
| [45](../05-case-studies/45-payment-system.md) | **L3** Daily settlement reconciliation | B3 | `ReconcileL3.java` | JDK only |
| [45](../05-case-studies/45-payment-system.md) | **L4** Whose failure is it? | B4 | `RoutingL4.java` | JDK only |
| [46](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md) | **L1** The ₹1 onion deal | B3 | `HotSkuL1.java` | PostgreSQL, Redis |
| [46](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md) | **L2** How long to hold stock | B3 | `ReservationTtlL2.java` | JDK only |
| [46](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md) | **L3** Phantom stock | B3 | `PhantomStockL3.java` | JDK only |
| [46](../05-case-studies/46-grocery-checkout-and-inventory-reservation.md) | **L4** The 8 pm crowd, end to end | B4 | `WaitingRoomL4.java` | PostgreSQL, HTTP (local) |
| [47](../05-case-studies/47-delivery-dispatch-and-live-tracking.md) | **L1** Geo indexes | B2 | `GeoL1.java` | Redis |
| [47](../05-case-studies/47-delivery-dispatch-and-live-tracking.md) | **L2** GPS ingestion | B3 | `TelemetryL2.java` | Redis |
| [47](../05-case-studies/47-delivery-dispatch-and-live-tracking.md) | **L3** Dispatch | B4 | `DispatchL3.java` | JDK only |
| [47](../05-case-studies/47-delivery-dispatch-and-live-tracking.md) | **L4** ETAs | B3 | `EtaL4.java` | JDK only |
| [48](../05-case-studies/48-delivery-slot-booking-and-capacity.md) | **L1** What "full" means | B2 | `SlotCapacityL1.java` | JDK only |
| [48](../05-case-studies/48-delivery-slot-booking-and-capacity.md) | **L2** Booking under contention | B3 | `SlotBookingL2.java` | PostgreSQL |
| [48](../05-case-studies/48-delivery-slot-booking-and-capacity.md) | **L3** The slot as an event-sourced aggregate | B4 | `SlotEventsL3.java` | PostgreSQL |
| [48](../05-case-studies/48-delivery-slot-booking-and-capacity.md) | **L4** Moving demand to where the riders are | B3 | `DemandShapingL4.java` | JDK only |
| [49](../05-case-studies/49-pricing-promotions-and-coupons.md) | **L1** Money is integers | B2 | `MoneyL1.java` | JDK only |
| [49](../05-case-studies/49-pricing-promotions-and-coupons.md) | **L2** The promotion engine | B3 | `PromoEngineL2.java` | JDK only |
| [49](../05-case-studies/49-pricing-promotions-and-coupons.md) | **L3** Budget pacing and incrementality | B4 | `BudgetPacingL3.java` | JDK only |
| [49](../05-case-studies/49-pricing-promotions-and-coupons.md) | **L4** Markdowns for perishables | B3 | `MarkdownL4.java` | JDK only |
| [50](../05-case-studies/50-distributed-job-scheduler.md) | **L1** Run once, really | B3 | `LeaderLockL1.java` | PostgreSQL, Redis |
| [50](../05-case-studies/50-distributed-job-scheduler.md) | **L2** Firing delayed jobs on time | B3 | `DueJobsL2.java` | PostgreSQL |
| [50](../05-case-studies/50-distributed-job-scheduler.md) | **L3** Pipelines as DAGs | B3 | `DagL3.java` | JDK only |
| [50](../05-case-studies/50-distributed-job-scheduler.md) | **L4** Fair sharing of a worker fleet | B4 | `FairnessL4.java` | JDK only |
| [51](../05-case-studies/51-real-time-analytics-and-top-k.md) | **L1** Heavy hitters with bounded memory | B2 | `HeavyHittersL1.java` | JDK only |
| [51](../05-case-studies/51-real-time-analytics-and-top-k.md) | **L2** Merging top-K across partitions | B3 | `MergeTopKL2.java` | JDK only |
| [51](../05-case-studies/51-real-time-analytics-and-top-k.md) | **L3** Unique customers: sets vs HyperLogLog | B3 | `UniquesL3.java` | Redis |
| [51](../05-case-studies/51-real-time-analytics-and-top-k.md) | **L4** Trending vs popular | B4 | `TrendingL4.java` | JDK only |
| [52](../05-case-studies/52-object-storage-and-media-pipeline.md) | **L1** Erasure coding and durability | B3 | `ErasureL1.java` | JDK only |
| [52](../05-case-studies/52-object-storage-and-media-pipeline.md) | **L2** Uploads over a flaky network | B3 | `UploadL2.java` | HTTP (local) |
| [52](../05-case-studies/52-object-storage-and-media-pipeline.md) | **L3** Image variants: real transforms, modelled traffic | B2 | `ImageVariantsL3.java` | JDK only |
| [52](../05-case-studies/52-object-storage-and-media-pipeline.md) | **L4** CDN freshness: TTLs, purges, versioned URLs | B4 | `CdnL4.java` | JDK only |
| [53](../05-case-studies/53-web-crawler.md) | **L1** A polite frontier | B3 | `FrontierL1.java` | HTTP (local) |
| [53](../05-case-studies/53-web-crawler.md) | **L2** URL dedup at scale | B2 | `DedupL2.java` | JDK only |
| [53](../05-case-studies/53-web-crawler.md) | **L3** Re-crawl scheduling | B4 | `RecrawlL3.java` | JDK only |
| [53](../05-case-studies/53-web-crawler.md) | **L4** Traps, robots.txt and sitemaps | B3 | `TrapsL4.java` | JDK only |
| [54](../05-case-studies/54-distributed-key-value-store.md) | **L1** Storage engines on real files | B2 | `LsmL1.java` | JDK only |
| [54](../05-case-studies/54-distributed-key-value-store.md) | **L2** What an acknowledgement survives | B3 | `WalL2.java` | child JVM |
| [54](../05-case-studies/54-distributed-key-value-store.md) | **L3** Merkle anti-entropy | B3 | `MerkleL3.java` | JDK only |
| [54](../05-case-studies/54-distributed-key-value-store.md) | **L4** Consistency levels and tail latency | B4 | `QuorumL4.java` | JDK only |
| [55](../05-case-studies/55-order-matching-engine.md) | **L1** Order books | B3 | `OrderBookL1.java` | JDK only |
| [55](../05-case-studies/55-order-matching-engine.md) | **L2** Determinism through sequencing | B4 | `DeterminismL2.java` | JDK only |
| [55](../05-case-studies/55-order-matching-engine.md) | **L3** Where the credit check runs | B4 | `RiskL3.java` | JDK only |
| [55](../05-case-studies/55-order-matching-engine.md) | **L4** Market data that can't lie silently | B4 | `MarketDataL4.java` | JDK only |

## Part VI · Capstone

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [56](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md) | **L1** The place-order latency budget | B3 | `LatencyBudgetL1.java` | JDK only |
| [56](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md) | **L2** The checkout under failure, end to end | B4 | `CheckoutSagaL2.java` | PostgreSQL, HTTP (local) |
| [56](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md) | **L3** Blast radius on the service map | B4 | `BlastRadiusL3.java` | JDK only |
| [56](../06-capstone/56-capstone-grocery-marketplace-end-to-end.md) | **L4** SLO composition and cells | B4 | `SloCompositionL4.java` | JDK only |

## Part VII · Interview

| Ch | Lab | Band | Programs | Needs |
|---|---|---|---|---|
| [57](../07-interview/57-the-system-design-interview-framework.md) | **L1** How precise must estimates be? | B2 | `EstimateL1.java` | JDK only |
| [57](../07-interview/57-the-system-design-interview-framework.md) | **L2** Robust choice or close call? | B3 | `TradeoffL2.java` | JDK only |
| [57](../07-interview/57-the-system-design-interview-framework.md) | **L3** The value of the first questions | B3 | `ClarifyL3.java` | JDK only |
| [57](../07-interview/57-the-system-design-interview-framework.md) | **L4** What the rubric rewards | B4 | `RubricL4.java` | JDK only |
| [58](../07-interview/58-level-expectations.md) | **L1** The B1 answer: in-memory dedup | B1 | — | JDK only |
| [58](../07-interview/58-level-expectations.md) | **L2** The B2 answer: dedup in the same transaction | B2 | — | JDK only |
| [58](../07-interview/58-level-expectations.md) | **L3** The B3 answer: the domain's state machine | B3 | — | JDK only |
| [58](../07-interview/58-level-expectations.md) | **L4** The B4 answer: reconciliation | B4 | — | JDK only |
| [59](../07-interview/59-interview-qa-bank.md) | **L1** Generate the bank from the chapters | B2 | `QaBankL1.java` | JDK only |
| [59](../07-interview/59-interview-qa-bank.md) | **L2** Coverage by part and level | B2 | — | JDK only |
| [59](../07-interview/59-interview-qa-bank.md) | **L3** A daily drill | B3 | `DrillL3.java` | JDK only |
| [59](../07-interview/59-interview-qa-bank.md) | **L4** Study plan: cram, cycle or space? | B4 | `SpacedL4.java` | JDK only |
| [60](../07-interview/60-mock-interviews.md) | **L1** Where did the 45 minutes go? | B2 | `PacingL1.java` | JDK only |
| [60](../07-interview/60-mock-interviews.md) | **L2** Should you trust your peer's verdict? | B3 | `CalibrationL2.java` | JDK only |
| [60](../07-interview/60-mock-interviews.md) | **L3** Check a claim before you make it | B3 | `ClaimCheckL3.java` | PostgreSQL |
| [60](../07-interview/60-mock-interviews.md) | **L4** Am I actually improving? | B4 | `ProgressL4.java` | JDK only |
| [61](../07-interview/61-behavioral-and-leadership.md) | **L1** Does the story bank cover the level? | B2 | `StoryBankL1.java` | JDK only |
| [61](../07-interview/61-behavioral-and-leadership.md) | **L2** Lint the stories | B2 | `StoryLintL2.java` | JDK only |
| [61](../07-interview/61-behavioral-and-leadership.md) | **L3** Evidence behind an impact number | B3 | `ImpactL3.java` | PostgreSQL |
| [61](../07-interview/61-behavioral-and-leadership.md) | **L4** Plan the loop | B3 | `LoopPlanL4.java` | JDK only |
