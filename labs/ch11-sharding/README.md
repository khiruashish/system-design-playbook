# Ch 11 labs — partitioning & sharding

Scripts used in Chapter 11 (outputs are quoted verbatim in the chapter).

## Redis 7 Cluster (Lab C)

Requirements: `redis-server` and `redis-cli` 7.x, `bc`. Uses `/tmp/rclab`, ports 7001–7004 (and 17001–17004 for the cluster bus).

```bash
./setup-redis.sh               # 3 masters, 16384 slots
./exp-c-redis-cluster.sh       # slots, MOVED, CROSSSLOT vs hash tags, one-slot migration (ASK/ASKING), add node + rebalance
```

Re-run `setup-redis.sh` before re-running the experiment (it adds node 7004 and moves slots).
Stop: `for p in 7001 7002 7003 7004; do redis-cli -p $p shutdown nosave; done`

## PostgreSQL 16 (Labs A and B)

Requirements: PostgreSQL 16 binaries at `/usr/lib/postgresql/16/bin` (adjust `B=`), run as a **non-root** user, directory `/tmp/shardlab`.

```bash
mkdir -p /tmp/shardlab && cp setup-postgres.sh /tmp/shardlab/setup.sh && cd /tmp/shardlab
./setup.sh                     # 5511 single node, 5521 shard A, 5522 shard B
../path/to/exp-a-partitioning.sh    # hash partitioning, pruning, PK must include the partition key
../path/to/exp-b-bucket-move.sh     # 64 buckets on 2 shards, fenced online move, stale writer refused
```

Stop: `for d in single shardA shardB; do /usr/lib/postgresql/16/bin/pg_ctl -D /tmp/shardlab/$d stop -m fast; done`
