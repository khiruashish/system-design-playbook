# Chapter 38 Labs — URL Shortener & SMS Short Links

JDK 21. Run with `-Dstdout.encoding=UTF-8`. L2 and L4 need PostgreSQL 16 on 127.0.0.1:5581, database `shop`, user `claude`
(trust auth), and `postgresql-42.7.4.jar` (Maven Central: org.postgresql:postgresql:42.7.4) in this folder. Quick start:
`docker run -d -p 5581:5432 -e POSTGRES_USER=claude -e POSTGRES_DB=shop -e POSTGRES_HOST_AUTH_METHOD=trust postgres:16`.
Outputs were recorded with durable commits (fsync on). L1/L3 are deterministic; L2/L4 timings vary by machine.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java CodeGenL1.java` | codegen_out.txt | 2M codes: MD5-6 40 collisions (35 predicted); random-7 2M lookups; counter 5 chars but 100% neighbour hits; counter+Feistel 0/0/0.00% |
| L2 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC -Dsun.net.httpserver.nodelay=true ShortenerL2.java` | shortener_out.txt | Idempotent create (same code on retry), https + allow-list validation, 302/410/404; 50k redirects: 85% cache hits, ~716 duplicate cold-miss reads; 11 sequence trips for 10,003 codes |
| L3 | `java ReadPathL3.java` | readpath_out.txt | LRU 1% → 58.5%, 10% → 77.7%; 20M-SMS campaign: hot shard 27,184/s (49x) → 570/s with 1 s near-cache; ~74M SMS saturates a shard |
| L4 | `java -cp postgresql-42.7.4.jar -Duser.timezone=UTC AnalyticsL4.java` | analytics_out.txt | UPDATE per click spread ~12k/s p99 ~11 ms; hot row ~2.3k/s p99 ~67 ms; micro-batch 0.01 ms, 20x fewer statements; HLL +0.41% vs summed uniques +294% |
