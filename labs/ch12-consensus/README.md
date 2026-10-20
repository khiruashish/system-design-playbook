# Ch 12 labs — consistency, time & consensus

## PostgreSQL 16: lease + fencing token (real)

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=`), a **non-root** user, directory `/tmp/fencelab`.

```bash
mkdir -p /tmp/fencelab && cp setup-postgres.sh /tmp/fencelab/setup.sh && cp exp-lease-and-fencing.sh /tmp/fencelab/exp.sh
cd /tmp/fencelab && ./setup.sh && ./exp.sh      # takes ~15 s (two runs with a 6 s "pause")
```

Stop: `/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/fencelab/db stop -m fast`

## etcd 3.5: elections, failover, quorum loss, CAS, watches (real)

Requirements: etcd 3.5 binaries (download `etcd-v3.5.x-linux-amd64.tar.gz` from the etcd GitHub releases and set
`ETCD_BIN` to the unpacked folder, default `/opt/etcd`), `python3`, and **root** for step 4 (it uses `iptables`
to partition one member, and removes the rules afterwards). The PostgreSQL part of step 2 runs if the lab DB
above is up on :5531, and is skipped otherwise.

```bash
mkdir -p /tmp/etcdlab && cp setup-etcd.sh exp-etcd.sh /tmp/etcdlab/ && cd /tmp/etcdlab
./setup-etcd.sh && ./exp-etcd.sh     # ~60 s
```

Stop: `pkill -x etcd`

## Simulations (plain Java 21+, no dependencies)

```bash
java ClocksL1.java            # wall vs Lamport vs vector vs HLC, wall vs monotonic timeouts
java LinearizabilityL2.java   # 20,000 histories per replication mode, checked for linearizability
java RaftSim.java 300         # Raft with faults: safety checks, two bug switches, Figure 8 script, read strategies (~15 s)
```
