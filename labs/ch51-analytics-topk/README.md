# Ch 51 labs — Real-time Analytics & Top-K

JDK 21, no dependencies. Run with `java -Dstdout.encoding=UTF-8 <File>.java` (L1 and L3 with `-Xmx3g`).

| Lab | File | Needs | Output |
|---|---|---|---|
| L1 heavy hitters: exact, Count-Min, Space-Saving, sampling | HeavyHittersL1.java | — | hh_out.txt |
| L2 merging top-K across partitions (incl. TPUT) | MergeTopKL2.java | — | merge_out.txt |
| L3 distinct counts: Redis SETs vs HyperLogLog | UniquesL3.java | Redis 7 :6390 (FLUSHALLs it!) | uniq_out.txt |
| L4 trending detection | TrendingL4.java | — | trend_out.txt |

Warning: L3 runs FLUSHALL on the Redis it connects to — use a scratch instance.
