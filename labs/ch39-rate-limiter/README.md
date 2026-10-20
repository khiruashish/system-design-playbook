# Chapter 39 Labs — Distributed Rate Limiter

JDK 21, no dependencies. Run with `-Dstdout.encoding=UTF-8`. L1 and L2 need Redis 7 on 127.0.0.1:6390
(`redis-server --port 6390 --save '' --appendonly no`). L2 passes a simulated clock into Lua, so its decisions are reproducible;
only the measured Lua round-trip varies. L3/L4 are deterministic simulations; attack parameters, SMS prices, attestation pass
rates and L4's 0.4% fallback refusal rate are stated assumptions.

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java LimiterStateL1.java` | state_out.txt | Real Redis memory per key: log 987 B, sliding counter 149, token bucket 123, GCRA 90; GCRA ≡ token bucket (0 diffs); sliding counter 4.3% wrong, 108 vs 60 in a true window |
| L2 | `java GlobalLimitL2.java` | global_out.txt | 1,000 req/s contract, 8 nodes, skewed 900 req/s: local splits reject 4,093; central GCRA 0 at 1.0 calls/req; adaptive leasing 0 at 0.17 |
| L3 | `java OtpAbuseL3.java` | otp_out.txt | SMS pumping: per-IP 47,175 attack SMS + 57.5% users blocked; per-phone 59,199 + 0%; range budgets + attestation 79 SMS (₹356) + 3 users |
| L4 | `java RegionsFailuresL4.java` | regions_out.txt | Static region splits reject 99k–167k in-contract; rebalancing 914; central over-admits 1,468/s in partition; outage: open ₹9,265 / closed 3,955 refused / local 30 + 16 |
