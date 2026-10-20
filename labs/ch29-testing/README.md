# Chapter 29 Labs — Testing Distributed Systems

L1–L3 are pure JDK 21 (no dependencies). L4 needs **real PostgreSQL 16** on :5581 (db `shop`) and `/tmp/pgjdbc.jar`.
All labs pass `-Duser.timezone=UTC` (the JDBC driver otherwise sends an unrecognized TZ) and `-Dstdout.encoding=UTF-8`.

## L1 — Property-based testing (`PropertyL1.java` → `property_out.txt`)
A from-scratch property tester + shrinker. Property: "split parts sum to the total." Finds the naive splitter's
paise-loss bug and shrinks it to the minimal `total=1, n=2`.
```bash
java -Dstdout.encoding=UTF-8 PropertyL1.java
```

## L2 — Consumer-driven contract testing (`ContractL2.java` → `contract_out.txt`)
Pact-style: consumer publishes a contract; the provider is verified over real HTTP with no consumer running.
v1 passes; v2 (renamed `total_paise`→`amount`, dropped `currency`) fails, naming the missing fields.
```bash
java -Dstdout.encoding=UTF-8 ContractL2.java
```

## L3 — Linearizability checking (`LinearL3.java` → `linear_out.txt`)
The core of Jepsen/Knossos: real-time-aware search for a valid sequential order over a recorded history.
Accepts a correct register history; rejects a stale read and a lost update.
```bash
java -Dstdout.encoding=UTF-8 LinearL3.java
```

## L4 — Integration testing on real PostgreSQL (`IntegrationL4.java` → `integration_out.txt`)
Rollback isolation; catches a missing `UNIQUE` constraint (fails as shipped, passes after the migration);
fault injection via `pg_terminate_backend` mid-transaction (asserts clean failure, no partial write).
The "1 failed" in the output is the deliberately-caught missing-constraint bug.
```bash
# setup PostgreSQL 16 on :5581 as an unprivileged user, create db `shop`, then:
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp /tmp/pgjdbc.jar IntegrationL4.java
```

**VERIFY (not run here):** jqwik (L1), Pact + broker/`can-i-deploy` (L2), Jepsen/Knossos/Elle under a nemesis (L3),
Testcontainers (L4) — the framework equivalents named in the chapter; the mechanics they wrap are what these labs demonstrate.
