# Ch 55 labs — Order Matching Engine

JDK 21, no dependencies. Run each with `java -Xmx3g -Dstdout.encoding=UTF-8 <File>.java`.

| Lab | File | Output |
|---|---|---|
| L1 order-book implementations, same trade stream (hash) | OrderBookL1.java | book_out.txt |
| L2 lock-based vs sequencer + journal + single matcher; replay | DeterminismL2.java | determinism_out.txt |
| L3 where the credit check runs (sim) | RiskL3.java | risk_out.txt |
| L4 market-data feeds under loss (sim) | MarketDataL4.java | md_out.txt |

L2 writes temporary journals under /tmp/melab.
