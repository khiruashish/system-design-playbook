# Chapter 46 Labs — Grocery Checkout & Inventory Reservation

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L1 needs PostgreSQL 16 on 127.0.0.1:5581 (db `shop`) and Redis 7 on :6390;
L4 needs PostgreSQL and `-Dsun.net.httpserver.nodelay=true`. Both need `postgresql-42.7.4.jar` and `-Duser.timezone=UTC`.
L2/L3 are deterministic simulations with stated behavioural assumptions. Timings vary by machine.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC HotSkuL1.java` | hotsku_out.txt | 5,000 units / 20,000 attempts: one row ~820/s p99 254 ms; 16 buckets 4.7x faster but 245k DB txns without sold-out flag; Redis gate 5,000 DB txns; oversold 0 |
| L2 | `java ReservationTtlL2.java` | ttl_out.txt | TTL 30 min: 1,621 unsold, 10,897 turned away; 3 min: 460 late captures; 3 min + extend on payment start: 9,993 sold, 123 late |
| L3 | `java PhantomStockL3.java` | phantom_out.txt | Missing-item orders 3.91% → 1.23% (flat buffer) → 0.50% (learned) → 0.40% (+ picker corrections); hidden units 0/2,160/1,509 |
| L4 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true WaitingRoomL4.java` | waiting_out.txt | 8,000 customers/2 s: no control 7,575 no answer, 1,838 silent buyers; fast 503 fair 31%; waiting room fair 92% |
