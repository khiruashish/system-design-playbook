# Ch 10 lab — PostgreSQL 16 streaming replication

Scripts used in Chapter 10 (outputs are quoted verbatim in the chapter).

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=` otherwise), run as a **non-root** user, lab directory `/tmp/pglab`.

```bash
mkdir -p /tmp/pglab && cp *.sh /tmp/pglab/ && cd /tmp/pglab
./setup.sh                          # primary :5501, replicas r1 :5502, r2 :5503
./exp1-lag-and-session-reads.sh     # replication lag, read-your-writes, monotonic reads
./exp3-quorum-sync.sh               # ANY 1 (r1, r2), blocked commit, "committed locally" warning
./exp4-split-brain-and-rewind.sh    # promote while primary alive, divergent writes, pg_rewind of p and r2
```

`exp4` changes the topology (r1 becomes primary). Re-run `setup.sh` to start over.
Stop everything with `for d in p r1 r2; do /usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pglab/$d stop -m fast; done`.
