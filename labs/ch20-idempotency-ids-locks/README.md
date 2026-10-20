# Ch 20 labs: Idempotency, IDs & Distributed Locks

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=` in `setup.sh`), a **non-root** user to run
the database, Redis 7 (`redis-server`), Java 21+, and the PostgreSQL JDBC driver (`postgresql-42.7.x.jar`, from pgjdbc GitHub releases).

```bash
mkdir -p /tmp/idlab && cp setup.sh /tmp/idlab/ && /tmp/idlab/setup.sh        # PostgreSQL :5542 (db shop), shared_buffers 32MB
redis-server --port 6390 --save '' --appendonly no --daemonize yes
```

| Level | Run | Output |
|---|---|---|
| L1 idempotent payment API (real HTTP + PG) | `java -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true -cp pgjdbc.jar IdempotencyL1.java` | `idem_out.txt` |
| L2 idempotent consumers (PG + Redis) | `java -Duser.timezone=UTC -cp pgjdbc.jar ConsumerL2.java` | `consumer_out.txt` |
| L3 ID schemes (PG insert benchmark + generators) | `java -Drows=4000000 -Duser.timezone=UTC -cp pgjdbc.jar IdsL3.java` (~2 min) | `ids_out.txt` |
| L3 negative control (single writer) | `java -Drows=1000000 -Dwriters=1 -Duser.timezone=UTC -cp pgjdbc.jar IdsL3.java A` | `ids_1writer.txt` |
| L4 distributed locks (PG + Redis) | `java -Duser.timezone=UTC -cp pgjdbc.jar LocksL4.java` | `locks_out.txt` |

Each program drops and recreates its own tables. Random pauses, crashes and lost responses make counts vary run to run; the shapes do not.
`-Duser.timezone=UTC` avoids a JVM time-zone name the server may not know. Stop: `pg_ctl -D /tmp/idlab/db stop -m fast; redis-cli -p 6390 shutdown`.
