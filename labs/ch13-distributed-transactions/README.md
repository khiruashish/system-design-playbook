# Ch 13 labs — distributed transactions

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=`), a **non-root** user for the
database, Java 21+, and the PostgreSQL JDBC driver (`postgresql-42.7.x.jar`, from the pgjdbc GitHub releases or Maven Central).

```bash
mkdir -p /tmp/txlab && cp setup-postgres.sh /tmp/txlab/setup.sh && cp exp-*.sh /tmp/txlab/ && cd /tmp/txlab
./setup.sh                    # :5541 with databases orders, inventory, payments (wal_level=logical, prepared xacts on)
./exp-2pc.sh                  # PREPARE TRANSACTION, a NO vote, coordinator crash + server restart with locks held
./exp-cdc.sh                  # logical decoding: committed-only, commit order, slot WAL retention and heartbeat
```

Java programs (run from this folder; `-Duser.timezone=UTC` avoids a JVM time-zone name the server may not know):

```bash
java DualWriteL1.java
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar OutboxL2.java   # outbox + SKIP LOCKED relay + inbox (~15 s)
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar SagaL3.java     # saga across 3 DBs with crashes/timeouts (~15 s)
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar TccL4.java      # holds with expiry, guarded vs unguarded confirm (~25 s)
```

Each program drops and recreates its own tables. Stop: `/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/txlab/db stop -m fast`
