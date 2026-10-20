# Chapter 36 Labs — Compliance, Governance & Data Residency

JDK 21, no dependencies. Real JDK crypto (AES-256-GCM, SHA-256) and real checksum algorithms (Verhoeff, Luhn).
**All identifiers are synthetic** (generated checksum-valid Aadhaar-format numbers, card networks' public TEST numbers, invented
names/phones/emails). L3's rules are an illustrative engineering encoding — **not legal advice**.
Run with `-Dstdout.encoding=UTF-8`. Output is deterministic except L4 timings.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java ErasureL1.java` | erasure_out.txt | Primary-only delete: 5 readable copies; delete-where-remembered: 2 (backup, warehouse); crypto-shredding: 0; GST invoice kept under legal-retention key; other customer intact |
| L2 | `java PiiScanL2.java` | piiscan_out.txt | 20k log lines: format-only precision Aadhaar 1.9% / card 0.9%; checksums 16.4% / 97.8%; key-aware 100% at 100% recall; redaction leaves 0 PII, destroys 0 order ids |
| L3 | `java ResidencyL3.java` | residency_out.txt | 10 flows × 5 rules: 5 blocked (SG DR with payment data, card numbers in logs, ad targeting without purpose, US fraud vendor storing payment data, 36-month invoice retention); full DR replica only Mumbai/Hyderabad |
| L4 | `java AuditLogL4.java` | auditlog_out.txt | 103,500 hash-chained events: chain catches edit/delete/reorder, misses delete-and-recompute; external anchors catch it; neither catches deletion of the 3,500 unanchored entries |
