# Ch 54 labs — Distributed Key-Value Store

JDK 21, no dependencies.

| Lab | File | Run | Output |
|---|---|---|---|
| L1 Bitcask vs LSM vs Bloom vs compaction (real files, ~1 GB in /tmp/kvlab/data) | LsmL1.java | `java -Xmx3g LsmL1.java` | lsm_out.txt |
| L2 WAL ack policies vs SIGKILL (child JVM) | WalL2.java (2 classes) | `javac -d out WalL2.java && java -cp out WalL2` | wal_out.txt |
| L3 Merkle anti-entropy | MerkleL3.java | `java -Xmx3g MerkleL3.java` | merkle_out.txt |
| L4 R/W consistency levels and tails | QuorumL4.java | `java QuorumL4.java` | quorum_out.txt |

L2 writes logs under /tmp/kvlab. Power loss can't be simulated; see the lab header.
