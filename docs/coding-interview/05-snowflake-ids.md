# 5. Snowflake: Distributed Unique Ids

<span class="level l2">L2</span> ★★☆ · Bit layout, sequence per millisecond, clock-backwards handling

## Problem

> Generate **unique, roughly time-ordered 64-bit ids** on many machines with no coordination per id. Handle 4,096+ ids
> in the same millisecond, and the clock **moving backwards** (an NTP step).

## Key idea

Pack **41 bits of milliseconds** since a custom epoch (about 69 years), **10 bits of machine id** (1,024 nodes) and **12 bits of sequence**
(4,096 per ms per node) into one `long`. Ids sort by time, are unique as long as machine ids are unique, and fit B-tree indexes better
than random UUIDs. If the clock goes backwards, **refuse** to generate (or wait) rather than risk duplicates.

```mermaid
flowchart LR
    A["0"] --- B["41 bits: ms since 2023-11-14"] --- C["10 bits: machine 7"] --- D["12 bits: sequence 0..4095"]
```

## Solution

```java title="SnowflakeId.java"
--8<-- "coding-interview/src/SnowflakeId.java"
```

## Walkthrough

| Code | Why |
|---|---|
| `((now - EPOCH) << 22) \| (machineId << 12) \| sequence` | The layout. Time is in the high bits, so numeric order roughly equals time order. |
| `sequence = (sequence + 1) & MAX_SEQUENCE; if (sequence == 0) wait for next ms` | Within one millisecond, increment. If all 4,096 are used, spin until the next millisecond. |
| `if (now < lastMs) throw ...` | Clock went backwards (an NTP step, VM migration). Generating anyway could repeat ids. Options: refuse, wait it out for small skews, or use a logical clock. |
| `LongSupplier clock` | An injected clock lets the test produce exact ids and simulate an NTP step. The 100k-id concurrency test uses the real clock. |

## Complexity

| Approach | Time | Space |
|---|---|---|
| next() | O(1) (synchronized) | O(1) |

## Run it

```bash
java coding-interview/src/SnowflakeId.java      # JDK 21+ single-file launcher, no build needed
```

```text
--8<-- "coding-interview/outputs/SnowflakeId.txt"
```

## Common mistakes

* Duplicate machine ids after auto-scaling. Assign them from ZooKeeper or etcd, a DB lease, or the pod's ordinal in a StatefulSet.
* Ignoring clock-backwards events, which produce silent duplicates.
* Assuming ids are *strictly* time-ordered across machines. They're only roughly ordered (clock skew), so don't use them as an event-ordering guarantee.
* Exposing them publicly when the business volume is sensitive. Ids reveal creation time and give a rough rate.

## Follow-up questions

??? question "Snowflake vs UUIDv4 vs UUIDv7 vs DB sequences?"
    UUIDv4: no coordination, but random, so it fragments B-tree indexes, and it's 128-bit. UUIDv7: time-ordered 128-bit, standardised, a good default today. DB sequences: simple, but a bottleneck across regions. Snowflake: compact 64-bit and time-ordered, but needs machine-id management.

??? question "How do you get more than 4,096 ids per ms per node?"
    Reallocate bits (fewer machine bits, more sequence bits), run more generators per node with distinct machine ids, or batch-allocate ids to callers.
