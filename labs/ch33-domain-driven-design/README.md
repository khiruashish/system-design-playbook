# Chapter 33 Labs — Domain-Driven Design

Self-contained JDK 21 programs, deterministic output. Run from this directory with `-Dstdout.encoding=UTF-8`.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java AggregateL1.java` | aggregate_out.txt | 9 hostile operations: anemic Order invalid after 8/9 (ends at −₹4,999.01 vs ₹1,455 subtotal); rich aggregate valid after 9/9, idempotent retried cancel |
| L2 | `java ContentionL2.java` | contention_out.txt | 20k reservations, Zipf SKUs, 16 in flight: store-wide aggregate 16.0x ideal (93.7% wasted), per-SKU 3.2x, per-SKU + hot-SKU buckets 1.1x |
| L3 | `java AclL3.java legacy_erp_feed.txt` | acl_out.txt | ERP feed: naive mapping wrong product in 9/12 rows (units, ₹78.85→₹78.84, discontinued sellable, blank HSN); ACL 0/12 — 8 translated, 4 quarantined |
| L4 | `java ContextsL4.java` | contexts_out.txt | Use-case/entity affinity: nouns split 78.9% cross-context; single-linkage chains into a blob; modularity finds Catalog + Fulfilment (Address → DeliveryLocation) but also a call-pattern artifact |

JPA/Spring snippets in the chapter are marked VERIFY.
