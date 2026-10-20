# Ch 27 labs: Reliability & Disaster Recovery

L1/L2/L4-A use real PostgreSQL 16 binaries (`/usr/lib/postgresql/16/bin`); run the .sh as a non-root OS user. L3/L4-B are Java 21.

| Level | Run | Output |
|---|---|---|
| L1 point-in-time recovery (WAL archiving + named restore point) | `./exp-pitr.sh` | `pitr_out.txt` |
| L2 streaming replication, RPO (async vs sync), failover | `./exp-replication.sh` (~30 s) | `repl_out.txt` |
| L3 chaos engineering (fault injection vs resilience) | `java -Dsun.net.httpserver.nodelay=true ChaosL3.java` | `chaos_out.txt` |
| L4a restore drill (catches truncated / orphaned backups) | `./exp-restoredrill.sh` | `restoredrill_out.txt` |
| L4b graceful degradation ladder | `java DegradeL4b.java` | `degrade_out.txt` |

The .sh scripts create clusters under /tmp/drlab on ports 5551–5571. They stop their servers at the end; if a run is
interrupted, `kill` leftover postgres processes by PID (never `pkill -f` with a pattern that matches your shell — exit 143/144).
Managed-service / Patroni YAML in the chapter is VERIFY; the PostgreSQL PITR and replication above are the real mechanisms.
