# Chapter 35 Labs — Cost Engineering & FinOps

JDK 21, no dependencies, deterministic output. **All prices are illustrative assumptions**, not current vendor rates —
the lessons are in the shapes and ratios. Run from this directory with `-Dstdout.encoding=UTF-8`.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java UnitCostL1.java bill.csv usage.csv` | unitcost_out.txt | ₹2.28 Cr bill → ₹0.76/order; driver vs even allocation moves up to ₹3.7 L/month between teams; 4.5% untagged; recommendations returns ~8.5x its cost |
| L2 | `java ScalingL2.java` | scaling_out.txt | 14 days, 5.3x festival spike: static-for-peak uses 4% of provisioned; normal-day static overloaded 120 min; reactive autoscaling 5 min overload at ~7% cost; + scheduled pre-scale 0 min |
| L3 | `java CommitL3.java` | commit_out.txt | Optimal commitment 37 pods (busy 62% ≥ 60% break-even), −23%; under demand uncertainty p90 commitment worst (₹35.7 L p95 regret); curve flat 0.85x–1.2x; growth skew favours upper end |
| L4 | `java StorageL4.java` | storage_out.txt | Measured: gzip 6.3x, binary 6.3x, binary+gzip 9.0x vs JSON. 3-yr retention: order events trivial; logs+clickstream ₹23.2 Cr hot/raw vs ₹29.3 L compressed+tiered; cross-zone small at this scale |
