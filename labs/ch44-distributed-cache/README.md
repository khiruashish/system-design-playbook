# Chapter 44 Labs — Distributed Cache

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L2–L4 need Redis 7 (`redis-server`, `redis-cli`, `redis-benchmark`) on the PATH;
L2 also expects a Redis on 127.0.0.1:6390. L3 builds its own cluster on 7001–7004 via `cluster-setup.sh`. L4 starts, stalls
(SIGSTOP) and kills (SIGKILL) its own Redis processes on 6391/6392 — Linux only. Timings and loss counts vary between runs.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java -Xmx3g EvictionL1.java` | eviction_out.txt | LRU / sampled LRU / decayed LFU / W-TinyLFU on Zipf, scan and shift traces: W-TinyLFU +9.1 pts at 10k slots; loses to LRU after a shift at 200k |
| L2 | `java -Xmx2g MiniCacheL2.java` | minicache_out.txt | Hand-written RESP server vs Redis under redis-benchmark: ~50k ops/s unpipelined, 260–410k pipelined; Redis ~2x; 212 vs 152 bytes/key |
| L3 | `./cluster-setup.sh && java ClusterL3.java` | cluster_out.txt | Real Redis Cluster: add 4th master, live rebalance → 25% of keys moved (modulo 75%); smart client 0 errors (3,126 MOVED), static client 11,706 errors |
| L4 | `java FailoverL4.java` | failover_out.txt | Async replication + replica stall + primary kill → thousands of acknowledged writes lost; WAIT 1 100 → 0 lost |
