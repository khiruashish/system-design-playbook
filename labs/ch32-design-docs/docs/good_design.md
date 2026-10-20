# Design: Checkout Service v2

## Context
Checkout p99 is 1.8 s at 300 req/s peak; 4% of carts abandon at payment. Drivers: latency, flash-sale capacity.

## Goals
- Checkout p99 < 300 ms at 800 req/s peak (festival load).
- Zero oversell during flash sales.

## Non-Goals
- Replacing the payment provider (Razorpay stays).

## Requirements
- Availability 99.95% monthly for POST /checkout.
- Throughput 800 req/s sustained, 1,200 req/s for 10 min bursts.
- RPO 0 for orders, RTO 15 min.

## Proposed Design
Fan out catalog, inventory, pricing and promotions in parallel (p99 budget 111 ms, see estimation appendix).
Inventory reservation with a 10 min TTL in PostgreSQL, idempotency keys on POST /checkout.

## Alternatives Considered
1. Keep the sequential chain (p99 334 ms at 800 req/s, breaches SLO).
2. Parallel fan-out (chosen).
3. Precomputed cart snapshot in Redis (rejected: stale prices during flash sales).

## Trade-offs and Risks
- Fan-out raises peak concurrency on downstreams by 4x; mitigated with bulkheads.

## Rollout
Shadow traffic 1 week, then canary 5% → 25% → 100% with automatic rollback on p99 > 300 ms.

## Observability
RED metrics per downstream call, trace per checkout, SLO burn-rate alerts at 2%/1h.

## Security
Idempotency keys scoped per user; no card data touches the service (PCI scope unchanged).

## Open Questions
- Should promotions have a 150 ms timeout with a cached fallback? Owner: pricing team, due 2026-10-10.

## Decision Record
ADR-051 (parallel fan-out). Revisit when sustained load exceeds 1,500 req/s.
