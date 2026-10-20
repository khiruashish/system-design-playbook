# Chapter 40 Labs — Notification System

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L2 needs PostgreSQL 16 on 127.0.0.1:5581 (db `shop`, user `claude`, trust auth)
and `postgresql-42.7.4.jar` (org.postgresql:postgresql:42.7.4); add `-Duser.timezone=UTC`. L1/L3/L4 are deterministic;
L2 fault injection is per-thread random, so its counts vary slightly. Traffic, vendor behaviour, costs and push reachability
are stated assumptions.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java PriorityLanesL1.java` | lanes_out.txt | 2.5M campaign + surge on 500 SMS/s: FIFO 100% OTPs expired; strict priority 130,467 campaign unsent at 21:00; weights 20/30/50 11.9% OTPs expired; separate 400/s promo route: all on time |
| L2 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC DeliveryL2.java` | delivery_out.txt | SKIP LOCKED job table, injected redeliveries/timeouts/crashes: duplicates 1,838 → 884 (intake key) → 0 (vendor key); 0 lost |
| L3 | `java VendorRoutingL3.java` | routing_out.txt | Silent vendor failure: error-based failover 3.9% on time; DLR-health routing 90% (shift after 42 s); + resend 98.9% |
| L4 | `java PolicyEngineL4.java` | policy_out.txt | 100k customers/day: SMS ₹50,971 → ₹11,067; users >10/day 54.9% → 2.7%; critical seen ≤2 min ~99% |
