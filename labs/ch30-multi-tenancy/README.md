# Chapter 30 Labs — Multi-Tenancy & SaaS Architecture

L1/L2 need **real PostgreSQL 16** on :5581 (db `shop`) + `/tmp/pgjdbc.jar`. L4 needs **real Redis** on :6390. L3 is pure JDK.
JDBC labs use `-Duser.timezone=UTC` (PG 16 rejects the JVM's Asia/Calcutta) and `-Dstdout.encoding=UTF-8`.

## Setup
```bash
# PostgreSQL 16 as an unprivileged user (initdb refuses root), port 5581, db shop:
su claude -c "/usr/lib/postgresql/16/bin/initdb -D /tmp/tenantlab/pgdata -U postgres -A trust"
# append: port=5581, listen_addresses='localhost', unix_socket_directories='/tmp/tenantlab', lock_timeout=8000, fsync=off
su claude -c "/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/tenantlab/pgdata -l /tmp/tenantlab/pg.log start"
su claude -c "/usr/lib/postgresql/16/bin/psql -h localhost -p 5581 -U postgres -c 'create database shop'"
redis-server --port 6390 --daemonize yes --save '' --appendonly no
```

## L1 — Data isolation models (`IsolationL1.java` → `isolation_out.txt`)
Shared-table / schema-per-tenant / database-per-tenant on real PG. The shared table leaks all 9 rows across 3 tenants
when a query forgets `WHERE tenant_id=?`; the scoped models make the same query harmless.
```bash
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp /tmp/pgjdbc.jar IsolationL1.java
```

## L2 — Row-Level Security (`RlsL2.java` → `rls_out.txt`)
Real RLS policy keyed to `app.tenant`. The L1 leak query returns only 3 rows; a cross-tenant UPDATE hits 0 rows;
no-tenant-set returns 0 (fails closed). NOTE: the app connects as a limited role `app_user` — superusers/owners
BYPASS RLS (connecting as `postgres` leaks). On pooled connections use `SET LOCAL app.tenant` inside a transaction.
```bash
java -Dstdout.encoding=UTF-8 -Duser.timezone=UTC -cp /tmp/pgjdbc.jar RlsL2.java
```

## L3 — Noisy neighbor & fair queuing (`NoisyL3.java` → `noisy_out.txt`)
Deterministic single-worker simulation. FIFO: quiet tenants wait ~224 ticks behind a 300-task burst.
Per-tenant round-robin fair queuing: quiet-tenant wait ~0; noisy tenant just drains its own burst.
```bash
java -Dstdout.encoding=UTF-8 NoisyL3.java
```

## L4 — Per-tenant rate limiting & tiers (`token_bucket.lua` + `exp-ratelimit.sh` → `ratelimit_out.txt`)
Atomic Redis token bucket (server-side Lua). free/pro/enterprise = capacity 5/20/200 → 5/20/30 of a 30-request burst
allowed; per-tenant usage meter for billing; free bucket refills ~2 tokens in 4s (0.5/s).
```bash
./exp-ratelimit.sh
```

**VERIFY (not run here):** per-tenant KMS keys / crypto-shredding, cell routers, silo provisioners — named in the chapter;
the isolation, RLS, fairness and rate-limiting mechanics they build on are what these labs demonstrate on real engines.
