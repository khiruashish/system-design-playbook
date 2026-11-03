# 11. Quorum Replication: R + W > N

<span class="level l3">L3</span> ★★★ · Tunable consistency, versioned values, read repair, availability

## Problem

> Simulate Dynamo-style replication with **N replicas**, write quorum **W** and read quorum **R**. Show that **R + W > N** gives
> reads the latest acknowledged write, that weaker settings can read stale data, and how failures affect availability.

## Key idea

When **R + W > N**, every read set **overlaps** every write set in at least one replica, so the highest-versioned value seen is the
latest acknowledged write. The read also **repairs** stale replicas it saw. With **R + W ≤ N** you get lower latency and higher
availability, but stale reads become possible. Each operation needs enough live replicas to reach its quorum.

```mermaid
flowchart LR
    subgraph W2["write v2 → W=2"]
        r1a[r1 ✓]
        r2a[r2 ✓]
    end
    subgraph R2["read → R=2"]
        r0b["r0 (v1, stale)"]
        r2b["r2 (v2)"]
    end
    r2a -. overlap .- r2b
    R2 --> NV["newest = v2; repair r0"]
```

## Solution

```java title="QuorumReplication.java"
--8<-- "coding-interview/src/QuorumReplication.java"
```

## Walkthrough

| Code | Why |
|---|---|
| `Versioned(value, ++clock)` | Each write carries a version, and reads pick the maximum. Real systems use per-key vector clocks or hybrid logical clocks, because one counter isn't available in a distributed cluster. |
| `for (int i : reachableOrder) ... if (++acks == w) return true` | The write succeeds once W replicas acknowledge. The rest are updated later (or not, as in the demo, which leaves r0 stale). |
| `if (v == null \|\| v.version() < newest.version()) { rep.data.put(key, newest); repairs.add(rep.name); }` | Read repair: the read path fixes the stale replicas it touched. |
| `N=3 W=1 R=1 → read v1` | The read set {r0} and write set {r1} don't overlap, so the read is stale. It's the cost of the fastest settings. |
| `2 of 3 down: write ok? false` | With W=2, two failures make writes unavailable. That's the CAP trade-off in a single line. |

## Complexity

| Approach | Time | Space |
|---|---|---|
| write / read | O(N) messages, waits for W / R | N copies |

## Run it

```bash
java coding-interview/src/QuorumReplication.java      # JDK 21+ single-file launcher, no build needed
```

```text
--8<-- "coding-interview/outputs/QuorumReplication.txt"
```

## Common mistakes

* Thinking R + W > N gives linearizability. It doesn't on its own: concurrent writes, sloppy quorums and clock-based versions can still misbehave.
* Last-write-wins with wall-clock timestamps, which silently drops concurrent updates. Use vector clocks plus application merge, or CRDTs.
* Forgetting that a failed quorum write may still have reached *some* replicas, so it may appear later. Clients must treat a failed write as "unknown", not "didn't happen".

## Follow-up questions

??? question "Sloppy quorum and hinted handoff?"
    If home replicas are down, a sloppy quorum writes to other healthy nodes, which keep a *hint* and hand the data back when the home replica returns. Availability goes up, but R + W > N no longer guarantees overlap with the home replicas.

??? question "How do Cassandra's consistency levels map to this?"
    `ONE`, `QUORUM`, `ALL` per query, plus `LOCAL_QUORUM` (a quorum within one data centre) for multi-region setups. A common choice is `LOCAL_QUORUM` for both reads and writes with RF = 3, which gives strong consistency within a DC and survives one node failure.
