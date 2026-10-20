# Ch 56 labs — Capstone: The Grocery Marketplace, End to End

JDK 21. Add `-Dstdout.encoding=UTF-8` on Windows consoles.

| Lab | File | Run | Output |
|---|---|---|---|
| L1 place-order latency budget (sim) | LatencyBudgetL1.java | `java LatencyBudgetL1.java` | budget_out.txt |
| L2 checkout saga under failure (REAL HTTP + PostgreSQL :5581 db `shop`) | CheckoutSagaL2.java | `java -Dsun.net.httpserver.nodelay=true -Duser.timezone=UTC -cp postgresql-42.7.4.jar CheckoutSagaL2.java` | saga_out.txt |
| L3 blast radius on the 26-service map | BlastRadiusL3.java | `java BlastRadiusL3.java` | blast_out.txt |
| L4 SLO composition and cells (sim) | SloCompositionL4.java | `java SloCompositionL4.java` | slo_out.txt |

L2 drops/creates tables stock, reservation, payment, orders, saga in the target database. Counts vary slightly run to run.
