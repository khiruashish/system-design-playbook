# Chapter 34 Labs — Modern Architecture Styles

JDK 21, no dependencies. HTTP labs start real JDK HttpServers on ephemeral localhost ports.
Always pass `-Dsun.net.httpserver.nodelay=true` for L1/L2/L4 (without it Nagle/delayed-ACK adds ~40 ms per request).

| Lab | Command | Output | What it shows |
|---|---|---|---|
| L1 | `java -Dsun.net.httpserver.nodelay=true HexagonalL1.java` | hexagonal_out.txt | Same domain, in-memory vs file+HTTP adapters: identical outcomes (1,980 paid / 20 declined), ~200-300x speed difference; reflection dependency check passes domain, catches a class holding HttpClient |
| L2 | `java -Dsun.net.httpserver.nodelay=true HopsL2.java` | hops_out.txt | 5 collaborator calls: ~11 µs in-process vs ~1.1 ms serial HTTP (~99x) vs ~0.5 ms fan-out; 6 required 99.9% deployables: 43 → 259 min/month downtime |
| L3 | `java CellsL3.java` | cells_out.txt | 10k simulated years: global 880 platform-min/yr; 8 cells + 90-min waves 205 (worst 100%, latent bugs); 8 cells + 12-h waves 110 (worst 12.5%) |
| L4 | `java -Dsun.net.httpserver.nodelay=true CellRouterL4.java` | cellrouter_out.txt | Real router + 3 cells, move Pune under live traffic: naive flip 0 errors but ~1.5k acknowledged items lost; hold–copy–flip loses 0, Pune held ~13 ms, Nagpur unaffected |

Timings (L1, L2, L4) vary per run; ratios and correctness results are stable. L3 is deterministic (fixed seed).
