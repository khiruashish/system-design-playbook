# Ch 15 labs — event sourcing & CQRS on PostgreSQL

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=`), a **non-root** user for the
database, Java 21+, PostgreSQL JDBC driver `postgresql-42.7.4.jar` in this folder.

```bash
mkdir -p /tmp/eslab && cp setup-postgres.sh /tmp/eslab/setup.sh && /tmp/eslab/setup.sh     # :5551, database "eventstore"
java EventStylesL1.java
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar EventStoreL2.java      # optimistic concurrency vs overdraft
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar ProjectionL3.java      # naive vs xid-safe projector checkpoints, rebuild
java -Duser.timezone=UTC -cp postgresql-42.7.4.jar SnapshotUpcastL4.java  # snapshots; exact vs double upcasting
```

Each program drops and recreates its tables. Stop: `/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/eslab/db stop -m fast`
