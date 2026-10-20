# Chapter 28 Labs — Schema & Data Evolution

All labs run against **real PostgreSQL 16** or **real protoc 28.3**. Outputs in `*_out.txt` are verbatim.

## Setup

```bash
./setup.sh          # starts PostgreSQL 16 on :5581, database `shop` (lock_timeout=8s)
# pgjdbc driver expected at /tmp/pgjdbc.jar ; protoc at /opt/protoc/bin/protoc
```

## L1 — Which DDL blocks (`SchemaL1.java` → `schema_out.txt`)
2,000,000-row table, 8-thread SELECT/UPDATE workload. Measures the worst stall live queries suffer during five DDL ops.
```bash
java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar SchemaL1.java
```
Result: volatile-default ADD COLUMN stalls ~1.8 s (rewrite); constant-default add is metadata-only (~14 ms); naive
SET NOT NULL stalls to the 8 s lock_timeout; CREATE INDEX blocks writes; CREATE INDEX CONCURRENTLY does not.

## L2 — Expand/contract, zero downtime (`ExpandL2.java` → `expand_out.txt`)
Renames + retypes `total_paise` (bigint) → `amount_rupees` (numeric) in six phases with a live writer + reader.
```bash
java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar ExpandL2.java
```
Result: zero errors, zero old-vs-new mismatches through expand → dual-write → backfill → validate → read-new → contract.

## L3 — Message schema compatibility (`proto/` → `compat_out.txt`)
Real protoc encode/decode round-trips + a descriptor-based compatibility checker (BACKWARD/FORWARD/FULL).
```bash
cd proto
protoc --encode=shop.Order order_v2.proto <<< 'id: 42
sku: "SKU7"
total_paise: 129900
currency: "INR"
tags: "fresh"
tags: "veg"' > order_v2.bin
protoc --decode=shop.Order order_v1.proto < order_v2.bin      # forward compat
for v in v1 v2 v3_bad; do protoc --descriptor_set_out=order_$v.desc order_$v.proto; done
python3 compat.py                                             # registry-style verdicts
```
Result: v1→v2 (added fields) = FULL (accepted); v1→v3 (reused field #3, moved sku) = INCOMPATIBLE (rejected).

## L4 — Large-table backfill (`BackfillL4.java` → `backfill_out.txt`)
Backfills 3,000,000 rows one big UPDATE vs 25k-row committed batches, with a live writer recording its worst wait.
```bash
java -Duser.timezone=UTC -cp /tmp/pgjdbc.jar BackfillL4.java
```
Result: single UPDATE blocks the writer until the 8 s lock_timeout; batched form caps it at ~100 ms, same end result.

**VERIFY:** the Confluent Schema Registry / Kafka serializer snippets in the chapter need a running registry and are not
executed here; the compatibility *rules* they rely on are exactly what `compat.py` demonstrates on real descriptor sets.
