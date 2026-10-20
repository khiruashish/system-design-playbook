# Chapter 41 Labs — Chat: Customer–Rider Messaging

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L2 needs PostgreSQL 16 on 127.0.0.1:5581 (db `shop`, user `claude`, trust auth),
Redis 7 on 127.0.0.1:6390, and `postgresql-42.7.4.jar`; add `-Duser.timezone=UTC`. L1/L3/L4 are deterministic simulations;
L2 is a real system (hand-written RFC 6455 servers, JDK WebSocket clients) and its timings vary.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java OrderingL1.java` | ordering_out.txt | Client-clock order: 59,998 replies before questions, 2,407 dups, 4,034 missing; server time: 539 own-order inversions, 4,083 missing; seq + ids + gap fetch: 0/0/0/0 |
| L2 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC WsChatL2.java` | wschat_out.txt | 2 WebSocket nodes + PostgreSQL + Redis pub/sub: 4,187 sends (187 retries) → 4,000 stored; 250 replayed on reconnect to other node; 0 dups/gaps/reorders |
| L3 | `java PresenceL3.java` | presence_out.txt | 60k riders: 5 s heartbeat 12,000 writes/s + 4.53 false offline/h; 20 s grace 0.55; derived from location 0 writes, 0.67, p95 34 s |
| L4 | `java ContactLeakL4.java` | leak_out.txt | Contact masking: regex 15.8% (100 FP), normalise 59.0% (200 FP), any-script + words + context 85.7% (0 FP); split/misspelt still leak |
